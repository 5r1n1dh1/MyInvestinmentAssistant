package com.mia.app.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.mia.app.domain.model.Timeframe
import com.mia.app.ui.accuracy.AccuracyScreen
import com.mia.app.ui.compare.CompareScreen
import com.mia.app.ui.detail.StockDetailScreen
import com.mia.app.ui.di.SearchRoute
import com.mia.app.ui.education.EducationLessonScreen
import com.mia.app.ui.education.EducationScreen
import com.mia.app.ui.fundamentals.FundamentalsScreen
import com.mia.app.ui.journal.JournalScreen
import com.mia.app.ui.patterns.PatternsScreen
import com.mia.app.ui.sectors.SectorScreen
import com.mia.app.ui.settings.SettingsScreen
import com.mia.app.ui.stocklist.StockListScreen
import com.mia.app.ui.technicals.TechnicalsScreen

/**
 * The navigation graph.
 *
 * ### Every route is a real screen
 * All ten destinations in [Screen] are registered with the composable that implements them. There are
 * no placeholders left in this file and no open orchestrator question in it: a route that renders a
 * stand-in is indistinguishable from a route that is simply broken, and plan.md §12 names exactly that
 * as the most dangerous failure in the product.
 *
 * The argument lists were already correct before the screens existed and are unchanged — `ARG_SYMBOL`
 * on every symbol-scoped route and `ARG_TIMEFRAME` on every timeframe-scoped one, both `NavType.StringType`
 * so a hand-typed route or a restored back-stack entry can never crash on a parse error
 * ([ScreenArgs] falls back instead). The screens read them from the `SavedStateHandle` of this
 * destination's `NavBackStackEntry`, which is why none of them takes a symbol parameter: the route is
 * the source of truth, and a hand-passed argument could disagree with it.
 *
 * ### Where each screen actually lives
 * | route | composable | notes |
 * |---|---|---|
 * | `Screen.Search` | [SearchRoute] | a wrapper, not `SearchScreen` directly: it composes the screen **and** the Hinglish recogniser that consumes its `SearchEvent` channel. See `ui/di/SearchRoute.kt`. |
 * | `Screen.StockList` | [StockListScreen] | takes the two navigation callbacks. |
 * | `Screen.StockDetail` | [StockDetailScreen] | hosts the Fundamentals and Technicals tabs itself (`DetailSlot`); it takes no arguments for the same `SavedStateHandle` reason. |
 * | `Screen.Fundamentals` | [FundamentalsScreen] | the standalone route. The copy *inside* the detail screen's tab slot is `StockDetailScreen`'s own `DetailSlot`, not this destination. |
 * | `Screen.Technicals` | [TechnicalsScreen] | ditto. |
 * | `Screen.Patterns` | [PatternsScreen] | the standalone route for plan.md §6.5's full pattern list. |
 * | `Screen.Accuracy` | [AccuracyScreen] | |
 * | `Screen.Journal` | [JournalScreen] | gap E5. Argument-free: the journal spans every symbol written about, and its one callback opens the Accuracy tab so the app's own record can be read in full beside the reader's. |
 * | `Screen.Compare` | [CompareScreen] | gap E6. Argument-free: the compared set is re-chosen on the screen. |
 * | `Screen.Sector` | [SectorScreen] | the sector median beside the subject's ratios. Argument-free; the peer set is derived from what this device holds. |
 * | `Screen.Settings` | [SettingsScreen] | |
 *
 * `Screen.Fundamentals` and `Screen.Technicals` therefore have two entry points each — their own
 * destination, and the tab slot inside `Screen.StockDetail` — which is deliberate and is the same
 * `ViewModel` in both cases: `hiltViewModel()` keys off the back-stack entry, so the tab slot reuses the
 * detail entry's ViewModels and the standalone destination creates its own.
 *
 * ### `startDestination`
 * [Screen.Search], unchanged, and it is the right answer now that every screen is real:
 * * plan.md §1.1's flow is *open app → search a stock → read its tabs*, so search is where a cold launch
 *   belongs;
 * * a reader who has tracked symbols can reach the list in one tap from Search's results (each row's
 *   ticker opens the detail screen, and the empty watchlist state on the list screen points back here),
 *   so starting at the list would cost a reader who has **not** tracked anything a dead end;
 * * the alternative — "the watchlist if populated" — would need a synchronous read of Room during the
 *   first composition to decide, which is exactly the main-thread read plan.md §12 forbids, and it would
 *   make the launch destination differ between launches, so a reviewer could never reproduce a bug from a
 *   single fresh install.
 *
 * @param navController defaults to a controller remembered by the caller (`MainActivity`), so the
 *   activity's app bar can read the current destination for its title and back affordance.
 */
