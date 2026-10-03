package com.mia.app.domain.redflag.technical

import com.mia.app.domain.model.Candle
import com.mia.app.domain.model.Confidence
import com.mia.app.domain.model.Ratios
import com.mia.app.domain.model.Sector
import com.mia.app.domain.model.Source
import com.mia.app.domain.model.Timeframe
import com.mia.app.domain.redflag.RedFlagContext
import com.mia.app.domain.technical.IndicatorSnapshot
import java.time.LocalDate

/**
 * The synthetic candle builder every test in this package constructs its fixtures with.
 *
 * **Why it exists and why it is deliberately explicit.** A technical red flag is a statement about
 * two numbers read from *one bar* or one trailing window (`close` versus `SMA200`; `volume` versus
 * `volumeSma20`; `deliveryPercent` versus its own 20-bar mean). Writing those fixtures as
 * `List(260) { Candle(...) }` and patching three indices produced fixtures that were wrong in ways
 * no assertion could see — a series whose 200-day average never existed, or a "down day" whose open
 * was above its high.
 *
 * So the builder makes the things a fixture has to get right impossible to fudge:
 *
 * 1. **Every O/H/L/C/V is stated or derived from a stated close**, and [bar] *asserts* the bar
 *    brackets itself, so an impossible candle cannot be written at all.
 * 2. **Ramps have exact endpoint closes** ([ramp]), so a spec's *"SMA50 < SMA200"* fixture is
 *    arithmetic rather than a hope.
 * 3. **Volume is solved, not guessed** ([volumeForRatio]) so a test can assert
 *    `volume / volumeSma20 == 3.0` rather than `≈ 2.97`.
 * 4. **Delivery % is a first-class argument** on every bar, because `RF-T-5` is the only flag that
 *    reads it and it is `null` on every real fixture bar.
 *
 * Dates are synthetic consecutive days: every indicator in `:domain` is index-aligned, so the
 * calendar carries no meaning here and the real-data regression keeps the real dates.
 */
internal class CandleLab(
    private val symbol: String = "TEST",
    private val timeframe: Timeframe = Timeframe.D1,
    start: LocalDate = LocalDate.of(2026, 1, 1),
) {
    private val bars = ArrayList<Candle>()
    private var cursor: LocalDate = start

    val size: Int get() = bars.size
    val last: Candle? get() = bars.lastOrNull()

    /** Appends one bar with every level stated. Returns `this` for chaining. */
    fun bar(
        open: Double,
        high: Double,
        low: Double,
        close: Double,
        volume: Long = DEFAULT_VOLUME,
        deliveryPercent: Double? = null,
    ): CandleLab = apply {
        require(high >= maxOf(open, close, low) && low <= minOf(open, close, high)) {
            "bar $size: high=$high low=$low must bracket open=$open close=$close"
        }
        require(open > 0.0 && close > 0.0 && high >= low) { "bar $size: prices must be positive" }
        require(deliveryPercent == null || deliveryPercent in 0.0..100.0) {
            "bar $size: delivery $deliveryPercent% must be within 0..100"
        }
        bars += Candle(
            symbol = symbol,
            timeframe = timeframe,
            date = cursor,
            open = open,
            high = high,
            low = low,
            close = close,
            volume = volume,
            deliveryPercent = deliveryPercent,
            source = Source.NSE_BHAVCOPY,
        )
        cursor = cursor.plusDays(1)
    }

    /**
     * Appends [count] bars whose **closes** ramp linearly from [fromClose] to [toClose] inclusive.
     *
     * [body] is the bar's open-minus-close offset (0 makes every bar a doji, which is the honest
     * fixture when only the close series matters) and [wick] the distance from the body to the high
     * and the low.
     */
    fun ramp(
        count: Int,
        fromClose: Double,
        toClose: Double,
        volume: Long = DEFAULT_VOLUME,
        deliveryPercent: Double? = null,
        body: Double = 0.0,
        wick: Double = 0.5,
    ): CandleLab = apply {
        require(count >= 2) { "a ramp needs at least two bars to have endpoints" }
        val step = (toClose - fromClose) / (count - 1)
        for (i in 0 until count) {
            val close = fromClose + step * i
            val open = close + body
            bar(
                open = open,
                high = maxOf(open, close) + wick,
                low = minOf(open, close) - wick,
                close = close,
                volume = volume,
                deliveryPercent = deliveryPercent,
            )
        }
    }

    /** Appends [count] bars at exactly [price]. */
    fun flat(count: Int, price: Double, volume: Long = DEFAULT_VOLUME, deliveryPercent: Double? = null): CandleLab =
        apply {
            repeat(count) {
                bar(price, price, price, price, volume, deliveryPercent)
            }
        }

    /**
     * Appends bars built from a stated **close** list, with `open = previous close` and a stated
     * half-range around the body.
     *
     * This is the form a hand-written close series uses: the closes are the fixture, and the
     * high/low exist only so every bar is structurally valid and ATR is computable.
     */
    fun fromCloses(
        closes: List<Double>,
        volume: Long = DEFAULT_VOLUME,
        halfRange: Double = 0.5,
        deliveries: List<Double?> = List(closes.size) { null },
    ): CandleLab = apply {
        require(closes.size == deliveries.size) { "one delivery reading per bar" }
        var previous: Double? = null
        for (i in closes.indices) {
            val close = closes[i]
            val open = previous ?: close
            bar(
                open = open,
                high = maxOf(open, close) + halfRange,
                low = minOf(open, close) - halfRange,
                close = close,
                volume = volume,
                deliveryPercent = deliveries[i],
            )
            previous = close
        }
    }

    /** The volume a bar must carry for its `volume / volumeSma20` to be exactly [target]. */
    fun volumeForRatio(target: Double, otherVolumes: List<Long>): Long {
        require(target in 0.0..19.0) { "ratio $target cannot occur in a 20-bar mean" }
        val sum = otherVolumes.sum().toDouble()
        return Math.round(target * sum / (20.0 - target)).toLong().coerceAtLeast(1L)
    }

    fun build(): List<Candle> = bars.toList()

    companion object {
        const val DEFAULT_VOLUME: Long = 1_000_000L
    }
}

/**
 * The [RedFlagContext] a technical rule is evaluated against.
 *
 * `indicators` defaults to **null on purpose**: every rule resolves its own snapshot through
 * [indicatorsOrCompute], and running the tests that way proves the fallback works rather than
 * proving that a test remembered to precompute one. The tests that care about the snapshot path
 * pass one explicitly.
 *
 * The [Ratios] placeholder carries a real `asOf` and `Confidence.SINGLE` because
 * `RedFlagContext.hasFundamentals` keys off `ratios.asOf != null`, and a technical-only context
 * should report that it has candle data without claiming a fundamentals parse it never did.
 */
internal fun technicalContext(
    candles: List<Candle>,
    symbol: String = "TEST",
    timeframe: Timeframe = candles.firstOrNull()?.timeframe ?: Timeframe.D1,
    indicators: IndicatorSnapshot? = null,
    today: LocalDate = LocalDate.of(2026, 10, 2),
): RedFlagContext = RedFlagContext(
    symbol = symbol,
    sector = Sector.OTHER,
    ratios = Ratios(
        symbol = symbol,
        sector = Sector.OTHER,
        asOf = LocalDate.of(2026, 10, 1),
        source = Source.SCREENER_IN,
        confidence = Confidence.SINGLE,
    ),
    derived = com.mia.app.domain.model.CashFlowDerived(),
    candles = candles,
    timeframe = timeframe,
    indicators = indicators,
    today = today,
)