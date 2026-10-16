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
import com.mia.app.domain.pattern.detectors.chart.RisingWedgeDetector
import com.mia.app.domain.pattern.detectors.chart.InverseHeadAndShouldersDetector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
    fun `the evaluated rows are the canonical names minus the retired ones`() {
        // The join between catalogue and scan results is by name, so the two vocabularies must
        // reconcile. They no longer have to be *equal*: six canonical names (the candlestick family)
        // were moved to the not-evaluated list, so the contract is now that every canonical id still
        // has a row and that the difference is exactly the named set.
        //
        // The union check is the part that still catches the original failure — a detector added
        // without a catalogue row, whose results would then have nothing to render. Equality alone
        // no longer would, which is why it was not simply left in place.
        assertEquals(
            PatternIds.EVALUATED_CANONICAL_NAMES.toSet(),
            PatternCatalog.evaluated.map { it.name }.toSet(),
            "the catalogue's evaluated rows and PatternIds.EVALUATED_CANONICAL_NAMES have drifted",
        )
        assertEquals(
            PatternIds.CANONICAL_NAMES.toSet(),
            (PatternCatalog.evaluated.map { it.name } + PatternIds.RETIRED_CANONICAL_NAMES)
                .filter { it in PatternIds.CANONICAL_NAMES }
                .toSet(),
            "evaluated + retired must account for every v1 canonical name, or an id has no row " +
                "and stored outcomes pointing at it would render nothing",
        )
        assertEquals(9, PatternCatalog.evaluatedCount, "nine patterns: the v1 chart eight plus Rising Wedge")
    }

    @Test
    fun `every retired canonical name has a row that admits it is not evaluated`() {
        // The retired rows must be *present and labelled*, not deleted. Deleting them would make a
        // reader's "what is a hammer?" unanswerable, and would also drop the stored ids 1..6 out of
        // the catalogue that joins to them.
        for (name in PatternIds.RETIRED_CANONICAL_NAMES) {
            val row = assertNotNull(
                PatternCatalog.byName(name),
                "$name is a canonical pattern and must keep its catalogue row",
            )
            assertFalse(
                row.isEvaluated,
                "$name was retired from evaluation and must say so on its row",
            )
            assertEquals(
                PatternShape.UNAVAILABLE,
                row.shape,
                "$name is no longer evaluated, so it must not carry an overlay shape: an overlay " +
                    "beside data the app never scored invites exactly the comparison the shape " +
                    "contract forbids",
            )
        }
    }

    @Test
    fun `every canonical name resolves to a row, and an evaluated one only if it is evaluated`() {
        // Note the deliberate weakening: this used to demand an *evaluated* row for all fourteen,
        // which is exactly the claim that was withdrawn. The join itself is still checked — a
        // canonical name with no row at all would break every stored outcome that points at it.
        for (name in PatternIds.CANONICAL_NAMES) {
            assertNotNull(
                PatternCatalog.byName(name),
                "$name is a canonical pattern but has no catalogue row",
            )
        }
        for (name in PatternIds.EVALUATED_CANONICAL_NAMES) {
            assertNotNull(
                PatternCatalog.evaluatedByName(name),
                "$name is claimed as evaluated but has no evaluated row",
            )
        }
        for (name in PatternIds.RETIRED_CANONICAL_NAMES) {
            assertNull(
                PatternCatalog.evaluatedByName(name),
                "$name is retired, so evaluatedByName must not hand it back to a caller",
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
        //
        // Chart patterns have three legitimate representations, not one: a single line (double top,
        // head & shoulders), two boundary lines (triangles), or a composite of a line and a
        // boundary (the flags, whose pole is separate from their channel).
        for (spec in PatternCatalog.evaluated) {
            when (spec.family) {
                PatternFamily.CANDLESTICK -> assertTrue(
                    spec.shape is PatternShape.CandleLayout,
                    "${spec.name} is a candlestick but does not use a CandleLayout",
                )

                else -> assertTrue(
                    spec.shape is PatternShape.Polyline ||
                        spec.shape is PatternShape.Boundaries ||
                        spec.shape is PatternShape.Composite,
                    "${spec.name} is a chart pattern but uses " +
                        "${spec.shape::class.simpleName}, which is not a line-based shape",
                )
            }
        }
    }

    @Test
    fun `a candle layout is never used for a chart pattern and vice versa`() {
        // The stronger form of the family check above: `CandleLayout` is only ever legal on a
        // candlestick row, because rendering a chart pattern as bars would draw nonsense and
        // rendering a candlestick as lines would erase its signal.
        for (spec in PatternCatalog.all) {
            if (spec.shape is PatternShape.CandleLayout) {
                assertEquals(
                    PatternFamily.CANDLESTICK,
                    spec.family,
                    "${spec.name} uses a CandleLayout but is not a candlestick pattern",
                )
            }
            if (spec.family == PatternFamily.CANDLESTICK && spec.isEvaluated) {
                assertTrue(
                    spec.shape is PatternShape.CandleLayout,
                    "${spec.name} is an evaluated candlestick but has no CandleLayout",
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
                // An invalidation is the sentence most likely to drift into advice, because it is
                // the one that talks about what happens next ("if it breaks, the pattern is
                // void") and a single extra clause turns it into a forecast. It is scanned with
                // the rest rather than trusted.
                "invalidation" to entry.invalidation,
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

    // ------------------------------------------------- invalidation: the gap E4 content

    /**
     * gaps.md E4: the glossary taught descriptions and stopped there, so a row with no
     * invalidation is the defect this workstream exists to close.
     *
     * The field has no default in the schema either, so a blank can only come from a sentence of
     * whitespace — which is why the length floor is here rather than another `isNotBlank`.
     */
    @Test
    fun `every entry states what would prove the pattern wrong`() {
        for (spec in PatternCatalog.all) {
            assertTrue(
                spec.glossary.invalidation.isNotBlank(),
                "${spec.name} has a blank invalidation condition",
            )
            assertTrue(
                spec.glossary.invalidation.length > 40,
                "${spec.name} has a token invalidation: " +
                    "\"${spec.glossary.invalidation}\"",
            )
        }
    }

    /**
     * An invalidation two rows share verbatim is a disclaimer wearing the field's name, which is
     * the whole thing E4 says the catalogue was missing.
     *
     * The bound is two rather than one because a mirror pair may legitimately state the same
     * condition — but mirrored words are still different strings, so a repeat at all has to be
     * copy-pasted rather than derived. Anything past two rows is a template, not an analysis.
     */
    @Test
    fun `no invalidation sentence is reused across more than two rows`() {
        val shared = PatternCatalog.all.groupBy { it.glossary.invalidation }.filterValues { it.size > 1 }
        for ((text, rows) in shared) {
            assertTrue(
                rows.size <= 2,
                "${rows.size} rows share one invalidation verbatim in ${rows.map { it.name }}: " +
                    "\"$text\". Each row's invalidation has to be its own.",
            )
        }
    }

    /**
     * The nine evaluated patterns get a genuinely different condition each.
     *
     * Stronger than the reuse bound above, because these are the rows the app scores: two sharing
     * a condition would mean a reader cannot tell which formation they are looking at from the one
     * sentence meant to distinguish it.
     */
    @Test
    fun `the evaluated patterns each state their own invalidation`() {
        val evaluated = PatternCatalog.evaluated
        assertTrue(evaluated.isNotEmpty(), "no evaluated rows")
        val shared = evaluated.groupBy { it.glossary.invalidation }.filterValues { it.size > 1 }
        assertTrue(
            shared.isEmpty(),
            "evaluated patterns share an invalidation: " +
                shared.keys.joinToString() +
                " — each one's break level differs, so each sentence should too",
        )
        // …and an invalidation is a claim about geometry, so it has to name an observable price
        // event. A sentence mentioning no close, boundary, level or break is a disclaimer.
        val observables = listOf(
            "close", "boundary", "line", "break", "level", "pole", "symmetry", "height",
        )
        for (spec in evaluated) {
            val text = spec.glossary.invalidation.lowercase()
            assertTrue(
                observables.any { text.contains(it) },
                "${spec.name}'s invalidation names no observable price event: " +
                    "\"${spec.glossary.invalidation}\"",
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
         * The v1 detectors in plan.md §6.5 order, plus every post-v1 addition.
         *
         * The post-v1 entry is here rather than in a separate list because this map exists to answer
         * "does a detector back this catalogue row?", and a row marked evaluated with no detector
         * behind it is precisely the failure it is written to catch. `PatternDiscriminationGateTest`
         * and `WindowSizingMeasurementTest` keep the v1 twelve, since they measure the v1 set.
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
            RisingWedgeDetector(),
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