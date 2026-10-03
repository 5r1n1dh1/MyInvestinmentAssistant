package com.mia.app.domain.redflag.fundamentals

import com.mia.app.domain.model.Evidence
import com.mia.app.domain.model.FlagGroup
import com.mia.app.domain.model.FlagSeverity
import com.mia.app.domain.model.FlagTab
import com.mia.app.domain.model.RedFlag
import com.mia.app.domain.model.Sector
import com.mia.app.domain.model.Source
import com.mia.app.domain.redflag.RedFlagContext
import com.mia.app.domain.ratios.RatioComputation

/**
 * Debt/equity spiking year on year, or sitting above the sector's band.
 *
 * ### The ratio is derivable, and that corrects the plan
 * plan.md §8.1 lists "no D/E" among the missing data. That is wrong: the balance-sheet summary
 * card publishes `Borrowings`, `Equity Capital` and `Reserves`, and `equity capital + reserves`
 * *is* net worth, so `borrowings ÷ net worth` is a division of published figures rather than a
 * guess. Verified on the fixtures: RELIANCE `402,962 ÷ 904,030 = 0.4457`, TITAN
 * `30,621 ÷ 15,703 = 1.9501`. [RatioComputation.debtToEquity] owns the arithmetic; this rule
 * owns the comparison.
 *
 * ### Banks get no debt/equity flag at all
 * plan.md §7.3 is unambiguous — for a bank, deposits and borrowings *are* the business rather
 * than leverage — and `RatioComputation.debtToEquity` already returns a suppressed computation
 * with that reason for [Sector.BANKING]. This rule forwards the suppression rather than working
 * around it, so no bank can ever show a debt/equity flag (spec §6.1 lists the banking level band
 * as "n/a (suppressed)"). An NBFC keeps the flag with a 3.0 ceiling: for an NBFC leverage is the
 * §7.3 question, named as its own slot there.
 *
 * ### Level bands
 * Above [FundamentalsPolicy.DEBT_TO_EQUITY_LEVEL_CEILING] for the sector, or above the book's
 * generic 2.0 when the sector has no band of its own. [Sector.OTHER] keeps only the spike check,
 * because a level test is exactly the "never compare debt ratios across unrelated industries"
 * comparison `[Ch.17-R5]` that an unresolved sector cannot support.
 *
 * ### The book's warning is in the evidence, not the prose
 * *"High debt is not automatically a red flag… Never compare debt ratios across unrelated
 * industries."* `[Ch.17-R5]` The title states what changed; the book bands and the sector
 * benchmark sit in the evidence where a user can weigh them.
 */
class DebtToEquitySpikeRule : FundamentalsRule {

    override val id: String = FundamentalsFlagIds.DEBT_TO_EQUITY_SPIKE
    override val tab: FlagTab = FlagTab.FUNDAMENTALS

