package com.mia.app.domain.redflag.fundamentals

import com.mia.app.domain.model.Sector
import com.mia.app.domain.ratios.RatioComputation

/**
 * Every threshold, sector table and copy string the ten fundamentals flags use, in one file.
 *
 * ### Why one table, and why it is public
 * `docs/spec/redflags-spec.md` labels a lot of its own numbers `[derived]`: the day bands, the
 * other-income percentages, the P/E bands and the per-sector debt/equity ceilings are the
 * spec author's arithmetic, not the textbook's. The spec itself asks for them to be *"a single
 * named constant table so that tuning is one edit"* and *"must be reviewed by the owner
 * against a real sample before shipping"*. That is only true if they are reachable, so the
 * tables are public data on this object rather than private constants sprinkled through the
 * rules, and the tests assert the tables are total (every sector either has a band or is
 * deliberately absent).
 *
 * ### What is a book number and what is not
 * * **Book:** the days-sales-outstanding / inventory-days formula and their industry-norm
 *   warnings `[Ch.13-R1 §10.12-10.13]`, the debt/equity bands and "debt is not automatically
 *   a red flag" `[Ch.17-R5]`, interest-coverage bands `[Ch.17-R5]`, the 2-year cash-flow rule
 *   `[Ch.11-4 §Red Flag 4]`, the value-trap warning signs `[Ch.48 §Mistake 1]`.
 * * **`[derived]`:** every numeric threshold below that the book states qualitatively. Each
 *   constant's KDoc names the reasoning and the residual doubt.
 * * **Not reproducible on purpose:** the book's own adjective for the top debt/equity band is
 *   deliberately absent from every string here. plan.md §1.4 rule 1 bans judgement language in
 *   user-facing copy, so the band is reported as *"the book's top band"* and the fact that the
 *   book reaches for a judgement word is recorded here, in a comment, where it belongs.
 */
object FundamentalsPolicy {

    // ------------------------------------------------------------ earnings quality

    /**
     * `CFO < 0` while `PAT > 0`, for at least this many consecutive fiscal years.
     *
     * **Book**, `[Ch.11-4 §Red Flag 4]`: *"One year may not indicate a problem. Several
     * consecutive years require explanation."* The book sets no number and plan.md §8.1 sets
     * two; two is also the smallest count for which the phrase "several years" is honest, and
     * shortening it to one would fire on the ordinary capex year the same chapter warns about.
     */
    const val CFO_NEGATIVE_MIN_CONSECUTIVE_YEARS: Int = 2

    /** At this many consecutive years the spec grades the flag `HIGH` rather than `MEDIUM`. */
    const val CFO_NEGATIVE_HIGH_CONSECUTIVE_YEARS: Int = 3

    /**
     * Rising working-capital days, in days: `10 → LOW`, `25 → MEDIUM`, `45 → HIGH`.
     *
     * **`[derived]`** from two facts: the book expresses the underlying warning in growth
     * percentages, not days (12%→14% Normal, 12%→65% warning `[Ch.11-4 §Example]`), and ten
     * days of debtor days is about 2.7% of a year's revenue turning into receivables — the same
     * idea as plan.md §8.1's "10pp gap", in the only unit the source publishes. The floor is
     * also calibrated against the real TITAN fixture, whose inventory days wander over a
     * 169→222 day range, so a ±5-day annual wobble is normal for a watch business and a
     * 10-day rule does not fire on noise.
     */
    object WorkingCapitalDayBands {
        const val LOW: Double = 10.0
        const val MEDIUM: Double = 25.0
        const val HIGH: Double = 45.0
    }

    /**
     * Other income as a percent of profit after tax: above `MEDIUM` is a flag, above `HIGH`
     * is the top severity. Never `LOW` — the spec grades this flag `MEDIUM` at worst-read
     * threshold and `HIGH`, because a quarter of profit arriving from outside operations is
     * either the whole finding or nothing.
     *
     * **`[derived]`:** the book gives no number. Its worked example is 60%
     * (`[Ch.11-3 §9.68]`, PAT ₹1,000 Cr with ₹600 Cr of other income) and it says only
     * *"investigate whether the underlying operating business is actually strong"*.
     *
     * ### Calibration conflict the owner must resolve
     * The spec set 30% *because* RELIANCE sits below it — and it measured RELIANCE on the
     * **`TTM` column** (20,393 / 88,167 = 23.1%). Our parser drops `TTM` on purpose
     * (`DerivedMetrics.series`: a trailing-twelve-months figure is not a fiscal year and must
     * not sit beside twelve annual ones), so the annual FY2026 figures are 28,846 / 95,754 =
     * **30.13%** and the flag does fire on RELIANCE. Either the band moves above 30.13% or
     * `TTM` is parsed again; leaving it as it is means a `[derived]` threshold that fires on
     * the fixture the spec used to justify it. Reported, not silently patched.
     */
    const val OTHER_INCOME_MEDIUM_PERCENT: Double = 30.0
    const val OTHER_INCOME_HIGH_PERCENT: Double = 50.0

