package com.mia.app.domain.pattern

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The overlay contract, asserted from `:domain` so it is actually verified.
 *
 * ### Why this test lives here and not in `:app`
 * `PatternOverlayMapper` is `:app` code — mapping a shape onto a window is a drawing decision —
 * and `:app` **cannot be compiled on this machine**: `settings.gradle.kts` includes it only when an
 * Android SDK is present, and there is none. So the mapper's own logic is unverifiable here.
 *
 * What *is* verifiable is the part that decides whether the overlay can mislead: the shape data
 * itself. Every assertion below is about `:domain` values that `:app` merely reads, which means a
 * malformed shape is caught on a machine that can run `:domain` tests rather than discovered on a
 * phone.
 */
class PatternOverlayContractTest {

    // ------------------------------------------------------------- shapes are drawable at all

    @Test
    fun `every authored shape stays inside the unit square`() {
        // The mapper scales `0..1` onto the window. A point outside that range would map off-plot
        // or, worse, invert, and the reader would see a shape that contradicts the catalogue.
        for (spec in PatternCatalog.evaluated) {
            when (val shape = spec.shape) {
                is PatternShape.Polyline -> for (point in shape.points) {
                    assertTrue(point.x in 0f..1f, "${spec.name}: x ${point.x} is outside 0..1")
                    assertTrue(point.y in 0f..1f, "${spec.name}: y ${point.y} is outside 0..1")
                }

                is PatternShape.CandleLayout -> for (bar in shape.bars) {
                    for ((label, v) in listOf(
                        "bodyHigh" to bar.bodyHigh, "bodyLow" to bar.bodyLow,
                        "wickHigh" to bar.wickHigh, "wickLow" to bar.wickLow,
                    )) {
                        assertTrue(v in 0f..1f, "${spec.name}: $label $v is outside 0..1")
                    }
                    assertTrue(
                        bar.wickHigh >= bar.bodyHigh,
                        "${spec.name}: a wick does not cover its body",
                    )
                }

                // Boundary and composite figures are checked by their own tests, which compare
                // lines against lines. There is no single point list to range-check here.
                is PatternShape.Boundaries, is PatternShape.Composite -> Unit

                PatternShape.UNAVAILABLE -> fail("${spec.name} is evaluated but has no shape")
            }
        }
    }

    @Test
    fun `every polyline ascends left to right`() {
        // The mapper places points by bar index derived from `x`. A decreasing `x` would draw the
        // shape right-to-left, which inverts the reading of every pattern it applies to.
        for (spec in PatternCatalog.evaluated) {
            for (polyline in polylinesIn(spec)) {
                val xs = polyline.points.map { it.x }
                assertTrue(
                    xs == xs.sorted(),
                    "${spec.name} has non-ascending x values: $xs",
                )
            }
        }
    }

    /**
     * Every polyline reachable in [spec], descending through composites.
     *
     * `Boundaries` rows are excluded: their two lines are separately ascending by construction (the
     * type's own `init` asserts it), and their points are deliberately *not* globally ascending —
     * a triangle's upper line sits entirely above its lower one.
     */
    private fun polylinesIn(spec: PatternSpec): List<PatternShape.Polyline> = when (val s = spec.shape) {
        is PatternShape.Polyline -> listOf(s)
        is PatternShape.Composite -> s.parts.flatMap { part ->
            if (part is PatternShape.Polyline) listOf(part) else emptyList()
        }

        else -> emptyList()
    }

    @Test
    fun `role labels line up with the points they annotate`() {
        // `PatternOverlay` carries one label per point and the draw pass zips them together. A
        // mismatched list would put "Head" on a neckline.
        for (spec in PatternCatalog.evaluated) {
            val shape = spec.shape as? PatternShape.Polyline ?: continue
            if (shape.roleLabels.isEmpty()) continue
            assertEquals(
                shape.points.size,
                shape.roleLabels.size,
                "${spec.name}: ${shape.roleLabels.size} labels for ${shape.points.size} points",
            )
            for (label in shape.roleLabels) {
                assertTrue(label.isNotBlank(), "${spec.name} has a blank role label")
            }
        }
    }

    // ------------------------------------------------------ the distinction the honesty rests on

    @Test
    fun `continuations are open and formations are closed`() {
        // Closed means the enclosed region is the pattern (double top, head and shoulders). Open
        // means only the boundary lines are the pattern (triangles, flags). Getting this backwards
        // would draw a shaded-looking enclosure around a flag, implying a structure that is not
        // there.
        val closedExpected = setOf("Double Top", "Double Bottom", "Head & Shoulders", "Inverse Head & Shoulders")
        // Continuations are `Boundaries` or `Composite` now, so they have no `closed` flag at all —
        // which is the stronger guarantee: there is no way to express a closed continuation.
        for (spec in PatternCatalog.evaluated) {
            val shape = spec.shape as? PatternShape.Polyline ?: continue
            val shouldBeClosed = spec.name in closedExpected
            assertEquals(
                shouldBeClosed,
                shape.closed,
                "${spec.name}'s closed=${shape.closed} does not match its family; a reversal " +
                    "formation should enclose a region and a continuation should not",
            )
        }
    }

