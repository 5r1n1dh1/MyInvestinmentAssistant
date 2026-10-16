package com.mia.app.data.android.repo

import com.mia.app.data.android.db.IndexCandleEntity
import com.mia.app.data.android.db.IndexDao
import com.mia.app.data.android.di.IoDispatcher
import com.mia.app.data.price.IndexFailureReason
import com.mia.app.data.price.IndexSource
import com.mia.app.data.price.IndexSymbol
import com.mia.app.domain.model.ParserHealth
import com.mia.app.domain.model.Source
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The benchmark index, stored locally and read by every scan.
 *
 * ### What this class does that a caller could not
 * Exactly two things: turn an [IndexSeries] into rows, and turn rows back into an
 * [IndexBenchmark]. Everything else — which index, which range, which host, what a short series
 * means — is decided by `:data` and comes back as a typed reason. A caller that wanted a
 * benchmark could not have built one out of anything else on the device, which is deliberate:
 * the failure mode this workstream exists to remove is a benchmark that is *absent*, and the one
 * that would replace it worst is a benchmark that is *invented*.
 *
 * ### `null` is a real, tested answer
 * With nothing stored, [benchmark] returns `DataResult.unavailable` and every caller passes `null`
 * to `PatternScanOptions.benchmarkCandles`. That is not a degraded mode — it is the engine's
 * documented behaviour, and it produces the note "No index benchmark — relative strength
 * unscored" (`PatternEngine.dataQualityNotes`). [benchmarkCandlesOrNull] is the single function
 * that turns storage state into that value, so the honest path cannot be skipped by accident and
 * cannot be replaced by an empty list.
 */
