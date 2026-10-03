package com.mia.app.data.android.repo

import com.mia.app.data.symbols.AssetReader
import com.mia.app.data.symbols.SymbolBook
import com.mia.app.data.symbols.SymbolBookLoadResult
import com.mia.app.data.symbols.SymbolBookSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import com.mia.app.data.android.di.IoDispatcher

/**
 * Loads and memoises the bundled NSE symbol master.
 *
 * ### Why it is loaded once, in memory
 * plan.md §9 requirement 5: search must be **local, offline and instant**. The master is 2,593
 * rows of ~183 KB of source, so the whole index is about a megabyte of heap — cheap on a 2 GB
 * phone (plan.md §13) and the reason there is no database query per keystroke. Room's
 * `symbols` table holds the *durable* identity; this class is the read path.
 *
 * ### Why only successes are memoised
 * A build with the asset missing must keep reporting the missing asset, not a stale success,
 * and a build whose assets were fixed underneath it must be able to recover: [invalidate] drops
 * the cache and [SymbolBookSource.load] simply runs again. Caching a failure would make the
 * first run's answer permanent.
 */
@Singleton
class SymbolBookProvider @Inject constructor(
    private val assetReader: AssetReader,
    @IoDispatcher private val dispatcher: CoroutineDispatcher,
) {

    private val mutex = Mutex()

    @Volatile
    private var cached: SymbolBook? = null

    /**
     * The book, or a not-ok result naming why not.
     *
     * Never throws. `SymbolBookSource.load` already converts a missing asset into a failed
     * [SymbolBookLoadResult] so a stripped debug APK renders a message instead of a crash.
     */
    suspend fun load(): SymbolBookLoadResult = withContext(dispatcher) {
        cached?.let { book ->
            return@withContext SymbolBookLoadResult(book = book, health = book.health, rowsTotal = book.size)
        }
        mutex.withLock {
            cached?.let { book ->
                return@withLock SymbolBookLoadResult(book = book, health = book.health, rowsTotal = book.size)
            }
            val loaded = SymbolBookSource.load(assetReader)
            if (loaded.book != null) cached = loaded.book
            loaded
        }
    }

    /** Drops the memo so the next [load] re-reads the asset. */
    fun invalidate() {
        cached = null
    }
}