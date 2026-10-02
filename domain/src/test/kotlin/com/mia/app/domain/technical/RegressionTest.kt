package com.mia.app.domain.technical

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Golden values for the least-squares fit that feeds `ScoreBreakdown.structure`
 * (plan.md §6.3, "trendline fit R²").
 */
class RegressionTest {

    @Test
    fun `a perfect ramp fits exactly`() {
        // values 0,1,2,...,9 against x = 0..9 -> slope 1, intercept 0, R² 1.
        val fit = linearRegression(List(10) { it.toDouble() })
        assertEquals(1.0, fit.slope)
        assertEquals(0.0, fit.intercept)
        assertEquals(1.0, fit.rSquared)
        assertFalse(fit.isDegenerate)
        assertEquals(9.0, fit.at(9.0))
    }

    @Test
    fun `a noisy ramp fits a known slope intercept and r squared`() {
        // values 1,3,2,5,4 against x = 0..4:
        //   mean x = 2, mean y = 3
        //   Sxy = (-2)(-2)+(-1)(0)+(0)(-1)+(1)(2)+(2)(1) = 4+0+0+2+2 = 8
        //   Sxx = 4+1+0+1+4 = 10
        //   slope = 8/10 = 0.8,  intercept = 3 - 0.8*2 = 1.4
        //   Syy = 4+0+1+4+1 = 10
        //   SSE  = 0.16 + 0.64 + 1.0 + 1.44 + 0.36 = 3.6
        //   R²   = 1 - 3.6/10 = 0.64
        val fit = linearRegression(listOf(1.0, 3.0, 2.0, 5.0, 4.0))
        assertClose(0.8, fit.slope, 1e-12)
        assertClose(1.4, fit.intercept, 1e-12)
        assertClose(0.64, fit.rSquared, 1e-12)
        assertFalse(fit.isDegenerate)
    }

    @Test
    fun `a flat series is degenerate with r squared zero`() {
        // Every y is identical, so the variance is 0 and the classic 0/0 has no meaning. The
        // documented choice is 0.0: a horizontal line explains none of the variation, because
        // there is none. Reporting 1.0 (as some libraries do) would claim a perfect trendline.
        val fit = linearRegression(List(10) { 7.0 })
        assertEquals(0.0, fit.slope)
        assertEquals(7.0, fit.intercept)
        assertEquals(0.0, fit.rSquared)
        assertTrue(fit.isDegenerate)
    }

    @Test
    fun `too few points is degenerate`() {
        assertTrue(linearRegression(emptyList()).isDegenerate)
        assertTrue(linearRegression(listOf(1.0)).isDegenerate)
        // Two points always lie exactly on a line, so R² is 1 by construction. That is flagged
        // degenerate so a scorer cannot read one move as a well-fitted trendline.
        val two = linearRegression(listOf(1.0, 4.0))
        assertTrue(two.isDegenerate)
        assertEquals(3.0, two.slope)
        assertEquals(1.0, two.intercept)
        assertEquals(1.0, two.rSquared)
    }

    @Test
    fun `a declining series has a negative slope`() {
        // 100, 95, 90, 85, 80 against x = 0..4 -> slope -5, intercept 100.
        val fit = linearRegression(listOf(100.0, 95.0, 90.0, 85.0, 80.0))
        assertClose(-5.0, fit.slope, 1e-12)
        assertClose(100.0, fit.intercept, 1e-12)
        assertEquals(1.0, fit.rSquared)
    }

    @Test
    fun `r squared stays inside zero and one for seeded random walks`() {
        for (seed in 1L..25L) {
            val closes = TestSeries.seededWalk(300, seed).map { it.close }
            val fit = trendlineFit(closes)
            assertFalse(fit.rSquared.isNaN(), "NaN rSquared for seed $seed")
            assertTrue(fit.rSquared in 0.0..1.0, "rSquared=${fit.rSquared} for seed $seed")
            assertFalse(fit.slope.isNaN())
            assertFalse(fit.slope.isInfinite())
        }
    }

    @Test
    fun `trendlineFit uses the trailing thirty bar window`() {
        // A 40-bar series whose last 30 bars are a perfect ramp but whose first 10 are wild.
        val values = List(10) { 1000.0 - it * 7.0 } + List(30) { it.toDouble() }
        val fit = trendlineFit(values)
        // Window is the last 30 points, x re-based to 0..29, slope exactly 1 and R² exactly 1.
        assertEquals(1.0, fit.slope, 1e-9)
        assertEquals(1.0, fit.rSquared, 1e-9)
        assertEquals(0.0, fit.intercept, 1e-9)
    }

    @Test
    fun `trendlineFit on a shorter series fits everything it has`() {
        val fit = trendlineFit(listOf(1.0, 2.0, 3.0, 4.0))
        assertEquals(1.0, fit.slope, 1e-9)
        assertEquals(1.0, fit.rSquared, 1e-9)
    }

    @Test
    fun `trendlineFit on an empty or zero window series is degenerate`() {
        assertTrue(trendlineFit(emptyList()).isDegenerate)
        assertTrue(trendlineFit(listOf(1.0, 2.0), window = 0).isDegenerate)
        assertTrue(trendlineFit(listOf(1.0, 2.0), window = -5).isDegenerate)
    }

    @Test
    fun `trendlineFit of candles matches trendlineFit of their closes`() {
        val bars = TestSeries.seededWalk(80, seed = 99L)
        val fit = trendlineFitOf(bars)
        assertEquals(fit.slope, trendlineFit(bars.map { it.close }).slope)
        assertEquals(fit.rSquared, trendlineFit(bars.map { it.close }).rSquared)
        assertEquals(fit.intercept, trendlineFit(bars.map { it.close }).intercept)
    }

    @Test
    fun `at extrapolates the fitted line`() {
        val fit = linearRegression(listOf(1.0, 3.0, 2.0, 5.0, 4.0))
        // slope 0.8, intercept 1.4
        assertClose(1.4, fit.at(0.0))
        assertClose(2.2, fit.at(1.0))
        assertClose(4.6, fit.at(4.0))
        assertClose(5.4, fit.at(5.0))
    }

    @Test
    fun `regression is an alias of linearRegression`() {
        val values = listOf(1.0, 3.0, 2.0, 5.0, 4.0)
        assertEquals(linearRegression(values), regression(values))
    }
}