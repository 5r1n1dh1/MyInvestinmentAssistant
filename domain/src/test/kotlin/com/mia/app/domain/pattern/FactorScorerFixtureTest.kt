package com.mia.app.domain.pattern

import com.mia.app.domain.model.Direction
import com.mia.app.domain.model.MatchScorePolicy
import com.mia.app.domain.model.ScoreBreakdown
import com.mia.app.domain.model.Timeframe
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The three hand-computed fixtures of `scoring-spec.md` §10, reproduced **exactly**.
 *
 * They are the acceptance criterion for the scorer: if these three numbers move, either the
 * normalisation anchors, the weights, the penalty cap or the rounding changed, and plan.md §11's
 * P7 gate is no longer meaningful.
 *
 * | | A (perfect H&S) | B (ambiguous H&S) | C (near-miss double top) |
 * |---|---|---|---|
 * | GEOMETRY | 68 | 52 | 69 |
 * | STRUCTURE | 69 | 61 | 69 |
 * | CONFIRMATION | 98 | 50 | 31 |
 * | CONTEXT | 67 | 68 | 62 |
 * | rawPenalty | 0 | 17 | 37 |
 * | totalPenalty (cap 30) | 0 | 17 | 30 |
 * | **score** | **74** | **39** | **30** |
 * | verdict | PROJECT | SUPPRESS | SUPPRESS |
 *
 * B is the important negative: a formation that *looks* like an H&S and passes every shape rule
 * must score 39 and never be shown. C is the cap fixture: without the cap it would read 23.
 */
class FactorScorerFixtureTest {

    private val d1 = Timeframe.D1
    private val barCount = 500

    // --------------------------------------------------------------------- EXAMPLE A

    @Test
    fun `exampleA_textbookPerfectHeadAndShoulders_scores74_andProjects`() {
        val assessed = FactorScorer.assess(ScoringFixtures.exampleA())

        assertEquals(68, assessed.factors.getValue(ScoreFactor.GEOMETRY), "G")
        assertEquals(69, assessed.factors.getValue(ScoreFactor.STRUCTURE), "S")
        assertEquals(98, assessed.factors.getValue(ScoreFactor.CONFIRMATION), "C")
        assertEquals(67, assessed.factors.getValue(ScoreFactor.CONTEXT), "X")

        val application = FactorScorer.apply(assessed.breakdown, ScoringFixtures.exampleAPenalties())
        assertEquals(0, application.rawPenaltyPoints)
        assertEquals(0, application.appliedPenaltyPoints)

        // `Match.score` is always `ScoreBreakdown.total`.
        assertEquals(74, application.breakdown.total)
        assertEquals(
            MatchScorePolicy.Verdict.PROJECT,
            MatchScorePolicy.verdict(application.breakdown.total, d1, barCount, true),
        )
        // The measured move the book prints for the same formation.
        assertEquals(189.412, ScoringFixtures.exampleAHeight, 0.001)
        assertEquals(995.0 - ScoringFixtures.exampleAHeight, ScoringFixtures.exampleAProjectedLevel, 0.001)
    }

    // --------------------------------------------------------------------- EXAMPLE B

    @Test
    fun `exampleB_ambiguousHeadAndShoulders_scores39_andIsSuppressed`() {
        val assessed = FactorScorer.assess(ScoringFixtures.exampleB())

        assertEquals(52, assessed.factors.getValue(ScoreFactor.GEOMETRY), "G")
        assertEquals(61, assessed.factors.getValue(ScoreFactor.STRUCTURE), "S")
        assertEquals(50, assessed.factors.getValue(ScoreFactor.CONFIRMATION), "C")
        assertEquals(68, assessed.factors.getValue(ScoreFactor.CONTEXT), "X")

        val penalties = ScoringFixtures.exampleBPenalties()
        val application = FactorScorer.apply(assessed.breakdown, penalties)
        assertEquals(17, application.rawPenaltyPoints)
        assertEquals(17, application.appliedPenaltyPoints)
        assertEquals(39, application.breakdown.total)
        assertEquals(
            MatchScorePolicy.Verdict.SUPPRESS,
            MatchScorePolicy.verdict(application.breakdown.total, d1, barCount, true),
        )
    }

