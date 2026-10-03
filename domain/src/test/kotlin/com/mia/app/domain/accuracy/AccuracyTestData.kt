package com.mia.app.domain.accuracy

import com.mia.app.domain.model.Candle
import com.mia.app.domain.model.Category
import com.mia.app.domain.model.Direction
import com.mia.app.domain.model.Match
import com.mia.app.domain.model.MeasuredMove
import com.mia.app.domain.model.OutcomeTrackerPolicy
import com.mia.app.domain.model.PatternOutcome
import com.mia.app.domain.model.Source
import com.mia.app.domain.model.Timeframe
import java.time.LocalDate
import kotlin.math.abs

/**
 * Hand-built inputs for the tests that need exact geometry.
 *
 * Everything here is derived from a formula rather than pasted from the fixture, so an assertion
 * can be re-checked on paper: closes are integers, levels are halves, dates are consecutive days.
 */
internal object AccuracyTestData {

    val SYMBOL: String = "TEST"
    val PATTERN: String = "Double bottom"

    /** 2026-01-01 + i days. Consecutive days keep "bars after the projection" unambiguous. */
    fun date(i: Int): LocalDate = LocalDate.of(2026, 1, 1).plusDays(i.toLong())

    /**
     * A structurally valid bar around [close], with [pad] of range on both sides.
     *
     * `open = close` keeps the arithmetic trivial and `high/low` symmetric, so `high >= level`
     * tests land exactly on the level rather than near it.
     */
    fun bar(
        i: Int,
        close: Double,
        symbol: String = SYMBOL,
        pad: Double = 1.0,
        timeframe: Timeframe = Timeframe.D1,
        deliveryPercent: Double? = null,
    ): Candle = Candle(
        symbol = symbol,
        timeframe = timeframe,
        date = date(i),
        open = close,
        high = close + pad,
        low = close - pad,
        close = close,
        volume = 1_000L,
        deliveryPercent = deliveryPercent,
        source = Source.NSE_BHAVCOPY,
    )

    /** [count] bars at [close], each with a range of [pad]. */
    fun flatBars(
        count: Int,
        close: Double = 100.0,
        startIndex: Int = 0,
        symbol: String = SYMBOL,
        timeframe: Timeframe = Timeframe.D1,
        deliveryPercent: (Int) -> Double? = { null },
    ): List<Candle> = List(count) { i ->
        bar(startIndex + i, close, symbol = symbol, timeframe = timeframe, deliveryPercent = deliveryPercent(i))
    }

    /** A match with a measured move: the only shape [OutcomeTracker.record] accepts. */
    fun match(
        name: String = PATTERN,
        score: Int = 75,
        projectedLevel: Double = 110.0,
        direction: Direction = Direction.UP,
        detectedOn: LocalDate = date(0),
        patternId: Long = 1L,
        height: Double = 10.0,
        timeframe: Timeframe = Timeframe.D1,
        category: Category = Category.REVERSAL,
    ): Match = Match(
        patternId = patternId,
        name = name,
        score = score,
        measuredMove = MeasuredMove(
            height = height,
            triggerLevel = projectedLevel,
            direction = direction,
            projectedLevel = projectedLevel,
        ),
        category = category,
        timeframe = timeframe,
        detectedOn = detectedOn,
    )

    /** A candlestick match: scored, but with no measured move (spec DD-14). */
    fun candlestickMatch(
        name: String = "Hammer",
        score: Int = 80,
        detectedOn: LocalDate = date(0),
    ): Match = Match(
        patternId = 9L,
        name = name,
        score = score,
        measuredMove = null,
        category = Category.CANDLESTICK,
        timeframe = Timeframe.D1,
        detectedOn = detectedOn,
    )

    /** A pending outcome, as [OutcomeTracker.record] would have produced it. */
    fun outcome(
        symbol: String = SYMBOL,
        patternName: String = PATTERN,
        projectedOn: LocalDate = date(0),
        projectedLevel: Double = 110.0,
        direction: Direction = Direction.UP,
        score: Int = 75,
        horizonBars: Int = OutcomeTrackerPolicy.DEFAULT_HORIZON_BARS,
        patternId: Long = 1L,
        resolved: Boolean = false,
        hit: Boolean? = null,
        actualHigh: Double? = null,
        actualLow: Double? = null,
    ): PatternOutcome = PatternOutcome(
        patternId = patternId,
        symbol = symbol,
        patternName = patternName,
        timeframe = Timeframe.D1,
        category = Category.REVERSAL,
        projectedOn = projectedOn,
        score = score,
        direction = direction,
        projectedLevel = projectedLevel,
        actualHigh = actualHigh,
        actualLow = actualLow,
        hit = hit,
        resolved = resolved,
        horizonBars = horizonBars,
    )

    /**
     * [count] settled outcomes for one symbol and pattern: [hits] reached their level and the
     * rest did not, projected on consecutive days before [asOf].
     *
     * Used by the reporting-gate tests, where the exact (hits, samples) pair is the subject.
     */
    fun resolvedOutcomes(
        count: Int,
        hits: Int,
        symbol: String = SYMBOL,
        patternName: String = PATTERN,
        asOf: LocalDate = date(1),
        horizonBars: Int = OutcomeTrackerPolicy.DEFAULT_HORIZON_BARS,
    ): List<PatternOutcome> = List(count) { i ->
        outcome(
            symbol = symbol,
            patternName = patternName,
            projectedOn = asOf.minusDays((count - i).toLong()),
            patternId = i.toLong(),
            resolved = true,
            hit = i < hits,
            actualHigh = if (i < hits) 110.0 else 109.0,
            actualLow = 100.0,
        )
    }
}

