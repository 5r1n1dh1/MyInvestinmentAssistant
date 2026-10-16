package com.mia.app.data.fundamentals

import com.mia.app.data.fundamentals.ScreenerHtml.ParsedTable
import com.mia.app.domain.model.Confidence
import com.mia.app.domain.model.FundamentalsSnapshot
import com.mia.app.domain.model.ParserHealth
import com.mia.app.domain.model.Ratios
import com.mia.app.domain.model.Sector
import com.mia.app.domain.model.Source
import com.mia.app.domain.ratios.SectorResolver
import com.mia.app.domain.ratios.ShareholdingPattern
import org.jsoup.nodes.Document
import java.time.LocalDate
import java.time.ZoneId

/**
 * What a fundamentals parse produced, with its health attached.
 *
 * A bare `FundamentalsSnapshot` would let a caller mistake "the page changed" for "this company
 * has no red flags" — the exact failure plan.md §12 calls the most dangerous one. [health] is
 * therefore part of the return type and never optional.
 *
 * [sections] carries the raw parsed tables so the red-flag phase and the UI's evidence view can
 * read a published figure (plan.md §8.3: a flag must show the actual numbers) without re-parsing
 * the HTML or widening the domain's snapshot type.
 */
data class FundamentalsParseResult(
    val snapshot: FundamentalsSnapshot,
    val health: ParserHealth,
    /** HTTP status when the result came from a fetch; `null` for a local parse. */
    val httpCode: Int? = null,
    /** How many requests were issued, including retries. */
    val attempts: Int = 1,
    /** The URL that produced this result. Exactly one permitted shape ever reaches it. */
    val url: String? = null,
    /** Raw tables by section id, for evidence display. Absent sections are simply not present. */
    val sections: Map<String, ParsedTable> = emptyMap(),
    /**
     * The `ratios` card — debtor days, inventory days, days payable, ROCE/ROE.
     *
     * Kept beside [sections] rather than inside the snapshot because it is a *series* rather than
     * a statement row, and `FundamentalsSnapshot` is a domain type this parser does not own. It is
     * a section of the page like any other, so [health] reports it: [RatiosCard.isAbsent] and
     * [RatiosCard.isEmpty] say which of the two failure modes occurred.
     */
    val ratiosCard: RatiosCard = RatiosCard.EMPTY,
    /** [ratiosCard] keyed by fiscal year, plus the domain's `RatioExtras` / `RatiosCardSeries`. */
    val ratioCard: RatioCardDerived = RatioCardDerived(),
    /**
     * The `shareholding` card's FII / DII / promoter rows, resolved to quarters.
     *
     * Carried for the same reason [ratioCard] is: the quarterly **column headers** are what date a
     * shareholding figure, and they are not part of `AnnualRow`, so a caller that only gets the
     * snapshot cannot date one. Without this the parser would read the card and every consumer would
     * have to guess a quarter — which is what the FII/DII card refuses to do.
     */
    val shareholdingPattern: ShareholdingPattern = ShareholdingPattern.EMPTY,
) {
    val isOk: Boolean get() = health.ok
}

/**
 * The single entry point for turning screener.in HTML into a [FundamentalsSnapshot].
 *
 * ### What "ok" means here
 * `ok` is true only when **every** section named in [SECTION_IDS] was found *and* each one
 * produced at least one data row. A section that exists in the markup but parses to zero rows is
 * reported in [ParserHealth.sectionsMissing], because "present but empty" is indistinguishable
 * from "the page changed" — the same reasoning plan.md §12 gives for the silent-empty rule.
 * It is strict on purpose: a fundamentals tab that shows four of six statements should say so.
 *
 * ### The `ratios` card counts as a section
 * plan.md §3.3 lists six sections because the card was unparsed when that list was written. Now
 * that [RatiosCardParser] reads it, a page with no `ratios` card is a **missing section**, not a
 * quietly absent series: debtor days and inventory days are the inputs to plan.md §8.1's two
 * working-capital red flags, and a fundamentals tab that silently shows neither is the failure
 * mode §12 exists to prevent. [SECTION_IDS] therefore extends [ScreenerHtml.SECTION_IDS] rather
 * than replacing it, and the card sits between `cash-flow` and `shareholding` — its real position
 * in the page markup — so a truncated-page diagnostic reads in document order.
 *
 * ### It never throws
 * Malformed HTML, an error page, a truncated download and a total redesign of the markup all
 * come back as a not-ok result carrying notes. There is no code path from a bad page to an
 * exception, because every caller would eventually turn that exception into a blank screen.
 */
