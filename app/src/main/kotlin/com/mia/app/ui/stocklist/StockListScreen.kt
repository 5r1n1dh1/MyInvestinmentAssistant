package com.mia.app.ui.stocklist

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mia.app.domain.model.Source
import com.mia.app.domain.ratios.Freshness
import com.mia.app.domain.ratios.FreshnessKind
import com.mia.app.domain.ratios.FreshnessPolicy
import com.mia.app.ui.common.AsOfChip
import com.mia.app.ui.common.ChangePercentText
import com.mia.app.ui.common.DataSourceFooter
import com.mia.app.ui.common.EmptyState
import com.mia.app.ui.common.ErrorState
import com.mia.app.ui.common.LoadingState
import com.mia.app.ui.common.formatPrice
import com.mia.app.ui.theme.MiaSpacing
import com.mia.app.ui.theme.glass.GlassBackdrop
import com.mia.app.ui.theme.glass.GlassCard
import com.mia.app.ui.theme.glass.GlassTopBar
import com.mia.app.ui.theme.glass.glassEntrance
import com.mia.app.ui.theme.glass.glassPressScale

/**
 * The tracked-symbols list — the app's home surface after a first launch.
 *
 * ### Section order, and why
 * ```
 *  add row            pinned above the scroll: adding a ticker must never require scrolling first
 *  notice             one transient line, the acknowledgement of the last add/remove
 *  coverage note      what the red-flag check could NOT evaluate — plan.md §12
 *  error              only when there is nothing to render at all
 *  rows               symbol, name, last close, day change, flag count, as-of, remove
 *  data-source footer
 * ```
 *
 * ### Offline-first, visibly
 * Every row renders from the on-device cache, so the list is correct with no network and appears on
 * the first frame. What it must never do is look *live*, so each row carries an [AsOfChip] with the
 * trading day the close is from, judged against `FreshnessKind.PRICE`'s one-day window: a Friday
 * close read on Sunday is marked stale rather than presented as today's price (plan.md §10).
 *
 * ### The empty list is a first-run state
 * A brand-new install has no tracked symbols, and that is the app's normal beginning, not a failure.
 * [EmptyWatchlist] therefore explains what tracking is *for* and points at Search, rather than
 * rendering "no data".
 *
 * ### Glass (planv2 W7) — the reference implementation for the rest of the app
 * Three layers, and only three:
 * ```
 *  GlassBackdrop   one fixed, blurred background behind the whole screen — the entire blur budget
 *  GlassTopBar     the add-row header, blurring its own copy of that background
 *  GlassCard       the rows: a translucent pane and a hairline, and NO blur
 * ```
 * The rule that makes this affordable lives in `Glass.kt`: a blur is never inside a `LazyColumn`
 * item. The rows sit on the screen's single blurred layer, so a watchlist of 40 costs what a
 * watchlist of 4 costs. The two interactions — [glassPressScale] on a row, [glassEntrance] on the
 * list — are `graphicsLayer` transforms: no measure, no relayout, no second pass, and both stop
 * costing anything once they finish.
 *
 * The screen's own logic is untouched: the same `when` over the same four states, the same
 * `testTag`s, the same strings. Every other screen should copy this shape rather than inventing
 * its own.
 *
 * @param onOpenStock invoked with a ticker when a row is tapped. The screen never navigates itself —
 *   `MiaNavHost` belongs to another agent. Always opens `Timeframe.D1` (plan.md decision D1).
 * @param onOpenSearch invoked from the empty state. Search is the app's front door (plan.md §1.1),
 *   so it is where "find your first ticker" belongs.
 * @param viewModel obtained with `hiltViewModel()`. The route takes no arguments, so there is no nav
 *   argument state to restore.
 */