    @Test
    fun `exampleB_hasNoTimeSymmetrySoItsSpacingScoreCollapsesToZero`() {
        // The book relies on this: one leg of 28 bars against an ideal of 15±12 clamps to 0, and
        // the product of the leg scores is therefore 0 — a formation that "passes every shape
        // rule" still fails on structure.
        assertEquals(0.0, spacingScore(listOf(14, 12, 14, 28), gIdeal = 15.0, gTol = 12.0), 1e-12)
        assertEquals(0.0, legSpacingScore(28, 15.0, 12.0), 1e-12)
    }

    // --------------------------------------------------------------------- EXAMPLE C

    @Test
    fun `exampleC_nearMissDoubleTop_scores30_afterThePenaltyCapBinds`() {
        val assessed = FactorScorer.assess(ScoringFixtures.exampleC())
        assertEquals(69, assessed.factors.getValue(ScoreFactor.GEOMETRY), "G (spec's redistributed weights)")
        assertEquals(69, assessed.factors.getValue(ScoreFactor.STRUCTURE), "S")
        assertEquals(31, assessed.factors.getValue(ScoreFactor.CONFIRMATION), "C")
        assertEquals(62, assessed.factors.getValue(ScoreFactor.CONTEXT), "X")

        val penalties = ScoringFixtures.exampleCPenalties()
        val application = FactorScorer.apply(assessed.breakdown, penalties)
        assertEquals(37, application.rawPenaltyPoints)
        assertEquals(PatternScoringPolicy.PENALTY_CAP, application.appliedPenaltyPoints)
        assertTrue(application.penaltyCapBound)
        assertEquals(30, application.breakdown.total)

        // Without the cap the number would read 23. The cap changes the *reported* number, never
        // the verdict, so it cannot launder a failed pattern back into view.
        val uncapped = application.breakdown.total - (application.rawPenaltyPoints - application.appliedPenaltyPoints)
        assertEquals(23, uncapped)
        assertEquals(
            MatchScorePolicy.Verdict.SUPPRESS,
            MatchScorePolicy.verdict(uncapped, d1, barCount, true),
        )
    }

    @Test
    fun `exampleC_geometryDependsOnWhichWeightSetIsUsedAndThatIsDocumented`() {
        // scoring-spec.md §10.3 prints `G = round(90·0.3333 + 0·0.1667 + 66.667·0.3333 + 100·0.1667) = 69`.
        // Those four coefficients are 0.3333/0.1667/0.3333/0.1667 — the two freed weights (G4 time
        // symmetry and G5 neckline tilt, 0.10 each) spread over the four remaining measures.
        // §3's table, however, weights those same four measures 0.20/0.15/0.20/0.15, which
        // renormalises to 0.2857/0.2143/0.2857/0.2143 and scores 66.
        //
        // The scorer implements the *rule* ("renormalise the applicable weights"), not a printed
        // constant, so both are asserted here and the discrepancy is reported rather than hidden.
        assertEquals(69, FactorScorer.geometry(ScoringFixtures.exampleCGeometry(specWeights = true)), "spec's printed weights")
        assertEquals(66, FactorScorer.geometry(ScoringFixtures.exampleCGeometry(specWeights = false)), "§3 table weights")
    }

    // ------------------------------------------------------------------ shared assertions

    @Test
    fun `everyFixtureBreakdownTotalEqualsItsIndependentRecomputation`() {
        for ((label, measures, penalties) in allFixtures()) {
            val assessed = FactorScorer.assess(measures)
            val breakdown = FactorScorer.combine(assessed.breakdown, penalties)
            val independent = (
                breakdown.geometry * MatchScorePolicy.GEOMETRY_WEIGHT +
                    breakdown.structure * MatchScorePolicy.STRUCTURE_WEIGHT +
                    breakdown.confirmation * MatchScorePolicy.CONFIRMATION_WEIGHT +
                    breakdown.context * MatchScorePolicy.CONTEXT_WEIGHT
                ) / 100
            assertEquals(independent, breakdown.total, "$label: total must equal the weighted sum")
            assertTrue(breakdown.total in 0..100, "$label: score out of range")
        }
    }

