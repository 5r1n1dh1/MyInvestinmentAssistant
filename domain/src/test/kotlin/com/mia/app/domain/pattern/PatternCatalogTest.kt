package com.mia.app.domain.pattern

import com.mia.app.domain.pattern.detectors.candlestick.BearishEngulfingDetector
import com.mia.app.domain.pattern.detectors.candlestick.BullishEngulfingDetector
import com.mia.app.domain.pattern.detectors.candlestick.HammerDetector
import com.mia.app.domain.pattern.detectors.candlestick.ShootingStarDetector
import com.mia.app.domain.pattern.detectors.candlestick.StarDetector
import com.mia.app.domain.pattern.detectors.chart.AscendingTriangleDetector
import com.mia.app.domain.pattern.detectors.chart.DescendingTriangleDetector
import com.mia.app.domain.pattern.detectors.chart.DoubleBottomDetector
import com.mia.app.domain.pattern.detectors.chart.DoubleTopDetector
import com.mia.app.domain.pattern.detectors.chart.FlagDetector
import com.mia.app.domain.pattern.detectors.chart.HeadAndShouldersDetector
import com.mia.app.domain.pattern.detectors.chart.InverseHeadAndShouldersDetector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The catalogue's own invariants.
 *
 * ### Why the catalogue needs policing rather than trusting
 * It is ~50 rows of prose and geometry, and both rot silently. A rename in a detector would leave
 * a catalogue row that joins to nothing; a banned phrase would slip into one entry among fifty;
 * a normalised point outside `0..1` would only fail when something tried to draw it.
 *
 * None of those throw. They produce a page that is quietly wrong, which is the failure mode the
 * whole product is built against — so they are asserted here instead.
 */
class PatternCatalogTest {

    // ------------------------------------------------------------------ structure

    @Test
    fun `the evaluated rows are exactly the fourteen canonical pattern names`() {
        // The join between catalogue and scan results is by name, so the two vocabularies must be
        // the same fourteen. If a detector were added without a catalogue row, its results would
        // have nothing to render; if a row were added for an unevaluated name, it would claim the
        // app looks for something it does not.
        assertEquals(
            PatternIds.CANONICAL_NAMES.toSet(),
            PatternCatalog.evaluated.map { it.name }.toSet(),
            "the catalogue's evaluated rows and PatternIds.CANONICAL_NAMES have drifted apart",
        )
        assertEquals(14, PatternCatalog.evaluatedCount, "the v1 detector set is twelve / fourteen names")
    }

    @Test
    fun `every canonical name resolves to an evaluated row`() {
        for (name in PatternIds.CANONICAL_NAMES) {
            assertNotNull(
                PatternCatalog.evaluatedByName(name),
                "$name is a canonical pattern but has no evaluated catalogue row",
            )
        }
    }

    @Test
    fun `names are unique`() {
        val duplicates = PatternCatalog.all.groupBy { it.name }.filterValues { it.size > 1 }.keys
        assertTrue(duplicates.isEmpty(), "duplicate catalogue rows: $duplicates")
    }

    @Test
    fun `the catalogue is larger than what the app can evaluate, and says so`() {
        // The whole point of the not-evaluated rows is that they exist. If this ever empties, the
        // catalogue has collapsed into a detector list and the "why did nothing match?" question
        // has lost one of its three answers.
        assertTrue(
            PatternCatalog.notEvaluated.isNotEmpty(),
            "no not-evaluated rows remain; the catalogue no longer shows its own gaps",
        )
        assertTrue(
            PatternCatalog.all.size > PatternCatalog.evaluatedCount,
            "the catalogue must name patterns the app does not look for",
        )
    }

    @Test
    fun `lookup is tolerant of case and surrounding space`() {
        assertNotNull(PatternCatalog.byName("  double top "))
        assertNotNull(PatternCatalog.byName("HEAD & SHOULDERS"))
        assertEquals(
            PatternCatalog.byName("Hammer")?.name,
            PatternCatalog.byName("hammer")?.name,
        )
        assertEquals(null, PatternCatalog.byName("Nonexistent Pattern"))
    }

    @Test
    fun `evaluatedByName refuses a pattern the app cannot look for`() {
        // The distinction matters: a caller asking for the evaluated row of "Cup & Handle" must get
        // null rather than a row claiming the app evaluates it.
        assertEquals(null, PatternCatalog.evaluatedByName("Cup & Handle"))
        assertNotNull(PatternCatalog.byName("Cup & Handle"), "but it is still in the catalogue")
    }