    /**
     * plan.md §7.3 / spec §6.1: for a bank, an NBFC or an insurer, non-operating income is a
     * structural component of the income statement rather than a top-up to operating profit,
     * so the ratio is not a finding. Suppressed, not lowered.
     */
    val OTHER_INCOME_SUPPRESSED_SECTORS: Set<Sector> =
        setOf(Sector.BANKING, Sector.NBFC, Sector.INSURANCE)

    /**
     * Sectors for which [WorkingCapitalDayBands] cannot be judged, because the comparison
     * needs an industry norm we do not have (spec §6.1/§6.2).
     *
     * The book is explicit that both measures are norm-dependent — *"Seasonality and industry
     * norms should always be considered"* `[Ch.13-R1 §10.12]` and *"Some industries naturally
     * require higher inventory levels than others"* `[Ch.13-R1 §10.13]` — so a 10-day move is
     * normal in one business and a finding in another. [Sector.OTHER] is in the set because an
     * unresolved sector is the case where the norm is *least* knowable: spec §6.2 suppresses
     * the working-capital flags outright rather than lowering them.
     */
    val WORKING_CAPITAL_NORM_SUPPRESSED_SECTORS: Set<Sector> = setOf(
        Sector.METALS,
        Sector.CEMENT,
        Sector.POWER,
        Sector.RENEWABLE,
        Sector.RAILWAY,
        Sector.REALTY,
        Sector.AVIATION,
        Sector.CHEMICALS,
        Sector.OTHER,
    )

    // --------------------------------------------------------------- balance sheet

    /**
     * Debt/equity spike: `+0.50` absolute, or `≥ +50%` relative.
     *
     * **`[derived]`** from the book's own four level bands (0.3 / 1.0 / 2.0
     * `[Ch.17-R5 §Interpretation]`): half a band, or half again in proportional terms, is the
     * smallest move that visibly crosses a band, so anything smaller is not a spike by the
     * book's own vocabulary. The relative test additionally requires the earlier year to be at
     * least [DEBT_TO_EQUITY_RELATIVE_FLOOR], because 0.02 → 0.05 is +150% and nothing else.
     */
    const val DEBT_TO_EQUITY_SPIKE_ABSOLUTE: Double = 0.50
    const val DEBT_TO_EQUITY_SPIKE_RELATIVE: Double = 1.50
    const val DEBT_TO_EQUITY_RELATIVE_FLOOR: Double = 0.20

    /** The book's highest debt/equity band boundary, [Ch.17-R5 §Interpretation]. */
    const val DEBT_TO_EQUITY_BOOK_HIGH_BAND: Double = 2.0

    /**
     * Per-sector debt/equity ceiling that replaces the generic `2.0` level test (spec §6.1).
     *
     * **`[derived]` for every row.** The book's industry table `[Ch.17-R5 §Industry
     * Differences]` gives an *ordering* — software near zero, FMCG low, pharma low, utilities
     * moderate-to-high, telecom high, infrastructure high, airlines high — and these numbers
     * interpolate it. They must be reviewed against a real sample before shipping; a ceiling
     * that is too tight turns a healthy balance sheet into a flag.
     *
     * [Sector.BANKING] is absent on purpose: plan.md §7.3 removes debt/equity for banks
     * entirely, which [DebtToEquitySpikeRule] enforces by suppressing the whole flag rather
     * than only its level test. [Sector.OTHER] is absent because spec §6.2 suppresses the level
     * check for an unresolved sector and keeps only the spike.
     */
    val DEBT_TO_EQUITY_LEVEL_CEILING: Map<Sector, Double> = mapOf(
        Sector.NBFC to 3.0,
        Sector.INSURANCE to 1.0,
        Sector.IT to 0.5,
        Sector.PHARMA to 0.5,
        Sector.FMCG to 0.8,
        Sector.AUTO to 1.5,
        Sector.METALS to 1.5,
        Sector.CEMENT to 1.2,
        Sector.POWER to 2.0,
        Sector.RENEWABLE to 2.5,
        Sector.DEFENCE to 0.5,
        Sector.RAILWAY to 2.0,
        Sector.REALTY to 2.0,
        Sector.TELECOM to 2.5,
        Sector.AVIATION to 2.5,
        Sector.DURABLES to 1.0,
        Sector.RETAIL to 1.0,
        Sector.LOGISTICS to 1.5,
        Sector.CAPITAL_GOODS to 2.0,
        Sector.CHEMICALS to 1.5,
    )