@Composable
fun MiaNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
    startDestination: String = Screen.Search.route,
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier.testTag(NAV_HOST_TAG),
    ) {
        // ------------------------------------------------------------------------ search

        composable(route = Screen.Search.route) {
            SearchRoute(onOpenStock = { symbol -> navController.openStock(symbol) })
        }

        // ------------------------------------------------------------------- tracked list

        composable(route = Screen.StockList.route) {
            StockListScreen(
                onOpenStock = { symbol -> navController.openStock(symbol) },
                onOpenSearch = {
                    // `popUpTo` + `launchSingleTop` so a reader who taps "search" from the empty
                    // watchlist lands on Search with one entry, not with a growing stack of identical
                    // destinations behind them.
                    navController.navigate(Screen.Search.route) {
                        popUpTo(Screen.Search.route) { inclusive = true }
                        launchSingleTop = true
                    }
                },
            )
        }

        // --------------------------------------------------------------- stock detail (tabs)

        composable(
            route = Screen.StockDetail.route,
            arguments = listOf(
                navArgument(ARG_SYMBOL) { type = NavType.StringType },
                navArgument(ARG_TIMEFRAME) { type = NavType.StringType },
            ),
        ) {
            StockDetailScreen()
        }

        // ------------------------------------------------------------------------ standalone

        composable(
            route = Screen.Fundamentals.route,
            arguments = listOf(navArgument(ARG_SYMBOL) { type = NavType.StringType }),
        ) {
            FundamentalsScreen()
        }

        composable(
            route = Screen.Technicals.route,
            arguments = listOf(
                navArgument(ARG_SYMBOL) { type = NavType.StringType },
                navArgument(ARG_TIMEFRAME) { type = NavType.StringType },
            ),
        ) {
            TechnicalsScreen()
        }

        composable(
            route = Screen.Patterns.route,
            arguments = listOf(
                navArgument(ARG_SYMBOL) { type = NavType.StringType },
                navArgument(ARG_TIMEFRAME) { type = NavType.StringType },
            ),
        ) {
            PatternsScreen()
        }

        composable(route = Screen.Accuracy.route) {
            AccuracyScreen()
        }

        composable(route = Screen.Settings.route) {
            SettingsScreen(
                onOpenEducation = { navController.navigate(Screen.Education.route) },
                onOpenJournal = { navController.navigate(Screen.Journal.route) },
                onOpenCompare = { navController.navigate(Screen.Compare.route) },
                onOpenSector = { navController.navigate(Screen.Sector.route) },
            )
        }

        composable(route = Screen.Journal.route) {
            // `launchSingleTop` so a reader who backs out of the journal and re-enters it from
            // Settings does not build a stack of identical destinations behind them.
            JournalScreen(
                onOpenAccuracy = {
                    navController.navigate(Screen.Accuracy.route) { launchSingleTop = true }
                },
            )
        }

        composable(route = Screen.Sector.route) {
            SectorScreen()
        }

        composable(route = Screen.Compare.route) {
            CompareScreen()
        }

        composable(route = Screen.Education.route) {
            EducationScreen(
                onOpenLesson = { id ->
                    navController.navigate(Screen.EducationLesson.createRoute(id))
                },
            )
        }

        composable(
            route = Screen.EducationLesson.route,
            arguments = listOf(navArgument(ARG_LESSON) { type = NavType.StringType }),
        ) { entry ->
            EducationLessonScreen(
                // `NavBackStackEntry.arguments` is a `Bundle?`, so the one argument this route
                // carries is read off it directly rather than converting the whole bundle to a map
                // — which would mean depending on `Bundle.toMap()`, an androidx-core extension
                // rather than platform API. A null bundle yields a null id, which normalises to the
                // empty string and renders the lesson screen's "not available" state.
                lessonId = ScreenArgs.lessonIdOf(entry.arguments?.getString(ARG_LESSON)),
            )
        }
    }
}

/**
 * Opens a symbol's tabs.
 *
 * ### Why `Timeframe.D1` always
 * plan.md decision D1 removed intraday and left daily as the default granularity. Both callers
 * (`SearchScreen`, `StockListScreen`) document the same choice in their own KDoc, and the detail
 * screen's header toggle plus the Technicals tab's own `TimeframeToggle` are how a reader changes it —
 * so a route built here is a *seed*, not a decision the caller has to make.
 *
 * ### Why the route is built by `Screen.StockDetail.createRoute` and not by string concatenation
 * The route template and the builder live together in [Screen], so the argument names, the order and
 * the `Timeframe` storage key are one edit rather than two. A hand-written
 * `"stockdetail/$symbol/D1"` here would be a second answer to "what is a stock-detail route".
 *
 * @param symbol a ticker as the user typed it. `Screen.StockDetail.createRoute` interpolates it
 *   verbatim, and the argument is read back through [ScreenArgs.symbolOf], which trims and upper-cases
 *   it — so a mixed-case or padded ticker from the search box resolves correctly. A **blank** symbol
 *   would produce `/D1`; no caller can do that, because both rows come from a resolved `SymbolSummary`,
 *   and the detail screen's own ViewModel has an explicit `MISSING_SYMBOL_REASON` for the case anyway.
 */
private fun NavHostController.openStock(symbol: String) {
    navigate(Screen.StockDetail.createRoute(symbol.trim().uppercase(), Timeframe.D1))
}

/** Test tag on the graph root, so an instrumentation test can find the host without a route name. */
const val NAV_HOST_TAG: String = "mia_nav_host"