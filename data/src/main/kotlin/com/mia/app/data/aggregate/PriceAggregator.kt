package com.mia.app.data.aggregate

import com.mia.app.data.history.HistoryResult
import com.mia.app.data.price.BhavcopyParseResult
import com.mia.app.data.price.LegacyDeliveryResult
import com.mia.app.domain.model.Candle
import com.mia.app.domain.model.Confidence
import com.mia.app.domain.model.Merged
import com.mia.app.domain.model.Source
import com.mia.app.domain.model.Timeframe
import com.mia.app.domain.ratios.FreshnessKind
import java.time.LocalDate

/**
 * One OHLCV field, merged per field (plan.md §5.1).
 *
 * [merged] is `null` only when neither source published the field. It is never a zero and never
 * a silent blank.
 */
data class PriceField(
    val id: PriceFieldId,
    val merged: Merged<Double>?,
    val nse: Double?,
    val yahoo: Double?,
    /** Fraction; `null` when undefined or when only one side has the field. */
    val divergence: Double?,
) {
    val divergencePercent: Double? get() = divergence?.let { it * 100.0 }

    val confidence: Confidence? get() = merged?.confidence
    val hasConflict: Boolean get() = merged?.hasConflict == true

    /** The greyed line plan.md §5.1 asks for, or `null` when the sources did not disagree. */
    fun chip(): String? = merged?.let {
        if (!it.hasConflict) null
        else DivergentField(
            field = id.name,
            shown = it.value,
            shownSource = it.source,
            alternate = it.alternate!!,
            alternateSource = it.alternateSource ?: Source.YAHOO,
            divergence = AggregationRule.divergence(it.value, it.alternate),
            unit = id.unit,
            asOf = it.asOf,
        ).chip()
    }
}

/** The price fields plan.md §5.1's precedence table covers. */
enum class PriceFieldId(val title: String, val unit: String) {
    OPEN("Open", "₹"),
    HIGH("High", "₹"),
    LOW("Low", "₹"),
    CLOSE("Close", "₹"),
    VOLUME("Volume", "shares"),
    DELIVERY_PERCENT("Delivery", "%"),
}

/**
 * The result of merging one session's price across sources.
 *
 * [candle] is the bar the app should store — the NSE bar, per plan.md §5.1 — carrying the merged
 * field values *and* whatever the legacy file said about delivery (plan.md §3.5).
 */
data class PriceMerge(
    val symbol: String,
    val asOf: LocalDate,
    val fields: List<PriceField>,
    val candle: Candle?,
    val report: DivergenceReport,
) {
    fun field(id: PriceFieldId): PriceField? = fields.firstOrNull { it.id == id }

    val close: Merged<Double>? get() = field(PriceFieldId.CLOSE)?.merged

    /** The delivery percentage merged as its own field, `null` when the legacy file has none. */
    val deliveryPercent: Double? get() = field(PriceFieldId.DELIVERY_PERCENT)?.merged?.value

    val hasConflicts: Boolean get() = report.hasConflicts
}

/**
 * Merges the price layer field by field (plan.md §5.1).
 *
 * ### The rule being implemented, not approximated
 *
 * > Precedence: `price/OHLCV → NSE` (then Yahoo).
 *
 * NSE wins because the bhavcopy is the official end-of-day publication, needs no auth, and one
 * request returns the whole market (plan.md §3.1, §13). The measured evidence that the two
 * sources *agree* is plan.md §5.1's RELIANCE row: **1168.0 vs 1167.7 → 0.03% → agreed**, and
 * those two numbers are the app's cross-check that nothing has gone wrong upstream.
 *
 * When they do *not* agree the divergence is surfaced, not averaged: [PriceMerge.report] carries
 * the row and the UI renders Yahoo's number greyed beneath NSE's.
 *
 * ### Delivery percentage
 * `DELIV_PER` comes from a *different* NSE file (plan.md §3.5, availability UNVERIFIED there)
 * and is attached to the NSE bar when present. It is never defaulted to `0.0`: plan.md §8.2's
 * "delivery collapsed" red flag and `domain/technical/Volume.kt` both treat a 0% bar as a real
 * observation, so a fabricated zero would manufacture a red flag out of missing data.
 */
object PriceAggregator {

    /**
     * Fields the price layer can produce, in the order a UI should render them.
     */
    val FIELDS: List<PriceFieldId> = listOf(
        PriceFieldId.OPEN,
        PriceFieldId.HIGH,
        PriceFieldId.LOW,
        PriceFieldId.CLOSE,
        PriceFieldId.VOLUME,
        PriceFieldId.DELIVERY_PERCENT,
    )

    /**
     * Nothing in the price layer publishes these, so they are named as unavailable rather than
     * left as blanks that look like zeros (plan.md §13).
     */
    val NOT_OBTAINABLE: List<UnavailableField> = listOf(
        UnavailableField(
            "DELIVERY_PERCENT_BEFORE_LEGACY",
            "plan.md §3.5's legacy sec_bhavdata file is the only delivery source and its " +
                "availability for recent dates is UNVERIFIED, so historical delivery % is absent",
        ),
    )

