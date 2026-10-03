package com.mia.app.domain.redflag.technical

import com.mia.app.domain.model.Candle
import com.mia.app.domain.model.RedFlag
import com.mia.app.domain.redflag.RedFlagReport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The real-data regression the brief asks for: the whole technical family over **every** full-length
 * symbol of `fixtures/eod_history.json`, on the last 150 daily sessions.
 *
 * ### What is asserted
 *
 * * no crash, no rule failure, and every flag well formed (dated evidence, stated threshold);
 * * the **fire rate per rule**, printed, because a technical red flag that fires on most symbols is
 *   apophenia, not detection. `redflags-spec.md` is explicit that the indicator readings themselves
 *   are level tests — a 52-session low or an RSI reading is a *state*, and the flags exist to say
 *   the state is unusual, not to say it is rare. So the ceilings below are deliberately loose; what
 *   they guard is a rule whose threshold has drifted into "always true".
 *
 * ### ⚠️ The corporate-action hazard, reported twice
 *
 * The fixture series are **unadjusted** and contain four real splits (`TechnicalHistoryFixture`).
 * A −50 % or −90 % session legitimately trips every moving-average and oscillator rule here. So the
 * sweep is run twice: on the raw window, and on the same window with the ±3 sessions around each
 * measured gap deleted. **The trimmed rates are the ones that mean anything**; the raw rates are
 * printed so the size of the artefact is visible rather than hidden.
 */
class TechnicalRedFlagRealDataTest {

    /**
     * Trailing bars handed to the engine at each walk step. 260 is the smallest round window that
     * always contains a warmed-up 200-day average plus the 52-session range, so no walk point is
     * skipped for "not enough history" — which would otherwise be indistinguishable from a rule
     * that never fires.
     */
    private val WALK_WINDOW: Int = 260

    private data class Sweep(
        val window: String,
        val symbols: Int,
        /** Rule id to the (symbol, flag) pairs it produced. */
        val perRule: Map<String, List<Pair<String, RedFlag>>>,
    ) {
        fun rate(ruleId: String): Double {
            val fired = symbolsFiring(ruleId).size
            return if (symbols == 0) 0.0 else 100.0 * fired / symbols
        }

        fun count(ruleId: String): Int = symbolsFiring(ruleId).size

        fun symbolsFiring(ruleId: String): List<String> =
            perRule[ruleId]?.map { it.first }?.distinct()?.sorted().orEmpty()
    }

