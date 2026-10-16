package com.mia.app.ui.detail

import com.mia.app.data.android.repo.CandleRepository
import com.mia.app.data.android.repo.SymbolRepository
import com.mia.app.data.symbols.AssetReader
import com.mia.app.data.symbols.IndexCohort
import com.mia.app.data.symbols.IndexCohortParser
import com.mia.app.data.symbols.IndexCohortTable
import com.mia.app.domain.model.Timeframe
import com.mia.app.domain.technical.Liquidity
import com.mia.app.domain.technical.LiquidityProfile
import com.mia.app.ui.di.orNullWhenNothingStored
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which NSE index a stock belongs to, in the words the chip shows.
 *
 * ### Why the cohort is a *fact* and not an inference
 * The bundled symbol master has no market-cap column, so "large cap" could not be derived from
 * anything on the device — which is why `IndexCohort` exists: NSE publishes the membership, and a
 * published list is a claim a reader can check against a dated file rather than a proxy this app
 * invented.
 *
 * @property asOf the vintage of the bundled constituent file. Shown beside the name because NSE
 *   revises membership quarterly: "NIFTY MIDCAP 150" without a date is a claim about whichever
 *   revision happened to be on disk when the APK was built.
 */
data class CapBand(
    val name: String,
    val asOf: LocalDate,
) {
    /**
     * The one neutral sentence that may accompany the name.
     *
     * States a documented property of index drawdowns and nothing about this stock. It is
     * deliberately not advice: it says what the *cohort* has done historically, names no
     * direction for this symbol, and gives no instruction. [IndexCohortNotes.NEUTRAL_DRAWDOWN_NOTE].
     */
    val note: String = IndexCohortNotes.NEUTRAL_DRAWDOWN_NOTE

    /** What the chip reads: `NIFTY MIDCAP 150 · as of 2026-10-04`. */
    fun chipLabel(): String = "$name · as of $asOf"

    /** What TalkBack reads, including the note the chip itself does not have room for. */
    fun spokenLabel(): String = "$name, constituent list as of $asOf. $note"
}

/**
 * The wording the cap-band chip is allowed to use.
 *
 * ### Why these strings are constants rather than literals at the call site
 * SEBI posture (plan.md §1.4) is machine-checked by `AdviceLanguage` against *every* user-facing
 * literal in this repository. Naming the copy once, here, next to the type that decides whether to
 * show it, means a compliance scan has a single list to read and a change to the wording is a
 * reviewed diff rather than an edit scattered across composables.
 */
object IndexCohortNotes {

    /**
     * The neutral, historical note that may sit under a mid- or small-cap name.
     *
     * It states what the indices have done over past cycles and says nothing about this stock,
     * this reader, or what to do. "Historically deeper" is a measurement about an index; "riskier"
     * would be a verdict about a security, and verdicts are what plan.md §1.4 forbids.
     */
    const val NEUTRAL_DRAWDOWN_NOTE: String =
        "Mid- and small-cap indices have historically fallen further than large-cap indices in " +
            "broad market declines. That is a record of past index moves, not a forecast for this " +
            "stock."

    /**
     * Shown when no cohort's constituent list contains the symbol.
     *
     * The honest form of "not in any of NSE's three published lists". Naming the lists keeps the
     * statement checkable, and it is *not* the same claim as "this is a small cap" — a company
     * outside all three is simply outside all three.
     *
     * The vintage is interpolated from [IndexCohort.BUNDLED_AS_OF] rather than typed in, so a
     * refreshed bundle cannot leave a hard-coded date claiming to be the file's.
     */
    val NOT_IN_ANY_INDEX: String =
        "Not a constituent of ${IndexCohort.NIFTY_50.label}, " +
            "${IndexCohort.MIDCAP_150.label} or ${IndexCohort.SMALLCAP_250.label} as of " +
            "${IndexCohort.BUNDLED_AS_OF}."
}

/**
 * Everything the header's market-context row needs for one symbol: the cap band and the liquidity
 * measurement.
 *
 * ### Why this is a value and not two function calls
 * The header shows them side by side and both are facts about the *same* symbol read from the
 * *same* stored state. Reading them independently could put a cohort label from one date beside a
 * turnover median from another, and a reader has no way to tell. One load, one consistent
 * snapshot.
 *
 * @property capBand `null` when the symbol is in none of the published lists, or when the lists
 *   could not be read. The two cases are told apart by [capBandUnavailableReason], because "this
 *   company is not an NIFTY constituent" and "this build is missing the constituent files" are
 *   very different sentences and only one of them is a fact about the company.
 * @property liquidity the measured turnover profile. Its own [LiquidityProfile.notice] is `null`
 *   when the median is healthy, so the header stays quiet for an ordinary large cap.
 */
