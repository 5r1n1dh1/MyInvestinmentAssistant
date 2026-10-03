package com.mia.app.data.android.net

import android.content.Context
import com.mia.app.data.symbols.AssetReader
import java.io.IOException
import java.io.InputStream

/**
 * [AssetReader] over `Context.assets`.
 *
 * ### The one-lambda seam
 * plan.md §4.3 forbids Android classes inside `:data`, so the bundled NSE symbol master is
 * read through `com.mia.app.data.symbols.AssetReader { name -> … }`. That is the entire
 * production surface: `SymbolBookSource.load` is written not to throw, and returning `null`
 * for a missing asset is what produces "symbol data unavailable" instead of a stack trace in a
 * worker's failure slot.
 *
 * ### `FileNotFoundException` is an expected answer
 * A missing asset is a normal state of a debug build, so it is caught and turned into `null`.
 * Anything else — a corrupt archive's `IOException` on read, a `SecurityException` — is also
 * turned into `null` rather than propagated, because the caller's only options are "load it" or
 * "explain why it could not be loaded", and an exception is not an explanation.
 */
class ContextAssetReader(context: Context) : AssetReader {

    private val appContext: Context = context.applicationContext

    override fun open(name: String): InputStream? = try {
        appContext.assets.open(name)
    } catch (e: IOException) {
        // `FileNotFoundException extends IOException`; both mean "not loadable right now".
        null
    } catch (e: SecurityException) {
        null
    }
}

/**
 * Convenience factory, so the Hilt binding reads as one expression instead of an anonymous
 * object and so a test can substitute a lambda at the same call site.
 */
fun assetReaderOf(context: Context): AssetReader = ContextAssetReader(context)