    override fun evaluate(context: RedFlagContext): List<RedFlag> {
        val pair = yearPair(context) ?: return emptyList()
        val previousYear = pair.first
        val currentYear = pair.second
        val current = RatioComputation.debtToEquity(context.derived, context.sector, currentYear)
            .orNull() ?: return emptyList()
        val previous = RatioComputation.debtToEquity(context.derived, context.sector, previousYear)
            .orNull() ?: return emptyList()
        if (!current.isFinite() || !previous.isFinite()) return emptyList()

        val change = current - previous
        val relative = if (previous > 0.0) current / previous else Double.POSITIVE_INFINITY
        val spike = change >= FundamentalsPolicy.DEBT_TO_EQUITY_SPIKE_ABSOLUTE ||
            (previous >= FundamentalsPolicy.DEBT_TO_EQUITY_RELATIVE_FLOOR &&
                relative >= FundamentalsPolicy.DEBT_TO_EQUITY_SPIKE_RELATIVE)
        val ceiling = FundamentalsPolicy.DEBT_TO_EQUITY_LEVEL_CEILING[context.sector]
        val levelBreach = ceiling != null && current > ceiling
        if (!spike && !levelBreach) return emptyList()
        // When `levelBreach` is true, `ceiling` is the band that was crossed. The fallback only
        // exists so the level branch has a number to print in the unreachable case where the
        // sector has no band of its own; it is never rendered.
        val levelCeiling = ceiling ?: FundamentalsPolicy.DEBT_TO_EQUITY_BOOK_HIGH_BAND

        val currentFiscal = FundamentalsEvidence.fyLabel(currentYear)
        val previousFiscal = FundamentalsEvidence.fyLabel(previousYear)
        val title = if (spike) {
            "Debt/equity rose from ${FundamentalsEvidence.multiple(previous)} to " +
                "${FundamentalsEvidence.multiple(current)} between $previousFiscal and " +
                "$currentFiscal (${FundamentalsEvidence.signed(change, 2)}), moving from the " +
                "${FundamentalsPolicy.debtToEquityBookBand(previous)} band to the " +
                "${FundamentalsPolicy.debtToEquityBookBand(current)} band."
        } else {
            "Debt/equity was ${FundamentalsEvidence.multiple(current)} in $currentFiscal, above " +
                "the ${FundamentalsEvidence.number(levelCeiling, 1)} ceiling this band table " +
                "sets for ${FundamentalsEvidence.sectorName(context.sector)}; it was " +
                "${FundamentalsEvidence.multiple(previous)} in $previousFiscal."
        }

        val asOf = FundamentalsEvidence.fiscalYearEnd(currentYear)
        val rows = mutableListOf<Evidence>()
        context.derived.debtByYear[currentYear]?.let {
            rows += FundamentalsEvidence.statement(
                "Borrowings ($currentFiscal)",
                FundamentalsEvidence.crore(it),
                currentYear,
            )
        }
        context.derived.equityByYear[currentYear]?.let {
            rows += FundamentalsEvidence.statement(
                "Shareholders' equity, equity capital plus reserves ($currentFiscal)",
                FundamentalsEvidence.crore(it),
                currentYear,
            )
        }
        rows += Evidence(
            "Debt/equity ($currentFiscal)",
            FundamentalsEvidence.multiple(current),
            Source.DERIVED,
            asOf,
        )
        rows += Evidence(
            "Debt/equity ($previousFiscal)",
            FundamentalsEvidence.multiple(previous),
            Source.DERIVED,
            FundamentalsEvidence.fiscalYearEnd(previousYear),
        )
        rows += Evidence(
            "Change",
            FundamentalsEvidence.signed(change, 2) +
                if (previous > 0.0) {
                    " (${FundamentalsEvidence.number(relative * 100.0, 1)} % of the previous year)"
                } else {
                    ""
                },
            Source.DERIVED,
            asOf,
        )
        rows += Evidence(
            "Book band ($currentFiscal)",
            FundamentalsPolicy.debtToEquityBookBand(current),
            Source.DERIVED,
            asOf,
        )
        rows += Evidence(
            "Industry benchmark (sector)",
            if (ceiling == null) {
                FundamentalsPolicy.UNRESOLVED_SECTOR_VALUE
            } else {
                "${FundamentalsEvidence.sectorName(context.sector)} ceiling " +
                    "${FundamentalsEvidence.multiple(ceiling, 1)}; the band table is a derived " +
                    "starting point and is marked for review before shipping"
            },
            Source.DERIVED,
            asOf,
        )
        rows += Evidence(
            "Definition caveat",
            "High debt is not automatically a red flag: the book lists stable cash flow, " +
                "predictable revenue, manageable interest rates, long-lived assets and returns " +
                "above borrowing cost as the reasons debt can be acceptable, and says never to " +
                "compare debt ratios across unrelated industries (Ch.17-R5).",
            Source.DERIVED,
            asOf,
        )
        rows += context.sectorEvidenceRows()
        rows += FundamentalsEvidence.threshold("Threshold", THRESHOLD_TEXT, asOf)

        return listOf(
            RedFlag(
                id = id,
                group = FlagGroup.BALANCE_SHEET,
                severity = if (current > FundamentalsPolicy.DEBT_TO_EQUITY_BOOK_HIGH_BAND) {
                    FlagSeverity.HIGH
                } else {
                    FlagSeverity.MEDIUM
                },
                tab = tab,
                title = title,
                evidence = rows,
                threshold = THRESHOLD_TEXT,
            ),
        )
    }