    /**
     * The book's four debt/equity bands, as level evidence `[Ch.17-R5 §Interpretation]`:
     * below 0.3 conservative, 0.3–1.0 comfortable, 1–2 high, above 2.0 the top band.
     *
     * The book's own adjective for the top band is not reproduced: plan.md §1.4 rule 1 keeps
     * judgement out of user-facing copy, and the numbers plus the spec's own quotation are
     * enough for the user to look it up.
     */
    fun debtToEquityBookBand(value: Double): String = when {
        !value.isFinite() -> "Undefined (not a finite ratio)"
        value < 0.3 -> "Below 0.3 — conservative"
        value <= 1.0 -> "0.3 to 1.0 — comfortable"
        value <= DEBT_TO_EQUITY_BOOK_HIGH_BAND -> "1 to 2 — high"
        else -> "Above 2.0 — the book's top band"
    }

    /**
     * Interest coverage: below [INTEREST_COVERAGE_MEDIUM] is a flag, below
     * [INTEREST_COVERAGE_HIGH] is the top severity.
     *
     * **Book**, `[Ch.17-R5 §Interpretation]`: below 1 serious financial distress · 1–2 weak ·
     * 2–5 acceptable · above 5 strong. plan.md §8.1's "below 2.0" is exactly the boundary
     * between weak and acceptable, so plan and book agree and nothing is `[derived]` here.
     */
    const val INTEREST_COVERAGE_MEDIUM: Double = 2.0
    const val INTEREST_COVERAGE_HIGH: Double = 1.0

    /** The same four bands as [debtToEquityBookBand]'s vocabulary, for interest coverage. */
    fun interestCoverageBookBand(value: Double): String = when {
        !value.isFinite() -> "Undefined (not a finite ratio)"
        value < INTEREST_COVERAGE_HIGH -> "Below 1 — serious financial distress"
        value < INTEREST_COVERAGE_MEDIUM -> "1 to 2 — weak"
        value <= 5.0 -> "2 to 5 — acceptable"
        else -> "Above 5 — strong"
    }

    /**
     * plan.md §7.3 / spec §4.3 and §6.1: interest coverage is suppressed for banks, NBFCs and
     * insurers.
     *
     * A bank's published `Interest` row is interest **income** (verified on the HDFCBANK
     * fixture), so `EBIT ÷ interest` is inverted nonsense; and for the other two, interest is a
     * cost of goods sold rather than a financing cost. The spec's own definition caveat says
     * the `EBIT` used here excludes other income, *"which understates coverage for companies
     * with large recurring non-operating income (banks, NBFCs, investment companies)"* — the
     * correction is to not compute it at all, and to say so.
     */
    val INTEREST_COVERAGE_SUPPRESSED_SECTORS: Set<Sector> =
        setOf(Sector.BANKING, Sector.NBFC, Sector.INSURANCE)

    /** plan.md §8.1: goodwill above this share of total assets is a finding. */
    const val GOODWILL_PERCENT_OF_ASSETS: Double = 20.0

    /**
     * Promoter pledge above this share of the promoter's holding is `HIGH`; any pledge at all
     * is `MEDIUM`.
     *
     * **`[derived]`** — plan.md §8.1 says only "promoter pledging (if available from
     * shareholding data)" and gives no threshold. Zero is not the trip condition: a pledge of
     * 0.01% is a published figure and therefore something the reader asked about, and
     * thresholding at 0 would make the flag depend on rounding in the source.
     */
    const val PROMOTER_PLEDGE_HIGH_PERCENT: Double = 25.0

    // -------------------------------------------------------------- valuation traps

