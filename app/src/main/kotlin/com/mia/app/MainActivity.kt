package com.mia.app

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.mia.app.domain.compliance.Disclosure
import com.mia.app.ui.nav.MiaNavHost
import com.mia.app.ui.nav.Screen
import com.mia.app.ui.theme.MiaSpacing
import com.mia.app.ui.theme.MiaTheme

/**
 * The single activity (plan.md §4.1).
 *
 * ### Responsibilities, in order
 * 1. **The first-launch SEBI disclaimer gate** (plan.md §1.4 rule 4). Nothing else is shown until
 *    the reader has seen [Disclosure.FIRST_LAUNCH] and acknowledged it. This is a *gate*, not a
 *    banner: a dismissible banner that a user swipes past has not been shown to them.
 * 2. **Edge-to-edge**, so the chart can run under the status bar on a tall screen.
 * 3. The theme root, the app bar and the navigation graph.
 *
 * ### Why the gate's flag is plain `SharedPreferences`
 * It is one boolean, read once, and it must be readable **before** any Hilt object is created —
 * a `DataStore` or `EncryptedSharedPreferences` dependency would be either async (so the first
 * composition races the read) or an extra dependency for one flag. Synchronous `SharedPreferences`
 * on the main thread for a single boolean read at startup is measured in microseconds, and the file
 * holds nothing else: no symbol, no history, no personal data.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Must run before `setContent`: it sets the system-bar appearance and the transparent
        // scrims, and both are read when the first frame is drawn.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            MiaTheme {
                MiaApp()
            }
        }
    }
}

/** The whole UI, so `MainActivity` stays a shell and this is previewable/testable in isolation. */
@Composable
private fun MiaApp(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var acknowledged by rememberSaveable { mutableStateOf(hasAcknowledgedDisclaimer(context)) }

    if (!acknowledged) {
        FirstLaunchDisclaimerDialog(
            onAcknowledge = {
                recordDisclaimerAcknowledged(context)
                acknowledged = true
            },
        )
    }

    val navController = rememberNavController()
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { MiaTopBar(navController = navController) },
    ) { innerPadding ->
        MiaNavHost(
            navController = navController,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        )
    }
}

/**
 * plan.md §1.4 rule 4: the SEBI disclaimer, on first launch, verbatim.
 *
 * ### Why the copy is a constant and not a layout
 * [Disclosure.FIRST_LAUNCH] is the same string the Settings screen must show (rule 4 says *both*),
 * and it is the string a regulator would check. Rendering the constant means the two surfaces cannot
 * drift and that `AdviceLanguage`'s compliance test can walk the exact text the user sees.
 *
 * The dialog is not dismissible by tapping outside and has no "close" affordance — only
 * **"I understand"**. A gate that can be swiped away has not gated anything. It is also not
 * cancelable, so the back button cannot skip it either.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun FirstLaunchDisclaimerDialog(onAcknowledge: () -> Unit) {
    AlertDialog(
        onDismissRequest = { /* Intentionally empty: the gate cannot be dismissed. */ },
        title = {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleMedium,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(MiaSpacing.md),
            ) {
                Text(
                    text = Disclosure.FIRST_LAUNCH,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onAcknowledge,
                modifier = Modifier.testTag(DISCLAIMER_ACK_TAG),
            ) {
                Text(text = "I understand")
            }
        },
    )
}

/**
 * The app bar.
 *
 * Shows the app name at the root and the current screen's title deeper in, and offers a back
 * affordance whenever there is somewhere to go back to. The back control is a **text glyph**
 * (`←`) rather than an icon from `material-icons-core`: that artifact's availability has moved
 * between Compose versions, and a chart module that cannot be compiled here should not depend on it.
 * A glyph is also what TalkBack reads most reliably, and it is the shape Indian users expect.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun MiaTopBar(
    navController: NavHostController,
    modifier: Modifier = Modifier,
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val route = backStackEntry?.destination?.route
    val canGoBack = navController.previousBackStackEntry != null
    val title = remember(route) { titleFor(route) }

    TopAppBar(
        modifier = modifier,
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Start,
                maxLines = 1,
            )
        },
        navigationIcon = {
            if (canGoBack) {
                TextButton(
                    onClick = { navController.popBackStack() },
                    modifier = Modifier.testTag(BACK_TAG),
                ) {
                    Text(
                        text = BACK_GLYPH,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        },
        // plan.md §1.4 rule 4 requires the disclaimer in Settings, and §1.1's flow plus D6's
        // track record both need to be reachable. Text glyphs rather than `material-icons-core`:
        // that artifact's availability has moved between releases, and a missing icon would fail
        // the build, whereas a glyph cannot.
        actions = {
            TopBarLink("Track record") { navController.navigateTopLevel(Screen.Accuracy.route) }
            TopBarLink("Settings") { navController.navigateTopLevel(Screen.Settings.route) }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
            actionIconContentColor = MaterialTheme.colorScheme.onSurface,
        ),
    )
}

/**
 * A top-bar text button.
 *
 * A separate composable rather than three inline `TextButton`s so the three entry points cannot
 * drift apart in padding or test tag.
 */