    override fun explainSkip(context: RedFlagContext): String? {
        // For a bank this returns the plan.md §7.3 reason verbatim, so the user is told the
        // ratio is not meaningful here rather than merely absent.
        val latest = RatioComputation.debtToEquity(context.derived, context.sector)
        if (latest.value == null) {
            return latest.note ?: "Debt/equity could not be computed from this page."
        }
        if (yearPair(context) == null) {
            return "Not evaluated: the two most recent fiscal years publishing both 'Borrowings' " +
                "and 'Equity Capital + Reserves' are not adjacent, and this check is a " +
                "year-on-year change."
        }
        return null
    }

    /** The latest two **adjacent** fiscal years that publish both balance-sheet inputs. */
    private fun yearPair(context: RedFlagContext): Pair<Int, Int>? {
        val debt = context.derived.debtByYear
        val equity = context.derived.equityByYear
        val common = debt.keys.filter { equity.containsKey(it) }.sorted()
        if (common.size < 2) return null
        val latest = common.last()
        if (!common.contains(latest - 1)) return null
        return (latest - 1) to latest
    }

    companion object {
        const val THRESHOLD_TEXT =
            "Debt/equity up at least 0.50 absolute or at least 50% relative year on year; level " +
                "bands: below 0.3 / 0.3 to 1.0 / 1 to 2 / above 2.0"
    }
}

/**
 * Interest coverage below two.
 *
 * **Book**, `[Ch.17-R5 §Interest Coverage Ratio]`: `EBIT ÷ interest expense`, with bands below
 * 1 serious financial distress · 1–2 weak · 2–5 acceptable · above 5 strong. plan.md §8.1's
 * "below 2.0" is exactly the boundary between weak and acceptable, so plan and book agree and
 * nothing here is derived.
 *
 * ### The EBIT definition caveat is in the evidence, not the title
 * `EBIT = operating profit + depreciation`, the Indian GAAP presentation this source publishes.
 * That **excludes other income**, which understates coverage for a business with large recurring
 * non-operating income. Rather than hide that, the flag carries a second coverage figure computed
 * with other income included, so the reader sees both. For banks, NBFCs and insurers the ratio is
 * not computed at all — see [FundamentalsSectors.interestCoverageSkip].
 *
 * ### Maturities are invisible
 * `[Ch.17-R5 §Debt Maturity Matters]` makes the point that two companies with identical
 * debt-to-EBITDA differ enormously by maturity. We cannot see maturities, so an evidence row
 * says exactly that rather than leaving the reader to assume short-dated debt.
 */
class InterestCoverageBelowTwoRule : FundamentalsRule {

    override val id: String = FundamentalsFlagIds.INTEREST_COVERAGE_BELOW_2
    override val tab: FlagTab = FlagTab.FUNDAMENTALS