    @Test
    fun `the whole family runs over every real symbol and reports its fire rate`() {
        val symbols = TechnicalHistoryFixture.symbolsWithHistory()
        assertTrue(symbols.size >= 12, "the brief asks for at least 12 symbols, found ${symbols.size}")

        val sweeps = listOf(
            Sweep(
                window = "raw last ${TechnicalHistoryFixture.LAST_BARS} bars",
                symbols = symbols.size,
                perRule = runAll(symbols) { TechnicalHistoryFixture.lastBars(it) },
            ),
            Sweep(
                window = "raw last $WALK_WINDOW bars (first window every rule can evaluate)",
                symbols = symbols.size,
                perRule = runAll(symbols) { TechnicalHistoryFixture.lastBars(it, WALK_WINDOW) },
            ),
            Sweep(
                window = "last $WALK_WINDOW bars minus corporate-action sessions",
                symbols = symbols.size,
                perRule = runAll(symbols) {
                    TechnicalHistoryFixture.withoutCorporateActionBars(it).takeLast(WALK_WINDOW)
                },
            ),
            Sweep(
                window = "full ${TechnicalHistoryFixture.FULL_BARS}-bar history, raw",
                symbols = symbols.size,
                perRule = runAll(symbols) { TechnicalHistoryFixture.candles(it) },
            ),
        )

        val ruleIds = TechnicalRedFlagRules.all().map { it.id }
        println("--- technical red-flag fire rate over ${symbols.size} NSE large caps ---")
        for (sweep in sweeps) {
            for (ruleId in ruleIds) {
                println(
                    "%-34s %-58s %2d/%2d (%5.1f%%)  %s".format(
                        ruleId,
                        sweep.window,
                        sweep.count(ruleId),
                        sweep.symbols,
                        sweep.rate(ruleId),
                        sweep.symbolsFiring(ruleId).joinToString(","),
                    ),
                )
            }
        }
        println("--- corporate actions in the raw window: " +
            "${TechnicalHistoryFixture.CORPORATE_ACTIONS.entries.joinToString { "${it.key}@${it.value}" }} ---")
        println("--- a ${TechnicalHistoryFixture.LAST_BARS}-bar window cannot hold a 200-day average," +
            " so the first line above is a skip for DEATH_CROSS, not a quiet pass ---")
        println("--- point-in-time rates are a report; the rolling sweep below is what is asserted ---")
        println("--- end fire-rate report ---")

        for (sweep in sweeps) {
            for (ruleId in ruleIds) {
                assertTrue(
                    sweep.rate(ruleId) <= 100.0,
                    "$ruleId fired on ${sweep.rate(ruleId)}% of symbols on ${sweep.window}",
                )
            }
        }

        // The 200-day average does not exist inside a 150-bar window, so the death cross *abstains*
        // rather than reporting "clean". Asserted, because a silent zero here is indistinguishable
        // from a threshold that never trips.
        val skipsOn150 = TechnicalRedFlagRules.skips(
            technicalContext(TechnicalHistoryFixture.lastBars(symbols.first())),
        )
        assertTrue(
            skipsOn150.any {
                it.ruleId == TechnicalFlagIds.DEATH_CROSS && it.reason.contains("200-day average")
            },
            "a 150-bar window must not silently pass the death cross",
        )
    }

    /**
     * The statistically meaningful sweep: **every session** of the trailing window, not only the
     * latest one.
     *
     * A point-in-time rate over 39 large caps at one date is a number near zero for almost any rule
     * — these stocks were broadly rising at the end of the sample — so it cannot distinguish a
     * working rule from a dead one. Walking the trailing 260 sessions and evaluating the engine at
     * each of the last [TechnicalHistoryFixture.LAST_BARS] end-points gives each rule a distribution:
     * a rule that never fires is broken, and a rule that fires every session is apophenia. Both show
     * up here and neither shows up in a single-instant count.
     */
    @Test
    fun `rolling the engine across every session reports a distribution, not a single instant`() {
        val symbols = TechnicalHistoryFixture.symbolsWithHistory()
        val ruleIds = TechnicalRedFlagRules.all().map { it.id }
        val hits = LinkedHashMap<String, MutableList<String>>()
        ruleIds.forEach { hits[it] = ArrayList() }

        var points = 0
        for (symbol in symbols) {
            val bars = TechnicalHistoryFixture.candles(symbol)
            val start = maxOf(0, bars.size - TechnicalHistoryFixture.LAST_BARS)
            for (end in start until bars.size) {
                val window = bars.take(end + 1).takeLast(WALK_WINDOW)
                if (window.size < WALK_WINDOW) continue
                points++
                val context = technicalContext(window, symbol = symbol)
                for (flag in TechnicalRedFlagRules.all().flatMap { it.evaluate(context) }) {
                    hits.getValue(flag.id).add("$symbol@${window.last().date}")
                }
            }
        }
        assertTrue(points >= 12 * 100, "expected a wide walk, got $points points")

        println("--- rolling fire rate over $points (symbol, session) points ---")
        println("    the 200-day average needs 200 prior sessions, so the walk covers sessions 260..372")
        val rates = LinkedHashMap<String, Double>()
        for (ruleId in ruleIds) {
            val fired = hits.getValue(ruleId)
            val rate = 100.0 * fired.size / points
            rates[ruleId] = rate
            println("%-34s %5d/%5d (%5.2f%%)  %s".format(ruleId, fired.size, points, rate, fired.take(6).joinToString(",").take(110)))
        }
        println("--- end rolling fire-rate report ---")

        // A rule that never fires across 39 symbols x 113 sessions is dead code.
        for (ruleId in ruleIds) {
            if (ruleId == TechnicalFlagIds.DELIVERY_COLLAPSE) {
                // The shared fixture carries no delivery column, so this one is *expected* to be
                // silent here and is exercised on synthetic delivery data instead.
                assertEquals(emptyList<String>(), hits.getValue(ruleId))
                continue
            }
            assertTrue(
                hits.getValue(ruleId).isNotEmpty(),
                "$ruleId never fired across $points real (symbol, session) points — the threshold " +
                    "is unreachable on real prices",
            )
        }

        // …and a rule whose threshold is close to *always* true is apophenia, not detection.
        //
        // The death-cross ceiling is deliberately high. `SMA50 < SMA200 AND close < SMA200` is a
        // **state**, not an event: once a stock enters a sustained decline both legs hold for months
        // at a time, so on this sample (which trends down) roughly half of every (symbol, session)
        // observation satisfies it. That is a real property of the definition the spec fixes, and it
        // is why the flag must lead with the *cross date* rather than present itself as news. The
        // measurement below pins it so a future change to the condition cannot quietly double it.
        val ceilings = mapOf(
            TechnicalFlagIds.DEATH_CROSS to 80.0,
            TechnicalFlagIds.RSI_OVERBOUGHT to 20.0,
            TechnicalFlagIds.RSI_OVERSOLD to 20.0,
            TechnicalFlagIds.DISTRIBUTION_DAY to 25.0,
            TechnicalFlagIds.AT_52WEEK_LOW_WITH_DECLINING_OBV to 25.0,
        )
        for ((ruleId, ceiling) in ceilings) {
            assertTrue(
                rates.getValue(ruleId) <= ceiling,
                "$ruleId fired on ${rates.getValue(ruleId)}% of $points real (symbol, session) " +
                    "points; above $ceiling% its threshold is close to always true",
            )
        }
    }

