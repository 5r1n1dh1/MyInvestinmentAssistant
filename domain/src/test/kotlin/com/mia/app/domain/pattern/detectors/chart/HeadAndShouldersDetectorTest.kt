package com.mia.app.domain.pattern.detectors.chart

import com.mia.app.domain.model.Direction
import com.mia.app.domain.model.MatchScorePolicy
import com.mia.app.domain.model.ScoreBreakdown
import com.mia.app.domain.model.Timeframe
import com.mia.app.domain.pattern.PatternIds
import com.mia.app.domain.model.PivotKind
import com.mia.app.domain.pattern.FactorScorer
import com.mia.app.domain.pattern.ScoreFactor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Detector 6 — `Head & Shoulders`, on `patterns-spec.md` §12.7's worked fixture and
 * `scoring-spec.md` §10.1's example A.
 *
 * The fixture is the spec's own numbers, written into a synthetic series:
 *
 * | legIndex | role | bar | price | kind |
 * |---|---|---|---|---|
 * | 18 | `L0` | 40 | 950 | LOW |
 * | 19 | `LS` | 58 | 1,150 | HIGH |
 * | 20 | `T1` | 74 | 1,000 | LOW |
 * | 21 | `H`  | 92 | 1,200 | HIGH |
 * | 22 | `T2` | 108 | 1,020 | LOW |
 * | 23 | `RS` | 126 | 1,140 | HIGH |
 * | 24 | — | 136 | 995 | LOW (the breakout bar) |
 *
 * with `close[38] = 1,010.00` for the prior-trend reading and `preTrendHigh = 1,120` at bar 30 so
 * DD-6's scored clause has something to fail against, exactly as §12.7's resolution note describes.
 */
class HeadAndShouldersDetectorTest {

    private val detector = HeadAndShouldersDetector()

    // ----------------------------------------------------------------------------- the fixture

    @Test
    fun `the_specs_worked_fixture_is_detected_with_its_exact_measured_move`() {
        val series = hsSeries()
        val matches = detector.detect(series.pivots, series.candles)

        assertEquals(1, matches.size, "one formation in the §12.7 fixture")
        val match = matches.single()
        assertEquals("Head & Shoulders", match.name)
        assertEquals(PatternIds.of("Head & Shoulders"), match.patternId)
        assertTrue(PatternIds.isCanonical(match.name), "the accuracy-table key must be canonical")
        assertEquals(com.mia.app.domain.model.Category.REVERSAL, match.category)

        // §12.1 P2 — the five role pivots, in order, are exactly the ones the detector used.
        assertEquals(listOf(58, 74, 92, 108, 126), match.pivots.map { it.barIndex })
        assertEquals(
            listOf(PivotKind.HIGH, PivotKind.LOW, PivotKind.HIGH, PivotKind.LOW, PivotKind.HIGH),
            match.pivots.map { it.kind },
        )

        // §12.4 — the book's own two steps.
        //   neckline: lineThrough(T1=(74,1000), T2=(108,1020)), slope 20/34 = 0.58824 ₹/bar
        //   necklineAt(92) = 1000 + 0.58824 × 18 = 1,010.588
        //   ref = 1200 − 1,010.588 = 189.412
        //   triggerLevel = neckline.at(136) = 1,036.470
        //   projectedLevel = 995.00 − 189.412 = 805.588
        val measured = assertNotNull(match.measuredMove, "a triggered formation carries a measured move")
        assertEquals(189.412, measured.height, 1e-3, "§12.4 height = head − neckline")
        assertEquals(1036.470, measured.triggerLevel, 1e-3, "§12.4 trigger = the neckline at the breakout bar")
        assertEquals(805.588, measured.projectedLevel, 1e-3, "§12.4 projection = breakout close − height")
        assertEquals(Direction.DOWN, measured.direction)
        assertTrue(measured.height >= 0.0, "plan.md §12: a measured move is never negative")
    }