    @Test
    fun `family grouping partitions the catalogue`() {
        val total = PatternFamily.entries.sumOf { PatternCatalog.byFamily(it).size }
        assertEquals(PatternCatalog.all.size, total, "every row belongs to exactly one family")
        for (family in PatternFamily.entries) {
            assertTrue(PatternCatalog.byFamily(family).isNotEmpty(), "$family has no rows")
        }
    }

    // ------------------------------------------------- shapes: only where they can be drawn

    @Test
    fun `every evaluated pattern has an authored shape`() {
        for (spec in PatternCatalog.evaluated) {
            assertTrue(spec.hasShape, "${spec.name} is evaluated but has no shape to draw")
        }
    }

    @Test
    fun `no unevaluated pattern claims a shape`() {
        // A shape on a row the app cannot evaluate would invite the reader to compare an overlay
        // against data the app never scored for that pattern.
        for (spec in PatternCatalog.notEvaluated) {
            assertFalse(
                spec.hasShape,
                "${spec.name} is not evaluated but carries a shape",
            )
        }
        assertEquals(
            PatternCatalog.evaluatedCount,
            PatternCatalog.withShape.size,
            "only evaluated patterns should carry shapes",
        )
    }

    @Test
    fun `every polyline shape is well formed`() {
        for (spec in PatternCatalog.evaluated) {
            val shape = spec.shape
            if (shape !is PatternShape.Polyline) continue
            assertTrue(shape.points.size >= 2, "${spec.name} has a ${shape.points.size}-point polyline")
            // roleLabels is either empty or exactly one label per point. A partial list would put
            // the wrong name on a swing point when annotating the overlay.
            assertTrue(
                shape.roleLabels.isEmpty() || shape.roleLabels.size == shape.points.size,
                "${spec.name} has ${shape.points.size} points and " +
                    "${shape.roleLabels.size} role labels",
            )
        }
    }

    @Test
    fun `every candle layout shape is well formed`() {
        for (spec in PatternCatalog.evaluated) {
            val shape = spec.shape
            if (shape !is PatternShape.CandleLayout) continue
            assertTrue(shape.bars.isNotEmpty(), "${spec.name} has an empty candle layout")
            for (bar in shape.bars) {
                // NormalisedCandle's own init enforces the wick/body ordering; re-asserted here so
                // the failure names the pattern, since a bare constructor error would not.
                assertTrue(
                    bar.wickHigh >= bar.bodyHigh && bar.wickLow <= bar.bodyLow,
                    "${spec.name} has a bar whose wick does not contain its body",
                )
            }
        }
    }

    @Test
    fun `candlestick patterns use candle layouts and chart patterns use polylines`() {
        // The reason PatternShape is sealed rather than a bare point list: forcing candles into a
        // polyline would drop the body and wick, which for a hammer *is* the pattern.
        for (spec in PatternCatalog.evaluated) {
            when (spec.family) {
                PatternFamily.CANDLESTICK -> assertTrue(
                    spec.shape is PatternShape.CandleLayout,
                    "${spec.name} is a candlestick but does not use a CandleLayout",
                )

                else -> assertTrue(
                    spec.shape is PatternShape.Polyline,
                    "${spec.name} is a chart pattern but does not use a Polyline",
                )
            }
        }
    }

    // ------------------------------------------------------- the copy rule, enforced

    /**
     * plan.md §1.4 rule 3 and §8.3 ban claiming a probability of profit. A glossary is prose about
     * patterns, which is exactly where that language creeps in — "will rise", "guarantees",
     * "sure thing". Asserted over every field of every entry.
     */
    @Test
    fun `no glossary entry uses prediction or probability language`() {
        val banned = listOf(
            "guarantee", "guaranteed", "will rise", "will fall", "will go up", "will go down",
            "sure thing", "certain to", "always wins", "risk-free", "risk free", "cannot lose",
            "probability of", "chance of profit", "predicts the", "predicted return",
            "you should buy", "you should sell", "recommend buying", "recommend selling",
        )
        for (spec in PatternCatalog.all) {
            val entry = spec.glossary
            val fields = mapOf(
                "whatItIs" to entry.whatItIs,
                "howItLooks" to entry.howItLooks,
                "textbookSays" to entry.textbookSays,
                "doesNotPredict" to entry.doesNotPredict,
            )
            for ((field, text) in fields) {
                val lower = text.lowercase()
                for (phrase in banned) {
                    assertFalse(
                        lower.contains(phrase),
                        "${spec.name}.$field contains banned phrase \"$phrase\": $text",
                    )
                }
            }
        }
    }

