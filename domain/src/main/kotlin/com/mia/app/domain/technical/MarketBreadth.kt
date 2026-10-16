package com.mia.app.domain.technical

import java.time.LocalDate

/**
 * A measurement that is either a number or a **stated absence**.
 *
 * ### Why this type and not `Double?`
 * plan.md §12's rule is that a value the app cannot compute must never look like a value it can.
 * A `null` `Double` is ambiguous by construction: it is indistinguishable from "not calculated
 * yet", "the source had no row" and "the denominator was zero", and each of those is a different
 * sentence for the reader. [Measured] makes the absence carry its own [Unavailable.reason], so
 * "52-week highs: not measured — 3 of 451 symbols have fewer than 250 stored sessions" is a value
 * the UI can print, rather than a `-` it would have to invent an explanation for.
 *
 * The same shape serves `Int` and `Double`, which is why it is generic: every statistic in
 * [Breadth] is either a count or a percentage, and both must degrade the same way.
 */
sealed interface Measured<out T> {

    /** A computed value. */
    data class Value<T>(val value: T) : Measured<T>

    /** Not computed, with the reason stated in words a reader can see. */
    data class Unavailable<T>(val reason: String) : Measured<T>

    /** The value, or `null` when this is [Unavailable]. */
    fun valueOrNull(): T? = when (this) {
        is Value -> value
        is Unavailable -> null
    }

    /** The stated reason, or `null` when this is a [Value]. */
    fun reasonOrNull(): String? = when (this) {
        is Value -> null
        is Unavailable -> reason
    }

    /** `true` when a number is actually there. */
    fun isAvailable(): Boolean = this is Value

    companion object {
        /** A measured value, or [Unavailable] carrying [reason]. */
        fun <T> of(value: T?, reason: String): Measured<T> =
            if (value == null) Unavailable(reason) else Value(value)
    }
}

/**
 * One instrument's closes for a single session, as the breadth pass sees it.
 *
 * @param prevClose `PrvsClsgPric` from the bhavcopy. Null when NSE published no previous close
 *   for that row; such a symbol **cannot** be classified as advancing or declining and is counted
 *   separately rather than assumed flat (plan.md §3.1: a blank in the file is an absence, never
 *   a `0.0`).
 */
data class InstrumentClose(
    val symbol: String,
    val close: Double,
    val prevClose: Double? = null,
)

/**
 * One day's whole-market breadth, computed on device from the bhavcopy the app already stores.
 *
 * ### Why this is computed here rather than fetched
 * planv2 W3's observation: breadth is the one piece of market context that needs **no external
 * source at all**. The EOD file already holds every symbol's close and previous close, and the
 * write-once `candles` table already holds each symbol's stored history — so advancers,
 * decliners, 52-week extremes and the share of symbols above their 50-day average are all
 * arithmetic over data the device has. That makes this the load-bearing part of market context
 * even on a day when every network source is unreachable.
 *
 * ### Every statistic degrades to a stated reason
 * No field here is ever a silent zero. A statistic whose inputs were too thin is
 * [Measured.Unavailable] with the count that explains it, because the two mistakes this must not
 * make are "0 new 52-week highs" (which reads as a dead market) and "0%" (which reads as every
 * symbol being below its average) when the truth is "3 symbols have 40 stored sessions".
 *
 * @property advancers / [decliners] / [unchanged] counted **only** over symbols whose previous
 *   close is present and usable. [classified] is their sum and [unclassified] is the rest.
 * @property advanceDeclineRatio advancers ÷ decliners. Stated as unavailable — never `Infinity` —
 *   when nothing declined, because "infinite" would be an arithmetic artefact dressed as a reading.
 * @property newHighs / [newLows] over symbols with at least [MIN_SESSIONS_52_WEEK] stored closes;
 *   [extremesEligible] and [extremesExcluded] say how many qualified either way.
 * @property percentAboveSma50 over symbols with at least [SMA_PERIOD] stored closes; its
 *   denominator is [smaEligible], never the whole market.
 */
