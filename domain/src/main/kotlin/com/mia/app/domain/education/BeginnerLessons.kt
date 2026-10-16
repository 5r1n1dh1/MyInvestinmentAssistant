package com.mia.app.domain.education

/**
 * The BEGINNER curriculum: eight modules, 31 lessons.
 *
 * ### Why every number in this file is a number the repository measured
 *
 * A lesson that teaches with an invented figure teaches a wrong figure, and an education screen
 * is the one place where nobody is checking. Every quantity quoted below is one of:
 *
 * * a value from `docs/VERIFICATION.md` or `docs/DATA-NOTES.md` — the write-ups of live and
 *   fixture measurements;
 * * a value asserted by an existing test in `:domain` or `:data` (the golden screener.in pages,
 *   the 372-session end-of-day fixture, the golden bhavcopy row);
 * * or a constant from the engine itself (`MatchScorePolicy`, `TechnicalPolicy`,
 *   `FundamentalsPolicy`, `RatioPriority`, `PatternCatalog`).
 *
 * Where a figure would have been useful and could not be verified, the lesson says the figure is
 * absent instead of approximating one. That is why the tax module names no percentages at all:
 * the app has no measured source for them, and a tax rate written into an education screen is a
 * liability with an expiry date.
 *
 * ### The compliance contract these lessons are held to
 *
 * [Lesson.doesNotEstablish] is mandatory on every lesson and is not decoration. It is the
 * sentence a reader is entitled to after the lesson: the thing the lesson's subject is *not*
 * evidence of. Every body and every `doesNotEstablish` string is walked through
 * [com.mia.app.domain.compliance.AdviceLanguage] and
 * [com.mia.app.domain.compliance.HonestyContract] by `CurriculumComplianceTest`, so a lesson
 * that grows advice-shaped language fails the build.
 *
 * ### Registration
 *
 * Kotlin has no top-level `init` block, so the registration into [Curriculum] rides on this
 * property's own initialiser through `also` — the single documented call site named in the W6
 * contract. Touching [BEGINNER_MODULES] is what performs the registration.
 */