/**
 * A forward walk over real NSE bars that records and settles one projection per step.
 *
 * The point is not to detect patterns — P8 owns that — but to put a stand-in detector in front of
 * [OutcomeTracker] so the track-record machinery runs over genuine prices: a measured move from
 * the height of the last [LOOKBACK] bars, projected from the close, and then checked against the
 * following [HORIZON] bars.
 *
 * Windows are walked on a [STRIDE]-bar step so no two outcomes share a bar: overlapping windows
 * would count the same price move as several independent samples and flatter the hit rate.
 *
 * The 15 symbols are the ones whose 372-bar history contains no synthetic-looking discontinuity
 * (see `TatamotorsCorporateActionTest` for the demerger, and the report for the two half-scaled
 * series in the fixture).
 */
internal object RealHistorySimulation {

    val SYMBOLS: List<String> = listOf(
        "RELIANCE", "TCS", "INFY", "ICICIBANK", "SBIN",
        "BHARTIARTL", "ITC", "LT", "SUNPHARMA", "TITAN",
        "AXISBANK", "MARUTI", "ASIANPAINT", "POWERGRID", "NTPC",
    )

    /** Bars in the formation whose high-low height becomes the measured move. */
    const val LOOKBACK: Int = 5

    /**
     * First bar the walk may project on.
     *
     * plan.md §6.4 makes 60 daily bars a hard gate before a symbol is even scored, so projecting on
     * the tenth bar of a series is not a situation the app can be in.
     */
    const val FIRST_BAR: Int = 60

    /** Bars allowed for the level to be reached. plan.md §6.6: 1–5 bars. */
    const val HORIZON: Int = 5

    /** Step between consecutive projections, in bars. Equal to [HORIZON]: no overlap. */
    const val STRIDE: Int = 5

    /** Formation names cycled through so every grouping in the report has data. */
    val PATTERNS: List<String> = listOf("Bull flag", "Ascending triangle", "Head & shoulders")

    /** One projection made during the walk, kept so the tests can reason about geometry. */
    data class Sample(
        val symbol: String,
        val patternName: String,
        val barIndex: Int,
        val close: Double,
        val projectedLevel: Double,
        val hit: Boolean,
    ) {
        /** True when the full [HORIZON] bars after [barIndex] existed in the fixture. */
        val hasFullHorizon: Boolean
            get() = barIndex + HORIZON < EodHistoryFixture.EXPECTED_FULL_BARS

        /** How far the projected level sits from the close, in percent of price. */
        val distancePercent: Double get() = abs(projectedLevel - close) / close * 100.0
    }

    /** Everything the walk produced. */
    data class SimulationResult(
        val outcomes: List<PatternOutcome>,
        val samples: List<Sample>,
        val candlesBySymbol: Map<String, List<Candle>>,
        val today: LocalDate,
    ) {
        val resolved: List<PatternOutcome> get() = outcomes.filter { it.resolved }
        val pending: List<PatternOutcome> get() = outcomes.filterNot { it.resolved }
    }

    /** Walks every symbol in [SYMBOLS] once and settles what has settled. */
    fun run(): SimulationResult {
        val today = EodHistoryFixture.LAST_DATE
        val candlesBySymbol = SYMBOLS.associateWith { EodHistoryFixture.candles(it) }
        val samples = ArrayList<Sample>()
        val outcomes = ArrayList<PatternOutcome>()

        candlesBySymbol.entries.forEachIndexed { symbolIndex, entry ->
            val symbol = entry.key
            val candles = entry.value
            var barIndex = FIRST_BAR
            var patternId = 0L
            while (barIndex < candles.size) {
                val formation = candles.subList(barIndex - LOOKBACK + 1, barIndex + 1)
                val height = formation.maxOf { it.high } - formation.minOf { it.low }
                val close = candles[barIndex].close
                val rising = close > formation.first().close
                val direction = if (rising) Direction.UP else Direction.DOWN
                val level = if (rising) close + height else close - height
                val patternName = PATTERNS[(symbolIndex + barIndex / STRIDE) % PATTERNS.size]

                val match = Match(
                    patternId = patternId++,
                    name = patternName,
                    // Deterministic spread inside the project band, so the record is not a
                    // single repeated score. The tracker gates on >= 50, not on quality.
                    score = 70 + ((barIndex + symbolIndex * 3) % 26),
                    measuredMove = MeasuredMove(
                        height = height,
                        triggerLevel = close,
                        direction = direction,
                        projectedLevel = level,
                    ),
                    category = if (patternName == "Head & shoulders") {
                        Category.REVERSAL
                    } else {
                        Category.CONTINUATION
                    },
                    timeframe = Timeframe.D1,
                    detectedOn = candles[barIndex].date,
                )
                val outcome = OutcomeTracker.record(match, symbol, candles[barIndex].date, HORIZON)
                outcomes += outcome

                val window = candles.drop(barIndex + 1).take(HORIZON)
                samples += Sample(
                    symbol = symbol,
                    patternName = patternName,
                    barIndex = barIndex,
                    close = close,
                    projectedLevel = level,
                    hit = window.size == HORIZON && OutcomeTrackerPolicy.isHit(outcome, window, HORIZON),
                )
                barIndex += STRIDE
            }
        }
        return SimulationResult(
            outcomes = OutcomeTracker.evaluateAll(outcomes, candlesBySymbol, today),
            samples = samples,
            candlesBySymbol = candlesBySymbol,
            today = today,
        )
    }
}