@Composable
fun StockListScreen(
    onOpenStock: (symbol: String) -> Unit,
    onOpenSearch: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: StockListViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag(STOCK_LIST_TAG),
    ) {
        // The one blurred layer for this screen. Fixed, static content, blurred once and then served
        // from a cached layer — so scrolling costs nothing, whatever the length of the list.
        GlassBackdrop(modifier = Modifier.fillMaxSize())

        Column(
            modifier = Modifier.fillMaxSize(),
        ) {
            GlassTopBar {
                AddSymbolRow(
                    value = state.addField,
                    enabled = !state.isMutating,
                    onValueChange = viewModel::onAddFieldChanged,
                    onSubmit = viewModel::onAddConfirmed,
                )
            }

            state.notice?.let { notice ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = MiaSpacing.lg, vertical = MiaSpacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MiaSpacing.sm),
                ) {
                    Text(
                        text = notice,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .weight(1f)
                            .testTag(NOTICE_TAG),
                    )
                    TextButton(onClick = viewModel::onNoticeDismissed) {
                        Text(text = DISMISS_LABEL)
                    }
                }
            }

            state.coverageNotes.forEach { note ->
                Text(
                    text = coverageLine(note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .padding(horizontal = MiaSpacing.lg, vertical = MiaSpacing.xs)
                        .testTag(COVERAGE_TAG),
                )
            }

            when {
                state.errorReason != null && !state.hasRows -> ErrorState(
                    reason = state.errorReason ?: UNEXPECTED_ERROR,
                    onRetry = viewModel::onRetry,
                    modifier = Modifier.padding(horizontal = MiaSpacing.lg, vertical = MiaSpacing.sm),
                )

                state.isLoading && !state.hasRows -> LoadingState(label = LOADING_LABEL)

                state.isEmpty -> EmptyWatchlist(
                    onOpenSearch = onOpenSearch,
                    modifier = Modifier.padding(horizontal = MiaSpacing.lg, vertical = MiaSpacing.sm),
                )

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = MiaSpacing.lg,
                        end = MiaSpacing.lg,
                        top = MiaSpacing.sm,
                        bottom = MiaSpacing.xxl,
                    ),
                    verticalArrangement = Arrangement.spacedBy(MiaSpacing.sm),
                ) {
                    // First item, so breadth frames the rows rather than sitting above them, and so
                    // it scrolls with the list instead of pinning a second full-screen element. It
                    // renders only alongside rows: breadth with no watchlist to frame it answers a
                    // question the reader has not asked yet, and the empty state has its own say.
                    item(key = MARKET_PULSE_KEY) {
                        MarketPulseCard(breadth = state.breadth)
                    }
                    itemsIndexed(
                        items = state.rows,
                        key = { _, row -> row.symbol },
                    ) { index, row ->
                        WatchlistRowCard(
                            row = row,
                            index = index,
                            removeEnabled = !state.isMutating,
                            onOpenStock = onOpenStock,
                            onRemove = viewModel::onRemove,
                        )
                    }
                    item(key = FOOTER_KEY) { DataSourceFooter() }
                }
            }

            // A failed reload while rows are on screen is an error *about the refresh*, not about
            // the list, so it is a line under the rows rather than a card that replaces them.
            state.errorReason?.takeIf { state.hasRows }?.let { reason ->
                Text(
                    text = refreshFailedLine(reason),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .padding(horizontal = MiaSpacing.lg, vertical = MiaSpacing.xs)
                        .testTag(REFRESH_ERROR_TAG),
                )
            }
        }
    }
}

// ------------------------------------------------------------------------------ add row

/**
 * The inline add field.
 *
 * ### Why there is a text field here at all, when Search exists
 * Two different jobs. Search is for *finding* an instrument among 2,593, with ranking and near
 * misses. This is for someone who has just been handed a ticker — pasted from a message, read off a
 * screen — who already knows the string and wants it tracked. It deliberately has no ranking and no
 * suggestions: offering near matches here would suggest the app is guessing about a string it was
 * simply given.
 *
 * Pinned above the list rather than inside it, so adding never requires a scroll.
 */
@Composable
private fun AddSymbolRow(
    value: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = MiaSpacing.lg, vertical = MiaSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MiaSpacing.sm),
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .weight(1f)
                .testTag(ADD_FIELD_TAG),
            label = { Text(text = ADD_FIELD_LABEL) },
            singleLine = true,
            enabled = enabled,
        )
        OutlinedButton(
            onClick = onSubmit,
            enabled = enabled && value.isNotBlank(),
            modifier = Modifier.testTag(ADD_BUTTON_TAG),
        ) {
            Text(text = ADD_LABEL)
        }
    }
}

