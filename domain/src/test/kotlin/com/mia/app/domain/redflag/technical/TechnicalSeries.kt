package com.mia.app.domain.redflag.technical

import com.mia.app.domain.model.Candle

/**
 * Every engineered series the per-rule tests share, in one place.
 *
 * **Why the fixtures live together rather than inside each test.** Each of these has a specific
 * numeric property that one test is the *only* test that needs — but the "quiet" and
 * "cannot-evaluate" cases of a neighbouring rule want the same series, and a rule's fixture
 * duplicated across two files is a fixture that drifts. `redflags-spec.md` §7 states each threshold
 * exactly; the series below are the mechanical statement of each one, so a reader can check the
 * threshold against the fixture that trips it without reading any test body.
 */
internal object TechnicalSeries {

    // ------------------------------------------------------------------------- death cross

    /**
     * `RF-T-1` firing fixture: 260 sessions that rise, then fall.
     *
     * * bars 0–39 flat at 100 (so the 200-day average has history to average)
     * * bars 40–159 ramp 100 → 180 (the 50-day average climbs above the 200-day average)
     * * bars 160–259 ramp 180 → 110 (it crosses back below, and the last close goes under it)
     *
     * The cross prints far enough back that the flag is `MEDIUM`, which is the common real case and
     * the one worth pinning. [recentDeathCross] covers the `HIGH` branch.
     */
    fun deathCross(): List<Candle> = CandleLab().apply {
        flat(count = 40, price = 100.0)
        ramp(count = 120, fromClose = 100.0, toClose = 180.0)
        ramp(count = 100, fromClose = 180.0, toClose = 110.0)
    }.build()

    /**
     * `RF-T-1` `HIGH` fixture: the cross prints **one session** before the latest bar.
     *
     * * bars 0–59 flat at 110 (200-day average history)
     * * bars 60–199 ramp 110 → 130, then bars 200–259 drift 130 → 120
     * * bars 260–264 collapse 120 → 35 in five sessions
     *
     * The last two legs matter. A long shallow uptrend leaves the 50-day average only ~2.6 points
     * above the 200-day average, and because the two averages damp a new bar differently (the
     * 50-day one 4× faster) it takes a five-session collapse of that size to flip them. A steeper
     * prior uptrend leaves a bigger gap and would need a bigger collapse — so the fixture is
     * engineered, and the KDoc on [TechnicalRedFlagPolicy.DEATH_CROSS_RECENT_BARS] records the
     * consequence: on gentle price action the `HIGH` band is unreachable and `MEDIUM` is what
     * almost every real death cross reports.
     */
    fun recentDeathCross(): List<Candle> {
        val closes = ArrayList<Double>()
        closes += List(60) { 110.0 }
        closes += (0 until 140).map { 110.0 + it * (20.0 / 139.0) }
        closes += (1..60).map { 130.0 - it * (10.0 / 60.0) }
        closes += listOf(100.0, 80.0, 60.0, 45.0, 35.0)
        return CandleLab().fromCloses(closes, halfRange = 0.5).build()
    }

    /** A steady uptrend: the 50-day average is above the 200-day average and so is the close. */
    fun noDeathCross(): List<Candle> = CandleLab().apply {
        ramp(count = 260, fromClose = 100.0, toClose = 400.0)
    }.build()

    /**
     * A 260-session series that falls the whole way, so the 50-day average is below the 200-day
     * average on every computable bar and **no ABOVE -> BELOW transition exists inside the window**.
     *
     * This is the fixture that makes `deathCrossBarIndex == null` a reachable state, which is what
     * the flag's "no crossover inside the loaded window" evidence row exists to report.
     */
    fun monotonicDecline(): List<Candle> =
        CandleLab().apply { ramp(count = 260, fromClose = 400.0, toClose = 100.0) }.build()

    /** Fewer bars than the 200-day average needs. */
    fun tooShortForDeathCross(bars: Int = 80): List<Candle> =
        CandleLab().apply { ramp(count = bars, fromClose = 100.0, toClose = 150.0) }.build()

    // ----------------------------------------------------------------------------- RSI bands

    /**
     * `RF-T-2` `RSI_OVERBOUGHT` firing fixture: 30 sessions whose closes gain ₹1.00 on five sessions
     * out of six and give back ₹0.90 on the sixth.
     *
     * Wilder's smoothing settles at `avgGain = 5/6`, `avgLoss = 0.9/6`, i.e. `RS ≈ 5.56` and an RSI
     * comfortably above 70 for many sessions — which is the `MEDIUM` (persistent) branch. It is a
     * *sustained* reading, not a saturated one, so the test proves the flag fires on a number a
     * charting package would also draw.
     */
    fun rsiOverbought(): List<Candle> = steppedCloses(gainsPerCycle = 5, gain = 1.0, loss = 0.9)