    override fun evaluate(context: RedFlagContext): List<RedFlag> {
        if (FundamentalsSectors.interestCoverageSkip(context.sector) != null) return emptyList()
        val year = coverageYear(context) ?: return emptyList()
        val coverage = RatioComputation.interestCoverage(context.derived, context.sector, year)
            .orNull() ?: return emptyList()
        if (!coverage.isFinite() || coverage >= FundamentalsPolicy.INTEREST_COVERAGE_MEDIUM) {
            return emptyList()
        }

        val ebit = context.derived.ebitByYear.getValue(year)
        val interest = context.derived.interestByYear.getValue(year)
        val otherIncome = context.derived.otherIncomeByYear[year]
        val inclusive = otherIncome?.let { (ebit + it) / interest }
        val fiscal = FundamentalsEvidence.fyLabel(year)

        val title = buildString {
            append("EBIT, operating profit plus depreciation, covered interest ")
            append(FundamentalsEvidence.multiple(coverage))
            append(" in $fiscal (EBIT ")
            append(FundamentalsEvidence.crore(ebit))
            append(" against interest ")
            append(FundamentalsEvidence.crore(interest))
            append(")")
            if (inclusive != null && inclusive.isFinite()) {
                append(". Including other income the figure is ")
                append(FundamentalsEvidence.multiple(inclusive))
                append(".")
            }
            append(".")
        }

        val asOf = FundamentalsEvidence.fiscalYearEnd(year)
        val rows = mutableListOf(
            FundamentalsEvidence.statement(
                "EBIT, operating profit plus depreciation ($fiscal)",
                FundamentalsEvidence.crore(ebit),
                year,
            ),
            FundamentalsEvidence.statement(
                "Interest expense ($fiscal)",
                FundamentalsEvidence.crore(interest),
                year,
            ),
            Evidence(
                "Interest coverage ($fiscal)",
                FundamentalsEvidence.multiple(coverage),
                Source.DERIVED,
                asOf,
            ),
        )
        if (inclusive != null && inclusive.isFinite()) {
            rows += Evidence(
                "Interest coverage including other income ($fiscal)",
                FundamentalsEvidence.multiple(inclusive),
                Source.DERIVED,
                asOf,
            )
            otherIncome?.let {
                rows += FundamentalsEvidence.statement(
                    "Other income ($fiscal)",
                    FundamentalsEvidence.crore(it),
                    year,
                )
            }
        }
        rows += Evidence(
            "Book band",
            FundamentalsPolicy.interestCoverageBookBand(coverage),
            Source.DERIVED,
            asOf,
        )
        rows += FundamentalsEvidence.notAvailable(
            "Debt maturity profile",
            "Not available from our sources: the balance sheet publishes borrowings without a " +
                "split by maturity, and the book notes that two companies with the same " +
                "debt-to-EBITDA differ enormously by maturity (Ch.17-R5).",
            asOf,
        )
        rows += FundamentalsEvidence.notAvailable(
            "Sector benchmark",
            "Not available from our sources: the book calls these ratios general guidelines that " +
                "need industry context (Ch.17-R5).",
            asOf,
        )
        rows += context.sectorEvidenceRows()
        rows += FundamentalsEvidence.threshold("Threshold", THRESHOLD_TEXT, asOf)

        return listOf(
            RedFlag(
                id = id,
                group = FlagGroup.BALANCE_SHEET,
                severity = if (coverage < FundamentalsPolicy.INTEREST_COVERAGE_HIGH) {
                    FlagSeverity.HIGH
                } else {
                    FlagSeverity.MEDIUM
                },
                tab = tab,
                title = title,
                evidence = rows,
                threshold = THRESHOLD_TEXT,
            ),
        )
    }

    override fun explainSkip(context: RedFlagContext): String? {
        FundamentalsSectors.interestCoverageSkip(context.sector)?.let { return it }
        if (coverageYear(context) == null) {
            return "Not evaluated: interest coverage needs an EBIT (operating profit plus " +
                "depreciation) and an interest expense line for the same fiscal year, and this " +
                "page publishes no such pair."
        }
        return null
    }

    /** Latest fiscal year publishing both an EBIT and a non-zero interest expense. */
    private fun coverageYear(context: RedFlagContext): Int? {
        val ebit = context.derived.ebitByYear
        val interest = context.derived.interestByYear
        return ebit.keys.filter { interest.containsKey(it) && interest.getValue(it) != 0.0 }
            .maxOrNull()
    }