// ------------------------------------------------------------------------------- the rows

/**
 * One tracked symbol.
 *
 * ### Why the change is [ChangePercentText]
 * It carries a ▲/▼ glyph and a spoken direction word, so the day's move survives a colour-vision
 * deficiency, a washed-out screen and TalkBack. A red/green percentage is the failure plan.md §1.4's
 * accessibility reality is about, and a watchlist is the densest place it would bite.
 *
 * ### Why the flag chip is absent when the count is zero
 * A permanent "0 flags" badge on every row teaches the eye to ignore the badge, and the badge is the
 * thing a reader scans for. Silence is the correct empty state, and the coverage note above the
 * list is what stops that silence from reading as "all clear".
 *
 * This is deliberately the opposite of `RedFlagAccess`, which always renders its row even at zero
 * flags. The difference is density: a watchlist is a dense grid scanned for one thing, so a chip on
 * every row is noise — whereas `RedFlagAccess` sits once at the top of a single stock's tab, where
 * an absent row would leave the reader with no way to reach the report at all.
 *
 * ### The glass and the two interactions
 * A [GlassCard] rather than a `Card`: a translucent pane with a hairline over the screen's blurred
 * background, and — the important half — **no blur of its own**, because this is a `LazyColumn`
 * item and the list recycles.
 *
 * The press scale and the ripple read the *same* [MutableInteractionSource], so they cannot drift
 * apart: one `MutableInteractionSource` per row, handed to [glassPressScale] and to `clickable`.
 * Scale rather than size, so a press never remeasures or relayouts the row.
 */
@Composable
private fun WatchlistRowCard(
    row: WatchlistRow,
    index: Int,
    removeEnabled: Boolean,
    onOpenStock: (String) -> Unit,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }

    GlassCard(
        modifier = modifier
            .fillMaxWidth()
            .glassEntrance(index = index)
            .glassPressScale(interactionSource)
            .clickable(
                // `indication` has no default on the interactionSource overload of `clickable`, and
                // it is passed explicitly rather than as `null` so the ripple survives: a dip with no
                // ripple reads as a dropped frame on a slow device, which is exactly the impression
                // this screen cannot afford on a 2 GB phone.
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = { onOpenStock(row.symbol) },
            )
            .testTag(ROW_TAG_PREFIX + row.symbol),
        // What the Material `Card` this replaced set: the secondary tone for every `Text` inside
        // that does not pick its own colour.
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MiaSpacing.sm),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.symbol,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = row.name,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = formatPrice(row.lastClose),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                ChangePercentText(
                    changePercent = row.changePercent,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MiaSpacing.sm),
        ) {
            row.asOf?.let { asOf ->
                AsOfChip(
                    source = Source.NSE_BHAVCOPY,
                    asOf = asOf,
                    freshness = priceFreshness(asOf),
                )
            }
            if (row.redFlagCount > 0) {
                RedFlagCountChip(count = row.redFlagCount)
            }
            if (row.isFromCache && row.asOf != null) {
                Text(
                    text = CACHED_LABEL,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(
                onClick = { onRemove(row.symbol) },
                enabled = removeEnabled,
                modifier = Modifier.testTag(REMOVE_TAG_PREFIX + row.symbol),
            ) {
                Text(text = REMOVE_LABEL)
            }
        }
    }
}

/**
 * How many red flags this symbol has.
 *
 * A bordered label, like every other chip in the app: the border and the number are the encodings and
 * colour is only decoration, so the count survives a colour-vision deficiency and a low-quality
 * panel. The word is spelled out because "7" next to a red box means nothing on its own.
 */
@Composable
private fun RedFlagCountChip(count: Int, modifier: Modifier = Modifier) {
    Text(
        text = RED_FLAG_COUNT_LABEL(count),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier
            .clip(MaterialTheme.shapes.extraSmall)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.error,
                shape = MaterialTheme.shapes.extraSmall,
            )
            .padding(horizontal = MiaSpacing.sm, vertical = 2.dp)
            .semantics { contentDescription = RED_FLAG_COUNT_SPOKEN(count) },
    )
}

