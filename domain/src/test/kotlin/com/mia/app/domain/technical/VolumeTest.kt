package com.mia.app.domain.technical

import com.mia.app.domain.model.Timeframe
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Golden values and invariants for OBV, the volume averages and the NSE delivery-percentage
 * indicators that drive plan.md §8.2's "delivery % collapsing" red flag.
 */
class VolumeTest {

    private val candles = TestSeries.golden

    // ----------------------------------------------------------------------------- OBV

    @Test
    fun `obv on the golden series is an exact running signed volume total`() {
        // Volume is a constant 1000, so OBV accumulates +/-1000 per directional bar.
        // Rising ramp, indices 1..9 (9 up bars):  obv[i] = 1000*i  -> obv[9] = 9000
        // Decline, indices 10..18 (9 down bars):      obv[i] = 9000 - 1000*(i-9)
        //                                             obv[18] = 0
        // Rally, indices 19..29 (11 up bars):          obv[i] = 1000*(i-18) -> obv[29] = 11000
        val o = obv(candles)
        assertEquals(30, o.size)
        assertEquals(0.0, o[0])
        assertEquals(1000.0, o[1])
        assertEquals(9000.0, o[9])
        assertEquals(8000.0, o[10])
        assertEquals(1000.0, o[17])
        assertEquals(0.0, o[18])
        assertEquals(1000.0, o[19])
        assertEquals(11000.0, o[29])
    }

    @Test
    fun `obv is flat when the close does not move`() {
        val o = obv(TestSeries.directionless(10))
        assertEquals(List(10) { 0.0 }, o)
    }

    @Test
    fun `obv is empty for no bars`() {
        assertEquals(emptyList(), obv(emptyList()))
    }

    @Test
    fun `obv slope on the golden series is exactly 0_1`() {
        // Over the trailing 20 bars (indices 10..29): 9 down bars (-1000 each) and 11 up bars
        // (+1000 each) -> signed sum = 2000, gross volume = 20 * 1000 = 20000.
        // 2000 / 20000 = 0.1
        assertEquals(0.1, obvSlope(candles, lookback = 20))
    }

    @Test
    fun `obv slope is negative on a declining series and positive on a rising one`() {
        assertTrue(obvSlope(TestSeries.rising(80), lookback = 20) == 1.0)
        assertEquals(-1.0, obvSlope(TestSeries.falling(80), lookback = 20))
    }

    @Test
    fun `obv slope is null without enough bars or without any volume`() {
        assertNull(obvSlope(candles.take(10), lookback = 20))
        assertNull(obvSlope(candles, lookback = 0))
        assertNull(obvSlope(candles, lookback = -1))
        // 20 bars of directionless prices with volume still give 0 (all flat bars -> no signs).
        assertNull(
            obvSlope(
                List(25) {
                    com.mia.app.domain.model.Candle(
                        symbol = "T",
                        timeframe = Timeframe.D1,
                        date = TestSeries.date(it),
                        open = 100.0,
                        high = 100.0,
                        low = 100.0,
                        close = 100.0,
                        volume = 0L,
                    )
                },
                lookback = 20,
            ),
        )
    }

    // --------------------------------------------------------------- volume averages

    @Test
    fun `volume sma of a constant volume series is that constant`() {
        val v = volumeSma(candles, period = 20)
        assertWarmup(v, 19, "volumeSma20")
        assertEquals(1000.0, v[19])
        assertEquals(1000.0, v[29])
    }

    @Test
    fun `volume sma matches the sma of the volume array on a mixed series`() {
        val volumes = List(40) { (1L + it) * 100L }
        val series = TestSeries.withVolumes(40, volumes)
        assertEquals(
            sma(volumes.map { it.toDouble() }, 5),
            volumeSma(series, 5),
        )
    }

    @Test
    fun `volume ratio is the ratio to the trailing average`() {
        assertEquals(2.0, volumeRatio(2000.0, 1000.0))
        assertEquals(0.5, volumeRatio(500.0, 1000.0))
    }

