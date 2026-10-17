package com.mia.app.domain.ratios

import com.mia.app.domain.model.Sector
import com.mia.app.domain.redflag.fundamentals.FundamentalsFlagIds

/**
 * One place a reader can go to check, by hand, a figure this app does not have.
 *
 * ### Why this is a deliverable and not a nicety
 * The alternative to naming a source is a blank. A blank reads as *"we looked and there was
 * nothing"*, which is a different and wrong claim: for a bank's GNPA the answer exists, is audited,
 * and is published — it is simply behind a paywall our network cannot reach
 * ([docs/LIVE-PROBE.md](../docs/LIVE-PROBE.md) §3, 60 empty `highlight-cell` elements). The honest
 * response to "we cannot see it" is therefore not an empty row but *"we cannot see it, and it is
 * here"*. That is what this file is, and it is the substitution the rejected Class-B work was
 * supposed to produce.
 *
 * ### What is deliberately not here
 * * A URL that answers. The NSE corporate-announcement and shareholding-pattern endpoints **404
 *   from this network** (LIVE-PROBE §3/§4), so naming them as "the app can fetch this" would be
 *   false. They appear only where a **human reader** can open them, which is a different claim and
 *   a true one.
 * * Any second-hand summary of the figure. "Where to verify" has to point at the *primary*
 *   document, or it would be the same unverified number with a citation.
 * * Anything phrased as a recommendation. [com.mia.app.domain.compliance.AdviceLanguage] is
 *   machine-checked over these strings, and the wording is "this is where the figure is published",
 *   never "this is a good sign".
 */
data class VerificationSource(
    /** What the document is, in the reader's terms. */
    val title: String,
    /** Where in it. A section or a note, because "the annual report" alone is 300 pages. */
    val where: String,
    /** The line this document settles. Names the figure so the reader knows when to stop looking. */
    val whatItAnswers: String,
) {
    /** One sentence for the UI: which document, where in it, and what it settles. */
    fun asSentence(): String = "$title — $where. Settles: $whatItAnswers."
}

/**
 * Where to verify every ratio and every fundamentals red-flag check this app cannot evaluate.
 *
 * ### Coverage is a contract, not a best effort
 * [forRatio] returns a non-empty list for every [RatioState] other than [RatioState.AVAILABLE] and
 * [RatioState.STALE] — the two states that mean "we have the figure". [forRule] returns one for
 * every id in `FundamentalsFlagIds`. `VerificationSourcesTest` asserts both, which is the only
 * thing that stops a newly added `RatioId` from shipping with a blank and no destination.
 *
 * ### Why the bank destinations are named individually rather than as "the annual report"
 * A bank's annual report, its CRAR disclosure and the RBI's Financial Stability Report each answer
 * a different question: GNPA and NNPA are in the financial statements' asset-quality note,
 * **provision coverage** is not disclosed by the statutory formats at all and has to be read out of
 * the provisions schedule, and the *system-wide* picture is the RBI's. Collapsing them into one
 * sentence would send a reader to a place that does not contain the number they were told to find.
 *
 * @param sector the sector the reader's screen resolved to. Bank and NBFC destinations genuinely
 *   differ from a pharma company's, so it is an input rather than a constant.
 */
object VerificationSources {

    // ------------------------------------------------------------------------- documents

    /**
     * The company's own audited annual report.
     *
     * Named by the section that carries the figure rather than as a whole document, because the
     * reason the app does not have most of these numbers is that screener.in's consolidated page
     * publishes a *summary* — the notes behind it are exactly where the real disclosure lives.
     */
    private fun annualReport(section: String, answers: String) = VerificationSource(
        title = "The company's annual report",
        where = section,
        whatItAnswers = answers,
    )