    companion object {
        const val THRESHOLD_TEXT = "Interest coverage below 2.0x (below 1.0x is the top severity)"
    }
}

/**
 * Goodwill above twenty percent of total assets — **implemented, permanently unsourceable**.
 *
 * ### Why the arithmetic exists even though the input does not
 * The check is on plan.md §8.1's list, the threshold is 20% of total assets, and
 * `RatioComputation.goodwillToAssets` already divides the two. So the rule is complete and
 * unit-tested against a context that *does* publish goodwill: the day a source does, the flag
 * appears with no further change. Today `DerivedMetrics` leaves `goodwillByYear` empty — goodwill
 * sits inside the `Fixed Assets +` drill-down behind an AJAX sub-schedule that plan.md §3.6 does
 * not permit us to fetch, and `grep -i goodwill` over all five fixtures returns zero hits — so
 * the rule returns nothing and [explainSkip] names the missing source.
 *
 * ### What is never done here
 * No neighbouring asset line is substituted. Goodwill is a **subset** of fixed assets, so
 * "fixed assets above 20% of assets" would report a different and much more common condition
 * under this flag's id, which is the kind of confident wrong number plan.md §13 lists as the
 * fastest way to lose trust. The gap is declared in
 * [FundamentalsPolicy.DECLARED_COVERAGE_GAPS] so a Settings screen can print it instead of
 * silently showing nothing.
 */
class GoodwillAboveTwentyPercentAssetsRule : FundamentalsRule {

    override val id: String = FundamentalsFlagIds.GOODWILL_ABOVE_20PCT_ASSETS
    override val tab: FlagTab = FlagTab.FUNDAMENTALS

    override fun evaluate(context: RedFlagContext): List<RedFlag> {
        val year = shareYear(context) ?: return emptyList()
        val share = RatioComputation.goodwillToAssets(context.derived, year).orNull() ?: return emptyList()
        if (!share.isFinite() || share <= FundamentalsPolicy.GOODWILL_PERCENT_OF_ASSETS) {
            return emptyList()
        }
        val goodwill = context.derived.goodwillByYear.getValue(year)
        val assets = context.derived.totalAssetsByYear.getValue(year)
        val fiscal = FundamentalsEvidence.fyLabel(year)
        val asOf = FundamentalsEvidence.fiscalYearEnd(year)

        val title = "Goodwill was ${FundamentalsEvidence.crore(goodwill)} against total assets of " +
            "${FundamentalsEvidence.crore(assets)} in $fiscal — " +
            "${FundamentalsEvidence.number(share, 1)} % of assets."

        val rows = listOf(
            FundamentalsEvidence.statement(
                "Goodwill ($fiscal)",
                FundamentalsEvidence.crore(goodwill),
                year,
            ),
            FundamentalsEvidence.statement(
                "Total assets ($fiscal)",
                FundamentalsEvidence.crore(assets),
                year,
            ),
            Evidence(
                "Goodwill as % of total assets",
                FundamentalsEvidence.percent(share, 1),
                Source.DERIVED,
                asOf,
            ),
        ) + context.sectorEvidenceRows() +
            listOf(FundamentalsEvidence.threshold("Threshold", THRESHOLD_TEXT, asOf))

        return listOf(
            RedFlag(
                id = id,
                group = FlagGroup.BALANCE_SHEET,
                severity = FlagSeverity.MEDIUM,
                tab = tab,
                title = title,
                evidence = rows,
                threshold = THRESHOLD_TEXT,
            ),
        )
    }

    override fun explainSkip(context: RedFlagContext): String? {
        if (shareYear(context) == null) return MISSING_INPUT
        return null
    }

    private fun shareYear(context: RedFlagContext): Int? {
        val goodwill = context.derived.goodwillByYear
        val assets = context.derived.totalAssetsByYear
        return goodwill.keys.filter { assets.containsKey(it) && assets.getValue(it) != 0.0 }
            .maxOrNull()
    }

