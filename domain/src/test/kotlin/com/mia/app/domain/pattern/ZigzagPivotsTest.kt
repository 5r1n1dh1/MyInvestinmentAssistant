package com.mia.app.domain.pattern

import com.mia.app.domain.model.Candle
import com.mia.app.domain.model.Pivot
import com.mia.app.domain.model.PivotKind
import com.mia.app.domain.model.Source
import com.mia.app.domain.model.Timeframe
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * plan.md §12 property tests for the shared pivot extractor, plus a run over the real
 * `eod_history.json` fixture.
 */
class ZigzagPivotsTest {

    // ------------------------------------------------------------------ plan.md §12 properties

    @Test
    fun `zigzag never emits two consecutive same-kind pivots`() {
        for (series in deterministicSeries()) {
            val pivots = compute(series, Timeframe.D1)
            for (i in 1 until pivots.size) {
                assertTrue(
                    pivots[i].kind != pivots[i - 1].kind,
                    "same-kind neighbours at $i in ${pivots[i]} / ${pivots[i - 1]}",
                )
            }
        }
    }

    @Test
    fun `zigzag bar indices are strictly increasing`() {
        for (series in deterministicSeries()) {
            val pivots = compute(series, Timeframe.D1)
            for (i in 1 until pivots.size) {
                assertTrue(
                    pivots[i].barIndex > pivots[i - 1].barIndex,
                    "bar index did not increase at $i: ${pivots[i - 1].barIndex} then ${pivots[i].barIndex}",
                )
            }
        }
    }

    @Test
    fun `zigzag leg index equals the pivot's own index`() {
        for (series in deterministicSeries()) {
            val pivots = compute(series, Timeframe.D1)
            pivots.pivots.forEachIndexed { index, pivot ->
                assertEquals(index, pivot.legIndex, "legIndex must equal the position in the list")
            }
        }
    }

    @Test
    fun `every pivot price is the high or low of its own candle`() {
        for (series in deterministicSeries()) {
            val pivots = compute(series, Timeframe.D1)
            for (pivot in pivots.pivots) {
                val candle = series[pivot.barIndex]
                when (pivot.kind) {
                    PivotKind.HIGH -> assertEquals(candle.high, pivot.price, 1e-9, "HIGH must be the bar high")
                    PivotKind.LOW -> assertEquals(candle.low, pivot.price, 1e-9, "LOW must be the bar low")
                }
            }
        }
    }

    @Test
    fun `a flat series yields zero pivots`() {
        val flat = List(200) { i ->
            Candle("FLAT", Timeframe.D1, date(i), 100.0, 100.5, 99.5, 100.0, 1_000L)
        }
        assertEquals(0, ZigzagPivots.compute(flat, Timeframe.D1).size)

        val zeroRange = List(200) { i ->
            Candle("FLAT", Timeframe.D1, date(i), 100.0, 100.0, 100.0, 100.0, 1_000L)
        }
        assertEquals(0, ZigzagPivots.compute(zeroRange, Timeframe.D1).size)
    }

    @Test
    fun `identical input yields identical output`() {
        for (series in deterministicSeries()) {
            val first = compute(series, Timeframe.D1)
            val second = compute(series, Timeframe.D1)
            assertEquals(first.pivots, second.pivots)
            assertEquals(first.atrAtPivot, second.atrAtPivot)
            assertEquals(first.usedFallbackDepth, second.usedFallbackDepth)
        }
    }

    @Test
    fun `output size is bounded so a long series cannot flood the detectors`() {
        // A 500-bar series of pure ATR noise is the pathological case the bound exists for.
        val noisy = List(500) { i ->
            val swing = 1.0 + (i % 2) * 6.0 // every other bar a 6% poke: maximal pivot count
            val base = 1_000.0 + swing
            Candle("NOISE", Timeframe.D1, date(i), base, base + 2.0, base - 2.0, base, 1_000L)
        }
        val shallow = ZigzagPivots.compute(noisy, Timeframe.D1, ZigzagParams.D1.scaledDepth(0.05))
        assertTrue(shallow.size <= ZigzagParams.D1.maxPivots, "got ${shallow.size} pivots")
        // And the tail is kept: dropping the newest would starve every detector.
        assertTrue(
            shallow.pivots.last().barIndex >= noisy.lastIndex - 5,
            "the most recent pivots must survive the bound, newest at bar ${shallow.pivots.last().barIndex}",
        )
        shallow.pivots.forEachIndexed { index, pivot -> assertEquals(index, pivot.legIndex) }
    }

