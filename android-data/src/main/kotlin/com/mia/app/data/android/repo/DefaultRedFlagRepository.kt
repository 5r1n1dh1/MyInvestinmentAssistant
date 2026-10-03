package com.mia.app.data.android.repo

import com.mia.app.data.android.di.IoDispatcher
import com.mia.app.domain.model.FlagTab
import com.mia.app.domain.model.Sector
import com.mia.app.domain.model.Source
import com.mia.app.domain.model.Timeframe
import com.mia.app.domain.redflag.RedFlagContext
import com.mia.app.domain.redflag.RedFlagReport
import com.mia.app.domain.redflag.fundamentals.FundamentalsRedFlagEngine
import com.mia.app.domain.redflag.technical.TechnicalRedFlagRules
import com.mia.app.domain.technical.IndicatorSnapshot
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs the red-flag engines over what this device has cached.
 *
 * ### Determinism, restated
 * plan.md §8: *"All are deterministic threshold checks. No AI, no inference."* This class gathers
 * inputs and calls the domain engines; it computes no threshold. Two runs over the same cache
 * produce the same flags in the same order, which is what lets a stored detection key off a
 * stable rule id.
 *
 * ### Absence is only for a check that ran
 * plan.md §12's "silent-empty is indistinguishable from no red flags" is the failure this type is
 * shaped around. A rule that abstained — a bank with no `Debt/Equity` (plan.md §7.3), a series
 * with fewer than 200 bars — becomes a [com.mia.app.domain.redflag.RuleSkip] with a reason, and
 * [RedFlagView.complete] is false. Neither is ever rendered as a green tick.
 */