    /**
     * Merges the most recent session.
     *
     * @param bhavcopy the parsed NSE file. Null or empty is not an error — Yahoo may still serve
     *   the session, and the D1 tab can run entirely on Yahoo if NSE has not published.
     * @param yahoo a `2y/1d` [HistoryResult]. The bar matched by date is the comparison; a
     *   different date is a *different session* and is never compared.
     * @param delivery optional legacy result; when it carries the symbol, the percentage attaches
     *   to the merged bar.
     */
    fun mergeSession(
        symbol: String,
        bhavcopy: BhavcopyParseResult?,
        yahoo: HistoryResult? = null,
        delivery: LegacyDeliveryResult? = null,
        today: LocalDate,
    ): PriceMerge {
        val nseBar = bhavcopy?.candles?.firstOrNull { it.symbol == symbol }
        val yahooBar = yahoo?.candles?.lastOrNull()

        val notes = mutableListOf<String>()
        if (nseBar == null) notes += "no $symbol row in the NSE bhavcopy file (${bhavcopy?.tradDt ?: "no date"}); " +
            "the session's price comes from Yahoo alone"
        if (yahoo == null) notes += "no Yahoo history was supplied; the session's price comes from the NSE bhavcopy alone"

        // Two bars are only two opinions about a price if they are the *same session*. The last
        // session is routinely absent from one side of a backfill (the Yahoo 2y/1d window closes
        // before the newest file, and a holiday or a rollback moves the boundary), and comparing
        // across sessions would report yesterday's close against today's as a 5% "conflict".
        // Suppressed only when there is genuinely nothing to compare against: with one side
        // absent the other side is the session's price, not an opinion being overruled.
        val sameSession = nseBar == null || yahooBar == null || nseBar.date == yahooBar.date
        if (nseBar != null && yahooBar != null && !sameSession) {
            notes += "the newest Yahoo bar is ${yahooBar.date} but the bhavcopy session is " +
                "${nseBar.date}; two different sessions are not two opinions, so no divergence was " +
                "computed and the session's price is the bhavcopy's alone"
        }

        val fields = FIELDS.map { id ->
            mergeField(
                id = id,
                nse = nseValue(id, nseBar, delivery),
                yahoo = if (sameSession) yahooValue(id, yahooBar) else null,
                nseAsOf = nseBar?.date,
                yahooAsOf = if (sameSession) yahooBar?.date else null,
                today = today,
            )
        }

        val candle = buildCandle(nseBar, yahooBar, fields, symbol, delivery, notes)
        val asOf = nseBar?.date ?: yahooBar?.date ?: bhavcopy?.tradDt ?: today

        return PriceMerge(
            symbol = symbol,
            asOf = asOf,
            fields = fields,
            candle = candle,
            report = DivergenceReport(
                symbol = symbol,
                asOf = asOf,
                conflicts = conflictsOf(fields, asOf),
                unavailable = NOT_OBTAINABLE,
                notes = notes,
            ),
        )
    }

    /**
     * Merges two daily series into one, per field per date (plan.md §5.1).
     *
     * The NSE bar wins every date it covers — it is the precedence winner for OHLCV and it is
     * what the rest of the app has always shown. Yahoo fills the dates NSE has no file for,
     * which is most of a 500-bar backfill. Delivery is attached from [delivery] when the symbol
     * has a published figure, and left `null` otherwise.
     *
     * Input order does not matter: the result is sorted by date. No clock is needed because a
     * candle carries its own date; staleness of the *series* is `HistoryFallbackPlanner`'s job
     * (plan.md §10) and staleness of a *ratio* is `FreshnessPolicy`'s.
     */
    fun mergeSeries(
        nseDaily: List<Candle>,
        yahooDaily: List<Candle>,
        delivery: LegacyDeliveryResult? = null,
    ): List<Candle> {
        val byDate = LinkedHashMap<LocalDate, Candle>()
        yahooDaily.forEach { byDate[it.date] = it.copy(source = Source.YAHOO) }
        nseDaily.forEach { byDate[it.date] = it.copy(source = Source.NSE_BHAVCOPY) }
        val merged = byDate.values.sortedBy { it.date }.map { candle ->
            val pct = deliveryOf(delivery, candle.symbol, candle.date)
            if (pct == null) candle else candle.copy(deliveryPercent = pct)
        }
        return merged
    }

    /** Merges one field. Exposed because a UI sometimes reconciles a single number. */
    fun mergeField(
        id: PriceFieldId,
        nse: Double?,
        yahoo: Double?,
        nseAsOf: LocalDate?,
        yahooAsOf: LocalDate?,
        today: LocalDate,
    ): PriceField = PriceField(
        id = id,
        merged = AggregationRule.merged(
            shown = nse,
            other = yahoo,
            shownSource = Source.NSE_BHAVCOPY,
            otherSource = Source.YAHOO,
            shownAsOf = nseAsOf,
            otherAsOf = yahooAsOf,
            today = today,
            kind = FreshnessKind.PRICE,
        ),
        nse = nse,
        yahoo = yahoo,
        divergence = AggregationRule.divergence(nse, yahoo),
    )

