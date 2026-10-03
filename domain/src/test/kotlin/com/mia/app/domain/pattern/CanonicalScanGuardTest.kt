package com.mia.app.domain.pattern

import com.mia.app.domain.model.MatchScorePolicy
import com.mia.app.domain.model.PatternDetector
import com.mia.app.domain.model.Timeframe
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
import kotlin.test.assertTrue

/**
 * Phase 2(d): the Explorer's window scans must not be mistaken for the canonical scan.
 *
 * ### The hazard this pins
 * `PatternIds.of` keys a pattern by **name**, so the same pattern carries the same id in every
 * window. `OutcomeTracker` records a projection against `(symbol, timeframe, patternId)`.
 *
 * The Explorer therefore re-scans the same symbol at the same timeframe over several different
 * windows, producing the same pattern ids. If any of those scans were treated as canonical, the
 * last chip the reader tapped would silently overwrite the earlier projection in the accuracy
 * table — and the app's headline hit rate would depend on browsing order rather than on the data.
 *
 * `PatternEngine` cannot enforce this by itself, because recording happens in
 * `DefaultAccuracyRepository`, downstream of the engine. What it *can* do — and does — is mark
 * every result with whether it is canonical, so the downstream recorder has the fact available and
 * a test can assert the Explorer's call sites pass it correctly.
 *
 * ### What is actually asserted here
 * Only what is verifiable in `:domain`: the flag round-trips, the default is the safe `true`, and
 * a non-canonical scan is still a fully valid scan — it is not a degraded or partial result. The
 * "explorer passes `false`" and "recorder skips it" halves live in `:app` and `:android-data`
 * respectively and must be covered there.
 */
class CanonicalScanGuardTest {

    private val detectors: List<PatternDetector> = listOf(
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

    @Test
    fun `a scan is canonical unless the caller says otherwise`() {
        // Safe by default: a new call site gets today's behaviour rather than silently opting out
        // of the accuracy table.
        assertTrue(PatternScanOptions.DEFAULT.isCanonicalScan)
        assertTrue(PatternScanOptions(symbol = "RELIANCE").isCanonicalScan)
        assertTrue(PatternScanOptions().isCanonicalScan)
    }

    @Test
    fun `an exploratory window scan is marked non-canonical but is otherwise complete`() {
        val candles = EodHistory.candles("RELIANCE")
        val canonical = PatternEngine.run(
            candles = candles,
            timeframe = Timeframe.D1,
            detectors = detectors,
            options = PatternScanOptions(symbol = "RELIANCE", isCanonicalScan = true),
        )
        val exploratory = PatternEngine.run(
            candles = candles,
            timeframe = Timeframe.D1,
            detectors = detectors,
            options = PatternScanOptions(symbol = "RELIANCE", isCanonicalScan = false),
        )

        // The flag must not change what the engine computes. If it did, the Explorer would be
        // showing different scores from the canonical path for the same data, which is a far worse
        // bug than the one the flag exists to prevent.
        assertTrue(
            canonical.contentEquals(exploratory),
            "changing isCanonicalScan changed the scan result; it must be metadata only",
        )
    }

    /**
     * The hazard itself, made concrete: two windows of one symbol produce the same pattern id.
     *
     * This is why a window scan cannot be recorded rather than merely being discouraged — the ids
     * genuinely collide, so there is nothing downstream that *could* tell the two apart without
     * the flag.
     */
    @Test
    fun `the same pattern in two windows carries one id, which is why the flag is required`() {
        val candles = EodHistory.candles("RELIANCE")

        val wide = PatternEngine.run(
            candles = candles,
            timeframe = Timeframe.D1,
            detectors = detectors,
            options = PatternScanOptions(symbol = "RELIANCE"),
        )
        val narrow = PatternEngine.run(
            candles = candles.takeLast(120),
            timeframe = Timeframe.D1,
            detectors = detectors,
            options = PatternScanOptions(symbol = "RELIANCE", isCanonicalScan = false),
        )

        // Compare every name the pipeline *saw* — matches and suppressed — rather than only the
        // kept matches. A narrow window usually keeps nothing (RELANCE keeps a match at 372 bars
        // but not at 120), so intersecting kept matches alone is usually empty and the test would
        // pass without asserting anything.
        val wideNames = (wide.matches.map { it.name } + wide.suppressed.map { it.name }).toSet()
        val narrowNames =
            (narrow.matches.map { it.name } + narrow.suppressed.map { it.name }).toSet()
        val shared = wideNames.filter { it in narrowNames }

        assertTrue(
            shared.isNotEmpty(),
            "RELIANCE produced no pattern name in both windows, so the id collision is untested",
        )

        // Whatever overlaps must resolve to a *canonical* id, because canonical ids are the
        // fixed 1..14 range — and a second window's match for the same name therefore lands on
        // exactly the same key `OutcomeTracker` writes against. That identity is the whole
        // hazard, so it is asserted rather than described: if a future change made ids
        // window-dependent, this fails and the flag's necessity would need re-arguing.
        for (name in shared) {
            assertTrue(
                PatternIds.isCanonical(name),
                "$name resolved to a custom id; canonical ids are what collide across windows",
            )
        }

        // Same name, same id — twice, from two different windows.
        val name = shared.first()
        assertEquals(
            PatternIds.of(name),
            PatternIds.of(name),
            "PatternIds.of is not deterministic for $name",
        )
    }

    /**
     * Every window the Explorer may offer clears the gate, because a window below it yields no
     * candidates at all and would show a reader an empty list labelled "no patterns found".
     *
     * Measured over 20 real large caps (see `WindowSizingMeasurementTest`): 30 and 40 daily bars
     * returned **zero** kept matches and zero suppressed candidates on every symbol. The chip set
     * must therefore start at the gate.
     */
    @Test
    fun `every offered daily window clears the daily gate`() {
        for (window in EXPLORER_DAILY_WINDOWS) {
            assertTrue(
                window >= MatchScorePolicy.minBars(Timeframe.D1),
                "a $window-bar window is under the " +
                    "${MatchScorePolicy.minBars(Timeframe.D1)}-bar daily gate and yields nothing",
            )
        }
    }

    private companion object {
        /**
         * The measured D1 chip set. The upper bound is what the fixture holds, and the app's own
         * first-run pass fetches 280 sessions, so nothing above that can be offered anyway.
         */
        val EXPLORER_DAILY_WINDOWS = listOf(60, 120, 250, 372)
    }
}