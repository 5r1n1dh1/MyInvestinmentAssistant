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
 * Only the figures the tests assert are carried; every other map stays empty, which is itself
 * the measured shape of the source — `receivablesByYear`, `inventoryByYear` and
 * `goodwillByYear` are empty for every fixture because the collapsed balance-sheet summary
 * carries no such line.
 */
internal fun relianceDerived(): CashFlowDerived = CashFlowDerived(
    cfoByYear = mapOf(2024 to 185_000.0, 2025 to 190_000.0, 2026 to 192_113.0),
    patByYear = mapOf(2024 to 88_347.0, 2025 to 92_754.0, 2026 to 95_754.0),
    fcfByYear = mapOf(2024 to 60_000.0, 2025 to 65_000.0, 2026 to 70_023.0),
    // The page prints "113%"; the map stores the ratio.
    cfoToOpByYear = mapOf(2024 to 1.03, 2025 to 1.06, 2026 to 1.13),
    revenueByYear = mapOf(
        2022 to 872_593.0,
        2023 to 963_889.0,
        2024 to 1_000_228.0,
        2025 to 1_063_009.0,
        2026 to 1_055_780.0,
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

/** The five RELIANCE `shareholding` columns and the Promoters row, as published. */
internal val relianceShareholdingPeriods: List<String> =
    listOf("Sep 2023", "Dec 2023", "Mar 2024", "Jun 2024", "Sep 2024", "Dec 2024",
        "Mar 2025", "Jun 2025", "Sep 2025", "Dec 2025", "Mar 2026", "Jun 2026")

/**
 * RELIANCE Promoters holding, indexed by [relianceShareholdingPeriods].
 *
 * `Jun 2026` is 50.48% (the value `RatioMapper` publishes as the latest) and `Jun 2025` is
 * 50.47%, so the year-on-year change is +0.01pp — a deliberately tiny number, because the
 * point of the test is that the comparison is made against the *same quarter a year earlier*
 * rather than against the previous column.
 */
internal val relianceShareholdingRow: AnnualRow = AnnualRow(
    label = "Promoters +",
    values = listOf(
        50.44, 50.44, 50.46, 50.46, // Sep/Dec 2023, Mar/Jun 2024
        50.45, 50.45, 50.47, 50.47, // Sep/Dec 2024, Mar/Jun 2025
        50.48, 50.48, 50.48, // Sep/Dec 2025, Mar 2026
    ),
)

/** A profit-loss table carrying only `OPM %`, indexed by [relianceProfitLossPeriods]. */
internal val relianceProfitLossPeriods: List<String> = listOf(
    "Mar 2022", "Mar 2023", "Mar 2024", "Mar 2025", "Mar 2026", "TTM",
)

internal val relianceOpmRow: AnnualRow = AnnualRow(
    label = "OPM %",
    values = listOf(11.55, 14.31, 17.61, 18.36, 17.0, null),
)