    companion object {
        const val THRESHOLD_TEXT = "Goodwill above 20% of total assets"

        const val MISSING_INPUT =
            "Not evaluated, and not evaluable from our sources: screener.in's consolidated page " +
                "publishes no Goodwill line — it sits inside the 'Fixed Assets +' drill-down, " +
                "which is an AJAX sub-schedule plan.md §3.6 does not fetch (zero hits across all " +
                "five captured fixtures). No other asset line is substituted, because goodwill is " +
                "a subset of fixed assets and the substitution would misstate the ratio."
    }
}

/**
 * Promoter shares pledged — **implemented, permanently unsourceable**.
 *
 * ### The gap
 * `grep -i pledg` returns zero hits across all five captured fixtures. The shareholding card
 * publishes promoter *holding*, FII/DII/government holdings and a shareholder count — never a
 * pledge. `CashFlowDerived.promoterPledgePercent` therefore stays `null`, never `0`.
 *
 * ### Holding is not pledge, and must never be shown as pledge
 * plan.md §8.1 says "promoter pledging (if available from shareholding data)"; it is not
 * available. Presenting promoter holding here would answer a question the user did not ask with
 * a number that looks like an answer, so the flag carries an explicit row saying which figure
 * the source does publish and why it is a different thing.
 */
class PromoterPledgingRule : FundamentalsRule {

    override val id: String = FundamentalsFlagIds.PROMOTER_PLEDGING
    override val tab: FlagTab = FlagTab.FUNDAMENTALS

    override fun evaluate(context: RedFlagContext): List<RedFlag> {
        val pledge = RatioComputation.promoterPledge(context.derived).orNull() ?: return emptyList()
        if (!pledge.isFinite() || pledge <= 0.0) return emptyList()

        val asOf = context.ratios.asOf
        val title = "Promoter shares pledged are ${FundamentalsEvidence.percent(pledge)} of the " +
            "promoter holding as of ${FundamentalsEvidence.formatDate(asOf)}."
        val rows = listOf(
            Evidence(
                "Promoter pledge",
                FundamentalsEvidence.percent(pledge),
                Source.SCREENER_IN,
                asOf,
            ),
            FundamentalsEvidence.notAvailable(
                "Pledged shares in number",
                "Not available from our sources: the source publishes the pledge as a percentage " +
                    "only, and the share count behind it is not published in the same place.",
                asOf,
            ),
            Evidence(
                "Promoter holding is not pledge",
                "Promoter holding, which this source does publish, is a different figure: the " +
                    "share of the company promoters own, with no indication of whether any of it " +
                    "is encumbered. It is never presented as a pledge.",
                Source.DERIVED,
                asOf,
            ),
        ) + context.sectorEvidenceRows() +
            listOf(FundamentalsEvidence.threshold("Threshold", THRESHOLD_TEXT, asOf))

        return listOf(
            RedFlag(
                id = id,
                group = FlagGroup.BALANCE_SHEET,
                severity = if (pledge > FundamentalsPolicy.PROMOTER_PLEDGE_HIGH_PERCENT) {
                    FlagSeverity.HIGH
                } else {
                    FlagSeverity.MEDIUM
                },
                tab = tab,
                title = title,
                evidence = rows,
                threshold = THRESHOLD_TEXT,
            ),
        )
    }

    override fun explainSkip(context: RedFlagContext): String? {
        RatioComputation.promoterPledge(context.derived).orNull()?.let { return null }
        return MISSING_INPUT
    }

    companion object {
        const val THRESHOLD_TEXT =
            "Any promoter pledge above 0%; above 25% is the top severity"

        const val MISSING_INPUT =
            "Not evaluated, and not evaluable from our sources: no pledge figure is published " +
                "anywhere on the page we are permitted to read (zero hits for 'pledg' across all " +
                "five captured fixtures). Promoter holding is published and is a different " +
                "number; it is never substituted for a pledge."
    }
}