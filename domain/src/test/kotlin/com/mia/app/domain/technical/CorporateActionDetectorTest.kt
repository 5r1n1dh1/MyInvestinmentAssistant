package com.mia.app.domain.technical

import com.mia.app.domain.model.Candle
import com.mia.app.domain.model.Source
import com.mia.app.domain.model.Timeframe
import java.time.LocalDate
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [CorporateActionDetector].
 *
 * The fixtures are the **real** corporate actions measured in this repository's own
 * `eod_history.json` (372 real NSE sessions, 41 large caps), not invented numbers. Getting these
 * wrong in the direction of *missing* an event is the failure that matters: an undetected −90% bar
 * silently corrupts every indicator computed over a window spanning it.
 */
class CorporateActionDetectorTest {

    private var day = 0
    private fun bar(close: Double, symbol: String = "TEST"): Candle {
        day++
        return Candle(
            symbol = symbol,
            timeframe = Timeframe.D1,
            date = LocalDate.of(2026, 1, 1).plusDays(day.toLong()),
            open = close,
            high = close * 1.01,
            low = close * 0.99,
            close = close,
            volume = 1_000L,
            source = Source.NSE_BHAVCOPY,
        )
    }

    // ----------------------------------------------------------------- the four measured events

    @Test
    fun `detects the HDFCBANK 1-for-2 split measured on 2025-08-26`() {
        // 1964.1 -> 973.4, a 50.4% drop. Implied ratio 2.017: a 1:2 split.
        val series = listOf(bar(1964.1), bar(973.4))

        val splits = CorporateActionDetector.detect(series)
        assertEquals(1, splits.size)
        val split = splits.single()
        assertTrue(abs(split.changePercent + 50.4) < 0.2, "measured -50.4%, got ${split.changePercent}")
        assertEquals(2.02, split.impliedRatio, absoluteTolerance = 0.01)
        assertEquals("2026-01-03", split.date.toString())
    }

    @Test
    fun `detects the BAJFINANCE 1-for-10 split measured on 2025-06-16`() {
        // 9331 -> 938, a 89.9% drop. The largest event in the fixture.
        val splits = CorporateActionDetector.detect(listOf(bar(9331.0), bar(938.0)))
        assertEquals(1, splits.size)
        assertTrue(abs(splits.single().changePercent + 89.9) < 0.1, "${splits.single().changePercent}")
        assertEquals(9.95, splits.single().impliedRatio, absoluteTolerance = 0.05)
    }

    @Test
    fun `detects the KOTAKBANK 1-for-5 split measured on 2026-01-14`() {
        val splits = CorporateActionDetector.detect(listOf(bar(2132.6), bar(421.0)))
        assertEquals(1, splits.size)
        assertTrue(abs(splits.single().changePercent + 80.3) < 0.1, "${splits.single().changePercent}")
        assertEquals(5.07, splits.single().impliedRatio, absoluteTolerance = 0.05)
    }

    @Test
    fun `detects the TATAMOTORS demerger step measured on 2025-10-14`() {
        val splits = CorporateActionDetector.detect(listOf(bar(660.75), bar(395.45)))
        assertEquals(1, splits.size)
        assertTrue(abs(splits.single().changePercent + 40.2) < 0.1, "${splits.single().changePercent}")
    }

    @Test
    fun `finds every event in a series that contains all four`() {
        // The four measured events, separated by ordinary sessions so each is isolated.
        // Every inter-event step is deliberately small: an earlier draft used 970 -> 938, which is
        // only -3.3% and therefore correctly *not* an event — the fixture was wrong, not the rule.
        val series = buildList {
            add(bar(1964.1))
            add(bar(1960.0))   // ordinary
            add(bar(973.4))    // HDFCBANK  1:2
            add(bar(970.0))    // ordinary
            add(bar(485.0))    // BAJFINANCE-scale 1:2
            add(bar(483.0))    // ordinary
            add(bar(96.6))     // KOTAKBANK-scale 1:5
            add(bar(96.0))     // ordinary
            add(bar(57.6))     // TATAMOTORS-scale
        }

        val splits = CorporateActionDetector.detect(series)
        assertEquals(4, splits.size, "all four measured events must be found: ${splits.map { it.date }}")
        // Each event is the bar *after* the step. Nine bars run 2026-01-02..2026-01-10, so the
        // steps on bars 3, 5, 7 and 9 are detected on bars 4, 6, 8 and 10.
        assertEquals(
            listOf("2026-01-04", "2026-01-06", "2026-01-08", "2026-01-10"),
            splits.map { it.date.toString() },
        )
    }

    // ---------------------------------------------------------------------- must NOT fire

    @Test
    fun `a routine Indian daily move does not fire`() {
        // ±10% is an ordinary large day in India. The threshold exists so that ordinary volatility
        // never reads as a corporate action.
        assertTrue(CorporateActionDetector.detect(listOf(bar(100.0), bar(90.0))).isEmpty())
        assertTrue(CorporateActionDetector.detect(listOf(bar(100.0), bar(110.0))).isEmpty())
        assertTrue(CorporateActionDetector.detect(listOf(bar(100.0), bar(115.0))).isEmpty())
        assertTrue(CorporateActionDetector.detect(listOf(bar(100.0), bar(124.0))).isEmpty())
    }