// --------------------------------------------------------------------------- empty states

/**
 * plan.md §1.1's first step, on a screen that has nothing to show yet.
 *
 * ### Why this is an explanation and not an apology
 * "No tracked symbols" tells the reader what the app has; it does not tell them what the app is *for*.
 * The body says what tracking changes (the nightly refresh and the red-flag count), and the button
 * sends them to Search, which is where plan.md §1.1's flow starts.
 */
@Composable
private fun EmptyWatchlist(
    onOpenSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag(EMPTY_TAG),
        verticalArrangement = Arrangement.spacedBy(MiaSpacing.md),
    ) {
        EmptyState(
            title = EMPTY_TITLE,
            body = EMPTY_BODY,
        )
        OutlinedButton(
            onClick = onOpenSearch,
            modifier = Modifier.testTag(EMPTY_SEARCH_TAG),
        ) {
            Text(text = EMPTY_SEARCH_LABEL)
        }
    }
}

// ------------------------------------------------------------------------------ freshness

/**
 * Freshness for an EOD close.
 *
 * `FreshnessKind.PRICE`, whose window is one day, because the close comes from the same daily
 * bhavcopy (plan.md §3.1) and a week-old close is genuinely not today's price for a list whose whole
 * premise is "read this after today's close".
 *
 * `today` is [FreshnessPolicy.todayInMarket] — the market's day, in `Asia/Kolkata` — so the same
 * instant cannot produce two different ages in two time zones.
 */
private fun priceFreshness(asOf: java.time.LocalDate): Freshness = FreshnessPolicy.inspect(
    asOf = asOf,
    today = FreshnessPolicy.todayInMarket(),
    kind = FreshnessKind.PRICE,
)

// ------------------------------------------------------------------------------ copy tokens

/** `1 red flag` / `3 red flags`. */
private fun RED_FLAG_COUNT_LABEL(count: Int): String =
    if (count == 1) "1 red flag" else "$count red flags"

private fun RED_FLAG_COUNT_SPOKEN(count: Int): String =
    if (count == 1) "One red flag. Open the stock to see the numbers behind it."
    else "$count red flags. Open the stock to see the numbers behind them."

private fun coverageLine(note: String): String = "Red-flag check incomplete — $note"

private fun refreshFailedLine(reason: String): String =
    "Showing the last stored data. A refresh did not succeed: $reason"

private const val STOCK_LIST_TAG: String = "mia_stocklist"
private const val ADD_FIELD_TAG: String = "mia_stocklist_add_field"
private const val ADD_BUTTON_TAG: String = "mia_stocklist_add"
private const val NOTICE_TAG: String = "mia_stocklist_notice"
private const val COVERAGE_TAG: String = "mia_stocklist_coverage"
private const val REFRESH_ERROR_TAG: String = "mia_stocklist_refresh_error"
private const val EMPTY_TAG: String = "mia_stocklist_empty"
private const val EMPTY_SEARCH_TAG: String = "mia_stocklist_empty_search"
private const val ROW_TAG_PREFIX: String = "mia_stocklist_row_"
private const val REMOVE_TAG_PREFIX: String = "mia_stocklist_remove_"

private const val ADD_FIELD_LABEL: String = "Add a ticker"
private const val ADD_LABEL: String = "Add"
private const val REMOVE_LABEL: String = "Remove"
private const val DISMISS_LABEL: String = "Dismiss"
private const val CACHED_LABEL: String = "from the on-device cache"
private const val LOADING_LABEL: String = "Reading your tracked symbols"
private const val EMPTY_TITLE: String = "Nothing tracked yet"
private const val EMPTY_BODY: String =
    "Track a ticker to see its last close, its day change and its red flags here after every " +
        "market close. Search works offline over the bundled NSE symbol master, so it needs no " +
        "network."
private const val EMPTY_SEARCH_LABEL: String = "Search for a stock"
private const val UNEXPECTED_ERROR: String = "the tracked list could not be read"

private const val FOOTER_KEY: String = "stocklist.footer"

/** Lazy-list key for the market-pulse card. Stable so a rotation does not rebuild the row. */
private const val MARKET_PULSE_KEY: String = "stocklist.marketPulse"