    @Test
    fun `theSpecsBreakoutBarIs136NotTheFirstBarThatTouchedTheNeckline`() {
        val series = hsSeries()
        val match = detector.detect(series.pivots, series.candles).single()
        // §3.7's `isBreakout` takes "the **first** `j >= from`" whose *close* clears the level by
        // the buffer; the price walked down to the neckline over bars 127–135 without closing through.
        assertEquals(995.0, series.closeAt(136), "the fixture's breakout close")
        assertEquals(126, match.pivots.maxOf { it.barIndex }, "the roles end at the right shoulder")
        assertTrue(match.measuredMove!!.projectedLevel < match.measuredMove!!.triggerLevel)
    }

    // ------------------------------------------------------------------------------- the score

    @Test
    fun `theGeometryFactorIsTheSpecsOwnArithmeticIncludingTheDd6ClauseThatFails`() {
        val series = hsSeries()
        val candidate = singleCandidate(series)
        val assessed = FactorScorer.assess(candidate.measures)

        // §12.7's raw geometry numbers, typed out again rather than read out of the detector.
        val symShoulder = 1140.0 / 1150.0
        val headDominance = (1200.0 - 1150.0) / 189.41176470588235
        val timeSymmetry = 16.0 / 18.0
        val necklineTilt = (20.0 / 34.0) * 68.0 / 1000.0 * 100.0

        assertEquals(symShoulder, candidate.measure("G1 symShoulder"), 1e-12)
        assertEquals(headDominance, candidate.measure("G2 headDominance"), 1e-9)
        assertEquals(timeSymmetry, candidate.measure("G4 timeSymmetry"), 1e-12)
        assertEquals(necklineTilt, candidate.measure("G5 |necklineTilt|"), 1e-9)
        assertEquals(1.0, candidate.measure("G6 monoScore"), 1e-12)
        // §12.7's resolution note: "highest HIGH before LS = 1,120 ; RS 1140 > 1120 ⇒ this sub-rule
        // FAILS" — DD-6 scores it rather than gating on it, so the measure reads 0 and the match
        // still stands.
        assertEquals(0.0, candidate.measure("rsBelowPriorHigh (DD-6)"), 1e-12)
        assertEquals(1.0, candidate.measure("volumeDecline (S10)"), 1e-12)

        val expected = listOf(
            linear(symShoulder, 0.80, 1.00) to 20,
            linear(headDominance, 0.10, 0.60) to 15,
            linear(headDominance, 0.10, 0.50) to 20,
            linear(timeSymmetry, 0.50, 1.00) to 10,
            linear(1.0 - necklineTilt / 10.0, 0.0, 1.0) to 10,
            linear(1.0, 0.60, 1.00) to 15,
            0.0 to 15,
            100.0 to 10,
        ).fold(0.0) { acc, (reading, weight) -> acc + reading * weight } / 115.0

        assertEquals(expected, 61.762, 1e-2, "the hand-computed geometry weighted mean")
        assertEquals(62, assessed.factors.getValue(ScoreFactor.GEOMETRY))
    }

    @Test
    fun `everyFactorIsScoredAndTheBreakdownTotalIsTheMatchScore`() {
        val series = hsSeries()
        val candidate = singleCandidate(series)
        val match = detector.detect(series.pivots, series.candles).single()

        val assessed = FactorScorer.assess(candidate.measures)
        assertTrue(assessed.isFullyScored, "all four factors must carry at least one applicable measure")
        for (factor in ScoreFactor.ALL) {
            assertTrue(
                candidate.measures.any { it.factor == factor && it.isApplicable },
                "factor ${factor.label} has no applicable measure",
            )
        }

        // `FactorScorer.apply` subtracts the deduction from **every** factor (`scoring-spec.md`
        // §7.2), so the breakdown a `Match` carries is the penalised one and `Match.score` is its
        // total — the penalty can therefore never be hidden inside a single factor.
        val applied = FactorScorer.apply(assessed.breakdown, candidate.penalties)
        val breakdown = assertNotNull(match.breakdown, "a detector must always emit a ScoreBreakdown")
        assertEquals(applied.breakdown.geometry, breakdown.geometry)
        assertEquals(applied.breakdown.structure, breakdown.structure)
        assertEquals(applied.breakdown.confirmation, breakdown.confirmation)
        assertEquals(applied.breakdown.context, breakdown.context)
        assertTrue(
            applied.appliedPenaltyPoints >= applied.rawPenaltyPoints,
            "the §7.2 cap can only drop deductions, never add them",
        )
        assertEquals(breakdown.total, match.score, "CONTRACTS.md: score is always the breakdown total")
        assertTrue(match.score in 0..100)
        // The §12.7 fixture, scored on a synthetic series, reaches the PROJECT threshold. The four
        // factors are pinned here so a change to any normalisation anchor shows up as one number.
        assertEquals(ScoreBreakdown(geometry = 62, structure = 61, confirmation = 93, context = 79), breakdown)
        assertEquals(70, match.score)
        assertEquals(
            MatchScorePolicy.Verdict.PROJECT,
            MatchScorePolicy.verdict(match.score, Timeframe.D1, series.candles.size, regimeAligned = true),
        )
    }

