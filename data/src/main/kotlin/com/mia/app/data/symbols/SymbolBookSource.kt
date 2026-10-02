package com.mia.app.data.symbols

import com.mia.app.domain.model.ParserHealth
import java.io.InputStream

/**
 * Reads a bundled asset by name. **The only IO seam in this package.**
 *
 * plan.md §4.3 requires `:data` to contain no Android class, so `Context.assets` cannot appear
 * here. `:android-data` implements this with `context.assets.open(name)`; tests implement it over
 * the JVM classpath or a `ByteArray`. That is the whole production surface: one lambda.
 *
 * Returning `null` rather than throwing is deliberate — "the asset is not bundled in this build"
 * is an expected state of a debug build and must produce a *message*, not a stack trace in a
 * worker's failure slot.
 */
fun interface AssetReader {
    fun open(name: String): InputStream?
}

/**
 * Builds a [SymbolBook] from the bundled NSE symbol master.
 *
 * **Why the book is loaded from assets rather than fetched** (plan.md §9 requirement 1): search
 * must work offline and instantly, so the identity of all 2,593 listed instruments has to be on
 * the device. The file is 183 KB of source, which compresses well and costs ~1 MB of heap
 * indexed — a good trade for a search box that never fails, on the budget hardware plan.md §13
 * flags. Refreshing it over the network is a *v2* concern; nothing here assumes the bundled copy is
 * current, and [SymbolBook.health] is where a stale bundle would be surfaced.
 */
object SymbolBookSource {

    /** Asset path of the bundled master, matching `data/src/main/resources/assets/EQUITY_L.csv`. */
    const val ASSET_NAME: String = SymbolBookParser.ASSET_NAME

    /**
     * Loads and indexes the master.
     *
     * Never throws. A missing asset comes back as [SymbolBookLoadResult.book] `null` with a failed
     * [ParserHealth] that says so, because CONTRACTS.md rule 5 forbids a silent empty result and
     * plan.md §9 requirement 4 forbids a blank screen: the caller has to be able to render
     * "symbol data unavailable" instead of an unresolvable search box.
     */
    fun load(reader: AssetReader, assetName: String = ASSET_NAME): SymbolBookLoadResult {
        val stream = try {
            reader.open(assetName)
        } catch (e: Exception) {
            return failed("asset '$assetName' could not be opened: ${e.javaClass.simpleName}: ${e.message}")
        } ?: return failed("asset '$assetName' is not bundled in this build")

        val parsed = stream.use { SymbolBookParser.parse(it) }
        if (!parsed.isUsable) return SymbolBookLoadResult(book = null, health = parsed.health)

        val book = SymbolBook.of(parsed)
        return SymbolBookLoadResult(
            book = book,
            health = book.health,
            rowsTotal = parsed.rowsTotal,
            rejected = parsed.rejected,
        )
    }

    /** Parses master CSV [text] straight into a book; the seam for tests and one-shot ingestion. */
    fun bookFrom(text: String): SymbolBookLoadResult {
        val parsed = SymbolBookParser.parse(text)
        if (!parsed.isUsable) return SymbolBookLoadResult(book = null, health = parsed.health)
        val book = SymbolBook.of(parsed)
        return SymbolBookLoadResult(book, book.health, parsed.rowsTotal, parsed.rejected)
    }

    private fun failed(reason: String) = SymbolBookLoadResult(
        book = null,
        health = ParserHealth.failed(reason),
        error = reason,
    )
}