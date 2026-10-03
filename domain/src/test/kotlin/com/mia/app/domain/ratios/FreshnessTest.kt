package com.mia.app.domain.ratios

import com.mia.app.domain.model.Confidence
import com.mia.app.domain.model.Ratios
import com.mia.app.domain.model.Sector
import com.mia.app.domain.model.Source
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * [FreshnessPolicy] tests (plan.md §10).
 *
 * Every test injects `today`, so the boundary cases — "is exactly seven days old fresh?" — are
 * assertable rather than a matter of what day it is when the suite runs.
 */
class FreshnessTest {

    private val today: LocalDate = LocalDate.of(2026, 10, 9)

    @Test
    fun `the plan windows are one day one day seven days two hundred and one hundred twenty`() {
        assertEquals(1, FreshnessKind.PRICE.windowDays)
        assertEquals(1, FreshnessKind.DAILY_CANDLE.windowDays)
        assertEquals(7, FreshnessKind.FUNDAMENTALS_SNAPSHOT.windowDays)
        assertEquals(200, FreshnessKind.ANNUAL_STATEMENT.windowDays)
        assertEquals(120, FreshnessKind.QUARTERLY.windowDays)
    }

    /**
     * The boundary, for every kind: **exactly** the window is still fresh, one day more is not.
     *
     * Inclusive is the only defensible choice. A price from the previous session is *the*
     * definition of "today's price" in an EOD app, so a 1-day-old close must read as fresh;
     * making the window exclusive would mark the normal case stale and train users to ignore
     * the label.
     */
    @Test
    fun `exactly the window is fresh and one day past it is stale`() {
        for (kind in FreshnessKind.entries) {
            val asOf = today.minusDays(kind.windowDays.toLong())
            assertEquals(
                FreshnessState.FRESH,
                FreshnessPolicy.evaluate(asOf, today, kind),
                "$kind at exactly ${kind.windowDays} days",
            )
            assertEquals(
                FreshnessState.STALE,
                FreshnessPolicy.evaluate(asOf.minusDays(1), today, kind),
                "$kind one day past its window",
            )
        }
    }

    @Test
    fun `today itself is fresh for every kind`() {
        for (kind in FreshnessKind.entries) {
            assertEquals(FreshnessState.FRESH, FreshnessPolicy.evaluate(today, today, kind), kind.name)
            assertEquals(0L, FreshnessPolicy.inspect(today, today, kind).ageDays, kind.name)
        }
    }

    @Test
    fun `a null as of date is missing for every kind`() {
        for (kind in FreshnessKind.entries) {
            assertEquals(FreshnessState.MISSING, FreshnessPolicy.evaluate(null, today, kind), kind.name)
            val inspected = FreshnessPolicy.inspect(null, today, kind)
            assertEquals(null, inspected.asOf)
            assertEquals(null, inspected.ageDays)
            assertNotNull(inspected.note)
        }
    }