    /**
     * Absolute P/E band per sector, standing in for plan.md §8.1's "below sector median".
     *
     * ### Why this table exists at all — the honest long-term fix
     * plan.md §8.1 asks for `PE < sector median`. A sector median needs sector listing pages,
     * which are **outside our permitted source set** (plan.md §3.3 parses exactly one URL
     * shape, `/company/{SYMBOL}/consolidated/`; §3.6 forbids search and pagination), so the
     * median cannot be computed today. This table is the substitute the spec defines, it is
     * named `DERIVED_PE_BAND` so no reader can mistake it for a measured median, and the gap
     * is **not closed**: the correct fix is to obtain a real per-sector median (a periodic
     * sector-page fetch, cached with its own as-of date) and then delete this table. Until then
     * a company is compared against an absolute constant, and the evidence row says exactly
     * that.
     *
     * **Every row is `[derived]`.** The book's industry table `[Ch.47 §Industry Comparison
     * Table]` names the *right multiple* per industry (banks on P/B, IT and FMCG on P/E,
     * cement on EV/EBITDA) but gives no P/E numbers, so these interpolate its structure and
     * the spec's sector list. They must be reviewed against a real sample of ~30 stocks per
     * sector before shipping.
     */
    val DERIVED_PE_BAND: Map<Sector, Double> = mapOf(
        Sector.BANKING to 12.0,
        Sector.NBFC to 10.0,
        Sector.IT to 20.0,
        Sector.PHARMA to 25.0,
        Sector.FMCG to 35.0,
        Sector.TELECOM to 15.0,
        Sector.METALS to 12.0,
        Sector.CEMENT to 18.0,
        Sector.POWER to 18.0,
        Sector.RENEWABLE to 30.0,
        Sector.REALTY to 20.0,
        Sector.AVIATION to 30.0,
        Sector.DURABLES to 25.0,
        Sector.RETAIL to 40.0,
        Sector.LOGISTICS to 30.0,
        Sector.CAPITAL_GOODS to 30.0,
        Sector.CHEMICALS to 18.0,
        Sector.AUTO to 20.0,
        Sector.INSURANCE to 15.0,
        Sector.DEFENCE to 45.0,
        Sector.RAILWAY to 22.0,
    )

    /**
     * Profitability has to fall by at least this much, in percentage points per year, over
     * [PROFITABILITY_TREND_YEARS], for the value-trap flag to fire.
     *
     * plan.md §8.1 says "ROE declining" with no number. **`[derived]`** at one point a year:
     * the flag also requires a P/E below the sector band, so the trend only has to establish
     * direction, and a tenth of a point a year is measurement noise on a rounded percentage
     * series (screener.in prints `ROCE %` as whole numbers).
     */
    const val PROFITABILITY_TREND_TRIGGER_PP_PER_YEAR: Double = -1.0

    /**
     * Window for the profitability slope. plan.md §8.1 says "declining" with no window.
     *
     * `[derived]`: three fiscal years is the shortest window that is not one bad quarter, and
     * it matches the window the spec's own evidence rows show.
     */
    const val PROFITABILITY_TREND_YEARS: Int = 3

    /**
     * Which published profitability series the value-trap flag reads.
     *
     * spec §10 R3: the 12-year series in the `ratios` card is `ROCE %` for everybody and
     * `ROE %` only for banks — a bank publishes no ROCE (verified: the HDFCBANK fixture's
     * `ratios` card is exactly `['ROE %']`). For the two other financials, `ROE %` is what is
     * published, so they read `ROE %` too.
     */
    val PROFITABILITY_FROM_ROE: Set<Sector> =
        setOf(Sector.BANKING, Sector.NBFC, Sector.INSURANCE)

    /**
     * plan.md §8.1's second valuation trap: trailing earnings falling while the multiple on
     * those earnings rises.
     *
     * `HIGH` when the current multiple is at least this multiple of the earlier one.
     * **`[derived]`** 25%: the spec fixes no number; a quarter of multiple expansion over four
     * quarters is the point at which the two facts have stopped being able to be a rounding
     * artefact of each other.
     */
    const val PE_RISING_HIGH_MULTIPLE: Double = 1.25

    /** Quarters summed into one trailing-twelve-month EPS figure. Four, by definition of TTM. */
    const val EPS_TTM_QUARTERS: Int = 4

    /** Quarters between the two endpoints of the comparison. Four = one year. */
    const val PE_TREND_QUARTERS: Int = 4

    /**
     * How far from a quarter-end date a cached close may sit and still be used.
     *
     * spec §5.2 asks for a cached close "within ±10 trading days". `:domain` has no trading
     * calendar (plan.md §3.4 says a holiday list is the only one available, and the red-flag
     * engine has none), so counting sessions here would make the window wrong across every
     * Diwali and every state holiday. Ten trading days is about fourteen calendar days, and
     * fourteen is what this uses.
     */
    const val PRICE_MATCH_TOLERANCE_DAYS: Int = 14