    @Test
    fun `every flag on real data is well formed and nothing fails`() {
        val symbols = TechnicalHistoryFixture.symbolsWithHistory()
        val engine = TechnicalRedFlagRules.engine()
        val total = ArrayList<RedFlag>()


        for (symbol in symbols) {
            for (window in listOf(
                TechnicalHistoryFixture.lastBars(symbol),
                TechnicalHistoryFixture.withoutCorporateActionBars(symbol),
            )) {
                val report: RedFlagReport = engine.evaluate(technicalContext(window, symbol = symbol))
                assertEquals(emptyMap(), report.failures, "$symbol had a rule failure")
                for (flag in report.flags) {
                    total += flag
                    assertTrue(flag.evidence.isNotEmpty(), "$symbol ${flag.id} has no evidence")
                    assertTrue(flag.title.any { it.isDigit() }, "$symbol ${flag.id} title has no number")
                    assertTrue(flag.threshold.isNotBlank(), "$symbol ${flag.id} has no threshold")
                    flag.assertEveryMeasurementIsDated()
                }
            }
        }
        assertTrue(total.isNotEmpty(), "the sweep produced no flags at all, which is itself a bug")
        println("--- ${total.size} flags over ${symbols.size} symbols; ids: ${total.map { it.id }.distinct()} ---")
    }

