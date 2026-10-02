package com.mia.app.data.symbols

import com.mia.app.domain.model.Source
import java.time.LocalDate

/**
 * One listed instrument as published in the NSE symbol master (`EQUITY_L.csv`).
 *
 * **Why this type exists at all**, given that `Candle.symbol` already looks like an identity:
 * a ticker is *not* an identity. plan.md §9 records the measured failure — `TATAMOTORS`
 * returns zero rows on Yahoo *and* is absent from the official NSE bhavcopy, because it
 * demerged. A symbol-keyed app therefore shows a blank screen for a stock that still trades.
 * The fix is to key on [isin], which survives every rename, and to keep [symbol] as a
 * *mutable, human-facing label*. [SymbolResolver] and [BhavcopyReconciler] exist to translate
 * between the two.
 *
 * [symbol] and [isin] are non-blank by construction — a row missing either one has no usable
 * identity and [SymbolBookParser] rejects and counts it rather than inventing an empty key.
 * Every other field is nullable because NSE publishes blanks (verified on the 2026-10-01
 * capture: no blanks today, but a future file may have them and a blank must never become 0.0).
 *
 * [source] travels with the record so any figure the UI renders from it can be attributed,
 * as plan.md §1.4 rule 3 requires.
 */
data class SymbolRecord(
    /** NSE ticker, upper-cased and trimmed. `RELIANCE`, `BAJAJ-AUTO`, `M&M`. */
    val symbol: String,
    /** `NAME OF COMPANY` verbatim, e.g. `Reliance Industries Limited`. */
    val name: String,
    /** NSE series code: `EQ` (equity), `BE` (book entry), `BZ` (odd-lot series). */
    val series: String,
    /** ISIN — **the stable identity key** (plan.md §3.1, §3.4, §9). */
    val isin: String,
    /** `DATE OF LISTING` as `dd-MMM-yyyy` in the file, e.g. `29-NOV-1995`. */
    val listingDate: LocalDate? = null,
    /** `PAID UP VALUE`, rupees. */
    val paidUpValue: Double? = null,
    /** `MARKET LOT` — shares per tradable lot. */
    val lotSize: Long? = null,
    /** `FACE VALUE`, rupees. */
    val faceValue: Double? = null,
    /** Provenance of this row. `Source.NSE_EQUITY_MASTER` in production. */
    val source: Source = Source.NSE_EQUITY_MASTER,
) {
    init {
        require(symbol.isNotBlank()) { "symbol must not be blank — a record without a ticker has no identity" }
        require(isin.isNotBlank()) { "isin must not be blank — it is the stable identity key (plan.md §9)" }
    }

    /** True for a normal listed equity, the only series the app charts (plan.md §3.1). */
    val isEquity: Boolean get() = series == "EQ"

    /**
     * A listing date is only used for identity messaging, never for maths, so this formats a
     * real date as ISO and states plainly when the master did not publish one.
     */
    fun listingDateLabel(): String = listingDate?.toString() ?: "unknown"
}