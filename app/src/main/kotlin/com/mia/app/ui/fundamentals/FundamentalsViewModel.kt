package com.mia.app.ui.fundamentals

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mia.app.domain.model.Confidence
import com.mia.app.domain.model.Merged
import com.mia.app.domain.model.Ratios
import com.mia.app.domain.model.RedFlag
import com.mia.app.domain.model.Sector
import com.mia.app.domain.model.Source
import com.mia.app.domain.ratios.Computation
import com.mia.app.domain.ratios.Freshness
import com.mia.app.domain.ratios.FreshnessKind
import com.mia.app.domain.ratios.FreshnessPolicy
import com.mia.app.domain.ratios.RatioComputation
import com.mia.app.domain.ratios.RatioId
import com.mia.app.domain.ratios.RatioPriority
import com.mia.app.domain.ratios.RatioSlot
import com.mia.app.domain.ratios.RatioState
import com.mia.app.domain.ratios.RatioUnit
import com.mia.app.domain.ratios.RatioAvailability
import com.mia.app.domain.ratios.QuarterlyComparison
import com.mia.app.domain.ratios.QuarterlyMetric
import com.mia.app.domain.ratios.QuarterlyResults
import com.mia.app.domain.ratios.SectorResolution
import com.mia.app.domain.ratios.SectorResolver
import com.mia.app.domain.ratios.ShareholdingMetrics
import com.mia.app.domain.ratios.Trend
import com.mia.app.domain.ratios.withFreshness
import com.mia.app.domain.redflag.RuleSkip
import com.mia.app.ui.nav.ARG_SYMBOL
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import java.time.LocalDate

/**
 * One number on a ratio row, with its own availability, its own provenance and its own divergence.
 *
 * ### Why a reading is not just a `Double`
 * plan.md §7.3 is about three visually different absences and one number cannot carry any of them:
 *
 * | [RatioAvailability.state] | the reader must see |
 * |---|---|
 * | `AVAILABLE` | the figure |
 * | `STALE` | the figure, marked old, with its age |
 * | `SUPPRESSED_FOR_SECTOR` | *"Not applicable for `<Sector>`"* |
 * | `NOT_SOURCED` | *"Not available from our sources"* |
 * | `MISSING` | *"Not published this time"* |
 *
 * So the state travels with the value, and [RatioAvailability]'s own `init` refuses to let a
 * suppressed/unsourced/missing ratio carry one. The screen cannot print `0.00` for a bank's D/E even
 * by accident.
 */
data class RatioReading(
    val id: RatioId,
    val unit: RatioUnit,
    val availability: RatioAvailability<Double>,
    /** Where the figure came from. plan.md §1.4 rule 3 makes this mandatory on every figure. */
    val source: Source,
    /** The page's date, or `null` when the source published none. Rendered by `AsOfChip`. */
    val asOf: LocalDate?,
    /** The other source's value when the sources disagreed by >5% (plan.md §5.1). */
    val divergence: RatioDivergence? = null,
    /**
     * plan.md §5.1's conflict state for this field. `STALE` outranks `CONFLICT`: two old numbers are
     * two old numbers, and `FreshnessPolicy.confidenceFor` already prefers staleness for exactly that
     * reason.
     */
    val confidence: Confidence = Confidence.SINGLE,
) {
    /** True when there is a figure to print, fresh or stale. */
    val hasValue: Boolean get() = availability.isRenderable

    /** The value, or `null`. */
    val valueOrNull: Double? get() = availability.value

    /** `true` when the sector deliberately removed this ratio (plan.md §7.3). */
    val isSuppressed: Boolean get() = availability.state == RatioState.SUPPRESSED_FOR_SECTOR

    /** `true` when no source of ours publishes this ratio at all (plan.md §3.3, §3.7). */
    val isNotSourced: Boolean get() = availability.state == RatioState.NOT_SOURCED

    /** `true` when the ratio is meaningful here and this page simply did not carry it. */
    val isMissing: Boolean get() = availability.state == RatioState.MISSING

    /**
     * The `Merged` view of this reading, for [com.mia.app.ui.common.DivergenceChip].
     *
     * `null` unless there is a value, a date and an alternate — the three things plan.md §5.1's
     * two-number display needs. `Merged.asOf` is non-nullable, so a figure with no publication date
     * cannot be rendered as a conflict at all, which is correct: an undated second number is not a
     * comparable one.
     */
    fun mergedOrNull(): Merged<Double>? {
        val value = availability.value ?: return null
        val date = asOf ?: return null
        val other = divergence ?: return null
        return Merged(
            value = value,
            source = source,
            confidence = confidence,
            asOf = date,
            alternate = other.alternateValue,
            alternateSource = other.alternateSource,
        )
    }
}