    @Test
    fun `alternatingHighLowAndRisingLowsAreFound`() {
        // A textbook zigzag: highs and lows alternating with genuine ~3% swings.
        val candles = zigzag(legs = 12, legBars = 4)
        val pivots = ZigzagPivots.pass(candles, ZigzagParams.D1, emptyList())
        assertTrue(pivots.size >= 8, "a clean zigzag must produce pivots, got ${pivots.size}")
        // §2.3 starts the machine in SEEK_HIGH — "we do not know the first pivot's kind yet" — so a
        // series that opens by rising confirms its first pivot as a swing high.
        assertEquals(PivotKind.HIGH, pivots.first().kind)
        for (i in 1 until pivots.size) {
            assertTrue(pivots[i].kind != pivots[i - 1].kind)
            assertTrue(pivots[i].barIndex - pivots[i - 1].barIndex >= ZigzagParams.D1.minBarsBetween)
        }
        // A rising zigzag: every swing high is above the one before it.
        val highs = pivots.filter { it.kind == PivotKind.HIGH }
        assertTrue(highs.size >= 3, "expected several rising highs, got ${highs.size}")
        for (i in 1 until highs.size) assertTrue(highs[i].price > highs[i - 1].price)
    }

    @Test
    fun `confirmationLagMarksOnlyTheNewestPivotUnconfirmed`() {
        val series = wave(220, amplitude = 12.0)
        val pivots = compute(series, Timeframe.D1)
        assertTrue(pivots.size >= 3)
        (0 until pivots.size).forEach { index ->
            val barsAgo = pivots.barCount - 1 - pivots[index].barIndex
            assertEquals(
                barsAgo >= ZigzagParams.D1.confirmBars,
                pivots.isConfirmed(index),
                "confirmation at index $index",
            )
        }
        assertEquals(
            pivots.pivots.filterIndexed { i, _ -> pivots.isConfirmed(i) },
            pivots.confirmed(),
        )
    }

    @Test
    fun `lookupsByIndexKindAndBarAgreeWithTheList`() {
        val pivots = compute(wave(300, amplitude = 10.0), Timeframe.D1)
        assertTrue(pivots.size >= 4)
        (0 until pivots.size).forEach { index ->
            val pivot = pivots[index]
            assertEquals(pivot, pivots.pivotAt(pivot.barIndex))
            assertEquals(pivot.price, pivots.priceAt(pivot.barIndex))
            assertTrue(pivots.atrAt(index) >= 0.0)
        }
        assertEquals(pivots.size, pivots.highs().size + pivots.lows().size)
        assertEquals(pivots.highs(), pivots.ofKind(PivotKind.HIGH))
        assertEquals(pivots.legGaps().sum() + pivots[0].barIndex, pivots[pivots.size - 1].barIndex)
        pivots.legGaps().forEach { assertTrue(it >= ZigzagParams.D1.minBarsBetween) }
    }

    @Test
    fun `theFallbackPassOnlyRunsWhenThePrimaryDepthIsStarved`() {
        // Legs of exactly 1.0 % of price against an ATR so small that the *percentage* anchor
        // decides: 1.5 % (primary) rejects them, 1.5 × 0.6 = 0.9 % (§2.4) accepts them.
        val atr = 0.002 * 100.0
        val candles = staircase(count = 200, stepPct = 0.010, range = 0.001)
        val atrSeries = List(candles.size) { atr }

        val primary = ZigzagPivots.pass(candles, ZigzagParams.D1, atrSeries)
        val shallow = ZigzagPivots.pass(candles, ZigzagParams.D1.scaledDepth(ZigzagParams.FALLBACK_DEPTH_FACTOR), atrSeries)
        assertTrue(primary.size < ZigzagParams.MIN_PIVOTS, "the primary depth must be starved, got ${primary.size}")
        assertTrue(shallow.size > primary.size, "the shallower pass must find more: ${shallow.size} vs ${primary.size}")

        val set = ZigzagPivots.compute(candles, Timeframe.D1, atr = atrSeries)
        assertTrue(set.usedFallbackDepth, "a starved primary pass must re-run shallower")
        assertEquals(shallow.size, set.size)
        assertEquals(ZigzagParams.D1.depthPct * ZigzagParams.FALLBACK_DEPTH_FACTOR, set.params.depthPct, 1e-12)

        // A real zigzag with enough pivots must NOT be re-run at the shallower depth.
        val zig = ZigzagPivots.compute(wave(300, amplitude = 12.0), Timeframe.D1)
        if (zig.size >= ZigzagParams.MIN_PIVOTS) {
            assertTrue(!zig.usedFallbackDepth)
            assertEquals(ZigzagParams.D1.depthPct, zig.params.depthPct, 1e-12)
        }
    }

