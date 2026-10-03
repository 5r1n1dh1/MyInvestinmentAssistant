package com.mia.app.ui.share

import com.mia.app.domain.model.BreakoutTrigger
import com.mia.app.domain.model.Candle
import com.mia.app.domain.model.HitRate
import com.mia.app.domain.model.Match
import com.mia.app.domain.model.MeasuredMove
import com.mia.app.domain.model.RedFlag
import com.mia.app.domain.model.Source
import com.mia.app.domain.model.Timeframe
import java.time.LocalDate

/**
 * One stock brief, as data.
 *
 * ### Why the data and the text are separate types
 * [BriefCardContent] turns this into a flat list of styled lines, and `renderBriefBitmap` draws that
 * list. Nothing in this file knows about pixels, and nothing in the renderer knows about a `Match`.
 * That split is what makes the compliance claim in the renderer checkable: the exact words that go
 * into the image can be printed without constructing a `Bitmap`, a `Canvas` or a device.
 *
 * ### Every field is nullable except the identity ones, and that is the point
 * A missing close renders "—" ([com.mia.app.ui.common.NOT_AVAILABLE]) rather than a zero, because a
 * shared image saying "₹0.00" is a false statement about a listed company. The same applies to the
 * hit rate: it renders whatever `HitRate.label()` says, which withholds the percentage below 30
 * samples and always carries `n`.
 *
 * ### What is deliberately absent
 * No field for a forecast, an expected return, or a verdict. The image can only ever be built from
 * measurements the app already shows on screen, so there is nothing to accidentally leak.
 */
data class BriefCard(
    /** NSE symbol, uppercase. */
    val symbol: String,
    /** Registered company name, when the symbol master has one. */
    val companyName: String? = null,

    /** Which bars every figure on the image came from. */
    val timeframe: Timeframe = Timeframe.D1,

    /**
     * The last bar's date. Mandatory in practice: plan.md §1.4 rule 3 makes the as-of date
     * non-optional, so a card without one renders the missing-marker rather than omitting the line.
     */
    val asOf: LocalDate? = null,

    /** Where the close came from. Rendered on every figure's section header. */
    val priceSource: Source = Source.NSE_BHAVCOPY,

    val lastClose: Double? = null,

    /** Day change in percent, or `null` for a single stored bar. */
    val changePercent: Double? = null,

    /** The best-scoring renderable match, or `null` when none cleared the threshold. */
    val topMatch: Match? = null,

    /** The top match's measured move. `null` for a candlestick, which has no height. */
    val measuredMove: MeasuredMove? = null,

    /** The breakout condition. Never merged into the measured move (decision D2). */
    val breakoutTrigger: BreakoutTrigger? = null,

    /**
     * Per stock **and** per pattern (decision D6). `null` when the symbol has never been tracked,
     * which is a different fact from "tracked but withheld".
     */
    val trackRecord: HitRate? = null,

    /** This symbol across every tracked pattern. */
    val trackRecordForSymbol: HitRate? = null,

    /** Flags to carry, with their evidence already attached. */
    val redFlags: List<RedFlag> = emptyList(),

    /** Transparency notes from the scan: warm-ups, missing benchmarks, unscored factors. */
    val notes: List<String> = emptyList(),

    /** Stored bars, used only for the coverage line. */
    val barCount: Int = 0,

    /** The gate the bars were measured against. */
    val minimumBars: Int = 0,
) {
    /** Builds a card from bars already in hand, so no data source call is needed for a preview. */
    companion object {
        fun fromCandles(
            symbol: String,
            companyName: String? = null,
            timeframe: Timeframe = Timeframe.D1,
            candles: List<Candle>,
            minimumBars: Int = 0,
            topMatch: Match? = null,
        ): BriefCard {
            val last = candles.lastOrNull()
            val previous = candles.getOrNull(candles.lastIndex - 1)
            val change = if (last != null && previous != null && previous.close > 0.0) {
                (last.close - previous.close) / previous.close * 100.0
            } else {
                null
            }
            return BriefCard(
                symbol = symbol,
                companyName = companyName,
                timeframe = timeframe,
                asOf = last?.date,
                lastClose = last?.close,
                changePercent = change,
                topMatch = topMatch,
                measuredMove = topMatch?.measuredMove,
                barCount = candles.size,
                minimumBars = minimumBars,
            )
        }
    }
}