data class Breadth(
    val advancers: Int = 0,
    val decliners: Int = 0,
    val unchanged: Int = 0,
    /** Symbols that could be classified at all: advancers + decliners + unchanged. */
    val classified: Int = 0,
    /** Symbols whose previous close was missing or non-positive, so no verdict was possible. */
    val unclassified: Int = 0,
    val advanceDeclineRatio: Measured<Double> = Measured.Unavailable("no session was measured"),
    val newHighs: Measured<Int> = Measured.Unavailable("no session was measured"),
    val newLows: Measured<Int> = Measured.Unavailable("no session was measured"),
    /** Symbols with enough stored closes for a 52-week extreme to be meaningful. */
    val extremesEligible: Int = 0,
    /** Symbols excluded from [newHighs] / [newLows] for want of stored history. */
    val extremesExcluded: Int = 0,
    val aboveSma50: Measured<Int> = Measured.Unavailable("no session was measured"),
    /** Symbols with enough stored closes for a 50-day average. */
    val smaEligible: Int = 0,
    /** Symbols excluded from the SMA-50 population for want of stored history. */
    val smaExcluded: Int = 0,
    val percentAboveSma50: Measured<Double> = Measured.Unavailable("no session was measured"),
    /** The session these figures describe, when the caller knows it. */
    val asOf: LocalDate? = null,
    /** Diagnostics: what was excluded and why. Never rendered as a verdict. */
    val notes: List<String> = emptyList(),
) {
    /** True when every statistic on this object is a real number. */
    val isComplete: Boolean
        get() = advanceDeclineRatio.isAvailable() &&
            newHighs.isAvailable() &&
            newLows.isAvailable() &&
            aboveSma50.isAvailable() &&
            percentAboveSma50.isAvailable()

    /**
     * The one-line market summary, factual and free of any prediction.
     *
     * Written here rather than in `:app` so the arithmetic and its wording cannot drift apart.
     * It reports counts; it never says what they mean for a reader's money.
     */
    fun summary(): String = buildString {
        append("Advancers ").append(advancers)
        append(" · Decliners ").append(decliners)
        append(" · Unchanged ").append(unchanged)
        asOf?.let { append(" (as of ").append(it).append(')') }
        if (unclassified > 0) append(" · ").append(unclassified).append(" not classifiable")
    }
}

/**
 * On-device market breadth — planv2 W3's "market pulse" numbers.
 *
 * ### The inputs, and why they are exactly these
 * * [InstrumentClose] for **one** session: the bhavcopy's `CLS_PRIC` and `PRVSCLS_PRIC`
 *   (plan.md §3.1). Both are already on the device the moment a refresh succeeds.
 * * A per-symbol history of **closes, oldest first** — the write-once `candles` table read
 *   through `:android-data`. The order is load-bearing and is part of the contract: the 50-day
 *   average takes the **last** [SMA_PERIOD] entries, which is wrong on a series given in any
 *   other order, and a window whose extremes are read off the wrong end would report the
 *   52-week high as the 52-week low.
 *
 * ### What is deliberately *not* done
 * * No breadth is computed from a subset presented as the market. A symbol with no stored history
 *   is **excluded and counted**, so the denominator is always visible on screen.
 * * No 52-week extreme is inferred from a shorter window. Fewer than [MIN_SESSIONS_52_WEEK]
 *   closes is not "a 52-week high"; it is an unknown, and the number of unknowns is reported.
 */
object MarketBreadth {

    /**
     * Closes a symbol needs before its extremes can be called 52-week ones.
     *
     * 250 is the conventional ~52 weeks of trading sessions. It is a **floor**, not a target: a
     * symbol with 250 stored closes genuinely has a year of history, and one with 249 does not
     * have a year of history however the window is sliced.
     */
    const val MIN_SESSIONS_52_WEEK: Int = 250

    /** The moving-average period behind [Breadth.percentAboveSma50]. */
    const val SMA_PERIOD: Int = 50