/**
 * One plan.md §7.2 card.
 *
 * ### Why a card, not a flat list of ratios
 * §7.2's rows are not one-ratio-per-row: row 3 is *"Debt/Equity + interest coverage"* and row 12 is
 * *"Dividend yield + payout"*. `RatioSlot.companions` already models that, and collapsing it would
 * lose the pairing the plan puts in one card — and would make a suppressed D/E look like an
 * unrelated gap rather than half of a card whose other half was also removed.
 */
data class RatioCard(
    /** The `RatioSlot` from `RatioPriority`, carried so the plan's own order is traceable. */
    val slot: RatioSlot,
    /** The primary reading first, then its companions, in the plan's order. */
    val readings: List<RatioReading>,
    /** `true` when §7.3 added or promoted this card for the resolved sector. */
    val isSectorSpecific: Boolean,
    /**
     * `true` for the card plan.md does not rank at all.
     *
     * Distinct from [isSectorSpecific] because the two are added for opposite reasons — one by the
     * sector, one *despite* every sector — and a reader who cannot tell them apart cannot tell
     * whether a card is absent because of their company's sector or because of the app's coverage.
     */
    val isSupplementary: Boolean,
    /** The `Trend` for the card when the plan asks for one and one could be computed. */
    val trend: Trend? = null,
    /**
     * Why there is no trend, when the plan asks for one.
     *
     * Non-null with [trend] non-null means the trend exists and this is its provenance note.
     * Non-null with [trend] null means *"we looked and there is no series"* — which is a different
     * statement from not having looked, and plan.md §7.2 ranks 2 and 7 on the trend itself.
     */
    val trendNote: String? = null,
) {
    /** The card's headline, from the slot's primary ratio. */
    val title: String get() = slot.title

    /** plan.md §7.2's 1–12 rank, or `0` for a §7.3-only card. */
    val planRank: Int get() = slot.planRank

    /** `true` when the card wants a 5-year trend beside it. */
    val wantsTrend: Boolean get() = slot.id in TREND_RATIOS

    /** True when no reading on this card has a figure — the whole card is absences. */
    val isAllBlank: Boolean get() = readings.none { it.hasValue }

    companion object {
        /**
         * plan.md §7.2 asks for a 5-year trend on exactly two ratios: rank 2 (operating margin) and
         * rank 7 (gross margin). Listed here so "which rows get a sparkline" is one line of data and
         * not a `when` buried in the composable.
         */
        val TREND_RATIOS: Set<RatioId> = setOf(RatioId.OPERATING_MARGIN, RatioId.GROSS_MARGIN)
    }
}

/**
 * One quarterly line: the latest figure, its two comparisons, and the series to draw.
 *
 * ### Why this is its own card type and not another [RatioCard]
 * A [RatioCard] is one row of plan.md §7.2's ranked list, with a plan rank and a sector override
 * behind it. The quarterly card has neither: `QuarterlyMetric` is a column of the `quarters` table,
 * not a ratio in §7.2's ordering, so putting it in a `RatioSlot` would give it a rank the plan
 * never assigned. It borrows the same visual treatment — title, rationale, provenance chip — and
 * keeps its own honesty rules, which are stated on [QuarterlyCardRow].
 */
data class QuarterlyCardRow(
    val metric: QuarterlyMetric,
    val comparison: QuarterlyComparison,
    /** Source + as-of, so every figure carries its provenance like every ratio row does. */
    val source: Source,
    val asOf: LocalDate?,
) {
    val title: String get() = metric.title

    /**
     * True when the page published no such row at all.
     *
     * HDFCBANK publishes no `Operating Profit` row, and this is the state its card renders: the
     * reason, not a zero and not a substituted neighbouring line.
     */
    val isAbsent: Boolean get() = comparison.isEmpty

    /** True when at least one of the two comparisons could be made. */
    val hasAnyComparison: Boolean get() = comparison.comparisonCount > 0
}