    @Test
    fun `volume ratio is null when the average is zero rather than infinite`() {
        assertNull(volumeRatio(1000.0, 0.0))
        assertNull(volumeRatio(0.0, 0.0))
    }

    @Test
    fun `volume expansion fires above the 2_5x plan threshold`() {
        // 20 bars of 10000 volume, then a final bar of 25000.
        // Twenty bars of 10,000 then one of 30,000. The trailing 20-bar window at the final bar covers
        // indices 1..20, i.e. nineteen 10,000s and the 30,000, so the average is 220000/20 = 11000
        // and the ratio is 30000/11000 = 2.7273, comfortably above the 2.5x threshold. The window
        // must include the spike bar itself — an average that excluded it could never flag one.
        val volumes = List(20) { 10_000L } + listOf(30_000L)
        val series = TestSeries.withVolumes(21, volumes)
        val ratio = assertNotNull(volumeRatio(series).last())
        assertEquals(11_000.0, volumeSma(series)[20])
        assertClose(30_000.0 / 11_000.0, ratio)
        assertClose(2.727272727272727, ratio)
        assertTrue(isVolumeExpanding(series))
        // A 25,000 bar only reaches 2.3256x and must not trip the threshold.
        val milder = List(20) { 10_000L } + listOf(25_000L)
        assertEquals(10_750.0, volumeSma(TestSeries.withVolumes(21, milder))[20])
        assertClose(2.3255813953488373, volumeRatio(TestSeries.withVolumes(21, milder)).last())
        assertFalse(isVolumeExpanding(TestSeries.withVolumes(21, milder)))
        assertFalse(isVolumeExpanding(candles), "a flat volume series never expands")
    }

    @Test
    fun `volume expansion is null-safe on an empty series`() {
        assertFalse(isVolumeExpanding(emptyList()))
        assertTrue(volumeRatio(emptyList()).isEmpty())
    }

    // ---------------------------------------------------------------------- delivery

    @Test
    fun `delivery average is the mean of the trailing window`() {
        // 24 bars, delivery 50% on bars 0..21 and 10% on the final two.
        val series = TestSeries.fromCloses(
            closes = List(24) { 100.0 + it },
            deliveryPercent = { i -> if (i >= 22) 10.0 else 50.0 },
        )
        val d = deliveryAveragePercent(series, period = 20)
        assertWarmup(d, 19, "deliveryAverage")
        // Window 0..19 is entirely 50% -> 50.0
        assertEquals(50.0, d[19])
        // Window 2..21 is entirely 50% -> 50.0
        assertEquals(50.0, d[21])
        // Window 3..22: nineteen 50s and one 10 -> (950 + 10)/20 = 48.0
        assertEquals(48.0, d[22])
        // Window 4..23: eighteen 50s and two 10s -> (900 + 20)/20 = 46.0
        assertEquals(46.0, d[23])
    }

    @Test
    fun `delivery average is all null when no bar carries delivery data`() {
        val d = deliveryAveragePercent(candles, period = 20)
        assertTrue(d.all { it == null })
        assertWarmup(d, 19, "deliveryAverage without data")
    }

    @Test
    fun `delivery average never fabricates a reading from too little coverage`() {
        // Only 5 of the last 20 bars have delivery, which is below the 75% coverage requirement.
        val series = TestSeries.fromCloses(
            closes = List(20) { 100.0 + it },
            deliveryPercent = { i -> if (i >= 15) 40.0 else null },
        )
        assertTrue(deliveryAveragePercent(series, period = 20).all { it == null })
    }

    @Test
    fun `delivery average ignores missing bars inside the window`() {
        // 20 bars, 4 of them missing. Coverage = 16/20 = 80%, above the 75% gate, so the mean is
        // taken over the 16 readings that exist: all 40% -> 40.0.
        val series = TestSeries.fromCloses(
            closes = List(20) { 100.0 + it },
            deliveryPercent = { i -> if (i in setOf(2, 7, 11, 18)) null else 40.0 },
        )
        val d = deliveryAveragePercent(series, period = 20)
        assertEquals(40.0, d[19])
    }