    /**
     * Sectors where a falling four-quarter EPS is more likely a cyclical trough than a decline,
     * so the multiple-on-falling-earnings flag is suppressed when the fall is shallow
     * (spec §5.2's own `[derived]` mitigation).
     */
    val CYCLICAL_SECTORS: Set<Sector> = setOf(
        Sector.METALS,
        Sector.CEMENT,
        Sector.CHEMICALS,
        Sector.POWER,
        Sector.AVIATION,
        Sector.CAPITAL_GOODS,
    )

    /**
     * In a cyclical sector, an EPS fall shallower than this (i.e. greater than −40%) is
     * treated as a trough rather than a fall, and the flag does not fire. **`[derived]`.**
     */
    const val CYCLICAL_TROUGH_MAX_FALL_PERCENT: Double = -40.0

    /**
     * Quarters of EPS needed before the multiple-on-falling-earnings flag is evaluated.
     *
     * The spec's §5.2 window line says "needs ≥ 9 quarters", but the comparison it then
     * defines uses only two endpoints four quarters apart, so five quarters are what the
     * arithmetic consumes. Nine would suppress real findings on a page that publishes 13
     * quarters but has a gap. Deviation from the spec, reported.
     */
    const val MIN_QUARTERS_FOR_PE_TREND: Int = 5

    // ------------------------------------------------------------------- shared bits

    /**
     * The evidence value every surviving flag must carry when the sector is unresolved.
     *
     * spec §6.2: for [Sector.OTHER] we must not assert an industry-norm-dependent finding, and
     * *"every surviving flag must carry an evidence row"* saying so. Absence of the row would
     * let a user read a norm-dependent flag as a universal one.
     */
    const val UNRESOLVED_SECTOR_VALUE: String = "Unresolved (industry norms not applied)"

    /** Indian fiscal years end in March; the `Mar 2026` column is the year ended 31 Mar 2026. */
    fun fiscalYearEnd(year: Int): java.time.LocalDate =
        java.time.LocalDate.of(year, RatioComputation.FISCAL_YEAR_END_MONTH, 31)

    /**
     * A check that our sources can never produce, declared rather than hidden.
     *
     * spec §2.3 and §4.4/§4.5: goodwill and promoter pledge are on plan.md §8.1's list, are
     * absent from every one of the five captured fixtures (`grep -i goodwill` and
     * `grep -i pledg` both return zero hits), and must ship as a declared capability gap —
     * with the rules present, returning nothing, and a reason a Settings screen can print.
     * Substituting a neighbouring line (fixed assets for goodwill, promoter *holding* for
     * pledge) would be a lie in both cases: goodwill is a subset of fixed assets, and holding
     * is not pledge.
     */
    data class CoverageGap(
        /** The flag id that exists but can never fire today. */
        val ruleId: String,
        /** What is missing, phrased for a user. */
        val missingSource: String,
        /** Why the gap exists, phrased for whoever can close it. */
        val note: String,
    )

    /** The two permanently-unsourceable checks (spec §2.3). Reported, never faked. */
    val DECLARED_COVERAGE_GAPS: List<CoverageGap> = listOf(
        CoverageGap(
            ruleId = FundamentalsFlagIds.GOODWILL_ABOVE_20PCT_ASSETS,
            missingSource = "Goodwill is not published by screener.in's consolidated page. " +
                "It sits inside the 'Fixed Assets +' drill-down behind an AJAX sub-schedule that " +
                "plan.md §3.6 does not permit us to fetch.",
            note = "Checked across all five captured fixtures: zero hits for 'goodwill'. The " +
                "arithmetic is implemented against CashFlowDerived.goodwillByYear, which stays " +
                "empty, so the flag returns nothing rather than substituting fixed assets, " +
                "because goodwill is a subset of fixed assets and the substitution would " +
                "misstate the ratio.",
        ),
        CoverageGap(
            ruleId = FundamentalsFlagIds.PROMOTER_PLEDGING,
            missingSource = "Promoter pledge is not published. The shareholding card exposes " +
                "promoter holding, promoter count and FII/DII/government holdings — not pledge.",
            note = "Checked across all five captured fixtures: zero hits for 'pledg'. " +
                "CashFlowDerived.promoterPledgePercent stays null and never 0; promoter holding " +
                "is a different number and must never be shown as a pledge.",
        ),
    )
}