@Composable
private fun TopBarLink(label: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.testTag("topbar_$label"),
    ) {
        Text(text = label, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * Navigates without stacking duplicates.
 *
 * These are three destinations reachable from anywhere, so a user tapping "Settings" on four
 * screens in a row must land on Settings four times, not on a stack of four Settings screens.
 * `launchSingleTop` plus `popUpTo` the start destination with `saveState`/`restoreState` is the
 * pattern for that; the state restore is what keeps the selected tab and scroll position alive
 * when the user navigates away and comes back.
 */
private fun NavHostController.navigateTopLevel(route: String) {
    navigate(route) {
        popUpTo(graph.startDestinationId) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/** The screen title for a route pattern, falling back to the app name. */
private fun titleFor(route: String?): String = when (route) {
    null -> APP_NAME_FALLBACK
    Screen.Technicals.route -> "Technicals"
    Screen.Search.route -> APP_NAME_FALLBACK
    Screen.StockList.route -> "Tracked symbols"
    Screen.StockDetail.route -> "Stock"
    Screen.Fundamentals.route -> "Fundamentals"
    Screen.Patterns.route -> "Patterns"
    Screen.Accuracy.route -> "Accuracy"
    Screen.Settings.route -> "Settings"
    else -> APP_NAME_FALLBACK
}

/**
 * The app name, resolved once at the top level.
 *
 * `stringResource` is a composable call, so it cannot appear in the `when` above without every
 * branch becoming composable; the root screens just show the app name instead, which is both correct
 * and simpler.
 */
private val APP_NAME_FALLBACK: String = Disclosure.APP_NAME

// ---------------------------------------------------------------- first-launch persistence

/**
 * `true` when the reader has already acknowledged [Disclosure.FIRST_LAUNCH].
 *
 * Wrapped in `runCatching` because a `SharedPreferences` read can throw on a device with a corrupt
 * preferences file. Failing *open* here would skip the regulatory gate, so the read defaults to
 * `false` and the disclaimer is shown again — an extra disclaimer is a recoverable annoyance, a
 * missing one is not.
 */
private fun hasAcknowledgedDisclaimer(context: Context): Boolean = runCatching {
    context.preferences().getBoolean(KEY_DISCLAIMER_ACKNOWLEDGED, false)
}.getOrDefault(false)

/**
 * Records the acknowledgement.
 *
 * `apply()` rather than `commit()`: the write is a single boolean, and blocking the main thread on a
 * disk write during the first frame is exactly the kind of jank plan.md §12 is trying to avoid. If the
 * write is lost the gate simply reappears next launch, which is the safe direction to fail.
 */
private fun recordDisclaimerAcknowledged(context: Context) {
    runCatching {
        context.preferences().edit().putBoolean(KEY_DISCLAIMER_ACKNOWLEDGED, true).apply()
    }
}

/**
 * The preferences file. Named so it is greppable in an adb backup and so nothing else in the app can
 * add an unrelated key to it by accident.
 */
private fun Context.preferences() =
    applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

private const val PREFERENCES_NAME = "mia_compliance"
private const val KEY_DISCLAIMER_ACKNOWLEDGED = "first_launch_disclaimer_acknowledged"

/** Test tag on the gate's acknowledgement button. */
const val DISCLAIMER_ACK_TAG: String = "mia_disclaimer_ack"

/** Test tag on the app bar's back control. */
const val BACK_TAG: String = "mia_back"

/** `←` — a back affordance that needs no icon artifact. */
private const val BACK_GLYPH = "←"
