package com.mia.app.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mia.app.domain.model.Timeframe
import com.mia.app.ui.common.AsOfChip
import com.mia.app.ui.common.ChangePercentText
import com.mia.app.ui.common.LoadingState
import com.mia.app.ui.common.TimeframeToggle
import com.mia.app.ui.common.formatPrice
import com.mia.app.ui.fundamentals.FundamentalsScreen
import com.mia.app.ui.technicals.TechnicalsScreen
import com.mia.app.ui.theme.MiaSpacing

/**
 * The tab host for one symbol. Route: `stockdetail/{symbol}/{timeframe}`.
 *
 * ### What this screen is, and what it deliberately is not
 * It is a **frame**: a header, two tabs and a slot. It owns no analysis of its own. Every figure below
 * the tabs comes from the tab that owns it, and the header's four numbers come from
 * [StockDetailDataSource], four fields wide. That is the whole reason the technicals and fundamentals
 * tabs can each own a ViewModel, a data source and a refresh policy without either knowing the other
 * exists.
 *
 * ```
 *  header      symbol, company name, 1D/1W toggle, last close + day change, as-of chip
 *  TabRow      Fundamentals | Technicals
 *  slot        the selected tab
 * ```
 *
 * ### Why the header's timeframe toggle is not the tab's timeframe
 * `TechnicalsScreen()` is called **directly**, with no arguments, as required. Its ViewModel resolves
 * against this same `NavBackStackEntry`, reads `ARG_SYMBOL` and `ARG_TIMEFRAME` from the route in its
 * constructor, and therefore draws its **own** `TimeframeToggle` for the price pane. The header's
 * toggle is the tab host's granularity, and it writes back to `ARG_TIMEFRAME` so a destination opened
 * from here inherits the reader's choice.
 *
 * The consequence is two toggles on the Technicals tab that start in agreement and can diverge after
 * the reader changes one of them. That is a documented trade, not an accident: removing it means giving
 * `TechnicalsScreen` an optional `timeframe` parameter, which is a file this agent does not own.
 *
 * @param viewModel obtained with `hiltViewModel()`. The ticker and the initial timeframe come from the
 *   route's `SavedStateHandle`, and the selected tab is written back into it, so the whole screen is
 *   restorable after process death without a single ViewModel field holding navigation state.
 */
@Composable
fun StockDetailScreen(
    modifier: Modifier = Modifier,
    viewModel: StockDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val selectedTab by viewModel.selectedTab.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag(DETAIL_TAG),
    ) {
        DetailHeader(
            state = state,
            onTimeframeSelected = viewModel::onTimeframeSelected,
            onRetry = viewModel::onRetry,
        )

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        TabRow(
            selectedTabIndex = selectedTab,
            modifier = Modifier.testTag(TAB_ROW_TAG),
        ) {
            TAB_LABELS.forEachIndexed { index, label ->
                Tab(
                    selected = index == selectedTab,
                    onClick = { viewModel.onTabSelected(index) },
                    text = {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.testTag("mia_stock_detail_tab_" + label),
                        )
                    },
                )
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        DetailSlot(
            tab = selectedTab,
            modifier = Modifier
                .fillMaxSize()
                .testTag(DETAIL_SLOT_TAG),
        )
    }
}

/**
 * The header.
 *
 * ### Why the change is `ChangePercentText`
 * A ▲/▼ glyph plus a spoken direction word, so the day's move survives a colour-vision deficiency, a
 * washed-out screen and TalkBack. A bare red percentage is the encoding plan.md §1.4's accessibility
 * reality exists to prevent, and the header is the one place a reader looks first.
 *
 * ### Why the close's absence is explained
 * A symbol added to the watchlist seconds ago has no bar yet. The header says so with the reason
 * rather than printing a dash in a price slot, because an unexplained dash is indistinguishable from a
 * bug (plan.md §12).
 */
