package com.mia.app.domain.education

/**
 * The control test, as committed data.
 *
 * ### Why this object exists
 * The repository's single most important measurement is a **negative** one (plan.md §6.1): a
 * control test over 20,780 next-day returns across 20 large caps found every conventional signal
 * sitting on top of a coin flip. That finding lives in `plan.md` and `README.md` — documents
 * almost nobody opens inside the app. Gap E7 in `gaps.md` asks for it to be *in the product*,
 * because "the evidence for the app's honesty is invisible to the people it protects".
 *
 * So the figures are committed as `const`s rather than retyped into prose. A lesson body that
 * renders `${ALWAYS_UP_PCT}` cannot drift from the published number, and `BaseRateTest` asserts
 * each constant against the value in `plan.md` §6.1 verbatim.
 *
 * ### Why the numbers are not recomputed here
 * These are *published measurements*. The app does not re-derive them, cannot re-derive them
 * (it holds 372 sessions for 41 symbols, not 20,780 returns across 20 large caps in a 20-year
 * window), and must not present a re-derived substitute as the same number. What the app *can*
 * do — and does record — is its own independent reproduction over its own fixture, which is why
 * [APP_REPRODUCED_UP_PCT] exists as a separate constant with its own source, never as a
 * replacement for [ALWAYS_UP_PCT].
 */
object BaseRates {

    /** Where every constant in this object comes from, so a reader can check it in one hop. */
    const val PUBLISHED_SOURCE = "plan.md §6.1 — control test over 20,780 next-day returns, 20 large caps"
    const val APP_SOURCE = "docs/VERIFICATION.md §3 — reproduced over the app's own 372-session fixture"

    // ---- plan.md §6.1: the published control test ------------------------------------------

    /** Returns in the published control sample. */
    const val PUBLISHED_RETURNS = 20_780

    /** Large caps in the published control sample. */
    const val PUBLISHED_SYMBOLS = 20

    /** `base rate always up : 50.10%`. */
    const val ALWAYS_UP_PCT = 50.10

    /** `base rate always down : 49.90%`. */
    const val ALWAYS_DOWN_PCT = 49.90

    /** `RSI(14) corr=-0.0033`. */
    const val RSI14_CORRELATION = -0.0033

    /** `RSI(14) dir. accuracy 50.24%`. */
    const val RSI14_DIRECTION_ACCURACY_PCT = 50.24

    /** `momentum 200d corr=-0.0024`. */
    const val MOMENTUM_200D_CORRELATION = -0.0024

    /** `momentum 200d dir. accuracy 50.17%`. */
    const val MOMENTUM_200D_DIRECTION_ACCURACY_PCT = 50.17

    /** `z-score vs MA50 corr=-0.0109`. */
    const val ZSCORE_VS_MA50_CORRELATION = -0.0109

    /** `z-score vs MA50 dir. accuracy 49.81%`. */
    const val ZSCORE_VS_MA50_DIRECTION_ACCURACY_PCT = 49.81

    /** `next-day move: median 0.73%`. */
    const val NEXT_DAY_MOVE_MEDIAN_PCT = 0.73

    /** `next-day move: mean 0.99%`. */
    const val NEXT_DAY_MOVE_MEAN_PCT = 0.99

    // ---- the app's own reproduction (docs/VERIFICATION.md §3) --------------------------------

    /** Sessions in the app's fixture that closed higher than they opened. */
    const val APP_REPRODUCED_UP_SESSIONS = 2_723

    /** Sessions in the app's fixture that had a next session to compare against. */
    const val APP_REPRODUCED_TOTAL_SESSIONS = 5_565

    /** `2,723 up / 5,565 sessions = 48.93%`. */
    const val APP_REPRODUCED_UP_PCT = 48.93

    /** Sessions in the committed price fixture — `data/src/test/resources/eod_history.json`. */
    const val APP_FIXTURE_SESSIONS = 372

