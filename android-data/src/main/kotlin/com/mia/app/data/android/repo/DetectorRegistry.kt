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
import com.mia.app.domain.pattern.detectors.chart.RisingWedgeDetector
import com.mia.app.domain.pattern.detectors.chart.HeadAndShouldersDetector
import com.mia.app.domain.pattern.detectors.chart.InverseHeadAndShouldersDetector
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The v1 detector set, wired in one place.
 *
 * ### Thirteen detectors, fourteen evaluated patterns
 * The KDoc here used to say "twelve" while the list below carried thirteen entries — the count had
 * drifted as detectors were added and the comment was never re-derived. The real relationship:
 *
 * - **13 detector instances** are constructed below.
 * - **14 pattern rows** are evaluated, because [FlagDetector] emits both a bull and a bear flag
 *   from one pass.
 *
 * Those 14 evaluated rows are not 14 equally-live patterns. The five candlestick detectors still
 * run on every scan, but a candle formation has no measured move, and `OutcomeTracker` refuses any
 * match without one — so their matches can never enter the track record. The catalogue therefore
 * lists the candlestick family as **glossary-only** rows (a deliberate labelling decision,
 * documented at length in `PatternCatalogData.kt`). The nine patterns with a live track record are
 * Rising Wedge, Head & Shoulders, Inverse Head & Shoulders, Double Top, Double Bottom, Ascending
 * Triangle, Descending Triangle, Bull Flag and Bear Flag.
 *
 * ### Why the list lives here and not in the engine
 * `PatternEngine.run` takes `detectors: List<PatternDetector>` precisely so the per-pattern
 * packages never leak into each other. Something still has to decide *which* detectors run, and it
 * has to be somewhere reviewable: this is the single line of the app that says "this build ships
 * these patterns and no others", so a fourteenth detector is a visible, deliberate edit.
 *
 * ### Order is deterministic
 * 1–5 candlestick, 6–9 chart reversals, 10–12 continuations, 13 the wedge. The engine sorts its
 * output by score and then by the newest pivot date, so this order affects only which detector's
 * diagnostics appear first — but a stable order makes those diagnostics diffable.
 *
 * Each detector is stateless and a fresh instance is created per scan, which matters because
 * plan.md §6.2 shares the pivot list and indicator snapshot across them: any accidental
 * per-detector field would be a cross-scan leak.
 */
@Singleton
class DetectorRegistry @Inject constructor() {

    /**
     * A fresh list of the thirteen detectors.
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
        // 10–12: continuations [Ch.18-3, Ch.18-4, Ch.18-5]. FlagDetector emits both the bull
        // and the bear flag from one pass, which is where 13 detectors yield 14 evaluated rows.
        AscendingTriangleDetector(),
        DescendingTriangleDetector(),
        FlagDetector(),
        // 13: the wedge the triangle engine used to refuse (§16.2 S4 named it as "no v1
        // detector"). Rising Wedge only — Falling Wedge stays educational until its own
        // measurement exists, because registering an unmeasured detector would put a row in the
        // Explorer that can only ever say "no candidate formed".
        RisingWedgeDetector(),
    )

    /** How many detectors this build runs. Reported by `PatternRepository.storedCount`'s sibling. */
    val size: Int get() = all().size
}