    @Test
    fun `theThreeFixturesSpanThirtyToSeventyFour`() {
        val scores = allFixtures().map { (label, measures, penalties) ->
            label to FactorScorer.combine(FactorScorer.score(measures), penalties).total
        }
        assertEquals(listOf("A" to 74, "B" to 39, "C" to 30), scores)
        assertEquals(44, scores.maxOf { it.second } - scores.minOf { it.second })
    }

    @Test
    fun `penaltyCeilingNeverLetsAMatchBackOverTheSuppressThreshold`() {
        // scoring-spec.md §7.2 guarantee: the factor sum is computed first, penalties subtract, and
        // the clamp only ever lowers. A *perfect* match with every penalty in the catalogue still
        // lands at exactly pre − 30, never above.
        val best = ScoreBreakdown(100, 100, 100, 100)
        val worstPenalty = List(10) { Penalty.of(PenaltyId.FAILED_PATTERN, "repeated failure") }
        val result = FactorScorer.combine(best, worstPenalty)
        assertEquals(100 - PatternScoringPolicy.PENALTY_CAP, result.total)
        assertTrue(result.total < best.total, "penalties can only lower the score")
        assertTrue(result.total >= 0)
    }

    @Test
    fun `reversalContextUsesTheInvertedAdxBand`() {
        // scoring-spec.md §6 X2: "a reversal pattern wants ADX *down*". 31 → 45, 30 → 50, 34 → 30.
        assertEquals(45.0, X2(31.0), 1e-9)
        assertEquals(50.0, X2(30.0), 1e-9)
        assertEquals(30.0, X2(34.0), 1e-9)
    }

    private fun X2(adx: Double): Double = MeasureBand
        .Linear(false, 0.0, PatternScoringPolicy.CTX_ADX_LO)
        .normalise(PatternScoringPolicy.CTX_ADX_HI - adx)

    private fun allFixtures(): List<Triple<String, List<Measure>, List<Penalty>>> = listOf(
        Triple("A", ScoringFixtures.exampleA(), ScoringFixtures.exampleAPenalties()),
        Triple("B", ScoringFixtures.exampleB(), ScoringFixtures.exampleBPenalties()),
        Triple("C", ScoringFixtures.exampleC(), ScoringFixtures.exampleCPenalties()),
    )
}

/**
 * The measure sets behind `scoring-spec.md` §10's three worked examples.
 *
 * Every raw value is the number the spec prints, and every normalisation is the spec's formula,
 * so the tests assert arithmetic rather than re-implementing it. The pivots themselves come from
 * `patterns-spec.md` §12.7 / §14.7 so the geometry can be *derived* rather than asserted.
 */
internal object ScoringFixtures {

    // patterns-spec.md §12.7, the textbook H&S.
    private val necklineSlope = (1020.0 - 1000.0) / (108 - 74)
    private val necklineAt92 = 1000.0 + necklineSlope * (92 - 74)
    private val headDominanceA = (1200.0 - 1150.0) / (1200.0 - necklineAt92)

    /** The book's own measured move for example A. */
    val exampleAHeight = 1200.0 - necklineAt92
    val exampleAProjectedLevel = 995.0 - exampleAHeight

    private fun geometryA(): List<Measure> = listOf(
        Measure.linear(
            "G1 symShoulder", symmetryRatio(1150.0, 1140.0), 20, ScoreFactor.GEOMETRY,
            PatternScoringPolicy.GEOM_SYM_LO, PatternScoringPolicy.GEOM_SYM_HI,
        ),
        Measure.linear(
            "G2 headDominance", headDominanceA, 15, ScoreFactor.GEOMETRY,
            PatternScoringPolicy.GEOM_DOM_LO, PatternScoringPolicy.GEOM_DOM_HI,
        ),
        Measure.linear(
            "G3 headDominance", headDominanceA, 20, ScoreFactor.GEOMETRY,
            PatternScoringPolicy.GEOM_DOM3_LO, PatternScoringPolicy.GEOM_DOM3_HI,
        ),
        Measure.linear(
            "G4 timeSym", symmetryRatio(16.0, 18.0), 10, ScoreFactor.GEOMETRY,
            PatternScoringPolicy.GEOM_TIMESYM_LO, PatternScoringPolicy.GEOM_TIMESYM_HI,
        ),
        Measure.decay(
            "G5 |neckTilt|", 4.0, 10, ScoreFactor.GEOMETRY,
            PatternScoringPolicy.GEOM_TILT_PERFECT, PatternScoringPolicy.GEOM_TILT_FLOOR,
        ),
        Measure.linear(
            "G6 monoScore", 1.0, 15, ScoreFactor.GEOMETRY,
            PatternScoringPolicy.GEOM_MONO_LO, PatternScoringPolicy.GEOM_MONO_HI,
        ),
    )