    /** Symbols in the committed price fixture. All of them survived; see the survivorship lesson. */
    const val APP_FIXTURE_SYMBOLS = 41

    /**
     * One row of the published table, so a screen can render the table instead of a sentence
     * about the table. `correlation` is the correlation between the signal and the next-day
     * return; `directionAccuracyPct` is the share of sessions the signal called correctly.
     */
    data class ControlSignal(
        val label: String,
        val correlation: Double,
        val directionAccuracyPct: Double,
    )

    /** The three signals plan.md §6.1 publishes, in the order it prints them. */
    val PUBLISHED_SIGNALS: List<ControlSignal> = listOf(
        ControlSignal("RSI(14)", RSI14_CORRELATION, RSI14_DIRECTION_ACCURACY_PCT),
        ControlSignal("200-day momentum", MOMENTUM_200D_CORRELATION, MOMENTUM_200D_DIRECTION_ACCURACY_PCT),
        ControlSignal("z-score vs MA50", ZSCORE_VS_MA50_CORRELATION, ZSCORE_VS_MA50_DIRECTION_ACCURACY_PCT),
    )

    /**
     * The three figures as printed lines, e.g. `"RSI(14) — correlation -0.0033, direction accuracy
     * 50.24%"`. Rendered from the constants so a screen never retypes a measurement.
     */
    fun signalLines(): List<String> = PUBLISHED_SIGNALS.map {
        "%s — correlation %+.4f, direction accuracy %.2f%%".format(it.label, it.correlation, it.directionAccuracyPct)
    }
}

/**
 * Gap E7: the base-rate module.
 *
 * Level [Level.BEGINNER] deliberately — this is the foundation lesson the other two levels assume,
 * and putting it at the top of the tree is the honest ordering: every ratio, flag and pattern
 * later in the curriculum is only interpretable once the reader knows that the base rate for
 * tomorrow is a coin flip and that this app measures rather than asserts.
 */
