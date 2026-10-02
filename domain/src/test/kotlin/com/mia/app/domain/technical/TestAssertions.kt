package com.mia.app.domain.technical

import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tolerance used for golden assertions whose exact value involves a long chain of
 * floating-point operations (EMA recursion, Wilder smoothers).
 *
 * The *rational* values those tests assert are quoted in their comments; this is the
 * numeric slack needed to compare a double against a hand-derived rational. Where the
 * computation is exactly representable in binary — integer closes, ±1 changes — the tests
 * use `assertEquals` with no tolerance at all.
 */
internal const val TOL: Double = 1e-9

internal fun assertClose(expected: Double, actual: Double?, tolerance: Double = TOL, message: String = "") {
    if (actual == null) {
        throw AssertionError("${message}expected $expected but was null".trim())
    }
    assertTrue(
        abs(expected - actual) <= tolerance,
        "$message expected $expected but was $actual (delta ${abs(expected - actual)})",
    )
}

/** Asserts that every element of the series is `null` before [fromIndex]. */
internal fun assertWarmup(series: List<Double?>, fromIndex: Int, message: String = "") {
    for (i in 0 until minOf(fromIndex, series.size)) {
        assertNull(series[i], "$message index $i should be null (inside warm-up), was ${series[i]}")
    }
}

/** Asserts no element is NaN or infinite. plan.md §12: a silent NaN on the chart is a bug. */
internal fun assertFinite(name: String, series: List<Double?>) {
    series.forEachIndexed { i, v ->
        if (v == null) return@forEachIndexed
        assertTrue(!v.isNaN(), "$name[$i] is NaN")
        assertTrue(!v.isInfinite(), "$name[$i] is infinite: $v")
    }
}