    /**
     * The quarterly shareholding-pattern filing.
     *
     * A human can open this on the company's own website or on the exchange's corporate-filings
     * page. It is named here rather than fetched because the app cannot: LIVE-PROBE §4 measured the
     * NSE announcement endpoints **404ing** from this network, so an in-app link to them would be a
     * promise the build cannot keep.
     */
    private fun shareholdingFiling(answers: String) = VerificationSource(
        title = "The quarterly shareholding-pattern filing",
        where = "the company's website, under its investor-relations or 'shareholding pattern' " +
            "page, and the exchange's corporate-filings page under the same heading",
        whatItAnswers = answers,
    )

    private fun annualResults(answers: String) = VerificationSource(
        title = "The company's quarterly results announcement",
        where = "filed with the exchange and reproduced in the company's investor-relations section",
        whatItAnswers = answers,
    )

    /**
     * RBI's Financial Stability Report, for the **system-wide** view.
     *
     * Included alongside the company's own disclosure on purpose: a single bank's GNPA says what
     * happened to that bank, and the reader also needs the benchmark it should be read against. The
     * report is free and published twice a year, which is why it is the honest answer for the
     * figures no free per-company source carries.
     */
    private val rbiFinancialStability = VerificationSource(
        title = "RBI Financial Stability Report",
        where = "the 'Financial Stability' chapter, which publishes system-level GNPA, NNPA, capital " +
            "and liquidity aggregates",
        whatItAnswers = "how the banking system as a whole is doing on asset quality, so a single " +
            "bank's numbers can be read against the benchmark rather than in isolation",
    )

    private val rbiMasterDirectionNbfc = VerificationSource(
        title = "The company's annual report, filed under the RBI's Master Direction for NBFCs",
        where = "the disclosures the Master Direction requires: credit performance, asset-quality " +
            "and ageing tables, and the cost of funds",
        whatItAnswers = "asset quality, stage-wise provisioning and the cost of funds, which are " +
            "mandatory disclosures rather than optional ones",
    )

    private val fdaWarningLetters = VerificationSource(
        title = "The US FDA's public warning-letter and inspection databases",
        where = "searched by company name on fda.gov",
        whatItAnswers = "whether the US regulator has issued an observation letter, a warning " +
            "letter or a consent decree, and its date",
    )

    private val nseDisclosureOfPledge = VerificationSource(
        title = "The exchange's 'Disclosure of Pledge / Encumbrance' filings",
        where = "the company's continuous-disclosure page on its exchange, searchable by symbol " +
            "(the NSE and BSE corporate-filings pages)",
        whatItAnswers = "pledged shares as a count and as a percentage of the promoter's holding, " +
            "filed within two days of the encumbrance",
    )

    // --------------------------------------------------------------------------- ratios

    /**
     * Ratio → where its figure is published, for the three holder families plan.md §7.3 names.
     *
     * Grouped by family so "where do I look for a bank's GNPA" and "where do I look for a bank's
     * cost of funds" are one line each, and so adding a fifth NBFC ratio is one line rather than a
     * second hand-written paragraph.
     */
    private val BANKING_RATIOS: Map<RatioId, List<VerificationSource>> = mapOf(
        RatioId.NET_INTEREST_MARGIN to listOf(
            annualReport(
                "the Management Discussion and Analysis, 'Net Interest Margin' and the notes on " +
                    "advances and borrowings",
                "the bank's own net interest margin, on the same definition it reports",
            ),
            rbiFinancialStability,
        ),
        RatioId.GNPA_NNPA to listOf(
            annualReport(
                "the notes to the financial statements, the asset-quality / advances note, which " +
                    "carries gross and net non-performing assets by segment",
                "gross and net NPA, with the ageing and the sector split",
            ),
            rbiFinancialStability,
        ),
        RatioId.CASA_RATIO to listOf(
            annualReport(
                "the notes on deposits, which split current and savings balances from term and " +
                    "non-interest-bearing deposits",
                "the CASA share of total deposits, computed from the disclosed balances",
            ),
            rbiFinancialStability,
        ),
        RatioId.PROVISION_COVERAGE_RATIO to listOf(
            annualReport(
                "the provisions note, where the provision held against non-performing assets is " +
                    "disclosed — this ratio is **not** in any statutory summary table and has to be " +
                    "read out of the provision schedule",
                "provision coverage, which the standardised formats do not publish as a single line",
            ),
            rbiFinancialStability,
        ),
    )

