package com.mia.app.data.price

import com.mia.app.domain.model.Candle
import com.mia.app.domain.model.ParserHealth
import com.mia.app.domain.model.Source
import com.mia.app.domain.model.Timeframe
import java.time.LocalDate

/**
 * One instrument as published in the NSE EOD bhavcopy, before the series filter.
 *
 * Why a raw row type exists at all when [Candle] already carries OHLCV: [Candle] cannot
 * hold an ISIN, and the ISIN is the app's **stable identity key** (plan.md §3.1, §4.2,
 * §9). The `TATAMOTORS` demerger is the documented failure that forces this: a symbol
 * disappears from the bhavcopy and an ISIN-keyed resolver is the only way to notice.
 * Mapping to [Candle] therefore *loses* information, so the row is the lossless form and
 * [Candle] is the lossy, consumer-facing projection of it.
 *
 * Every field name maps to the exact, verified NSE column spelling in plan.md §3.1 —
 * note that NSE is inconsistent (`FinInstrmTp` vs `FininstrmActlXpryDt`), so the field
 * names here mirror the file rather than tidy Kotlin naming.
 */
data class BhavcopyRow(
    val tradDt: LocalDate,
    val bizDt: LocalDate? = null,
    /** `Sgmt` — `CM` for capital market. */
    val segment: String? = null,
    /** `Src` — `NSE`. */
    val sourceExchange: String? = null,
    /** `FinInstrmTp` — e.g. `STK`, `ETF`, `IDX`. */
    val instrumentType: String? = null,
    /** `FinInstrmId` — NSE's internal id. Stable-ish, but *not* the identity key. */
    val finInstrmId: String? = null,
    /** `ISIN` — the stable identity key (plan.md §3.1). */
    val isin: String? = null,
    /** `TckrSymb` — primary human-facing lookup key. */
    val ticker: String,
    /** `SctySrs` — series code; `EQ` and `SM` are the only ones we keep. */
    val series: String,
    /** `FinInstrmNm`. */
    val name: String? = null,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    /** `LastPric` — last traded price, distinct from the official close. */
    val lastPrice: Double? = null,
    /** `PrvsClsgPric`. */
    val prevClose: Double? = null,
    /** `UndrlygPric`. */
    val underlyingPrice: Double? = null,
    /** `SttlmPric`. */
    val settlementPrice: Double? = null,
    /** `OpnIntrst`. */
    val openInterest: Double? = null,
    /** `ChngInOpnIntrst`. */
    val changeInOpenInterest: Double? = null,
    /** `TtlTradgVol` — shares traded. */
    val volume: Long,
    /** `TtlTrfVal` — turnover in **rupees** (plan.md §3.1). */
    val turnoverValue: Double? = null,
    /** `TtlNbOfTxsExctd`. */
    val trades: Long? = null,
    /** `SsnId` — `F1` for the normal session. */
    val sessionId: String? = null,
    /** `NewBrdLotQty`. */
    val lotSize: Long? = null,
) {
    /**
     * Projection into the price model.
     *
     * Fixed choices, all mandated rather than chosen:
     * - `Timeframe.D1` — plan.md §1.4 rule 9: D1 and W1 only, no intraday.
     * - `Source.NSE_BHAVCOPY` — plan.md §5.1 precedence: OHLCV comes from NSE.
     * - `deliveryPercent = null` — delivery lives in the *legacy* file (plan.md §3.5), not
     *   here. A fabricated 0.0% would manufacture the "delivery collapsed" red flag that
     *   plan.md §8.2 depends on, so null is the only safe value (see `Volume.kt`).
     */
    fun toCandle(): Candle = Candle(
        symbol = ticker,
        timeframe = Timeframe.D1,
        date = tradDt,
        open = open,
        high = high,
        low = low,
        close = close,
        volume = volume,
        deliveryPercent = null,
        source = Source.NSE_BHAVCOPY,
    )
}

/**
 * Why a fetch could not produce candles.
 *
 * A 404 is **not** an error in this system: plan.md §3.1 verified that today's file answers
 * `404` until roughly 18:00 IST. It is a normal, expected state that the EOD worker must be
 * able to distinguish from a network failure, because the right reaction differs — retry
 * later vs. report a broken source.
 */
enum class FetchFailure {
    /** Every host answered 404/410: the session's file is not published (yet). */
    NOT_PUBLISHED,

    /** No host answered 2xx and none answered 404 (DNS, TLS, 403/429/5xx). */
    HOST_UNREACHABLE,

    /** A host answered 2xx but the payload was not a usable bhavcopy. */
    UNPARSEABLE,
}

/**
 * Result of parsing (and possibly fetching) one bhavcopy file.
 *
 * **A parser must never return a silent empty list** (plan.md §12, CONTRACTS.md rule 5).
 * That is enforced structurally here:
 * - `candles` empty ⟹ [health] is `ParserHealth.failed(...)` and/or [fetchFailure] is set.
 * - nothing skipped and nothing missing ⟹ [health].ok.
 * - rows were skipped ⟹ [health] is [ParserHealth.partial] naming what was missing, with
 *   [skippedSamples] carrying the offending lines.
 *
 * [rowsTotal] counts **data rows present in the file**, including series that were filtered
 * out; [rowsSkipped] counts only rows that were *malformed*. Keeping those apart matters:
 * filtering out 661 `BE`/`ST`/`GB`/... rows is the intended behaviour, while skipping a row
 * because `ClsPric` said `n/a` is a defect that must be visible.
 */
data class BhavcopyParseResult(
    val candles: List<Candle> = emptyList(),
    val health: ParserHealth = ParserHealth.failed("not parsed"),
    val rowsTotal: Int = 0,
    val rowsSkipped: Int = 0,
    val skippedSamples: List<String> = emptyList(),
    val tradDt: LocalDate? = null,
    /** Lossless rows behind [candles], same order and same series filter. */
    val rows: List<BhavcopyRow> = emptyList(),
    /**
     * Set when the payload could not be turned into candles: [FetchFailure.UNPARSEABLE] when
     * a parser rejected the bytes, and a host-level reason when [NseBhavcopySource] gave up.
     * Null means the bytes were understood, whether or not rows were skipped (that is what
     * [health] is for).
     */
    val fetchFailure: FetchFailure? = null,
) {
    val rowsKept: Int get() = rows.size

    /** Series filter that produced this result, for diagnostics. */
    val isUsable: Boolean get() = candles.isNotEmpty()

    /**
     * Drops [rows] once a caller has taken what it needs.
     *
     * The EOD worker only needs candles, and plan.md §13 flags budget 2 GB/2 GB-RAM devices,
     * so the row copy is released as soon as identity resolution is done.
     */
    fun withoutRows(): BhavcopyParseResult = copy(rows = emptyList())
}
