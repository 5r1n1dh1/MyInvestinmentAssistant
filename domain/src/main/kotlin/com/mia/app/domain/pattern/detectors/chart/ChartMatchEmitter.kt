package com.mia.app.domain.pattern.detectors.chart

import com.mia.app.domain.model.Candle
import com.mia.app.domain.model.Match
import com.mia.app.domain.model.PatternDetector
import com.mia.app.domain.pattern.matchOf
import kotlin.math.abs

/**
 * Turns the candidates a chart detector found into `Match` objects, in the order
 * `patterns-spec.md` §4 rule 3 requires.
 *
 * **Why the ordering is by formation, not by `detectedOn`.** §4 rule 3 says matches come back
 * "in ascending `detectedOn` order", but `matchOf` stamps `detectedOn` with the *latest bar*
 * (`patterns-spec.md` DD-12: a `Match` is re-emitted every day while the formation persists). Every
 * match from one scan therefore shares one date and that ordering is a no-op. The order that does
 * carry information — and the one a chart needs — is oldest formation first, with the formations
 * that have **not** triggered yet last, because a `FORMING` match has no breakout bar to order by
 * and is the least established of the set.
 *
 * Rule 6 ("at most one `Match` per (formation, breakout) pair") holds by construction: each
 * detector produces at most one candidate per role window, and each window has exactly one first
 * breakout bar.
 */
internal fun buildChartMatches(
    detector: PatternDetector,
    candles: List<Candle>,
    candidates: List<ChartCandidate>,
): List<Match> = candidates
    .sortedWith(
        compareBy<ChartCandidate> { it.isForming }
            .thenBy { it.formationStartBar }
            .thenBy { it.breakoutBar },
    )
    .map { candidate ->
        matchOf(
            detector = detector,
            candles = candles,
            pivots = candidate.pivots,
            measures = candidate.measures,
            measuredMove = candidate.measuredMove,
            penalties = candidate.penalties,
            name = candidate.name,
        )
    }

/** `abs`, spelled once, because a slope is signed and a *tilt* never is. */
internal fun magnitude(value: Double): Double = abs(value)