    @Test
    fun `theStructureAndConfirmationMeasuresMatchTheSpecsStatedInputs`() {
        val series = hsSeries()
        val candidate = singleCandidate(series)

        // scoring-spec.md §10.1: legs 16, 18, 16, 18 against `gIdeal 15, gTol 12`.
        assertEquals(0.472656, candidate.measure("S4 spacingScore"), 1e-6)
        // §10.1: span 68 against `spanIdeal 68`, so the raw ratio is exactly 1.
        assertEquals(1.0, candidate.measure("S5 durationFit"), 1e-12)
        // DD-7: only two neckline points exist, so the fit is the neutral 0.60 — never a fabricated 1.
        assertEquals(0.60, candidate.measure("S3 boundaryR2"), 1e-12)
        // §12.7: both neckline points lie on the line, so the residual is 0.
        assertEquals(0.0, candidate.measure("S1 residMax"), 1e-12)
        // §12.7: rvol[136] = 4,900,000 / 1,550,000 = 3.16.
        assertEquals(3.16, candidate.measure("C2 rvolBreakout"), 0.01)
        // §12.7: breakDepth = (1,036.47 − 995.00) / 1,036.47 = 4.00 %.
        assertEquals(4.0, candidate.measure("C1 breakoutDepth"), 0.01)
        // §12.5 / §3.8: the bar-by-bar OBV slope fell with the break, and the next two closes
        // continued down.
        assertEquals(1.0, candidate.measure("C3 followThrough"), 1e-12)
        assertEquals(1.0, candidate.measure("C5 obvAgrees"), 1e-12)
        // §12.7: `priorTrendPct(58) = (1,150 − 1,010) / 1,010 × 100 = +13.861 %`.
        assertEquals(13.861, candidate.measure("X1 priorTrendPct"), 0.001)
    }

    @Test
    fun `aTextbookHeadAndShouldersCarriesNoFailureModePenalties`() {
        val candidate = singleCandidate(hsSeries())
        assertEquals(
            emptyList(),
            candidate.penalties,
            "§12.7's fixture satisfies every §12.3 rule, so no penalty may fire",
        )
    }

    // ------------------------------------------------------------------------------ polarity

    @Test
    fun `aHeadAndShouldersIsNeverAlsoAnInverseHeadAndShoulders`() {
        val series = hsSeries()
        val inverse = InverseHeadAndShouldersDetector().detect(series.pivots, series.candles)
        assertTrue(
            inverse.none { it.pivots.map { p -> p.barIndex } == listOf(58, 74, 92, 108, 126) },
            "the §12.7 five-pivot window is HIGH LOW HIGH LOW HIGH, which is not the inverse role sequence",
        )
    }

    // ----------------------------------------------------------------------------- near misses

    @Test
    fun `aRightShoulderThatBreaksShoulderSymmetryIsNotAMatchAtAll`() {
        // §12.2 S3 is `[derived]` 0.90: "0.90 allows a 10 % shoulder difference". `RS = 1,030` makes
        // `sym = 1030/1150 = 0.89565 < 0.90`, and the book explicitly wants that rejected: calling
        // every three-peak formation a Head & Shoulders is the mistake [Ch.18-2 §Introduction] warns
        // about. No match, rather than a lower-scoring one.
        val series = hsSeries(rightShoulder = 1030.0)
        val matches = detector.detect(series.pivots, series.candles)
        assertTrue(matches.isEmpty(), "shoulder symmetry below 0.90 must produce no match, got $matches")
        assertTrue(ShouldersEngine.candidates("Head & Shoulders", top = true, contextOf(series), confirmedOf(series)).isEmpty())
    }