    @Test
    fun `invalidBarsNeverBecomePivots`() {
        val good = wave(120, amplitude = 10.0).toMutableList()
        good[60] = good[60].copy(high = Double.NaN, low = Double.NaN)
        good[61] = good[61].copy(high = -1.0, low = -2.0)
        val set = ZigzagPivots.compute(good, Timeframe.D1)
        set.pivots.forEach { pivot ->
            assertTrue(pivot.barIndex != 60 && pivot.barIndex != 61, "an invalid bar became a pivot")
        }
    }

    // ------------------------------------------------------------------------ real data

    @Test
    fun `realDailyHistoryProducesASanePivotCountAndEveryInvariant`() {
        val symbols = EodHistory.fullHistorySymbols(8)
        assertTrue(symbols.size >= 6, "the fixture should carry at least 6 full-history symbols")

        val counts = LinkedHashMap<String, Int>()
        val fallbacks = ArrayList<String>()
        for (symbol in symbols) {
            val candles = EodHistory.candles(symbol)
            assertEquals(372, candles.size, "$symbol should have 372 real daily bars")
            assertTrue(candles.zipWithNext().all { (a, b) -> a.date.isBefore(b.date) }, "$symbol not ascending")

            val set = ZigzagPivots.compute(candles, Timeframe.D1)
            counts[symbol] = set.size
            if (set.usedFallbackDepth) fallbacks += symbol

            // The spec's floor is MIN_PIVOTS = 8 (patterns-spec.md §2.4): below it the fallback
            // pass still cannot find a 5-pivot H&S, so no structural detector can match at all.
            assertTrue(set.size >= 8, "$symbol produced ${set.size} pivots, below MIN_PIVOTS")
            // Upper bound: with DEPTH_ATR = 3.0 and DEPTH_PCT = 1.5 % on daily data, a large cap
            // yields one significant swing roughly every 15–20 bars — 12–24 over 372 bars. The
            // bound only has to reject a pathological list.
            assertTrue(set.size <= 60, "$symbol produced ${set.size} pivots, above the sane bound")

            for (i in 1 until set.size) {
                assertTrue(set[i].kind != set[i - 1].kind, "$symbol: same-kind pivots at $i")
                assertTrue(set[i].barIndex > set[i - 1].barIndex, "$symbol: bar index not increasing at $i")
                assertEquals(i, set[i].legIndex)
            }
            set.pivots.forEach { pivot ->
                val candle = candles[pivot.barIndex]
                val expected = if (pivot.kind == PivotKind.HIGH) candle.high else candle.low
                assertEquals(expected, pivot.price, 1e-6, "$symbol: pivot price is not its own high/low")
            }
            assertTrue(set.atrAtPivot.all { it >= 0.0 && it.isFinite() }, "$symbol: bad ATR at a pivot")
        }
        // Printed into the report so the numbers are auditable rather than merely bounded.
        println("real D1 pivot counts (372 bars): " + counts.entries.joinToString { "${it.key}=${it.value}" })
        println("symbols that needed the §2.4 fallback pass: " + fallbacks.ifEmpty { listOf("none") })
    }

    @Test
    fun `realDailyHistoryIsDeterministicAcrossRuns`() {
        val symbol = "RELIANCE"
        val candles = EodHistory.candles(symbol)
        val first = ZigzagPivots.compute(candles, Timeframe.D1)
        val second = ZigzagPivots.compute(candles, Timeframe.D1)
        assertEquals(first, second)
    }

    @Test
    fun `weeklyResampledHistoryStaysInsideTheSpecifiedDepth`() {
        // The W1 depth (2.5 ATR / 2.5 %, patterns-spec.md §2.2) is much deeper than the D1 one, so
        // a weekly series legitimately yields far fewer pivots. This asserts the *bound* and the
        // invariants, not a count, because the count is a property of the spec's constants.
        val daily = EodHistory.candles("RELIANCE")
        val weekly = com.mia.app.domain.technical.CandleResampler.toWeekly(daily)
        assertTrue(weekly.size >= 40, "the fixture should yield at least 40 weekly bars, got ${weekly.size}")
        val set = ZigzagPivots.compute(weekly, Timeframe.W1)
        assertTrue(set.size <= ZigzagParams.W1.maxPivots)
        for (i in 1 until set.size) {
            assertTrue(set[i].kind != set[i - 1].kind)
            assertTrue(set[i].barIndex > set[i - 1].barIndex)
        }
        println("RELIANCE: ${weekly.size} weekly bars → ${set.size} W1 pivots (depth ${set.params.depthPct}% / ${set.params.atrMultiple} ATR)")
    }

