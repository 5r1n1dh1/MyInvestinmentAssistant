package com.mia.app.domain.compliance

/**
 * Regulatory posture (plan.md §1.4). These are hard constraints, not style preferences.
 *
 * SEBI's Investment Adviser boundary: the app is educational end-of-day analysis, not
 * advice. The engine has no buy/sell verdicts and no forecast targets, and the UI must not
 * manufacture either out of pattern geometry.
 */
object Disclosure {

    const val APP_NAME = "My Investment Assistant"

    /** Shown on first launch and in Settings (plan.md §1.4 rule 4). */
    const val FIRST_LAUNCH =
        "My Investment Assistant provides educational end-of-day analysis of publicly " +
            "available market data. It does not give investment advice, recommendations, " +
            "or price targets. Nothing in this app is a promise or probability of future " +
            "returns. Consult a SEBI-registered investment adviser before investing."

    const val SETTINGS = FIRST_LAUNCH

    const val SCORE_MEANING =
        "A match score measures how closely price action resembles the textbook shape of a " +
            "chart pattern. It does not measure whether the stock will rise, and it is not a " +
            "buy or sell signal."

    const val MEASURED_MOVE =
        "Projected levels are measured-move pattern geometry — the height of the formation " +
            "drawn from its breakout point. They are not price targets."

    const val HIT_RATE =
        "Hit rates show how often a pattern's measured-move level was reached on this stock " +
            "in the past. A hit is a chart event, not a profit, and past outcomes do not " +
            "predict future ones."

    const val DATA_SOURCE =
        "Prices: NSE EOD bhavcopy. History: Yahoo Finance. Fundamentals: screener.in. " +
            "Data may be delayed or incorrect. Verify before acting."

    const val STORE_LISTING_POSITIONING =
        "Educational end-of-day analytics for Indian equities. Not investment advice, not a " +
            "recommendation engine, no price targets."

    /** Shown on the measured-move / projection surfaces. */
    const val PROJECTION_FOOTER = MEASURED_MOVE

    /** Attach to every fundamentals figure. */
    const val AS_OF_TEMPLATE = "as of %s"
}

/**
 * Build-time and test-time guard against advice-like language (plan.md §1.4 rule 1).
 *
 * A unit test walks the app's own string resources and this repository's user-facing
 * Kotlin literals through [findForbiddenPhrases], so the constraint is machine-checked
 * rather than remembered.
 */
object AdviceLanguage {
    /**
     * Banned as standalone claims. Matched case-insensitively on word boundaries.
     * Deliberately narrow: legitimate words such as "buy" inside "buyer" or "probable
     * profit margin" must not trip the guard.
     */
    val FORBIDDEN = listOf(
        "buy signal",
        "sell signal",
        "buy recommendation",
        "sell recommendation",
        "guaranteed",
        "sure shot",
        "risk-free",
        "risk free",
        "will rise",
        "will fall",
        "will go up",
        "will go down",
        "price target",
        "target price",
        "probability of profit",
        "chance of profit",
        "80% chance",
        "foolproof",
        "can't lose",
        "cannot lose",
        "no-brainer",
        "hot tip",
    )

    /** Phrases that must always accompany a score or a projection. */
    val REQUIRED_NEAR = listOf(
        "match score" to Disclosure.SCORE_MEANING,
    )

    fun findForbiddenPhrases(text: String): List<String> {
        val lower = text.lowercase()
        return FORBIDDEN.filter { phrase ->
            lower.contains(phrase) || lower.contains(phrase.replace('-', ' '))
        }
    }

    fun isClean(text: String): Boolean = findForbiddenPhrases(text).isEmpty()

    /** Throws when user-facing text violates the regulatory posture. */
    fun requireClean(vararg texts: String) {
        texts.forEach { text ->
            val hits = findForbiddenPhrases(text)
            require(hits.isEmpty()) {
                "Regulatory violation in \"$text\": ${hits.joinToString()}. " +
                    "See plan.md §1.4."
            }
        }
    }
}

/**
 * Phrases the honesty contract requires. plan.md §6.1 forbids rendering a match score as
 * a chance of profit, and requires the sample size alongside every hit rate.
 */
object HonestyContract {
    fun requireHitRateSampleSize(label: String) {
        require(label.contains("n=") || label.contains("samples")) {
            "A hit rate must always be shown with n. Got: \"$label\""
        }
    }

    fun requireNotProbabilityClaim(text: String) {
        val normalised = text.lowercase()
        PROBABILITY_CLAIMS.forEach { claim ->
            require(!normalised.contains(claim)) {
                "Match score must not be rendered as $claim: \"$text\""
            }
        }
    }

    val PROBABILITY_CLAIMS = listOf(
        "chance to rise",
        "chance of rise",
        "chance of rise",
        "% chance",
        "probability of rise",
        "probability of profit",
        "odds of rise",
        "will likely rise",
        "expected return of",
        "target ₹",
    )
}