val BASE_RATE_MODULES: List<Module> = listOf(

    Module(
        id = "beginner.base-rates",
        level = Level.BEGINNER,
        title = "Why nobody can predict tomorrow",
        summary = "The control test: over 20,780 next-day returns across 20 large caps, every " +
            "conventional indicator called the direction about half the time. This module prints " +
            "those figures instead of asserting that they exist.",
        lessons = listOf(

            Lesson(
                id = "beginner.base-rates.control-test",
                title = "The control test, printed",
                body = """
                    Before any tool can claim it finds something, the honest question is: what
                    would it have printed if it found nothing at all? That question was answered
                    by measuring. ${BaseRates.PUBLISHED_SOURCE}.

                    The base rate is simply the share of sessions in which a price closed higher.
                    Across ${BaseRates.PUBLISHED_RETURNS} next-day returns in ${BaseRates.PUBLISHED_SYMBOLS} large caps, the
                    price closed higher ${"%.2f".format(BaseRates.ALWAYS_UP_PCT)}% of the time and lower
                    ${"%.2f".format(BaseRates.ALWAYS_DOWN_PCT)}% of the time. A method that always answered
                    "up" would have been right about half the time, before reading a single chart.

                    Three conventional indicators were then measured against the same sessions —
                    the correlation between each signal and the next day's return, and the share of
                    sessions each signal's direction call got right:

                    ${BaseRates.signalLines().joinToString("\n") { "  - " + it }}

                    Every correlation is within a rounding error of zero, and every accuracy sits
                    between ${"%.2f".format(BaseRates.ZSCORE_VS_MA50_DIRECTION_ACCURACY_PCT)}% and
                    ${"%.2f".format(BaseRates.RSI14_DIRECTION_ACCURACY_PCT)}%. That is what a coin does. It is also
                    why this app refuses to print a score as if it were an outcome.

                    The typical next-day move was small, too: median
                    ${"%.2f".format(BaseRates.NEXT_DAY_MOVE_MEDIAN_PCT)}%, mean
                    ${"%.2f".format(BaseRates.NEXT_DAY_MOVE_MEAN_PCT)}%. The mean being well above the median is
                    ordinary — a few large days pull an average up while most days stay small —
                    and it matters later, because a strategy that wins rarely and wins big is
                    betting against the shape of the distribution, not against the base rate.
                """.trimIndent(),
                doesNotEstablish = "It does not establish that no indicator can ever be useful. " +
                    "It establishes that these three indicators, over this sample, on this " +
                    "market, carried no measurable directional information.",
            ),

            Lesson(
                id = "beginner.base-rates.reproduction",
                title = "This app reproduced it",
                body = """
                    ${BaseRates.APP_SOURCE}.

                    Next-day direction over the app's own committed fixture:
                    ${BaseRates.APP_REPRODUCED_UP_SESSIONS} up out of ${BaseRates.APP_REPRODUCED_TOTAL_SESSIONS} comparable
                    sessions = ${"%.2f".format(BaseRates.APP_REPRODUCED_UP_PCT)}%. The fixture is
                    ${BaseRates.APP_FIXTURE_SESSIONS} real sessions of ${BaseRates.APP_FIXTURE_SYMBOLS} NSE large caps,
                    read from the end-of-day bhavcopy.

                    Two independent samples — one large and historical, one small and recent —
                    landing within about one percentage point of each other is the useful part.
                    A single study could be a quirk of its sample. Two that agree are a property
                    of the market being measured.

                    Notice what ${"%.2f".format(BaseRates.APP_REPRODUCED_UP_PCT)}% is not. It is not a
                    shortfall against a promise: nothing promised 50.01%. It is a number measured
                    on the data this device actually holds, which is the only kind of number this
                    app treats as true.
                """.trimIndent(),
                doesNotEstablish = "It does not establish that the two samples are statistically " +
                    "identical, and it is not a substitute for the published sample. The app's " +
                    "fixture is 372 sessions of 41 surviving large caps, not " +
                    "${BaseRates.PUBLISHED_RETURNS} returns across ${BaseRates.PUBLISHED_SYMBOLS} names over a long history.",
            ),

            Lesson(
                id = "beginner.base-rates.two-numbers",
                title = "Why this app keeps two numbers and never one",
                body = """
                    Given a base rate of about half, a single blended number is always a
                    fabrication: some part of it is geometry, some part is history, and the reader
                    cannot see which is which. So the app keeps them apart and labels both.

                    Match score, 0-100: how closely the price action resembles the textbook shape
                    of a formation. It is a description of the chart, computed from the geometry
                    that is on screen. Nothing about tomorrow enters into it.

                    Hit rate, in per cent: how often this pattern's measured-move level was
                    reached on this stock, from outcomes the app tracked after the fact. It is a
                    historical count, never a forward statement.

                    The second number has a floor: below 30 samples it is not printed at all,
                    and the app shows "(n of 30)" instead. Thirty is not a statistical threshold —
                    it is the point at which a single outcome still moves the percentage by more
                    than three points, which makes a small number look more precise than it is.

                    A 70 match score and a 55% hit rate answer two different questions. Collapsing
                    them into "70%" would be the single most misleading thing this app could do,
                    and it is the reason the base-rate module exists at all.
                """.trimIndent(),
                doesNotEstablish = "It does not establish that a pattern with a high hit rate is " +
                    "worth acting on, that the two numbers can be combined, or that any number " +
                    "in this app predicts a future price.",
            ),
        ),
    ),
).also { Curriculum.register(it) }

/**
 * Registers the control-test module.
 *
 * Self-registering for the same reason [EXPERT_MODULES] does. This is the module that carries the
 * repository's central finding — that every conventional signal in it sits on a coin flip — so it
 * being declared but unreachable would leave the app's own honesty argument as the one thing a
 * reader cannot find.
 */
