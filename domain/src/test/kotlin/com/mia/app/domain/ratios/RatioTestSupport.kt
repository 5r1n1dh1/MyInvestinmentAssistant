package com.mia.app.domain.ratios

import com.mia.app.domain.model.AnnualRow
import com.mia.app.domain.model.CashFlowDerived
import kotlin.math.abs
import kotlin.test.assertTrue

/**
 * Shared helpers for the ratios tests.
 *
 * The tolerance is used only where the expected value is a division or a root of a
 * hand-derived rational (plan.md §12: golden-file tests assert exact values; computed values
 * carry the numeric slack that floating point requires and nothing more).
 */
internal const val RATIO_TOL: Double = 1e-9

internal fun assertRatioClose(expected: Double, actual: Double?, tolerance: Double = RATIO_TOL, message: String = "") {
    if (actual == null) {
        throw AssertionError("${message}expected $expected but was null".trim())
    }
    assertTrue(
        abs(expected - actual) <= tolerance,
        "$message expected $expected but was $actual (delta ${abs(expected - actual)})",
    )
}

/** Asserts no non-null value in the collection is NaN or infinite. plan.md §12. */
internal fun assertAllFinite(what: String, values: Iterable<Double?>) {
    values.forEach { value ->
        if (value == null) return@forEach
        assertTrue(!value.isNaN(), "$what produced NaN")
        assertTrue(!value.isInfinite(), "$what produced $value")
    }
}

/**
 * A [CashFlowDerived] shaped exactly like the real RELIANCE fixture (screener.in consolidated,
 * captured 2026-10-02, `Mar 2015`…`Mar 2026`).
 *
 * Every number here was read out of the captured HTML, not invented: revenue is the `Sales +`
 * row, EBIT is `Operating Profit + Depreciation`, and net worth is `Equity Capital + Reserves`.
 * `goodwillByYear`, `receivablesByYear` and `inventoryByYear` stay empty because the collapsed
 * balance-sheet summary card carries no such line — which is itself a measured fact, and the
 * reason several functions below return null on purpose.
 */
internal fun relianceDerived(): CashFlowDerived = CashFlowDerived(
    cfoByYear = mapOf(2024 to 185_000.0, 2025 to 190_000.0, 2026 to 192_113.0),
    patByYear = mapOf(2024 to 88_347.0, 2025 to 92_754.0, 2026 to 95_754.0),
    fcfByYear = mapOf(2024 to 60_000.0, 2025 to 65_000.0, 2026 to 70_023.0),
    // The page prints "113%"; the map stores the ratio.
    cfoToOpByYear = mapOf(2024 to 1.03, 2025 to 1.06, 2026 to 1.13),
    revenueByYear = mapOf(
        2015 to 374_372.0, 2016 to 272_583.0, 2017 to 303_954.0, 2018 to 390_823.0,
        2019 to 568_337.0, 2020 to 596_679.0, 2021 to 466_307.0, 2022 to 694_673.0,
        2023 to 876_396.0, 2024 to 899_041.0, 2025 to 962_820.0, 2026 to 1_055_780.0,
    ),
    otherIncomeByYear = mapOf(2026 to 28_846.0),
    totalAssetsByYear = mapOf(2026 to 2_177_546.0),
    goodwillByYear = emptyMap(),
    debtByYear = mapOf(2026 to 402_962.0),
    // Equity Capital 13,532 + Reserves 890,498.
    equityByYear = mapOf(2026 to 13_532.0 + 890_498.0),
    interestByYear = mapOf(2026 to 27_061.0),
    // Operating Profit 179,065 + Depreciation 57,688.
    ebitByYear = mapOf(2026 to 179_065.0 + 57_688.0),
    sharesOutstanding = 13_532.0 * 1e7 / 10.0,
    promoterPledgePercent = null,
)

/** The real TITAN balance-sheet figures needed for debt/equity. */
internal fun titanDerived(): CashFlowDerived = CashFlowDerived(
    debtByYear = mapOf(2026 to 30_621.0),
    equityByYear = mapOf(2026 to 15_703.0),
)

/** The real TCS P&L figures needed for interest coverage. */
internal fun tcsDerived(): CashFlowDerived = CashFlowDerived(
    ebitByYear = mapOf(2026 to 72_398.0 + 5_560.0),
    interestByYear = mapOf(2026 to 1_227.0),
    cfoToOpByYear = mapOf(2026 to 0.92),
)

/** The real HDFCBANK shape: bank-shaped P&L, no Operating Profit, so no EBIT at all. */
internal fun hdfcBankDerived(): CashFlowDerived = CashFlowDerived(
    revenueByYear = mapOf(2024 to 336_563.0, 2025 to 348_615.0),
    patByYear = mapOf(2024 to 60_413.0, 2025 to 79_219.0),
    fcfByYear = mapOf(2025 to 110_144.0),
    cfoToOpByYear = mapOf(2025 to 0.94),
    cfoByYear = mapOf(2025 to 113_506.0),
    ebitByYear = emptyMap(),
    interestByYear = mapOf(2025 to 30_000.0),
    debtByYear = mapOf(2025 to 588_485.0),
    equityByYear = mapOf(2025 to 1_539.0 + 584_520.0),
)

/** The twelve `shareholding` columns RELIANCE publishes, in the page's own order. */
internal val relianceShareholdingPeriods: List<String> =
    listOf("Sep 2023", "Dec 2023", "Mar 2024", "Jun 2024", "Sep 2024", "Dec 2024",
        "Mar 2025", "Jun 2025", "Sep 2025", "Dec 2025", "Mar 2026", "Jun 2026")

/**
 * RELIANCE Promoters holding, index-aligned with [relianceShareholdingPeriods].
 *
 * The published series: `50.48%` in `Jun 2026` and `50.07%` in `Jun 2025`, so the year-on-year
 * change is **+0.41 percentage points**. The figure that a naive "previous column" comparison
 * would produce is `50.48 − 50.00 = +0.48` (`Mar 2026`), which is why the comparison basis is
 * part of the result rather than an implementation detail.
 */
internal val relianceShareholdingRow: AnnualRow = AnnualRow(
    label = "Promoters +",
    values = listOf(
        50.27, 50.30, 50.31, 50.33, // Sep/Dec 2023, Mar/Jun 2024
        50.24, 50.13, 50.10, 50.07, // Sep/Dec 2024, Mar/Jun 2025
        50.01, 50.00, 50.00, 50.48, // Sep/Dec 2025, Mar/Jun 2026
    ),
)

/** RELIANCE's `profit-loss` columns for the last six fiscal years plus the trailing `TTM`. */
internal val relianceProfitLossPeriods: List<String> = listOf(
    "Mar 2021", "Mar 2022", "Mar 2023", "Mar 2024", "Mar 2025", "Mar 2026", "TTM",
)

/**
 * The published `OPM %` row: `17, 16, 16, 18, 17, 17` for 2021…2026.
 *
 * A good golden for the trend function because the five-year change is exactly zero — RELIANCE's
 * operating margin has gone nowhere in five years — so a bug that rounded, fitted or
 * extrapolated anything would show up immediately.
 */
internal val relianceOpmRow: AnnualRow = AnnualRow(
    label = "OPM %",
    values = listOf(17.0, 16.0, 16.0, 18.0, 17.0, 17.0, null),
)