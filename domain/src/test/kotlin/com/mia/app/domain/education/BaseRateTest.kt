package com.mia.app.domain.education

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The base-rate figures are **pinned to plan.md §6.1**, not to a copy of it.
 *
 * Two layers of assertion, deliberately:
 *
 * 1. The constants equal the published values, spelled out. This fails the moment somebody
 *    "improves" a figure — rounds 50.10 to 50, recomputes 48.93 as 48.9, or invents a plausible
 *    fourth signal.
 * 2. The same constants are re-parsed **out of `plan.md` itself** and compared. A hard-coded
 *    expectation only detects change; it does not detect *the plan changing underneath the app*.
 *    Layer 2 does, and its failure message names the file to edit.
 *
 * Layer 2 skips rather than fails when the document is absent (an installed artifact, a renamed
 * checkout) — but on CI the file is always there, so the guard is live where it matters.
 */
class BaseRateTest {

    // ---- layer 1: the published values, verbatim ----------------------------------------------

    @Test
    fun `published control test figures match plan md 6 1`() {
        assertEquals(20_780, BaseRates.PUBLISHED_RETURNS, "plan.md §6.1: 20,780 next-day returns")
        assertEquals(20, BaseRates.PUBLISHED_SYMBOLS, "plan.md §6.1: 20 large caps")

        assertEquals(50.10, BaseRates.ALWAYS_UP_PCT, "plan.md §6.1: base rate always up")
        assertEquals(49.90, BaseRates.ALWAYS_DOWN_PCT, "plan.md §6.1: base rate always down")

        assertEquals(-0.0033, BaseRates.RSI14_CORRELATION, "plan.md §6.1: RSI(14) corr")
        assertEquals(50.24, BaseRates.RSI14_DIRECTION_ACCURACY_PCT, "plan.md §6.1: RSI(14) dir. accuracy")

        assertEquals(-0.0024, BaseRates.MOMENTUM_200D_CORRELATION, "plan.md §6.1: momentum 200d corr")
        assertEquals(50.17, BaseRates.MOMENTUM_200D_DIRECTION_ACCURACY_PCT, "plan.md §6.1: momentum 200d dir. accuracy")

        assertEquals(-0.0109, BaseRates.ZSCORE_VS_MA50_CORRELATION, "plan.md §6.1: z-score vs MA50 corr")
        assertEquals(49.81, BaseRates.ZSCORE_VS_MA50_DIRECTION_ACCURACY_PCT, "plan.md §6.1: z-score vs MA50 dir. accuracy")

        assertEquals(0.73, BaseRates.NEXT_DAY_MOVE_MEDIAN_PCT, "plan.md §6.1: next-day move median")
        assertEquals(0.99, BaseRates.NEXT_DAY_MOVE_MEAN_PCT, "plan.md §6.1: next-day move mean")
    }

    @Test
    fun `app reproduction figures match verification md 3`() {
        assertEquals(2_723, BaseRates.APP_REPRODUCED_UP_SESSIONS)
        assertEquals(5_565, BaseRates.APP_REPRODUCED_TOTAL_SESSIONS)
        assertEquals(48.93, BaseRates.APP_REPRODUCED_UP_PCT)

        // The constant is the rounded quotient, and it must stay that way rather than drifting
        // into a second, separately-typed version of the same ratio.
        val recomputed = 100.0 * BaseRates.APP_REPRODUCED_UP_SESSIONS / BaseRates.APP_REPRODUCED_TOTAL_SESSIONS
        assertEquals(
            BaseRates.APP_REPRODUCED_UP_PCT,
            Math.round(recomputed * 100.0) / 100.0,
            "48.93% is 2,723/5,565 rounded to two places; the constant must not be re-derived",
        )
    }

    @Test
    fun `the two directions sum to one hundred percent`() {
        assertEquals(
            100.0,
            BaseRates.ALWAYS_UP_PCT + BaseRates.ALWAYS_DOWN_PCT,
            1e-9,
            "plan.md §6.1 prints 50.10 + 49.90",
        )
    }

    @Test
    fun `every published signal renders its own constants`() {
        val lines = BaseRates.signalLines()
        assertEquals(3, lines.size)
        assertTrue(lines[0].contains("-0.0033") && lines[0].contains("50.24%"), lines[0])
        assertTrue(lines[1].contains("-0.0024") && lines[1].contains("50.17%"), lines[1])
        assertTrue(lines[2].contains("-0.0109") && lines[2].contains("49.81%"), lines[2])
    }

    // ---- layer 2: re-parsed out of plan.md ------------------------------------------------------

    @Test
    fun `plan md still publishes these numbers`() {
        val plan = repositoryFile("plan.md") ?: return
        val text = plan.readText()

        fun after(label: String): String {
            val start = text.indexOf(label)
            assertTrue(start >= 0, "plan.md no longer contains \"$label\"")
            return text.substring(start + label.length).lineSequence().first()
        }

        assertTrue(
            after("base rate  always up   :").contains("50.10"),
            "plan.md §6.1 changed its published up base rate",
        )
        assertTrue(
            after("base rate  always down :").contains("49.90"),
            "plan.md §6.1 changed its published down base rate",
        )
        assertTrue(after("RSI(14)").contains("corr=-0.0033") && after("RSI(14)").contains("50.24%"))
        assertTrue(after("momentum 200d").contains("corr=-0.0024") && after("momentum 200d").contains("50.17%"))
        assertTrue(after("z-score vs MA50").contains("corr=-0.0109") && after("z-score vs MA50").contains("49.81%"))
        assertTrue(after("next-day move:").contains("median 0.73%") && after("next-day move:").contains("mean 0.99%"))
    }

    @Test
    fun `verification md still states the reproduction`() {
        val doc = repositoryFile("docs/VERIFICATION.md") ?: return
        val text = doc.readText().replace(",", "")
        assertTrue(text.contains("2723 up / 5565 sessions = 48.93%"), "docs/VERIFICATION.md §3 changed")
    }

    private fun repositoryFile(relative: String): File? {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        return null
    }

    // ---- the module that renders them ----------------------------------------------------------

    @Test
    fun `the base rate module exists and is the foundation`() {
        assertEquals(1, BASE_RATE_MODULES.size, "gap E7 asks for one module, not a spread")
        val module = BASE_RATE_MODULES.single()
        assertEquals(Level.BEGINNER, module.level, "the base-rate lesson is the foundation; it is not expert content")
        assertEquals("beginner.base-rates", module.id)
        assertTrue(module.title.contains("predict"), module.title)
        assertTrue(module.lessons.isNotEmpty(), "a module with no lessons renders as an empty screen")
    }

    @Test
    fun `every base rate lesson declares what it does not establish`() {
        for (module in BASE_RATE_MODULES) {
            for (lesson in module.lessons) {
                assertTrue(
                    lesson.doesNotEstablish.isNotBlank(),
                    "${lesson.id} has a blank doesNotEstablish; that field is mandatory",
                )
                assertTrue(
                    lesson.body.isNotBlank(),
                    "${lesson.id} has a blank body",
                )
                assertTrue(
                    lesson.body.contains("does not establish", ignoreCase = true) ||
                        lesson.doesNotEstablish.contains("does not establish", ignoreCase = true),
                    "${lesson.id} must phrase its limit as a negated claim",
                )
            }
        }
    }
}