    @Test
    fun `every entry states what it does not predict`() {
        // Required rather than optional: this is the field that stops a named, well-known
        // formation being read as advice, so a row without it is not a glossary row.
        for (spec in PatternCatalog.all) {
            assertTrue(
                spec.glossary.doesNotPredict.length > 30,
                "${spec.name} has a token doesNotPredict: " +
                    "\"${spec.glossary.doesNotPredict}\"",
            )
        }
    }

    @Test
    fun `every entry describes a shape rather than an outcome`() {
        for (spec in PatternCatalog.all) {
            assertTrue(
                spec.shapeSummary.isNotBlank(),
                "${spec.name} has a blank shape summary",
            )
            assertTrue(
                spec.shapeSummary.length <= 120,
                "${spec.name}'s shape summary is ${spec.shapeSummary.length} chars; " +
                    "it should name a shape, not explain it",
            )
        }
    }

    @Test
    fun `evaluated rows agree with their detector's own minBars`() {
        // Read back from the detectors themselves rather than a copied table, so a detector raising
        // its floor without the catalogue following shows up here.
        //
        // A name can map to more than one detector: `StarDetector` emits both `Morning Star` and
        // `Evening Star`, and `FlagDetector` both flag directions. So the *smallest* floor across
        // the detectors claiming a name is the one that matters — it is what actually lets a scan
        // proceed.
        val floorsByName = detectorFloors()
        assertTrue(floorsByName.isNotEmpty(), "no detectors were discovered")

        for (spec in PatternCatalog.evaluated) {
            val floor = floorsByName[spec.name]
            if (floor == null) {
                fail("${spec.name} is marked evaluated but no detector claims that name")
            }
            assertEquals(
                floor,
                spec.minBars,
                "${spec.name}'s catalogue minBars is ${spec.minBars} but its detector needs $floor",
            )
        }
    }

    /**
     * Name → the smallest `minBars` among the detectors that claim it.
     *
     * Walks the detector classes reflectively rather than listing them, so a new detector that
     * forgot to be added here is caught by the emptiness assertion above rather than skipped.
     */
    private fun detectorFloors(): Map<String, Int> {
        val floors = mutableMapOf<String, Int>()
        fun record(detector: com.mia.app.domain.model.PatternDetector) {
            floors.merge(detector.name, detector.minBars) { a, b -> minOf(a, b) }
            // Two-direction detectors advertise one family name but emit two `Match.name`s; the
            // catalogue lists both, so both are credited the same floor.
            for (alias in TWO_NAME_ALIASES[detector.name].orEmpty()) {
                floors.merge(alias, detector.minBars) { a, b -> minOf(a, b) }
            }
        }
        DETECTORS.forEach(::record)
        return floors
    }

    private companion object {
        /**
         * The twelve v1 detectors, in plan.md §6.5 order — the same list
         * `PatternDiscriminationGateTest` and `WindowSizingMeasurementTest` use.
         */
        val DETECTORS: List<com.mia.app.domain.model.PatternDetector> = listOf(
            BullishEngulfingDetector(),
            BearishEngulfingDetector(),
            HammerDetector(),
            ShootingStarDetector(),
            StarDetector(),
            HeadAndShouldersDetector(),
            InverseHeadAndShouldersDetector(),
            DoubleTopDetector(),
            DoubleBottomDetector(),
            AscendingTriangleDetector(),
            DescendingTriangleDetector(),
            FlagDetector(),
        )

        /**
         * Detectors that emit two `Match.name`s under one `name`.
         *
         * `StarDetector.name` is "Morning Star" and it also emits "Evening Star";
         * `FlagDetector.name` is "Bull Flag" and it also emits "Bear Flag". The catalogue lists all
         * four, so all four are credited the family's floor. Only these two are known to behave this
         * way — a third appearing would need adding here, and `evaluated rows agree with their
         * detector's own minBars` fails if one is missed.
         */
        val TWO_NAME_ALIASES: Map<String, List<String>> = mapOf(
            "Morning Star" to listOf("Evening Star"),
            "Bull Flag" to listOf("Bear Flag"),
        )
    }
}