object FundamentalsParserFacade {

    /**
     * Every section a fundamentals parse accounts for, in page order.
     *
     * `[ScreenerHtml.SECTION_IDS]` plus the `ratios` card. A superset, so
     * `ScreenerInSource.isComplete` — which checks the older six — stays satisfied by a page that
     * also carries the card.
     */
    val SECTION_IDS: List<String> =
        ScreenerHtml.SECTION_IDS.filterNot { it == ScreenerHtml.SHAREHOLDING } +
            RatiosCardParser.SECTION_ID +
            listOf(ScreenerHtml.SHAREHOLDING)

    /** Indian market time is what "as of" means for this app. */
    private val MARKET_ZONE: ZoneId = ZoneId.of("Asia/Kolkata")

    fun todayInIndia(): LocalDate = LocalDate.now(MARKET_ZONE)

    fun parse(html: String, symbol: String, asOf: LocalDate = todayInIndia()): FundamentalsParseResult =
        parse(ScreenerHtml.document(html), symbol, asOf)

    fun parse(doc: Document, symbol: String, asOf: LocalDate = todayInIndia()): FundamentalsParseResult {
        val topRatios = TopRatiosParser.parse(doc)
        val quarters = StatementParsers.quarters(doc)
        val profitLoss = ScreenerHtml.tableRowsById(doc, ScreenerHtml.PROFIT_LOSS)
        val balanceSheet = ScreenerHtml.tableRowsById(doc, ScreenerHtml.BALANCE_SHEET)
        val cashFlow = ScreenerHtml.tableRowsById(doc, ScreenerHtml.CASH_FLOW)
        val shareholding = ScreenerHtml.tableRowsById(doc, ScreenerHtml.SHAREHOLDING)
        val ratiosCard = RatiosCardParser.parse(doc)

        val tables = listOf(
            ScreenerHtml.TOP_RATIOS to null,
            ScreenerHtml.QUARTERS to quarters.table,
            ScreenerHtml.PROFIT_LOSS to profitLoss,
            ScreenerHtml.BALANCE_SHEET to balanceSheet,
            ScreenerHtml.CASH_FLOW to cashFlow,
            RatiosCardParser.SECTION_ID to ratiosCard.table,
            ScreenerHtml.SHAREHOLDING to shareholding,
        )

        val derived = DerivedMetrics.derive(
            profitLoss, balanceSheet, cashFlow, shareholding, topRatios, ratiosCard,
        )
        val name = TopRatiosParser.companyName(doc) ?: symbol

        val industry = TopRatiosParser.industryChain(doc)

        // plan.md §1.4 rule 8: never guess a sector. But do *ask* — the decision belongs to the
        // domain's `SectorResolver` and its input is screener.in's own industry taxonomy, which
        // this page has always carried and nothing ever read. Resolving here, rather than in `:app`,
        // is what makes the persisted `sector` column real: `DefaultRedFlagRepository` reads it to
        // build `RedFlagContext`, so a permanently-`OTHER` value silently disabled every
        // sector-conditioned red flag for every stock.
        val resolution = SectorResolver.resolve(
            name = name,
            about = industry,
        )

        val ratios = RatioMapper.map(
            symbol = symbol,
            asOf = asOf,
            topRatios = topRatios,
            derived = derived.derived,
            profitLoss = profitLoss,
            shareholding = shareholding,
            sector = resolution.sector,
            // Carried through to the ratios row, because that is what the cache restores. Keeping
            // it on the snapshot alone loses it on every cached read.
            industry = industry,
        )

        val health = health(topRatios, quarters, tables, ratiosCard, derived.notes, asOf)

        val snapshot = FundamentalsSnapshot(
            symbol = symbol,
            name = name,
            sector = resolution.sector,
            sectorResolved = resolution.resolved,
            industry = industry,
            ratios = ratios,
            health = health,
            quarters = quarters.records,
            profitLoss = profitLoss?.rows.orEmpty(),
            balanceSheet = balanceSheet?.rows.orEmpty(),
            cashFlow = cashFlow?.rows.orEmpty(),
            cashFlowDerived = derived.derived,
            shareholding = shareholding?.rows.orEmpty(),
        )

        return FundamentalsParseResult(
            snapshot = snapshot,
            health = health,
            sections = tables.mapNotNull { (id, table) -> table?.let { id to it } }.toMap(),
            ratiosCard = ratiosCard,
            ratioCard = derived.ratioCard,
            shareholdingPattern = derived.shareholdingPattern,
        )
    }

