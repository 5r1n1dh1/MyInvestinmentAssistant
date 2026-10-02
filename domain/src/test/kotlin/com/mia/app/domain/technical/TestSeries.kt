package com.mia.app.domain.technical

import com.mia.app.domain.model.Candle
import com.mia.app.domain.model.Source
import com.mia.app.domain.model.Timeframe
import java.time.LocalDate

/**
 * Deterministic candle fixtures for the indicator tests.
 *
 * Every series here is **constructed**, not captured, so that the golden values in the tests
 * can be re-derived by hand on paper. The closes are chosen to be integers and the high/low
 * offsets to be a single 0.5 so that true range and directional movement come out as exact
 * binary-representable doubles.
 */
internal object TestSeries {

    val SYMBOL = "TEST"
    private val START = LocalDate.of(2026, 1, 1)

    fun date(i: Int): LocalDate = START.plusDays(i.toLong())

    /**
     * Builds a candle from a close, deriving a structurally valid bar around it.
     *
     * `open = previous close`, `high = max(open, close) + pad`, `low = min(open, close) - pad`.
     * Using `max`/`min` (rather than `close ± pad`) guarantees [Candle.isValid] on every bar,
     * including the first, whose open has no predecessor.
     */
    fun bar(
        i: Int,
        close: Double,
        previousClose: Double,
        pad: Double = 0.5,
        volume: Long = 1_000L,
        deliveryPercent: Double? = null,
        timeframe: Timeframe = Timeframe.D1,
    ): Candle {
        val open = if (i == 0) close else previousClose
        val high = maxOf(open, close) + pad
        val low = minOf(open, close) - pad
        return Candle(
            symbol = SYMBOL,
            timeframe = timeframe,
            date = date(i),
            open = open,
            high = high,
            low = low,
            close = close,
            volume = volume,
            deliveryPercent = deliveryPercent,
            source = Source.NSE_BHAVCOPY,
        )
    }

    /** A candle list from an explicit close series. See [bar] for the OHLC convention. */
    fun fromCloses(
        closes: List<Double>,
        volume: Long = 1_000L,
        deliveryPercent: (Int) -> Double? = { null },
    ): List<Candle> = closes.mapIndexed { i, c ->
        bar(i, c, if (i == 0) c else closes[i - 1], volume = volume, deliveryPercent = deliveryPercent(i))
    }

    /**
     * The 30-bar golden series.
     *
     * ```
     * index :  0  1  2  3  4  5  6  7  8  9 10 11 12 13 14 15 16 17 18 19 20 21 22 23 24 25 26 27 28 29
     * close :  1  2  3  4  5  6  7  8  9 10  9  8  7  6  5  4  3  2  1  2  3  4  5  6  7  8  9 10 11 12
     * ```
     *
     * A 9-bar ramp up to 10, a 9-bar decline back to 1 (indices 10..18), then an 11-bar ramp to
     * 12. Chosen because: the ramp makes SMA/EMA/MACD arithmetic exact; the reversal exercises
     * the falling side of every indicator; and 30 bars is more than ADX(14) and Bollinger(20)
     * need, so a handful of non-warm-up bars exist for the ADX/Bollinger goldens.
     */
    val GOLDEN_CLOSES: List<Double> = buildList {
        for (i in 0..9) add(i + 1.0)          // 1..10
        for (i in 10..18) add (19.0 - i)      // 9,8,7,6,5,4,3,2,1
        for (i in 19..29) add (i - 17.0)      // 2,3,4,5,6,7,8,9,10,11,12
    }

    /** [GOLDEN_CLOSES] as candles, with a constant 1000 volume and no delivery data. */
    val golden: List<Candle> get() = fromCloses(GOLDEN_CLOSES)

    /**
     * A series whose closes alternate 100 / 101, i.e. every change is exactly +1 or −1.
     *
     * This is the series the RSI golden values are computed on: Wilder's averages of ±1 steps
     * are exact binary fractions, so RSI lands on values like 50.0, 25.0 and 62.5 with no
     * floating-point slack.
     */
    fun alternating(count: Int, low: Double = 100.0): List<Candle> =
        fromCloses(List(count) { i -> if (i % 2 == 0) low else low + 1.0 })

