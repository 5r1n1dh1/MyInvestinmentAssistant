package com.mia.app.domain.education

/**
 * The INTERMEDIATE curriculum: nine modules, 35 lessons.
 *
 * ### What "intermediate" means here
 *
 * The beginner level answers "what is this word". This level answers "what would make me believe
 * it", and it does that in three ways: by showing the same measure on several real companies at
 * once, by showing the arithmetic behind a figure rather than the figure alone, and by naming the
 * place a reader has to go when this app's sources stop.
 *
 * ### The grounding rule is stricter here, not looser
 *
 * Every drill in this file is built from the five captured screener.in pages, the 372-session
 * end-of-day fixture, or a constant that an existing test in this repository pins. Where a lesson
 * needed a number that does not exist in this repository — a bank's net interest margin, a tax
 * rate, a fund's expense ratio — the lesson teaches the concept and names the source the reader
 * has to use instead, rather than filling the gap with a plausible-looking figure.
 *
 * ### Registration
 *
 * As in [BEGINNER_MODULES], Kotlin has no top-level `init` block, so the registration into
 * [Curriculum] rides on this property's own initialiser through `also`. Touching
 * [INTERMEDIATE_MODULES] is what performs it.
 */
val INTERMEDIATE_MODULES: List<Module> = listOf(

    // =========================================================================
    // 1. Ratio reasoning drills
    // =========================================================================
    Module(
        id = "intermediate.ratio.drills",
        level = Level.INTERMEDIATE,
        title = "Ratio reasoning drills",
        summary = "The priority order the fundamentals tab is built on, then three real ratio " +
            "cards read against it — including the one where the lowest return on capital " +
            "employed belongs to a bank rather than to a weak business.",
        lessons = listOf(

            Lesson(
                id = "intermediate.ratio.drills.order",
                title = "The order, and the reason printed beside each slot",
                body = """
                    The fundamentals tab is a ranked list, and the ranking is an argument worth reading rather than a sort order worth accepting. Twelve slots, in this order: return on capital employed, described as the best single measure of business quality and hard to manipulate; operating margin, for pricing power and cost control; debt to equity with interest coverage, for survival; free cash flow against profit after tax, called the number one earnings-quality tell; revenue growth, for trajectory; return on equity, marked as duplicated leverage and therefore deliberately ranked below return on capital employed; gross margin, for input-cost pressure; the ratio to a sector median multiple; price to book, scoped to asset-heavy businesses; enterprise value to EBITDA; promoter holding; and dividend yield with payout.

                    Two of those reasons are worth pausing on, because they are the whole design in one line each. Return on capital employed leads because debt does not inflate it. Return on equity sits sixth for the opposite reason: debt does.

                    Three slots on that list cannot be filled from this app's sources and stay in place anyway — gross margin, the current ratio and enterprise value to EBITDA. Keeping an unfillable slot visible is the point: a reader can see that the software knows the card and cannot populate it, which is a different and more useful statement than a card that is quietly absent.
                """.trimIndent(),
                doesNotEstablish = "This order is a fixed editorial ranking chosen before any " +
                    "data arrives, with the rationale printed on each card. It establishes which " +
                    "measure the app leads with; it does not establish that a company is a good " +
                    "business, that the ranking is optimal, or that any slot in it is measurable " +
                    "for the company being read.",
                relatedRatio = "ROCE",
            ),

            Lesson(
                id = "intermediate.ratio.drills.cards",
                title = "Three cards, read in the order the app ranks them",
                body = """
                    Three of the five captured pages, taken in return-on-capital-employed order. TCS: return on capital employed 63.0%, return on equity 51.8%, P/E 14.0, market capitalisation 750,753 crore, book value 296 per share, interest coverage 63.5354 times, cash from operations against operating profit of 0.92, debtor days moving from 84 to 93. RELIANCE: 10.3%, 8.91%, P/E 21.2, market capitalisation 1,580,194 crore, book value 668, coverage 8.7489 times, debtor days 16 to 20, inventory days 88. TITAN: 20.5%, 37.7%, P/E 68.7, market capitalisation 400,898 crore, book value 177, debt to equity 1.95 times, coverage 7.78 times, inventory days 169 in 2015 rising to 222 in 2026.

                    Reading them against the order produces three different answers rather than one. On return on capital employed TCS leads by a wide margin, and the card's own reason — pricing power and cost control — is visible in a services business whose operating margin needs no inventory and whose capital base is small. On leverage, TITAN carries 1.95 times debt to equity where RELIANCE carries 0.4457, and the leverage slot is a survival question, not a quality one. On the multiple, TITAN's 68.7 sits at position 8 of 12 with no room above it, while TCS's 14.0 sits in the same slot and means something entirely different.

                    The trap for a beginner is to collapse that into a league table. The order exists precisely so the collapse cannot happen: a business is read top-down through the slots, and the first slot is the one the app trusts most.
                """.trimIndent(),
                doesNotEstablish = "Comparing three published ratio cards establishes their " +
                    "relative positions on the measures the pages publish. It does not establish " +
                    "which business is better, which is more attractively priced, or which is " +
                    "safer — the multiples in particular are not comparable across three different " +
                    "business models.",
                relatedRatio = "ROCE",
            ),

            Lesson(
                id = "intermediate.ratio.drills.bank",
                title = "Why the bank card looks broken and is not",
                body = """
                    HDFCBANK's page reports return on capital employed of 7.02%, the lowest of the five, and return on equity of 13.6%, the lowest but one. If the first slot on the list is "the best single measure of business quality", a card that puts the bank at the bottom looks like a broken ranking.

                    It is not, and the reason is visible in the rest of the numbers. The same page reports borrowings of 588,485 crore, deposits of 3,099,638 crore, financing profit of −40,511 crore and profit after tax of 79,219 crore. The denominator of return on capital employed is the capital a business needs in order to operate, and for a lender the productive asset is its loan book, which is funded overwhelmingly by deposits. Dividing by anything else — by equity, by a capital-employed figure built for an industrial company — measures a different thing.

                    So the bank override replaces the opening slots rather than appending to the generic list: net interest margin, gross and net non-performing assets, CASA and provision coverage lead, return on equity is promoted to first position among the surviving generic slots, and debt to equity, interest coverage, operating margin and the current ratio are removed with their reasons printed. A bank does not get debt to equity and net interest margin side by side. It gets net interest margin.
                """.trimIndent(),
                doesNotEstablish = "Explaining why a bank's return on capital employed looks " +
                    "lowest establishes that the generic ranking does not transfer to a lender's " +
                    "capital structure. It does not establish the bank's profitability, asset " +
                    "quality or suitability, and the four ratios that would answer those questions " +
                    "are the ones this app cannot source.",
                relatedRatio = "GNPA_NNPA",
            ),

            Lesson(
                id = "intermediate.ratio.drills.leverage",
                title = "Why return on equity is ranked below return on capital employed",
                body = """
                    Take the five captured pages and compare the two return measures on each. TCS: 63.0% against 51.8%. INFY: 40.0% against 31.9%. RELIANCE: 10.3% against 8.91%. HDFCBANK: 7.02% against 13.6%. TITAN: 20.5% against 37.7%.

                    On two pages return on equity sits below return on capital employed and on two it sits above, sometimes by a wide margin. That gap is the reason for the ranking, and the arithmetic behind it is worth doing once. Return on equity is return on capital employed multiplied by a leverage factor, because the numerator sits on top of a base that debt has enlarged. A business can therefore lift its return on equity without its operations changing at all, simply by carrying more debt. Return on capital employed is unmoved by that choice, which is exactly why it leads the list and why its card is annotated as hard to manipulate.

                    The practical consequence for a reader is a rule: look at return on capital employed first to see what the business does with the capital it needs, and only then look at return on equity to see how much of the result is the business and how much is the financing.
                """.trimIndent(),
                doesNotEstablish = "The relationship between the two returns, and the ranking " +
                    "derived from it, establish how to read a pair of published numbers. They do " +
                    "not establish that low leverage is desirable, that a high return on capital " +
                    "employed is sustainable, or that any company is financially sound.",
                relatedRatio = "ROE",
            ),
        ),
    ),

    // =========================================================================
    // 2. Earnings-quality lab
    // =========================================================================
    Module(
        id = "intermediate.earnings.quality",
        level = Level.INTERMEDIATE,
        title = "Earnings-quality lab",
        summary = "Four checks that ask whether a company's reported profit became cash: " +
            "operating cash flow against profit, debtor days, inventory days, and how much of " +
            "profit came from outside operations.",
        lessons = listOf(

            Lesson(
                id = "intermediate.earnings.quality.cfo_pat",
                title = "Operating cash flow against profit after tax",
                body = """
                    Profit after tax is an accounting estimate; cash from operating activities is money that actually moved. When they agree, the reader has corroboration. When they diverge for years at a stretch, one of them is telling a different story from the other, and which one is the outlier usually only becomes clear afterwards.

                    The numbers on the captured pages. RELIANCE: cash from operations of 192,113 crore against profit after tax of 95,754 crore, free cash flow of 70,023 crore, and the page's own cash-to-operating-profit ratio printed as 113%. TCS: 52,094 crore of operating cash flow, free cash flow of 48,013 crore, and the same ratio at 0.92. INFY: 33,986 crore of operating cash flow against 29,474 crore of profit. HDFCBANK: 113,506 crore against 79,219 crore. TITAN: minus 541 crore of operating cash flow and minus 1,011 crore of free cash flow.

                    The rule attached to this is deliberately conservative: it fires only when operating cash flow is below zero in at least two consecutive years while profit after tax stays positive, and it escalates at three. TITAN's negative year does not trip it, and that is the correct outcome — one negative year out of a long profitable history is a data point, not a pattern.
                """.trimIndent(),
                doesNotEstablish = "Cash from operations and profit after tax for one financial " +
                    "year establish two reported figures and their ratio. They do not establish " +
                    "the quality or honesty of either, and a single year of divergence is a fact " +
                    "about that year rather than about the business.",
                relatedRatio = "FCF_VS_PAT",
                relatedFlag = "CFO_NEGATIVE_WHILE_PAT_POSITIVE",
            ),

            Lesson(
                id = "intermediate.earnings.quality.debtor_days",
                title = "Debtor days, and why the receivables check is measured in days",
                body = """
                    Debtor days is a rough count of how long the business waits to be paid: receivables expressed as a fraction of a year's revenue. A rising figure means customers are taking longer, which means part of the reported profit has not yet turned into cash.

                    The app runs this check in days rather than in rupees for a concrete reason: the source page publishes no trade-receivables line at all. A grep across all five captured pages finds no receivables row on the balance sheet. The `ratios` card on the same page does publish a twelve-year debtor-days series, so the check evaluates the same idea — receivables growing faster than revenue — in the units that are actually published, and both the evidence and the threshold say which form was used.

                    The captured figures show how narrow the margin is. RELIANCE's debtor days moved from 16 to 20, a four-day rise. TCS moved from 84 to 93 — a nine-day rise, just under the ten-day floor the rule requires, so it stays quiet. A threshold that fires on a four-day move in one industry and a nine-day move in another would be measuring the industry, not the company.
                """.trimIndent(),
                doesNotEstablish = "Debtor days as published, and its year-on-year change, " +
                    "establish one rough measure of collection speed. They do not establish that " +
                    "customers are financially distressed, and the ten-day floor is a derived " +
                    "threshold calibrated on norm-dependent series rather than a measured " +
                    "distress signal.",
                relatedFlag = "RECEIVABLES_GROWING_FASTER_THAN_REVENUE",
            ),

            Lesson(
                id = "intermediate.earnings.quality.inventory_days",
                title = "Inventory days, and why norms matter more than the number",
                body = """
                    Inventory days measures how long stock sits before it is sold. On the captured pages RELIANCE publishes 88 days for March 2026, and TITAN's twelve-year series runs from 169 in 2015 to 222 in 2026. A jewellery retailer holding a year and a quarter of stock and an oil refiner holding three months are both doing something completely ordinary.

                    The app's rule fires on a year-on-year rise of at least ten days. TITAN's most recent move is five days on a series that ranges across 169 to 222, so it stays quiet — and that is the intended behaviour, not a near miss. A ten-day move inside a 53-day range is noise.

                    There is a further honesty rule here that is worth knowing about, because it means a flag can be absent for a reason other than a passing measurement. Both working-capital checks compare against an industry norm, and this app has no industry norms. When a company's sector cannot be resolved, the app suppresses those checks outright rather than lowering the threshold, because an unresolved sector is precisely the case where the norm is least knowable. RELIANCE's own captured page is the example: its sector resolves as unresolved, and its evidence row says so in as many words.
                """.trimIndent(),
                doesNotEstablish = "An inventory-days figure and its annual change establish one " +
                    "measure of stock turnover for one year. They do not establish that stock is " +
                    "excessive, unsaleable or mispriced, and where the sector is unresolved the " +
                    "app abstains from these checks entirely rather than reporting a pass.",
                relatedRatio = "WORKING_CAPITAL_DAYS",
                relatedFlag = "INVENTORY_DAYS_RISING",
            ),

            Lesson(
                id = "intermediate.earnings.quality.other_income",
                title = "How much of the profit came from outside the business",
                body = """
                    Other income is the part of profit that did not come from selling the company's product: interest, asset sales, one-off receipts. A company earning most of its profit there is a different business from what its primary line suggests.

                    RELIANCE's captured page is the worked example, and it is the single fundamentals flag the golden pages produce. Other income of 28,846 crore against profit after tax of 95,754 crore is 30.13%, and the threshold is thirty percent. The reportable figure rounds to 30.1%, with both figures printed beside it and the ratio stated as its own evidence row.

                    What makes this example worth studying is that the threshold's calibration is itself contested, and the app says so rather than quietly adjusting it. The thirty percent band was derived against RELIANCE's trailing-twelve-month column, where the same two figures read 20,393 and 88,167 — 23.13%, comfortably inside the band. This app's parser drops the trailing column by design, so the annual columns are what get compared, and the fixture that justified the band now trips it. Rather than raise the band or exempt the company, the behaviour is asserted in a test and the conflict is recorded for the owner to resolve. A threshold that is quietly rewritten to make a flagship fixture pass is a threshold that can no longer be trusted.
                """.trimIndent(),
                doesNotEstablish = "An other-income share of profit after tax establishes the " +
                    "arithmetic ratio for one fiscal year. It does not establish that the income is " +
                    "unusual, non-recurring or improper, and the thirty percent threshold is a " +
                    "derived band with a recorded calibration conflict rather than a measured " +
                    "cut-off.",
                relatedFlag = "OTHER_INCOME_AS_PCT_OF_PAT",
            ),
        ),
    ),

    // =========================================================================
    // 3. Reading a bank
    // =========================================================================
    Module(
        id = "intermediate.reading.a.bank",
        level = Level.INTERMEDIATE,
        title = "Reading a bank",
        summary = "The four ratios a lender is judged on, taught alongside the measured fact " +
            "that this app cannot see any of them — and exactly where a reader goes instead.",
        lessons = listOf(

            Lesson(
                id = "intermediate.reading.a.bank.nim",
                title = "Net interest margin, the slot that replaces operating margin",
                body = """
                    A bank buys and sells money. It takes deposits and borrowings, pays interest on them, and lends the proceeds at a higher rate. The gap between the two is its net interest margin, expressed as a percentage of average interest-earning assets, and it is the nearest thing a lender has to an operating margin.

                    Two features make it the first question rather than a supporting one. It is the bulk of how a bank earns, so a change in it moves profit more than anything else on the page. And it is negotiated: a bank can raise it by repricing loans or by reducing the deposit rate, both of which are decisions rather than accidents, and both of which have consequences a reader has to weigh separately.

                    On the captured HDFCBANK page the ratio is absent entirely. The company publishes a financing margin row instead of an operating profit row, which is one reason the generic operating-margin slot is removed for a bank rather than merely ranked lower. And the net interest margin slot, which is in the plan for banks precisely because it matters, comes back as not sourced.
                """.trimIndent(),
                doesNotEstablish = "Explaining net interest margin establishes what the ratio " +
                    "measures and why it leads a bank's list. It establishes no value for HDFCBANK " +
                    "or any other bank, and no statement about a lender's pricing power, deposit " +
                    "franchise or profitability.",
                relatedRatio = "NET_INTEREST_MARGIN",
            ),

            Lesson(
                id = "intermediate.reading.a.bank.asset_quality",
                title = "Gross and net non-performing assets, and the stage that precedes them",
                body = """
                    A non-performing asset is a loan on which the borrower has stopped paying as agreed. Gross non-performing assets are that share of the book measured before provisions; net non-performing assets are the same share after the provisions held against them. Both matter, and they matter for different reasons: gross says how much of the book has gone wrong, and net says how much of the loss the bank has already absorbed. A large gross number with a large provision behind it and a small gross number with no provisions behind it are very different situations.

                    The stage beneath is the one that moves first. Stage-two assets are loans that have deteriorated enough to be reclassified but are not yet non-performing — a warning rather than an event, and the one a reader would want earliest. Non-bank financial companies carry it as its own slot for exactly that reason.

                    This app keeps the gross and net slot and the stage-two slot in its plan and can fill none of them. It reports them as not sourced and moves on. The values are where the judgement is, so a lesson that supplied plausible-looking ones would be worse than useless.
                """.trimIndent(),
                doesNotEstablish = "Gross and net non-performing assets and stage-two ratios are " +
                    "defined here, and the app reports no value for any of them. Nothing in " +
                    "this module establishes the credit quality of any loan book, the adequacy " +
                    "of any bank's provisioning, or the stage at which any specific loan sits.",
                relatedRatio = "GNPA_NNPA",
                relatedFlag = "GOODWILL_ABOVE_20PCT_ASSETS",
            ),

            Lesson(
                id = "intermediate.reading.a.bank.casa_pcr",
                title = "CASA and provision coverage: the funding cushion and the loss cushion",
                body = """
                    CASA is current account savings as a share of total deposits. It is the cheapest money a bank can hold: transactional balances that do not reprice when the policy rate moves, so the margin they support is not squeezed by a rate cycle. A bank whose deposits are almost entirely CASA has a structurally different cost of funds from one funding itself in term deposits, and the difference shows up in its margin long before it shows up in its profit.

                    Provision coverage is provisions held against gross non-performing assets. Read as a cushion it answers one question: if the bad loans were written off in full, how much of the loss is already absorbed? A low provision coverage alongside a rising gross bad-loan share is the combination a reader is looking for, which is why the two are best read together rather than separately.

                    Both are on the page as labels and absent as values, which is the exact shape of the sourcing problem the next lesson unpacks. Note also that CASA matters most where the deposit base is retail and least where it is wholesale, so a single number across banks is comparing different things.
                """.trimIndent(),
                doesNotEstablish = "CASA and provision coverage are defined here and no value " +
                    "for either is available from this app's sources. They do not establish " +
                    "that a bank is better funded or better provisioned than another, and a CASA " +
                    "share is not comparable across banks with different deposit mixes.",
                relatedRatio = "CASA_RATIO",
            ),

            Lesson(
                id = "intermediate.reading.a.bank.where_to_verify",
                title = "Where to verify these four yourself",
                body = """
                    Here is the measured fact this module exists to deliver. A live probe of the HDFCBANK page searched for nine bank-ratio labels. Three label strings were found — gross non-performing assets twice, net once, CASA once — and six were not found at all, including net interest margin and provision coverage. Read that table on its own and you would conclude three ratios could be promoted into the app.

                    That conclusion is wrong, and the probe's own numbers show why: one occurrence each, on a page of 218 KB. Inspecting the fetched page directly, those labels sit inside sixty paywall cells — the label renders, the value cell is empty, and a sign-in button follows. The standalone ratio table carries the row headers with no values whatsoever. For contrast, a ratio the app already reads renders an actual figure, in a name span followed by a number span reading 7.02. A label grep is not a value parse, and any future promotion has to pin a captured page and assert a parsed number rather than a string match.

                    So the honest alternative is where to go. A bank's own annual report carries all four ratios with definitions and three years of history. The Reserve Bank of India's Financial Stability Report carries the system-wide picture, which is the context that turns one bank's number into something comparable. And because announcements and corporate-action pages are unreachable from this network, the app's own conclusion about an unusual move is to tell you where to confirm it rather than assert an event it never saw.
                """.trimIndent(),
                doesNotEstablish = "The probe's finding establishes that these four ratios are " +
                    "behind a login on this particular free source and are therefore outside what " +
                    "this app can read. It establishes nothing about the bank's actual values, and " +
                    "a label match on any page is never evidence that a figure was published.",
                relatedRatio = "PROVISION_COVERAGE_RATIO",
            ),
        ),
    ),

    // =========================================================================
    // 4. The measurement curriculum
    // =========================================================================
    Module(
        id = "intermediate.measurement",
        level = Level.INTERMEDIATE,
        title = "The measurement curriculum",
        summary = "What a sample size of thirty means, why a hit rate is not a win rate, what a " +
            "base rate is, and how a fixed Indian round-trip cost eats a small edge.",
        lessons = listOf(

            Lesson(
                id = "intermediate.measurement.n30",
                title = "What thirty samples actually buys you",
                body = """
                    Thirty is a floor, not a certificate. A hit rate computed over thirty settled cases carries real information; computed over three it carries almost none, and computed over zero it is a division by nothing. The app withholds anything below thirty rather than printing a small percentage that looks like a finding.

                    The threshold binds exactly where it should and where it is uncomfortable. On the measured walk over 15 large caps and 372 sessions, the strictest grouping — one stock and one pattern together — produced 45 cells holding 20 or 21 cases each. Every one of them is below thirty, so the entire per-stock table is withheld and the screen says so with the count: "21 of 30 samples". Group by stock alone and you get 62 cases per cell; group by pattern alone and 310. Both clear comfortably.

                    That asymmetry is the honest outcome of a strict grouping rule applied to eighteen months of history for one stock. The alternative — loosening the rule until the table fills — would produce numbers that pass a sample-size bar set by the reader's impatience rather than by the data. Where three formations measured on the whole walk, the app shows the cross-stock pool instead, with its own count: 58 reached in 310 cases, 18.7% (n=310).
                """.trimIndent(),
                doesNotEstablish = "A thirty-sample floor and the withholding behaviour below it " +
                    "establish how much history the app demands before printing a percentage. " +
                    "They do not establish that thirty cases is statistically sufficient for any " +
                    "particular claim, nor that a reportable hit rate is a favourable one.",
            ),

            Lesson(
                id = "intermediate.measurement.hit_is_not_win",
                title = "A hit is a level reached, not a profit",
                body = """
                    This is the single most misunderstood number in the app, so it is worth taking apart completely.

                    A projection is made at a breakout point with a measured-move level above or below it. An outcome is settled once five bars have elapsed, and it counts as a hit if the level was reached inside those five bars — on either side of price, as the rules define it. That is the whole definition. No cost is applied, no position is sized, no exit is chosen, and the projection is not compared with the alternative of doing nothing.

                    The measured figure over the walk is 153 hits out of 930 settled cases, 16.45% (n=930), split 76 of 464 upward and 77 of 466 downward. The near-perfect symmetry by direction is itself informative: whatever is being measured, it is not tracking a directional advantage.

                    And the distance buckets explain the level. At one percent away the hit rate is 33.3%, at two percent 26.5%, three 17.9%, four 13.9%, five 10.0%, six 7.2% and seven 4.9% — monotone, every step. That is what a fixed-distance event does: its likelihood is governed by how far away the level sits, not by any property of the pattern. The simulated moves averaged four to five percent of price while the median five-session favourable excursion was about 1.8%, which is exactly the arithmetic that produces those buckets.
                """.trimIndent(),
                doesNotEstablish = "A hit rate of 16.45% over 930 settled cases, n=930, and its " +
                    "distance buckets establish how often a measured-move level was reached in a " +
                    "tracked sample. They do not establish a win rate, a return, an advantage, or " +
                    "anything about a reader who acted on the projection.",
                relatedPattern = "Measured Move",
            ),

            Lesson(
                id = "intermediate.measurement.base_rates",
                title = "Base rates, and why they are the first thing to check",
                body = """
                    A base rate is the frequency of an outcome with no reference to any signal at all. On the Indian equity data this project is built on it is very close to one half — which is the single most useful number in the whole project, because it is the number every signal has to beat.

                    Measured over 20,780 next-day returns across 20 large caps: always higher 50.10%, always lower 49.90%, RSI on 14 sessions 50.24% with a correlation of −0.0033, 200-session momentum 50.17% at −0.0024, and a z-score against the 50-session average 49.81% at −0.0109. The median next-day move was 0.73% and the mean 0.99%. The app's own committed fixture reproduces it: 2,723 of 5,565 next-session pairs were higher, 48.93%.

                    The correlations matter more than the percentages. A correlation of −0.0033 is indistinguishable from zero on any sample, so those three readings carry essentially no linear information about the next session. And the 0.99% mean against a 0.73% median says the distribution has a longer right tail than left — worth knowing about a market whose day-to-day moves are not symmetric around zero.

                    The discipline this installs is simple: before asking whether a signal works, establish how often the thing happens without it. Most claims in this space die at that first step.
                """.trimIndent(),
                doesNotEstablish = "A base rate near one half, measured over 20,780 next-session " +
                    "returns and reproduced at 48.93% on this app's own fixture, establishes that " +
                    "these readings show almost no linear relationship with the next session on " +
                    "this data. It does not establish that no technique can work, and the " +
                    "measurement covers the past only.",
            ),

            Lesson(
                id = "intermediate.measurement.expectancy_and_costs",
                title = "Expectancy, and why a small edge can be entirely eaten",
                body = """
                    Expectancy is the average result per attempt, and it is computed from two things a hit rate is not: how often an attempt is right, and how much it makes or loses when it is. The form is the proportion of winning attempts multiplied by the average win, minus the proportion of losing attempts multiplied by the average loss.

                    The worked example below is illustrative only — these are not figures measured anywhere in this repository, they are chosen to make the arithmetic visible. Take a method that is correct 55% of the time, averaging a four percent gain when right and a three percent loss when wrong. Expectancy works out at 0.55 times 4 minus 0.45 times 3, which is 2.2 minus 1.35, so eighty-five hundredths of one percent per attempt before costs.

                    Now put the round trip on it. Securities transaction tax is charged on the trade, GST on the brokerage charge, brokerage is charged per execution, and slippage takes the difference between the screen price and the fill. Every one of those is charged on both the winning attempt and the losing one, so it comes straight off that eighty-five hundredths. The project's own conclusion on a 50.5% accuracy case says the same thing in one sentence: at that level the cost stack exceeds the edge entirely.

                    The arithmetic is why this app refuses to publish a single combined score. A genuine edge after costs is the thing worth having, and a tool that cannot demonstrate one honestly should show the components and the cost structure rather than a headline.
                """.trimIndent(),
                doesNotEstablish = "The expectancy arithmetic and the illustrative numbers in " +
                    "this lesson establish how a small edge interacts with a fixed round-trip " +
                    "cost. The figures are invented for the purpose of the example and are not " +
                    "measurements; nothing here establishes any reader's expectancy, any actual " +
                    "cost, or the profitability of any method.",
            ),
        ),
    ),

    // =========================================================================
    // 5. Corporate-actions case studies
    // =========================================================================
    Module(
        id = "intermediate.corporate.actions",
        level = Level.INTERMEDIATE,
        title = "Corporate-actions case studies",
        summary = "Four real events hiding inside this app's own committed price fixture, the " +
            "rule that finds them, and the Tata Motors case where the symbol itself changed.",
        lessons = listOf(

            Lesson(
                id = "intermediate.corporate.actions.the_four",
                title = "Four unadjusted corporate actions in one fixture",
                body = """
                    The app's committed price fixture is 372 real sessions, from 2025-04-01 to 2026-10-01, across 41 instruments, assembled from the NSE archive because the alternative history source is unreachable from this network. It is deliberately raw. A fixture that quietly hid four real splits would be a fixture that taught the tests to be comfortable with them.

                    Here they all are, read straight out of the committed file. HDFCBANK on 2025-08-26 moved from 1964.1 to 973.4, a fall of 50.4%, which is almost certainly a one-for-two split or bonus issue. BAJFINANCE on 2025-06-16 moved from 9331 to 938, a fall of 89.9%, almost certainly a one-for-ten split. KOTAKBANK on 2026-01-14 moved from 2132.6 to 421, a fall of 80.3%, almost certainly a one-for-five split. TATAMOTORS on 2025-10-14 moved from 660.75 to 395.45, a fall of 40.2%, in the middle of a demerger.

                    None of these is a market event. All four are real. The prices are stored exactly as published, and the app tells the reader instead of rewriting history — which is the subject of the next two lessons.
                """.trimIndent(),
                doesNotEstablish = "The four moves establish that real, unadjusted corporate " +
                    "actions sit inside this app's committed price data. The implied ratios are " +
                    "described as almost certainly rather than confirmed, because the app cannot " +
                    "read the announcements that would confirm them.",
            ),

            Lesson(
                id = "intermediate.corporate.actions.detector",
                title = "The rule that finds them, and why the note is not a red card",
                body = """
                    The detector flags a single-bar move of twenty-five percent or more between near-adjacent sessions. Three choices in that one sentence are all deliberate.

                    Twenty-five percent was chosen rather than guessed. Ordinary daily moves in this market run to around ten percent, so a threshold low enough to catch a one-for-one-and-a-half consolidation would fire constantly and train a reader to ignore the note; twenty-five sits above routine volatility and below a one-for-two, which is fifty percent. The rule is symmetric in sign, because a demerger or reverse split can re-rate a price upward and a silently broken series misleads exactly as much as a falling one. And near-adjacency is required — a one-to-four session gap — because a change across a long gap says nothing about the interval.

                    The detector has to over-accept, and the reason is calendar rather than statistical: Indian markets are shut at weekends, so every real split straddles one. A rule that rejected a split for falling on a Saturday would miss every split in the country.

                    Finally, the note about a possible corporate action renders in a neutral container rather than an error one. Nothing has failed. A red card would say the app is broken, when what has actually happened is that the source publishes unadjusted prices — which is true of every major price feed that does not adjust.
                """.trimIndent(),
                doesNotEstablish = "A twenty-five percent single-bar rule establishes that a " +
                    "price discontinuity large enough to be a split was detected. It does not " +
                    "establish what the event was, that any particular ratio applies, or that the " +
                    "price on either side was wrong.",
            ),

            Lesson(
                id = "intermediate.corporate.actions.tata_motors",
                title = "Tata Motors: when the symbol itself changes",
                body = """
                    Not every case study ends with a split. TATAMOTORS trades for the first 140 of the fixture's 372 sessions and then never appears again. It does not come back on another ticker — it stops, which the resolver reports as a disappeared symbol rather than a resumed one.

                    The symbol master shows what replaced it, and it is not as simple as it looks. It lists TMCV, Tata Motors Limited, with a new ISIN and a listing date of 2025-11-12. It also lists TMPV, Tata Motors Passenger Vehicles, which inherited a retired ISIN and has been listed since 1998. TMPV is a different company. Mapping a reader who searched TATAMOTORS to the passenger-vehicle entity would send them to the wrong business with complete confidence, so the resolver offers TMCV as the answer and TMPV as an alternative.

                    This is why the resolution ladder exists and reports which rung fired: exact match, then a normalised match, then a corporate-action alias, then an ISIN lookup, then fuzzy edit distance, and finally an explicit unresolved rather than a silent zero results. Reconciling the master against one day's prices makes the scale visible — 2,313 matched, 280 present only in the master, 738 present only in the daily file, and zero ISIN mismatches on that date. Small-company instruments exist only in the daily file and never in the master, which is one reason the app keeps them as a separate series.
                """.trimIndent(),
                doesNotEstablish = "Resolving TATAMOTORS to TMCV establishes which listed entity " +
                    "inherited the name in this source's symbol master. It does not establish " +
                    "the terms of any demerger, the fairness of the split between entities, or " +
                    "that either company is a sound investment.",
            ),

            Lesson(
                id = "intermediate.corporate.actions.indicator_damage",
                title = "What a minus ninety percent bar does to every indicator",
                body = """
                    A single bar of minus 89.9% is not noise; it is a corruption. It destroys a 200-session average for weeks afterwards and it will trip trend, momentum and volume rules that have nothing whatsoever to do with the business. Every indicator in this app is a weighted function over a window, so one bad bar changes every value in that window.

                    Two measurements in this project are about that. First, the chart-pattern agent ran detection with those sessions present and then removed, and the counts of detected formations came out identical — the structural gates turn out to be robust to a discontinuity, which was not an assumption anybody was entitled to make in advance.

                    Second, because point-in-time readings cannot distinguish a working rule from a broken one, the technical red-flag rates were measured over a rolling walk of 4,407 symbol-sessions. RSI oversold fired on 3.34% of observations, the distribution rule on 1.41%, and the 52-week-low rule on 6.85% — all plausible rates for rules that work. The death cross fired on 49.83%, independently reproduced outside this codebase at 49.49%, and that number is also plausible once you notice the rule describes a state rather than an event.

                    The honest conclusion sits between the two: structural detection survives an unadjusted discontinuity, and readers of a long-window indicator should know when a discontinuity is inside their window. The app shows the note beneath the chart, because the note's effect is the chart.
                """.trimIndent(),
                doesNotEstablish = "Identical detection counts with and without the corporate-" +
                    "action sessions establish that this app's structural pattern gates tolerate " +
                    "those bars. They do not establish that indicators are unaffected, that the " +
                    "measured flag rates hold on other data, or that any particular reading is " +
                    "correct.",
                relatedPattern = "Measured Move",
            ),
        ),
    ),

    // =========================================================================
    // 6. Delivery & volume analysis
    // =========================================================================
    Module(
        id = "intermediate.delivery.volume",
        level = Level.INTERMEDIATE,
        title = "Delivery and volume analysis",
        summary = "What delivery percentage is in an Indian cash market, the two volume rules and " +
            "their exact thresholds, and why volume confirms rather than decides.",
        lessons = listOf(

            Lesson(
                id = "intermediate.delivery.volume.delivery_percent",
                title = "What delivery percentage measures, and how thin the source is",
                body = """
                    Delivery is the share of a session's traded volume that changed hands into delivery rather than being bought and sold intraday. A high figure says more of the day's volume was absorbed rather than churned. That is all it says.

                    The figures on one measured day: RELIANCE 61.24, HDFCBANK 63.31, TCS 54.90, INFY 51.96 and 20MICRONS 46.10. Notice the spread — a mid-sized name sits well below a large one on the same day, which is the first hint that the measure carries a size effect rather than a conviction effect.

                    The source is thinner than the numbers look. The file returned 3,534 rows of which 3,262 carried a numeric value, so roughly one row in eight is blank, and every column name in it has a leading space — a detail that broke a naive parser and is now permanently guarded by a test. Delivery is optional everywhere in the app: the price layer never depends on it, a candle carries null when it is missing, and the one rule that needs it abstains with a stated reason rather than guessing.
                """.trimIndent(),
                doesNotEstablish = "A delivery percentage establishes the ratio of delivered volume " +
                    "in one session as published. It does not establish who traded, whether the " +
                    "trading was informed, or that a high or low reading continues — the " +
                    "app's own rule text notes that a low delivery percentage is not necessarily " +
                    "bearish.",
            ),

            Lesson(
                id = "intermediate.delivery.volume.collapse_rule",
                title = "The delivery-collapse rule and its three guards",
                body = """
                    The rule is: delivery percentage at or below forty percent of its own twenty-session average, for two consecutive sessions. The comparison is against the stock's own recent history rather than against a fixed number, which is what makes it work across a stock that normally delivers heavily and one that normally does not.

                    Three guards keep it from over-firing, and each one is a decision worth understanding. It runs only on daily bars, because delivery is published per session and there is no weekly equivalent. It refuses to run unless at least three quarters of the twenty-session window carries a delivery figure, so a mostly-missing file produces silence instead of a number. And it declines to fire below an average daily turnover of a hundred thousand shares, because the underlying text is explicit that volume in small caps can be manipulated; below that floor the rule reports that it did not evaluate rather than reporting a pass.

                    It also escalates only with context: the higher severity needs delivery to have been above its own average on each of the five sessions immediately before the collapse, and price to have fallen over that stretch. Participation leaving after a run of heavy delivery is a different statement from participation that was never there.
                """.trimIndent(),
                doesNotEstablish = "A delivery-collapse flag establishes that delivery fell to " +
                    "at or below forty percent of the stock's own twenty-session average for two " +
                    "sessions. It does not establish distribution by informed sellers, and the " +
                    "hundred-thousand-share floor is a derived number awaiting review against a " +
                    "real sample.",
                relatedFlag = "DELIVERY_COLLAPSE",
            ),

            Lesson(
                id = "intermediate.delivery.volume.distribution_day",
                title = "Reading a distribution bar, and the day it might not be one",
                body = """
                    The distribution rule looks for volume at or above 2.5 times the twenty-session average on a bar that closed lower, and it escalates to the higher severity at four times. Above average volume means above average participation — that much is arithmetic. It does not mean selling, and the rule's own caveat says so in the bluntest terms available: high volume means high participation, and direction requires context.

                    The cases that break a naive reading are ordinary in Indian markets. A session on which a company reports results will trade heavily in one direction. An index rebalance on a scheduled date will trade heavily in one direction in exactly the stocks that are being added or removed. A corporate action day will print an unusual bar. This app's sources carry none of those — announcements, bulk deals, block deals and the surveillance lists were all unreachable in the live probe — so it cannot distinguish an informed distribution from a mechanical rebalance and does not claim to.

                    The measurable consequence of that is a fire rate. Over the rolling walk of 4,407 symbol-sessions the rule fired on 1.41% of observations, which is a plausible rate for a rule that fires when something genuinely unusual happened and quiet otherwise. A rule that fired on 30% of sessions would be describing the average day, and a reader would learn to skip it.
                """.trimIndent(),
                doesNotEstablish = "A distribution-bar flag establishes that volume was at or " +
                    "above 2.5 times the twenty-session average on a lower-closing bar. It does " +
                    "not establish that informed sellers were present, and an index rebalance, " +
                    "an earnings release or a corporate action can produce an identical bar that " +
                    "this app cannot detect.",
                relatedFlag = "DISTRIBUTION_DAY",
            ),

            Lesson(
                id = "intermediate.delivery.volume.obv_confirmation",
                title = "Why volume confirms and never decides",
                body = """
                    Volume's value in this app is as a second opinion on a price reading, never as a reading on its own. Every volume-based rule here is conditional on something price has already done.

                    The clearest example is the 52-week-low rule: the close must sit at the lowest close of the last 52 sessions, and only then does the twenty-session slope of on-balance volume enter the decision. Volume is the qualifier, not the trigger. The rule's own caveat adds the limit that makes it usable: a falling on-balance-volume slope during a range can reflect profit-taking rather than distribution, so the slope shows direction and not intent.

                    There is also a deliberately odd-looking detail in the severity logic. The condition asks whether the close sits at the 52-session low, and the latest bar is itself inside that window, so the two are equal by construction and any tolerance band measured against that window would be dead code. The higher severity therefore measures the close against the low of the 52 sessions before the latest bar, with a two percent band — which gives the band a real job: separating a marginal retest of the prior low from a decisive new low.

                    Measured over the same rolling walk, this rule fired on 6.85% of observations. Conditional on price already being at an extreme, that is a rate worth reading; unconditional, it would be noise.
                """.trimIndent(),
                doesNotEstablish = "An on-balance-volume slope and a delivery average " +
                    "establish the direction and intensity of participation as published. They " +
                    "do not establish the identity or intent of the participants, and no volume " +
                    "reading in this app is a stand-alone conclusion.",
                relatedFlag = "AT_52WEEK_LOW_WITH_DECLINING_OBV",
            ),
        ),
    ),

    // =========================================================================
    // 7. Sector lens
    // =========================================================================
    Module(
        id = "intermediate.sector.lens",
        level = Level.INTERMEDIATE,
        title = "Sector lens",
        summary = "Why one global ratio list misleads, how the per-sector override replaces rather " +
            "than appends, and what to read for IT, consumer goods and pharma instead.",
        lessons = listOf(

            Lesson(
                id = "intermediate.sector.lens.override",
                title = "An override replaces rows; it never appends to them",
                body = """
                    The design principle behind the sector overrides is one sentence: a single global list misleads on banks. So the mechanism is replacement rather than addition. A sector-specific slot goes to the top of the composed list, rows the sector removed disappear with their reason attached, and one promoted row moves to an absolute position in the final order.

                    The consequence is worth stating plainly, because it is counter-intuitive: a bank does not get debt to equity and net interest margin side by side. It gets net interest margin. Debt to equity, interest coverage, operating margin and the current ratio are removed from that card, each with its own sentence saying why — deposits and borrowings are the business rather than leverage; a bank's published interest row is interest income rather than interest expense; a bank page publishes a financing margin rather than an operating profit line; and the current ratio does not apply.

                    Two kinds of removal are in play and the wording differs deliberately. "Not meaningful here" removes a row on the sector's grounds. "Not sourced" leaves a row in place and returns nothing for it, because the measure is meaningful and the software cannot see it. Confusing the two is the failure this whole subsystem exists to prevent.
                """.trimIndent(),
                doesNotEstablish = "The override mechanism establishes how the fundamentals list " +
                    "is composed for a given sector. It does not establish that the sector " +
                    "assignment is correct — resolution is keyword-based and an unresolved " +
                    "company gets the generic list — nor that the surviving rows are the right " +
                    "ones for that business.",
                relatedRatio = "DEBT_TO_EQUITY",
            ),

            Lesson(
                id = "intermediate.sector.lens.it",
                title = "Information technology: booked work instead of stock",
                body = """
                    An IT company's productive asset is people, not inventory, so its leading slots are about work already secured. Total contract value is the total value of contracts signed, which is booked but not yet recognised as revenue and is therefore a leading rather than a trailing figure. Attrition is the share of the workforce leaving in a year, and it measures delivery capacity: a company that cannot staff its contracts will not convert the bookings. Constant-currency growth strips currency movement out of reported growth, and revenue visibility is the share of the next year already booked.

                    The override also removes gross margin, with the reason stated: a services profit and loss account has no cost of goods sold, so a gross margin would be revenue minus an unallocated employee cost — not a published figure, and therefore not one the app will manufacture.

                    The captured TCS page shows what remains. Sales of 267,021 crore, operating profit of 72,398 crore, profit after tax of 49,454 crore, borrowings of just 11,283 crore, free cash flow of 48,013 crore, cash from operations of 52,094 crore, coverage of 63.5354 times, promoters at 71.77% in June 2026, debtor days rising from 84 to 93, and negative other income in the year — which is why the page produces no red flags at all. Every one of those four sector-specific ratios, though, comes back as not sourced.
                """.trimIndent(),
                doesNotEstablish = "Explaining the IT override establishes which measures suit a " +
                    "services business. It establishes no value for total contract value, " +
                    "attrition, constant-currency growth or revenue visibility, which this app " +
                    "cannot source, and nothing here speaks to a specific company's bookings.",
                relatedRatio = "TOTAL_CONTRACT_VALUE",
            ),

            Lesson(
                id = "intermediate.sector.lens.fmcg",
                title = "FMCG: is it volume, and how much cash is stuck in the channel",
                body = """
                    Two slots lead a consumer-goods card, and both exist because of how that business earns. Volume versus price growth separates growth that came from more units from growth that came from higher prices, because the second kind carries a very different margin consequence. Working capital days measures how much cash is tied up between buying stock and being paid for it.

                    For consumer goods, gross margin is promoted to the second position rather than dropped, and price to book is removed with a reason: the ratio is scoped to asset-heavy businesses, and a brand and distribution business is neither, so a high price to book there is a capitalisation artefact rather than a valuation signal.

                    The working-capital half of this card is where the honesty rule bites hardest. Both measures are norm-dependent — some industries genuinely need higher inventory than others — and this app has no industry norms to compare against. When a company's sector cannot be resolved, the checks are suppressed outright rather than compared against nothing. RELIANCE's captured page is exactly that case: its sector resolves as unresolved, the evidence row says so, and its eighty-eight inventory days are published as a figure with no judgement attached.
                """.trimIndent(),
                doesNotEstablish = "The consumer-goods override and its two leading slots " +
                    "establish which measures suit that business model. They do not establish " +
                    "that a company's growth is volume-led, that its channel terms are healthy, " +
                    "or that its inventory level is appropriate — and where the sector is " +
                    "unresolved the app makes no working-capital judgement at all.",
                relatedRatio = "VOLUME_VS_PRICE_GROWTH",
            ),

            Lesson(
                id = "intermediate.sector.lens.pharma",
                title = "Pharma: pipeline spend, regulatory standing and concentration",
                body = """
                    A pharmaceutical company's value sits in things that do not yet earn anything, so research and development as a percentage of sales leads the card — it is the pipeline investment that decides what the company looks like in a decade. USFDA compliance sits next to it because, for a company selling into the United States, an observation letter is an existential event rather than a quarterly irritation. Product concentration closes the leading block: how much of the business rides on a small number of molecules is the question that decides how much a single trial result or patent expiry matters.

                    Price to book is removed for pharma with its reason printed: book value for a pharmaceutical company is mostly intangibles, so the ratio misreads what it is meant to measure.

                    Worth pairing with the delivery module's lesson on norms: these three slots describe what the industry is judged on, and all three are among the measures this app cannot source. So for a pharmaceutical reader the practical position is the same as for a bank reader — the app tells you which three numbers to go and read, in a company's annual report, and then refuses to guess at them.
                """.trimIndent(),
                doesNotEstablish = "The pharma override establishes which measures suit the " +
                    "industry and why price to book is removed. It establishes no value for " +
                    "research spend, regulatory standing or product concentration, and it is not " +
                    "a view on any company's pipeline, filings or patent position.",
                relatedRatio = "RD_TO_SALES",
            ),
        ),
    ),

    // =========================================================================
    // 8. Thesis journaling
    // =========================================================================
    Module(
        id = "intermediate.thesis",
        level = Level.INTERMEDIATE,
        title = "Thesis journaling",
        summary = "Four fields that turn a held position from an open-ended hope into something " +
            "with a written test and a date attached.",
        lessons = listOf(

            Lesson(
                id = "intermediate.thesis.write",
                title = "My thesis, what I expect, what would prove me wrong, when I review",
                body = """
                    A thesis is the sentence that says why you own something. It is worth writing down before you look at the app rather than after, because a screen that agrees with what you already believe reads very differently from the same screen read cold.

                    Four fields, and each one earns its place by doing a different job. The thesis is the reason: what about this business or this price makes it interesting. What I expect is the specific, checkable thing you think happens next and by when. What would prove me wrong is the condition that retracts the thesis — the single most important field, because a thesis with no falsifier is a belief rather than a claim. And the review date is when the thesis gets tested whether or not anything has happened.

                    The value is not in having written it. It is in having written it before, because the falsification condition is the only part that cannot be reconstructed honestly afterwards, and it is the part that decides whether the rest of the record means anything.
                """.trimIndent(),
                doesNotEstablish = "Writing a thesis and a falsification condition establishes " +
                    "that a reader has stated a position and a test for it. It does not " +
                    "establish that the position is correct, and this app holds no journal, no " +
                    "record of what any reader holds, and no personal track record to compare " +
                    "against.",
            ),

            Lesson(
                id = "intermediate.thesis.falsify",
                title = "How to write a falsifier that can actually fail",
                body = """
                    Most things written as falsifiers cannot fail, which is the whole problem. If the condition is "the business continues to grow", nothing can refute it, because growth has an unbounded number of speeds and directions. If it is "the stock performs well", it has no threshold and no window.

                    A usable falsifier has three properties. It is a condition someone else could check without asking you. It has a threshold, so that "worse" means a number rather than an impression. And it has a date, so it expires rather than being re-stated after every disappointment.

                    It is also worth writing the falsifier that would retract the thesis you most want to keep, because that is where the writing does its work. And it is worth writing it in terms of the business rather than the screen: the app's own measurements show that next-session direction on its committed fixture ran at 48.93%, so a condition written purely in terms of what price does next is being written against a coin flip.
                """.trimIndent(),
                doesNotEstablish = "A well-formed falsification condition establishes that a " +
                    "thesis can be tested. It does not establish that the test will be applied " +
                    "honestly, that the thesis is right, or that any outcome follows from the " +
                    "thesis being confirmed.",
            ),

            Lesson(
                id = "intermediate.thesis.review_date",
                title = "Why the review date is the field that matters most",
                body = """
                    A thesis without a review date is a standing instruction to your own attention, and standing instructions lose to current ones. A date forces the question in a fixed window rather than whenever discomfort gets loud.

                    There is a second reason, specific to this app. Everything it knows is end-of-day and bounded by what the exchange publishes, so the honest cadence is the trading session. Its stored prices are unadjusted, so a thesis written on a chart that spans a split needs to be re-read afterwards. Its history is a window — 372 real sessions in the committed fixture, and the exchange's own daily file keeps roughly eighteen months — so a thesis resting on anything older than that rests on data this app never had.

                    None of that means the app should tell anyone when to review. It means a review date is the point at which a reader re-reads their own thesis, re-checks the figures against a primary source, and decides whether the falsification condition has been met. The app's role ends at supplying the current figures honestly; the decision is not the app's to make.
                """.trimIndent(),
                doesNotEstablish = "A review date establishes only when a reader intends to " +
                    "re-read a thesis. This app does not schedule, remind, monitor or act on " +
                    "anything, holds no record of any reader's positions, and its published " +
                    "figures are the ones available at the time they were fetched.",
            ),
        ),
    ),

    // =========================================================================
    // 9. Methodology
    // =========================================================================
    Module(
        id = "intermediate.methodology",
        level = Level.INTERMEDIATE,
        title = "Methodology, and how to audit the app itself",
        summary = "Where every number comes from, which URLs the fetcher is allowed to build, " +
            "how staleness is reported, and how to check any of this without trusting it.",
        lessons = listOf(
            Lesson(
                id = "intermediate.methodology.sources",
                title = "Where every number in this app comes from",
                body = """
                    Four sources, and they are the only ones. Prices come from the NSE end-of-day bhavcopy, one file per session containing every instrument, filtered to the ordinary equity and SME series codes: 3,712 instruments in, 3,051 kept. Symbols and ISINs come from the exchange's own symbol master, 2,593 rows, bundled into the app so search works offline and instantly. Fundamentals come from one free screener.in consolidated page per company, roughly 218 to 242 KB, captured for RELIANCE, TCS, HDFCBANK, INFY and TITAN and committed as fixtures so every parser test runs offline against the exact bytes that came back. Longer history and an adjusted-price series come from a second provider, which is unreachable from the network this project was built on and does answer from other networks.

                    Two host facts are worth knowing because they explain design choices. The documented archive host fails from some networks with a protocol error and a timeout, while the mirror serves a byte-identical file — so the fetcher tries both, and treats a session as not published only when every candidate host answers not found. And that same archive host keeps roughly eighteen months of daily files, which is why the history backfill exists at all.

                    One honest correction belongs in this lesson rather than buried. The project's original plan assumed a screener.in page carried 163 to 169 informational fields; the pages measured today carry nine in the company-information block and nine in the ratios block. The parser reads what is published and ignores additions gracefully.
                """.trimIndent(),
                doesNotEstablish = "Naming the sources establishes where each figure comes from " +
                    "and when it was last verified. It does not establish that any source is " +
                    "accurate, that its figures are audited, or that a figure fetched today has " +
                    "not already changed — every figure carries the date it was captured.",
            ),

            Lesson(
                id = "intermediate.methodology.robots",
                title = "The URLs this app is allowed to build, and why search is local",
                body = """
                    A scraper that respects the site it reads is a constraint the codebase enforces rather than documents, and the rules here are explicit. The disallowed paths on the fundamentals source include the query-string shapes for search, pagination, sorting and limits, the user area, and the quarterly-source documents. The fetcher can only build the one permitted URL shape, and a test asserts that it cannot build anything else.

                    Two consequences follow that a reader can check. The page's raw-PDF row is deliberately not fetched, because it links into one of the disallowed paths — following it would be a violation even though the fetch itself looks harmless. And search is answered locally from the bundled symbol master rather than by querying the site, which is both compliant and faster than a network round trip on a phone.

                    The general lesson is worth more than the specific list: an offline-first app that depends on a page it cannot be sure of tomorrow needs every captured response committed to the repository. That is why five full pages of roughly 240 KB each live in the test resources rather than being fetched at test time.
                """.trimIndent(),
                doesNotEstablish = "The disallowed paths and the one-permitted-shape assertion " +
                    "establish that this app restricts its own fetching to a single URL pattern " +
                    "for that source. They do not establish that any source permits the use made " +
                    "of it, nor that the restriction list is complete or current.",
            ),

            Lesson(
                id = "intermediate.methodology.staleness",
                title = "Staleness, and the difference between quiet and unchecked",
                body = """
                    Three separate freshness ideas run through the app, and conflating them is how a tool starts lying quietly.

                    A ratio is stale when it exists but is older than the window its own kind requires — annual figures and quarterly figures have different ages before they become misleading, and the state is carried explicitly so a stale number renders with a note instead of as a current one. A hit rate's freshness is different again: its last-settled date is the latest projection date in the record, which can never be later than the truth and therefore cannot overstate how fresh it is.

                    The third is coverage. A check that could not run must say so. The app distinguishes a report saying no flags from a report saying no flags in the data that was checked, because those are different facts and only one of them is reassuring. Every abstaining rule records its reason as user-facing prose — no delivery file, an unresolved sector, no receivables line, a turnover floor not met — and the reason is displayed rather than logged.

                    A fourth, quieter one: the delivery source is optional everywhere, so a candle can carry no delivery figure without anything being wrong, and the one rule that depends on it reports that it could not evaluate rather than treating a null as a low reading.
                """.trimIndent(),
                doesNotEstablish = "Carrying staleness and coverage as explicit states " +
                    "establishes how old a figure is and which checks actually ran. It does not " +
                    "establish that a figure which passed its freshness window is correct, and an " +
                    "abstention is never evidence that the abstained condition is absent.",
            ),

            Lesson(
                id = "intermediate.methodology.audit",
                title = "How to audit this app without trusting it",
                body = """
                    The most useful thing about an education tool is that its claims can be checked. Four routes, in increasing order of effort.

                    First, read the labels. Every red flag states its threshold; every ratio card states why it sits where it does; every blank states which of the five availability states produced it. A claim with no threshold and no reason attached is the thing to be suspicious of.

                    Second, check a figure against its primary source yourself. The fundamentals figures come from one free aggregator's page; the bank ratios this app cannot read come from a bank's annual report, and the system-wide picture from the central bank's financial stability report. Where the two sources disagree, the app's own divergence machinery exists to show the disagreement rather than average it away.

                    Third, read the committed fixtures. Real pages and real price files sit in the repository's test resources, so the parser tests run against the exact bytes that came back rather than against something reconstructed. Fourth, run the suites. This project has roughly 1,337 pure-JVM tests across the domain and data modules, every one of which runs in continuous integration on every change with no device and no network. One honest limit belongs here as well: the Android user-interface and database modules were never compiled on the machine this project was written on, because no Android SDK was present, so the first real build of those layers is still an open item rather than a verified fact.
                """.trimIndent(),
                doesNotEstablish = "Naming the fixtures, the suites and the primary sources " +
                    "establishes how a reader could check this app's claims. It does not " +
                    "establish that the app is correct, and the Android layers remain unverified " +
                    "by any local build.",
            ),
        ),
    ),
).also { Curriculum.register(it) }
