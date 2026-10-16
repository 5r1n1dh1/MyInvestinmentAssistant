package com.mia.app.data.android.repo

import com.mia.app.data.android.db.IndexCandleEntity
import com.mia.app.data.android.db.IndexDao
import com.mia.app.data.price.IndexFailureReason
import com.mia.app.data.price.IndexSeries
import com.mia.app.data.price.IndexSource
import com.mia.app.data.price.IndexSymbol
import com.mia.app.domain.model.Candle
import com.mia.app.domain.model.ParserHealth
import com.mia.app.domain.model.Source
import com.mia.app.domain.model.Timeframe
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [DefaultIndexRepository] against a fake DAO and a fake source.
 *
 * ### What is worth testing here, given `:data` is tested on its own
 * The wiring, and above all **the absence**. `benchmarkCandles = null` has been hardcoded at both
 * call sites since the app was written, and the engine's handling of it is already pinned by
 * `PatternEngineTest`. What was untested is the layer in between: whether this repository can
 * return something other than `null`, and whether the "nothing stored" answer is a stated absence
 * or a silent empty.
 *
 * The distinction the tests guard: `DataResult.unavailable` + `asBenchmarkCandles() == null` means
 * "no benchmark — relative strength unscored", which the engine states on screen. An `Ok` with an
 * empty series would reach the same code path while meaning "a benchmark exists and shares no
 * dates", and would unscore the factor with nothing to explain it.
 */
class DefaultIndexRepositoryTest {

    /** An in-memory stand-in for the Room DAO. Room's abstract DAOs subclass cleanly off-device. */
    private class FakeIndexDao : IndexDao() {
        val rows = mutableListOf<IndexCandleEntity>()

        override suspend fun insertIgnore(rows: List<IndexCandleEntity>): List<Long> =
            rows.map { row ->
                // The write-once rule the real DAO compiles to `INSERT OR IGNORE`.
                if (this.rows.any { it.symbol == row.symbol && it.date == row.date }) {
                    -1L
                } else {
                    this.rows += row
                    this.rows.size.toLong()
                }
            }

        override suspend fun all(symbol: String): List<IndexCandleEntity> =
            rows.filter { it.symbol == symbol }.sortedBy { it.date }

        override suspend fun count(symbol: String): Int = rows.count { it.symbol == symbol }

        override suspend fun latestDate(symbol: String): String? =
            rows.filter { it.symbol == symbol }.maxOfOrNull { it.date }

        override suspend fun symbols(): List<String> = rows.map { it.symbol }.distinct().sorted()
    }

    /** A source that answers whatever the test hands it. */
    private class FakeIndexSource(var response: IndexSeries) : IndexSource {
        var requested: String? = null

        override suspend fun daily(symbol: IndexSymbol, range: String): IndexSeries {
            requested = symbol.wire
            return response
        }
    }

    private fun bar(date: LocalDate, close: Double) = Candle(
        symbol = IndexSymbol.NIFTY_50.wire,
        timeframe = Timeframe.D1,
        date = date,
        open = close,
        high = close,
        low = close,
        close = close,
        volume = 0L,
        source = Source.YAHOO,
    )

    private fun series(closes: List<Double>) = IndexSeries(
        symbol = IndexSymbol.NIFTY_50,
        candles = closes.mapIndexed { i, c -> bar(LocalDate.parse("2026-01-01").plusDays(i.toLong()), c) },
        health = ParserHealth(ok = true, sectionsFound = listOf("meta"), notes = emptyList()),
        rowsRequested = closes.size,
    )

    /**
     * Takes the [IndexSource] *interface*, not [FakeIndexSource].
     *
     * It is tempting to narrow this to the fake, since almost every test wants one — but two tests
     * below deliberately hand in an anonymous `IndexSource` that throws or fails, and a helper
     * typed to the fake silently refuses them. The production constructor already takes the
     * interface, so the helper now matches it and the tests can say what they mean: "whatever the
     * source does, the repository turns it into a typed failure".
     */
    private fun TestScope.repository(dao: FakeIndexDao, source: IndexSource) =
        DefaultIndexRepository(dao, source, UnconfinedTestDispatcher(testScheduler))

    // --------------------------------------------------------------- the honest absence

    @Test
    fun `with nothing stored the repository says so rather than returning an empty series`() = runTest {
        val result = repository(FakeIndexDao(), FakeIndexSource(series(listOf(1.0)))).benchmark()

        assertFalse(result.isOk)
        val failure = assertNotNull(result.failure)
        assertEquals(DataFailureReason.NOT_ATTEMPTED, failure.reason)
        assertTrue(failure.message.contains("NIFTY 50"), failure.message)
        assertTrue(failure.message.contains("unscored"), failure.message)
        // "nothing stored" must never be reported as a healthy read of an empty benchmark.
        assertNull(result.valueOrNull())
    }

