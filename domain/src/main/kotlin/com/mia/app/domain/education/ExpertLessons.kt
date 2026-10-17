package com.mia.app.domain.education

/**
 * The expert level (planv2 §W6, gaps E1/E3, `gaps.md` §3 "Level 3").
 *
 * ### The editorial rule for this file
 * Every lesson teaches **how to compute something, how to read a table, or where to verify a
 * claim** — and none of them forecasts a price, ranks a stock, or estimates a return. That is not
 * a stylistic preference; SEBI's Investment Adviser boundary (plan.md §1.4) is enforced by
 * `AdviceLanguage.findForbiddenPhrases` and `HonestyContract.requireNotProbabilityClaim`, and
 * `ExpertComplianceTest` walks every string below through both.
 *
 * The vocabulary that makes that possible: *"the measurement shows"*, *"the measurement does
 * not show"*, *"how to compute"*, *"how to read"*. Not *"will"*, *"expect"*, *"likely"*.
 *
 * ### Why the numbers are quoted rather than rounded
 * Each figure below is traceable to a measurement committed in this repository — `plan.md` §6.1,
 * `docs/VERIFICATION.md`, `docs/LIVE-PROBE.md`, or the two research commits that measured score
 * calibration (`0fd2869`) and whether a hit is a trade (`ec04991`). Nothing here is estimated,
 * smoothed or "roughly". A lesson that paraphrases a measurement into a vague claim is the same
 * failure mode as an app that rounds a score into a forecast.
 *
 * ### What this file deliberately does NOT contain
 * No threshold chosen from these tables. Commit `ec04991` states the reason: "nothing here is
 * asserted. A threshold chosen from these numbers would be the guess this file exists to remove."
 */
private object Expert {

    // ---- measurement constants, quoted from the repository (see each module's KDoc) ----------

    const val WALK_FORWARD = "walk-forward, 17 large caps, 5-bar stride, 875 settled candidates"
    const val WALK_FORWARD_TRADES = "949 candidates"
}

/**
 * Gap E3 / planv2 §W6: the expert level, as data.
 *
 * Ten modules, 42 lessons. Registration is **not** done here — `Curriculum.register(...)` wires
 * these lists in, so this file stays free of any dependency on the schema object and can be read
 * on its own.
 */
