package com.mia.app.domain.sectors

import com.mia.app.domain.compliance.AdviceLanguage
import com.mia.app.domain.compliance.HonestyContract
import com.mia.app.domain.model.Confidence
import com.mia.app.domain.model.Sector
import com.mia.app.domain.ratios.RatioId
import com.mia.app.domain.ratios.RatioPriority
import com.mia.app.domain.ratios.RatioState
import com.mia.app.domain.ratios.SectorResolution
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The machine-checked half of the sector view: the properties that make it honest rather than
 * merely accurate.
 *
 * ### Why this file exists separately from the behaviour tests
 * `SectorBenchmarkTest` pins what the numbers *are*. This pins what they are allowed to *claim*, and
 * it reads the strings off the objects that own them, so editing a sentence fails here rather than
 * on a device.
 *
 * ### What each test guards, in one line
 * * **advice language** — no sentence this screen can render survives
 *   [AdviceLanguage.findForbiddenPhrases], across an energy sector *and* a bank;
 * * **no verdict** — the screen's own vocabulary contains no word that ranks one figure against
 *   another. Absence of a difference column is not enough: adjectives reach the same conclusion;
 * * **the subject's own figure** — the left column is the stored number, verbatim, so nothing can
 *   quietly turn it into a ratio-to-median;
 * * **sample size** — every published median's sentence carries an `n`, the same obligation
 *   [HonestyContract] places on a hit rate.
 */
class SectorComplianceTest {

    private val today = LocalDate.of(2026, 10, 16)

    // ------------------------------------------------------------------- advice language

    @Test
    fun `no sentence this screen renders is advice`() {
        val texts = mutableListOf(
            SectorPopulation.UNRESOLVED_REASON,
            SectorPopulation.NO_PEERS_REASON,
            SectorComparisonBuilder.POPULATION_DISCLOSURE,
            SectorComparisonBuilder.NO_VERDICT_NOTE,
            SectorComparisonBuilder.STALE_NOTE,
        )

        // An energy sector and a bank: §7.3's suppression and not-sourced notes are user-facing
        // sentences too, and they are the ones most likely to be reworded carelessly.
        texts += renderableNotes(Sector.OIL_GAS, oilGasCached())
        texts += renderableNotes(Sector.BANKING, bankingCached())

        assertTrue(texts.size >= 10, "the fixtures must produce sentences to check: ${texts.size}")

        texts.forEach { text ->
            val hits = AdviceLanguage.findForbiddenPhrases(text)
            assertTrue(hits.isEmpty(), "advice-like phrasing in \"${text.take(140)}\": $hits")
            HonestyContract.requireNotProbabilityClaim(text)
            assertTrue(text.isNotBlank(), "a blank is the misleading blank of plan.md §13")
        }
    }

    /**
     * Every sentence the screen could put on a median cell, for one sector.
     *
     * Walks the sector's **composed** slot list rather than the plan's raw twelve, because §7.3
     * adds slots and suppresses others: a check that read `RatioPriority.PLAN_ORDER` would never see
     * the bank suppression notes, which are exactly the sentences most worth policing.
     */
    private fun renderableNotes(sector: Sector, cached: List<SectorCompany>): List<String> {
        val population = SectorPopulation.select(subject = cached.first(), cached = cached)
        return RatioPriority.order(sector).flatMap { slot ->
            val median = SectorBenchmark.median(sector, slot.id, population)
            listOfNotNull(median.populationNote, median.note())
        } + population.populationSummary()
    }

    // ------------------------------------------------------------------------- no verdict

    @Test
    fun `the screen's own copy contains no ranking vocabulary`() {
        val copy = listOf(
            SectorComparisonBuilder.POPULATION_DISCLOSURE,
            SectorComparisonBuilder.NO_VERDICT_NOTE,
            SectorPopulation.UNRESOLVED_REASON,
            SectorPopulation.NO_PEERS_REASON,
        ).joinToString(" ").lowercase()

        // These are the words that turn two measurements into a conclusion. No arithmetic can reach
        // one; an adjective can, which is why the ban is on the vocabulary and not only on the code.
        val verdictWords = listOf(
            "above average", "below average", "better than", "worse than", "undervalued",
            "overvalued", "cheap", "expensive", "attractive", "strong", "weak", "outperform",
            "underperform", "ranks", "ranked", "best", "worst", "beats", "impressive",
        )
        val found = verdictWords.filter { copy.contains(it) }

        assertTrue(found.isEmpty(), "ranking vocabulary in the screen's copy: $found")
    }

    @Test
    fun `the subject column is the subject's own stored figure, verbatim`() {
        val subject = company("RELIANCE", Sector.OIL_GAS, roce = 18.4)
        val comparison = SectorComparisonBuilder.build(subject, oilGasCached())

        val roce = comparison.rows.first { it.slot.id == RatioId.ROCE }
        val stockValue = requireNotNull(roce.stock.first().availability.value)
        val medianValue = requireNotNull(roce.benchmarks.first().availability.value)

        // Both figures are present and neither has been combined with the other. If a future change
        // made the left column a ratio-to-median, or a percentile, this fails — which is the point:
        // the subtraction is the one thing this screen refuses to perform for the reader.
        assertEquals(18.4, stockValue, "the left column must be the stored figure, not a derived one")
        assertTrue(
            stockValue != medianValue,
            "the fixture must produce two different numbers for this to mean anything",
        )
        // Seven stored pages, one of which is the subject — so six peers, and the subject's own
        // 18.4 is not one of the six numbers behind the median.
        assertEquals(6, roce.benchmarks.first().sampleSize)
    }