    @Test
    fun `aRightShoulderJustInsideTheSymmetryFloorMatchesButScoresMateriallyLower`() {
        // `RS = 1,040` ⇒ `sym = 1040/1150 = 0.90435`, which clears §12.2 S3 by 0.004 but drops G1
        // from 95.65 to 52.17 — so the match survives and the score must fall. This is the
        // "assert the direction of the change" case: symmetry worse ⇒ geometry worse.
        // `preTrendHigh = null` in both, so DD-6's scored clause reads the same (1.0) and symmetry is
        // the *only* thing that differs.
        val text = hsSeries(preTrendHigh = null)
        val near = hsSeries(rightShoulder = 1040.0, preTrendHigh = null)
        val textGeometry = geometryOf(text)
        val nearGeometry = geometryOf(near)

        assertTrue(nearGeometry < textGeometry, "sym 0.904 must score below sym 0.991: $nearGeometry vs $textGeometry")
        assertTrue(textGeometry - nearGeometry >= 5, "the fall must be material, was ${textGeometry - nearGeometry}")
    }

    @Test
    fun `aNecklineTiltBeyondTheSixPercentBandIsRejected`() {
        // §12.2 S6: "The neckline may be horizontal, slightly upward sloping, slightly downward
        // sloping. All three are valid." `[derived]` 6.0 % across the whole formation. `T2 = 1,060`
        // lifts the tilt to `(60/34) × 68 / 1000 × 100 = 12.0 %`.
        val series = hsSeries(necklinePoint2 = 1060.0)
        assertTrue(
            detector.detect(series.pivots, series.candles).isEmpty(),
            "a 12 % neckline tilt is a different structure, not a Head & Shoulders",
        )
    }

    @Test
    fun `aFlatNecklineNearTheTiltCeilingStillMatchesButLosesGeometry`() {
        // `T2 = 1,029.5` gives a tilt of 5.9 %: inside the band, so the match survives and only the
        // §12.2 S6 measure (weight 10 of 115) falls. Direction of change: steeper ⇒ lower geometry.
        val flat = hsSeries()
        val steep = hsSeries(necklinePoint2 = 1029.5)
        val flatGeometry = geometryOf(flat)
        val steepGeometry = geometryOf(steep)
        assertTrue(steepGeometry < flatGeometry, "5.9 % tilt must score below 4.0 %: $steepGeometry vs $flatGeometry")
        // §12.2 S6 reads through `LIN_DECAY`: 4.0 % normalises to 60 and 5.9 % to 41.
        assertEquals(60.0, normalised(flat, "G5 |necklineTilt|"), 1e-9)
        assertEquals(41.0, normalised(steep, "G5 |necklineTilt|"), 1e-9)
    }

    @Test
    fun `aRightShoulderThatFailsTimeSymmetryIsRejected`() {
        // §12.2 S9 `[derived]` 0.50 on `sym(gap(LS,T1), gap(T2,RS))`. Moving `RS` to bar 142 makes
        // `g4 = 34` against `g1 = 16` ⇒ `sym = 16/34 = 0.4706 < 0.50`.
        val series = hsSeries(rightShoulderBar = 142, breakoutBar = 200)
        assertTrue(
            detector.detect(series.pivots, series.candles).isEmpty(),
            "a right swing four times the left swing is a different shape",
        )
    }

    // --------------------------------------------------------------------- false breakout

    @Test
    fun `aBullTrapReducesTheScoreAgainstTheSameFormationWithoutTheFailure`() {
        val clean = hsSeries()
        val trapped = hsSeries(closeAfterBreak = listOf(1045.0, 1030.0, 1020.0, 1010.0, 1005.0))

        val cleanMatch = detector.detect(clean.pivots, clean.candles).single()
        val trappedMatch = detector.detect(trapped.pivots, trapped.candles).single()

        val trappedCandidate = singleCandidate(trapped)
        assertTrue(
            trappedCandidate.penalties.any { it.name == "BULL_TRAP" },
            "§12.3 I3: a close back above the neckline inside TRAP_WINDOW bars is a bull trap; " +
                "got ${trappedCandidate.penalties.map { it.name }}",
        )
        assertTrue(
            trappedMatch.score < cleanMatch.score,
            "the trap must lower the score: ${trappedMatch.score} vs ${cleanMatch.score}",
        )
        // And the deduction is the penalty, not a coincidence: removing the penalties from the very
        // same measures restores a strictly higher total.
        val withoutPenalties = FactorScorer.combine(
            FactorScorer.score(trappedCandidate.measures),
            emptyList(),
        )
        assertTrue(withoutPenalties.total > trappedMatch.score)
    }