    /**
     * Computes breadth for one session. Pure, synchronous and total.
     *
     * @param day every instrument's close for the session, in any order.
     * @param histories symbol → its stored closes, **oldest first**. A symbol absent from the
     *   map is treated as having no stored history and is excluded from both history-dependent
     *   populations, and counted as excluded.
     */
    fun compute(
        day: List<InstrumentClose>,
        histories: Map<String, List<Double>> = emptyMap(),
        asOf: LocalDate? = null,
    ): Breadth {
        val notes = mutableListOf<String>()

        // ---- advancers / decliners / unchanged
        //
        // A symbol is classified only when it has a usable previous close. Comparing against a
        // missing previous close would make every unclassified symbol look flat, inflating
        // "unchanged" with rows the file never described.
        var advancers = 0
        var decliners = 0
        var unchanged = 0
        var unclassified = 0
        val closes = HashMap<String, Double>(day.size * 2)

        for (row in day) {
            val key = row.symbol.trim().uppercase()
            if (key.isEmpty()) continue
            val close = row.close
            if (!close.isFinite() || close <= 0.0) {
                unclassified++
                continue
            }
            closes[key] = close
            val previous = row.prevClose
            when {
                previous == null || !previous.isFinite() || previous <= 0.0 -> unclassified++
                close > previous -> advancers++
                close < previous -> decliners++
                else -> unchanged++
            }
        }

        val classified = advancers + decliners + unchanged
        if (unclassified > 0) {
            notes += "$unclassified symbol(s) had no usable previous close, so they are counted " +
                "neither as advancing nor as declining (the bhavcopy publishes PrvsClsgPric for " +
                "most rows but not all — plan.md §3.1)"
        }

        val ratio: Measured<Double> = when {
            classified == 0 -> Measured.Unavailable(
                "no symbol in this session had both a close and a previous close to compare",
            )
            // Division by zero is not a reading. "Advance-decline ratio: ∞" would be arithmetic
            // dressed as a fact, so the honest answer names the two counts instead.
            decliners == 0 -> Measured.Unavailable(
                "$advancers advancer(s) and no decliner(s) in this session, so the ratio is not a " +
                    "finite number",
            )
            else -> Measured.Value(advancers.toDouble() / decliners.toDouble())
        }

        // ---- 52-week extremes and the share above the 50-day average
        var newHighs = 0
        var newLows = 0
        var extremesEligible = 0
        var aboveSma = 0
        var smaEligible = 0

        for ((symbol, close) in closes) {
            val window = histories[symbol]
                ?.filter { it.isFinite() && it > 0.0 }
                .orEmpty()

            if (window.size >= MIN_SESSIONS_52_WEEK) {
                extremesEligible++
                // `>=` / `<=`: an unchanged close at the window high is still *at* the high. A
                // strict `>` would report a flat market as having no extremes at all.
                val highest = window.maxOrNull()
                val lowest = window.minOrNull()
                if (highest != null && close >= highest) newHighs++
                if (lowest != null && close <= lowest) newLows++
            }

            if (window.size >= SMA_PERIOD) {
                smaEligible++
                // The newest [SMA_PERIOD] closes, in stored order — an average of the highest 50
                // values would be a different number entirely.
                if (close > window.takeLast(SMA_PERIOD).average()) aboveSma++
            }
        }

        val excludedForExtremes = closes.size - extremesEligible
        val excludedForSma = closes.size - smaEligible
        if (excludedForExtremes > 0) {
            notes += "$excludedForExtremes of ${closes.size} symbol(s) have fewer than " +
                "$MIN_SESSIONS_52_WEEK stored closes, so they are excluded from the 52-week " +
                "high/low counts rather than being reported as holding no extreme"
        }
        if (excludedForSma > 0) {
            notes += "$excludedForSma of ${closes.size} symbol(s) have fewer than $SMA_PERIOD " +
                "stored closes, so they are excluded from the share above the $SMA_PERIOD-day average"
        }

        val noYear = "no symbol in this session has $MIN_SESSIONS_52_WEEK stored closes, so no " +
            "52-week extreme can be measured"
        val noSma = "no symbol in this session has $SMA_PERIOD stored closes, so no share above " +
            "the $SMA_PERIOD-day average can be measured"

        return Breadth(
            advancers = advancers,
            decliners = decliners,
            unchanged = unchanged,
            classified = classified,
            unclassified = unclassified,
            advanceDeclineRatio = ratio,
            newHighs = if (extremesEligible == 0) Measured.Unavailable(noYear) else Measured.Value(newHighs),
            newLows = if (extremesEligible == 0) Measured.Unavailable(noYear) else Measured.Value(newLows),
            extremesEligible = extremesEligible,
            extremesExcluded = excludedForExtremes,
            aboveSma50 = if (smaEligible == 0) Measured.Unavailable(noSma) else Measured.Value(aboveSma),
            smaEligible = smaEligible,
            smaExcluded = excludedForSma,
            percentAboveSma50 = if (smaEligible == 0) {
                Measured.Unavailable(noSma)
            } else {
                Measured.Value(aboveSma.toDouble() / smaEligible.toDouble() * 100.0)
            },
            asOf = asOf,
            notes = notes,
        )
    }
}