data class StockContext(
    val symbol: String,
    val capBand: CapBand? = null,
    val capBandUnavailableReason: String? = null,
    val liquidity: LiquidityProfile? = null,
    /** NSE's series code for this symbol, or `null` when the master has no row. */
    val series: String? = null,
) {
    /** The sentence the liquidity line renders, or `null` when there is nothing to say. */
    val liquidityNotice: String? get() = liquidity?.notice()

    /**
     * True when the SME gate **must** be applied to this symbol, whatever the measurement says.
     *
     * planv2 W3: SME (`SM` series) instruments always get the check. A reader who can see the
     * median for an SME is never asked to go looking for it.
     */
    val isSme: Boolean get() = Liquidity.isSmeSeries(series)

    /** True when there is nothing at all to draw, so the row is skipped entirely. */
    val isEmpty: Boolean get() = capBand == null && liquidityNotice == null
}

/**
 * Reads the market context for one symbol: its published index membership and its traded value.
 *
 * ### Why the constituent lists are read once per process
 * The three CSVs are bundled (451 rows in total, ~40 KB) and change only with an app update, so
 * a per-symbol read would re-parse the same bytes every time a stock is opened. The cache is
 * guarded by a [Mutex] for the same anti-stampede reason `YahooCrumbHandshake` uses: two coroutines
 * asking at once must not both read three files off the APK's asset path.
 *
 * ### A missing asset is a stated reason, not a crash
 * `ContextAssetReader` returns `null` for an asset that is not in this build, and
 * [IndexCohortParser] turns that into a failed table with the reason on its health. That is why a
 * stripped debug build shows "constituent lists could not be read: …" instead of a stock header
 * that claims a company is not in any index when the truth is that the app cannot tell.
 */
@Singleton
class RealStockContextSource @Inject constructor(
    private val assetReader: AssetReader,
    private val symbolRepository: SymbolRepository,
    private val candleRepository: CandleRepository,
    @DetailBackgroundDispatcher private val backgroundDispatcher: CoroutineDispatcher,
) {

    private val cohortLock = Mutex()

    @Volatile
    private var cachedCohorts: List<IndexCohortTable>? = null

    /**
     * The context for [symbol], or an explained absence.
     *
     * Never throws. Both halves degrade independently — a symbol can have a cap band and no
     * liquidity measurement (no stored bars yet), or the reverse (not in any index, but a full
     * series) — because they come from unrelated stores and one failing says nothing about the
     * other.
     */
    suspend fun context(symbol: String): StockContext = withContext(backgroundDispatcher) {
        val ticker = symbol.trim().uppercase()
        val tables = cohortTables()
        val band = tables.firstOrNull { it.contains(ticker) }

        val identity = runCatching { symbolRepository.record(ticker) }.getOrNull()?.valueOrNull()
        val series = identity?.series

        // `SM` series always get the check; anything else gets it too, because a thin-traded
        // `EQ` name is exactly the case where the median is worth stating.
        val bars = runCatching { candleRepository.storedSeries(ticker, Timeframe.D1) }
            .getOrNull()
            ?.orNullWhenNothingStored()
            ?.ordered()
            .orEmpty()

        StockContext(
            symbol = ticker,
            capBand = band?.let { CapBand(name = it.cohort.label, asOf = it.asOf) },
            capBandUnavailableReason = when {
                band != null -> null
                tables.isEmpty() -> INDEX_LISTS_UNAVAILABLE
                tables.any { !it.health.ok } -> INDEX_LISTS_UNAVAILABLE
                else -> IndexCohortNotes.NOT_IN_ANY_INDEX
            },
            liquidity = if (bars.isEmpty()) null else Liquidity.of(bars),
            series = series,
        )
    }

    /**
     * The three bundled constituent tables, parsed once per process.
     *
     * Returns an empty list when no list could be read **or** when every list failed its health
     * check, which is what keeps a truncated or wrong-shaped asset from looking like an index with
     * no constituents — the exact failure `IndexCohortTable.isComplete` exists to catch.
     */
    private suspend fun cohortTables(): List<IndexCohortTable> {
        cachedCohorts?.let { return it }
        return cohortLock.withLock {
            cachedCohorts?.let { return@withLock it }
            val tables = IndexCohort.entries.mapNotNull { cohort ->
                val stream = runCatching { assetReader.open(assetNameFor(cohort)) }.getOrNull()
                val table = IndexCohortParser.parse(cohort, stream)
                if (table.health.ok) table else null
            }
            cachedCohorts = tables
            tables
        }
    }

    private companion object {
        /**
         * Shown when no constituent list could be read.
         *
         * Phrased as a fact about the build, never as "this stock is not in an index" — the two
         * would look identical on screen while meaning opposite things.
         */
        const val INDEX_LISTS_UNAVAILABLE: String =
            "NSE's published index constituent lists could not be read on this device, so the " +
                "index membership of this stock is not shown."

        /**
         * `assets/indices/nifty50.csv` as bundled → `indices/nifty50.csv` on Android.
         *
         * [IndexCohort.DIRECTORY] is a **classpath** prefix, which is how the `:data` JVM tests
         * load these files. On Android `app/build.gradle.kts` adds `data/src/main/resources` as an
         * assets srcDir, so the same bytes sit at `indices/…` in the APK's asset tree and the
         * leading `assets/` must come off.
         */
        fun assetNameFor(cohort: IndexCohort): String =
            IndexCohort.DIRECTORY.removePrefix("assets/") + cohort.assetName
    }
}