    @Test
    fun `the engine is handed null, not an empty list, when there is no benchmark`() = runTest {
        val stored = repository(FakeIndexDao(), FakeIndexSource(series(listOf(1.0)))).benchmark()

        // The single value the scan sites hand to `PatternScanOptions.benchmarkCandles`.
        assertNull(stored.valueOrNull()?.asBenchmarkCandles())
    }

    @Test
    fun `an empty stored benchmark also yields null rather than an empty candle list`() = runTest {
        val empty = IndexBenchmark(
            wire = "^NSEI",
            label = "NIFTY 50",
            candles = emptyList(),
            asOf = null,
        )

        assertNull(empty.asBenchmarkCandles())
        assertTrue(empty.isEmpty)
        assertEquals(0, empty.barCount)
    }

    // ------------------------------------------------------------------- the happy path

    @Test
    fun `stored sessions come back ascending with the index named`() = runTest {
        val dao = FakeIndexDao()
        val result = repository(dao, FakeIndexSource(series(listOf(100.0, 101.0, 102.0))))
            .refresh()
        assertTrue(result.isOk, result.failure?.message)

        val benchmark = assertNotNull(repository(dao, FakeIndexSource(series(emptyList()))).benchmark().valueOrNull())

        assertEquals("^NSEI", benchmark.wire)
        assertEquals("NIFTY 50", benchmark.label)
        assertEquals(3, benchmark.barCount)
        assertEquals(
            benchmark.candles.map { it.date },
            benchmark.candles.map { it.date }.sorted(),
            "relativeStrength aligns by date, so order is load-bearing",
        )
        assertEquals(LocalDate.parse("2026-01-03"), benchmark.asOf)
        assertNull(benchmark.candles.first().deliveryPercent, "an index publishes no delivery figure")
        assertEquals(3, benchmark.asBenchmarkCandles()?.size)
    }

    @Test
    fun `a refresh is idempotent because the table is write-once`() = runTest {
        val dao = FakeIndexDao()
        val source = FakeIndexSource(series(listOf(100.0, 101.0)))
        val repository = repository(dao, source)

        val first = assertNotNull(repository.refresh().valueOrNull())
        val second = assertNotNull(repository.refresh().valueOrNull())

        assertEquals(2, first.barsWritten)
        assertEquals(0, first.barsAlreadyKnown)
        // The second run re-parsed the same two sessions and revised nothing.
        assertEquals(0, second.barsWritten)
        assertEquals(2, second.barsAlreadyKnown)
        assertEquals(2, second.sessionsStored)
    }

    @Test
    fun `refresh asks for the default benchmark index`() = runTest {
        val source = FakeIndexSource(series(listOf(100.0)))

        repository(FakeIndexDao(), source).refresh()

        assertEquals("^NSEI", source.requested)
    }

    // ------------------------------------------------------------------ the failure paths

    @Test
    fun `a failed source refresh stores nothing and states the reason`() = runTest {
        val dao = FakeIndexDao()
        val source = FakeIndexSource(
            IndexSeries.failed(
                symbol = IndexSymbol.NIFTY_50,
                failure = IndexFailureReason.HANDSHAKE_FAILED,
                reason = "no crumb could be obtained",
            ),
        )

        val result = repository(dao, source).refresh()

        assertFalse(result.isOk)
        assertEquals(DataFailureReason.SOURCE_UNAVAILABLE, assertNotNull(result.failure).reason)
        assertTrue(dao.rows.isEmpty(), "a failed fetch must leave the cache untouched")
    }

    @Test
    fun `a thrown source becomes a typed failure, never an exception`() = runTest {
        val source = object : IndexSource {
            override suspend fun daily(symbol: IndexSymbol, range: String): IndexSeries =
                throw IllegalStateException("boom")
        }

        val result = repository(FakeIndexDao(), source).refresh()

        assertFalse(result.isOk)
        assertNotNull(result.failure)
        assertTrue(result.failure!!.message.contains("boom"), result.failure!!.message)
    }

    @Test
    fun `a successful refresh reports what it wrote`() = runTest {
        val result = repository(FakeIndexDao(), FakeIndexSource(series(listOf(100.0, 101.0))))
            .refresh()

        val summary = assertNotNull(result.valueOrNull()).summary()

        assertTrue(summary.contains("^NSEI"), summary)
        assertTrue(summary.contains("2 session(s) written"), summary)
        assertTrue(summary.contains("as of 2026-01-02"), summary)
    }
}