    private val NBFC_RATIOS: Map<RatioId, List<VerificationSource>> = mapOf(
        RatioId.ASSET_QUALITY to listOf(rbiMasterDirectionNbfc),
        RatioId.STAGE_2_GNPA to listOf(rbiMasterDirectionNbfc),
        RatioId.COST_OF_FUNDS to listOf(rbiMasterDirectionNbfc),
        RatioId.LEVERAGE to listOf(
            annualReport(
                "the notes on borrowings and on equity, or the 'Financial Ratios' table in the " +
                    "Management Discussion and Analysis, which most NBFCs publish with a debt-equity " +
                    "line already computed",
                "debt to equity on the company's own definition",
            ),
        ),
    )

    private val IT_RATIOS: Map<RatioId, List<VerificationSource>> = mapOf(
        RatioId.TOTAL_CONTRACT_VALUE to listOf(
            annualResults(
                "the order-book or TCV disclosure in the results announcement, when the company " +
                    "publishes one",
            ),
            annualReport("the Management Discussion and Analysis, 'Business' section", "bookings " +
                "and order book commentary for the year"),
        ),
        RatioId.ATTRITION to listOf(
            annualReport(
                "the Directors' Report and the Human Resources / employee-benefit note, which " +
                    "discloses headcount at two dates — the only attrition figure an Indian listed " +
                    "company must publish",
                "headcount at two dates, from which attrition is computed",
            ),
        ),
        RatioId.CONSTANT_CURRENCY_GROWTH to listOf(
            annualResults(
                "the results presentation or press release, where constant-currency growth is " +
                    "normally given alongside reported growth",
            ),
        ),
        RatioId.REVENUE_VISIBILITY to listOf(
            annualResults(
                "the results announcement's guidance or remaining-performance-obligation note, " +
                    "under the Ind AS revenue standard",
            ),
        ),
    )

    private val PHARMA_RATIOS: Map<RatioId, List<VerificationSource>> = mapOf(
        RatioId.RD_TO_SALES to listOf(
            annualReport(
                "the expenses note (R&D expenditure, including capitalised and revenue R&D) against " +
                    "the revenue note",
                "R&D spend as a share of sales, on the company's own classification",
            ),
        ),
        RatioId.USFDA_COMPLIANCE to listOf(fdaWarningLetters),
        RatioId.PRODUCT_CONCENTRATION to listOf(
            annualReport(
                "the segment note, which discloses revenue by product or by therapeutic segment",
                "how much of the revenue depends on the top products",
            ),
        ),
    )

    private val FMCG_RATIOS: Map<RatioId, List<VerificationSource>> = mapOf(
        RatioId.VOLUME_VS_PRICE_GROWTH to listOf(
            annualResults(
                "the results announcement's volume-and-price commentary, or the investor " +
                    "presentation published with it",
            ),
        ),
        RatioId.WORKING_CAPITAL_DAYS to listOf(
            annualReport(
                "the inventories note (raw materials, work in progress, finished goods) and the " +
                    "trade-receivables and trade-payables ageing notes",
                "the balances the days are computed from, so the calculation can be reproduced",
            ),
        ),
    )