    @Test
    fun `mirror patterns are exact vertical mirrors of each other`() {
        // A paired pattern drawn without mirroring would teach the wrong shape. Checked numerically
        // rather than by eye, so an edit to one side that misses the other is caught.
        val pairs = listOf(
            "Double Top" to "Double Bottom",
            "Head & Shoulders" to "Inverse Head & Shoulders",
        )
        for ((up, down) in pairs) {
            assertMirrored(singlePolyline(up), singlePolyline(down), "$up/$down")
        }
    }

    @Test
    fun `bull and bear flags are exact vertical mirrors of each other`() {
        // Compared part-by-part, because a flag is a Composite (pole + channel) rather than one
        // line. Mirroring the parts individually is what proves the two flags are inverses rather
        // than merely different.
        val bull = PatternCatalog.evaluatedByName("Bull Flag")!!.shape as PatternShape.Composite
        val bear = PatternCatalog.evaluatedByName("Bear Flag")!!.shape as PatternShape.Composite
        assertEquals(bull.parts.size, bear.parts.size, "the two flags have different part counts")

        for (p in bull.parts.indices) {
            val a = bull.parts[p]
            val b = bear.parts[p]
            if (a is PatternShape.Polyline && b is PatternShape.Polyline) {
                assertMirrored(a.points, b.points, "flag part $p")
            } else if (a is PatternShape.Boundaries && b is PatternShape.Boundaries) {
                assertMirrored(a.upper, b.lower, "flag part $p upper/lower")
                assertMirrored(a.lower, b.upper, "flag part $p lower/upper")
            } else {
                throw AssertionError("flag part $p differs in kind: $a vs $b")
            }
        }
    }

    @Test
    fun `ascending and descending triangles mirror, boundary for boundary`() {
        val up = PatternCatalog.evaluatedByName("Ascending Triangle")!!.shape as PatternShape.Boundaries
        val down = PatternCatalog.evaluatedByName("Descending Triangle")!!.shape as PatternShape.Boundaries
        assertMirrored(up.upper, down.lower, "ascending upper vs descending lower")
        assertMirrored(up.lower, down.upper, "ascending lower vs descending upper")
    }

    @Test
    fun `a triangle's upper boundary is above its lower one across the whole span`() {
        // Otherwise the two "boundaries" cross and the overlay would draw an X through the chart.
        for (name in listOf("Ascending Triangle", "Descending Triangle")) {
            val shape = PatternCatalog.evaluatedByName(name)!!.shape as PatternShape.Boundaries
            val xs = shape.upper.map { it.x }
            assertEquals(xs, shape.lower.map { it.x }, "$name: boundaries use different x positions")
            for (i in xs.indices) {
                assertTrue(
                    shape.upper[i].y >= shape.lower[i].y,
                    "$name: at x=${xs[i]} the upper boundary (${shape.upper[i].y}) is below the " +
                        "lower one (${shape.lower[i].y})",
                )
            }
        }
    }
    // The five candlestick shape tests that used to live here were removed with the shapes.
    //
    // They verified real properties of real data — that a hammer's signal sits in its wick, that
    // a star's middle candle is the small one, that a bullish layout mirrors a bearish one — and
    // all of that was correct. But `PatternSpec` no longer carries a shape for any of the six
    // retired candlestick rows, because a shape on an unevaluated row invites a reader to compare
    // an overlay against data the app never scored for it. With the data gone there is nothing
    // left to assert, and a test that passes vacuously is worse than no test.
    //
    // `PatternCatalogTest` now asserts the removal instead: each retired row is present, marked
    // not-evaluated, and holds `PatternShape.UNAVAILABLE`. Reinstating a candlestick detector
    // means restoring its `CandleLayout` and bringing these five assertions back with it.

    @Test
    fun `a double top has two peaks and a double bottom two troughs`() {
        val top = PatternCatalog.evaluatedByName("Double Top")!!.shape as PatternShape.Polyline
        val bottom = PatternCatalog.evaluatedByName("Double Bottom")!!.shape as PatternShape.Polyline
        assertTrue(top.closed && bottom.closed, "both formations should enclose a region")
        // The two peaks are the highest points; the trough between them is lower than both.
        val sortedTop = top.points.sortedByDescending { it.y }
        assertTrue(
            top.points.any { p -> p == sortedTop[0] } && top.points.any { p -> p == sortedTop[1] },
            "the double top must have two peaks at the same level",
        )
        assertTrue(top.points.any { it.y < sortedTop[1].y }, "the double top needs a trough")

        val sortedBottom = bottom.points.sortedBy { it.y }
        assertTrue(
            bottom.points.any { p -> p == sortedBottom[0] } &&
                bottom.points.any { p -> p == sortedBottom[1] },
            "the double bottom must have two troughs at the same level",
        )
    }