    // ---------------------------------------------------------------------------- helpers

    private fun compute(candles: List<Candle>, timeframe: Timeframe): PivotSet =
        ZigzagPivots.compute(candles, timeframe)

    private fun date(i: Int) = java.time.LocalDate.of(2026, 1, 1).plusDays(i.toLong())

    /**
     * A clean rising zigzag: [legs] alternating legs of [legBars] bars each (+7 % up, −5 % down),
     * every leg comfortably larger than the D1 significance threshold and the 3-bar minimum leg.
     * The asymmetry is what makes the *highs* rise, which is the property the test asserts.
     */
    private fun zigzag(legs: Int, legBars: Int, start: Double = 100.0): List<Candle> {
        val closes = ArrayList<Double>(legs * (legBars + 1))
        var price = start
        var leg = 0
        repeat(legs) {
            val target = if (leg % 2 == 0) price * 1.07 else price * 0.95
            repeat(legBars) { k ->
                price = target - (target - price) * k / legBars
                closes += price
            }
            closes += target
            price = target
            leg++
        }
        return closes.mapIndexed { i, close ->
            val open = if (i == 0) close else closes[i - 1]
            Candle(
                symbol = "ZIG",
                timeframe = Timeframe.D1,
                date = date(i),
                open = open,
                high = maxOf(open, close) + 0.25,
                low = minOf(open, close) - 0.25,
                close = close,
                volume = 1_000L,
            )
        }
    }

    /**
     * A staircase of [stepPct] up/down legs, each `legBars` long, with a bar range of [range].
     *
     * The legs are *small relative to the significance threshold*, which is the only situation in
     * which §2.4's fallback pass changes the outcome.
     */
    private fun staircase(count: Int, stepPct: Double, range: Double, legBars: Int = 4): List<Candle> {
        val closes = ArrayList<Double>(count)
        var price = 100.0
        var leg = 0
        repeat(count) { i ->
            // One jump per leg; the price then holds, so the only moves are the legs themselves.
            if (i > 0 && i % legBars == 0) {
                leg++
                price *= if (leg % 2 == 0) 1.0 + stepPct else 1.0 - stepPct
            }
            closes += price
        }
        return closes.mapIndexed { i, close ->
            val open = if (i == 0) close else closes[i - 1]
            Candle(
                symbol = "STAIR",
                timeframe = Timeframe.D1,
                date = date(i),
                open = open,
                high = maxOf(open, close) + range * price / 2,
                low = minOf(open, close) - range * price / 2,
                close = close,
                volume = 100_000L,
            )
        }
    }

    /** A deterministic seeded walk with realistic ranges (no `Random`, so a failure reproduces). */
    private fun wave(count: Int, amplitude: Double = 10.0, seed: Long = 42L): List<Candle> {
        var state = seed
        val closes = DoubleArray(count)
        var price = 1_000.0
        for (i in 0 until count) {
            state = state * 6364136223846793005L + 1442695040888963407L
            val draw = ((state ushr 33) and 0xFFFF) / 65535.0 - 0.5
            price *= 1.0 + draw * amplitude / 50.0
            closes[i] = Math.round(price * 100.0) / 100.0
        }
        return closes.mapIndexed { i, close ->
            val previous = if (i == 0) close else closes[i - 1]
            val open = previous
            Candle(
                symbol = "WAVE",
                timeframe = Timeframe.D1,
                date = date(i),
                open = open,
                high = maxOf(open, close) + 0.4,
                low = minOf(open, close) - 0.4,
                close = close,
                volume = 100_000L,
                source = Source.NSE_BHAVCOPY,
            )
        }
    }

    private fun deterministicSeries(): List<List<Candle>> = listOf(
        wave(120),
        wave(240, amplitude = 4.0, seed = 7L),
        wave(240, amplitude = 25.0, seed = 99L),
        wave(60, amplitude = 12.0, seed = 2024L),
        // A saw-tooth: maximal pivot count for the depth.
        wave(200).mapIndexed { i, c ->
            c.copy(
                open = if (i % 2 == 0) c.close else c.open,
                high = c.high + (if (i % 3 == 0) 0.0 else 3.0),
                low = c.low - (if (i % 3 == 1) 0.0 else 3.0),
            )
        },
    )
}