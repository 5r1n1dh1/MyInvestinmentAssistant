package com.mia.app.ui.theme.glass

import android.app.ActivityManager
import android.content.Context
import android.content.SharedPreferences
import android.os.PowerManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext

/**
 * The Android half of the glass preference — everything [GlassMode] deliberately does not import.
 *
 * Two responsibilities and nothing else: persist the reader's choice in `SharedPreferences`, and
 * read the two device signals that turn `AUTO` into a decision. The decision itself lives in
 * [GlassModeResolver], which is pure Kotlin and can be reasoned about without a device.
 *
 * ### Persistence follows the existing pattern
 * `MainActivity` already stores the first-launch disclaimer acknowledgement in a named
 * `SharedPreferences` file with `runCatching` around both read and write. This does the same thing
 * for one more key, in its own file so an adb backup shows two greppable files rather than one
 * mixed bag, and for the same reasons: the read is synchronous and happens once per process (a
 * single `getString` on the main thread is measured in microseconds and there is nothing personal
 * in it), the write uses `apply()` so no frame ever waits on a disk flush, and a corrupt
 * preferences file degrades to the default instead of crashing.
 */
private const val GLASS_PREFERENCES_NAME = "mia_glass"
private const val KEY_GLASS_MODE = "glass_mode"

/**
 * [GlassModeStorage] on top of `SharedPreferences`.
 *
 * `applicationContext` rather than the passed context: a `SharedPreferences` instance is owned by
 * the context that opened it, and handing one to an activity-scoped context leaks the activity for
 * as long as the instance lives.
 */
class SharedPreferencesGlassModeStorage(context: Context) : GlassModeStorage {

    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(GLASS_PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun readRaw(): String? = runCatching {
        preferences.getString(KEY_GLASS_MODE, null)
    }.getOrNull()

    override fun writeRaw(value: String) {
        runCatching {
            preferences.edit().putString(KEY_GLASS_MODE, value).apply()
        }
    }
}

/**
 * The process-wide glass mode.
 *
 * ### Why a singleton and not a `CompositionLocal`
 * A composition local would have to be provided once at the theme root — and the theme root is
 * `MiaTheme`, which belongs to another workstream. More importantly the reader changes this in
 * Settings and expects the *next* screen to already be different, including screens that are still
 * in the back stack. One snapshot state read by every [GlassSurface] makes that automatic: the
 * write in Settings invalidates every reader, with no plumbing through a theme, a Hilt module or a
 * route argument.
 *
 * ### Why it is primed during composition
 * [rememberGlassMode] primes the value on the first glass surface that composes rather than in a
 * `LaunchedEffect`. An effect would run *after* the first frame, so on the low-RAM device this
 * whole feature exists for, the first frame would apply a full-screen blur and then immediately
 * remove it. The prime writes a snapshot state once, guarded by a plain flag so it can never
 * re-enter, and it happens off any recomposition path.
 */
@Stable
object GlassModeController {

    private val _effective = mutableStateOf(GlassMode.FULL)

    /** Whether [prime] has already run for this process. A plain flag, not snapshot state. */
    private var primed = false

    /**
     * The mode every glass surface is drawing right now.
     *
     * Defaults to [GlassMode.FULL] before the first prime, so a screen that somehow reads this
     * without going through [rememberGlassMode] gets the full effect rather than a crash.
     */
    val effective: State<GlassMode> get() = _effective

    /** Whether the value has been read from storage for this process. */
    val isReady: Boolean get() = primed

    /** Reads storage and the device signals once. A no-op after the first call. */
    fun prime(context: Context) {
        if (primed) return
        primed = true
        refresh(context)
    }

    /** Re-reads everything. Called after a write, and when a screen wants current conditions. */
    fun refresh(context: Context) {
        _effective.value = resolve(context)
    }

    /**
     * Records [mode] as the reader's choice and re-resolves.
     *
     * Persists even when it resolves to the same drawn mode: `AUTO` on a low-RAM device draws `OFF`,
     * and writing `FULL` there must still be remembered so it takes effect on a bigger device.
     */
    fun select(context: Context, mode: GlassMode) {
        GlassModeStore(SharedPreferencesGlassModeStorage(context)).persist(mode)
        refresh(context)
    }

    private fun resolve(context: Context): GlassMode {
        val activityManager = runCatching {
            context.getSystemService(ActivityManager::class.java)
        }.getOrNull()
        val powerManager = runCatching {
            context.getSystemService(PowerManager::class.java)
        }.getOrNull()

        return GlassModeStore(SharedPreferencesGlassModeStorage(context)).effective(
            // A device that will not answer the question is treated as capable rather than
            // crippled: the opposite default would silently switch the design off on every device
            // where the service lookup failed.
            isLowRamDevice = activityManager?.isLowRamDevice ?: false,
            isPowerSaveMode = powerManager?.isPowerSaveMode ?: false,
        )
    }
}

/**
 * The glass mode for this composition.
 *
 * ```kotlin
 * val mode = rememberGlassMode()
 * if (GlassModeResolver.blurs(mode)) { /* … */ }
 * ```
 *
 * Reads the process-wide value, so every surface on screen agrees, and every surface recomposes the
 * moment the reader changes the setting in Settings.
 */
@Composable
fun rememberGlassMode(): GlassMode {
    val context = LocalContext.current
    if (!GlassModeController.isReady) GlassModeController.prime(context)
    return GlassModeController.effective.value
}
