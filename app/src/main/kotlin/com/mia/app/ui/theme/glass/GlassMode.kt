package com.mia.app.ui.theme.glass

/**
 * How much glass the app draws.
 *
 * ### Why this is a preference and not a theme switch
 * plan.md §1.4 keeps user-tunable presentation out of v1, and this does not reopen that. It has no
 * effect on any number, any disclosure, or anything the reader is asked to act on: [FULL] and
 * [OFF] render the same text with the same contrast, differing only in whether a pane behind the
 * text is blurred. It is the accessibility equivalent of "reduce motion", which is why it defaults
 * to letting the device decide.
 */
enum class GlassMode {
    /** Follow the device: [GlassModeResolver] turns this into [FULL] or [OFF]. The default. */
    AUTO,

    /** 20 dp backdrop blur on headers and fixed surfaces, 12–18% fills, hairline edges. */
    FULL,

    /** 8 dp blur and a stronger fill. Same layout, a quarter of the fill rate. */
    REDUCED,

    /** No blur, no translucency. A plain surface. Nothing is lost but the effect. */
    OFF,
    ;

    companion object {
        /** What a reader who has never opened Settings gets. */
        val DEFAULT: GlassMode = AUTO

        /**
         * Parses a stored preference.
         *
         * Lenient by design and it must stay that way: the stored value is a `String` in a
         * preferences file that survives an app update, so it can hold a name this build no longer
         * declares. An unrecognised value falls back to [DEFAULT], because losing a cosmetic
         * preference is the only acceptable failure here.
         */
        fun fromStorage(raw: String?): GlassMode =
            entries.firstOrNull { it.name == raw } ?: DEFAULT
    }
}

/**
 * Turns the reader's choice into the mode actually drawn.
 *
 * **Pure Kotlin, no Android imports, on purpose.** The decision "should a 2 GB phone blur?" is
 * logic, not platform plumbing, and logic is testable. The platform side — `ActivityManager
 * .isLowRamDevice`, `PowerManager.isPowerSaveMode`, `SharedPreferences` — lives in
 * `GlassModeAndroid.kt` and is passed in here as two booleans, so this file could be moved to
 * `:domain` verbatim and tested with no emulator, no Robolectric and no SDK.
 */
object GlassModeResolver {

    /**
     * The mode to draw.
     *
     * ### The AUTO rule
     * `AUTO` is `OFF` when **either** signal says the device is struggling, and `FULL` otherwise:
     *
     * - `isLowRamDevice` is the platform's own judgement that this device cannot afford extra
     *   memory. A blur is a GPU pass over a surface, and a surface is memory; plan.md §12 targets a
     *   2 GB budget device where a full-screen blur is the single most expensive thing on screen.
     * - `isPowerSaveMode` means the user asked the device to stop doing work in the background.
     *   Honoring that with the same off-switch is the point of the flag, and a reader who is
     *   watching battery drain has usually turned it on for exactly this kind of reason.
     *
     * The two are OR-ed rather than AND-ed because the failure they prevent is the same failure —
     * a device that cannot afford the pass — and a device that trips both must not be treated as
     * healthy because "one of the two is fine".
     *
     * An explicit [FULL], [REDUCED] or [OFF] is **never** overridden. A reader who chose Full on a
     * device this app thinks is low-RAM has said what they want, and the app has no standing to
     * overrule it; the choice is theirs and [GlassMode.OFF] is one tap away.
     */
    fun resolve(
        selected: GlassMode,
        isLowRamDevice: Boolean,
        isPowerSaveMode: Boolean,
    ): GlassMode = when (selected) {
        GlassMode.AUTO -> if (isLowRamDevice || isPowerSaveMode) GlassMode.OFF else GlassMode.FULL
        GlassMode.FULL, GlassMode.REDUCED, GlassMode.OFF -> selected
    }

    /** Whether this mode runs a blur at all. */
    fun blurs(mode: GlassMode): Boolean = mode == GlassMode.FULL || mode == GlassMode.REDUCED

    /**
     * The blur radius, in dp, for [mode].
     *
     * [GlassMode.OFF] and an unresolvable [GlassMode.AUTO] are `0f`: [GlassMode.AUTO] is a stored
     * preference, never a drawn mode, and drawing it would mean guessing.
     */
    fun blurDp(mode: GlassMode): Float = when (mode) {
        GlassMode.FULL -> GlassTokens.MediumBlur.value
        GlassMode.REDUCED -> GlassTokens.ReducedBlur.value
        GlassMode.AUTO, GlassMode.OFF -> 0f
    }

    /** The fill alpha for [mode]: less blur means the fill has to carry more of the contrast. */
    fun fillAlpha(mode: GlassMode): Float = when (mode) {
        GlassMode.REDUCED -> GlassTokens.FillAlphaStrong
        else -> GlassTokens.FillAlphaMedium
    }
}

/**
 * Where the reader's choice is kept.
 *
 * A port rather than a `SharedPreferences` type, for the same reason [GlassModeResolver] takes
 * booleans: the round-trip "read what was written, including an unknown or missing value" is the
 * part that can actually be wrong, and this keeps it testable off-device. The Android
 * implementation is `SharedPreferencesGlassModeStorage`.
 */
interface GlassModeStorage {
    /** The stored mode name, or `null` if the reader has never chosen one. */
    fun readRaw(): String?

    /** Stores [value] as the mode name. */
    fun writeRaw(value: String)
}

/**
 * The preference itself: read, write, and resolve.
 *
 * Deliberately tiny and deliberately synchronous. It is the same reasoning as the first-launch
 * disclaimer flag in `MainActivity` — one small string, read once per process, and nothing here is
 * large or personal enough to justify a `DataStore` dependency that would make the first frame
 * race an async read.
 */
class GlassModeStore(private val storage: GlassModeStorage) {

    /** What the reader chose, ignoring the device. */
    fun stored(): GlassMode = GlassMode.fromStorage(storage.readRaw())

    /** Records the reader's choice. */
    fun persist(mode: GlassMode) {
        storage.writeRaw(mode.name)
    }

    /** What to draw, for the current device conditions. */
    fun effective(isLowRamDevice: Boolean, isPowerSaveMode: Boolean): GlassMode =
        GlassModeResolver.resolve(
            selected = stored(),
            isLowRamDevice = isLowRamDevice,
            isPowerSaveMode = isPowerSaveMode,
        )
}
