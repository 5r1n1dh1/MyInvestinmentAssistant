package com.mia.app.data.android.repo

import com.mia.app.data.android.db.CandleDao
import com.mia.app.data.android.db.JournalDao
import com.mia.app.data.android.db.JournalMappers
import com.mia.app.data.android.db.Mappers
import com.mia.app.data.android.db.PatternDao
import com.mia.app.data.android.di.IoDispatcher
import com.mia.app.domain.journal.JournalEntry
import com.mia.app.domain.journal.JournalResolver
import com.mia.app.domain.model.Candle
import com.mia.app.domain.model.HitRate
import com.mia.app.domain.model.Source
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The reader's journal over `journal_entry`, `candles` and `outcomes` (gap E5).
 *
 * ### Why this reads three tables
 * Each read answers a different question and none of the three is enough alone:
 *
 * * `journal_entry` — what the reader wrote, and when. The only table here a person typed into.
 * * `candles` — what the price actually did. The move in a resolution is arithmetic on stored
 *   closes, and it is computed only from bars dated on or before `today`, so an entry can never be
 *   "resolved" with a session the device had not published yet.
 * * `outcomes` — whether the app's own record has moved on for that symbol. This is the gate that
 *   keeps the journal off a second resolution path: `JournalResolver` refuses to close an entry
 *   until the engine has settled at least one of its own outcomes for the same symbol, so "the
 *   review period has passed" means the same thing in both records.
 *
 * ### Why nothing here computes a rate
 * The tally belongs to `JournalLedger` in `:domain`, and this class only supplies entries. A
 * percentage computed in the storage layer would be a second implementation of the 30-sample gate,
 * and the gate is the thing that must not be implemented twice.
 */