@Singleton
class DefaultIndexRepository @Inject constructor(
    private val indexDao: IndexDao,
    private val indexSource: IndexSource,
    @IoDispatcher private val dispatcher: CoroutineDispatcher,
) : IndexRepository {

    /**
     * The stored benchmark, or the typed reason there is none.
     *
     * The reason text matters: a reader whose Context factor shows no relative strength has to be
     * able to tell "the app has no benchmark and says so" from "the app is broken". The sentence
     * names the fix — the 19:00 IST refresh — so the state is actionable rather than merely
     * explained.
     */
    override suspend fun benchmark(): DataResult<IndexBenchmark> = withContext(dispatcher) {
        val symbol = IndexSymbol.DEFAULT
        val rows = indexDao.all(symbol.wire)
        if (rows.isEmpty()) {
            return@withContext DataResult.unavailable(
                failure = DataFailure(
                    reason = DataFailureReason.NOT_ATTEMPTED,
                    message = "No index benchmark is stored on this device, so relative strength " +
                        "against ${symbol.label} is left unscored rather than estimated. The 19:00 " +
                        "IST refresh fetches it once a day; Settings → Refresh history forces it now.",
                    detail = "symbol=${symbol.wire} rows=0 table=index_candles",
                ),
                source = Source.YAHOO,
                fetchedOn = todayInMarket(),
            )
        }

        val candles = rows.map { it.toCandle() }.sortedBy { it.date }
        DataResult.ok(
            value = IndexBenchmark(
                wire = symbol.wire,
                label = symbol.label,
                candles = candles,
                asOf = candles.last().date,
            ),
            source = Source.YAHOO,
            fetchedOn = todayInMarket(),
            asOf = candles.last().date,
            notes = listOf(
                "${candles.size} stored ${symbol.label} session(s) as of ${candles.last().date}; " +
                    "dates are read in the exchange timezone so they align with the NSE bhavcopy",
            ),
        )
    }

    /**
     * `benchmarkCandles` for the engine, or `null` when the device holds no benchmark.
     *
     * The only sanctioned way to reach the engine's `benchmarkCandles`, and the reason the honest
     * path survives: `null` means "no benchmark — unscored", while an **empty list** would reach
     * the same code path with a different meaning (a benchmark exists but shares no dates) and
     * would therefore unscore the factor with no stated reason at all.
     */
    suspend fun benchmarkCandlesOrNull(): List<com.mia.app.domain.model.Candle>? =
        runCatching { benchmark() }.getOrNull()?.valueOrNull()?.asBenchmarkCandles()

    /**
     * Fetch and store the newest index session(s). Never throws.
     *
     * Idempotent by the write-once rule: a re-run re-parses, matches every existing row and
     * writes nothing, which is why `EodRefreshWorker` can call this on every run without checking
     * first.
     */
    override suspend fun refresh(): DataResult<IndexRefresh> = withContext(dispatcher) {
        val symbol = IndexSymbol.DEFAULT
        val series = try {
            indexSource.daily(symbol)
        } catch (e: Exception) {
            // `:data` documents that its sources never throw. This is belt and braces so that an
            // unexpected runtime failure in a worker still produces a recorded reason rather than
            // a crash dialog at 19:00.
            return@withContext failed(symbol, IndexFailureReason.HOST_UNREACHABLE, e.toString())
        }
        if (!series.isUsable) {
            return@withContext failed(
                symbol,
                series.failure ?: IndexFailureReason.UNPARSEABLE,
                series.unavailableReason ?: "the index source returned no series",
                health = series.health,
            )
        }

        val rows = series.candles.map { candle ->
            IndexCandleEntity(
                symbol = candle.symbol,
                date = candle.date.toString(),
                open = candle.open,
                high = candle.high,
                low = candle.low,
                close = candle.close,
                volume = candle.volume,
                source = candle.source.name,
            )
        }
        val inserted = indexDao.insertIgnore(rows)
        val written = inserted.count { it != -1L }
        val asOf = indexDao.latestDate(symbol.wire)

        val refresh = IndexRefresh(
            wire = symbol.wire,
            barsWritten = written,
            barsAlreadyKnown = rows.size - written,
            asOf = asOf?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
            sessionsStored = indexDao.count(symbol.wire),
        )
        DataResult.ok(
            value = refresh,
            source = Source.YAHOO,
            fetchedOn = todayInMarket(),
            asOf = refresh.asOf,
            health = series.health,
            notes = listOf(
                "${symbol.label} (${symbol.wire}) fetched over ${series.range}/" +
                    "${series.interval} after ${series.attempts} request(s)",
            ),
        )
    }

    private fun failed(
        symbol: IndexSymbol,
        reason: IndexFailureReason,
        detail: String,
        health: ParserHealth? = null,
    ): DataResult<IndexRefresh> = DataResult.unavailable(
        failure = DataFailure(
            reason = reason.toDataFailureReason(),
            message = "The ${symbol.label} benchmark could not be refreshed, so relative " +
                "strength stays unscored on this device: $detail",
            detail = "symbol=${symbol.wire} reason=${reason.name}",
        ),
        source = Source.YAHOO,
        fetchedOn = todayInMarket(),
        health = health,
    )
}

/**
 * Maps the index layer's failure vocabulary onto this module's.
 *
 * The two are deliberately parallel: the reason a source failed and the reason a *reader* cannot
 * see a figure are the same question, and flattening one into a generic [DataFailureReason.UNEXPECTED]
 * would throw away the distinction between "try again tomorrow" and "this device has never had a
 * benchmark".
 */
internal fun IndexFailureReason.toDataFailureReason(): DataFailureReason = when (this) {
    IndexFailureReason.NOT_FOUND -> DataFailureReason.NO_ROWS_FOR_SYMBOL
    IndexFailureReason.UNPARSEABLE -> DataFailureReason.UNPARSEABLE
    IndexFailureReason.RATE_LIMITED -> DataFailureReason.SOURCE_UNAVAILABLE
    IndexFailureReason.HOST_UNREACHABLE -> DataFailureReason.SOURCE_UNAVAILABLE
    IndexFailureReason.HANDSHAKE_FAILED -> DataFailureReason.SOURCE_UNAVAILABLE
    IndexFailureReason.NO_DATA -> DataFailureReason.NO_ROWS_FOR_SYMBOL
    IndexFailureReason.EMPTY_SERIES -> DataFailureReason.UNPARSEABLE
    IndexFailureReason.NO_BENCHMARK_ROWS -> DataFailureReason.NOT_ENOUGH_HISTORY
    IndexFailureReason.UNKNOWN_SYMBOL -> DataFailureReason.NOTHING_TO_DO
    IndexFailureReason.UNSUPPORTED_REQUEST -> DataFailureReason.NOTHING_TO_DO
}