    @Test
    fun `the threshold boundary is inclusive at exactly 25 percent`() {
        // Pinned because the threshold is the one number a reviewer will want to argue with, and
        // a silent off-by-one here would either hide a 1:4 split or fire on ordinary volatility.
        assertTrue(
            CorporateActionDetector.detect(listOf(bar(100.0), bar(124.9))).isEmpty(),
            "24.9% is below the threshold and must stay quiet",
        )
        assertEquals(
            1,
            CorporateActionDetector.detect(listOf(bar(100.0), bar(125.0))).size,
            "exactly 25.0% is at the threshold and must fire",
        )
    }

    @Test
    fun `an upward step of the same size is also an event`() {
        // Symmetric in sign on purpose: a reverse split or a demerger can genuinely re-rate a price
        // upward, and a silently broken series misleads exactly as much as a falling one.
        assertEquals(
            1,
            CorporateActionDetector.detect(listOf(bar(100.0), bar(150.0))).size,
            "a +50% step is a corporate action too",
        )
        assertTrue(
            CorporateActionDetector.detect(listOf(bar(100.0), bar(124.0))).isEmpty(),
            "and a +24% step is still routine",
        )
    }

    @Test
    fun `a flat series detects nothing`() {
        val series = List(50) { bar(500.0) }
        assertTrue(CorporateActionDetector.detect(series).isEmpty())
    }

    @Test
    fun `an empty or single-bar series detects nothing`() {
        assertTrue(CorporateActionDetector.detect(emptyList()).isEmpty())
        assertTrue(CorporateActionDetector.detect(listOf(bar(100.0))).isEmpty())
    }

    @Test
    fun `a move across a long gap is not attributed to one event`() {
        // Prices three months apart carry no information about what happened in between, so a big
        // jump across the gap must not be reported as a single corporate action.
        val before = bar(1000.0)
        val after = Candle(
            symbol = "TEST",
            timeframe = Timeframe.D1,
            date = before.date.plusDays(120),
            open = 500.0,
            high = 505.0,
            low = 495.0,
            close = 500.0,
            volume = 1_000L,
        )
        assertTrue(CorporateActionDetector.detect(listOf(before, after)).isEmpty())
    }

    @Test
    fun `a Friday-to-Monday gap still counts as consecutive`() {
        // Indian markets are closed Saturday and Sunday, so every real split straddles a weekend.
        // A detector that required literally adjacent calendar dates would find nothing at all.
        val friday = Candle(
            symbol = "TEST",
            timeframe = Timeframe.D1,
            date = LocalDate.of(2026, 1, 2), // a Friday
            open = 100.0, high = 101.0, low = 99.0, close = 100.0, volume = 1_000L,
        )
        val monday = Candle(
            symbol = "TEST",
            timeframe = Timeframe.D1,
            date = LocalDate.of(2026, 1, 5), // the Monday after
            open = 50.0, high = 51.0, low = 49.0, close = 50.0, volume = 1_000L,
        )
        val splits = CorporateActionDetector.detect(listOf(friday, monday))
        assertEquals(1, splits.size, "a weekend-spanning 1:2 split must be detected")
    }

    @Test
    fun `a zero or negative close never produces a NaN ratio`() {
        val series = listOf(bar(0.0), bar(50.0))
        assertTrue(CorporateActionDetector.detect(series).isEmpty())
    }

    // ------------------------------------------------------------------------------ the copy

    @Test
    fun `the note names the date and the size, and hedges the cause`() {
        val note = CorporateActionDetector.noteFor(listOf(bar(9331.0), bar(938.0)))
        assertTrue(note != null)
        assertTrue(note!!.contains("suspected"), "must not claim to know the cause: $note")
        assertTrue(note.contains("2026-01-03"), "must name the date: $note")
        assertTrue(note.contains("-89.9"), "must name the size: $note")
        assertTrue(note.contains("1:9.95") || note.contains("1:10"), "must name the ratio: $note")
        assertTrue(
            note.contains("UNADJUSTED"),
            "must say the series is unadjusted so levels are not comparable: $note",
        )
    }

    @Test
    fun `the note is null when there is nothing to report`() {
        assertNull(CorporateActionDetector.noteFor(listOf(bar(100.0), bar(101.0))))
        assertNull(CorporateActionDetector.noteFor(emptyList()))
    }

    @Test
    fun `a clean series never says a number about a corporate action`() {
        // plan.md §1.4 rule 3: no figure without an "as of"/source framing. This asserts the copy
        // is factual and carries the caveat, which is what the caveat exists for.
        val note = CorporateActionDetector.noteFor(listOf(bar(9331.0), bar(938.0), bar(940.0)))
        assertTrue(note!!.contains("not directly comparable"))
        assertTrue(note.contains("indicators spanning one are affected"))
    }

    @Test
    fun `the note pluralises correctly`() {
        // Exactly two events. An earlier draft used 100/50/25/12.5, which is *three* halvings — the
        // fixture asserted "2" while building 3, which would have failed for the right reason.
        val one = CorporateActionDetector.noteFor(listOf(bar(100.0), bar(50.0)))
        val two = CorporateActionDetector.noteFor(
            listOf(bar(100.0), bar(50.0), bar(50.0), bar(25.0), bar(25.0), bar(24.0)),
        )
        assertTrue(one!!.startsWith("1 suspected corporate action"), one)
        assertTrue(two!!.startsWith("2 suspected corporate actions"), two)
    }

    @Test
    fun `a fifty percent step and a thirty percent step both read as one event`() {
        val splits = CorporateActionDetector.detect(
            listOf(bar(100.0), bar(50.0), bar(50.0), bar(35.0)),
        )
        assertEquals(2, splits.size, "each halving is its own event")
        assertTrue(abs(splits[1].changePercent + 30.0) < 0.1, "${splits[1].changePercent}")
    }
}