/**
 * What the Fundamentals tab renders.
 *
 * ### `cards` is already in plan.md's order and must not be re-sorted
 * [RatioPriority.order] composes §7.2's twelve rows with §7.3's five overrides — inserting the
 * sector's own slots, dropping the suppressed ones, applying the promotions — and then appends the
 * supplementary ownership card, which the plan does not rank — and it is the domain's single
 * authority. The ViewModel maps that list, card for card, and the screen renders it in index
 * order. Sorting here would undo §7.3.
 */
data class FundamentalsUiState(
    val symbol: String = "",
    /** The sector the list was composed for. [Sector.OTHER] means "generic list". */
    val sector: Sector = Sector.OTHER,
    /** The sector decision and its evidence. Never null after a successful load. */
    val sectorResolution: SectorResolution? = null,
    val cards: List<RatioCard> = emptyList(),
    /**
     * The quarterly results, one card per published line, after the ratio list.
     *
     * Placed after rather than between the ratio cards because it answers a different question —
     * *what happened in the last four quarters* rather than *how does this business compare* — and
     * a reader looking for ROCE should not have to scroll past three sparklines to find it.
     */
    val quarterly: List<QuarterlyCardRow> = emptyList(),
    val isLoading: Boolean = true,
    /** The sentence to show in `ErrorState`, or `null`. */
    val errorReason: String? = null,
    /**
     * Set when the page has never been fetched for this symbol. **Not** an error: a symbol the reader
     * has just opened for the first time legitimately has nothing cached, and a red banner would say
     * "this failed" about something that has not been attempted.
     */
    val notFetchedNote: String? = null,
    val redFlags: List<RedFlag> = emptyList(),
    val skippedRules: List<RuleSkip> = emptyList(),
    val ruleFailures: Map<String, String> = emptyMap(),
    /** The page's as-of date and its freshness verdict. Drives the header's staleness line. */
    val asOf: LocalDate? = null,
    val freshness: Freshness? = null,
    /** plan.md §12: parser sections that were missing, so the screen can name them. */
    val missingSections: List<String> = emptyList(),
    /** plan.md §12: parser notes, verbatim. */
    val parserNotes: List<String> = emptyList(),
) {
    /** `true` when the ratio list is the generic one because the sector could not be resolved. */
    val isSectorUnresolved: Boolean get() = sectorResolution?.resolved != true || sector == Sector.OTHER

    /** plan.md §7.3's one-line statement of what the override did, when one applied. */
    val overrideNote: String?
        get() {
            val resolution = sectorResolution
            if (resolution == null || !resolution.usesSectorOverrides) return null
            val note = RatioPriority.sectorOverrides(sector).planNote
            return note.takeIf { it.isNotBlank() }
        }

    /** `true` when the red-flag banner must not imply a clean bill of health. */
    val flagCoverageIncomplete: Boolean get() = skippedRules.isNotEmpty() || ruleFailures.isNotEmpty()

    /** How many figures are shown stale. Shown in the header so the reader knows before scrolling. */
    val staleCount: Int get() = cards.sumOf { card -> card.readings.count { it.availability.state == RatioState.STALE } }

    /** How many rows are a sector suppression rather than a data gap (plan.md §7.3). */
    val suppressedCount: Int
        get() = cards.sumOf { card -> card.readings.count { it.isSuppressed } }

    /** How many rows we simply cannot source (plan.md §3.3, §3.7). */
    val notSourcedCount: Int
        get() = cards.sumOf { card -> card.readings.count { it.isNotSourced } }

    /**
     * True when the tab must print plan.md §12's "what was not checked" panel.
     *
     * True for any gap, not just a red-flag gap: an unparsed section and a ratio we cannot source are
     * the same class of fact to the reader — the app did not look at something — and both read as a
     * silent absence if they are not named.
     */
    val needsCoverageSection: Boolean
        get() = flagCoverageIncomplete ||
            missingSections.isNotEmpty() ||
            parserNotes.isNotEmpty() ||
            cards.isEmpty()

    /**
     * True when the quarterly section has something to say, including when all it can say is why.
     *
     * plan.md §12: a section that produced nothing must still be reported, because "the app did not
     * check the quarters" and "the quarters were checked and are unremarkable" look identical from
     * outside. HDFCBANK's absent operating-profit card is the case that makes this non-obvious.
     */
    val needsQuarterlySection: Boolean get() = quarterly.isNotEmpty()

    companion object {
        fun initial(symbol: String): FundamentalsUiState = FundamentalsUiState(symbol = symbol)
    }
}