    @Test
    fun `aFailedRetestIsAPenaltyAndNotATrap`() {
        // §12.3 I5 / §18-5 §Throwbacks: price returned to the neckline and closed back *through* it
        // against the break. `neckline(137) = 1,041.06` and the touch tolerance there is
        // `max(1.5 × 1.5 % of price, 0.5 × ATR) = 23.3`, so a 1,080 close is decisively back above it.
        val series = hsSeries(closeAfterBreak = listOf(1080.0, 1040.0, 1030.0, 1020.0, 1015.0))
        val candidate = singleCandidate(series)
        assertTrue(
            candidate.penalties.any { it.name == "FAILED_RETEST" },
            "§12.3 I5 must fire; got ${candidate.penalties.map { it.name }}",
        )
        assertEquals(0.0, candidate.measure("C4 retestHeld"), 1e-12, "a retest that failed reads 0, not 0.5")
    }

    @Test
    fun `aFormationThatReTakesItsHeadAfterTheBreakIsAFailedPattern`() {
        // §12.3 I4 — the book's own "failed Head & Shoulders": "neckline breaks. Price quickly
        // recovers. Buyers regain control. Stock moves to new highs."
        val series = hsSeries(closeAfterBreak = listOf(1050.0, 1130.0, 1210.0, 1230.0, 1240.0))
        val candidate = singleCandidate(series)
        assertTrue(
            candidate.penalties.any { it.name == "FAILED_PATTERN" },
            "§12.3 I4 must fire; got ${candidate.penalties.map { it.name }}",
        )
    }

    @Test
    fun `a_still_forming_formation_carries_no_measured_move_and_no_confirmation`() {
        // §11.1's FORMING row: "shape rules satisfied, trigger level **not** closed through yet →
        // `measuredMove` is `null`; verdict cannot be `PROJECT`".
        val series = hsSeries(
            closeAtBreak = 1100.0,
            breakoutPivot = false,
            closeAfterBreak = List(9) { 1080.0 },
        )
        val match = detector.detect(series.pivots, series.candles).single()
        assertEquals(null, match.measuredMove, "§11.1: FORMING carries no measured move")
        assertEquals(0.0, singleCandidate(series).measure("C1 breakoutDepth"), 1e-12)
        assertEquals(0.0, singleCandidate(series).measure("C2 rvolBreakout"), 1e-12)
        assertEquals(0.5, singleCandidate(series).measure("C4 retestHeld"), 1e-12)
    }

    @Test
    fun `anUnconfirmedRightShoulderCannotCompleteTheFormation`() {
        // §2.5: "Structural detectors (6–12) skip unconfirmed pivots entirely" — the newest pivot of a
        // series is by definition unconfirmed, so a formation whose right shoulder is that pivot is
        // incomplete. Thresholds are never lowered to compensate.
        val series = hsSeries(rightShoulderBar = SERIES_LAST_BAR)
        val matches = detector.detect(series.pivots, series.candles)
        assertTrue(
            matches.none { it.pivots.any { p -> p.barIndex == SERIES_LAST_BAR } },
            "§2.5 forbids a match that uses an unconfirmed pivot",
        )
    }

    // -------------------------------------------------------------------------------- helpers