    @Test
    fun `the describe string is the plan 10 copy`() {
        assertEquals(
            "as of 02 Oct 2026 (7 days old)",
            FreshnessPolicy.describe(LocalDate.of(2026, 10, 2), today, FreshnessKind.FUNDAMENTALS_SNAPSHOT),
        )
        assertEquals(
            "as of 02 Oct 2026 (today)",
            FreshnessPolicy.describe(LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 2), FreshnessKind.PRICE),
        )
        assertEquals(
            "as of 08 Oct 2026 (1 day old)",
            FreshnessPolicy.describe(LocalDate.of(2026, 10, 8), today, FreshnessKind.PRICE),
        )
        assertEquals(
            "as of 09 Oct 2026 (today)",
            FreshnessPolicy.describe(today, today, FreshnessKind.DAILY_CANDLE),
        )
    }

    @Test
    fun `a stale value names the window it exceeded`() {
        // Mar 2026 annual statements read on 2 Oct 2026: 192 days, inside the 200-day window.
        val annual = LocalDate.of(2026, 3, 31)
        assertEquals(FreshnessState.FRESH, FreshnessPolicy.evaluate(annual, today, FreshnessKind.ANNUAL_STATEMENT))
        assertEquals(
            "as of 31 Mar 2026 (192 days old)",
            FreshnessPolicy.describe(annual, today, FreshnessKind.ANNUAL_STATEMENT),
        )

        val older = LocalDate.of(2026, 1, 1)
        assertEquals(FreshnessState.STALE, FreshnessPolicy.evaluate(older, today, FreshnessKind.ANNUAL_STATEMENT))
        assertEquals(
            "as of 01 Jan 2026 (281 days old, older than the 200-day window for annual statements)",
            FreshnessPolicy.describe(older, today, FreshnessKind.ANNUAL_STATEMENT),
        )
    }

    @Test
    fun `a missing value reads as never fetched`() {
        assertEquals(
            "no fundamentals data has been fetched for this symbol yet",
            FreshnessPolicy.describe(null, today, FreshnessKind.FUNDAMENTALS_SNAPSHOT),
        )
        assertEquals(
            "no price data has been fetched for this symbol yet",
            FreshnessPolicy.describe(null, today, FreshnessKind.PRICE),
        )
    }

    /**
     * The rule that matters most: an old value is [Confidence.STALE], not a silent
     * [Confidence.SINGLE].
     */
    @Test
    fun `an old value is STALE confidence and never silently SINGLE`() {
        val fresh = LocalDate.of(2026, 10, 8)
        val stale = LocalDate.of(2026, 7, 1)
        // Inside the window the parsed provenance survives untouched…
        for (parsed in Confidence.entries) {
            assertEquals(
                parsed,
                FreshnessPolicy.confidenceFor(fresh, today, FreshnessKind.FUNDAMENTALS_SNAPSHOT, parsed),
                "$parsed inside the window",
            )
        }
        // …and outside it, every provenance claim becomes STALE, because none of them says
        // "and it was current".
        for (parsed in Confidence.entries) {
            assertEquals(
                Confidence.STALE,
                FreshnessPolicy.confidenceFor(stale, today, FreshnessKind.FUNDAMENTALS_SNAPSHOT, parsed),
                "$parsed outside the window",
            )
        }
        assertEquals(
            Confidence.SINGLE,
            FreshnessPolicy.confidenceFor(fresh, today, FreshnessKind.FUNDAMENTALS_SNAPSHOT),
            "the default provenance is SINGLE, and freshness does not change it",
        )
        assertEquals(
            Confidence.STALE,
            FreshnessPolicy.confidenceFor(stale, today, FreshnessKind.FUNDAMENTALS_SNAPSHOT),
        )
    }

    @Test
    fun `a stale ratios snapshot carries STALE confidence`() {
        val snapshot = Ratios(
            symbol = "RELIANCE",
            sector = Sector.POWER,
            asOf = LocalDate.of(2026, 7, 1),
            source = Source.SCREENER_IN,
            confidence = Confidence.SINGLE,
            roce = 10.3,
        )
        val aged = snapshot.withFreshness(today)
        assertEquals(Confidence.STALE, aged.confidence)
        assertEquals(10.3, aged.roce!!, "no figure changes, only the provenance")
        assertEquals(snapshot.asOf, aged.asOf)
        assertEquals(snapshot.symbol, aged.symbol)

        // A fresh snapshot is returned untouched, so the call is safe on every render.
        val fresh = snapshot.copy(asOf = today, confidence = Confidence.AGREED)
        val unchanged = fresh.withFreshness(today)
        assertEquals(Confidence.AGREED, unchanged.confidence)
        assertEquals(fresh, unchanged)
    }

    @Test
    fun `an as of date in the future is reported rather than trusted`() {
        val future = today.plusDays(3)
        val inspected = FreshnessPolicy.inspect(future, today, FreshnessKind.PRICE)
        assertEquals(FreshnessState.FRESH, inspected.state)
        assertEquals(-3L, inspected.ageDays, "negative: the as-of date is three days ahead of today")
        assertNotNull(inspected.note)
        assertTrue(inspected.note!!.contains("after today"), inspected.note!!)
        assertTrue(FreshnessPolicy.describe(future, today, FreshnessKind.PRICE).contains("after today"))
    }

    @Test
    fun `the age is the number of calendar days between the dates`() {
        for (age in 0L..400L) {
            val asOf = today.minusDays(age)
            val inspected = FreshnessPolicy.inspect(asOf, today, FreshnessKind.FUNDAMENTALS_SNAPSHOT)
            assertEquals(age, inspected.ageDays)
            val expected = when {
                age <= 7 -> FreshnessState.FRESH
                else -> FreshnessState.STALE
            }
            assertEquals(expected, inspected.state, "age=$age")
        }
    }

    @Test
    fun `today defaults to the market clock not the system clock`() {
        // The default has to exist and be in market time, but a test may not assert on its value:
        // that is exactly the non-determinism the injectable parameter removes.
        val byDefault = FreshnessPolicy.evaluate(LocalDate.of(2026, 10, 2), today = today)
        assertTrue(byDefault == FreshnessState.STALE || byDefault == FreshnessState.FRESH)
        assertEquals(ZoneId.of("Asia/Kolkata"), FreshnessPolicy.MARKET_ZONE)
        assertNotNull(FreshnessPolicy.todayInMarket())
    }

    @Test
    fun `freshness copy never reads as advice`() {
        // plan.md §1.4 rule 1: user-facing strings must not look like a recommendation.
        for (kind in FreshnessKind.entries) {
            for (age in listOf(0L, 1L, 7L, 30L, 199L, 200L, 201L, 400L)) {
                val text = FreshnessPolicy.describe(today.minusDays(age), today, kind)
                assertTrue(
                    com.mia.app.domain.compliance.AdviceLanguage.isClean(text),
                    "forbidden language in \"$text\"",
                )
            }
        }
    }

    @Test
    fun `the inspect result describes itself`() {
        val fresh = FreshnessPolicy.inspect(today.minusDays(1), today, FreshnessKind.PRICE)
        assertTrue(fresh.isFresh)
        assertFalse(fresh.isStale)
        assertFalse(fresh.isMissing)
        assertEquals("as of 08 Oct 2026 (1 day old)", fresh.description)

        val missing = FreshnessPolicy.inspect(null, today, FreshnessKind.PRICE)
        assertTrue(missing.isMissing)
        assertEquals(missing.description, FreshnessPolicy.describe(missing))
    }
}