    /**
     * The exact mirror: gives back ₹1.00 on five sessions out of six and gains ₹0.90 on the sixth,
     * so `RS ≈ 0.18` and the reading settles far below 30 — the `RSI_OVERSOLD` firing fixture.
     */
    fun rsiOversold(): List<Candle> = steppedCloses(gainsPerCycle = 1, gain = 0.9, loss = 1.0)

    /**
     * `RSI_OVERBOUGHT` at `LOW`: 25 sessions of ±₹1.00 (RSI ≈ 50, inside the bands) and then one
     * ₹20.00 up session, which pushes the reading above 70 for the first time.
     *
     * The persistence count is 1, so the flag must be `LOW`. Without this fixture the
     * `PERSISTENCE_BARS` branch would be the only one ever exercised.
     */
    fun rsiFreshCrossingOfSeventy(): List<Candle> {
        val closes = ArrayList<Double>()
        var close = 100.0
        for (i in 1..25) {
            close += if (i % 2 == 1) 1.0 else -1.0
            closes += close
        }
        closes += close + 20.0
        return CandleLab().fromCloses(closes, halfRange = 0.5).build()
    }

    /** RSI oscillating between the two bands: the quiet case for both rules. */
    fun rsiBetweenBands(): List<Candle> {
        val closes = ArrayList<Double>()
        var close = 100.0
        for (i in 1..30) {
            close += if (i % 2 == 1) 1.0 else -1.0
            closes += close
        }
        return CandleLab().fromCloses(closes, halfRange = 0.5).build()
    }

    /**
     * 29 sessions of a six-session cycle: [gainsPerCycle] up-days of [gain] then the rest as
     * down-days of [loss]. `5/1` sits in the overbought band, `1/5` in the oversold one.
     */
    private fun steppedCloses(gainsPerCycle: Int, gain: Double, loss: Double): List<Candle> {
        require(gainsPerCycle in 1..5) { "a six-session cycle cannot hold $gainsPerCycle gains" }
        val closes = ArrayList<Double>()
        var close = 100.0
        for (i in 1..29) {
            val isGain = (i - 1) % 6 < gainsPerCycle
            close += if (isGain) gain else -loss
            closes += close
        }
        return CandleLab().fromCloses(closes, halfRange = 0.5).build()
    }

    // -------------------------------------------------------------------------- distribution

    /**
     * `RF-T-3` firing fixture: 29 ordinary sessions and a 30th that closed lower on exactly **3.0×**
     * its own 20-session average volume.
     *
     * The last bar's volume is solved from the 20-bar mean (`v = target·sum/(20−target)`) rather
     * than guessed, so the assertion can pin the printed ratio instead of tolerating 2.97.
     */
    fun distributionDownDay(): List<Candle> {
        val lab = CandleLab()
        repeat(29) { lab.bar(open = 100.0, high = 100.6, low = 99.4, close = 100.2) }
        val spike = lab.volumeForRatio(3.0, lab.build().takeLast(19).map { it.volume })
        lab.bar(open = 100.50, high = 100.80, low = 99.50, close = 99.60, volume = spike)
        return lab.build()
    }

    /** The identical 3.0× volume spike, on a bar that closed **higher**. Must not fire. */
    fun distributionUpDay(): List<Candle> {
        val lab = CandleLab()
        repeat(29) { lab.bar(open = 100.0, high = 100.6, low = 99.4, close = 100.2) }
        val spike = lab.volumeForRatio(3.0, lab.build().takeLast(19).map { it.volume })
        lab.bar(open = 99.60, high = 100.80, low = 99.50, close = 100.50, volume = spike)
        return lab.build()
    }

    /** A down day on an ordinary volume: the volume half of the condition is not met. */
    fun downDayOnAverageVolume(): List<Candle> = CandleLab().apply {
        repeat(29) { bar(open = 100.0, high = 100.6, low = 99.4, close = 100.2) }
        bar(open = 100.50, high = 100.80, low = 99.50, close = 99.60)
    }.build()

    /** Fewer than 20 sessions, so the volume baseline does not exist. */
    fun tooShortForVolumeAverage(bars: Int = 12): List<Candle> = CandleLab().apply {
        repeat(bars) { i ->
            bar(open = 100.0, high = 100.6, low = 99.4, close = 100.2 + (i % 3) * 0.1)
        }
    }.build()

    // ----------------------------------------------------------------- 52-session low + OBV

