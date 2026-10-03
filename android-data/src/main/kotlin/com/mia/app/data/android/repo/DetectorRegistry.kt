package com.mia.app.data.android.repo

import com.mia.app.domain.model.PatternDetector
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
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The v1 detector set — plan.md §6.5's twelve patterns, wired in one place.
 *
 * ### Why the list lives here and not in the engine
 * `PatternEngine.run` takes `detectors: List<PatternDetector>` precisely so the per-pattern
 * packages never leak into each other. Something still has to decide *which* twelve run, and it
 * has to be somewhere reviewable: this is the single line of the app that says "v1 ships these
 * patterns and no others", so a thirteenth detector is a visible, deliberate edit.
 *
 * ### Order is deterministic and matches plan.md §6.5
 * 1–5 candlestick, 6–9 reversal, 10–12 continuation. The engine sorts its output by score and
 * then by the newest pivot date, so this order affects only which detector's diagnostics appear
 * first — but a stable order makes those diagnostics diffable.
 *
 * Each detector is stateless and a fresh instance is created per scan, which matters because
 * plan.md §6.2 shares the pivot list and indicator snapshot across them: any accidental
 * per-detector field would be a cross-scan leak.
 */
@Singleton
class DetectorRegistry @Inject constructor() {

    /**
     * A fresh list of the twelve detectors.
     *
     * @return new instances, so no caller can hold a detector that another scan has already used.
     */
    fun all(): List<PatternDetector> = listOf(
        // 1–5: candlestick reversals of the immediately preceding bar's pressure [Ch.17-2].
        BullishEngulfingDetector(),
        BearishEngulfingDetector(),
        HammerDetector(),
        ShootingStarDetector(),
        StarDetector(),
        // 6–9: chart reversals [Ch.17-4, Ch.18-2, Ch.18-4].
        HeadAndShouldersDetector(),
        InverseHeadAndShouldersDetector(),
        DoubleTopDetector(),
        DoubleBottomDetector(),
        // 10–12: continuations [Ch.18-3, Ch.18-4, Ch.18-5].
        AscendingTriangleDetector(),
        DescendingTriangleDetector(),
        FlagDetector(),
    )

    /** How many detectors this build runs. Reported by `PatternRepository.storedCount`'s sibling. */
    val size: Int get() = all().size
}