@Composable
private fun DetailHeader(
    state: StockDetailUiState,
    onTimeframeSelected: (Timeframe) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = MiaSpacing.lg, vertical = MiaSpacing.md),
        verticalArrangement = Arrangement.spacedBy(MiaSpacing.sm),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(MiaSpacing.md),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = state.symbol.ifEmpty { MISSING_SYMBOL_LABEL },
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    text = state.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = LAST_CLOSE_LABEL + formatPrice(state.header?.lastClose),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                ChangePercentText(
                    changePercent = state.header?.changePercent,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        if (state.header?.hasClose != true) {
            Text(
                text = NO_CLOSE_REASON,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        TimeframeToggle(
            selected = state.timeframe,
            onSelect = onTimeframeSelected,
        )

        state.notPublishedNote?.let { note ->
            Text(
                text = note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(NOT_PUBLISHED_TAG),
            )
        }

        state.errorReason?.let { reason ->
            HeaderError(reason = reason, onRetry = onRetry)
        }

        val freshness = state.freshness
        if (state.asOf != null && freshness != null) {
            AsOfChip(
                source = BHAVCOPY_SOURCE,
                asOf = state.asOf,
                freshness = freshness,
                modifier = Modifier.testTag(HEADER_AS_OF_TAG),
            )
        }
    }
}

/**
 * A header-level failure.
 *
 * Deliberately **not** the shared `ErrorState`: that is a full card built for a screen with nothing
 * else to show. Inside a header that already carries the ticker, a second card saying "Could not load"
 * would bury the one fact the reader needs — which stock this is. So this is one line plus a retry
 * button, both in the error colour.
 *
 * The retry is a real [TextButton] rather than bare styled text: an error the reader cannot act on is
 * half an error, and a bare `Text` would have no 48 dp touch target.
 */
@Composable
private fun HeaderError(reason: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MiaSpacing.xs),
    ) {
        Text(
            text = HEADER_ERROR_PREFIX + reason,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag(HEADER_ERROR_TAG),
        )
        TextButton(
            onClick = onRetry,
            modifier = Modifier.testTag(HEADER_RETRY_TAG),
        ) {
            Text(text = RETRY_LABEL, color = MaterialTheme.colorScheme.error)
        }
    }
}

/**
 * The tab slot.
 *
 * ### Why both tabs are called directly
 * `TechnicalsScreen()` and `FundamentalsScreen()` each take only an optional `modifier` and resolve
 * their own ViewModel against **this** `NavBackStackEntry`. The technicals tab therefore reads this
 * route's `symbol` and `timeframe` arguments, keeps its own lifecycle, and needs nothing from here.
 * Wrapping either in a host that pushed state down would duplicate that state and re-introduce the
 * coupling the tab host exists to avoid.
 *
 * ### Why the non-selected tab is not composed
 * Only the selected tab is built, and the branch is a `when` over the tab index, so adding a third tab
 * is a compile-time decision rather than a silent blank slot. It is also plan.md §12's lazy-load rule:
 * the technicals tab's fifteen-indicator pass must not run for a reader who opened the symbol to read
 * its fundamentals.
 */
@Composable
private fun DetailSlot(tab: Int, modifier: Modifier = Modifier) {
    when (tab) {
        StockDetailViewModel.FUNDAMENTALS_TAB -> FundamentalsScreen(modifier = modifier)

        TECHNICALS_TAB -> TechnicalsScreen(modifier = modifier)

        PATTERNS_TAB -> PatternsScreen(modifier = modifier)

        else -> LoadingState(label = UNKNOWN_TAB_LABEL)
    }
}

// ------------------------------------------------------------------------------ copy tokens

/**
 * The two tab labels, in index order.
 *
 * A `List<String>` rather than two loose constants so the tab row and
 * [StockDetailViewModel.TAB_COUNT] cannot disagree about how many tabs exist.
 */
private val TAB_LABELS: List<String> = listOf(
    FUNDAMENTALS_LABEL,
    TECHNICALS_LABEL,
    PATTERNS_LABEL,
)

/** Index of the Technicals tab, matching its position in [TAB_LABELS]. */
private const val TECHNICALS_TAB: Int = 1

/**
 * Index of the Patterns tab, matching its position in [TAB_LABELS].
 *
 * The Pattern Explorer. `PatternsScreen` reads `ARG_SYMBOL` and `ARG_TIMEFRAME` from the route's
 * `SavedStateHandle`, which it inherits from **this** screen's destination — the same way the
 * Technicals tab already does — so it needed no route of its own and no signature change.
 */
private const val PATTERNS_TAB: Int = 2

private const val FUNDAMENTALS_LABEL: String = "Fundamentals"
private const val TECHNICALS_LABEL: String = "Technicals"
private const val PATTERNS_LABEL: String = "Patterns"

private const val LAST_CLOSE_LABEL: String = "Last close "
private const val NO_CLOSE_REASON: String =
    "No end-of-day close is stored for this symbol yet. The daily bhavcopy is fetched once a day " +
        "after close, so a symbol tracked moments ago has nothing to show."
private const val HEADER_ERROR_PREFIX: String = "Could not load the header: "
private const val RETRY_LABEL: String = "Retry"
private const val UNKNOWN_TAB_LABEL: String = "Loading"
private const val MISSING_SYMBOL_LABEL: String = "—"

private const val DETAIL_TAG: String = "mia_stock_detail"
private const val TAB_ROW_TAG: String = "mia_stock_detail_tabs"
private const val DETAIL_SLOT_TAG: String = "mia_stock_detail_slot"
private const val HEADER_AS_OF_TAG: String = "mia_stock_detail_as_of"
private const val HEADER_ERROR_TAG: String = "mia_stock_detail_error"
private const val HEADER_RETRY_TAG: String = "mia_stock_detail_retry"
private const val NOT_PUBLISHED_TAG: String = "mia_stock_detail_not_published"