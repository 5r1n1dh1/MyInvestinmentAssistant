package com.mia.app.data.android.repo

import com.mia.app.data.android.db.CandleDao
import com.mia.app.data.android.db.Mappers
import com.mia.app.data.android.db.PatternDao
import com.mia.app.data.android.di.IoDispatcher
import com.mia.app.domain.accuracy.AccuracyProjector
import com.mia.app.domain.accuracy.HitRateCalculator
import com.mia.app.domain.accuracy.OutcomeTracker
import com.mia.app.domain.accuracy.TrackRecordContext
import com.mia.app.domain.model.AccuracyReport
import com.mia.app.domain.model.Candle
import com.mia.app.domain.model.Match
import com.mia.app.domain.model.PatternOutcome
import com.mia.app.domain.model.Source
import com.mia.app.domain.model.Timeframe
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The app's own track record, over the stored `outcomes` table.
 *
 * ### The four rules this class exists to keep (plan.md §6.1, decision D6)
 *
 * 1. **Per stock AND per pattern.** `AccuracyReport` carries both, and `trackRecord` returns the
 *    per-stock cell next to a formation plus the cross-stock one when the local cell is thin.
 * 2. **Below 30 samples, withhold.** `HitRate.isReportable` decides this inside the domain; this
 *    class never computes a percentage itself.
 * 3. **A verdict is written once.** `OutcomeTracker.evaluate` returns a resolved outcome
 *    unchanged, so re-running [settlePending] cannot move `hit` from true to false. The guarantee
 *    is structural, not a promise in a KDoc.
 * 4. **A window is counted in sessions, not days.** "Has the horizon elapsed" is answered by
 *    `OutcomeTracker.dueForEvaluation`, which needs the bars; guessing a session calendar would
 *    be fabricating data.
 */