@Singleton
class DefaultJournalRepository @Inject constructor(
    private val journalDao: JournalDao,
    private val candleDao: CandleDao,
    private val patternDao: PatternDao,
    @IoDispatcher private val dispatcher: CoroutineDispatcher,
) : JournalRepository {

    override suspend fun entries(today: LocalDate): DataResult<List<JournalEntry>> =
        withContext(dispatcher) {
            val rows = journalDao.all().map(JournalMappers::toEntry)
            DataResult.ok(
                value = rows,
                source = JournalRepository.SOURCE,
                fetchedOn = today,
                asOf = rows.firstOrNull()?.createdAt,
                notes = notesFor(rows, today),
            )
        }

    override suspend fun entry(id: Long, today: LocalDate): DataResult<JournalEntry> =
        withContext(dispatcher) {
            val row = journalDao.byId(id)
                ?: return@withContext DataResult.unavailable(
                    failure = DataFailure(
                        reason = DataFailureReason.NOTHING_TO_DO,
                        message = "That journal entry is not on this device. It may have been " +
                            "deleted, or it may belong to another install.",
                        detail = "id=$id",
                    ),
                    fetchedOn = today,
                )
            DataResult.ok(
                value = JournalMappers.toEntry(row),
                source = JournalRepository.SOURCE,
                fetchedOn = today,
                asOf = row.createdAt.let(LocalDate::parse),
            )
        }

    override suspend fun symbols(): DataResult<List<String>> = withContext(dispatcher) {
        DataResult.ok(
            value = journalDao.symbols(),
            source = JournalRepository.SOURCE,
            fetchedOn = todayInMarket(),
            notes = listOf("symbols with at least one journal entry on this device"),
        )
    }

    override suspend fun record(entry: JournalEntry, today: LocalDate): DataResult<JournalEntry> =
        withContext(dispatcher) {
            // Normalised here as well as in `JournalDraft`: this is the last point before storage,
            // and a `reliance` row would split one reader's history across two symbols in every
            // query that follows.
            val stored = entry.copy(symbol = entry.symbol.trim().uppercase(), id = 0L)
            val id = journalDao.insert(JournalMappers.fromEntry(stored))
            DataResult.ok(
                value = stored.copy(id = id),
                source = JournalRepository.SOURCE,
                fetchedOn = today,
                asOf = stored.createdAt,
                notes = listOf(
                    "the entry is stored as written and has no method to edit it; the only change " +
                        "the app can make afterwards is to record an outcome against it",
                ),
            )
        }

    override suspend fun delete(id: Long, today: LocalDate): DataResult<Int> = withContext(dispatcher) {
        val removed = journalDao.delete(id)
        if (removed == 0) {
            return@withContext DataResult.unavailable(
                failure = DataFailure(
                    reason = DataFailureReason.NOTHING_TO_DO,
                    message = "That journal entry is not on this device, so there was nothing to " +
                        "delete.",
                    detail = "id=$id",
                ),
                fetchedOn = today,
            )
        }
        DataResult.ok(
            value = removed,
            source = JournalRepository.SOURCE,
            fetchedOn = today,
            notes = listOf("$removed entry removed from this device; it is not recoverable"),
        )
    }

    /**
     * Closes every entry whose window is observable, and reports the ones that are not.
     *
     * ### Why the reasons are collected rather than only counted
     * An entry that never closes is the failure mode a reader notices first, and "still open" with
     * no explanation reads as a bug in the app rather than as a threshold. [JournalResolver]'s
     * own sentences are passed through unchanged, so the screen states the gate that is still shut
     * instead of inventing a generic one — and the same sentence is used on the entry card, which
     * is where a reader looking for that entry will actually read it.
     */
    override suspend fun resolveDue(today: LocalDate): DataResult<JournalPass> = withContext(dispatcher) {
        val due = journalDao.dueForReview(today.toString())
        if (due.isEmpty()) {
            return@withContext DataResult.ok(
                value = JournalPass(resolved = 0, stillOpen = 0, asOf = today),
                source = JournalRepository.SOURCE,
                fetchedOn = today,
                asOf = today,
                notes = listOf("no journal entry is past its review date"),
            )
        }

        val settledSymbols = settledSymbols(due.map { it.symbol }.distinct())
        val candlesBySymbol = candlesByKey(due.map { "${it.symbol}|${it.timeframe}" }.distinct())

        var resolved = 0
        val reasons = mutableListOf<String>()
        for (row in due) {
            val entry = JournalMappers.toEntry(row)
            val candles = candlesBySymbol["${row.symbol}|${row.timeframe}"].orEmpty()
            val hasSettled = row.symbol in settledSymbols
            val reason = JournalResolver.blockingReason(entry, candles, hasSettled, today)
            if (reason != null) {
                if (reasons.size < MAX_REASONS) reasons += "${entry.symbol}: $reason"
                continue
            }
            val done = JournalResolver.resolve(entry, candles, hasSettled, today)
            val outcome = done.resolutionOutcome ?: continue
            val resolvedOn = done.resolvedAt ?: continue
            val move = done.resolutionChangePercent ?: continue
            // The DAO's own `WHERE resolutionOutcome IS NULL` guard makes this monotone even if
            // two passes race: the second write matches no row and this entry stays as written.
            if (journalDao.resolve(done.id, resolvedOn.toString(), outcome.name, move) > 0) resolved++
        }

        DataResult.ok(
            value = JournalPass(
                resolved = resolved,
                stillOpen = due.size - resolved,
                asOf = today,
                reasons = reasons,
            ),
            source = JournalRepository.SOURCE,
            fetchedOn = today,
            asOf = today,
            notes = listOf(
                "$resolved entry(s) closed from stored bars; a recorded outcome is never revised",
                "an entry closes only after its own review date and only once the app has settled " +
                    "at least one of its own pattern outcomes for that symbol",
            ),
        )
    }

    /**
     * The app's own record, aggregated — see [JournalRepository.appTrackRecord] for why it is here.
     *
     * Every outcome that carries a verdict contributes exactly once, because `HitRateCalculator`'s
     * groupings partition the same set rather than crossing it: `perPattern` sums its hits and
     * samples to the total over all resolved outcomes, which is what an "across everything" figure
     * has to be. The counts are taken from the stored `resolved`/`hit` flags rather than recomputed,
     * so this figure can never disagree with the Accuracy tab about how many verdicts exist.
     *
     * A device that has tracked nothing is `Ok` with a withheld `0/0` label, not a failure: "the
     * app has no record yet" is a true fact the journal states beside the reader's own, and turning
     * it into an error would put a red banner next to a personal record for no reason.
     */
    override suspend fun appTrackRecord(today: LocalDate): DataResult<AppTrackRecord> =
        withContext(dispatcher) {
            val settled = patternDao.allOutcomes().filter { it.resolved && it.hit != null }
            if (settled.isEmpty()) {
                return@withContext DataResult.ok(
                    value = AppTrackRecord.EMPTY,
                    source = Source.DERIVED,
                    fetchedOn = today,
                    asOf = today,
                    notes = listOf("the app has tracked no pattern outcomes on this device yet"),
                )
            }
            val samples = settled.size
            DataResult.ok(
                value = AppTrackRecord(
                    hitRate = HitRate(
                        symbol = null,
                        patternName = null,
                        hits = settled.count { it.hit == true },
                        samples = samples,
                    ),
                    settledOutcomes = samples,
                ),
                source = Source.DERIVED,
                fetchedOn = today,
                asOf = today,
                notes = listOf(
                    "$samples settled pattern outcome(s) on this device; a hit is a measured-move " +
                        "level being reached, never a profit (plan.md §6.1)",
                ),
            )
        }

    // --------------------------------------------------------------------------- internals

    override suspend fun settledOutcomeSymbols(today: LocalDate): DataResult<Set<String>> =
        withContext(dispatcher) {
            val symbols = patternDao.allOutcomes()
                .asSequence()
                .filter { it.resolved && it.hit != null }
                .map { it.symbol }
                .toSet()
            DataResult.ok(
                value = symbols,
                source = Source.DERIVED,
                fetchedOn = today,
                asOf = today,
                notes = listOf("${symbols.size} symbol(s) have at least one settled outcome"),
            )
        }

    /**
     * Symbols with at least one **settled** outcome — the engine's own `resolved = 1` flag, read
     * rather than recomputed, so this gate can never disagree with the Accuracy tab about whether a
     * verdict exists.
     */
    private suspend fun settledSymbols(symbols: List<String>): Set<String> =
        patternDao.allOutcomes()
            .asSequence()
            .filter { it.resolved && it.hit != null }
            .map { it.symbol }
            .filter { it in symbols }
            .toSet()

    /** Bars per `symbol|timeframe`, dated on or before [today] only. */
    private suspend fun candlesByKey(keys: List<String>): Map<String, List<Candle>> {
        val out = HashMap<String, List<Candle>>(keys.size)
        for (key in keys) {
            val separator = key.indexOf('|')
            if (separator <= 0) continue
            val symbol = key.substring(0, separator)
            val timeframe = Mappers.timeframeOrDefault(key.substring(separator + 1))
            out[key] = candleDao.all(symbol, Mappers.timeframeKey(timeframe))
                .map(Mappers::toCandle)
                .sortedBy { it.date }
        }
        return out
    }

    private fun notesFor(rows: List<JournalEntry>, today: LocalDate): List<String> = buildList {
        add("${rows.size} entry(s) written on this device")
        val due = rows.count { it.isDueForReview(today) }
        if (due > 0) add("$due entry(s) are past their review date")
    }

    private companion object {
        /** Bounded so twenty open entries cannot produce a 4 KB note. */
        const val MAX_REASONS = 5
    }
}