    @Test
    fun `a head and shoulders has its middle peak highest`() {
        for (name in listOf("Head & Shoulders", "Inverse Head & Shoulders")) {
            val shape = PatternCatalog.evaluatedByName(name)!!.shape as PatternShape.Polyline
            val head = labelledPoint(shape, "Head")
            val inverted = name.startsWith("Inverse")
            // For the inverse pattern the head is the lowest point, not the highest.
            val extreme = if (inverted) shape.points.minBy { it.y } else shape.points.maxBy { it.y }
            assertEquals(
                head,
                extreme,
                "$name: the labelled head is not the ${if (inverted) "lowest" else "highest"} point",
            )
        }
    }

    @Test
    fun `an unavailable shape is available on every evaluated row`() {
        // Re-asserted here because this is the property the `:app` renderer relies on, and the
        // renderer's own module cannot be tested here.
        assertTrue(
            PatternCatalog.evaluated.none { it.shape == PatternShape.UNAVAILABLE },
            "an evaluated pattern has no shape, so the Explorer would draw nothing for it",
        )
        assertFalse(
            PatternCatalog.notEvaluated.any { it.shape != PatternShape.UNAVAILABLE },
            "an unevaluated pattern has a shape, which invites comparing an overlay against data " +
                "the app never scored for it",
        )
    }

    /**
     * Asserts [bear] is [bull] reflected about `y = 0.5`.
     *
     * The wick and the body trade places under reflection, so the body of one is compared with the
     * wick of the other — comparing like-named fields tests something that is not a mirror.
     */
    private fun assertMirroredCandle(
        bull: NormalisedCandle,
        bear: NormalisedCandle,
        what: String,
    ) {
        // A candle is four levels, not four named fields: `bodyHigh` and `bodyLow` are endpoints
        // of a range and may be stored in either order, so everything here is computed from
        // max/min of each pair.
        // Reflect about y = 0.5 with **rank preserved**: the higher of the two body endpoints maps
        // to the higher of the two bear endpoints, and likewise for the wick. Comparing by field
        // name assumed a fixed endpoint order, which reflection does not preserve.
        fun top(b: NormalisedCandle) = maxOf(b.bodyHigh, b.bodyLow)
        fun bottom(b: NormalisedCandle) = minOf(b.bodyHigh, b.bodyLow)
        // Reflection reverses order, so the bull bar's *top* is the bear bar's *bottom*:
        //   1 − bullTop == bearBottom   and   1 − bullBottom == bearTop
        // Comparing like-named ranks (bullTop to bearTop) asserts the shapes are identical rather
        // than inverted, which is what made every mirrored pair look wrong.
        val pairs = listOf(
            "body top↔body bottom" to (1f - top(bull) to bottom(bear)),
            "body bottom↔body top" to (1f - bottom(bull) to top(bear)),
            "wick top↔wick bottom" to (1f - bull.wickHigh to bear.wickLow),
            "wick bottom↔wick top" to (1f - bull.wickLow to bear.wickHigh),
        )
        for ((label, values) in pairs) {
            assertTrue(
                kotlin.math.abs(values.first - values.second) < 1e-4f,
                "$what: $label is ${values.first} vs ${values.second}",
            )
        }
    }

    /** Asserts [a] and [b] are point-for-point vertical mirrors about `y = 0.5`. */
    private fun assertMirrored(
        a: List<NormalisedPoint>,
        b: List<NormalisedPoint>,
        what: String,
    ) {
        assertEquals(a.size, b.size, "$what: point counts differ (${a.size} vs ${b.size})")
        for (i in a.indices) {
            assertEquals(a[i].x, b[i].x, "$what point $i differs in x")
            assertTrue(
                kotlin.math.abs((a[i].y + b[i].y) - 1f) < 1e-4f,
                "$what point $i is not mirrored: y ${a[i].y} + ${b[i].y} != 1",
            )
        }
    }

    /**
     * The single polyline of [name], for pairs where both sides really are one line.
     *
     * Ascending and descending triangles are `Boundaries`, and flags are `Composite`, so the
     * mirror assertion below compares only the genuinely line-shaped pairs. The triangles are
     * covered by their own boundary test.
     */
    private fun singlePolyline(name: String): List<NormalisedPoint> {
        val shape = PatternCatalog.evaluatedByName(name)?.shape
        assertTrue(shape is PatternShape.Polyline, "$name is not a single polyline but ${shape!!::class.simpleName}")
        return (shape as PatternShape.Polyline).points
    }

    /** The point carrying [label] in [shape]'s role labels. Fails loudly on an unknown label. */
    private fun labelledPoint(shape: PatternShape.Polyline, label: String): NormalisedPoint {
        val index = shape.roleLabels.indexOf(label)
        check(index >= 0) { "no role label \"$label\" among ${shape.roleLabels}" }
        return shape.points[index]
    }
}