    /**
     * A result for a page that could not be read at all. Same contract as a successful parse:
     * a snapshot exists, its lists are empty, and [ParserHealth.ok] is false with the reason.
     *
     * Every section is reported missing, not just none: the caller must be able to tell "the
     * page had nothing" from "the fetch failed" without inspecting two different shapes.
     */
    fun failure(symbol: String, reason: String, asOf: LocalDate = todayInIndia()): FundamentalsParseResult {
        val health = ParserHealth(
            ok = false,
            sectionsFound = emptyList(),
            sectionsMissing = SECTION_IDS,
            notes = listOf(reason),
        )
        val snapshot = FundamentalsSnapshot(
            symbol = symbol,
            name = symbol,
            sector = Sector.OTHER,
            sectorResolved = false,
            ratios = Ratios(
                symbol = symbol,
                sector = Sector.OTHER,
                asOf = asOf,
                source = Source.SCREENER_IN,
                confidence = Confidence.SINGLE,
            ),
            health = health,
        )
        return FundamentalsParseResult(snapshot = snapshot, health = health)
    }

    private fun health(
        topRatios: TopRatiosSnapshot,
        quarters: StatementParsers.QuarterSection,
        tables: List<Pair<String, ParsedTable?>>,
        ratiosCard: RatiosCard,
        derivedNotes: List<String>,
        asOf: LocalDate,
    ): ParserHealth {
        val notes = mutableListOf<String>()

        val found = mutableListOf<String>()
        val missing = mutableListOf<String>()
        for ((id, table) in tables) {
            // `ratiosCard.isUsable` rather than the generic row-count test, because the card has a
            // third state the statements do not: a card can publish a row whose every cell is a
            // placeholder (TCS's Inventory Days). It is structurally present and carries no figure,
            // which for the working-capital flags is indistinguishable from having no card.
            val usable = when (id) {
                ScreenerHtml.TOP_RATIOS -> !topRatios.isEmpty
                RatiosCardParser.SECTION_ID -> ratiosCard.isUsable
                else -> table != null && table.rows.isNotEmpty()
            }
            if (usable) {
                found += id
            } else {
                missing += id
                notes += "section '$id' is absent or empty on this page"
            }
            table?.notes?.let { notes += it }
        }
        notes += ratiosCard.notes
        notes += quarters.notes

        if (topRatios.isEmpty) {
            notes += "top-ratios is empty; every ratio that depends on it (market cap, P/E, book " +
                "value, dividend yield, ROCE, ROE, face value) is null — never zero"
        }
        if (topRatios.debtToEquity == null) {
            notes += "'Debt / Equity' and 'Current Ratio' are absent from this page — reported as " +
                "null, never 0 (meaningless for banks, plan.md §7.3)"
        }
        notes += derivedNotes

        val latestQuarter = quarters.records.firstOrNull()
        val latestAnnual = profitLossNote(tables)
        notes += "as of $asOf; latest annual period ${latestAnnual ?: "unknown"}; " +
            "latest quarter ${latestQuarter?.let { "${it.quarter}Q${it.year}" } ?: "unknown"} " +
            "(plan.md §10: fundamentals lag price, staleness must be visible)"

        return ParserHealth(
            ok = missing.isEmpty(),
            sectionsFound = found,
            sectionsMissing = missing,
            // `distinct` because the quarters notes carry the quarters table's own notes, which
            // the loop above already collected — a duplicated note reads like a second problem.
            notes = notes.distinct(),
        )
    }

    private fun profitLossNote(tables: List<Pair<String, ParsedTable?>>): String? =
        tables.firstOrNull { it.first == ScreenerHtml.PROFIT_LOSS }?.second
            ?.years?.lastOrNull { ScreenerHtml.yearOf(it) != null }
}