    private fun structureA(legs: List<Int>, spanBars: Int, trendlineR2: Double): List<Measure> = listOf(
        Measure.decay(
            "S1 residMax", 0.0, 25, ScoreFactor.STRUCTURE, 0.0, PatternScoringPolicy.STRUCT_RESID_FLOOR,
        ),
        Measure.linear(
            "S2 trendlineFitRSquared", trendlineR2, 20, ScoreFactor.STRUCTURE,
            PatternScoringPolicy.STRUCT_R2_LO, PatternScoringPolicy.STRUCT_R2_HI,
        ),
        Measure.linear(
            "S3 boundaryR2", PatternScoringPolicy.NEUTRAL_BOUNDARY_R2, 15, ScoreFactor.STRUCTURE,
            PatternScoringPolicy.STRUCT_BOUNDARY_R2_LO, PatternScoringPolicy.STRUCT_BOUNDARY_R2_HI,
        ),
        Measure.linear(
            "S4 spacing", spacingScore(legs, 15.0, 12.0), 15, ScoreFactor.STRUCTURE, 0.0, 1.0,
        ),
        Measure.linear(
            // The raw ratio: `durationScore` is `LIN_LINEAR(span / spanIdeal, 0.30, 2.00)` and the
            // band does that normalisation, so the measure carries the ratio, not the score.
            "S5 duration", spanBars / 68.0, 15, ScoreFactor.STRUCTURE,
            PatternScoringPolicy.STRUCT_DUR_LO, PatternScoringPolicy.STRUCT_DUR_HI,
        ),
    )

    private fun confirmationChart(
        breakoutDepthPct: Double,
        rvol: Double,
        followThrough: Double,
        retestHeld: Double,
        obvAgrees: Double,
        deliveryVsAvg: Double,
        gapRsi: Double,
    ): List<Measure> = listOf(
        Measure.saturating(
            "C1 breakoutDepth", breakoutDepthPct, 25, ScoreFactor.CONFIRMATION,
            PatternScoringPolicy.CONF_BREAKDEPTH_SAT,
        ),
        Measure.saturating(
            "C2 rvolBreakout", rvol, 25, ScoreFactor.CONFIRMATION, PatternScoringPolicy.CONF_RVOL_SAT,
        ),
        Measure.identity("C3 followThrough", followThrough, 10, ScoreFactor.CONFIRMATION),
        Measure.identity("C4 retestHeld", retestHeld, 10, ScoreFactor.CONFIRMATION),
        Measure.identity("C5 obvAgrees", obvAgrees, 10, ScoreFactor.CONFIRMATION),
        Measure.saturating(
            "C6 deliveryVsAvg", deliveryVsAvg, 10, ScoreFactor.CONFIRMATION,
            PatternScoringPolicy.CONF_DELIVERY_SAT,
        ),
        Measure.linear(
            "C7 gapRsi", gapRsi, 10, ScoreFactor.CONFIRMATION, 0.0, PatternScoringPolicy.CONF_RSI_GAP_HI,
        ),
    )

    private fun context(
        priorTrendPct: Double,
        adx: Double,
        diAligned: Boolean,
        relativeStrength: Double,
        htfAgrees: Boolean,
        marketWeak: Boolean,
    ): List<Measure> = listOf(
        Measure.linear(
            "X1 priorTrendPct", priorTrendPct, 35, ScoreFactor.CONTEXT,
            PatternScoringPolicy.CTX_PRIOR_LO, PatternScoringPolicy.CTX_PRIOR_HI,
        ),
        Measure.linear(
            "X2 adxRegime", PatternScoringPolicy.CTX_ADX_HI - adx, 25, ScoreFactor.CONTEXT,
            0.0, PatternScoringPolicy.CTX_ADX_LO,
        ),
        Measure.binary("X3 diAligned", diAligned, 10, ScoreFactor.CONTEXT),
        Measure.saturating(
            "X4 relativeStrength", relativeStrength, 15, ScoreFactor.CONTEXT,
            PatternScoringPolicy.CTX_RS_SAT,
        ),
        Measure.binary("X5 htfAgrees", htfAgrees, 10, ScoreFactor.CONTEXT),
        Measure.binary("X6 marketHealthy", !marketWeak, 5, ScoreFactor.CONTEXT),
    )

