package com.mia.app.ui.nav

import com.mia.app.domain.model.Timeframe

/**
 * Nav argument name for the NSE symbol. Top level so a route template can interpolate it from
 * inside the `Screen` declarations without depending on companion-scope resolution.
 */
const val ARG_SYMBOL: String = "symbol"

/**
 * Nav argument name for the timeframe storage key. See [Screen] for why the key is `D1` / `W1` and
 * not the display label.
 */
const val ARG_TIMEFRAME: String = "timeframe"

/** Nav argument for a lesson id. See [Screen.EducationLesson]. */
const val ARG_LESSON: String = "lesson"

// Route constants for the argument-free screens.
private const val ROUTE_SEARCH = "search"
private const val ROUTE_STOCK_LIST = "stocklist"
private const val ROUTE_ACCURACY = "accuracy"
private const val ROUTE_SETTINGS = "settings"
private const val ROUTE_EDUCATION = "education"

/**
 * The app's route table.
 *
 * ### Why a sealed class and not a `when` over route strings
 * Every route, its arguments and its builder live in one exhaustive type, so "which screens exist"
 * is answerable by reading this file, and adding a screen is a compile error at every place that
 * must then handle it. The route strings are still plain strings, because that is what `NavHost`
 * needs and what would make typed deep links possible later without changing this type.
 *
 * ### Arguments
 * Two, and only two:
 *
 * * [ARG_SYMBOL] — the NSE symbol, uppercase, no suffix (`RELIANCE`).
 * * [ARG_TIMEFRAME] — a [Timeframe] **storage key**, i.e. `D1` / `W1`, via
 *   [Timeframe.fromStorageKey]. The enum name rather than the display label `1D` / `1W`, because a
 *   route is persisted in the navigation back stack and renaming it would orphan saved state.
 *
 * Both are passed as **strings, not enums**, so a hand-typed route or a restored back-stack entry
 * can never crash the app with a parse error: [ScreenArgs] falls back to a default instead.
 *
 * ### Ownership
 * This file is the whole navigation surface. Screens written by other agents are *declared* here so
 * their routes exist and are documented, but their composables are wired as clearly-marked
 * placeholders in `MiaNavHost.kt`.
 */
sealed class Screen(val route: String) {

    /** Offline symbol search. The app's entry point. */
    data object Search : Screen(ROUTE_SEARCH)

    /** The user's tracked symbols, with their latest EOD snapshots. */
    data object StockList : Screen(ROUTE_STOCK_LIST)

    /** A symbol's tabs. [ARG_TIMEFRAME] seeds the Technicals tab's toggle. */
    data object StockDetail : Screen("stockdetail/{$ARG_SYMBOL}/{$ARG_TIMEFRAME}") {
        fun createRoute(symbol: String, timeframe: Timeframe): String =
            "stockdetail/$symbol/${timeframe.name}"
    }

    /** plan.md §7 — ratios, statements, sector. */
    data object Fundamentals : Screen("fundamentals/{$ARG_SYMBOL}") {
        fun createRoute(symbol: String): String = "fundamentals/$symbol"
    }

    /** plan.md §6 / §8.2 — indicators, chart, red flags, projections. */
    data object Technicals : Screen("technicals/{$ARG_SYMBOL}/{$ARG_TIMEFRAME}") {
        fun createRoute(symbol: String, timeframe: Timeframe): String =
            "technicals/$symbol/${timeframe.name}"
    }

    /** plan.md §6.5 / decision D4 — every detected pattern, ranked, with the user's filter. */
    data object Patterns : Screen("patterns/{$ARG_SYMBOL}/{$ARG_TIMEFRAME}") {
        fun createRoute(symbol: String, timeframe: Timeframe): String =
            "patterns/$symbol/${timeframe.name}"
    }

    /** plan.md §6.1 / decision D6 — the app's own track record, per stock and per pattern. */
    data object Accuracy : Screen(ROUTE_ACCURACY)

    /** plan.md §1.4 rule 4 — the SEBI disclaimer, verbatim, plus the data sources. */
    data object Settings : Screen(ROUTE_SETTINGS)

    /** gap E1–E7 — the three-level curriculum. The hub lists modules; [Education] opens a lesson. */
    data object Education : Screen(ROUTE_EDUCATION)

    /**
     * One lesson, by id.
     *
     * A route rather than a detail pane so a lesson is a linkable destination — the curriculum is
     * the part of this app most worth citing, and a citation needs something to point at.
     *
     * The id is namespaced by level and topic (`beginner.jargon.pe`), so it is safe in a route
     * without escaping; `Curriculum.lesson(id)` resolving to null is handled by the screen, not by
     * crashing the nav graph.
     */
    data object EducationLesson : Screen("educationlesson/{$ARG_LESSON}") {
        fun createRoute(lessonId: String): String = "educationlesson/$lessonId"
    }
}

/** Reads and normalises nav arguments, so no screen has to. */
object ScreenArgs {

    /**
     * Reads a symbol out of a nav argument map, upper-cased and trimmed.
     *
     * NSE symbols are uppercase by convention, so normalising here means a hand-typed route or a
     * restored back-stack entry cannot produce `Reliance` in a query that expects `RELIANCE`.
     * Returns an empty string — **not a throw** — so a malformed deep link lands on the error state
     * instead of crashing the app on launch.
     */
    fun symbolOf(arguments: Map<String, Any?>): String =
        (arguments[ARG_SYMBOL] as? String)?.trim()?.uppercase().orEmpty()

    /**
     * Reads a timeframe out of a nav argument map, defaulting to [Timeframe.D1].
     *
     * A missing or unknown key is not an error: `Timeframe` has exactly two members (decision D1),
     * so the worst case is showing the other granularity, which the toggle makes obvious and
     * correctable in one tap.
     */
    /**
     * Reads a lesson id out of a nav argument map.
     *
     * Trimmed but otherwise untouched: lesson ids are dotted lowercase (`beginner.jargon.pe`) and
     * are compared verbatim against `Curriculum`, which owns the canonical spelling. Normalising
     * case here would be guessing at a key rather than normalising one.
     *
     * Returns an empty string rather than throwing, for the same reason [symbolOf] does: a stale
     * link renders the "lesson is not available" state instead of taking the app down on the way
     * in.
     */
    fun lessonIdOf(arguments: Map<String, Any?>): String =
        (arguments[ARG_LESSON] as? String)?.trim().orEmpty()

    fun timeframeOf(arguments: Map<String, Any?>): Timeframe {
        val raw = arguments[ARG_TIMEFRAME] as? String ?: return Timeframe.D1
        return Timeframe.fromStorageKey(raw.trim().uppercase()) ?: Timeframe.D1
    }
}