val EXPERT_MODULES: List<Module> = listOf(

    // =====================================================================================
    // 1. Calibration dashboard
    // =====================================================================================
    Module(
        id = "expert.calibration",
        level = Level.EXPERT,
        title = "Calibration dashboard",
        summary = "How to read a hit table: what n means per cell, why the raw match score runs " +
            "backwards against hit rate, and why a hit is a chart event rather than a gain.",
        lessons = listOf(

            Lesson(
                id = "expert.calibration.two-questions",
                title = "Reading a hit table without being misled by it",
                body = """
                    A calibration table answers one question per cell: of the formations in this
                    bucket, how many went on to touch their measured-move level inside this
                    horizon? Two numbers must travel together and the first one is n.

                    n is the count of outcomes behind the percentage in that cell, never the
                    count of patterns, never the count of stocks. A cell reading 83% from 87
                    samples and a cell reading 83% from 8,700 samples are the same string and
                    completely different claims. This app withholds its own hit rate below 30
                    samples and prints "(n of 30)" instead, so the smallest cell you will ever
                    see here has n = 30.

                    The structure used throughout the expert level is a grid: rows are buckets
                    (score band, target distance, stop distance), columns are horizons (h1, h2,
                    h3, h5, h10, h20 — sessions after the formation), and every cell carries its
                    own n. Read it in three passes:

                    1. n first. Discard any cell whose n cannot support the precision implied by
                       its percentage. A single flip moves a 10-sample cell by ten points.
                    2. Shape second. Does the row move monotonically across the columns, or is
                       that impression created by one cell moving?
                    3. Level last, and always against the base rate in the same units. From the
                       base-rate module: next-day direction over the app's fixture is 48.93%,
                       and the no-pattern five-session control below is 10%. A percentage with
                       no control beside it is a number without meaning.

                    One more trap: the horizons are *sessions*, not days, and the Indian trading
                    year runs to roughly 250 sessions — not 365. A 20-session horizon is about
                    five weeks of trading, not a month.
                """.trimIndent(),
                doesNotEstablish = "It does not establish that any cell's rate will repeat. Every " +
                    "number here is a count of what already happened on this fixture, and a cell " +
                    "is evidence about a past sample rather than a measurement of the future.",
            ),

            Lesson(
                id = "expert.calibration.score-runs-backwards",
                title = "The match score runs backwards — and the cause is geometric",
                body = """
                    This is the most important table in the app, because it is the one that
                    contradicts what a reader expects a score to do.

                    ${Expert.WALK_FORWARD} (commit 0fd2869). Hit rate by score bucket and horizon:

                      bucket   h1    h2    h3    h5    h10   h20     n
                      50-59    30%   32%   33%   35%   39%   45%    670
                      60-69    28%   28%   28%   30%   32%   34%    205

                    Higher score, lower hit rate, monotone across all six horizons. Not noise:
                    monotonicity across six columns is the signature of a mechanism rather than a
                    fluctuation.

                    The mechanism is measured in the same commit. The same formations, split by
                    how far the projected level sits from the breakout price:

                      bucket    median target distance    median score
                      50-59                9.7%                 55
                      60-69               14.1%                 61

                    A more textbook-ideal formation is a *bigger* formation. A bigger formation
                    is a *taller* measured move. A taller measured move is further away, and
                    further away is harder to reach inside five sessions. So the score is
                    behaving as a proxy for how distant the projected level is, oriented opposite
                    to the outcome: the 61 wants about a 14% move and the 55 wants about a 10%
                    one.

                    The practical reading rule: **do not compare hit rates across score bands**,
                    because the score is confounding distance with fit. Compare within a distance
                    band instead. Two numbers that answer different questions should not be
                    averaged, ranked against each other, or traded off against one another.

                    This is why the app keeps match score and hit rate as separate numbers with
                    separate labels, and why it never lets one stand in for the other.
                """.trimIndent(),
                doesNotEstablish = "It does not establish that a lower-scoring formation is a better " +
                    "one to hold, or that inverting the score would produce a usable ranking. It " +
                    "establishes only that, on this sample, the score and the hit rate move in " +
                    "opposite directions, and that target distance is the measured cause.",
                relatedPattern = "Measured Move",
            ),

            Lesson(
                id = "expert.calibration.distance-curve",
                title = "The distance curve, and where the real signal is",
                relatedFlag = "DEATH_CROSS",
                body = """
                    Hold the target distance fixed and everything else becomes legible. Hit rate
                    against distance from the breakout price (commit 0fd2869):

                      1% away   87%       3% away   52%
                      7% away   18%      12% away  13%

                    And the same sample restricted to targets within a given distance, read at
                    the five-session horizon:

                      target within 2%    87%   n=87    (10% of samples)
                      target within 3%    83%   n=119   (14%)
                      target within 4%    74%   n=167   (19%)
                      target within 5%    66%   n=197   (23%)
                      no pattern at all   10%

                    That last row is the control, and it is why the table means anything. A
                    10% base rate at five sessions means a level within 3% is touched 83% of the
                    time against a coin weighted ten times over — a real gap, visible without any
                    scoring model at all.

                    Two conclusions, and it is worth being precise about which is which.

                    The strong one: distance, not score, is what the outcome depends on here. The
                    curve is steep and steeply ordered.

                    The weak one: the app does not apply a distance filter today, so nothing in
                    the shipped product selects on this. Commit 0fd2869 says so in one line —
                    "a threshold invented here would be the guess this file exists to remove" —
                    and no threshold was invented. Until one exists, this table is a
                    description of the fixture, not a feature.

                    Note also what a hit is: the measured-move *level was reached*. It says
                    nothing about whether a position was ever open, at what price, or what a
                    reader would have done. Commit ec04991 measured that second question
                    separately.
                """.trimIndent(),
                doesNotEstablish = "It does not establish that proximity to a projected level " +
                    "precedes it in time, or that filtering on distance is profitable. A hit is " +
                    "a level being reached, never a gain, and the app applies no distance filter.",
            ),

            Lesson(
                id = "expert.calibration.hit-is-not-a-gain",
                title = "Hit rate is not win rate",
                body = """
                    The app's own simulated track record, over 15 large caps (docs/VERIFICATION.md
                    §3): 945 outcomes recorded, 930 resolved, 153 hits — 16.45%, split almost
                    evenly by direction (up 16.38%, down 16.52%).

                    That is the app's honest hit rate and it is a low number, which surprises
                    people who expect a detected pattern to work more often than not. The
                    explanation is arithmetic, not disappointment. The simulated moves average
                    4-5% of price, while the median five-session favourable excursion is about
                    1.8%. Most simulated targets are simply further away than most moves travel.

                    Measured on this fixture by distance, the hit rate is a monotone function of
                    that distance:

                      1% → 33.3%    2% → 26.5%    3% → 17.9%
                      4% → 13.9%    5% → 10.0%    6% → 7.2%    7% → 4.9%

                    Note that the 3% figure (17.9%) is far below the 52% in the research table
                    above. These are two different samples under two different constructions —
                    the app's simulation over its own detections, and the research walk-forward
                    over nine evaluated detectors. Quoting either without saying which sample
                    produced it is how a 17.9% and a 52% end up in the same argument.

                    The habit to build: whenever a hit rate appears, ask three questions. What
                    was the sample? What counted as a hit? What would have had to happen in
                    between for it to become money? This app answers the first two in its own
                    copy and declines to answer the third, because answering it would require
                    assuming fills, sizes and costs that the app does not have.
                """.trimIndent(),
                doesNotEstablish = "It does not establish that any pattern is profitable. A hit is " +
                    "a chart event — a level being reached — and says nothing about entry, exit, " +
                    "position size or cost.",
            ),

            Lesson(
                id = "expert.calibration.gate-satisfiability",
                title = "Check that a threshold can be crossed at all",
                body = """
                    A gate that nothing can pass is invisible to every test that checks what
                    happens behind it. This app had one. It gated on the match score, at 70, and
                    the project-and-alert path — the headline feature, the one that draws a
                    projected level and fires a notification — required a score of 70 or more
                    plus regime alignment.

                    The 875-settled-candidate walk-forward never produced one. The highest score
                    in 875 samples was 68. The path had never executed, and nothing in the
                    codebase had ever asked whether it could. It is now gated on the distance
                    between the projected level and the last close, and the screen names which
                    condition withheld a projection.

                    This is worth internalising as a habit, because it generalises. For every
                    threshold that gates a user-visible feature, the question is not "is the
                    logic behind it correct?" but "does anything reach it?". A threshold nobody
                    crosses is not a conservative feature; it is a dead branch rendered as a
                    working one. The app reports every gate it applies, and `gaps.md` §4 lists
                    gate satisfiability as a permanent test to add — this lesson is the education
                    half of that.

                    The same reasoning runs the other way. A pattern that fires on 49.83% of
                    rolling observations (docs/VERIFICATION.md §2, reproduced independently at
                    49.49% in Python) is not a broken rule; a death cross is a *state*, not an
                    event, so on a downtrending sample half of all observations satisfy it. The
                    app's response was to lead the flag with the cross date and the sessions
                    elapsed, so a persistent condition is never presented as news. Frequency is
                    a property of the definition, and a definition that fires constantly must be
                    reported with its frequency.
                """.trimIndent(),
                doesNotEstablish = "It does not establish that any pattern is profitable, and it " +
                    "does not establish what the score-gated path would have done had it ever " +
                    "run. It establishes only that on 875 samples no formation reached the score " +
                    "the path required.",
                relatedFlag = "DEATH_CROSS",
            ),
        ),
    ),

    // =====================================================================================
    // 2. Expectancy & sizing lab
    // =====================================================================================
    Module(
        id = "expert.expectancy",
        level = Level.EXPERT,
        title = "Expectancy & sizing lab",
        summary = "Computing expectancy from measured excursions, why the denominator must be " +
            "the whole sample, what the Indian round-trip cost stack does to it, and why sizing " +
            "heuristics break.",
        lessons = listOf(

            Lesson(
                id = "expert.expectancy.the-formula",
                title = "Expectancy, and which numbers belong in it",
                body = """
                    Expectancy per unit of risk is the arithmetic of a repeating outcome:

                      expectancy = (win rate x average win) - (loss rate x average loss)

                    The formula is trivial and almost always misused, because the inputs are
                    where the honesty lives. Three rules for the inputs:

                    Rule one — average win and average loss come from **measured excursions**, not
                    from the projected level. Maximal favourable excursion is how far price
                    actually went your way before the outcome resolved; maximal adverse excursion
                    is how far it went against. The app can compute both from stored bars because
                    both are properties of the price series, not of a position. What it cannot
                    supply is your entry price, your fill, or your size, so it cannot turn them
                    into money.

                    Rule two — measure the excursions on the same horizon the outcome is scored
                    on. A favourable excursion measured over twenty sessions is not the same
                    quantity as one measured over five, and mixing them silently flatters the
                    result.

                    Rule three — the median and the mean answer different questions. From the
                    base-rate module, the median next-day move is 0.73% while the mean is 0.99%.
                    A strategy whose edge lives in a few large days shows a thin median and a fat
                    mean, which means its win rate will be low and its per-win size large. That
                    combination is extremely sensitive to the exact exit rule, because the tail
                    is doing all the work.

                    Expectancy is a statement about an arithmetic relationship in a sample. It is
                    not a projection, and quoting one without the sample it came from is how a
                    number becomes a promise.
                """.trimIndent(),
                doesNotEstablish = "It does not establish that any positive expectancy is " +
                    "capturable after costs, nor that the excursions measured on this fixture " +
                    "will persist on another sample.",
            ),

            Lesson(
                id = "expert.expectancy.stop-sweep",
                title = "The stop sweep — and why the denominator is the whole sample",
                body = """
                    The app's own measurement of whether a "hit" is a trade (commit ec04991,
                    ${Expert.WALK_FORWARD_TRADES}). Stops on tight targets only, within 3%,
                    n=129, median target 1.2%:

                      stop     wins  losses  open    win% of all    expectancy
                      0.5%       35     80     14        27.1%          0.05%
                      1.0%       51     59     19        39.5%          0.03%
                      2.0%       73     24     32        56.6%          0.26%

                    Two things in that table matter more than the numbers.

                    First, the "open" column. The first run of this sweep **excluded** unresolved
                    trades, which turned a 5% stop into "96% of decided trades won" — while 666
                    of 949 candidates had not resolved at all. Excluding them answers a question
                    about the subset that finished, which is the subset that worked. The table
                    above counts an unresolved trade as zero. That is the only version of the
                    number worth quoting, and the difference between the two versions of this
                    same measurement is the single most useful thing in the lesson.

                    Second, the control. A **random** direction at the same 1.2% target size with
                    a half-size stop wins 31.7%. So the 39.5% at a 1% stop is about eight points
                    over random and the 56.6% at a 2% stop about twenty-five. The gap over random
                    is the signal; the absolute number is not. The 83% figure quoted elsewhere in
                    this curriculum is mostly geometry, and the distance control already
                    established how much of it is.

                    Read honestly: expectancy is positive but small, peaking at 0.26% per trade
                    at a 2% stop on tight targets. Both halves stay in the table because quoting
                    the win rate alone would be misleading, and so would quoting the expectancy
                    alone.
                """.trimIndent(),
                doesNotEstablish = "It does not establish that these win rates or expectancy values " +
                    "transfer to another sample, a live market, or any position actually taken. " +
                    "No fills, sizes or costs are modelled.",
            ),

            Lesson(
                id = "expert.expectancy.cost-stack",
                title = "The Indian round-trip cost stack",
                body = """
                    Every expectancy figure above is gross. The Indian equity cost stack sits
                    between it and anything real, and it has four components:

                    Securities transaction tax, charged on turnover — on both legs, so it is paid
                    entering and leaving, not once.
                    GST, charged at 18% on brokerage and on the exchange's own charges, not on
                    turnover. This one surprises people, because it appears on a small base.
                    Brokerage, a per-order or percentage charge set by your own contract, plus
                    exchange transaction charges, SEBI turnover fees, stamp duty, and the various
                    statutory levies.
                    Slippage, the difference between the price you intended and the price you
                    got — and the only component that scales with how urgently you trade.

                    The arithmetic that matters: the stack is a near-fixed percentage of turnover,
                    so it is charged **twice** on a round trip. Against the 0.26% per-trade
                    expectancy measured above, a round-trip cost of the same order of magnitude
                    consumes the entire edge. That is not a rounding objection; it is
                    annihilation. plan.md §6.1 makes the same point about the base rate: at 50.5%
                    accuracy, STT plus GST plus brokerage plus slippage exceed the edge entirely.

                    Why the app does not compute this number for you, which is a real limitation
                    rather than a design choice:

                    Brokerage is per-contract, so the app would need your contract details, and
                    it stores none — it has no broker connection and asks for no credentials.
                    Slippage requires knowing what you intended to trade and when, which is
                    execution data the app does not have and cannot reconstruct from end-of-day
                    bars.
                    Statutory rates change, and a tax rate hard-coded into an app becomes a
                    wrong number with no visible date on it.

                    So: compute it from your own contract note, which states every charge
                    itemised. That is the one cost figure the app deliberately sends you to
                    fetch for yourself, because it is the one it cannot know.
                """.trimIndent(),
                doesNotEstablish = "It does not establish a cost figure for your account, and it is " +
                    "not a statement that a particular cost level makes any method unprofitable. " +
                    "Only your contract note states your charges, and rates change.",
            ),

            Lesson(
                id = "expert.expectancy.atr-risk-units",
                title = "ATR as a unit of risk, and the sizing arithmetic",
                body = """
                    Comparing a stop to a percentage of price is comparing across different
                    quantities of risk. A 2% stop is a small move in a quiet month and a large one
                    during a volatile stretch. The Average True Range solves this by measuring
                    volatility in the unit that matters — money.

                    ATR is the mean of the true range over a rolling window. True range is the
                    larger of today's high minus today's low, and the absolute distance from
                    today's close to yesterday's close; that second term is what makes gaps count.
                    The app uses a 14-period ATR, the conventional window, and it is computed from
                    stored daily bars, so it is a statement about the price series rather than a
                    judgement.

                    The sizing arithmetic is then one subtraction:

                      risk budget per position  =  fraction of capital you accept losing
                      stop distance in currency   =  ATR x (a multiple you chose)
                      position size               =  risk budget / stop distance

                    The example that makes the mechanism obvious: capital 100,000; a risk budget
                    of 1%, so 1,000 at risk. ATR of 2% of price is 40 on a stock priced at
                    2,000. At a 1.5-ATR stop the distance is 60, so the size is 1,000 / 60 =
                    16 shares. At a 3-ATR stop it is 120, so 8 shares. Same risk, same rupee
                    amount, different share counts — because volatility changed, not your
                    willingness to lose money.

                    Two failure modes to know before using it. ATR is backward-looking and rises
                    *after* volatility arrives, so a stop placed at 1.5 ATR during a calm stretch
                    is inside the noise range and will be taken out by ordinary movement. And the
                    multiple is a free parameter chosen by a person — it is the most load-bearing
                    unmeasured number in the whole construction, and the honest move is to know
                    how sensitive the outcome is to it, which is exactly what a stop sweep is for.
                """.trimIndent(),
                doesNotEstablish = "It does not establish that any stop multiple, risk fraction or " +
                    "position size is appropriate. The arithmetic converts an assumed risk budget " +
                    "into a share count and nothing more.",
            ),

            Lesson(
                id = "expert.expectancy.fractional-kelly",
                title = "Fractional Kelly, and why sizing heuristics are fragile",
                body = """
                    Kelly sizing answers a well-posed question: given a repeatable edge with odds b
                    and win rate p, what fraction maximises long-run growth? The formula is
                    compact — f* = (b x p - q) / b, where q = 1 - p — and its derivation assumes
                    the edge is real, repeatable and known precisely. Each of those three
                    assumptions is where it breaks.

                    The edge is estimated, not known. A win rate from 30 samples has a standard
                    error near nine points; from 310 samples, near three. Kelly is extremely
                    convex in p, so a small overestimate of p produces a large overestimate of f*,
                    and a small *under*estimate produces a negative fraction — a formula that says
                    bet nothing while you are convinced it works.

                    The odds are estimated too, from the same thin sample, and b enters the
                    denominator directly. When the average win is much larger than the average
                    loss, the estimate of b has enormous leverage on the answer.

                    And the sample is not stationary. The 0.26% expectancy measured at a 2% stop
                    was measured on 949 candidates in one regime over one fixture.

                    The standard mitigation is to use a fraction of Kelly, commonly a quarter or
                    an eighth, to absorb estimation error. That reduces the damage from a bad
                    estimate without removing the mechanism. It does not fix the deeper problem:
                    with an expectancy this small relative to costs, the position size implied by
                    the formula can be dominated entirely by the cost stack rather than by the
                    edge.

                    Bayesian shrinkage is the principled version of the same caution — pull an
                    observed rate toward a prior (here, the measured base rate near 50%) in
                    proportion to how little data supports it. The point of every technique in
                    this lesson is the same one this app makes everywhere else: report the
                    measurement and its uncertainty, and never dress an estimate as a fact.
                """.trimIndent(),
                doesNotEstablish = "It does not establish an appropriate position size or that " +
                    "any sizing rule is robust. Kelly's formula returns a number for assumptions " +
                    "the sample cannot verify, which is why the honest use is sensitivity " +
                    "analysis rather than a single figure.",
            ),
        ),
    ),

    // =====================================================================================
    // 3. Regime & context analysis
    // =====================================================================================
    Module(
        id = "expert.regime",
        level = Level.EXPERT,
        title = "Regime & context analysis",
        summary = "ADX regime alignment, relative strength against a benchmark series, and " +
            "market breadth computed from the bhavcopy file the app already downloads.",
        lessons = listOf(

            Lesson(
                id = "expert.regime.adx-alignment",
                title = "ADX and what regime alignment does to a verdict",
                body = """
                    The average directional index answers one question: is price trending, or
                    ranging? It does not answer which way — ADX is unsigned, and the direction
                    comes from +DI against -DI. The reading that matters is the threshold pair the
                    app uses: above 25 the market is treated as trending, below 20 as ranging,
                    with 20 to 25 deliberately ambiguous.

                    That middle band is a design decision worth understanding. It exists because
                    ADX is a lagging measure built from smoothed directional movement, so it
                    confirms a regime after it has begun. A threshold with no dead zone would flip
                    verdicts on noise near the boundary. The app's candlestick patterns hit this
                    limit directly: a 1-3 bar formation has no zigzag pivot and therefore no
                    measured move, so regime and direction had to come from the pattern's
                    textbook semantics rather than from its geometry.

                    How regime enters a verdict: a match above the render threshold (50) is shown;
                    a match at or above the project threshold (70) with regime alignment is the
                    only path that draws a projected level. Alignment is a *gate*, not a score
                    bonus — it can withhold a high-scoring match, and it cannot promote a low
                    one. Applied to calibration, regime alignment is exactly the kind of context
                    variable worth testing for an interaction: a pattern's hit rate that differs
                    between trending and ranging regimes is a genuinely different measurement from
                    one that does not.

                    The honest limit: ADX is computed from a stock's own bars, so it describes
                    that stock. A stock can trend while the market ranges, and the app's current
                    alignment test says nothing about which of those two is true.
                """.trimIndent(),
                doesNotEstablish = "It does not establish that a trending regime improves any " +
                    "outcome. It defines how the app classifies a regime and where that " +
                    "classification can withhold a match.",
                relatedFlag = "DEATH_CROSS",
            ),

            Lesson(
                id = "expert.regime.relative-strength",
                title = "Relative strength needs a benchmark series",
                body = """
                    Relative strength is the ratio of a stock's return to a benchmark's over the
                    same window, or the stock's price divided by the index's on a rebased scale.
                    Both forms answer the question a stock's own return cannot: not "did it go
                    up?" but "did it go up more than the thing it was measured against?" A stock
                    can rise 3% in a month where the index rose 12%, which is a loss of relative
                    position and looks like a gain on a price chart.

                    It also cannot be computed from nothing. A relative-strength line needs the
                    index series on the same dates as the stock. Until recently the honest answer
                    was that no index series was obtainable, and the engine carried
                    benchmarkCandles = null — a null that said "not scored", rather than a zero
                    that would have looked like "no relative strength".

                    docs/LIVE-PROBE.md §2 closes that gap. A live probe run from GitHub's network
                    found Yahoo answers: fc.yahoo.com seeds a cookie, the getcrumb endpoint
                    returns a crumb, and v8/finance/chart/RELIANCE.NS returns 200 with the pair.
                    The same endpoint serves the benchmarks — ^NSEI (NIFTY 50), ^NSEMDCP50
                    (MIDCAP 50), ^NSEMDCP150 (MIDCAP 150). The NSE index-CSV route is dead: all
                    three candidate paths 404.

                    So relative strength stops being permanently unscored, and the gap's status
                    is "has an exit", not "was wrong". The pattern to copy from that probe is
                    the honest one: a source is not usable because a label matched. A series is
                    usable when a request returns 200 with the data, verified on a real symbol.
                """.trimIndent(),
                doesNotEstablish = "It does not establish that relative strength predicts anything. " +
                    "It records which benchmark series are obtainable, measured by probe, so " +
                    "that a context calculation has a source at all.",
            ),

            Lesson(
                id = "expert.regime.breadth-from-the-bhavcopy",
                title = "Breadth from a file you already downloaded",
                body = """
                    Breadth measures how widely a move is shared rather than how large it is. The
                    classic inputs are advancers versus decliners, new 52-week highs versus new
                    lows, and the share of instruments trading above their 50-day average — and
                    all of them are computable from the end-of-day bhavcopy itself, because that
                    one CSV contains every listed instrument for the day.

                    The fixture proves the file's scope: one daily file parsed to 3,712
                    instruments, 2,662 of them EQ series and 389 SM series. One download, the
                    entire market. That is the reason breadth is cheap in principle — there is no
                    per-symbol request problem, no rate-limit ladder, nothing that scales with
                    the number of stocks. The app fetches that file every evening already, so the
                    inputs are in hand.

                    How to compute each measure from a daily file:

                      advancers / decliners  —  count rows by the sign of the change field; the
                                                ratio, not the raw counts, is the readable form
                      new highs / lows       —  compare each close against its own trailing
                                                52-week extreme; requires a stored history per
                                                instrument, not just today's file
                      share above SMA50      —  needs a 50-session average, so one file cannot
                                                supply it; it needs 50 files or the local bar
                                                store

                    That last line is the practical constraint: one file gives you a same-day
                    cross-section, while anything involving a moving average or a 52-week extreme
                    needs accumulated history. Breadth is therefore a daily discipline rather
                    than a one-off, and the local bar store — not the download — is what makes
                    it affordable over time.

                    What breadth adds that a single stock cannot: a stock that rises while
                    breadth narrows is participating in a move fewer names share, which is
                    observable before any forecast is attempted. The app does not compute a
                    breadth index today. This lesson teaches the arithmetic, because the data is
                    already arriving.
                """.trimIndent(),
                doesNotEstablish = "It does not establish that breadth predicts a direction, and it " +
                    "is not a description of anything the app currently displays. The app stores " +
                    "bars and computes per-stock indicators; it publishes no breadth index.",
            ),
        ),
    ),

    // =====================================================================================
    // 4. Backtesting literacy
    // =====================================================================================
    Module(
        id = "expert.backtesting",
        level = Level.EXPERT,
        title = "Backtesting literacy",
        summary = "Why this app is not a backtester, and the four biases that make any " +
            "backtested result — including a good-looking one — untrustworthy.",
        lessons = listOf(

            Lesson(
                id = "expert.backtesting.not-a-backtester",
                title = "Why this app is not a backtester",
                body = """
                    A backtester answers: "if these rules had run over this history, with these
                    rules known in advance, what would have happened?" That requires running the
                    rules over history *without* the history leaking into them — no future bars,
                    no revised definitions, no parameter chosen after seeing the result.

                    This app does not do that, and the difference is worth being precise about.
                    It stores daily bars, runs detectors over a fixed 500-candle window ending
                    at the latest bar, scores the geometry it finds, and records an outcome once
                    the measured-move level is subsequently reached or expires. Every outcome it
                    reports therefore happened **after** the match was published, which is the
                    property that makes the number meaningful and the property a backtest cannot
                    have.

                    Where the resemblance to a backtest stops:

                    No historical replay. The engine analyses the most recent window; it does not
                    step through the past re-running detection, because the stored series is the
                    input to detection rather than its output.
                    No parameter search. Nothing in the app tunes a threshold by scanning history
                    for the best setting — and per commit ec04991, no threshold was chosen from
                    the research tables at all, because "a threshold chosen from these numbers
                    would be the guess this file exists to remove".
                    One window, not a sample. The app's own figures come from committed fixtures
                    (372 sessions, 41 symbols) and from dedicated walk-forward research, and the
                    two are reported separately precisely because they are different samples.

                    A tracked record and a backtest answer different questions. A tracked record
                    is honest but small and slow. A backtest is large and fast and structurally
                    prone to error. This app chose the first and says so.
                """.trimIndent(),
                doesNotEstablish = "It does not establish that any result from this app is " +
                    "out-of-sample, or that it would have been obtainable in real time. It " +
                    "establishes that the app performs no historical replay and no parameter " +
                    "search.",
            ),

            Lesson(
                id = "expert.backtesting.survivorship",
                title = "Survivorship bias: 41 winners and no memory of the rest",
                body = """
                    Survivorship bias is the error of testing on a sample that contains only the
                    subjects that made it. Every instrument in this app's price fixture exists
                    today, is large enough to be in the bhavcopy, and is still listed. The
                    delisted, merged, demerged-into-nothing and failed names are not in it —
                    they cannot be, because the file is fetched from the exchange as it publishes
                    today.

                    That is not a fixture bug. It is the default state of every dataset assembled
                    from a current symbol master, and it biases results in one specific,
                    predictable direction: companies that disappeared are missing from the
                    denominator of every long-run measurement. Remove the failures and averages
                    improve, drawdowns shrink, and survivorship looks like skill.

                    Three concrete examples from this repository, each a reminder that "still
                    listed" is not the same entity over time:

                    TATAMOTORS trades for the first 140 of the fixture's 372 sessions and then
                    stops. It demerged; the successor is TMCV, a new ISIN listed 2025-11-12. The
                    app detects the symbol disappearing and reports it, rather than resuming it.
                    The retired ISIN was inherited by TMPV, a different company listed since
                    1998 — so mapping "TATAMOTORS" naively to the passenger-vehicle entity
                    would send a reader to the wrong company with full confidence.

                    HDFCBANK and KOTAKBANK each appear in the fixture with a single-bar move of
                    over 50% (see the corporate-actions lesson). Both are consolidations. A
                    symbol's identity is stable; its price series is not, unless adjusted.

                    The defensive habit: whenever a dataset is assembled from a present-day list
                    — a watchlist, a screen, a fixture — ask what the list excluded, and treat
                    every long-horizon statistic computed from it as optimistic by an unknown
                    amount.
                """.trimIndent(),
                doesNotEstablish = "It does not establish a corrected figure for any statistic in " +
                    "this curriculum. The bias's size is unmeasured here; it is identified so " +
                    "the reader discounts long-horizon numbers accordingly.",
            ),

            Lesson(
                id = "expert.backtesting.lookahead-and-corporate-actions",
                title = "Look-ahead bias, and the four splits hiding in the fixture",
                body = """
                    Look-ahead bias is any use of information that would not have existed at the
                    moment being simulated: a bar published after the decision, a fundamental
                    restated later, a symbol list that includes only names known to have
                    survived.

                    The subtlest form is the adjusted-price question, and this app's fixture
                    contains four real examples:

                      HDFCBANK    2025-08-26   -50.4%   (~1:2)
                      BAJFINANCE  2025-06-16   -89.9%   (~1:10)
                      TATAMOTORS  2025-10-14   -40.2%   (~1:1.7)
                      KOTAKBANK   2026-01-14   -80.3%   (~1:5)

                    These are genuine corporate actions, not data errors. Any indicator computed
                    across one of those bars — a moving average, an ATR, a z-score, a return — is
                    computed over a discontinuity that did not represent a tradable move. A
                    backtest that silently adjusted for them would be assuming the adjusted data
                    was available in the historical window; a backtest that did not would produce
                    spectacular fake signals on four dates.

                    The app's position is neither. Its stored series is **unadjusted**, because
                    the NSE bhavcopy is canonical and publishes raw prices, and plan.md §5.1's
                    write-once guarantee means a correction could never be accepted afterwards.
                    Rewriting stored history to look continuous would be fabricating data. So the
                    bars are left exactly as published and the reader is told, below the chart,
                    where the effect is the chart.

                    Detection is a ±25% single-bar threshold between near-adjacent sessions. The
                    choice is deliberate: Indian daily moves of ±10% are ordinary, so a lower
                    threshold would fire constantly and train the reader to ignore it, while 25%
                    sits above routine volatility and below the smallest common split ratio. It
                    is symmetric in sign, because a reverse split can re-rate a price upward and a
                    silently broken series misleads exactly as much when it rises. Near-adjacency
                    is required because Indian markets shut at weekends, so every real split
                    straddles one.

                    The general lesson: unadjusted data is honest data with a stated defect.
                    Adjusted data that arrives without a source is a fabrication.
                """.trimIndent(),
                doesNotEstablish = "It does not establish that the fixture's other 368 sessions are " +
                    "free of corporate actions. The detector flags single-bar moves above 25% " +
                    "and is silent about anything smaller or spread over more than four days.",
            ),

            Lesson(
                id = "expert.backtesting.overfitting-and-multiples",
                title = "Overfitting and the multiple-comparisons problem",
                body = """
                    If you test enough things, something will look significant. With a
                    conventional 5% threshold, one in twenty tests produces a "significant"
                    result by construction — and a screen that ranks a watchlist by twenty
                    ratios and reports the best-looking five has run twenty tests and reported
                    five winners.

                    The size of the trap here is concrete. The app looks for 14 detectable
                    patterns, evaluated across 41 symbols and several horizons. Fourteen times
                    forty-one is 574 pattern-symbol combinations before horizons, and each one is
                    a test. At a 5% threshold, roughly 29 of them would look significant by
                    accident. A finding needs to survive that correction before it is a finding.

                    Overfitting is the same problem in time rather than in count. A threshold
                    chosen because it maximised a metric over one window is a description of that
                    window. Every parameter in a pattern detector — the zigzag depth, the
                    confirmation requirement, the penalty weights — is a place where a value
                    could have been tuned to this fixture and would not transfer. The app's
                    scoring weights are fixed in one table (geometry 40, structure 25,
                    confirmation 20, context 15, summing to 100) and pinned by three
                    hand-computed fixtures at 74, 39 and 30, so a change to them fails a test
                    rather than silently re-fitting the engine.

                    The most instructive evidence that this discipline matters is already in this
                    repository: the same research pass that measured an 83% figure also measured
                    a 10% base rate, and without the control the 83% would have been reported as
                    a finding. One table, one comparison, and the honest version was the smaller
                    number.

                    Practical defences: split the data in time and develop on one part, test on
                    the other; report every test that was run rather than only the surviving one;
                    prefer a result whose mechanism you can explain over a larger result whose
                    mechanism you cannot.
                """.trimIndent(),
                doesNotEstablish = "It does not establish that any pattern in this app has a real " +
                    "edge. It establishes only that the number of tests performed is large " +
                    "enough that uncorrected significance is close to meaningless.",
            ),

            Lesson(
                id = "expert.backtesting.reading-n",
                title = "How to read n=310",
                body = """
                    A sample count is a statement about how the data was grouped, and grouping is
                    a choice. In this app the same tracked outcomes can be pooled three ways, and
                    the three answers are all correct:

                    Per symbol **and** pattern gives 20-21 cases per cell. On 18 months of data
                    for one stock that is below the 30-sample floor, so **all 45 cells are
                    withheld** and the screen shows "(n of 30)". Nothing is displayed, and the
                    reason is visible.

                    Per symbol gives 62 — above the floor, so one stock's pattern history can be
                    shown.

                    Per pattern, pooled across symbols, gives 310 — well above the floor, and
                    this is the number most likely to be quoted casually.

                    The same outcomes, three different n. So "n=310" means: this pattern, across
                    many stocks, over this tracked window. It does **not** mean this pattern works
                    on the stock you are looking at. A per-pattern rate of 310 pooled samples
                    says nothing about any single member of that pool, and pooling also averages
                    away the very heterogeneity that matters — two stocks can share a pattern and
                    share nothing else.

                    The honest reading habit is to ask, of any rate: over what, across what, and
                    during what. Over which pattern. Across which symbols. During which window.
                    A rate that cannot answer all three is a number without a claim attached.

                    Thirty is a floor, not a threshold of validity. Above thirty, a percentage is
                    still an estimate with an interval around it — which the statistical-honesty
                    module shows how to compute.
                """.trimIndent(),
                doesNotEstablish = "It does not establish that 30 samples are sufficient for " +
                    "reliable inference. Thirty is the app's disclosure floor, chosen because " +
                    "below it a single outcome moves the percentage by more than three points.",
            ),
        ),
    ),

    // =====================================================================================
    // 5. Forensic red flags
    // =====================================================================================
    Module(
        id = "expert.forensics",
        level = Level.EXPERT,
        title = "Forensic red flags",
        summary = "Six accounting patterns worth checking by hand, the flag each one maps to in " +
            "this app, and — most importantly — where to verify each in exchange filings.",
        lessons = listOf(

            Lesson(
                id = "expert.forensics.receivables-and-inventory",
                title = "Receivables outrunning revenue",
                relatedRatio = "ROCE",
                relatedFlag = "RECEIVABLES_GROWING_FASTER_THAN_REVENUE",
                body = """
                    Revenue is recognised when it is earned, cash arrives when it is collected,
                    and the gap between the two is the trade receivable. When receivables grow
                    faster than revenue, the collection period is lengthening: the business is
                    reporting more sales without collecting the cash faster.

                    What it can mean, in rough order of how often it means it: genuine and benign
                    growth (a new geography or a new distribution channel legitimately stretches
                    terms), a deliberate channel stuffing at period end, a change in revenue
                    recognition timing, or a receivables balance that has stopped being real.
                    The number alone separates none of these — which is why the flag is named
                    after the condition observed, and never after a verdict.

                    What this app can and cannot see, precisely:

                    The ₹-based check needs a receivables line, and our source does not publish
                    one. The rule therefore evaluates the **same idea** in days sales
                    outstanding, which the ratios card does publish, and both the evidence and the
                    threshold string say which form was used. The flag id keeps the plan's wording
                    because that is what the check is about; the substitution is documented at
                    the rule rather than hidden inside the id.

                    The neighbouring check is inventory days rising by at least ten days year on
                    year — unsold stock accumulating. The app's ratio card does publish an
                    inventory-days series (RELIANCE 88 in the fixture), so this one is measured
                    rather than approximated. RELIANCE 88 days is a long time for stock to sit
                    in a business with low inventory intensity, which makes it exactly the kind
                    of number a reader should understand before reacting to it.

                    Where to verify, since the app cannot fetch it: the annual report's notes to
                    the accounts — trade receivables ageing, the significant accounting policies
                    note on revenue recognition, and the changes in trade receivables
                    reconciliation in the cash flow statement. The last is the strongest of the
                    three because it is the one that must tie to the balance sheet.
                """.trimIndent(),
                doesNotEstablish = "It does not establish that a company is reporting falsely, or " +
                    "that lengthening collection is abnormal in its sector. It names the " +
                    "condition that was measured and where the primary source can be read.",
            ),

            Lesson(
                id = "expert.forensics.earnings-quality",
                title = "Other income, and cash against reported profit",
                relatedRatio = "FCF_VS_PAT",
                relatedFlag = "OTHER_INCOME_AS_PCT_OF_PAT",
                body = """
                    Two checks on the same question: how much of the reported profit is earned
                    rather than incidental?

                    Other income as a share of profit after tax, above thirty percent, is the
                    first. Other income is interest, asset sales, one-off receipts, exchange
                    gains. It arrives below the operating line and flows through to PAT, so a
                    company whose profits are mostly other income is being carried by items that
                    may not recur. It is not automatically a problem — for a treasury-heavy
                    company, interest income *is* the business — which is why the rule reports the
                    ratio and leaves the interpretation to a reader who knows the sector.

                    Cash from operations below zero while PAT is positive, for two or more
                    consecutive fiscal years, is the second and it is sharper. Profit is an
                    accounting estimate; operating cash is money that moved. The two can diverge
                    for legitimate reasons in any single year — a large inventory build, a
                    long-credit expansion — but a multi-year divergence while profits are
                    reported is the single most useful accounting check there is.

                    Free cash flow against PAT is the same family of idea expressed as a ratio,
                    and it ranks fourth in the app's ratio priority order for exactly that reason:
                    "#1 earnings-quality tell". Where it is available it is more informative than
                    PAT alone, because it deducts the capital needed to run the business.

                    Where to verify: the cash flow statement, which must reconcile to the
                    balance sheet, and the notes for non-operating income broken out by head. For
                    a company with a subsidiary or a joint venture, also the related-party note —
                    because a large "other income" line is often where a transaction with an
                    affiliate would appear.
                """.trimIndent(),
                doesNotEstablish = "It does not establish that earnings are fabricated. A " +
                    "multi-year gap between operating cash and PAT warrants reading the filings, " +
                    "not a conclusion.",
            ),

            Lesson(
                id = "expert.forensics.accruals-and-related-party",
                title = "Capitalised interest, related-party deals, auditor changes",
                body = """
                    Three items that are always in the annual report and never on a data page.

                    **Capitalised interest.** Borrowing cost that builds an asset instead of
                    expensing it moves profit upward this year and the expense into future years.
                    It is permitted under the accounting standard when the asset takes a long time
                    to bring future benefit — a power plant, a refinery, a software build — and
                    it becomes a way to flatter earnings when the criteria are stretched. The
                    tell is in the borrowing-cost note: the amount capitalised in the period,
                    against the total finance cost. The app cannot see it; there is no line for
                    it in any of its sources.

                    **Related-party transactions.** Sales to, purchases from, loans to and
                    guarantees for entities connected to the promoter or to a group company.
                    Completely routine at scale, and also the most direct route by which reported
                    revenue could be recognised without an outside counterparty. The
                    related-party note discloses transaction-wise value, outstanding balances at
                    year end, and any provisions or write-offs. The balance matters more than
                    the flow: a large year-end balance is a claim on the group's cash.

                    **Auditor changes.** A change of auditor, especially a change shortly before
                    a qualified opinion or after a long tenure, is a disclosure event in its own
                    right. It appears in the corporate filings — not in a fundamentals page, and
                    not in anything this app reads.

                    A fourth worth knowing, since the app models it and cannot source it:
                    **promoter pledging**. Promoter shares pledged to a lender can be invoked,
                    and a rising pledge ratio is usually disclosed on the exchange's shareholding
                    pattern and pledge pages. The rule is implemented and unit-tested, and it
                    abstains on every real symbol while stating why — the honest shape for a
                    permanent gap that reports itself, rather than a blank that reads as a value.
                    Goodwill above twenty percent of total assets is the other unsourceable check,
                    for the same reason.
                """.trimIndent(),
                doesNotEstablish = "It does not establish that any of these is present at any " +
                    "company. Each is a line in a filing the reader must open; the app can " +
                    "neither fetch nor infer them.",
                relatedFlag = "PROMOTER_PLEDGING",
            ),

            Lesson(
                id = "expert.forensics.where-to-verify",
                title = "Where to verify, and why the app says so out loud",
                body = """
                    Every check in this module that the app cannot compute has one thing in
                    common: it lives in a document, not in a field. So the useful skill is
                    knowing which document, and this app's role is teaching the source rather
                    than pretending to be one.

                    Annual report — the notes to the accounts carry trade receivables ageing,
                    revenue recognition policy, capitalised interest, related-party transactions,
                    and contingent liabilities. This is the primary document and it is free.
                    Audited results — the quarterly and annual filings carry the restated figures
                    and the auditor's report, including any modification.
                    Exchange corporate filings — the statutory announcements carry board changes,
                    auditor changes, pledges and material events. These are the pages
                    docs/LIVE-PROBE.md §4 found returning 404 from GitHub's network; they are
                    reachable from an Indian network, and a probe failure is a fact about the
                    probe, not a verdict on the source.
                    Shareholding pattern — promoter holding and the pledge disclosure, filed
                    quarterly.
                    Bank-specific sources — net interest margin, gross and net NPA, CASA ratio and
                    provision coverage ratio are the first questions for a bank, and they are not
                    in the app. The probe grepped screener.in for those labels and found them;
                    the values sit behind a login paywall in 60 empty highlight cells, so they
                    stay NOT_SOURCED. That is the correct outcome: the labels render, the values
                    do not exist for the reader, and recording them anyway would be fabrication.
                    The honest alternatives are the bank's own annual report and the RBI's
                    Financial Stability Report for the system-wide picture.

                    The transferable lesson from that probe is larger than banking: a label match
                    is not a value, and any number that enters this app must be pinned by a test
                    that asserts a parsed figure rather than a string that looked familiar.
                """.trimIndent(),
                doesNotEstablish = "It does not establish that a reader will find a problem, or " +
                    "that these documents are complete. It identifies where the primary sources " +
                    "are, because the app's sources do not contain them.",
                relatedFlag = "PROMOTER_PLEDGING",
            ),
        ),
    ),

    // =====================================================================================
    // 6. Valuation frameworks
    // =====================================================================================
    Module(
        id = "expert.valuation",
        level = Level.EXPERT,
        title = "Valuation frameworks",
        summary = "When a multiple lies, what P/B and ROE actually pair to say, why EV/EBITDA " +
            "is NOT_SOURCED here, and how to think in reverse without forecasting.",
        lessons = listOf(

            Lesson(
                id = "expert.valuation.pe-at-peak-earnings",
                title = "P/E at peak earnings: the multiple and the denominator move together",
                relatedRatio = "PE_VS_SECTOR_MEDIAN",
                body = """
                    Price to earnings is price divided by earnings. When earnings are at a cyclical
                    peak, the denominator is at its largest and the multiple looks smallest — and
                    a low multiple attached to peak earnings is not cheapness. It is the multiple
                    telling you the denominator is not repeatable.

                    Steel, cement, shipping, airlines and commodity chemicals are the standard
                    examples. A steel producer at cycle peak can print a P/E of four. Buy it "for
                    value" and the P/E does not normalise to eight; the *earnings* normalise to
                    something a fraction of the peak, and the multiple re-rates upward at the
                    same time as the denominator collapses. Both moves hurt, and the ratio looked
                    like the safe one.

                    Two practical consequences. First, the comparison that holds up across a
                    cycle is not against the stock's own history but against the sector on the
                    same date — which is why the app computes P/E versus sector median and ranks
                    it eighth in its priority order, with the note that "a low P/E often signals
                    a problem". Second, the second-order check is whether the multiple is rising
                    while trailing earnings are falling; that combination is a flag
                    (PE_RISING_WHILE_EARNINGS_FALL) and it is the signature of a multiple
                    expanding into a deteriorating denominator.

                    The paired condition in the app is the other direction of the same idea: a
                    P/E below the sector band while ROCE or ROE is declining. Cheap and
                    deteriorating is a different question from cheap, and the flag names both
                    halves because a reader needs both to act on either.
                """.trimIndent(),
                doesNotEstablish = "It does not establish that any company is mispriced, or that a " +
                    "sector-relative multiple is a fair value. It describes why one ratio " +
                    "misleads at particular points in a cycle.",
                relatedFlag = "LOW_PE_WITH_FALLING_RETURNS",
            ),

            Lesson(
                id = "expert.valuation.pb-and-roe",
                title = "P/B and ROE are one number, not two",
                relatedRatio = "PB",
                body = """
                    Price to book divided by return on equity is close to constant across most
                    large listed companies, and the constant is called the market's implied
                    cost of equity. Algebraically, P/B = (P/E) x (E/B) and E/B is ROE, so
                    dividing one by the other leaves 1/ROE with the growth term included. The
                    practical reading: **two companies trading at the same price to book are
                    being asked to deliver the same return on equity.** A stock at 3x book
                    priced like a 3x-book stock must earn roughly what its peer earns, or the
                    market is already pricing something else — usually growth.

                    The reason price to book only works on asset-heavy businesses is visible in
                    the same algebra. Book value is the accounting value of the asset base, so
                    for a bank, a utility or an airline, book value is a reasonable proxy for
                    earning power. For a software company or a brand, the valuable asset is not on
                    the balance sheet at all, and two firms with identical books can be worth
                    wildly different amounts. This is why the app's ratio order attaches the
                    note "pairs with ROE; asset-heavy only".

                    The other half of the pair is why the app ranks ROCE first and ROE sixth, with
                    the rationale "duplicated leverage, deliberately ranked below ROCE". ROE can
                    be manufactured with debt: the same operating business, geared up, reports a
                    higher return on the (smaller) equity. Return on capital employed is measured
                    before that choice, so the two ranking positions encode a judgment the reader
                    would otherwise have to make every time.

                    Read them as one statement: what is the capital base, what does it earn, and
                    what is the market paying per unit of that capital?
                """.trimIndent(),
                doesNotEstablish = "It does not establish that a P/B-ROE relationship holds " +
                    "tightly for any particular company, or that book value is meaningful for " +
                    "an asset-light business. It describes why the two are read together.",
            ),

            Lesson(
                id = "expert.valuation.ev-ebitda-not-sourced",
                title = "EV/EBITDA, and why this app abstains on it",
                relatedRatio = "EV_TO_EBITDA",
                body = """
                    Enterprise value is the market value of the whole capital structure — equity
                    plus net debt — and EBITDA is earnings before interest, tax, depreciation and
                    amortisation. The pairing removes two distortions at once: capital structure,
                    because EV uses the enterprise rather than the equity, and depreciation
                    policy, because EBITDA is measured before it. A heavily geared company and a
                    debt-free one with identical operating earnings get the same multiple. That is
                    the whole argument for it, and it is why the ratio ranks tenth in the app's
                    order with the rationale "capital-structure neutral".

                    It also has a known weakness: it adds back depreciation while ignoring the
                    capital expenditure required to maintain the asset base. For an
                    infrastructure or telecom business, the two differ enormously, which is why
                    EV/EBITDA is often paired with a capital-expenditure ratio before anyone
                    trusts it.

                    **Why the app ships it as NOT_SOURCED.** There is no cash figure and no debt
                    figure in the source this app reads, so there is no net debt, so there is no
                    enterprise value — and without it the ratio cannot be computed at all. The
                    honest response is to declare the gap, name the missing input, and abstain.
                    The same fate, for the same reason, applies to gross margin: no cost-of-goods
                    line exists anywhere in the source.

                    Two ratios were *looked for* and still not promoted, which is worth repeating
                    because the failure mode is instructive. For banks, net interest margin,
                    gross and net NPA, CASA ratio and provision coverage ratio are the right
                    questions — and a live probe found the labels on screener.in. The values sat
                    behind a login paywall in 60 empty cells. Promotion was rejected. A ratio
                    recorded without a value is exactly the fabrication this app's honesty
                    contract exists to prevent, and the gap stays open by evidence rather than
                    left pending.

                    Where the numbers are obtainable instead: the annual report's balance sheet
                    for cash and borrowings, and the cash flow statement for depreciation and
                    amortisation and capital expenditure. All three lines are needed, from the
                    same period, or the multiple mixes periods and means nothing.
                """.trimIndent(),
                doesNotEstablish = "It does not establish a value for EV/EBITDA at any company. " +
                    "The ratio cannot be computed from this app's sources, and any figure " +
                    "presented as one would be invented.",
            ),

            Lesson(
                id = "expert.valuation.reverse-dcf",
                title = "Reverse thinking: what this price already assumes",
                body = """
                    A discounted cash flow starts from cash flows you estimate and works forward
                    to a present value. The reverse exercise runs the arithmetic backwards: take
                    today's price as given, fix the discount rate, and ask what growth path the
                    remaining cash flows must follow to justify it.

                    The useful output is not a number — it is a **required assumption made
                    visible**. When a price only works at a growth rate the business has never
                    approached, the price is not paying for the business as it has operated; it
                    is paying for a version of the business that does not exist yet. When the
                    required assumption is modest and the business has a record of clearing it,
                    the price is demanding less of the future than the past has delivered. Both
                    readings are about the *price*, arrived at from assumptions you state — which
                    is the honest direction of travel, because the inputs are visible and
                    arguable rather than buried inside an output.

                    How to run it as a mental model, without a model:

                    Fix the capital base and the return on it. The app's first-ranked ratio,
                    ROCE, is the natural anchor, because it is measured before financing choices
                    and is the rate at which incremental capital is actually reinvested.
                    Ask how much of that return the business must retain to grow at the rate the
                    price implies, and what that retention leaves for distributions. A business
                    retaining most of its profit to fund growth cannot simultaneously be paying a
                    large dividend; the two claims about the same rupee cannot both be in the
                    price.
                    Stress the discount rate, because a longer horizon amplifies small
                    differences in it. Every conclusion in this family of analysis is a function
                    of the rate, so a conclusion that survives only one rate is not a conclusion.

                    The discipline this app applies: it prints what was measured and names what
                    it cannot see. A valuation exercise done on hidden inputs is an assertion,
                    and the reverse form is only useful when every assumption is on the page.
                """.trimIndent(),
                doesNotEstablish = "It does not establish a fair value, a projected price, or a " +
                    "recommendation. The output of this exercise is a stated set of assumptions, " +
                    "not an estimate of what will happen.",
            ),
        ),
    ),

    // =====================================================================================
    // 7. Statistical honesty
    // =====================================================================================
    Module(
        id = "expert.statistics",
        level = Level.EXPERT,
        title = "Statistical honesty",
        summary = "Intervals around a hit rate, shrinkage for small samples, correcting for the " +
            "number of tests, and the difference between a result that is real and one that " +
            "matters.",
        lessons = listOf(

            Lesson(
                id = "expert.statistics.intervals",
                title = "Every percentage needs an interval",
                body = """
                    A percentage without an interval is a number pretending to be more precise
                    than its sample allows. The app's own simulated record is 153 hits out of 930
                    resolved outcomes — 16.45%. The Wilson score interval at 95% for 153 of 930 is
                    approximately **14.2% to 19.0%**, computed with the formula below. The app
                    prints 16.45% because that is what it counts; the interval is what the count
                    supports.

                    Wilson, rather than the more familiar normal approximation, because the normal
                    approximation misbehaves exactly where this app operates — small n and
                    proportions far from one half, which is the regime of a low hit rate:

                      centre = ( p + z²/2n ) / ( 1 + z²/n )
                      half   =  z / (1 + z²/n)  x  sqrt( p(1-p)/n + z²/4n² )

                    with p = hits/n and z = 1.96. The interval's width shrinks as the square root
                    of n, which is the single most useful fact in this lesson: halving the
                    interval's width costs four times as many samples. That is the real price of
                    confidence, and it is why the app's per-symbol view usually withholds its
                    rate while the per-pattern view can show one.

                    Read an interval as a question rather than a verdict. An interval of 14.2% to
                    19.0% excludes a coin flip by a wide margin — that is a meaningful statement.
                    An interval of 40% to 62% excludes nothing, and any point estimate inside it
                    is decoration. Most numbers worth arguing about fall in the second category,
                    which is usually the point.

                    The app does not print intervals on its own screens today. It prints the
                    count instead, which is the weaker but honest form of the same disclosure:
                    n tells you how much to believe, and it never lies.
                """.trimIndent(),
                doesNotEstablish = "It does not establish that the interval contains a future rate. " +
                    "It describes the precision supported by this sample under a stated " +
                    "confidence level, nothing more.",
            ),

            Lesson(
                id = "expert.statistics.shrinkage",
                title = "Small samples should not be believed as much",
                body = """
                    A 100% hit rate from 4 samples and a 100% hit rate from 400 samples are the
                    same string. The first is a sample of four, and the second is a fact about a
                    regime. Reporting them identically is the error.

                    Two honest corrections exist. The first is to refuse: print nothing below 30
                    samples and show "(n of 30)" instead. This is what the app does, and it is
                    the right default because it requires no modelling — the absence of a number
                    is itself informative.

                    The second is shrinkage. Take a prior, weight it by how little data supports
                    the observation, and average. In form:

                      shrunken rate = ( hits + a·prior ) / ( n + a )

                    with `a` representing how many observations of prior belief are worth. The
                    choice of `a` is a judgement, and the honest way to present one is to show
                    the unshrunk and shrunken values together so the reader can see how much of
                    the difference was data and how much was prior.

                    What the prior should be is not a detail. For a directional claim, this app's
                    own measurements supply the natural prior: next-day direction over the
                    fixture is 48.93%, and the no-pattern five-session control is 10%. Using a
                    prior of 50% for a next-day direction claim is not a neutral placeholder —
                    it is the measured base rate, so shrinking toward it is what "no information"
                    means in this market.

                    The failure mode of shrinkage is over-shrinking. Pull every estimate hard
                    toward the prior and a genuinely strong finding disappears into the average;
                    the answer is to report the interval as well, so the reader sees how much of
                    the pull the data justified.
                """.trimIndent(),
                doesNotEstablish = "It does not establish a corrected rate for any cell in this app. " +
                    "Shrinkage produces a reported number with a stated prior, which is a " +
                    "modelling choice rather than a measurement.",
            ),

            Lesson(
                id = "expert.statistics.multiple-testing",
                title = "Correcting for how many things you tried",
                body = """
                    A correction exists for one reason: the more hypotheses tested against the
                    same data, the more some will appear significant by accident. The
                    Bonferroni correction handles this crudely and correctly by dividing the
                    significance threshold by the number of tests — 14 patterns times 41 symbols
                    is 574 tests, so an uncorrected 5% becomes 0.009%, and most findings that
                    look significant stop being so. The Benjamini-Hochberg procedure is the
                    modern, less conservative alternative: it controls the expected proportion of
                    false discoveries rather than the chance of any one, which matters when tests
                    are correlated — and these are heavily correlated, because the same formation
                    feeds several detectors.

                    Applied to this repository's own research, the correction is the base-rate
                    control. The 83% figure was measured alongside a 10% no-pattern control on
                    the same sample. Rather than adjusting a threshold, the control **changes
                    what the number means**: it identifies how much of the 83% was the geometry
                    of a nearby level rather than anything about patterns. The remaining gap
                    over random — roughly 39.5% against 31.7% at a 1% stop on tight targets — is
                    the part the geometry does not explain, and it is much the smaller claim.

                    The behaviour this teaches is transferable beyond statistics. Report the
                    control next to the finding, always, even when it makes the finding worse.
                    Report every test that was run, not only those that survived. And prefer a
                    smaller result with a known cause over a larger one whose cause is unknown.
                """.trimIndent(),
                doesNotEstablish = "It does not establish that any pattern survives correction. It " +
                    "establishes only that an uncorrected percentage is meaningless without " +
                    "knowing how many tests produced it.",
            ),

            Lesson(
                id = "expert.statistics.significance-versus-importance",
                title = "Significant is not the same as worth doing",
                body = """
                    Two different questions, routinely confused. Statistical significance asks
                    whether a measured difference could be noise. Economic significance asks
                    whether the difference is larger than the costs of acting on it.

                    The clearest illustration in this repository is the stop sweep. A 2% stop on
                    tight targets produced a 56.6% win rate against 31.7% for a random
                    direction — a wide gap, comfortably significant on 949 candidates. The
                    expectancy behind it: **0.26% per trade**. So the statistically real finding
                    is economically negligible, because the per-trade edge is smaller than the
                    round-trip cost stack.

                    Statistical significance grows with n; economic significance depends on
                    magnitude and does not grow at all. This is why "significant at p < 0.01" is
                    not a reason to act, and why a large sample can make a tiny difference look
                    compelling. A thousand observations of a 0.1% effect is a thousand
                    observations of 0.1%.

                    The honest reporting form pairs both, every time: the effect with its
                    interval, and the effect's size against the cost of participating. Where the
                    second comparison fails, the finding is a curiosity — a real property of how
                    prices move, worth understanding and not worth transacting on.

                    That is also why this app's line is drawn where it is. It will compute,
                    score, count and display; it will not take the second comparison as settled
                    on a reader's behalf, because the cost side of that comparison depends on
                    an account this app has never seen and was never given.
                """.trimIndent(),
                doesNotEstablish = "It does not establish that any measured edge is unprofitable. " +
                    "The comparison against costs depends on the reader's own charges, which " +
                    "this app does not have and does not estimate.",
            ),
        ),
    ),

    // =====================================================================================
    // 8. Indian market microstructure
    // =====================================================================================
    Module(
        id = "expert.microstructure",
        level = Level.EXPERT,
        title = "Indian market microstructure",
        summary = "Impact cost and liquidity, how F&O and the cash segment relate, where open " +
            "interest actually comes from, and what GSM/ASM and circuit limits mean.",
        lessons = listOf(

            Lesson(
                id = "expert.microstructure.impact-and-adv",
                title = "Liquidity, ADV and why size is a variable",
                body = """
                    Impact cost is the price movement caused by your own order. It is not a
                    constant, and it is the reason a position size that looks small as a
                    percentage of capital can be large as a percentage of what the stock trades.

                    The workhorse measurement is average daily volume — ADV — measured over a
                    window rather than one day, because daily volume in Indian equities is
                    heavily event-driven. A screen that admits a name on the strength of one
                    high-volume day admits a name whose liquidity disappears the following week.
                    The app already computes the machinery for this: a 20-session volume average
                    exists for every symbol, and volume spikes are defined as 2.5x that average
                    rather than against a fixed number.

                    The rule of thumb to interrogate is "my order should be a small share of
                    ADV". It is a rule of thumb precisely because the denominator is a
                    twenty-day mean that says nothing about tomorrow's opening print, and because
                    exiting is harder than entering — the participants you need to buy from are
                    the ones who are not selling today.

                    This app reads volume and delivery volume from the bhavcopy, so the raw
                    inputs are present. What is absent is your order size, which is why it can
                    compute the liquidity measure and not the impact. The same honest split
                    applies throughout this module: the denominators are available, the numerators
                    belong to the reader.
                """.trimIndent(),
                doesNotEstablish = "It does not establish that any position size is liquid enough. " +
                    "Impact cost depends on an order size and a time of day that this app does " +
                    "not have.",
                relatedFlag = "DELIVERY_COLLAPSE",
            ),

            Lesson(
                id = "expert.microstructure.fno-and-basis",
                title = "F&O, the cash segment, and basis",
                body = """
                    The derivatives segment does not sit beside the cash market; it sits on top
                    of it. A futures contract is cash-settled or physically settled against the
                    underlying, so price discovery happens across both segments and the cash
                    price is the anchor the contract converges to.

                    Basis is the spread between futures and spot, and by the cost-of-carry
                    relationship it should equal roughly (the prevailing lending rate minus the
                    dividend yield)
                    times the time to expiry. So the spread is not a free parameter — it mostly
                    reflects funding and expected dividends, and it moves when those move. A
                    futures price far from that relationship is telling you something about
                    positioning or about a supply event in the underlying, which is why futures
                    leading the cash price is treated as information rather than as noise.

                    Where it gets complicated for a cash-only screen: expiry week changes the
                    arithmetic. Basis compresses, intraday ranges widen, and volume migrates
                    between segments, so a stock's cash-market behaviour in the week of expiry is
                    not a clean read of demand for the company.

                    What this app has: cash OHLCV and delivery volume for ~3,700 instruments
                    daily. What it has not: futures prices, open interest, or any options chain.
                    So every statement about positioning, rollover or hedging pressure in this
                    lesson is context for interpreting what the app shows — not a claim about
                    what the app measures. Open interest, in particular, is a separate published
                    series and this app does not fetch it; the exchange's own derivatives
                    reports are where it is verifiable.
                """.trimIndent(),
                doesNotEstablish = "It does not establish anything about any stock's positioning. " +
                    "This app holds no derivatives data, so basis and open interest are context " +
                    "the reader supplies, not measurements the app makes.",
            ),

            Lesson(
                id = "expert.microstructure.circuits-gsm-asm",
                title = "Circuit limits, GSM and ASM",
                body = """
                    Three mechanisms that change what an observed price means, all of them
                    exchange-side and none of them visible in a bhavcopy row.

                    **Circuit limits** halt trading at a price band — the index circuit filters
                    operate at index level, individual stock circuits at the stock, with band
                    widths set by the exchange by market capitalisation band and by the
                    instrument's recent volatility. The consequence for anyone reading an
                    end-of-day file: a session that halted does not have a continuous price path,
                    and indicators computed across the gap describe two markets rather than one.

                    **GSM (Graded Surveillance Mechanism)** applies additional surveillance to
                    stocks with a price outside the normal band, plus quarterly financial
                    results: higher margin funding, a weekly surveillance sheet, and more
                    frequent exchange interaction.
                    **ASM (Additional Surveillance Measure)** applies where there are abnormal
                    price moves, cash and carry out, frequent closures near circuit limits, or
                    other surveillance parameters. The observable signals to watch for in the
                    price series itself are repeated limit-up or limit-down closes and repeated
                    near-circuit closures — an ASM candidate often looks like a stock that keeps
                    closing at its band.

                    Where to verify: the exchange's published surveillance lists. This app cannot
                    fetch them — docs/LIVE-PROBE.md §4 records announcements, bulk deals, block
                    deals and the ASM/GSM lists all returning 404 from GitHub's network, so the
                    gap stays Class-B: the app flags what it detects from price behaviour and
                    tells the reader where to confirm, rather than asserting an event it never
                    saw.

                    The transferable point: some market facts are only in the regulator's
                    published lists, and an app that cannot reach a list should say so rather
                    than infer membership from a chart.
                """.trimIndent(),
                doesNotEstablish = "It does not establish that any stock is under surveillance. " +
                    "The app fetches no surveillance list; it can only point at the exchange " +
                    "publications where membership is published.",
            ),

            Lesson(
                id = "expert.microstructure.delivery-and-volume",
                title = "Delivery volume: what the bhavcopy actually tells you",
                relatedFlag = "DISTRIBUTION_DAY",
                body = """
                    The bhavcopy publishes traded quantity and delivery quantity per symbol. The
                    ratio is delivered quantity over traded quantity, and it is the one
                    participation measure available for the entire market from a single daily file.

                    The reading has two halves. High delivery means ownership is actually changing
                    hands — larger holders are transacting rather than intraday participants
                    rotating. Low delivery means most of the day's volume was positions opened
                    and closed the same day. Neither is inherently good or bad: what matters is
                    the direction of change and the level relative to that stock's own history,
                    which is why the app compares a 20-session delivery average rather than
                    absolute percentages.

                    That is exactly what the DELIVERY_COLLAPSE rule encodes — delivery falling
                    below 40% of its 20-session average for two consecutive bars, evaluated only
                    when at least 75% of the bars needed for the average exist. The coverage
                    requirement is the part worth noticing: a rule that quietly averages over a
                    half-empty window produces numbers that mean nothing, so the rule abstains
                    instead. The same principle appears in the distribution-day rule, where a
                    down day is only notable against a volume 4x its average.

                    The threshold question is unavoidable and this app resolves it by disclosure
                    rather than by assertion: 40% is a chosen number, the rule is stated as a
                    condition rather than a verdict, and the evidence string carries the measured
                    values. A reader who disagrees with the threshold can still read the numbers
                    and apply their own.

                    And the honest limit: delivery volume does not identify who transacted. It
                    cannot distinguish a promoter sale from an institution's accumulation, and no
                    inference about intent should be read into it.
                """.trimIndent(),
                doesNotEstablish = "It does not establish that low delivery precedes a decline, or " +
                    "that it reveals who traded. Delivery volume measures participation, not " +
                    "intent.",
            ),
        ),
    ),

    // =====================================================================================
    // 9. Portfolio construction
    // =====================================================================================
    Module(
        id = "expert.portfolio",
        level = Level.EXPERT,
        title = "Portfolio construction",
        summary = "What correlation can and cannot be measured over eighteen months of Indian " +
            "data, how to set a sector cap and a drawdown budget, and why rebalancing has a " +
            "tax bill.",
        lessons = listOf(

            Lesson(
                id = "expert.portfolio.correlation-short-histories",
                title = "Correlation measured over eighteen months",
                body = """
                    The honest caveat about every portfolio statistic in this module: the price
                    history available here is 372 real sessions of 41 large caps — roughly
                    eighteen months of trading days, in one regime, in one market.

                    Eighteen months contains one volatility cycle, at most, and usually less than
                    one. Correlations measured across that window are dominated by the common
                    factor of the period rather than by any durable relationship between two
                    businesses. Two stocks that look weakly correlated here may be strongly
                    correlated in a credit stress, because the factor that correlates them has
                    not appeared yet in the sample. That is not a small caveat; it is the reason
                    Indian-market diversification studies tend to use decade-long windows.

                    What eighteen months *is* good for: catching pairs that are structurally
                    related (same business, same promoter group, same regulator, same input
                    cost) regardless of regime. Structural correlation shows up early. Cyclical
                    correlation shows up late, which is exactly the kind that matters.

                    The practical consequence for a reader: treat a low measured correlation over
                    a short window as weak evidence, and weight structural arguments — shared
                    business model, shared regulator, shared funding source — above the measured
                    number. And prefer a construction whose risk does not depend on the
                    correlations staying where they are, since a cap-based approach does not.

                    This module is education about how portfolios are reasoned about. It is not a
                    construction recipe, and it expresses no view on any allocation.
                """.trimIndent(),
                doesNotEstablish = "It does not establish a correlation structure for the Indian " +
                    "market. The available window is one regime long and is too short for " +
                    "durability claims.",
            ),

            Lesson(
                id = "expert.portfolio.sector-caps",
                title = "Sector caps as a rule rather than an opinion",
                body = """
                    A sector cap is a rule that limits how much of a portfolio may sit in one
                    industry. Its purpose is not to raise returns; it is to make a specific
                    failure mode impossible by construction rather than by discipline — the
                    portfolio becoming one bet on one regulator or one input cost.

                    The reason it must be a rule rather than a judgement is behavioural. A cap
                    decided afresh each quarter is re-decided precisely when a sector is doing
                    well, because that is when the question feels urgent. A cap written down in
                    advance is enforced by arithmetic.

                    What to cap on is a real choice, and each basis fails differently. Cap by
                    sector label is transparent but coarse: in India one sector label can hold
                    businesses with unrelated input costs. Cap by input cost is more economically
                    honest and less legible. Cap by end-market demand is a reasonable middle and
                    requires the reader to classify each holding themselves — the app does not
                    classify, so this is manual work.

                    The measurement that makes the cap checkable is simply what share of the
                    portfolio each holding represents, and the app's sector resolver already
                    assigns one of its Indian sectors to each of the names it knows. So the cap
                    can be evaluated against real classifications rather than against adjectives.

                    Two things this lesson deliberately does not do: it does not suggest a cap
                    level, because the right level depends on a portfolio this app has never
                    seen; and it does not rank sectors. Both are reader decisions.
                """.trimIndent(),
                doesNotEstablish = "It does not establish an appropriate cap level or a sector " +
                    "allocation. It describes why caps are written as arithmetic rules rather " +
                    "than re-decided each period.",
                relatedRatio = "ROCE",
            ),

            Lesson(
                id = "expert.portfolio.drawdown-budget",
                title = "Drawdown budgeting, and what the app can measure",
                body = """
                    A drawdown budget is a decision, taken in advance, about how much loss a
                    portfolio is allowed to suffer before the plan changes. Its value is
                    mechanical: it removes the largest single decision from the worst possible
                    moment, when information is worst and a rule would be most useful.

                    Setting one honestly requires two separate numbers, and conflating them is
                    the common error:

                    A **tolerance** — how much decline the reader could sit through without
                    changing the plan. A **budget** — how much the portfolio is permitted to
                    decline before action is taken. These are different quantities, and choosing
                    the second by picking the first is how a budget ends up at a level the reader
                    would never actually tolerate.

                    What the app can contribute is the measurement side, from stored daily bars:
                    peak-to-trough decline of a symbol or of a tracked set of symbols, the
                    duration of that decline, and how many declines of a given size occurred in
                    the available window. That is descriptive and it is honest — it is what the
                    price series did, with n, no forecast attached. With 372 sessions the
                    distribution of large declines is thinly sampled, so treat it as a floor on
                    what is possible rather than as a complete census.

                    What the app cannot contribute is the number that matters most: how much
                    loss the reader can actually bear, which depends on income, obligations and
                    time horizon, none of which this app has ever seen.
                """.trimIndent(),
                doesNotEstablish = "It does not establish a maximum tolerable loss or an " +
                    "appropriate budget for any reader. It separates two numbers that are " +
                    "usually treated as one and describes what the stored bars can measure.",
            ),

            Lesson(
                id = "expert.portfolio.tax-aware-rebalancing",
                title = "Rebalancing has a tax bill",
                body = """
                    Rebalancing restores weights that have drifted, and in India it triggers
                    capital gains. The mechanics: equity held beyond twelve months is long-term,
                    within twelve months short-term, and gains in either category are taxed at
                    rates and with exemptions that the government revises. Every rule in this
                    paragraph is educational context and every one of them changes — so verify
                    the current position against the Income Tax Department or a tax professional
                    before acting on any of it.

                    The consequence for a rebalancing plan is that its frequency is a tax
                    decision, not just a discipline decision. Selling a drifted winner inside
                    twelve months converts a long-term gain into a short-term one; selling after
                    twelve months does not. That gives a natural cadence: let weights drift
                    within a band and act when a band is breached and the tax consequence is
                    acceptable, rather than selling on a fixed date regardless of when the
                    holding was bought.

                    The annual exemption is the lever that makes harvesting coherent. Gains
                    realised on long-term equity holdings up to the annual exemption limit can
                    be offset against realised long-term losses within that limit, so a
                    rebalancing sale that realises a loss can offset a gain elsewhere — turning a
                    tax liability into a smaller one. The specific limit is a published figure
                    and it is revised by the government; the app does not state a current
                    number, and will not, because a tax rate baked into an app carries no date
                    and ages invisibly.

                    This app holds no portfolio data, no tax lots and no cost basis. Everything
                    above is education about the mechanics a reader should understand before
                    choosing a rebalancing rule. It is not a tax strategy and not advice.
                """.trimIndent(),
                doesNotEstablish = "It does not establish a tax outcome, a current exemption " +
                    "limit, or an appropriate rebalancing frequency. Rates and limits change, " +
                    "and this app states none of them.",
            ),
        ),
    ),

    // =====================================================================================
    // 10. Build your own screener
    // =====================================================================================
    Module(
        id = "expert.screener",
        level = Level.EXPERT,
        title = "Build your own screener",
        summary = "Filtering the watchlist by the ratios the app already computes — and, for " +
            "each filter, what it selects for and what it selects against.",
        lessons = listOf(

            Lesson(
                id = "expert.screener.roce",
                title = "The ROCE filter, and the business it excludes",
                relatedRatio = "ROCE",
                body = """
                    ROCE ranks first in the app's ratio order for one stated reason: "best single
                    measure of business quality; hard to manipulate". It is operating profit over
                    capital employed, measured before financing and tax decisions, so it cannot
                    be improved by gearing, by choosing a tax treatment, or by moving the
                    denominator. Where a filter is available, this is the one to start from.

                    What it selects for: businesses that convert a unit of capital into a high
                    return, consistently. The consistency is the point — a single high year can
                    come from a commodity price; a high return across a cycle usually reflects
                    pricing power, a cost advantage, or a business that does not need much
                    capital.

                    What it selects against, and this is the half of every filter that a
                    threshold hides: capital-light businesses that genuinely do not need capital,
                    and asset-heavy ones early in a build-out phase. A business investing heavily
                    for future capacity reports a low ROCE while doing exactly what it should.
                    The filter is not measuring quality there; it is measuring the timing of
                    investment, and the two are indistinguishable in one number.

                    The other side of the number is equally load-bearing: ROCE is computed over
                    reported operating profit, so it inherits whatever is in that figure. This
                    is why the earnings-quality checks exist next to it — a high ROCE on profit
                    that never becomes cash is a different claim from a high ROCE on profit that
                    does.

                    Read it over several years, in its own sector. The app's ratio card shows
                    the series, and its sector resolver assigns the sector the comparison needs.
                """.trimIndent(),
                doesNotEstablish = "It does not establish that a high ROCE predicts returns, or " +
                    "that any particular ROCE level is a good business. It describes what the " +
                    "ratio measures and what a threshold silently excludes.",
            ),

            Lesson(
                id = "expert.screener.leverage",
                title = "Leverage and interest coverage, read as survival",
                relatedRatio = "DEBT_TO_EQUITY",
                body = """
                    Debt to equity ranks third in the app's ratio order with a one-word
                    rationale: "survival". Interest coverage shares that rank and the same card,
                    because the two answer the same question from different directions — how much
                    operating profit would have to disappear before obligations could not be met.

                    What they select for: a buffer. Businesses with low leverage can absorb a bad
                    year, a lost contract, or a rate rise without being forced into a decision
                    they did not choose. That asymmetry — being able to wait rather than react —
                    is the actual content of "survival".

                    What they select against: everything that needs capital. Capital-intensive
                    businesses, infrastructure, utilities, and finance itself, where leverage is
                    the product rather than a risk. Comparing a bank's D/E to an automaker's is
                    comparing two different businesses on a scale that only applies to one of
                    them. Sector context is not decoration on this ratio; it is the ratio's
                    precondition.

                    The app encodes the distinction rather than hiding it. The fundamentals flag
                    engine checks a debt-to-equity spike year on year **or above the sector's
                    band** — a relative test, not an absolute one — and separately flags interest
                    coverage below two, where EBIT is less than twice interest. That second
                    threshold is a survival line rather than a quality line, and the app reports
                    the condition with the numbers behind it rather than a verdict.

                    The pairing rule: read the two together. A low D/E with coverage near one is
                    a company whose buffer is thinner than the leverage ratio alone suggests,
                    because the denominator — operating profit — is the fragile part.
                """.trimIndent(),
                doesNotEstablish = "It does not establish that a leverage level is safe for a " +
                    "specific company. Ratios are relative to sector, cycle and accounting " +
                    "policy, and the app flags conditions rather than verdicts.",
                relatedFlag = "INTEREST_COVERAGE_BELOW_2",
            ),

            Lesson(
                id = "expert.screener.cash-versus-profit",
                title = "The cash filter: FCF against PAT",
                relatedRatio = "FCF_VS_PAT",
                body = """
                    Free cash flow against profit after tax is the fourth-ranked ratio and the
                    app annotates it "#1 earnings-quality tell". The ratio asks a single
                    question: for every rupee of reported profit, how much actual cash did the
                    business produce?

                    A value near or above one says reported profit is backed by cash. A value
                    well below one says the gap between the two is large, and the reader should
                    know why before proceeding — because the legitimate reasons (heavy
                    capital expenditure during expansion, a build in inventory ahead of a sales
                    push, receivables lengthening) are indistinguishable from the illegitimate
                    one (profit that is not being collected) without going to the filings.

                    This filter has an unusually good failure mode, which is worth noticing:
                    it selects *against* the companies whose accounting is most aggressive.
                    A screen on profitability ratios alone can be systematically improved by
                    adding a screen on cash conversion, because the two screens do not fail on
                    the same companies. That is the strongest argument for a cash filter, and it
                    is an argument about measurement rather than about returns.

                    The app's sharper version of the same check is the CFO_NEGATIVE_WHILE_PAT_POSITIVE
                    flag: operating cash flow below zero while PAT is positive, for two or more
                    consecutive fiscal years. The multi-year requirement is deliberate — a single
                    year can be explained away, and a repeated pattern cannot.

                    The pairing rule: never read this ratio without knowing what the capital was
                    spent on. Capital expenditure that builds capacity is an investment, not a
                    red flag, and only the filings distinguish them.
                """.trimIndent(),
                doesNotEstablish = "It does not establish that profit is overstated, or that a " +
                    "cash conversion ratio has a particular fair value. Low conversion has " +
                    "legitimate explanations that this ratio cannot separate.",
                relatedFlag = "CFO_NEGATIVE_WHILE_PAT_POSITIVE",
            ),

            Lesson(
                id = "expert.screener.combining-filters",
                title = "Combining filters — and the survivorship bias you just created",
                body = """
                    Stacking filters feels like rigour and is mostly arithmetic. Each filter
                    removes names, each removal shrinks the sample, and every percentage in the
                    result is computed over whatever survived. Six filters over a watchlist can
                    leave three names, and three names support no conclusion at all.

                    The first discipline is to run each filter alone and look at how many names
                    it removes before combining them. A filter that removes 40% of the list is
                    doing most of the work and deserves the most scrutiny, not the least.

                    The second is to write down what each filter excludes and keep that list.
                    Not as a caveat — as part of the result. ROCE high excludes capital-light
                    businesses and businesses mid-investment. Low leverage excludes anything that
                    needs capital. Strong cash conversion excludes businesses whose accounting
                    stretches receivables. A screen that states its own exclusions is auditable; a
                    screen that reports only its winners is not.

                    The third is the bias this module exists to warn about. Filtering a list of
                    today's survivors produces a list of today's survivors who passed. The
                    delisted and the merged are gone before the first filter runs, which is the
                    same survivorship bias that makes any fixture assembled from a present-day
                    symbol master optimistic. No amount of downstream care fixes an upstream
                    selection.

                    And the coverage rule, which is specific to this app: **do not build a filter
                    on a ratio the app cannot source.** Gross margin and EV/EBITDA have no COGS
                    line and no cash or debt line respectively, so they are NOT_SOURCED. The
                    bank ratios — net interest margin, gross and net NPA, CASA, provision coverage
                    — are NOT_SOURCED after a live probe found their labels on screener.in with
                    their values behind a login paywall. A screen over a value the app never
                    parsed is a screen over nothing.

                    Use the ratios the app prints, compare within sector, and record which
                    filters moved the count most. The result is a smaller, more legible list with
                    a written account of what it excludes — which is a better object to reason
                    about than a ranked list with no memory of what was removed.
                """.trimIndent(),
                doesNotEstablish = "It does not establish that a filtered list is better, safer " +
                    "or correctly valued. Filtering reduces sample size and inherits the " +
                    "survivorship bias of any list assembled from today's symbols.",
                relatedFlag = "CFO_NEGATIVE_WHILE_PAT_POSITIVE",
            ),
        ),
    ),
)