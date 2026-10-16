package com.mia.app.data.android.repo

import com.mia.app.domain.model.Candle
import java.time.LocalDate

/**
 * One stored index series, shaped for the engine and for the screen that names it.
 *
 * ### Why it carries strings rather than a `:data` type
 * docs/CONTRACTS.md: `:app` must not import `:data`. `:data` publishes `IndexSymbol`, but this
 * value is what crosses into the UI, so it names the index by the two things a reader needs — the
 * published label ("NIFTY 50") and the wire symbol used in diagnostics (`^NSEI`) — and passes the
 * series as plain `:domain` [Candle]s. The app can render everything it needs without knowing
 * which module the source lives in, which is what keeps a future NSE-hosted index file a change
 * in exactly one place.
 *
 * @property candles ascending by date. The engine aligns the benchmark to the stock **by date**
 *   (`relativeStrength`), so a series in any other order would produce an empty intersection and
 *   an unscored Context factor with nothing to show for it.
 */
data class IndexBenchmark(
    /** Yahoo's wire symbol, e.g. `^NSEI`. Diagnostics, and what identifies the stored series. */
    val wire: String,
    /** The published index name, e.g. `NIFTY 50`. What the UI shows. */
    val label: String,
    val candles: List<Candle>,
    /** Newest stored session. The "as of" the UI must show (plan.md §1.4 rule 3). */
    val asOf: LocalDate?,
) {
    val isEmpty: Boolean get() = candles.isEmpty()

    val barCount: Int get() = candles.size

    /**
     * The exact value to hand to `PatternScanOptions.benchmarkCandles`.
     *
     * `null` when there is nothing stored, which is the engine's **existing** honest path: it
     * unscores the relative-strength measure and records "No index benchmark — relative strength
     * unscored" in its notes. This function exists so that a caller cannot turn an absent
     * benchmark into an empty list, which the engine would treat as a benchmark with no
     * overlapping dates and therefore as a silent miss rather than a stated absence.
     */
    fun asBenchmarkCandles(): List<Candle>? = candles.ifEmpty { null }
}

/**
 * What one index refresh achieved, reported through `work_state` like every other fetch.
 *
 * @property barsWritten sessions newly stored; sessions already cached were left alone by the
 *   write-once rule and are counted in [barsAlreadyKnown].
 * @property failure the typed reason nothing was stored. **Non-null on a failed run**, so
 *   "refreshed" can never be reported over an empty table.
 */
data class IndexRefresh(
    /** Which index was fetched, by wire symbol. */
    val wire: String,
    /** Sessions newly written to `index_candles`. */
    val barsWritten: Int,
    /** Sessions that were already cached and therefore not revised. */
    val barsAlreadyKnown: Int,
    /** Newest session stored after this run. */
    val asOf: LocalDate?,
    /** Total sessions now stored for this index. */
    val sessionsStored: Int = 0,
    /** The reason nothing was stored, or `null` on success. */
    val failure: DataFailure? = null,
) {
    val isOk: Boolean get() = failure == null

    /** The one-line summary `work_state` and the logs share. */
    fun summary(): String = buildString {
        append(if (isOk) "Refreshed index $wire" else "Index $wire not refreshed")
        append("; $barsWritten session(s) written, $barsAlreadyKnown already cached")
        asOf?.let { append("; as of ").append(it) }
        failure?.let { append("; ").append(it.message) }
    }
}

/**
 * The app's index benchmark: one stored series, fetched once a day.
 *
 * ### Why this interface is two functions wide
 * The engine needs exactly one thing — a list of benchmark candles or `null` — and the EOD worker
 * needs exactly one other thing — a daily refresh. Everything else (which symbol, which range,
 * which host) is decided inside `:android-data`, so a future change to any of those cannot reach
 * a call site. That is the narrow-interface discipline `PriceSource` documents for the price layer
 * (plan.md §3.7), applied to the index.
 *
 * ### The absence is a first-class answer
 * [benchmark] returns `DataResult.unavailable` when nothing is stored, never a `DataResult.ok`
 * with an empty series. The distinction is the whole point: "no benchmark" is the engine's
 * documented unscored state and must read on screen as exactly that, whereas an empty-but-ok
 * result would be read as "the benchmark was loaded and did not overlap".
 */
interface IndexRepository {

    /**
     * The stored benchmark, or the typed reason there is none.
     *
     * Read-only and offline: it never performs IO beyond a local table read, so a scan can call it
     * on every keystroke without spending a request (plan.md §13).
     */
    suspend fun benchmark(): DataResult<IndexBenchmark>

    /**
     * Fetch the newest index session(s) and store them.
     *
     * Called once per day by `EodRefreshWorker`. Never throws: a failure comes back as a
     * [DataRefresh] with a [DataFailure], and the caller decides whether that matters — for the
     * EOD worker it must not, because the equity price layer succeeded independently and a
     * benchmark is an improvement rather than a prerequisite.
     */
    suspend fun refresh(): DataResult<IndexRefresh>
}