    /**
     * Ratios no free source of ours publishes, for **any** sector.
     *
     * Each one is absent because of what the page does not carry, and the destination is the note
     * that carries the components — `CURRENT_RATIO`'s current assets and current liabilities,
     * `GROSS_MARGIN`'s cost of goods, `EV_TO_EBITDA`'s net debt and depreciation. Naming the
     * components is the point: a reader who knows which note to open can still compute the ratio
     * themselves, which is more than the app can offer.
     */
    private val NEVER_SOURCED_RATIOS: Map<RatioId, List<VerificationSource>> = mapOf(
        RatioId.CURRENT_RATIO to listOf(
            annualReport(
                "the balance sheet and its current-asset / current-liability notes; the published " +
                    "summary balance sheet carries neither split",
                "current assets and current liabilities, from which the ratio can be computed",
            ),
        ),
        RatioId.GROSS_MARGIN to listOf(
            annualReport(
                "the expenses / cost-of-goods note. screener.in's summary P&L publishes no " +
                    "cost-of-goods line at all, so gross margin is never computed here",
                "cost of goods sold, without which gross margin is not arithmetic but a guess",
            ),
        ),
        RatioId.EV_TO_EBITDA to listOf(
            annualReport(
                "the notes on borrowings and cash (for net debt) and on depreciation and " +
                    "amortisation (for EBITDA); neither line is published in the summary page",
                "net debt and EBITDA, the two inputs of the multiple",
            ),
        ),
    )

    /** Every ratio with a named destination, regardless of sector. */
    private val BY_RATIO: Map<RatioId, List<VerificationSource>> =
        BANKING_RATIOS + NBFC_RATIOS + IT_RATIOS + PHARMA_RATIOS + FMCG_RATIOS + NEVER_SOURCED_RATIOS

    /**
     * The reader's own company documents, used for the ratios that can go missing at runtime.
     *
     * A ratio whose page simply did not carry it ([RatioState.MISSING]) is the same situation as a
     * never-sourced one from the reader's side: the figure is in the annual report. These three are
     * the ones that genuinely flip between `AVAILABLE` and `MISSING` across pages.
     */
    private val MISSING_FALLBACK: List<VerificationSource> = listOf(
        annualReport(
            "the financial statements and their notes; screener.in's summary page carries a subset " +
                "of the disclosed lines, and which subset varies by company",
            "the full set of disclosed figures, which is a superset of the summary the app reads",
        ),
    )

    /**
     * Where the reader verifies [id], when its state says the app has no figure.
     *
     * @return `null` only for [RatioState.AVAILABLE] and [RatioState.STALE] — the two states that
     *   mean the app *has* the figure and there is nothing to go and check. Every other state
     *   returns at least one source.
     */
    fun forRatio(id: RatioId, state: RatioState, sector: Sector): List<VerificationSource>? =
        when (state) {
            RatioState.AVAILABLE, RatioState.STALE -> null
            RatioState.SUPPRESSED_FOR_SECTOR -> {
                // §7.3 removed the ratio because it is meaningless here. That is an editorial
                // decision about the measure, not a gap in the disclosure, so pointing at a document
                // would imply the reader should override the app. The sector note is the answer.
                null
            }
            RatioState.NOT_SOURCED -> BY_RATIO[id] ?: MISSING_FALLBACK
            RatioState.MISSING -> BY_RATIO[id] ?: MISSING_FALLBACK
        }

    /**
     * The sources for a ratio, ignoring its state.
     *
     * Exposed so the coverage test can assert that **every** ratio the app can ever blank has a
     * destination, which is a stronger property than asserting it for the ratios that happen to be
     * blank on today's fixtures.
     */
    fun forEveryRatio(): Map<RatioId, List<VerificationSource>> = BY_RATIO

    // ---------------------------------------------------------------------- red flags