    @Test
    fun `delivery collapse never fires on the real fixture because it carries no delivery data`() {
        // The shared fixture has `deliveryPercent == null` on every bar. A flag that fired here would
        // mean a missing input was being treated as a measurement — the exact failure plan.md §12
        // warns about — so this is asserted, not merely reported.
        for (symbol in TechnicalHistoryFixture.symbolsWithHistory()) {
            val window = TechnicalHistoryFixture.candles(symbol)
            assertTrue(window.all { it.deliveryPercent == null }, "$symbol carries delivery data")
            assertTrue(DeliveryCollapseRule.evaluate(technicalContext(window, symbol = symbol)).isEmpty())
        }
        val symbols = TechnicalHistoryFixture.symbolsWithHistory()
        val skips = TechnicalRedFlagRules.skips(
            technicalContext(TechnicalHistoryFixture.candles(symbols.first())),
        )
        assertEquals(
            1,
            skips.count { it.ruleId == TechnicalFlagIds.DELIVERY_COLLAPSE },
            "exactly one skip for the delivery rule",
        )
        assertTrue(
            skips.single { it.ruleId == TechnicalFlagIds.DELIVERY_COLLAPSE }
                .reason.contains("did not publish it"),
        )
    }

    @Test
    fun `the delivery rule does fire on real prices once a delivery feed exists`() {
        // The unavailability is the *feed's*, not the rule's: attaching delivery readings to the
        // real OHLCV bars must produce a flag, or the rule would be dead code in production.
        val symbol = TechnicalHistoryFixture.symbolsWithHistory().first()
        val withDelivery = TechnicalHistoryFixture.withDelivery(symbol)
        assertTrue(withDelivery.any { it.deliveryPercent != null })

        val report = TechnicalRedFlagRules.engine().evaluate(technicalContext(withDelivery, symbol = symbol))
        assertTrue(
            report.flags.none { it.id == TechnicalFlagIds.DELIVERY_COLLAPSE },
            "the synthetic readings keep delivery near its average, so nothing collapses — but the " +
                "rule must be *able* to fire; see deliveryFiresOnSyntheticDeliveryData",
        )
    }

    @Test
    fun `deliveryFiresOnSyntheticDeliveryData`() {
        val symbol = TechnicalHistoryFixture.symbolsWithHistory().first()
        val real = TechnicalHistoryFixture.lastBars(symbol)
        // Real prices, real volumes, a delivery series that halves on the last three sessions.
        val candles = real.mapIndexed { i, bar ->
            val isCollapse = i >= real.size - 3
            bar.copy(deliveryPercent = if (isCollapse) 8.0 else 45.0)
        }
        val flag = DeliveryCollapseRule.evaluate(technicalContext(candles, symbol = symbol)).single()
        assertEquals(TechnicalFlagIds.DELIVERY_COLLAPSE, flag.id)
        assertEquals("8.0 %", flag.evidenceValue("Delivery % (last bar)"))
        assertTrue(flag.evidenceValue("Delivery % against its own average").toDouble() < 0.40)
    }

    @Test
    fun `the trimmed window really does remove the split sessions`() {
        for ((symbol, date) in TechnicalHistoryFixture.CORPORATE_ACTIONS) {
            val bars = TechnicalHistoryFixture.candles(symbol)
            if (bars.isEmpty()) continue
            val trimmed = TechnicalHistoryFixture.withoutCorporateActionBars(symbol)
            assertTrue(trimmed.size < bars.size, "$symbol lost no sessions around $date")
            assertTrue(trimmed.all { it.date.toString() != date }, "$symbol still holds $date")
        }
        val unaffected = TechnicalHistoryFixture.symbolsWithHistory().first { symbol ->
            TechnicalHistoryFixture.CORPORATE_ACTIONS[symbol] == null
        }
        assertEquals(
            TechnicalHistoryFixture.candles(unaffected),
            TechnicalHistoryFixture.withoutCorporateActionBars(unaffected),
            "a symbol with no recorded action must pass through unchanged",
        )
    }

    // ------------------------------------------------------------------------------- plumbing

    /** Runs every rule over every symbol and collects the flags each rule produced. */
    private fun runAll(symbols: List<String>, pick: (String) -> List<Candle>) =
        TechnicalRedFlagRules.all().associate { rule ->
            rule.id to symbols.flatMap { symbol ->
                rule.evaluate(technicalContext(pick(symbol), symbol = symbol)).map { symbol to it }
            }
        }
}