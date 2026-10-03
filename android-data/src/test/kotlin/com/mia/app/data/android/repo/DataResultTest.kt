package com.mia.app.data.android.repo

import com.mia.app.domain.model.ParserHealth
import com.mia.app.domain.model.Source
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The invariant that makes plan.md §12 structural rather than aspirational.
 *
 * > "A parser must return a health flag, not an empty list. Silent-empty is indistinguishable
 * > from 'no red flags' — the most dangerous possible failure."
 *
 * The way this is enforced here is that [DataResult] **cannot be constructed** in the one state
 * that would produce a silent empty: a null value with no failure. The test suite below is a
 * specification of that, and it is a plain JVM test with no Android runtime.
 */
class DataResultTest {

    private val today: LocalDate = LocalDate.parse("2026-10-01")

    @Test
    fun `a result carrying a value may not also carry a failure`() {
        assertFailsWith<IllegalArgumentException> {
            DataResult<String>(
                value = "RELIANCE",
                provenance = Provenance(Source.NSE_BHAVCOPY, today, today),
                failure = DataFailure(DataFailureReason.NOT_PUBLISHED, "not published yet"),
            )
        }
    }

    @Test
    fun `a result with neither a value nor a failure cannot be constructed`() {
        // This is the silent-empty state. It is a compile-time-checkable runtime invariant.
        assertFailsWith<IllegalArgumentException> {
            DataResult<String>(
                value = null,
                provenance = Provenance(Source.NSE_BHAVCOPY, null, today),
                failure = null,
            )
        }
    }

    @Test
    fun `a failure must explain itself`() {
        assertFailsWith<IllegalArgumentException> {
            DataFailure(DataFailureReason.SOURCE_UNAVAILABLE, "   ")
        }
    }

    @Test
    fun `an ok result reports its provenance`() {
        val result = DataResult.ok(
            value = listOf(1, 2, 3),
            source = Source.NSE_BHAVCOPY,
            fetchedOn = today,
            asOf = today,
            health = ParserHealth.healthy("candles"),
            notes = listOf("3 bar(s)"),
        )

        assertTrue(result.isOk)
        assertFalse(result.isDegraded)
        assertEquals(Source.NSE_BHAVCOPY, result.source)
        assertEquals(today, result.asOf)
        assertEquals(listOf("3 bar(s)"), result.provenance.notes)
        assertEquals(3, result.orThrow().size)
    }

    @Test
    fun `a result whose parser health is not ok is degraded, not failed`() {
        // The data is real but incomplete — plan.md §8.3's "Not evaluated" case. It must render
        // differently from a failure *and* differently from a clean success.
        val result = DataResult.ok(
            value = emptyList<String>(),
            source = Source.SCREENER_IN,
            fetchedOn = today,
            asOf = today,
            health = ParserHealth.partial(found = listOf("top-ratios"), missing = listOf("cash-flow"), note = "truncated page"),
        )

        assertTrue(result.isOk)
        assertTrue(result.isDegraded)
        assertFalse(result.provenance.isHealthy)
    }

    @Test
    fun `an unavailable result carries no value and the reason`() {
        val result = DataResult.unavailable<String>(
            failure = DataFailure(DataFailureReason.NOT_PUBLISHED, "NSE has not published it yet"),
            source = Source.NSE_BHAVCOPY,
            fetchedOn = today,
        )

        assertFalse(result.isOk)
        assertNull(result.valueOrNull())
        assertEquals(DataFailureReason.NOT_PUBLISHED, result.failure?.reason)
    }

    @Test
    fun `orThrow turns an unavailable result into a typed exception`() {
        val result = DataResult.unavailable<String>(
            failure = DataFailure(DataFailureReason.NO_DATA_CACHED, "nothing cached"),
            fetchedOn = today,
        )

        val thrown = assertFailsWith<DataUnavailableException> { result.orThrow() }
        assertEquals(DataFailureReason.NO_DATA_CACHED, thrown.failure.reason)
    }

    @Test
    fun `an empty list can be a legitimate answer when the provenance explains it`() {
        // "No red flags in the data checked" is a fact. `emptyIsFailure` exists for the cases
        // where it is not, and both routes are available.
        val honestEmpty = DataResult.ok(
            value = emptyList<String>(),
            source = Source.DERIVED,
            fetchedOn = today,
            health = ParserHealth.healthy("red-flags"),
            notes = listOf("every rule ran and none tripped"),
        )

        assertTrue(honestEmpty.isOk)
        assertEquals(0, honestEmpty.orThrow().size)
    }

    @Test
    fun `emptyIsFailure produces the typed failure instead`() {
        val suspiciousEmpty = DataResult.emptyIsFailure<List<String>>(
            emptyMessage = "The cash-flow statement could not be read, so the flags that need it " +
                "were not evaluated",
            reason = DataFailureReason.UNPARSEABLE,
            source = Source.SCREENER_IN,
            fetchedOn = today,
        )

        assertFalse(suspiciousEmpty.isOk)
        assertEquals(DataFailureReason.UNPARSEABLE, suspiciousEmpty.failure?.reason)
    }

    @Test
    fun `today in market time is not earlier than the UTC date it is derived from`() {
        // IST is UTC+05:30, so for any instant the IST date is either the UTC date or the next
        // one. That is what stops the same fetch producing two different "as of" dates.
        val todayInMarket = todayInMarket()
        val utcToday = LocalDate.now(java.time.ZoneOffset.UTC)

        assertTrue(
            todayInMarket == utcToday || todayInMarket == utcToday.plusDays(1),
            "IST date $todayInMarket vs UTC date $utcToday",
        )
    }
}