    /**
     * Fundamentals red-flag id → where its inputs are published.
     *
     * Every id in `FundamentalsFlagIds` has an entry, which `VerificationSourcesTest` asserts. The
     * destinations are the notes behind the numbers rather than a different data vendor: the whole
     * reason these checks cannot run is that our one free source publishes a summary, so the
     * primary disclosure is the correct place for a reader to go — and it is free.
     */
    private val BY_RULE: Map<String, List<VerificationSource>> = mapOf(
        FundamentalsFlagIds.CFO_NEGATIVE_WHILE_PAT_POSITIVE to listOf(
            annualReport(
                "the cash-flow statement and the note on cash generated from operations; a " +
                    "quarterly or half-yearly split is in the results announcements",
                "operating cash flow and profit after tax for each year, as audited",
            ),
        ),
        FundamentalsFlagIds.RECEIVABLES_GROWING_FASTER_THAN_REVENUE to listOf(
            annualReport(
                "the trade-receivables note, including its ageing schedule and any expected-credit-" +
                    "loss allowance, against the revenue note",
                "trade receivables and revenue, so the growth gap can be recomputed",
            ),
        ),
        FundamentalsFlagIds.INVENTORY_DAYS_RISING to listOf(
            annualReport(
                "the inventories note (raw materials, work in progress, finished goods) and the " +
                    "trade-payables ageing note",
                "inventory and cost of goods, the two inputs of inventory days",
            ),
        ),
        FundamentalsFlagIds.OTHER_INCOME_AS_PCT_OF_PAT to listOf(
            annualReport(
                "the 'other income' note, which breaks out interest, treasury and one-off items — " +
                    "the composition decides whether the share is structural",
                "the components of other income, not just its total",
            ),
        ),
        FundamentalsFlagIds.DEBT_TO_EQUITY_SPIKE to listOf(
            annualReport(
                "the borrowings note and the equity note, or the 'Financial Ratios' table, which " +
                    "most Indian annual reports publish with debt-equity already computed",
                "debt and equity on the company's own definition",
            ),
        ),
        FundamentalsFlagIds.INTEREST_COVERAGE_BELOW_2 to listOf(
            annualReport(
                "the finance-cost note and the operating-profit line of the profit and loss account",
                "operating profit and finance cost, the two inputs of the coverage ratio",
            ),
        ),
        FundamentalsFlagIds.GOODWILL_ABOVE_20PCT_ASSETS to listOf(
            annualReport(
                "the intangible-assets note and the business-combination note. Goodwill sits inside " +
                    "the 'Fixed Assets +' drill-down on screener.in, which is an AJAX sub-schedule " +
                    "plan.md §3.6 does not permit the app to fetch",
                "goodwill carried at cost less impairment, and total assets",
            ),
        ),
        FundamentalsFlagIds.PROMOTER_PLEDGING to listOf(
            shareholdingFiling(
                "the promoter category's own shareholding; holding is not pledge, and the pledge " +
                    "is a separate filing",
            ),
            nseDisclosureOfPledge,
        ),
        FundamentalsFlagIds.LOW_PE_WITH_FALLING_RETURNS to listOf(
            annualReport(
                "the five-year financial highlights and the 'Financial Ratios' table, which give " +
                    "the sector's own peer set through the ratios Indian annual reports must publish",
                "return ratios across years, so the trend can be checked",
            ),
        ),
        FundamentalsFlagIds.PE_RISING_WHILE_EARNINGS_FALL to listOf(
            annualResults("each quarter's earnings per share, as filed"),
            annualReport(
                "the earnings-per-share note and the five-year financial highlights",
                "EPS year on year, and the restated figures after any bonus or split",
            ),
        ),
    )

    /**
     * Where the reader verifies a fundamentals red-flag check that could not be evaluated.
     *
     * @return `null` for an id with no declared destination — which
     *   `VerificationSourcesTest` treats as a defect rather than an acceptable answer, because
     *   "we cannot check this" without a destination is exactly the blank this exists to remove.
     */
    fun forRule(ruleId: String): List<VerificationSource>? = BY_RULE[ruleId]

    /** Every fundamentals red-flag id with a declared destination. */
    fun rulesWithSources(): Map<String, List<VerificationSource>> = BY_RULE
}