    /**
     * `RF-T-4` firing fixture: 84 sessions that rise 100 → 200 and then fall to 120, with a
     * four-session plateau on the way down.
     *
     * * trailing 52 sessions = indices 32–83, whose lowest close is the final ₹120 ✔
     * * 20-session OBV slope: 16 down sessions and 4 flat ones ⇒ signed ÷ gross = **−0.8** ✔
     *
     * The plateau is what keeps the slope off the degenerate −1.0: it proves the rule reads a
     * *direction*, not a count.
     */
    fun atFiftyTwoSessionLowWithFallingObv(): List<Candle> {
        val closes = ArrayList<Double>()
        closes += (0 until 52).map { 100.0 + it * (100.0 / 51.0) }
        closes += (1..24).map { 200.0 - it * (60.0 / 24.0) }
        repeat(4) { closes += 140.0 }
        closes += (1..4).map { 140.0 - it * (20.0 / 4.0) }
        return CandleLab().fromCloses(closes, halfRange = 0.5).build()
    }

    /**
     * The same 52-session low with a **rising** OBV.
     *
     * Nineteen rising sessions carry 1,000,000 shares each and the final bar drops below the prior
     * low on 1,000 shares, so the low is confirmed *and* the 20-session slope is positive
     * (≈ +1.00). The rule must stay silent: a low with participation rising is not this flag.
     */
    fun atFiftyTwoSessionLowWithRisingObv(): List<Candle> {
        val lab = CandleLab()
        lab.fromCloses((0 until 52).map { 100.0 + it * (100.0 / 51.0) })
        lab.fromCloses((1..12).map { 200.0 - it * (49.0 / 12.0) })
        lab.fromCloses((1..19).map { 151.0 + it * (39.0 / 19.0) }, volume = 1_000_000L)
        lab.bar(open = 190.0, high = 190.5, low = 119.5, close = 120.0, volume = 1_000L)
        return lab.build()
    }

    /** Fewer than 52 sessions, so the high/low window does not exist. */
    fun tooShortForFiftyTwoSessionWindow(bars: Int = 30): List<Candle> = CandleLab().apply {
        fromCloses((0 until bars).map { 100.0 + it * (100.0 / (bars - 1)) })
    }.build()

    // -------------------------------------------------------------------- delivery collapse

    /**
     * `RF-T-5` firing fixture at `MEDIUM`: 28 sessions delivering 50.0 % and then two delivering
     * 10.0 %.
     *
     * * the 20-session average at the second collapsing bar is (18×50.0 + 2×10.0)/20 = **46.0 %**
     * * the last reading is 10.0 % ⇒ ratio **0.22**, at or below the 0.40 threshold ✔
     * * only the last two sessions collapse, and neither of the five before them was above its own
     *   average, so the `HIGH` escalation correctly does not apply
     */
    fun deliveryCollapse(): List<Candle> {
        val lab = CandleLab()
        val deliveries = ArrayList<Double?>()
        val closes = ArrayList<Double>()
        var close = 100.0
        for (i in 0 until 28) {
            close += 0.4
            closes += close
            deliveries += 50.0
        }
        for (i in 0 until 2) {
            close -= 1.5
            closes += close
            deliveries += 10.0
        }
        lab.fromCloses(closes, deliveries = deliveries)
        return lab.build()
    }

    /**
     * `RF-T-5` at `HIGH`: delivery ran **above** its own 20-session average for the five sessions
     * before the collapse, and price fell over the same stretch.
     *
     * Deliveries: 20 sessions at 30.0 %, five at 80.0 %, five at 10.0 %. The five 80 % sessions sit
     * above their own rolling averages precisely *because* the early 30 % sessions drag the average
     * down — which is the shape the spec's `HIGH` clause describes and the reason it cannot be
     * expressed as an absolute level.
     */
    fun deliveryCollapseAfterHighParticipation(): List<Candle> {
        val lab = CandleLab()
        val deliveries = ArrayList<Double?>()
        val closes = ArrayList<Double>()
        var close = 100.0
        for (i in 0 until 20) {
            close += 0.2
            closes += close
            deliveries += 30.0
        }
        for (i in 0 until 5) {
            close += 0.5
            closes += close
            deliveries += 80.0
        }
        for (i in 0 until 5) {
            close -= 1.0
            closes += close
            deliveries += 10.0
        }
        lab.fromCloses(closes, deliveries = deliveries)
        return lab.build()
    }

    /** The same prices as [deliveryCollapse] with no delivery readings at all: the skip case. */
    fun deliveryAbsent(): List<Candle> {
        val closes = ArrayList<Double>()
        var close = 100.0
        for (i in 0 until 30) {
            close += if (i < 28) 0.4 else -1.5
            closes += close
        }
        return CandleLab().fromCloses(closes).build()
    }

    /**
     * The [deliveryCollapse] delivery readings on a series whose turnover is far below the
     * liquidity floor — the *"in small caps volume can be manipulated"* suppression.
     */
    fun deliveryCollapseThinLiquidity(): List<Candle> =
        deliveryCollapse().map { it.copy(volume = 1_000L) }
}