    @Test
    fun `delivery average returns null for too few bars or a bad period`() {
        assertTrue(deliveryAveragePercent(candles.take(5), period = 20).all { it == null })
        assertTrue(deliveryAveragePercent(candles, period = 0).all { it == null })
        assertEquals(emptyList(), deliveryAveragePercent(emptyList()))
    }

    // ------------------------------------------------------------- collapsing delivery

    @Test
    fun `delivery collapse fires when delivery falls to well under its own average`() {
        // The last two bars deliver 10% against a 20-bar average of 46% and 48%: ratios of
        // 10/48 = 0.2083 and 10/46 = 0.2174, both under the 0.40 threshold.
        val series = TestSeries.fromCloses(
            closes = List(24) { 100.0 + it },
            deliveryPercent = { i -> if (i >= 22) 10.0 else 50.0 },
        )
        assertTrue(isDeliveryCollapsing(series))
        val evidence = assertNotNull(deliveryCollapseEvidence(series))
        assertEquals(10.0, evidence.latestPercent)
        assertEquals(46.0, evidence.averagePercent)
        assertClose(10.0 / 46.0, evidence.ratio)
        assertEquals(series.last().date, evidence.date)
    }

    @Test
    fun `delivery collapse does not fire on a single soft session`() {
        // Only the final bar collapses (10% against a 50% average = ratio 0.2), but the flag
        // requires two consecutive bars, so bar 22's 50% vetoes it.
        val series = TestSeries.fromCloses(
            closes = List(24) { 100.0 + it },
            deliveryPercent = { i -> if (i == 23) 10.0 else 50.0 },
        )
        assertFalse(isDeliveryCollapsing(series))
    }

    @Test
    fun `delivery collapse does not fire when delivery merely dips below the threshold`() {
        // Final two bars at 25% against a 50% average: ratio 0.5, above the 0.40 threshold.
        val series = TestSeries.fromCloses(
            closes = List(24) { 100.0 + it },
            deliveryPercent = { i -> if (i >= 22) 25.0 else 50.0 },
        )
        assertFalse(isDeliveryCollapsing(series))
        assertTrue(isDeliveryCollapsing(series, ratioThreshold = 0.6), "a looser threshold fires")
    }

    @Test
    fun `delivery collapse is false when delivery data was never fetched`() {
        // Absence of evidence is not evidence of collapse (plan.md §12 on parser health).
        assertFalse(isDeliveryCollapsing(candles))
        assertNull(deliveryCollapseEvidence(candles))
    }

    @Test
    fun `delivery collapse is false on a sparse series and on zero bars`() {
        val sparse = TestSeries.fromCloses(
            closes = List(40) { 100.0 + it },
            deliveryPercent = { i -> if (i % 3 == 0) null else 50.0 },
        )
        assertFalse(isDeliveryCollapsing(sparse), "coverage below the gate yields no reading")
        assertFalse(isDeliveryCollapsing(emptyList()))
    }

    @Test
    fun `delivery collapse never produces NaN when delivery percentages are null`() {
        for (seed in 1L..20L) {
            val walk = TestSeries.seededWalk(120, seed, withDelivery = true)
            assertFinite("deliveryAverage(seed=$seed)", deliveryAveragePercent(walk))
            // Either it fires or it does not, but it must answer without throwing either way.
            isDeliveryCollapsing(walk)
            deliveryCollapseEvidence(walk)
        }
    }

    @Test
    fun `delivery collapse requires a positive average to divide by`() {
        val series = TestSeries.fromCloses(
            closes = List(24) { 100.0 + it },
            deliveryPercent = { 0.0 },
        )
        assertFalse(isDeliveryCollapsing(series), "an all-zero average has no ratio to compare")
        assertNull(deliveryCollapseEvidence(series))
    }
}