    private fun nseValue(id: PriceFieldId, bar: Candle?, delivery: LegacyDeliveryResult?): Double? = when (id) {
        PriceFieldId.OPEN -> bar?.open
        PriceFieldId.HIGH -> bar?.high
        PriceFieldId.LOW -> bar?.low
        PriceFieldId.CLOSE -> bar?.close
        PriceFieldId.VOLUME -> bar?.volume?.toDouble()
        PriceFieldId.DELIVERY_PERCENT -> bar?.let {
            deliveryOf(delivery, it.symbol, it.date)
        }
    }

    private fun yahooValue(id: PriceFieldId, bar: Candle?): Double? = when (id) {
        PriceFieldId.OPEN -> bar?.open
        PriceFieldId.HIGH -> bar?.high
        PriceFieldId.LOW -> bar?.low
        PriceFieldId.CLOSE -> bar?.close
        PriceFieldId.VOLUME -> bar?.volume?.toDouble()
        // Yahoo publishes no delivery figure at all; plan.md §5.1 lists delivery as a screener.in/
        // bhavcopy-family field, and plan.md §3.5 names the legacy file as its only source.
        PriceFieldId.DELIVERY_PERCENT -> null
    }

    /**
     * The bar to store.
     *
     * NSE's bar, **field by field**: an NSE open with a Yahoo close would be a bar that never
     * traded. The only mixed case the code below allows is an entire bar from Yahoo when NSE has
     * no row for the session, tagged [Source.YAHOO] so the UI can say where it came from.
     */
    private fun buildCandle(
        nseBar: Candle?,
        yahooBar: Candle?,
        fields: List<PriceField>,
        symbol: String,
        delivery: LegacyDeliveryResult?,
        notes: MutableList<String>,
    ): Candle? {
        val asOf = nseBar?.date ?: yahooBar?.date ?: return null
        val timeframe = Timeframe.D1
        val open = fields.value(PriceFieldId.OPEN)
        val high = fields.value(PriceFieldId.HIGH)
        val low = fields.value(PriceFieldId.LOW)
        val close = fields.value(PriceFieldId.CLOSE)
        val volume = fields.value(PriceFieldId.VOLUME)
        if (open == null || high == null || low == null || close == null) return null
        // A published DELIV_PER belongs to the exact session it was published for (plan.md §3.5
        // is one file per day), so it is attached only on a date match. Attaching it to every bar
        // of a merged series would state a single day's delivery as if it were the whole history.
        val pct = nseBar?.let { deliveryOf(delivery, symbol, it.date) }
            ?: deliveryOf(delivery, symbol, asOf)
            ?: nseBar?.deliveryPercent
            ?: yahooBar?.deliveryPercent
        if (pct == null) {
            notes += "delivery percentage is absent for $symbol on $asOf; it stays null so that " +
                "plan.md §8.2's delivery-collapse flag cannot fire on missing data"
        }
        if (nseBar != null && !fields.allAgree()) {
            notes += "the stored bar keeps the NSE value for every field it published; a Yahoo " +
                "value was used only where NSE published none"
        }
        return Candle(
            symbol = symbol,
            timeframe = timeframe,
            date = asOf,
            open = open,
            high = high,
            low = low,
            close = close,
            volume = (volume ?: 0.0).toLong(),
            deliveryPercent = pct,
            source = if (nseBar != null) Source.NSE_BHAVCOPY else Source.YAHOO,
        )
    }

    /**
     * The published `DELIV_PER` for [symbol] **on [date]**, or `null`.
     *
     * `LegacyDeliveryResult.tradDt` is the session the file was published for, so a bar from any
     * other date has no published delivery figure and must not borrow this one.
     */
    private fun deliveryOf(delivery: LegacyDeliveryResult?, symbol: String, date: LocalDate): Double? =
        delivery?.takeIf { it.tradDt == date }?.deliveryPercentBySymbol?.get(symbol)

    private fun List<PriceField>.value(id: PriceFieldId): Double? =
        firstOrNull { it.id == id }?.merged?.value

    private fun List<PriceField>.allAgree(): Boolean =
        none { it.hasConflict }

    private fun conflictsOf(fields: List<PriceField>, asOf: LocalDate): List<DivergentField> =
        fields.mapNotNull { field ->
            val merged = field.merged ?: return@mapNotNull null
            if (!merged.hasConflict) return@mapNotNull null
            DivergentField(
                field = field.id.name,
                shown = merged.value,
                shownSource = merged.source,
                alternate = merged.alternate!!,
                alternateSource = merged.alternateSource ?: Source.YAHOO,
                divergence = AggregationRule.divergence(merged.value, merged.alternate),
                unit = field.id.unit,
                asOf = merged.asOf,
            )
        }
}