val BEGINNER_MODULES: List<Module> = listOf(

    // =========================================================================
    // 1. First look: one stock, end to end
    // =========================================================================
    Module(
        id = "beginner.first.look",
        level = Level.BEGINNER,
        title = "First look: one stock, end to end",
        summary = "A guided walk through what each tab of this app is measuring on a real stock, " +
            "and a plain statement of what none of the four tabs can tell you.",
        lessons = listOf(

            Lesson(
                id = "beginner.first.look.price",
                title = "The price and technicals tab, starting from one bar",
                body = """
                    Every screen in this app starts with a price bar. One bar is one trading session, and it carries four numbers — the open, the high, the low and the close — together with the volume that traded. RELIANCE's bar for 2026-10-01 closed at 1167.70. That exact figure is what a parser test pins against the real exchange file, so the number on screen is the published one rather than something the app worked out for you.

                    The whole market arrives in a single fetch: the end-of-day bhavcopy file for one session holds 3,712 instruments, of which this app keeps the 3,051 carrying the ordinary equity and SME series codes. That is why one daily download gives every symbol the same depth instead of favouring whichever stock you happen to be looking at.

                    The technicals tab is where you meet the derived readings. They are arithmetic on those bars and nothing else: simple moving averages at 20, 50, 100 and 200 sessions; exponential averages over the same spans; RSI on a 14-session window with 70 and 30 as its band edges; MACD on 12, 26 and 9; ATR on 14; Bollinger bands over 20 sessions at twice the standard deviation; ADX on 14; and on-balance volume. Underneath all of it sits a hard gate — no pattern is scored on fewer than 60 daily bars or 40 weekly bars — so "not enough history" is the gate talking, not a failure.
                """.trimIndent(),
                doesNotEstablish = "A bar, an average and an oscillator establish only what already traded. None of " +
                    "them is evidence about a company's business, its accounts, its management " +
                    "or what a price does next, and the 372 sessions this app can see are a " +
                    "window rather than a company's history.",
            ),

            Lesson(
                id = "beginner.first.look.ratios",
                title = "The fundamentals tab and its ratio cards",
                body = """
                    The fundamentals tab is a ranked list of ratios, not a score. The order is fixed in advance and each card carries the reason it sits where it does: return on capital employed first, operating margin second, then debt and interest coverage, free cash flow against profit after tax, revenue growth, return on equity, and the rest. The reason is printed on the card precisely so the ordering can be argued with rather than taken on faith.

                    Here is what one captured page looks like. RELIANCE's, as published: market capitalisation 1,580,194 crore, P/E 21.2, return on capital employed 10.3%, return on equity 8.91%, book value 668 per share, debtor days 20 in the March 2026 year against 16 a year earlier, and inventory days 88.

                    Two habits are worth forming immediately. First, look for the date on every figure — these numbers describe a financial year that ended months ago, not today. Second, notice which cards are blank and read the sentence attached to the blank. A blank on this tab is never a zero; it is one of a small number of named states, and the last module in this level exists because the difference between those states matters more than it sounds.
                """.trimIndent(),
                doesNotEstablish = "A ratio card is arithmetic over a past financial year, and past " +
                    "accounting is a summary written by the company's own accountants. A ranked " +
                    "list of ratios does not establish that a business is sound, that its price is " +
                    "right, or that the figures survive an audit, and this app has never read an " +
                    "audited filing.",
                relatedRatio = "ROCE",
            ),

            Lesson(
                id = "beginner.first.look.flags",
                title = "The red-flag rows and what one is made of",
                body = """
                    A red flag in this app is three things stacked together: a named condition, the threshold at which that condition counts, and the evidence behind it. The identifiers say what was observed rather than what it implies — there is a row for operating cash flow being negative while profit after tax is positive, not a row that calls a company dishonest. That naming is deliberate, because a condition is something a reader can go and check.

                    On the four pages this app keeps as golden fixtures, the outcome is worth reading closely because it is unremarkable. RELIANCE raises exactly one row: other income of 28,846 crore against profit after tax of 95,754 crore, which is 30.1%. TITAN raises exactly one row: debt to equity of 1.95 times. TCS and HDFCBANK raise none at all.

                    The most important feature of the panel is what happens when a check cannot run. Instead of quietly passing, the app records that the check was skipped and prints the reason, so a header saying "no flags" stays distinguishable from a header saying "no flags in the data that was checked". Half the rules abstaining is a different statement from half the rules passing.
                """.trimIndent(),
                doesNotEstablish = "A raised flag establishes that a measured condition was met on " +
                    "the data available. It does not establish wrongdoing, nor that a price move " +
                    "follows, nor that the condition still holds today: a flag computed from a " +
                    "fiscal year's accounts is a reading of the past.",
                relatedFlag = "OTHER_INCOME_AS_PCT_OF_PAT",
            ),

            Lesson(
                id = "beginner.first.look.patterns",
                title = "The patterns tab, a score, and what that score is a score of",
                body = """
                    The patterns tab looks for shapes in price. A pattern here is a geometric description — three peaks with the middle one highest, or two roughly parallel lines drifting upward after a sharp move — and the app scores how closely the recent bars resemble the textbook drawing of that shape. The score runs from 0 to 100 and is built from four factors in fixed proportion: geometry at 40, structure at 25, confirmation at 20 and context at 15.

                    Three thresholds matter. Below 50 a candidate is suppressed entirely; between 50 and 69 it is shown without any projection; at 70 or above, and only if enough bars exist and the trend context agrees, the app also draws the measured-move level — the height of the formation measured from its breakout point.

                    The catalogue is much bigger than the detector set, and that gap is deliberate. There are 42 named rows: 9 that this app evaluates on a real stock, and 33 it names, teaches and marks as not looked for. Every candlestick entry is in that second group, so a formation such as a doji or an engulfing pattern is here to be explained rather than detected.
                """.trimIndent(),
                doesNotEstablish = "A match score establishes only that the recent bars resemble a drawing. It does not " +
                    "establish whether a price moves, it is not a percentage of any outcome, and " +
                    "the measured-move level drawn beside it is pattern geometry rather than a " +
                    "forecast — a line drawn from the formation's own height.",
                relatedPattern = "Head & Shoulders",
            ),

            Lesson(
                id = "beginner.first.look.limits",
                title = "What none of the four tabs can tell you",
                body = """
                    This is the lesson worth reading twice, because it is the difference between an education tool and an adviser.

                    None of these tabs forecasts. There is no column anywhere in this app that says where a price is going, and the measured-move level that appears beside a high-scoring pattern is the height of a formation drawn from its breakout point — geometry, not an expectation.

                    None of these tabs carries company news. In a live probe run from outside this network, announcements, bulk deals, block deals and the additional-surveillance and GSM lists all returned errors from the archive hosts the app can reach. That is a measured boundary rather than a design choice: a pattern can look perfect on the day a company's results land, and this app would not know why.

                    None of these tabs carries a corporate action register either. The app infers a split or bonus from a price discontinuity and tells you where to confirm it, because announcements are out of reach. And none of the stored prices are adjusted for such events, which is the subject of its own case-study module at the intermediate level.
                """.trimIndent(),
                doesNotEstablish = "Price bars, ratio cards, red-flag rows and pattern scores are " +
                    "derived from published price and account data. Together they do not establish " +
                    "a view on any company, they carry no news, no filing, no corporate action " +
                    "register and no ownership register, and they are not a substitute for a " +
                    "SEBI-registered adviser.",
            ),
        ),
    ),

    // =========================================================================
    // 2. Jargon decoder
    // =========================================================================
    Module(
        id = "beginner.jargon",
        level = Level.BEGINNER,
        title = "Jargon decoder",
        summary = "Fifteen terms you meet in the first ten minutes of using this app: what each " +
            "one is, why it is on the screen at all, and the limit of what it shows.",
        lessons = listOf(

            Lesson(
                id = "beginner.jargon.pe",
                title = "EPS and the price-to-earnings ratio",
                body = """
                    Earnings per share is the profit attributable to ordinary shareholders divided by the number of shares, expressed per share. On RELIANCE's captured page the March 2026 figure is 59.69. It is a trailing per-share number, which means it covers the last reported year plus any later quarters the company has published.

                    The price-to-earnings ratio is simply the share price divided by that earnings figure. RELIANCE's page publishes 21.2, TCS 14.0, HDFCBANK 14.1, INFY 13.5 and TITAN 68.7. A reader who has heard "cheap" and "expensive" attached to this number has learned a slogan rather than a method, because the same fourteen means something quite different for a business with steady earnings and for one whose earnings are falling away.

                    This app ranks a related card — a stock's ratio to its sector's median — at position 8 of 12, behind the measures that describe the business and ahead of price to book. The ordering is itself the opinion: what a business earns on the capital it needs is a more robust fact about quality than what the market currently pays for each rupee of reported earnings.
                """.trimIndent(),
                doesNotEstablish = "A price-to-earnings ratio divides a market price by a trailing " +
                    "reported profit. It does not establish that a share is cheap or dear, that " +
                    "the reported profit repeats, or that the sector median it is measured against " +
                    "is itself a fair yardstick — a low multiple frequently accompanies a " +
                    "business in trouble rather than a bargain.",
                relatedRatio = "PE_VS_SECTOR_MEDIAN",
            ),

            Lesson(
                id = "beginner.jargon.returns",
                title = "Return on capital employed and return on equity",
                body = """
                    Return on capital employed measures profit from operations against the capital the business needs in order to run. Return on equity measures profit against shareholders' funds. The two differ by exactly one thing: how much of the business is financed by other people's money.

                    On the five pages this app keeps, the gap shows up in both directions. TCS reports 63.0% against 51.8% and INFY 40.0% against 31.9%, so return on equity sits below return on capital employed. HDFCBANK reports 7.02% against 13.6% and TITAN 20.5% against 37.7%, which is the reverse.

                    That is why return on capital employed is ranked first and return on equity sixth, with the card's own note calling the second one duplicated leverage. Return on equity counts the same return twice over once debt is involved, so a business can lift its return on equity by borrowing more without the underlying business changing at all. Return on capital employed does not move when only the financing changes.
                """.trimIndent(),
                doesNotEstablish = "Both returns are computed from a past financial year using the " +
                    "company's own accounting. Neither establishes that the return persists, that " +
                    "the capital base is correctly measured, or that a higher return makes a share " +
                    "attractive at any particular price.",
                relatedRatio = "ROCE",
            ),

            Lesson(
                id = "beginner.jargon.bank",
                title = "NIM, GNPA and NNPA, CASA and PCR",
                body = """
                    These four belong to banks and to nobody else, which is why they replace the generic ratios rather than sitting beside them.

                    Net interest margin is the spread between what a bank earns on the loans it makes and what it pays for the money it funds them with; it is a bank's operating margin. Gross and net non-performing assets are the share of the loan book not being repaid as agreed — gross before provisions, net after them. CASA is current account plus savings as a share of deposits, the cheapest funding a bank has. Provision coverage is provisions held against gross bad loans, so it measures the cushion if those loans go wrong.

                    One honest warning belongs on this lesson. This app knows these four slots and cannot fill them. A live probe of the HDFCBANK page found the labels for gross and net non-performing assets and CASA present, but the value cells empty and followed by a sign-in button: the numbers sit behind a login the app has no credentials for. So they appear as not sourced rather than blank, and the intermediate module on reading a bank explains where to find them yourself.
                """.trimIndent(),
                doesNotEstablish = "These four ratios describe a lender's published accounts. " +
                    "Because this app cannot source them it reports no value for them at all, and " +
                    "in no case do they establish the credit quality of a specific loan book, the " +
                    "adequacy of a bank's provisioning policy, or the safety of any deposit.",
                relatedRatio = "NET_INTEREST_MARGIN",
            ),

            Lesson(
                id = "beginner.jargon.size",
                title = "Market capitalisation, cap bands, FII and DII",
                body = """
                    Market capitalisation is the share price multiplied by the number of shares outstanding. Across the five captured pages it runs from 400,898 crore for TITAN to 1,580,194 crore for RELIANCE. It measures the size of the company in the market's own currency — not its sales, not its assets, and not the amount of cash anyone would receive if it were sold tomorrow.

                    Large, mid and small cap are bands of that number, used as a proxy for how a stock behaves rather than how good it is. The reason this app keeps the small-company series separately is visible in its own measurements: in one end-of-day file 2,662 instruments carried the ordinary equity code and 389 carried the SME code, and the SME group exists only in the daily price file, never in the symbol master the app bundles for offline search.

                    FII and DII are holding categories on a company's shareholding page: foreign institutional investors and domestic institutional investors. RELIANCE's published June 2026 column reads 18.67% and 20.46%, against promoters at 50.48%. Those are reported holdings at a quarter end, and they say who stands on the register rather than what any of them intend.
                """.trimIndent(),
                doesNotEstablish = "Market capitalisation and cap bands describe size and market " +
                    "behaviour, not business quality. FII and DII holdings are a quarter-end " +
                    "snapshot of a published register, and they do not establish the intention, " +
                    "the timing or the direction of any holder's future trades.",
            ),

            Lesson(
                id = "beginner.jargon.tape",
                title = "Delivery percentage, the 52-week range, circuit bands and SME",
                body = """
                    Delivery percentage is an Indian-market measure: the share of a day's volume that was actually taken up into delivery, rather than traded back and forth intraday. The app reads it from a separate exchange file which, on one measured day, carried 3,534 rows of which 3,262 carried a numeric value. RELIANCE read 61.24, HDFCBANK 63.31, TCS 54.90, INFY 51.96 and 20MICRONS 46.10. Delivery is optional throughout — if that file is absent the price layer carries on unaffected, and the one check that depends on it abstains with a stated reason.

                    The 52-week high and low are the highest and lowest closes of the last 52 trading sessions, and one of this app's six technical checks fires when the close sits at that low while the 20-session slope of on-balance volume is declining.

                    A circuit band is the exchange-imposed limit on how far a security's price can move in one session, set by the exchanges rather than by this app, and its width is something to read off the exchange's own circular rather than infer from a chart. SME means small and medium enterprise: in the exchange's series coding it is a distinct code, and this app keeps those 389 instruments alongside the 2,662 ordinary equity ones.
                """.trimIndent(),
                doesNotEstablish = "Delivery percentage, the 52-session range, circuit bands and " +
                    "series codes are mechanical descriptions of traded volume and price limits. " +
                    "None of them establishes who was trading, why a band was hit, or what " +
                    "follows a band, and a delivery figure is explicitly not evidence of informed " +
                    "activity.",
            ),
        ),
    ),

    // =========================================================================
    // 3. Two numbers, never one
    // =========================================================================
    Module(
        id = "beginner.two.numbers",
        level = Level.BEGINNER,
        title = "Two numbers, never one",
        summary = "The module this app is built around: a match score and a hit rate answer two " +
            "different questions, and on measured data both of them sit near a coin flip.",
        lessons = listOf(

            Lesson(
                id = "beginner.two.numbers.control",
                title = "The control test: every simple signal landed near one half",
                body = """
                    Before shipping any signal, this project measured whether simple ones work at all. The test ran over 20,780 next-day returns across 20 large caps and asked four questions: how often is the next session higher, and how often does each of three familiar readings agree with the next session's direction?

                    The answers were 50.10% for always higher, 49.90% for always lower, 50.24% for RSI on 14 sessions, 50.17% for 200-session momentum and 49.81% for a z-score against the 50-session average. The median next-day move was 0.73% and the mean 0.99%. A fourth control on the app's own committed price fixture, 5,565 next-session pairs, reproduced the result independently: 2,723 were higher, which is 48.93%.

                    Read those numbers carefully, because they are the whole design. They do not say indicators are useless. They say that a reading which classifies about half of next sessions correctly is not an answer to the question people ask of it. No score built on top of that can honestly be called certain, which is precisely why this app never uses words like that.
                """.trimIndent(),
                doesNotEstablish = "A directional accuracy near one half, measured over 20,780 " +
                    "next-session returns and reproduced at 48.93% on the app's own fixture, " +
                    "establishes that these particular readings are weak discriminators on this " +
                    "data. It does not establish that every technique is weak, and it licenses no " +
                    "statement about what a particular stock does next.",
            ),

            Lesson(
                id = "beginner.two.numbers.match_vs_hit",
                title = "A match score and a hit rate answer different questions",
                body = """
                    The match score answers one question: how closely do these recent bars resemble the textbook drawing of this pattern? It runs from 0 to 100 out of four weighted factors — geometry 40, structure 25, confirmation 20, context 15 — and it says nothing whatsoever about outcomes.

                    The hit rate answers a different question: across this app's tracked history, how often was this formation's measured-move level actually reached? A hit is a chart event — a level touched within five bars of the projection — and nothing more.

                    The score is not a decorative number. A gate test over 20 real stocks produced 67 scored candidates with a minimum of 17, a maximum of 70, a mean of 45.9 and a standard deviation of 12.0 across 37 distinct values; one candidate reached 70 or above, 26 fell between 50 and 69, and 41 were suppressed. That spread is why the thresholds do any work at all. Rendering the two numbers as one figure would be the single most misleading thing this app could do, so it never does.
                """.trimIndent(),
                doesNotEstablish = "A match score establishes geometric resemblance and a hit rate establishes how " +
                    "often a measured-move level was reached. Merged into one figure they would " +
                    "measure nothing at all, and neither number is a statement about profit, " +
                    "cost or outcome for a reader who traded it.",
            ),

            Lesson(
                id = "beginner.two.numbers.n30",
                title = "Why every hit rate carries its sample size",
                body = """
                    A percentage without a sample size is decoration, so this app withholds hit rates below 30 settled cases and prints the count beside the ones it does show.

                    The threshold was not guessed. On the app's measured walk over 15 large caps and 372 sessions, grouping by stock and by pattern at once produced 45 cells holding 20 or 21 cases each — every single one below the gate and every one withheld. Grouping by stock gave 62 cases per cell and grouping by pattern gave 310, and both clear it. The strictest grouping loses the entire table, and the app says so rather than lowering the bar to fit.

                    What those figures are: 153 hits out of 930 settled projections, which is 16.45% (n=930), split almost evenly by direction — 76 of 464 upward and 77 of 466 downward. That looks like a failure until you notice what a hit is. A hit is a fixed geometric distance being reached, so the further the level sits from the price the less often it is reached: bucketed by distance, the same n=930 samples give 33.3% at 1%, 26.5% at 2%, 17.9% at 3%, 13.9% at 4%, 10.0% at 5%, 7.2% at 6% and 4.9% at 7%. The measured moves averaged four to five percent of price while the median five-session favourable excursion was about 1.8%.
                """.trimIndent(),
                doesNotEstablish = "A hit rate over tracked history with its sample size printed " +
                    "beside it — 16.45% of 930 settled cases, n=930 — establishes how often a " +
                    "measured-move level was reached on that sample. It is not a win rate, it " +
                    "carries no cost, no position size and no profit figure, and a level reached " +
                    "is a chart event rather than a gain.",
                relatedPattern = "Bull Flag",
            ),

            Lesson(
                id = "beginner.two.numbers.never_one_number",
                title = "Why the app refuses to combine them into one",
                body = """
                    Because the two numbers would end up answering each other's question. A high match score means the drawing looks right. A high hit rate means a level has been reached often in the past. Multiplying them, averaging them, or presenting them as a single ranking would assert an edge that the measurements above do not support.

                    There is a second reason and it is arithmetic. A round trip in the Indian cash market is not free. Securities transaction tax is levied on the trade itself, GST applies to the brokerage charge, brokerage is charged per execution, and slippage is the gap between the price you saw and the price you got. The project's own written conclusion on the 50.5% accuracy case is blunt: at that level of directional accuracy the cost stack exceeds the edge entirely.

                    So the design consequence is not cosmetic. Two numbers, shown separately, each with its own meaning and each honest about its own weakness, is the most this data supports. It is also why every projection the app draws carries a footer saying the level is measured-move geometry rather than a forecast.
                """.trimIndent(),
                doesNotEstablish = "Keeping two numbers separate establishes that this app is not " +
                    "claiming an edge. It does not establish that trading is unprofitable for " +
                    "anyone, and the cost stack described here is a structure rather than a set " +
                    "of rates — the app publishes no transaction charges and computes no tax.",
            ),
        ),
    ),

    // =========================================================================
    // 4. Pattern school
    // =========================================================================
    Module(
        id = "beginner.pattern.school",
        level = Level.BEGINNER,
        title = "Pattern school",
        summary = "What a chart pattern is, how to draw one honestly, what the textbook " +
            "associates with each family, and what each one leaves undetermined.",
        lessons = listOf(

            Lesson(
                id = "beginner.pattern.school.vocabulary",
                title = "Forty-two names, nine of which this app looks for",
                body = """
                    The pattern catalogue has 42 rows. Nine of them are evaluated on a real stock: Rising Wedge, Head & Shoulders, Inverse Head & Shoulders, Double Top, Double Bottom, Ascending Triangle, Descending Triangle, Bull Flag and Bear Flag. The other 33 are named, described and taught, and marked as not looked for — including every one of the 18 candlestick rows and both volume rows.

                    That split is the honest shape of the feature. A list containing only what the software can score would read as "these are the patterns that exist". A list containing all 42, each one marked, reads as "here is the vocabulary, and here is exactly how much of it the app can evaluate".

                    The candlestick family is the clearest case. Detectors for those formations exist and do run, but evaluating them would mean claiming a measured direction and a measured move for a one-to-three-bar shape that has neither a pivot nor a height, so the catalogue no longer advertises them as detected. They are here to be explained.
                """.trimIndent(),
                doesNotEstablish = "A catalogue row marked as not looked for establishes only that " +
                    "this app does not evaluate that formation. It does not establish that the " +
                    "formation is absent from a chart, that it is meaningless, or that no other " +
                    "software detects it.",
                relatedPattern = "Ascending Triangle",
            ),

            Lesson(
                id = "beginner.pattern.school.drawing",
                title = "How to draw one, and how to draw one badly",
                body = """
                    A chart pattern is a set of pivots — the swing points where price stopped and turned — joined by lines. Drawing one honestly means three things: mark the pivots first, connect them with the fewest lines the shape needs, and then check that every line has more than one touch from price itself rather than from your own imagination.

                    The app's own drawings are deliberately textbook-exact rather than flattering. A double top whose two peaks sit at exactly the same price looks better on a chart than a real one, so the catalogue's shape carries a small difference between the peaks, matching the symmetry tolerance the detector itself enforces. An overlay that looked tidier than reality would teach the wrong tolerance.

                    The pivots come from a single shared zigzag pass over the bars, which is the reason detection stays fast enough to run on a phone. Every detector sees the same pivot list, so a pivot cannot exist for one pattern and not for another — which is what lets two formations be compared on equal terms.
                """.trimIndent(),
                doesNotEstablish = "A hand-drawn formation establishes only that a reader can see " +
                    "that shape in that window. Shape selection is a judgement, and the same bars " +
                    "support different drawings depending on the swing size chosen, which is why " +
                    "the app fixes its pivot rule rather than asking the reader to pick one.",
                relatedPattern = "Double Top",
            ),

            Lesson(
                id = "beginner.pattern.school.families",
                title = "What the textbook associates with each family",
                body = """
                    The catalogue groups formations into five families, and each family carries the traditional reading rather than a measured one.

                    Reversal formations — head and shoulders, double and triple tops and bottoms, rounding tops and bottoms, the V-reversal, the island reversal — are traditionally read as a change from one direction toward the other. Continuation formations — wedges, triangles, flags, pennants, channels — are traditionally read as the existing trend pausing and then resuming. Bilateral formations such as a rectangle are a period of balance with no direction named at all. Candlestick and volume rows describe very short formations of one to three bars.

                    A Head & Shoulders is three peaks with the middle one highest over a shared neckline, and its traditional reading concerns a move back down through that neckline. An Ascending Triangle has a flat ceiling and a rising floor; the textbook association is with continuation upward, and its failure is a close below the rising floor. Those are descriptions of convention, and the app presents them as convention.
                """.trimIndent(),
                doesNotEstablish = "The textbook association for a family establishes what a " +
                    "tradition says about a shape. It does not establish that the tradition holds " +
                    "on the stock in front of you, and this app has measured no forward return " +
                    "conditional on any of these formations being detected.",
                relatedPattern = "Head & Shoulders",
            ),

            Lesson(
                id = "beginner.pattern.school.invalidation",
                title = "What each pattern does not settle",
                body = """
                    Every catalogue row carries a sentence saying what it fails to establish, and reading those sentences is the most useful habit in this module.

                    A rising wedge can break either way — the narrowing is the pattern, and it does not fix a direction. A symmetrical triangle is nicknamed a coil for exactly this reason: its direction is genuinely undetermined until it breaks, which makes it the clearest case of a pattern that names no outcome at all. A rectangle's entire content is indecision. A triple top is harder to distinguish from ordinary chop, and its third peak is the confirmation rather than a forecast.

                    For the nine formations the app does evaluate, the same limit applies to the score beside them. The measured-move level is the height of the formation measured from its breakout point. It is geometry — a statement about the size of the drawing — and it is never rendered as a forecast.
                """.trimIndent(),
                doesNotEstablish = "The catalogue's per-row notes and this module together " +
                    "establish only that a formation leaves its direction undetermined in most " +
                    "cases. They do not establish a direction, a level at which to act, or an " +
                    "outcome conditional on the pattern breaking in a particular way.",
                relatedPattern = "Symmetrical Triangle",
            ),
        ),
    ),

    // =========================================================================
    // 5. Red-flag school
    // =========================================================================
    Module(
        id = "beginner.red.flag.school",
        level = Level.BEGINNER,
        title = "Red-flag school",
        summary = "What each family of red-flag check actually measures, with figures taken from " +
            "the project's own captured pages, and what a raised flag does not prove.",
        lessons = listOf(

            Lesson(
                id = "beginner.red.flag.school.anatomy",
                title = "A flag is a condition, a threshold and some evidence",
                body = """
                    Read a red-flag row from the top down: what condition was observed, what the threshold was, and what numbers sit behind the observation. All three are on the row. A row that can only be read as "something is wrong" is not usable, because you cannot check it.

                    The identifiers follow that rule exactly. There is a row for operating cash flow being negative in at least two consecutive years while profit after tax is positive. There is a row for receivables growing faster than revenue, evaluated in days of sales outstanding because the source publishes no receivables line. There is a row for other income above thirty percent of profit after tax, one for debt to equity spiking, one for interest coverage below two, one for a low multiple alongside falling returns, and one for trailing earnings per share falling while the multiple on it rises.

                    Two of the ten are declared coverage gaps. Goodwill above twenty percent of total assets, and promoter shares pledged, are implemented and unit-tested against synthetic input, and then abstain with a stated reason on every real symbol because no source of ours publishes either line. A permanent gap that reports itself is more useful than a blank that reads as a value.
                """.trimIndent(),
                doesNotEstablish = "Naming a flag's condition and threshold establishes what was " +
                    "measured and against which bar. It does not establish that the condition is " +
                    "unusual for the business, that it is recent, or that anyone is acting on it — " +
                    "and two of the ten fundamentals checks can never be evaluated at all.",
                relatedFlag = "CFO_NEGATIVE_WHILE_PAT_POSITIVE",
            ),

            Lesson(
                id = "beginner.red.flag.school.technical",
                title = "The six technical checks and how often each one fires",
                body = """
                    Six checks run on price and volume, and each is stated as a threshold rather than an opinion.

                    A death cross is a close below the 200-session average with the 50-session average below the 200-session average. An RSI reading above 70 or below 30, held for five consecutive sessions. A distribution bar: volume at or above 2.5 times the 20-session average on a bar that closed lower, escalating to the higher severity at four times. A close at the 52-session low with a declining 20-session slope in on-balance volume. Delivery at or below forty percent of its own 20-session average for two consecutive sessions.

                    Their fire rates come from a rolling walk over 4,407 symbol-sessions, because a single day's readings cannot tell a working rule from a broken one. RSI oversold fired on 3.34% of observations, the distribution rule on 1.41%, and the 52-week-low rule on 6.85%. The death cross fired on 49.83%, and that is not a defect — it is the lesson of the whole exercise. It is a state rather than an event, so on a falling sample about half of all observations sit inside it. The app therefore leads with the cross date and the sessions elapsed, so a persistent condition is never dressed up as news.
                """.trimIndent(),
                doesNotEstablish = "A technical flag establishes that a stated price and volume " +
                    "condition was present in the window checked. It does not establish why it " +
                    "was present, whether it is unusual, or what follows it — and a condition " +
                    "such as the death cross describes a state that was true for roughly half of " +
                    "all observations in the measured walk.",
                relatedFlag = "DEATH_CROSS",
            ),

            Lesson(
                id = "beginner.red.flag.school.fundamentals",
                title = "The ten fundamentals checks against four real pages",
                body = """
                    The four captured pages make the thresholds concrete, because each one produces a countable answer.

                    RELIANCE: operating cash flow of 192,113 crore against profit after tax of 95,754 crore, so the cash-flow check stays quiet; debt to equity moved from 0.4439 to 0.4457, which is not a spike; interest coverage is 8.7489 times, far above two; and its single flag is other income at 28,846 crore, or 30.1% of profit after tax, against a thirty percent threshold. TCS: coverage of 63.5354 times, debtor days moving from 84 to 93 — a nine-day rise, just under the ten-day floor the receivables check needs — and negative other income in the year, so no flags at all. HDFCBANK: none, because the checks that would apply there are dropped for a bank by design. TITAN: debt to equity of 1.95 times against a derived consumer-durables ceiling of 1.0, which is one flag, while a single negative cash-flow year is deliberately not enough and an inventory-days rise of five is not enough either.

                    Four pages, two flags. That is the shape to expect from a well-calibrated rule set, and it is worth noticing how much of the answer came from deciding not to fire.
                """.trimIndent(),
                doesNotEstablish = "A fundamentals flag establishes a measured condition in one " +
                    "company's published accounts for one financial year. It does not establish " +
                    "fraud, distress, or a change in a business's prospects, and the sector bands " +
                    "behind several of these checks are derived rather than measured.",
                relatedFlag = "DEBT_TO_EQUITY_SPIKE",
            ),

            Lesson(
                id = "beginner.red.flag.school.not_proof",
                title = "What a flag does not prove",
                body = """
                    The most important sentence on every row is its caveat, and the distribution bar's is the sharpest: high volume means high participation, and direction requires context. One session can also be an earnings event, a corporate action or an index rebalance — and this app's sources carry none of those, so it cannot tell the three apart.

                    The delivery caveat is similarly blunt, and it is quoted rather than paraphrased in the rule text: a low delivery percentage is not necessarily bearish, and delivery has to be read alongside price action and wider market context. The 52-week-low rule adds that a falling on-balance-volume slope during a range can reflect profit-taking rather than distribution, so the slope shows direction and not intent.

                    Two more limits are structural. A flag computed from a financial year is a reading of that year, not of today. And when a check cannot run — no delivery file, an unresolved sector, no receivables line — the app abstains and says which check abstained and why, rather than reporting the silence as a pass.
                """.trimIndent(),
                doesNotEstablish = "A raised flag with its caveat attached establishes one " +
                    "measured condition and the limits of that measurement. It does not establish " +
                    "intent behind a session, a connection between a flag and any later move, or " +
                    "that a company is engaged in anything improper.",
                relatedFlag = "DISTRIBUTION_DAY",
            ),
        ),
    ),

    // =========================================================================
    // 6. Money before stocks
    // =========================================================================
    Module(
        id = "beginner.money.before.stocks",
        level = Level.BEGINNER,
        title = "Money before stocks",
        summary = "The unglamorous prerequisites: money set aside, money not borrowed, a horizon " +
            "long enough to survive being wrong, and an honest comparison against a diversified " +
            "index fund.",
        lessons = listOf(

            Lesson(
                id = "beginner.money.before.stocks.emergency",
                title = "An emergency fund comes before a screen",
                body = """
                    The first question is not which stock. It is whether there is money set aside for the next six to twelve months of ordinary expenses — rent, food, medical, transport — in a form that can be reached without selling anything at a moment of your choosing.

                    This matters because it converts the nature of every other decision. Money you do not need for a known date has no deadline, so a falling price is an inconvenience rather than an emergency, and an inconvenience is something you can wait out. Money that replaces next month's rent has a deadline, and under that deadline being wrong is expensive regardless of how good the analysis was.

                    The app cannot help with this part and does not try. It has no view of your finances, no portfolio, no holdings, and no record of what you own. The screens are a reading of a company and of a chart, and a reading of a company is the last step of a process rather than the first.
                """.trimIndent(),
                doesNotEstablish = "An emergency fund is a personal-finance precaution. Nothing in " +
                    "this app measures it, and no lesson here establishes that a reader's savings " +
                    "are adequate, that their income is secure, or that any particular purchase is " +
                    "affordable to them.",
            ),

            Lesson(
                id = "beginner.money.before.stocks.no_borrowing",
                title = "Money you borrowed is money you do not have",
                body = """
                    The second rule follows directly from the control test in this level. Borrowing to invest means the reader's outcome is set by two things at once: whether the stock's direction was right, and whether the borrowed cost plus any margin requirement let the position survive being wrong in the meantime. A person who is directionally right and financially forced out still loses.

                    Beyond that, leverage multiplies exactly the error this app has measured. When a directional reading classifies about half of sessions correctly, a one-times position and a three-times position do not both fail; they fail by different amounts, and the cost of the money compounds the difference.

                    This app offers no margin, no derivatives, no intraday product and no broker connection of any kind. It reads end-of-day published data and computes on a phone. Nothing in it can place, size or close an order, and the regulatory posture is explicit that it is educational analysis rather than advice.
                """.trimIndent(),
                doesNotEstablish = "The absence of any trading or margin feature in this app " +
                    "establishes nothing about a reader's own circumstances. It does not establish " +
                    "that borrowing is always wrong for every person and horizon, and it is not a " +
                    "statement about the suitability of any instrument.",
            ),

            Lesson(
                id = "beginner.money.before.stocks.spread_and_horizon",
                title = "Diversification, horizon, and the honest index comparison",
                body = """
                    Diversification is not a slogan about comfort; it is a consequence of the arithmetic above. If no reading on this app has been shown to classify next-session direction better than about one half, then holding one company's outcome rests entirely on that company's business rather than on anything measurable here.

                    Horizon follows from the same place. A change of mind is cheap when the money is not needed soon, and expensive when it is. A review date written down in advance, and a thesis that names what would prove it wrong, turn a held position from an open-ended hope into something with a scheduled test.

                    And here is the honest comparison. Against a diversified index fund, single-stock analysis has to justify itself, and this app cannot make that case for you because it holds no fund data at all — no expense ratio, no tracking error, no net asset value history, no tax treatment applied, no comparison of any kind. What it does hold is a measurement worth putting next to that decision: on its own committed fixture, the next session was higher 2,723 times out of 5,565, which is 48.93%. Build your own view of what that implies for a single-stock approach.
                """.trimIndent(),
                doesNotEstablish = "Diversification, a stated horizon and an explicit comparison against a " +
                    "diversified fund are ways of thinking about risk, and none of them " +
                    "establishes anything on its own. This app measures none of them and holds " +
                    "no fund data, and its 48.93% next-session base rate is a property of the past " +
                    "372 sessions on 15 stocks rather than a forecast or a return figure.",
            ),
        ),
    ),

    // =========================================================================
    // 7. Indian tax starter
    // =========================================================================
    Module(
        id = "beginner.tax.starter",
        level = Level.BEGINNER,
        title = "Indian tax starter",
        summary = "The shape of Indian equity taxation — holding period, the short and long " +
            "regimes, and transaction-level levies — with no rates, because no rate in this " +
            "file is a rate the app has verified.",
        lessons = listOf(

            Lesson(
                id = "beginner.tax.starter.holding_period",
                title = "Holding period changes the treatment, not just the paperwork",
                body = """
                    Indian equity taxation is organised around how long a share has been held. A holding period threshold separates the short-term treatment from the long-term one, and crossing it changes which set of rules applies to the gain.

                    Why it belongs in a first-course module: the threshold interacts with everything else on this list. It is a reason a trading frequency is a tax decision as much as a market one, and it is a reason that "how long do I plan to hold this" is not a rhetorical question.

                    This app holds no record of what any person holds and computes nothing for anyone. It has no transaction records, no cost basis, no purchase dates, and no way to know a reader's holding period even in principle. That is a deliberate absence, not a missing feature.
                """.trimIndent(),
                doesNotEstablish = "Holding period is a fact about a transaction this app cannot see. Nothing here " +
                    "establishes any reader's actual holding period, tax treatment, liability or " +
                    "eligibility. The threshold is deliberately unstated: rates change, every " +
                    "figure must be verified against the Income Tax Department's current " +
                    "publication or a SEBI-registered adviser before anyone relies on it.",
            ),

            Lesson(
                id = "beginner.tax.starter.two_regimes",
                title = "The two regimes, described without a single percentage",
                body = """
                    The structure you will meet is two regimes rather than one. Gains on shares held beyond the holding-period threshold fall under the long-term regime, often abbreviated LTCG; gains on shares held within it fall under the short-term regime, abbreviated STCG. Different rate slabs, exemptions and reporting treatment apply to each.

                    This lesson deliberately contains no percentages, thresholds or slab edges. The app has no measured source for Indian tax rates, and rates are among the fastest-changing facts in finance — a number written into an education screen would be confidently wrong within a budget cycle, and a reader has no way to tell that it was never checked.

                    What to do instead is simple and is the only responsible instruction this module can give: treat every figure you read about Indian equity taxation as expired until you have read it this financial year from the Income Tax Department's own pages or from a SEBI-registered adviser's current written advice.
                """.trimIndent(),
                doesNotEstablish = "Naming the existence of two regimes establishes the shape of " +
                    "Indian equity taxation and nothing more. It states no rate, threshold or " +
                    "exemption, and this module is not tax advice — rates change, and every " +
                    "figure must be verified against the Income Tax Department's current " +
                    "publication or a SEBI-registered adviser.",
            ),

            Lesson(
                id = "beginner.tax.starter.cost_stack",
                title = "Transaction-level levies are a cost, not a rounding error",
                body = """
                    Some levies are charged on the trade rather than on the gain. Securities transaction tax is levied on cash-market equity transactions. GST applies to the brokerage charge rather than to the trade itself. Brokerage is charged per execution, which means an entry and an exit are two charges. And there is one more that is not a levy at all but behaves like one: slippage, the gap between the price on the screen and the price actually obtained.

                    Added together these form a round-trip cost, and that is the number that matters, because it is charged on every completed journey rather than only on the profitable ones. A strategy that is directionally correct slightly more often than a coin flip can still lose money to a fixed cost applied to both sides.

                    The app holds no brokerage schedule, no tax rate and no fill record, and it therefore cannot compute a round-trip cost for any stock or any reader. It names the components because the structure is stable; the amounts are not, and neither is quoted.
                """.trimIndent(),
                doesNotEstablish = "Naming the components of an Indian round-trip cost establishes the structure of " +
                    "the charges and no amounts whatsoever. It does not establish what any reader " +
                    "would pay, whether a cost exceeds any particular edge, or what a tax " +
                    "liability would be. Rates and thresholds change, and every figure must be " +
                    "verified against the Income Tax Department's current publication or a " +
                    "SEBI-registered adviser.",
            ),
        ),
    ),

    // =========================================================================
    // 8. Why did this number disappear?
    // =========================================================================
    Module(
        id = "beginner.three.states",
        level = Level.BEGINNER,
        title = "Why did this number disappear?",
        summary = "A blank on the fundamentals tab is never a zero. Five named states explain " +
            "it, and the bank debt-to-equity card is the clearest example of the difference.",
        lessons = listOf(

            Lesson(
                id = "beginner.three.states.model",
                title = "Five states, and a blank is never a zero",
                body = """
                    When a ratio card shows nothing, the app is reporting one of five states, and the state is named on the card alongside a reason.

                    Available means the figure is in hand and inside its freshness window. Suppressed for sector means the figure is not meaningful for this kind of business, and was removed on purpose. Not sourced means the figure is perfectly meaningful but no source the app uses publishes it. Stale means the figure exists but is older than the window its kind requires. Missing means the figure is meaningful and normally available, and this particular page did not carry it.

                    The distinction that matters most to a reader is the middle pair. Suppressed says the concept does not apply here; not sourced says the concept applies and the app cannot see it. Collapsing those two is the difference between an honest blank and a misleading one, and the app carries the state as an explicit value rather than as a null so that a caller cannot accidentally print a zero for a ratio that was never computed.
                """.trimIndent(),
                doesNotEstablish = "A blank ratio card with a named state establishes why the " +
                    "app has no figure. It does not establish that the underlying number is " +
                    "zero, unavailable in the world, or unimportant — a suppressed ratio is not " +
                    "a missing value and a not-sourced ratio is not an absent one.",
            ),

            Lesson(
                id = "beginner.three.states.bank_debt_to_equity",
                title = "The bank debt-to-equity card, and the four this app cannot fill",
                body = """
                    Take a bank. Debt to equity is dropped for a bank, and the reason is printed on the card: a bank's deposits and borrowings are the business itself rather than leverage on top of it, so dividing one by the other measures nothing useful. That is suppression — not meaningful here, on purpose. The same reasoning removes interest coverage, because a bank's published interest row is interest income rather than interest expense, and it removes operating margin, because a bank page publishes a financing margin instead of an operating profit line.

                    Now take the four ratios a bank genuinely needs: net interest margin, gross and net non-performing assets, CASA and provision coverage. These are not suppressed. They appear in the plan for banks precisely because they matter, and every one of them comes back as not sourced. A live probe found the labels on the page with the values sitting behind a sign-in wall, and this app has no credentials and does not acquire any.

                    Same blank-looking row, same bank, two completely different meanings. That is the whole point of the five-state model.
                """.trimIndent(),
                doesNotEstablish = "The bank card's two kinds of blank establish that debt to " +
                    "equity is meaningless for a lender while net interest margin, asset " +
                    "quality, CASA and provision coverage are meaningful and unavailable to " +
                    "this app. Neither establishes the value of any of the four, which the app " +
                    "cannot see.",
                relatedRatio = "DEBT_TO_EQUITY",
            ),

            Lesson(
                id = "beginner.three.states.permanent_gaps",
                title = "Three ratios no source publishes, and two that will never evaluate",
                body = """
                    Three of the twelve planned ratios can never be computed here, and they are not sector-specific: they are simply absent from the one fundamentals page this app reads.

                    Gross margin is gone because the page publishes no cost-of-goods line anywhere, so there is no denominator. The current ratio is gone because neither the ratio nor its components appear — the collapsed balance-sheet summary carries no current-assets against current-liabilities split. Enterprise value to EBITDA is gone because there is no enterprise value and no EBITDA line. Two things that look like data on the page are not: enterprise value to EBITDA appears as an interactive chart button rather than a published figure, and a label match is not a value parse. A single grep for a name on a 218 KB page will happily return a hit for a number nobody can read.

                    Two more gaps are of a different kind. Goodwill above a fifth of total assets, and promoter shares pledged, are implemented, unit-tested and then abstaining on every real symbol — because goodwill and pledged shares are simply not published by this source. The app reports a permanent gap rather than substituting another line, which would misstate the ratio.
                """.trimIndent(),
                doesNotEstablish = "These five gaps establish only what this app cannot obtain " +
                    "from its chosen sources. They do not establish that the underlying figures " +
                    "are unavailable to a reader, that they are unimportant, or that any " +
                    "substitute the reader finds would be measuring the same thing.",
                relatedRatio = "EV_TO_EBITDA",
            ),
        ),
    ),
).also { Curriculum.register(it) }