@Singleton
class DefaultAccuracyRepository @Inject constructor(
    private val patternDao: PatternDao,
    private val candleDao: CandleDao,
    @IoDispatcher private val dispatcher: CoroutineDispatcher,
) : AccuracyRepository {

    override suspend fun report(today: LocalDate): DataResult<AccuracyReport> =
        withContext(dispatcher) {
            val outcomes = readOutcomes()
            if (outcomes.isEmpty()) {
                return@withContext DataResult.unavailable(
                    failure = DataFailure(
                        reason = DataFailureReason.NO_DATA_CACHED,
                        message = "No pattern outcome has been tracked yet, so there is no hit rate to " +
                            "show. plan.md §6.1 requires the number to be withheld below " +
                            "${com.mia.app.domain.model.MatchScorePolicy.MIN_HIT_RATE_SAMPLES} samples, " +
                            "and an empty table must read as \"nothing tracked\", never as 0 %.",
                    ),
                    source = Source.DERIVED,
                    fetchedOn = today,
                )
            }
            // A projection dated after the reporting date cannot have happened. `HitRateCalculator`
            // fails loudly on one rather than dropping it, which is the behaviour to keep.
            val safe = outcomes.filter { !it.projectedOn.isAfter(today) }
            DataResult.ok(
                value = HitRateCalculator.report(safe, today),
                source = Source.DERIVED,
                fetchedOn = today,
                asOf = safe.maxOf { it.projectedOn },
                notes = buildList {
                    add("${safe.size} tracked outcome(s), ${HitRateCalculator.unresolvedCount(safe)} still open")
                    add("hit rates below ${com.mia.app.domain.model.MatchScorePolicy.MIN_HIT_RATE_SAMPLES} " +
                        "samples are withheld and shown as (n of ${com.mia.app.domain.model.MatchScorePolicy.MIN_HIT_RATE_SAMPLES})")
                    add("a hit means the measured-move level was touched inside the horizon — a chart " +
                        "event, never a profit (plan.md §6.1)")
                },
            )
        }

    override suspend fun trackRecord(
        symbol: String,
        patternName: String,
        today: LocalDate,
    ): DataResult<TrackRecordContext> = withContext(dispatcher) {
        val history = readOutcomes().filter { !it.projectedOn.isAfter(today) }
        val context = AccuracyProjector.context(
            symbol = symbol,
            patternName = patternName,
            history = history,
            asOf = today,
        )
        DataResult.ok(
            value = context,
            source = Source.DERIVED,
            fetchedOn = today,
            asOf = today,
            notes = listOf(context.evidence),
        )
    }

    override suspend fun recordProjections(
        symbol: String,
        timeframe: Timeframe,
        asOf: LocalDate,
    ): DataResult<AccuracyPass> = withContext(dispatcher) {
        val rows = patternDao.matches(symbol, Mappers.timeframeKey(timeframe))
        val alreadyTracked = patternDao.allOutcomes().map { it.patternId }.toSet()
        var recorded = 0
        val rejected = mutableListOf<String>()

        for (row in rows) {
            if (row.id in alreadyTracked) continue
            val match: Match = Mappers.toMatch(row, patternDao.pivots(row.id))
            val rejection = OutcomeTracker.rejectionReason(match)
            if (rejection != null) {
                rejected += "${match.name}: ${rejection.substringAfter(": ")}"
                continue
            }
            if (match.detectedOn.isAfter(asOf)) continue
            val outcome = runCatching { OutcomeTracker.record(match, symbol, asOf) }.getOrNull()
            if (outcome == null) continue
            patternDao.insertOutcome(Mappers.fromOutcome(row.id, outcome))
            recorded++
        }

        val open = patternDao.unresolvedOutcomes().size
        DataResult.ok(
            value = AccuracyPass(
                recorded = recorded,
                stillPending = open,
                settled = 0,
                asOf = asOf,
                notes = buildList {
                    add("$recorded projection(s) opened for $symbol on ${timeframe.name}")
                    if (rejected.isNotEmpty()) {
                        add(
                            "${rejected.size} detection(s) cannot be tracked: " +
                                rejected.take(MAX_REJECTION_NOTES).joinToString("; "),
                        )
                    }
                },
            ),
            source = Source.DERIVED,
            fetchedOn = asOf,
            asOf = asOf,
        )
    }

    override suspend fun settlePending(today: LocalDate): DataResult<AccuracyPass> =
        withContext(dispatcher) {
            val outcomes = patternDao.unresolvedOutcomes().map { Mappers.toOutcome(it) }
            if (outcomes.isEmpty()) {
                return@withContext DataResult.ok(
                    value = AccuracyPass(
                        recorded = 0,
                        stillPending = 0,
                        settled = 0,
                        asOf = today,
                        notes = listOf("no projection is awaiting a verdict"),
                    ),
                    source = Source.DERIVED,
                    fetchedOn = today,
                    asOf = today,
                )
            }

            // Keyed by (symbol, timeframe): a symbol can have both a daily and a weekly
            // projection open, and an outcome must only ever be judged on its own timeframe's
            // bars.
            val barsByKey: Map<Pair<String, Timeframe>, List<Candle>> =
                outcomes.map { it.symbol to it.timeframe }.distinct()
                    .associateWith { (symbol, timeframe) -> candlesFor(symbol, timeframe) }

            var settled = 0
            for (outcome in outcomes) {
                val bars = barsByKey[outcome.symbol to outcome.timeframe].orEmpty()
                val due = OutcomeTracker.isDue(outcome, bars, today)
                val evaluated = OutcomeTracker.evaluate(outcome, bars, today)
                if (!due && evaluated == outcome) continue
                patternDao.insertOutcome(Mappers.fromOutcome(outcome.patternId, evaluated))
                if (evaluated.resolved && evaluated.hit != null) settled++
            }

            val stillOpen = patternDao.unresolvedOutcomes().size
            DataResult.ok(
                value = AccuracyPass(
                    recorded = 0,
                    stillPending = stillOpen,
                    settled = settled,
                    asOf = today,
                    notes = listOf(
                        "$settled verdict(s) written as of $today; a verdict is never revised " +
                            "afterwards (plan.md §6.1)",
                        "$stillOpen projection(s) still awaiting their full evaluation window",
                    ),
                ),
                source = Source.DERIVED,
                fetchedOn = today,
                asOf = today,
            )
        }

    override suspend fun pending(today: LocalDate): DataResult<List<PatternOutcome>> =
        withContext(dispatcher) {
            val open = OutcomeTracker.pending(readOutcomes(), today)
            DataResult.ok(
                value = open,
                source = Source.DERIVED,
                fetchedOn = today,
                asOf = today,
                notes = listOf("${open.size} projection(s) awaiting a verdict"),
            )
        }

    override suspend fun outcomeFor(
        symbol: String,
        patternId: Long,
    ): DataResult<PatternOutcome> = withContext(dispatcher) {
        val found = readOutcomes().firstOrNull { it.symbol == symbol && it.patternId == patternId }
        if (found == null) {
            return@withContext DataResult.unavailable(
                failure = DataFailure(
                    reason = DataFailureReason.NO_DATA_CACHED,
                    message = "No outcome has been opened for $symbol's pattern $patternId. A " +
                        "projection is only tracked once the app displayed it, and only when it has " +
                        "a measured-move level to observe (plan.md §6.1).",
                    detail = "symbol=$symbol patternId=$patternId",
                ),
                source = Source.DERIVED,
                fetchedOn = todayInMarket(),
            )
        }
        DataResult.ok(
            value = found,
            source = Source.DERIVED,
            fetchedOn = found.projectedOn,
            asOf = found.projectedOn,
        )
    }

    // --------------------------------------------------------------------------- internals

    private suspend fun readOutcomes(): List<PatternOutcome> =
        patternDao.allOutcomes().map { Mappers.toOutcome(it) }

    private suspend fun candlesFor(symbol: String, timeframe: Timeframe): List<Candle> =
        candleDao.all(symbol, Mappers.timeframeKey(timeframe))
            .map { Mappers.toCandle(it) }
            .sortedBy { it.date }

    private companion object {
        /** Bounded so a watchlist of 40 rejected detections cannot produce a 4 KB note. */
        const val MAX_REJECTION_NOTES = 3
    }
}