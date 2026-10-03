package com.mia.app.ui.settings

import com.mia.app.ui.share.BriefCard
import com.mia.app.work.CacheClearResult
import java.time.LocalDate

/**
 * The Settings screen's entire view of the outside world (plan.md §1.4 rule 4, §3, §10).
 *
 * ### What Settings has to be, and why it is more than a disclaimer screen
 * plan.md §1.4 rule 4 puts the SEBI disclaimer here, but a screen whose only content is a paragraph
 * of legal text is a screen nobody trusts and nobody reads. So this screen is also where the app
 * states, in one place and honestly:
 * * what it measured (the regularised reading surfaces),
 * * what it could **not** measure (the coverage gaps — the part users never see otherwise),
 * * when the data was last refreshed and whether that succeeded (plan.md §10's "staleness must be
 *   visible in the UI"), and
 * * what the app does with the microphone (or rather, does not, until the user says so).
 *
 * ### Contract
 * * `suspend`, already off the main thread.
 * * `:domain` types only, plus two types owned by this module ([SettingsCoverage] and [BriefCard]).
 * * `null` / empty means **"there is nothing"**, not "we failed". A function that fails throws
 *   [SettingsUnavailableException].
 */
interface SettingsDataSource {

    /**
     * What the app can and cannot measure on this device, right now.
     *
     * Not a static list. A coverage gap that has been closed — because a backfill finished, or
     * because a symbol was removed from the watchlist — must disappear from this screen, or the
     * screen starts understating what the app knows.
     */
    suspend fun coverage(): SettingsCoverage

    /**
     * Builds the shareable brief for [symbol], or `null` when there is nothing stored for it.
     *
     * `null` is distinct from "stored but empty": a symbol the app has never fetched cannot be
     * shared, and the button must say so rather than produce an image of nothing.
     */
    suspend fun briefFor(symbol: String): BriefCard?

    /**
     * Clears cached market data and history.
     *
     * Never clears the tracked-symbol list — losing that is unrecoverable, and it is not what "clear
     * cache" means to anyone.
     */
    suspend fun clearCache(): CacheClearResult

    /**
     * Whether the device can recognise speech at all, and whether the microphone is granted.
     *
     * A plain two-value answer rather than a `Context` query in the composable, so the same check
     * runs on a device with no recogniser service without the screen needing a platform import.
     */
    suspend fun speechAvailability(): SpeechAvailability
}

/**
 * What the app can and cannot measure, as data.
 *
 * ### Why this is an owned type and not `RedFlagReport`
 * Coverage is a **negative**: it is the list of things that were not evaluated, over *every* surface
 * — prices, history, fundamentals, red-flag rules, pattern detection, accuracy — not one tab's flags.
 * There is no domain type for it because no domain engine produces it: it is assembled by asking each
 * layer what it did, which is a presentation concern.
 *
 * Every entry is a **fact with a date**, never a bare "missing". A reader cannot act on "some data is
 * missing" and does not know whether it is their problem.
 */
data class SettingsCoverage(
    /** True when nothing at all has been fetched on this device yet. */
    val isFirstRun: Boolean = true,

    /** Symbols the user is tracking. The one thing "clear cache" must not remove. */
    val trackedSymbolCount: Int = 0,

    /** Symbols with stored daily bars. */
    val symbolsWithDailyBars: Int = 0,

    /** Symbols with stored weekly bars. */
    val symbolsWithWeeklyBars: Int = 0,

    /** Symbols with a stored fundamentals snapshot. */
    val symbolsWithFundamentals: Int = 0,

    /** Symbols with stored pattern detections. */
    val symbolsWithPatterns: Int = 0,

    /** Symbols with at least one tracked pattern outcome. */
    val symbolsWithOutcomes: Int = 0,

    /** The newest stored bar's date, or `null` when no bar exists. */
    val latestBarDate: LocalDate? = null,

    /** The newest fundamentals as-of date, or `null`. */
    val latestFundamentalsDate: LocalDate? = null,

    /**
     * Declared gaps: what this build cannot do, whatever the device state is.
     *
     * The part of coverage that does **not** change with a refresh, so it belongs here and not in a
     * computed count. Every entry must be a fact about the app's scope, phrased so a reader can
     * check it.
     */
    val declaredGaps: List<CoverageGap> = CoverageGap.DEFAULT_GAPS,

    /**
     * Per-source notes about a fetch that did not complete. Empty when nothing is outstanding.
     */
    val sourceNotes: List<String> = emptyList(),
) {
    /** `true` when at least one declared gap or outstanding note exists, so the section always shows. */
    val hasAnythingToReport: Boolean
        get() = declaredGaps.isNotEmpty() || sourceNotes.isNotEmpty()

    companion object {
        /** Nothing fetched, everything declared. The state of a fresh install. */
        val EMPTY = SettingsCoverage()
    }
}