    fun exampleA(): List<Measure> = geometryA() +
        structureA(legs = listOf(16, 18, 16, 18), spanBars = 68, trendlineR2 = 0.910) +
        confirmationChart(
            breakoutDepthPct = 4.000,
            rvol = 3.161,
            followThrough = 1.0,
            retestHeld = 1.0,
            obvAgrees = 1.0,
            deliveryVsAvg = 1.24,
            gapRsi = 7.60,
        ) +
        context(
            priorTrendPct = 13.861,
            adx = 31.0,
            diAligned = false,
            relativeStrength = 0.06,
            htfAgrees = true,
            marketWeak = false,
        )

    fun exampleAPenalties(): List<Penalty> = emptyList()

    // ---------------------------------------------------------------------------------------
    // Example B: same five roles, flat neckline, mismatched shoulders, a shallow head.

    private val necklineSlopeB = (1005.0 - 1000.0) / (98 - 72)
    private val headDominanceB = (1060.0 - 1050.0) / (1060.0 - (1000.0 + necklineSlopeB * (84 - 72)))

    private fun geometryB(): List<Measure> = listOf(
        Measure.linear(
            "G1 symShoulder", symmetryRatio(1050.0, 1020.0), 20, ScoreFactor.GEOMETRY,
            PatternScoringPolicy.GEOM_SYM_LO, PatternScoringPolicy.GEOM_SYM_HI,
        ),
        Measure.linear(
            "G2 headDominance", headDominanceB, 15, ScoreFactor.GEOMETRY,
            PatternScoringPolicy.GEOM_DOM_LO, PatternScoringPolicy.GEOM_DOM_HI,
        ),
        Measure.linear(
            "G3 headDominance", headDominanceB, 20, ScoreFactor.GEOMETRY,
            PatternScoringPolicy.GEOM_DOM3_LO, PatternScoringPolicy.GEOM_DOM3_HI,
        ),
        Measure.linear(
            "G4 timeSym", symmetryRatio(14.0, 28.0), 10, ScoreFactor.GEOMETRY,
            PatternScoringPolicy.GEOM_TIMESYM_LO, PatternScoringPolicy.GEOM_TIMESYM_HI,
        ),
        Measure.decay(
            "G5 |neckTilt|",
            abs(necklineSlopeB * 68 / 1000.0 * 100.0), 10, ScoreFactor.GEOMETRY,
            PatternScoringPolicy.GEOM_TILT_PERFECT, PatternScoringPolicy.GEOM_TILT_FLOOR,
        ),
        Measure.linear(
            "G6 monoScore", 1.0, 15, ScoreFactor.GEOMETRY,
            PatternScoringPolicy.GEOM_MONO_LO, PatternScoringPolicy.GEOM_MONO_HI,
        ),
    )

    fun exampleB(): List<Measure> = geometryB() +
        structureA(legs = listOf(14, 12, 14, 28), spanBars = 68, trendlineR2 = 0.905) +
        confirmationChart(
            breakoutDepthPct = 1.3381,
            rvol = 1.72,
            followThrough = 0.0,
            retestHeld = 1.0,
            obvAgrees = 0.0,
            deliveryVsAvg = 0.92,
            gapRsi = 2.5,
        ) +
        context(
            priorTrendPct = 13.861,
            adx = 30.0,
            diAligned = false,
            relativeStrength = 0.05,
            htfAgrees = true,
            marketWeak = false,
        )

    fun exampleBPenalties(): List<Penalty> = listOf(
        Penalty.of(PenaltyId.WEAK_VOLUME, "rvol 1.72 on the breakdown bar"),
        Penalty.of(PenaltyId.NO_DIVERGENCE, "gapRsi 2.5 < 3.0"),
        Penalty.of(PenaltyId.NO_FOLLOW_THROUGH, "the next bar closed above the breakdown close"),
    )