    @Test
    fun `rows follow the sector's own ratio plan`() {
        // A bank leads with ROE and its §7.3 bank slots and has no debt/equity row at all, so the
        // sector view and the Fundamentals tab cannot disagree about which ratios a sector has.
        val bankRows = SectorComparisonBuilder
            .build(company("HDFCBANK", Sector.BANKING, roce = 10.0), bankingCached())
            .rows
            .map { it.slot.id }

        assertEquals(
            RatioPriority.order(Sector.BANKING).map { it.id.name },
            bankRows.map { it.name },
        )
        assertTrue(
            bankRows.none { it == RatioId.DEBT_TO_EQUITY },
            "§7.3 removes a bank's debt/equity; the row must not exist at all",
        )
        assertTrue(
            bankRows.contains(RatioId.NET_INTEREST_MARGIN),
            "§7.3's bank slots must lead the list even though no source publishes them",
        )
    }

    // ---------------------------------------------------------------------- sample sizes

    @Test
    fun `every published median states its sample size`() {
        val cached = oilGasCached()
        val population = SectorPopulation.select(subject = cached.first(), cached = cached)
        val published = RatioPriority.order(Sector.OIL_GAS)
            .map { slot -> SectorBenchmark.median(Sector.OIL_GAS, slot.id, population) }
            .filter { it.isPublished }

        assertTrue(published.isNotEmpty(), "the fixture must publish at least one median")

        published.forEach { median ->
            val note = requireNotNull(median.populationNote)
            // The same obligation the contract places on a hit rate: a rate without its sample size
            // is not a smaller claim, it is a different claim.
            HonestyContract.requireHitRateSampleSize(note)
            assertTrue(
                note.contains("this device holds a page for"),
                "the denominator must say where the peers came from: $note",
            )
        }
    }

    @Test
    fun `an unpublished median carries a count and no figure`() {
        // Two peers: every ratio is refused, and every refusal explains itself.
        val subject = company("RELIANCE", Sector.OIL_GAS, roce = 18.4)
        val cached = listOf(
            subject,
            company("A", Sector.OIL_GAS, roce = 10.0),
            company("B", Sector.OIL_GAS, roce = 12.0),
        )
        val comparison = SectorComparisonBuilder.build(subject, cached)
        var refusals = 0

        comparison.rows.forEach { row ->
            row.benchmarks.forEach { median ->
                if (median.isPublished) return@forEach
                refusals++
                assertTrue(
                    median.sampleSize < SectorBenchmark.MINIMUM_SAMPLE,
                    "${median.id}: a withheld median is always below the floor, got " +
                        median.sampleSize,
                )
                assertTrue(
                    median.availability.value == null,
                    "${median.id}: an unpublished median must carry no value",
                )
                assertTrue(median.note().isNotBlank(), "${median.id}: a blank must never be rendered")
            }
        }

        assertTrue(refusals > 0, "two peers must produce at least one refusal")
    }

    @Test
    fun `every absence on the screen is a state that explains itself`() {
        val cached = oilGasCached()
        val comparison = SectorComparisonBuilder.build(subject = cached.first(), cached = cached)

        comparison.rows.forEach { row ->
            row.stock.forEach { value ->
                if (value.availability.state != RatioState.AVAILABLE) {
                    assertTrue(
                        value.availability.note?.isNotBlank() == true,
                        "${value.id}: every non-available state must say why (plan.md §7.3)",
                    )
                    assertTrue(value.absenceText().isNotBlank())
                }
            }
        }
    }

    // ------------------------------------------------------------------------- fixtures

    /** Seven oil & gas pages: the subject plus six peers, so ROCE clears the floor. */
    private fun oilGasCached(): List<SectorCompany> = listOf(
        company("RELIANCE", Sector.OIL_GAS, roce = 18.4),
    ) + (1..6).map { company("PEER$it", Sector.OIL_GAS, roce = 10.0 * it) }

    private fun bankingCached(): List<SectorCompany> = listOf(
        company("HDFCBANK", Sector.BANKING, roce = 10.0),
    ) + (1..6).map { company("BANK$it", Sector.BANKING, roce = 10.0 * it) }

    private fun company(symbol: String, sector: Sector, roce: Double?): SectorCompany =
        SectorCompany(
            symbol = symbol,
            name = "$symbol Ltd",
            resolution = SectorResolution(
                sector = sector,
                resolved = true,
                evidence = "test fixture: $sector",
                confidence = Confidence.AGREED,
            ),
            asOf = today,
            isStale = false,
            readings = mapOf(RatioId.ROCE to roce),
        )
}
