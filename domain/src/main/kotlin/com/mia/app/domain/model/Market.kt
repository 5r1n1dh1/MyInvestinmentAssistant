package com.mia.app.domain.model

import java.time.LocalDate

/**
 * Canonical market vocabulary (plan.md §5).
 *
 * These types are the contract between every module. `:domain` stays free of Android and
 * of `:data`, so all cross-module data crosses this boundary as plain Kotlin.
 */

enum class Timeframe(val barsPerYear: Int) {
    /** Daily bars. plan.md D1: intraday was removed on purpose. */
    D1(252),

    /** Weekly bars. */
    W1(52),
    ;

    companion object {
        fun fromStorageKey(key: String): Timeframe? = entries.firstOrNull { it.name == key }
    }
}

/** Where a value came from. Never hide this from the user (plan.md §1.4 rule 3). */
enum class Source {
    NSE_BHAVCOPY,
    YAHOO,
    SCREENER_IN,
    NSE_EQUITY_MASTER,
    DERIVED,
}

/**
 * How much to trust a value after merging sources.
 *
 * plan.md §5.1: sources agree on price and disagree on fundamentals, so we merge per field
 * and surface disagreement instead of averaging it away.
 */
enum class Confidence {
    /** Two sources, within tolerance. */
    AGREED,

    /** Only one source had the field. */
    SINGLE,

    /** Two sources disagree by more than the tolerance (5% by default). */
    CONFLICT,

    /** Value is older than the freshness window for its kind. */
    STALE,
}

/**
 * Indian market sectors. Drives which ratios matter (plan.md §7.3).
 *
 * Resolution must default to [OTHER] and never guess (plan.md §7.1).
 */
enum class Sector {
    BANKING,
    NBFC,
    IT,
    PHARMA,
    FMCG,
    AUTO,
    INSURANCE,
    METALS,
    CEMENT,
    POWER,
    RENEWABLE,
    DEFENCE,
    RAILWAY,
    REALTY,
    TELECOM,
    AVIATION,
    DURABLES,
    RETAIL,
    LOGISTICS,
    CAPITAL_GOODS,
    CHEMICALS,
    /**
     * Upstream, refining and midstream energy.
     *
     * Added because plan.md §5's list had no energy bucket at all, which made every Indian oil and
     * gas name unresolvable — and it is not a rare case: ONGC, IOC, BPCL, GAIL, Oil India and
     * Reliance Industries are all large caps. The omission was carried deliberately as a
     * *documented gap* (`SectorResolverTest` asserted RELIANCE stayed `OTHER`), on the reasoning
     * that an unnamed bucket invites a guess. It is named now because screener.in publishes the
     * classification itself: RELIANCE's page has always said `Energy → Oil, Gas & Consumable
     * Fuels → Petroleum Products → Refineries & Marketing`, so this sector is read off the source
     * rather than inferred from a company name.
     */
    OIL_GAS,
    OTHER,
}

/** One OHLCV bar. `deliveryPercent` is null when the legacy source is unavailable. */
data class Candle(
    val symbol: String,
    val timeframe: Timeframe,
    val date: LocalDate,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Long,
    val deliveryPercent: Double? = null,
    val source: Source = Source.NSE_BHAVCOPY,
) {
    val isBullish: Boolean get() = close >= open
    val range: Double get() = high - low
    val body: Double get() = kotlin.math.abs(close - open)

    /** True when the bar is structurally usable (no zero/negative prices, sane range). */
    val isValid: Boolean
        get() = open > 0 && high > 0 && low > 0 && close > 0 &&
            high >= low && high >= open && high >= close && low <= open && low <= close
}

/** A value plus its provenance, as required by plan.md §1.4 rule 3. */
data class Merged<T : Any>(
    val value: T,
    val source: Source,
    val confidence: Confidence,
    val asOf: LocalDate,
    /** The disagreeing value from the other source, kept for display — never averaged. */
    val alternate: T? = null,
    val alternateSource: Source? = null,
) {
    val hasConflict: Boolean get() = confidence == Confidence.CONFLICT && alternate != null
}

/**
 * Fundamentals snapshot (plan.md §5).
 *
 * Field order follows the plan's priority order in §7.2 loosely; the authoritative order
 * lives in `domain/ratios/RatioPriority` so the UI and the share-image stay in sync.
 */
data class Ratios(
    val symbol: String,
    val sector: Sector,
    /**
     * screener.in's own industry classification, e.g. `"Energy, Oil, Gas & Consumable Fuels, …"`.
     *
     * Carried on [Ratios] rather than only on `FundamentalsSnapshot` because **the ratios row is
     * what survives the cache**: the snapshot is rebuilt from it on every read, so anything kept
     * only on the snapshot is lost the moment a row is cached. `SectorResolver`'s highest-precedence
     * input is this text, so losing it silently downgraded every cached symbol to name-only matching.
     *
     * `null` is legal and means "this source published no industry classification", which the
     * resolver reports honestly rather than treating as a failure.
     */
    val industry: String? = null,
    val asOf: LocalDate,
    val source: Source,
    val confidence: Confidence,
    // Core
    val roce: Double? = null,
    val roe: Double? = null,
    val debtToEquity: Double? = null,
    val interestCoverage: Double? = null,
    val operatingMargin: Double? = null,
    val fcf: Double? = null,
    val pat: Double? = null,
    val cfoToOp: Double? = null,
    val revenueCagr3y: Double? = null,
    val revenueGrowth: Double? = null,
    val grossMargin: Double? = null,
    val pe: Double? = null,
    val pb: Double? = null,
    val evToEbitda: Double? = null,
    val dividendYield: Double? = null,
    val promoterHolding: Double? = null,
    val marketCap: Double? = null,
) {
    val isResolvedSector: Boolean get() = sector != Sector.OTHER
}