    // ---------------------------------------------------------------------------------------
    // Example C: two peaks 2 % apart, a shallow trough, no volume expansion, a bear trap.

    /**
     * @param specWeights `true` uses the redistributed coefficients `scoring-spec.md` §10.3
     *   prints (0.3333/0.1667/0.3333/0.1667); `false` uses §3's 0.20/0.15/0.20/0.15.
     */
    fun exampleCGeometry(specWeights: Boolean): List<Measure> {
        val (g1, g2, g3, g6) = if (specWeights) {
            listOf(20, 10, 20, 10)
        } else {
            listOf(20, 15, 20, 15)
        }
        return listOf(
            Measure.linear(
                "G1 sym", symmetryRatio(1200.0, 1176.0), g1, ScoreFactor.GEOMETRY,
                PatternScoringPolicy.GEOM_SYM_LO, PatternScoringPolicy.GEOM_SYM_HI,
            ),
            Measure.linear(
                "G2 troughDepthAtr", (1200.0 - 1140.0) / (2 * 18.0), g2, ScoreFactor.GEOMETRY,
                PatternScoringPolicy.GEOM_DOM_DT_LO, PatternScoringPolicy.GEOM_DOM_DT_HI,
            ),
            Measure.linear(
                "G3 peakSym", symmetryRatio(1200.0, 1176.0), g3, ScoreFactor.GEOMETRY,
                0.94, 1.00,
            ),
            Measure.linear(
                "G6 monoScore", 1.0, g6, ScoreFactor.GEOMETRY,
                PatternScoringPolicy.GEOM_MONO_LO, PatternScoringPolicy.GEOM_MONO_HI,
            ),
        )
    }

    fun exampleC(): List<Measure> = exampleCGeometry(specWeights = true) +
        listOf(
            Measure.decay(
                "S1 residMax", 0.0, 25, ScoreFactor.STRUCTURE, 0.0, PatternScoringPolicy.STRUCT_RESID_FLOOR,
            ),
            Measure.linear(
                "S2 trendlineFitRSquared", 0.880, 20, ScoreFactor.STRUCTURE,
                PatternScoringPolicy.STRUCT_R2_LO, PatternScoringPolicy.STRUCT_R2_HI,
            ),
            Measure.linear(
                "S3 boundaryR2", PatternScoringPolicy.NEUTRAL_BOUNDARY_R2, 15, ScoreFactor.STRUCTURE,
                PatternScoringPolicy.STRUCT_BOUNDARY_R2_LO, PatternScoringPolicy.STRUCT_BOUNDARY_R2_HI,
            ),
            Measure.linear(
                "S4 spacing", spacingScore(listOf(42), 30.0, 25.0), 15, ScoreFactor.STRUCTURE, 0.0, 1.0,
            ),
            Measure.linear(
                "S5 duration", 42 / 42.0, 15, ScoreFactor.STRUCTURE,
                PatternScoringPolicy.STRUCT_DUR_LO, PatternScoringPolicy.STRUCT_DUR_HI,
            ),
        ) +
        confirmationChart(
            breakoutDepthPct = 1.05263,
            rvol = 1.21,
            followThrough = 0.0,
            retestHeld = 0.0,
            obvAgrees = 0.0,
            deliveryVsAvg = 0.71,
            gapRsi = 2.2,
        ) +
        context(
            priorTrendPct = 20.000,
            adx = 34.0,
            diAligned = false,
            relativeStrength = 0.04,
            htfAgrees = true,
            marketWeak = false,
        )

    fun exampleCPenalties(): List<Penalty> = listOf(
        Penalty.of(PenaltyId.BEAR_TRAP, "closed back above the confirmation level"),
        Penalty.of(PenaltyId.WEAK_VOLUME, "rvol 1.21 on the breakdown bar"),
        Penalty.of(PenaltyId.NO_DIVERGENCE, "gapRsi 2.2 < 3.0"),
        Penalty.of(PenaltyId.FAILED_RETEST, "the pullback closed back above support"),
        Penalty.of(PenaltyId.NO_FOLLOW_THROUGH, "the next bar closed higher"),
    )
}