    /**
     * A perfectly directionless series: constant close, constant range, no movement.
     *
     * `trueRange` is 1.0 on every bar (not 0), so Wilder's denominators stay valid and ADX comes
     * out as exactly 0 — the "flat price series → ADX ≈ 0" case.
     */
    fun directionless(count: Int, price: Double = 100.0): List<Candle> = List(count) { i ->
        Candle(
            symbol = SYMBOL,
            timeframe = Timeframe.D1,
            date = date(i),
            open = price,
            high = price + 0.5,
            low = price - 0.5,
            close = price,
            volume = 1_000L,
        )
    }

    /**
     * A degenerate series with a zero intraday range on every bar (`high == low`), which drives
     * Wilder's smoothed true range to exactly 0.
     */
    fun zeroRange(count: Int, price: Double = 100.0): List<Candle> = List(count) { i ->
        Candle(
            symbol = SYMBOL,
            timeframe = Timeframe.D1,
            date = date(i),
            open = price,
            high = price,
            low = price,
            close = price,
            volume = 1_000L,
        )
    }

    /**
     * A strictly rising series of [count] bars, closes `100, 101, …`.
     *
     * Every up-move has `+DM = 1`, `−DM = 0` and `TR = 1.5`, so `+DI = 100/1.5 = 66.666…`,
     * `−DI = 0`, `DX = 100` and therefore `ADX = 100` exactly — the stable TRENDING_UP case.
     */
    fun rising(count: Int, start: Double = 100.0): List<Candle> =
        fromCloses(List(count) { start + it })

    /**
     * A strictly falling series: closes `start, start-1, …`.
     *
     * Mirrors [rising] with `−DM = 1`, `+DM = 0`, `TR = 2.0`, so `−DI = 50`, `+DI = 0`,
     * `DX = 100`, `ADX = 100` — the stable TRENDING_DOWN case.
     */
    fun falling(count: Int, start: Double = 500.0): List<Candle> =
        fromCloses(List(count) { start - it })

    /** A series with the given volumes and constant close, for the volume indicators. */
    fun withVolumes(count: Int, volumes: List<Long>, close: Double = 100.0): List<Candle> =
        List(count) { i ->
            val previousClose = close
            bar(i, close, previousClose, volume = volumes.getOrElse(i) { 1_000L })
        }

    /**
     * A pseudo-random but **seeded** generator, so a failing property test can always be
     * reproduced. A fixed linear congruential generator rather than [java.util.Random] so the
     * sequence is identical on every platform and every Kotlin version.
     */
    fun lcg(seed: Long): () -> Long {
        var state = if (seed == 0L) 1L else seed
        return {
            state = state * 6364136223846793005L + 1442695040888963407L
            (state ushr 33) and 0x7FFFFFFF
        }
    }

    /**
     * A seeded random walk of [count] bars with OHLC ranges, mixed volumes and *intermittent*
     * delivery data (every third bar is `null`, mirroring the legacy NSE file being published
     * for only some sessions).
     *
     * Prices stay strictly positive and the bars are always [Candle.isValid].
     */
    fun seededWalk(
        count: Int,
        seed: Long = 20_260_101L,
        start: Double = 1_000.0,
        withDelivery: Boolean = false,
        timeframe: Timeframe = Timeframe.D1,
    ): List<Candle> {
        val next = lcg(seed)
        val closes = DoubleArray(count)
        var price = start
        for (i in 0 until count) {
            // Map a 31-bit draw into [-4%, +4%].
            val step = ((next() % 801) - 400) / 10_000.0
            price = (price * (1.0 + step)).coerceAtLeast(1.0)
            closes[i] = Math.round(price * 100.0) / 100.0
        }
        return closes.mapIndexed { i, close ->
            val previous = if (i == 0) close else closes[i - 1]
            val volume = 10_000L + next() % 900_000L
            bar(
                i = i,
                close = close,
                previousClose = previous,
                pad = 0.25 + (next() % 300) / 1000.0,
                volume = volume,
                timeframe = timeframe,
                deliveryPercent = if (withDelivery && i % 3 != 0) {
                    5.0 + (next() % 5000) / 100.0
                } else {
                    null
                },
            )
        }
    }
}