/**
 * One declared coverage gap.
 *
 * @param area which part of the app it affects, named the way the tabs are.
 * @param gap what is not covered, in one sentence.
 * @param consequence what the reader therefore cannot conclude. This is the field that makes the
 *   entry useful: a gap without a consequence is a disclaimer, and a disclaimer the reader has
 *   already scrolled past.
 */
data class CoverageGap(
    val area: String,
    val gap: String,
    val consequence: String,
) {
    companion object {
        /**
         * The gaps this build declares, from plan.md and its own scope decisions.
         *
         * Phrased from what the code does rather than from what it aspires to, so this list can be
         * checked against the implementation:
         * * **intraday** — plan.md D1 removed it; every figure is end-of-day.
         * * **delivery percentage** — plan.md §3.5's legacy file is unverified, and
         *   `PatternEngine` unscores confirmation when it is absent.
         * * **index benchmark** — `IndicatorSnapshot.relativeStrength` is `null` without one, and
         *   `PatternEngine` records that as an unscored context factor.
         * * **adjustment for corporate actions** — a split or demerger is detected by ISIN change
         *   (plan.md §9) and surfaced, but historical prices are not restated.
         * * **sector** — always defaults to `Sector.OTHER` rather than guessed (plan.md §1.4 rule 8).
         * * **accuracy coverage** — the track record only exists for symbols the nightly scan has
         *   already visited, which is the watchlist, not the market.
         */
        val DEFAULT_GAPS: List<CoverageGap> = listOf(
            CoverageGap(
                area = "Every figure",
                gap = "Prices, indicators and patterns are end-of-day only. There is no intraday " +
                    "mode.",
                consequence = "A figure cannot be read as a live price, and a pattern cannot be " +
                    "called a developing one during the session.",
            ),
            CoverageGap(
                area = "Pattern confirmation",
                gap = "Delivery percentage for the latest session is not always available; the " +
                    "source for it is unverified (plan.md §3.5).",
                consequence = "The confirmation factor is partly unscored on those bars, which " +
                    "lowers the match score rather than filling the gap with a guess.",
            ),
            CoverageGap(
                area = "Pattern context",
                gap = "Relative strength needs an index benchmark. No benchmark is loaded for a " +
                    "symbol on its own.",
                consequence = "The context factor is unscored, and the scan says so in its notes.",
            ),
            CoverageGap(
                area = "Fundamentals",
                gap = "A company's sector is only resolved when it is in the override list; " +
                    "everything else defaults to 'other'.",
                consequence = "Sector-specific ratios may not be the ones that matter for the " +
                    "company. Nothing is guessed to fill the gap.",
            ),
            CoverageGap(
                area = "History after a corporate action",
                gap = "A split, bonus or demerger is detected from an ISIN change and reported, " +
                    "but stored prices before it are not restated.",
                consequence = "Levels drawn from before the event are on the old scale. Check the " +
                    "symbol's note before reading an old level.",
            ),
            CoverageGap(
                area = "Track record",
                gap = "Outcomes settle in trading sessions, and only for symbols the nightly scan " +
                    "has visited — the tracked list, not the whole market.",
                consequence = "A hit rate covers what this device has actually watched. A pattern " +
                    "on an untracked symbol shows no record rather than a borrowed one.",
            ),
        )
    }
}

/**
 * Whether voice search can work on this device.
 *
 * @param isRecognizerPresent `SpeechRecognizer.isRecognitionAvailable`. `false` means the feature is
 *   absent from the device, so no permission prompt is worth showing.
 * @param isMicrophoneGranted the `RECORD_AUDIO` state.
 */
data class SpeechAvailability(
    val isRecognizerPresent: Boolean,
    val isMicrophoneGranted: Boolean,
) {
    /** `true` when a tap on the microphone would actually start a session. */
    val isUsable: Boolean get() = isRecognizerPresent && isMicrophoneGranted

    /** One sentence for the settings row. Says what is missing rather than only what is not. */
    val description: String
        get() = when {
            !isRecognizerPresent ->
                "This device has no speech recognition service, so voice search is unavailable. " +
                    "Typing a ticker works the same way."

            !isMicrophoneGranted ->
                "Speech recognition is present but the microphone permission has not been granted. " +
                    "Nothing is recorded until you grant it."

            else ->
                "Speech recognition is available and the microphone is granted. Recognition happens " +
                    "through the device's own speech service; this app asks it to work offline and " +
                    "reports only the words it returns."
        }

    companion object {
        /** Unknown, because the adapter is not wired. Rendered as unavailable, never as ready. */
        val UNKNOWN = SpeechAvailability(isRecognizerPresent = false, isMicrophoneGranted = false)
    }
}

/**
 * The exception every implementation throws on a failed read.
 *
 * Settings degrades more gracefully than other tabs — a coverage section that cannot be read still
 * has to render the disclaimer — so the screen shows the error *inside* the coverage card and keeps
 * everything else on screen.
 */
class SettingsUnavailableException(
    /** Plain language, shown verbatim. */
    val reason: String,
    val isTransient: Boolean = true,
) : RuntimeException(reason)

/** Test tag on the settings root. */
const val SETTINGS_TAG: String = "mia_settings"