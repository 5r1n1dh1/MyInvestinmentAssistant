package com.mia.app.domain.redflag.technical

import com.mia.app.domain.model.Evidence
import com.mia.app.domain.model.RedFlag
import com.mia.app.domain.technical.IndicatorSnapshot
import com.mia.app.domain.technical.RsiResult
import kotlin.test.assertNotNull
import kotlin.test.fail

/**
 * Assertions shared by the per-rule tests.
 *
 * **Why `evidenceValue` exists.** plan.md §8.3 requires that tapping a flag show the actual numbers,
 * and `redflags-spec.md` §1 rule 2 requires the evidence value to be *the number the comparison used*.
 * A test that searched the evidence list for a substring could pass on a row that is not the one it
 * thinks it is; looking a row up **by label** makes the assertion name the row it means, so a
 * reworded or reordered evidence list fails the test instead of quietly weakening it.
 */
internal fun RedFlag.evidenceRow(label: String): Evidence =
    evidence.firstOrNull { it.label == label }
        ?: fail("${this::class.simpleName} flag $id has no evidence row labelled '$label'; it has ${evidence.map { it.label }}")

internal fun RedFlag.evidenceValue(label: String): String = evidenceRow(label).value

/** Evidence labels that are commentary rather than a measurement, and so carry no as-of date. */
internal val NONDATED_EVIDENCE_LABELS: Set<String> = setOf("Threshold", "Caveat", "Band")

/**
 * Every row that is a *measurement* must carry an as-of date (plan.md §1.4 rule 3,
 * `redflags-spec.md` §11 rule 9). Applied as a property, not per test.
 */
internal fun RedFlag.assertEveryMeasurementIsDated() {
    for (row in evidence) {
        if (row.label !in NONDATED_EVIDENCE_LABELS) {
            assertNotNull(row.asOf, "evidence row '${row.label}' = '${row.value}' has no as-of date")
        }
    }
}

/** A context whose RSI series is stated outright, so band boundaries can be tested exactly. */
internal fun contextWithRsi(
    candles: List<com.mia.app.domain.model.Candle>,
    values: List<Double?>,
): com.mia.app.domain.redflag.RedFlagContext {
    val base = IndicatorSnapshot.of(candles)
    val filled = RsiResult(
        rsi = values,
        averageGain = values.map { null },
        averageLoss = values.map { null },
    )
    return technicalContext(candles, indicators = base.copy(rsi = filled))
}