/**
 * The Fundamentals tab's ViewModel (plan.md P5).
 *
 * ### Why the ratio list is built here and not in the screen
 * Composing §7.2 with §7.3, judging each row's state, and computing the two margin trends are
 * decisions, not layout. Doing them in the ViewModel means the screen has one job and the decisions
 * are reviewable without a composition, which is why this file is where the plan's rules are
 * transcribed.
 *
 * ### Precedence: `Ratios` first, then the computation
 * `Ratios` is plan.md §5.1's **merged** output — the per-field merge of screener.in and Yahoo — so
 * where it has a field, that field is the app's answer and the single-source computation is only a
 * fallback for fields the page's `top-ratios` card does not carry (debt/equity, interest coverage,
 * FCF/PAT, revenue CAGR, dividend payout, promoter holding). Computing first would throw away the
 * merge on every field the page happens to publish, which is exactly what §5.1 says not to do.
 */
@HiltViewModel
class FundamentalsViewModel @Inject constructor(
    private val dataSource: FundamentalsDataSource,
    savedStateHandle: SavedStateHandle,
    @FundamentalsBackgroundDispatcher private val backgroundDispatcher: CoroutineDispatcher,
) : ViewModel() {

    /**
     * The symbol, from the nav argument.
     *
     * Read out of [SavedStateHandle] rather than passed in, so it is restored by the navigation
     * component itself after process death — the argument *is* the saved state, and a field on this
     * class would be lost with a `Field is transient`-shaped bug rather than a readable empty symbol.
     */
    private val symbol: String = savedStateHandle.get<String>(ARG_SYMBOL)
        ?.trim()
        ?.uppercase()
        .orEmpty()

    private val _uiState = MutableStateFlow(FundamentalsUiState.initial(symbol))
    val uiState: StateFlow<FundamentalsUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null

    init {
        load()
    }

    /** Re-runs the load. Offered by the error state. */
    fun onRetry() {
        load()
    }

    private fun load() {
        loadJob?.cancel()
        if (symbol.isEmpty()) {
            // A route that arrived without a symbol. Say so rather than searching for the empty
            // string, which would resolve to nothing and look like a data failure.
            _uiState.update {
                it.copy(isLoading = false, errorReason = MISSING_SYMBOL_REASON)
            }
            return
        }
        _uiState.update { current ->
            current.copy(isLoading = true, errorReason = null, notFetchedNote = null)
        }
        loadJob = viewModelScope.launch {
            try {
                val loaded = withContext(backgroundDispatcher) { fetch() }
                _uiState.value = loaded
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (unavailable: FundamentalsUnavailableException) {
                if (unavailable.isNotPublished) {
                    _uiState.update { current ->
                        current.copy(
                            isLoading = false,
                            errorReason = null,
                            notFetchedNote = unavailable.reason,
                        )
                    }
                } else {
                    _uiState.update { current ->
                        current.copy(isLoading = false, errorReason = unavailable.reason)
                    }
                }
            } catch (failure: Throwable) {
                _uiState.update { current ->
                    current.copy(
                        isLoading = false,
                        errorReason = failure.message ?: UNEXPECTED_FAILURE,
                    )
                }
            }
        }
    }

    /** The whole tab's load, on the background dispatcher. */
    private suspend fun fetch(): FundamentalsUiState {
        val page = dataSource.fundamentals(symbol)
        val flags = dataSource.redFlags(symbol)
        val divergenceList = dataSource.divergences(symbol)

        val snapshot = page.snapshot
        // `withFreshness` is the one place `Confidence.STALE` can be applied (plan.md §10), and it is
        // idempotent, so calling it on every load is free and cannot double-apply.
        val ratios = snapshot.ratios.withFreshness()
        val freshness = FreshnessPolicy.inspect(
            asOf = ratios.asOf,
            today = FreshnessPolicy.todayInMarket(),
            kind = FreshnessKind.FUNDAMENTALS_SNAPSHOT,
        )
        val computed = computeAll(page)
        val sector = page.sectorResolution.sector

        val cards = RatioPriority.order(sector).map { slot ->
            RatioCard(
                slot = slot,
                readings = slot.ids.map { id -> reading(sector, ratios, freshness, computed, divergenceList, id) },
                isSectorSpecific = slot.isSectorSpecific,
                isSupplementary = slot.isSupplementary,
                trend = trendFor(slot, computed),
                trendNote = trendNoteFor(slot, computed),
            )
        }

        return FundamentalsUiState(
            symbol = symbol,
            sector = sector,
            sectorResolution = page.sectorResolution,
            cards = cards,
            quarterly = quarterlyRows(page, ratios),
            isLoading = false,
            errorReason = null,
            notFetchedNote = null,
            redFlags = flags.flags,
            skippedRules = flags.skipped,
            ruleFailures = flags.failures,
            asOf = ratios.asOf,
            freshness = freshness,
            missingSections = snapshot.health.sectionsMissing,
            parserNotes = snapshot.health.notes,
        )
    }

    // ------------------------------------------------------------------- ratio derivation

    /**
     * The quarterly cards, one per published line.
     *
     * ### Why this is built from [QuarterlyResults.all] and not assembled here
     * The QoQ/YoY arithmetic, the gap detection and the "which row is absent" decision all live in
     * `:domain` where they are unit-tested against synthetic inputs; repeating any of them in a
     * ViewModel would be a second implementation that could disagree with the first. All this does
     * is attach provenance, which is a display concern and belongs on this side.
     *
     * ### Why an absent line still produces a card
     * Returning only the metrics that produced a figure would make HDFCBANK's operating-profit
     * card simply not exist, and a reader comparing five banks would see four cards for one of them
     * and no explanation. The card carries the reason instead.
     */
    private fun quarterlyRows(page: FundamentalsPage, ratios: Ratios): List<QuarterlyCardRow> =
        QuarterlyResults.all(page.snapshot.quarters).map { comparison ->
            QuarterlyCardRow(
                metric = comparison.metric,
                comparison = comparison,
                source = ratios.source,
                asOf = ratios.asOf,
            )
        }

    /**
     * The `RatioComputation` results this tab needs, computed once per load.
     *
     * Grouped so a single `Trend` walk over the annual columns happens once rather than once per
     * ratio that asks for one, and so the screen never has to know which ratios are computed.
     */
    private data class Computed(
        val debtToEquity: Computation<Double>,
        val interestCoverage: Computation<Double>,
        val fcfToPat: Computation<Double>,
        val revenueCagr: Computation<Double>,
        val promoterHolding: Computation<Double>,
        val dividendPayout: Computation<Double>,
        val operatingMarginTrend: Computation<Trend>,
        val grossMarginTrend: Computation<Trend>,
        val fiiHolding: Computation<Double>,
        val diiHolding: Computation<Double>,
        val fiiQuarterlyChange: Computation<Double>,
    )

    private fun computeAll(page: FundamentalsPage): Computed {
        val derived = page.snapshot.cashFlowDerived
        val sector = page.sectorResolution.sector
        // Resolved once here so the three ownership readings cannot disagree about which quarter is
        // the latest — the failure mode of reading a quarterly card three times independently.
        val ownership = ShareholdingMetrics.of(page.shareholdingPeriods, page.snapshot.shareholding)
        return Computed(
            debtToEquity = RatioComputation.debtToEquity(derived, sector),
            interestCoverage = RatioComputation.interestCoverage(derived, sector),
            fcfToPat = RatioComputation.fcfToPat(derived),
            revenueCagr = RatioComputation.revenueCagr(derived, RatioComputation.CAGR_SHORT_YEARS),
            promoterHolding = RatioComputation.promoterHolding(
                page.shareholdingPeriods,
                page.snapshot.shareholding,
            ),
            dividendPayout = RatioComputation.dividendPayout(
                page.profitLossPeriods,
                page.snapshot.profitLoss,
            ),
            operatingMarginTrend = RatioComputation.operatingMarginTrend(
                page.profitLossPeriods,
                page.snapshot.profitLoss,
                RatioComputation.TREND_YEARS,
            ),
            // No source of ours publishes a cost-of-goods line, so the series is empty and the
            // function answers with the reason (plan.md §3.3). Passing `emptyMap()` is not a stub:
            // it is the truthful input, and the day a source supplies the line this becomes a
            // five-point trend with no change to this file.
            grossMarginTrend = RatioComputation.grossMarginTrend(
                grossMarginByYear = emptyMap(),
                years = RatioComputation.TREND_YEARS,
            ),
            fiiHolding = ShareholdingMetrics.fiiHolding(ownership),
            diiHolding = ShareholdingMetrics.diiHolding(ownership),
            fiiQuarterlyChange = ShareholdingMetrics.fiiHoldingQuarterlyChange(ownership),
        )
    }

    /**
     * One row's availability, provenance and divergence.
     *
     * ### The three absences, produced by the domain and only annotated here
     * [RatioPriority.availability] is what decides between "the sector removed this" and "no source
     * publishes this" and "we have it": `SUPPRESSED_FOR_SECTOR` from §7.3's suppression table,
     * `NOT_SOURCED` for [RatioId.isNeverSourced] and for a §7.3-only slot, and `AVAILABLE`/`MISSING`
     * from the value in hand. This function never chooses — it passes the value and the reason and
     * lets the domain apply §7.3, which is why a bank can never be handed a debt/equity even if a
     * caller hands one in.
     */
    private fun reading(
        sector: Sector,
        ratios: Ratios,
        freshness: Freshness,
        computed: Computed,
        divergences: List<RatioDivergence>,
        id: RatioId,
    ): RatioReading {
        val value = valueOf(ratios, computed, id)
        val note = noteFor(computed, id)
        val base = RatioPriority.availability(sector, id, value, note)
        val divergence = divergences.firstOrNull { it.id == id }
        val effective = if (freshness.isStale) {
            base.value?.let { RatioAvailability.stale(it, staleNote()) } ?: base
        } else {
            base
        }
        return RatioReading(
            id = id,
            unit = id.unit,
            availability = effective,
            source = ratios.source,
            asOf = ratios.asOf,
            divergence = divergence,
            // Staleness outranks everything, then a per-field divergence, then whatever the plan.md
            // §5.1 merge already concluded about this page — which may itself be `CONFLICT` for a
            // field the adapter could not attribute individually. Collapsing that last case to
            // `SINGLE` would understate a conflict the merge did find.
            confidence = when {
                freshness.isStale -> Confidence.STALE
                divergence != null -> Confidence.CONFLICT
                else -> ratios.confidence
            },
        )
    }

    /**
     * The figure for [id], `Ratios` first and the computation second.
     *
     * The `else` branch is `null` on purpose: the §7.3-only slots (a bank's NIM, an IT company's
     * attrition) are all `RatioId.isSectorSpecific`, which
     * [RatioPriority.sectorStateFor] already classifies as `NOT_SOURCED`, so returning `null` here
     * produces the correct state rather than an accidental `MISSING`.
     */
    private fun valueOf(ratios: Ratios, computed: Computed, id: RatioId): Double? = when (id) {
        RatioId.ROCE -> ratios.roce
        RatioId.ROE -> ratios.roe
        RatioId.DEBT_TO_EQUITY -> ratios.debtToEquity ?: computed.debtToEquity.value
        RatioId.INTEREST_COVERAGE -> ratios.interestCoverage ?: computed.interestCoverage.value
        RatioId.FCF_VS_PAT -> computed.fcfToPat.value
        RatioId.REVENUE_CAGR -> ratios.revenueCagr3y ?: computed.revenueCagr.value
        RatioId.GROSS_MARGIN -> ratios.grossMargin
        RatioId.PE_VS_SECTOR_MEDIAN -> ratios.pe
        RatioId.PB -> ratios.pb
        RatioId.EV_TO_EBITDA -> ratios.evToEbitda
        RatioId.PROMOTER_HOLDING -> ratios.promoterHolding ?: computed.promoterHolding.value
        RatioId.DIVIDEND_YIELD -> ratios.dividendYield
        RatioId.DIVIDEND_PAYOUT -> computed.dividendPayout.value
        // The trend's own latest point is the operating margin's figure, and it is the better one:
        // it comes from the annual statement rather than from the `top-ratios` card.
        RatioId.OPERATING_MARGIN -> computed.operatingMarginTrend.value?.latest?.value
            ?: ratios.operatingMargin
        // Read straight off the shareholding card, never from `Ratios`: the merged model has no
        // column for them, and `RatioMapper` does not write them because nothing downstream of the
        // cache consumed them until now. See `VerificationSources` for what a reader can check
        // these against, since the page's own as-of date is the fetch date rather than the quarter.
        RatioId.FII_HOLDING -> computed.fiiHolding.value
        RatioId.DII_HOLDING -> computed.diiHolding.value
        RatioId.FII_TREND -> computed.fiiQuarterlyChange.value
        else -> null
    }

    /**
     * The provenance note for [id], preferring the computation's own explanation.
     *
     * `RatioComputation` writes notes like *"revenue 2018 ₹100 Cr → 2026 ₹133.1 Cr over 8 year(s)"*
     * and *"no fiscal year publishes both 'Borrowings' and 'Equity Capital + Reserves'"*. Those are
     * the sentences that make a number auditable, so they are passed straight through to the row's
     * note line rather than replaced with anything the UI would have to invent.
     */
    private fun noteFor(computed: Computed, id: RatioId): String? = when (id) {
        RatioId.DEBT_TO_EQUITY -> computed.debtToEquity.note
        RatioId.INTEREST_COVERAGE -> computed.interestCoverage.note
        RatioId.FCF_VS_PAT -> computed.fcfToPat.note
        RatioId.REVENUE_CAGR -> computed.revenueCagr.note
        RatioId.PROMOTER_HOLDING -> computed.promoterHolding.note
        RatioId.DIVIDEND_PAYOUT -> computed.dividendPayout.note
        // The trend's own note names both endpoints and the years skipped, which is strictly more
        // informative than `ratios.operatingMargin`'s bare number — so it serves for the present and
        // for the miss alike, and a miss is exactly when the reader needs the reason.
        RatioId.OPERATING_MARGIN -> computed.operatingMarginTrend.note
        // Each ownership note names the quarter that published its figure and, for the change, both
        // endpoints. That is what makes a percentage-point figure auditable — "down 1.5pp" is a
        // different statement when the two quarters are six months apart.
        RatioId.FII_HOLDING -> computed.fiiHolding.note
        RatioId.DII_HOLDING -> computed.diiHolding.note
        RatioId.FII_TREND -> computed.fiiQuarterlyChange.note
        else -> null
    }

    /**
     * The trend for a card, or `null`.
     *
     * Only the two cards plan.md §7.2 ranks on a trend ever get one, and only when
     * [RatioComputation] produced it. A card that wants a trend and did not get one says so in
     * [RatioCard.trendNote] — never shows an empty chart frame.
     */
    private fun trendFor(slot: RatioSlot, computed: Computed): Trend? = when (slot.id) {
        RatioId.OPERATING_MARGIN -> computed.operatingMarginTrend.value
        RatioId.GROSS_MARGIN -> computed.grossMarginTrend.value
        else -> null
    }

    private fun trendNoteFor(slot: RatioSlot, computed: Computed): String? = when (slot.id) {
        RatioId.OPERATING_MARGIN -> computed.operatingMarginTrend.note
        RatioId.GROSS_MARGIN -> computed.grossMarginTrend.note
        else -> null
    }

    /**
     * Why every stale figure on this page is stale.
     *
     * `RatioAvailability`'s `init` requires a non-blank note for every non-`AVAILABLE` state, and
     * this is the fact the reader needs: the page exceeded plan.md §10's window for a fundamentals
     * snapshot. The window itself is named rather than left for the reader to infer.
     */
    private fun staleNote(): String =
        "older than the ${FreshnessKind.FUNDAMENTALS_SNAPSHOT.windowDays}-day window for " +
            "${FreshnessKind.FUNDAMENTALS_SNAPSHOT.label} (plan.md §10); the figure is still true " +
            "of its own date, it is not current"

    private companion object {
        const val UNEXPECTED_FAILURE = "the data layer failed without a reason"
        const val MISSING_SYMBOL_REASON = "this screen was opened without a symbol"
    }
}

/** Re-exported so the screen's copy can name the sector's display form without another import. */
internal fun Sector.displayNameForUser(): String = SectorResolver.displayNameOf(this)