@Singleton
class DefaultRedFlagRepository @Inject constructor(
    private val fundamentalsRepository: FundamentalsRepository,
    private val candleRepository: CandleRepository,
    @IoDispatcher private val dispatcher: CoroutineDispatcher,
) : RedFlagRepository {

    override suspend fun flags(
        symbol: String,
        timeframe: Timeframe,
    ): DataResult<RedFlagView> = withContext(dispatcher) {
        val fundamentals = fundamentalsRepository.snapshot(symbol, allowFetch = false)
        val series = candleRepository.storedSeries(symbol, timeframe)

        if (fundamentals.valueOrNull() == null && series.valueOrNull() == null) {
            return@withContext DataResult.unavailable(
                failure = DataFailure(
                    reason = DataFailureReason.NO_DATA_CACHED,
                    message = "Neither a fundamentals page nor a $symbol price series is stored, so " +
                        "no red flag could be evaluated at all. The banner must say the checks could " +
                        "not run — an empty flag list here would read as \"this company is clean\" " +
                        "(plan.md §12).",
                    detail = "symbol=$symbol timeframe=${timeframe.name}",
                ),
                source = Source.DERIVED,
                fetchedOn = todayInMarket(),
            )
        }

        val redFlagView = evaluate(symbol, timeframe, fundamentals.valueOrNull(), series.valueOrNull())
        DataResult.ok(
            value = redFlagView,
            source = Source.DERIVED,
            fetchedOn = todayInMarket(),
            asOf = series.valueOrNull()?.asOf ?: fundamentals.valueOrNull()?.ratios?.asOf,
            notes = buildList {
                if (fundamentals.valueOrNull() == null) {
                    add(
                        "no fundamentals page is cached for $symbol, so the ten fundamentals checks " +
                            "are listed as not evaluated rather than as clear",
                    )
                }
                if (series.valueOrNull() == null) {
                    add("no $symbol price series is cached, so the technical checks could not run")
                }
            },
        )
    }

    override suspend fun fundamentalsFlags(symbol: String): DataResult<RedFlagView> =
        withContext(dispatcher) {
            val fundamentals = fundamentalsRepository.snapshot(symbol, allowFetch = false)
            val view = fundamentals.valueOrNull() ?: return@withContext DataResult.unavailable(
                failure = DataFailure(
                    reason = DataFailureReason.NO_DATA_CACHED,
                    message = fundamentals.failure?.message
                        ?: "No fundamentals page is cached for $symbol.",
                ),
                source = Source.SCREENER_IN,
                fetchedOn = todayInMarket(),
            )
            DataResult.ok(
                value = view.toFlagView(symbol, Timeframe.D1),
                source = Source.SCREENER_IN,
                fetchedOn = todayInMarket(),
                asOf = view.ratios.asOf,
            )
        }

    override suspend fun technicalFlags(
        symbol: String,
        timeframe: Timeframe,
    ): DataResult<RedFlagView> = withContext(dispatcher) {
        val series = candleRepository.storedSeries(symbol, timeframe)
        val candles = series.valueOrNull()?.ordered()
        if (candles == null || candles.isEmpty()) {
            return@withContext DataResult.unavailable(
                failure = DataFailure(
                    reason = DataFailureReason.NO_DATA_CACHED,
                    message = series.failure?.message
                        ?: "No $symbol price series is cached, so no technical red flag could be " +
                            "evaluated.",
                ),
                source = Source.NSE_BHAVCOPY,
                fetchedOn = todayInMarket(),
            )
        }
        val context = technicalContext(symbol, candles, timeframe, fundamentalsView = null)
        val engine = TechnicalRedFlagRules.engine()
        val report = engine.evaluate(context)
        val skipped = TechnicalRedFlagRules.skips(context)
        DataResult.ok(
            value = RedFlagView(
                symbol = symbol,
                timeframe = timeframe,
                flags = report.flags,
                skipped = skipped,
                failureReasons = report.failures,
                complete = report.failures.isEmpty() && skipped.isEmpty(),
                everyFlagCarriesEvidence = report.flags.all { it.evidence.isNotEmpty() },
            ),
            source = Source.NSE_BHAVCOPY,
            fetchedOn = todayInMarket(),
            asOf = candles.last().date,
        )
    }

    override suspend fun ruleIds(): List<String> = withContext(dispatcher) {
        FundamentalsRedFlagEngine().ruleIds + TechnicalRedFlagRules.all().map { it.id }
    }

    // --------------------------------------------------------------------------- internals

    /**
     * One engine over both families.
     *
     * plan.md §8.3 puts flags at the top of **both** tabs, so the fundamentals tab wants the
     * technical flags too and vice versa. `FundamentalsRedFlagEngine(extraRules = …)` is the
     * constructor that exists for exactly this wiring, and it is what fills in
     * [com.mia.app.domain.model.RuleSkip] for the fundamentals rules — the half of plan.md §12
     * that an empty flag list cannot express.
     */
    private fun evaluate(
        symbol: String,
        timeframe: Timeframe,
        fundamentals: FundamentalsView?,
        series: CandleSeries?,
    ): RedFlagView {
        val candles = series?.ordered().orEmpty()
        val context = technicalContext(symbol, candles, timeframe, fundamentals)
        val engine = FundamentalsRedFlagEngine(
            extraRules = TechnicalRedFlagRules.all(),
            // The `ratios` card series is not persisted: it is a per-fiscal-year series on the
            // page rather than a statement row. Passing null makes the two working-capital rules
            // abstain with a stated reason, which is correct — a flag that guessed at the card
            // would be a flag nobody could audit.
            ratiosCard = null,
        )
        val report: RedFlagReport = engine.evaluate(context)
        return report.toView(symbol, timeframe)
    }

    /**
     * The one `RedFlagContext` both engines read.
     *
     * Every field is nullable-tolerant by design (see `RedFlagContext`): fundamentals lag a
     * quarter and technicals need 200 bars, so a rule that cannot be evaluated says so.
     * [sector] stays [Sector.OTHER] when unresolved — plan.md §1.4 rule 8, never a guess.
     */
    private fun technicalContext(
        symbol: String,
        candles: List<com.mia.app.domain.model.Candle>,
        timeframe: Timeframe,
        fundamentalsView: FundamentalsView?,
    ): RedFlagContext {
        val ratios = fundamentalsView?.ratios
        val derived = fundamentalsView?.snapshot?.cashFlowDerived
        // `Ratios.asOf` is non-nullable in the domain model, so "there is no fundamentals page"
        // cannot be expressed as null. The strongest available signal is used instead: a sentinel
        // date that is 56 years stale, paired with `Confidence.STALE`, so any rule that consults
        // freshness abstains rather than reading today's date as current. No v1 rule reads
        // `RedFlagContext.hasFundamentals`, so nothing turns this into a fabricated figure.
        val absentRatios = ratios ?: com.mia.app.domain.model.Ratios(
            symbol = symbol,
            sector = Sector.OTHER,
            asOf = java.time.LocalDate.of(1970, 1, 1),
            source = Source.DERIVED,
            confidence = com.mia.app.domain.model.Confidence.STALE,
        )
        return RedFlagContext(
            symbol = symbol,
            sector = ratios?.sector ?: Sector.OTHER,
            ratios = absentRatios,
            derived = derived ?: com.mia.app.domain.model.CashFlowDerived(),
            quarters = fundamentalsView?.snapshot?.quarters.orEmpty(),
            candles = candles,
            timeframe = timeframe,
            indicators = if (candles.isEmpty()) null else IndicatorSnapshot.of(candles, null),
            today = todayInMarket(),
        )
    }

    private fun RedFlagReport.toView(symbol: String, timeframe: Timeframe): RedFlagView =
        RedFlagView(
            symbol = symbol,
            timeframe = timeframe,
            flags = flags,
            skipped = skipped,
            failureReasons = failures,
            complete = failures.isEmpty() && skipped.isEmpty(),
            everyFlagCarriesEvidence = flags.all { it.evidence.isNotEmpty() },
        )

    /** `FundamentalsView` narrowed to just what a flag banner needs. */
    private suspend fun FundamentalsView.toFlagView(symbol: String, timeframe: Timeframe): RedFlagView {
        val candles = candleRepository.storedSeries(symbol, timeframe).valueOrNull()?.ordered().orEmpty()
        val context = technicalContext(symbol, candles, timeframe, this)
        val engine = FundamentalsRedFlagEngine()
        val report = engine.evaluate(context)
        return RedFlagView(
            symbol = symbol,
            timeframe = timeframe,
            flags = report.flags.filter { it.tab != FlagTab.TECHNICALS },
            skipped = report.skipped,
            failureReasons = report.failures,
            complete = report.failures.isEmpty() && report.skipped.isEmpty(),
            everyFlagCarriesEvidence = report.flags.all { it.evidence.isNotEmpty() },
        )
    }
}