    private fun hsSeries(
        leftShoulder: Double = 1150.0,
        head: Double = 1200.0,
        rightShoulder: Double = 1140.0,
        necklinePoint2: Double = 1020.0,
        rightShoulderBar: Int = 126,
        preTrendHigh: Double? = 1120.0,
        closeAtBreak: Double = 995.0,
        breakoutBar: Int = 136,
        breakoutPivot: Boolean = true,
        closeAfterBreak: List<Double>? = null,
        choppyHead: Boolean = true,
    ): BuiltSeries {
        val lastBar = maxOf(SERIES_LAST_BAR, rightShoulderBar + 2, breakoutBar + 1)
        val builder = ChartSeriesBuilder(bars = lastBar + 1)
            // The run-up into the pattern. §12.7 needs `close[38] = 1,010.00` for its prior-trend
            // reading, and the waypoint at bar 20 keeps the rise into `preTrendHigh` smooth so the
            // synthetic ATR is not distorted by one spike bar.
            .at(0, 800.0)
            .at(20, 1050.0)
            .at(38, 1010.0)
            .pivot(40, 950.0, PivotKind.LOW)
            .pivot(58, leftShoulder, PivotKind.HIGH)
            .pivot(74, 1000.0, PivotKind.LOW)
            .pivot(92, head, PivotKind.HIGH)
            .pivot(108, necklinePoint2, PivotKind.LOW)
            .pivot(rightShoulderBar, rightShoulder, PivotKind.HIGH)
            // Walk down to the neckline without closing through it before the breakout bar, then fall
            // through: §3.7 requires the **first** decisive *close*, not the first touch.
            .at(breakoutBar - 1, 1050.0)
            .at(breakoutBar, closeAtBreak)
            .at(lastBar, closeAfterBreak?.lastOrNull() ?: 985.0)
            // §12.7's volume sequence: healthy buying volume at the shoulder, less at the head, less
            // again at the right shoulder, then a breakout on 3.16× the 20-bar average.
            .volumes(0, lastBar, 1_383_333L)
            .volumeAt(58, 2_000_000L)
            .volumeAt(92, 1_600_000L)
            .volumeAt(rightShoulderBar, 1_200_000L)
            .volumeAt(136, 4_900_000L)
            .deliveryEvery(50.0)
            .deliveryAt(136, 61.2)
        if (choppyHead) {
            // §12.7's `I7` needs RSI lower at the head than at the left shoulder — "[Ch.18-2 §Stage 3]
            // names lower RSI at the head as a hallmark". A single smooth rally cannot produce that,
            // so the leg into the head is made choppy while the head's own price is untouched. The
            // geometry rules only read the pivot prices, so this changes the *momentum*, not the shape.
            listOf(66 to 1180.0, 72 to 1120.0, 78 to 1190.0, 84 to 1130.0, 89 to 1195.0)
                .forEach { (bar, price) -> builder.at(bar, price) }
        }
        if (preTrendHigh != null) builder.pivot(30, preTrendHigh, PivotKind.HIGH)
        if (breakoutPivot) builder.pivot(136, closeAtBreak, PivotKind.LOW)
        if (closeAfterBreak != null) {
            closeAfterBreak.forEachIndexed { index, price -> builder.closeAt(137 + index, price) }
        }
        return builder.build()
    }

    private fun singleCandidate(series: BuiltSeries): ChartCandidate {
        val candidates = ShouldersEngine.candidates(
            name = "Head & Shoulders",
            top = true,
            context = contextOf(series),
            confirmed = confirmedOf(series),
        )
        assertEquals(1, candidates.size, "expected exactly one §12.7 formation")
        return candidates.single()
    }

    /** The GEOMETRY factor of the single formation in [series] — the factor §12.2 shapes touch. */
    private fun geometryOf(series: BuiltSeries): Int =
        FactorScorer.geometry(singleCandidate(series).measures)

    private fun contextOf(series: BuiltSeries) = ChartContext.of(series.candles)

    private fun confirmedOf(series: BuiltSeries) = contextOf(series).confirmed(series.pivots)

    private fun ChartCandidate.measure(name: String): Double =
        measures.first { it.name == name }.value

    /** The 0–100 reading the scorer will use for one measure. */
    private fun normalised(series: BuiltSeries, name: String): Double =
        singleCandidate(series).measures.first { it.name == name }.normalised()

    private fun linear(value: Double, lo: Double, hi: Double): Double =
        (100.0 * (value.coerceIn(lo, hi) - lo) / (hi - lo))

    private companion object {
        const val SERIES_LAST_BAR = 145
    }
}