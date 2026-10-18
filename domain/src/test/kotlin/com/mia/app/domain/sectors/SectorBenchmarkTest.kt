package com.mia.app.domain.sectors

import com.mia.app.domain.model.Confidence
import com.mia.app.domain.model.Sector
import com.mia.app.domain.ratios.FreshnessPolicy
import com.mia.app.domain.ratios.RatioId
import com.mia.app.domain.ratios.RatioState
import com.mia.app.domain.ratios.SectorResolution
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The sample floor, which is the whole reason this package exists.
 *
 * ### What each test is guarding, in one line
 * * **The fresh-install case** — one stored page must not produce a median, because the median
 *   would *be* that page's number;
 * * **the floor itself** — two and three peers are refused too, and the refusal is an absence with
 *   a count rather than a value with a caveat;
 * * **the median itself** — odd and even samples, and the middle value rather than the mean;
 * * **stale peers** — excluded from the sample and counted separately, so the printed population
 *   always describes the sample actually used;
 * * **§7.3 outranks the data** — a suppressed ratio is never mediansed, whatever the peers hold.
 *
 * ### Why these are asserted against [SectorMedian]'s own `init` and not only here
 * `SectorMedian` refuses to be constructed with a sample below [SectorBenchmark.MINIMUM_SAMPLE], so
 * a future caller cannot manufacture a small-sample median and rely on the UI to caveat it. The
 * assertions below are about the *sentence*, which no type can enforce.
 */
class SectorBenchmarkTest {

    private val today = LocalDate.of(2026, 10, 16)

    // ------------------------------------------------------------------ the fresh-install case

    @Test
    fun `one stored page cannot produce a sector median`() {
        // The exact situation the feature exists for: a device that has only ever opened RELIANCE.
        val reliance = company("RELIANCE", Sector.OIL_GAS, roce = 18.4)
        val population = SectorPopulation.select(subject = reliance, cached = listOf(reliance))

        assertFalse(population.isComparable, "one page is not a peer group")
        assertEquals(
            SectorPopulation.NO_PEERS_REASON,
            population.notComparableReason,
        )

        val median = SectorBenchmark.median(Sector.OIL_GAS, RatioId.ROCE, population)
        assertFalse(median.isPublished)
        assertEquals(0, median.sampleSize)
        // The bug this exists to prevent: the subject's own number coming back as the sector's.
        assertNull(median.availability.value, "a withheld median must carry no value at all")
        assertFalse(
            median.note().contains("18.4"),
            "the withheld sentence must not contain the subject's own figure",
        )
    }

    @Test
    fun `the subject is never counted among its own peers`() {
        val subject = company("RELIANCE", Sector.OIL_GAS, roce = 18.4)
        val peers = listOf(
            subject,
            company("ONGC", Sector.OIL_GAS, roce = 10.0),
            company("IOC", Sector.OIL_GAS, roce = 12.0),
            company("BPCL", Sector.OIL_GAS, roce = 14.0),
            company("GAIL", Sector.OIL_GAS, roce = 16.0),
        )

        val population = SectorPopulation.select(subject = subject, cached = peers)

        assertFalse(
            population.members.any { it.symbol == subject.symbol },
            "the subject must not appear in its own peer group",
        )
        // Four peers — exactly the floor. If the subject were counted this would be five and the
        // median would sit above the middle peer.
        assertEquals(4, population.members.size)
        val median = SectorBenchmark.median(Sector.OIL_GAS, RatioId.ROCE, population)
        assertTrue(median.isPublished, "four peers is the floor and must publish")
        assertEquals(4, median.sampleSize)
        assertEquals(
            13.0,
            requireNotNull(median.availability.value),
            "peers 10/12/14/16 medians to 13 — the subject's 18.4 must not shift it",
        )
    }

    // --------------------------------------------------------------------------- the floor

    @Test
    fun `three peers are refused where four are published`() {
        val subject = company("RELIANCE", Sector.OIL_GAS, roce = 18.4)
        val threePeers = listOf(subject) + (1..3).map { i ->
            company("PEER$i", Sector.OIL_GAS, roce = 10.0 * i)
        }
        val refused = SectorBenchmark.median(
            Sector.OIL_GAS,
            RatioId.ROCE,
            SectorPopulation.select(subject, threePeers),
        )
        assertFalse(refused.isPublished, "n=3 is below the floor")
        assertNull(refused.availability.value)
        assertTrue(refused.note().contains("3 peers publish"), "got: ${refused.note()}")
        assertTrue(refused.note().contains("4"), "the sentence must name the floor")

        val fourPeers = listOf(subject) + (1..4).map { i ->
            company("PEER$i", Sector.OIL_GAS, roce = 10.0 * i)
        }
        val published = SectorBenchmark.median(
            Sector.OIL_GAS,
            RatioId.ROCE,
            SectorPopulation.select(subject, fourPeers),
        )
        assertTrue(published.isPublished, "n=4 is the floor and must publish")
        assertEquals(4, published.sampleSize)
    }

    @Test
    fun `a withheld median names the count it needs`() {
        val subject = company("RELIANCE", Sector.OIL_GAS, roce = 18.4)
        val population = SectorPopulation.select(
            subject,
            listOf(subject) + (1..2).map { company("PEER$it", Sector.OIL_GAS, roce = 10.0 * it) },
        )
        val note = SectorBenchmark.median(Sector.OIL_GAS, RatioId.ROCE, population).note()

        // "Not enough data" is a dead end; the count is an instruction.
        assertTrue(note.contains("2 peers publish"), "got: $note")
        assertTrue(note.contains("median needs 4"), "got: $note")
    }

    // -------------------------------------------------------------------------- the median

    @Test
    fun `the median is the middle peer, not the mean`() {
        assertEquals(3.0, SectorBenchmark.medianOf(listOf(1.0, 3.0, 5.0)))
        // An even sample takes the mean of the two middle values — the conventional definition, and
        // the only one continuous in the data as the peer count changes.
        assertEquals(2.5, SectorBenchmark.medianOf(listOf(1.0, 2.0, 4.0, 4.0)))
        assertEquals(7.0, SectorBenchmark.medianOf(listOf(7.0)))
    }

    @Test
    fun `one outlier cannot move the median the way it moves a mean`() {
        // The reason this is a median and not an average: a sector median is supposed to report the
        // middle company, and a single company with a provisioning blow-up should not define it.
        val sorted = listOf(8.0, 9.0, 10.0, 11.0, 900.0)
        val median = SectorBenchmark.medianOf(sorted)
        val mean = sorted.average()

        assertTrue(median < mean / 10, "median $median should be nowhere near the mean $mean")
    }

    @Test
    fun `a peer whose ratio column is empty does not count towards the sample`() {
        val subject = company("RELIANCE", Sector.OIL_GAS, roce = 18.4)
        val population = SectorPopulation.select(
            subject,
            listOf(
                subject,
                company("A", Sector.OIL_GAS, roce = 10.0),
                company("B", Sector.OIL_GAS, roce = 12.0),
                company("C", Sector.OIL_GAS, roce = 14.0),
                company("D", Sector.OIL_GAS, roce = null),
            ),
        )
        val median = SectorBenchmark.median(Sector.OIL_GAS, RatioId.ROCE, population)

        assertEquals(3, median.sampleSize, "three peers published the figure")
        assertEquals(3, median.peersWithFigure, "the empty peer contributes to neither count")
        assertFalse(median.isPublished, "three is below the floor")
    }

    // --------------------------------------------------------------------------- staleness

    @Test
    fun `stale peers are excluded from the sample and counted separately`() {
        val subject = company("RELIANCE", Sector.OIL_GAS, roce = 18.4)
        val old = LocalDate.of(2026, 1, 1)
        val population = SectorPopulation.select(
            subject,
            listOf(
                subject,
                company("A", Sector.OIL_GAS, roce = 10.0, asOf = today),
                company("B", Sector.OIL_GAS, roce = 12.0, asOf = today),
                company("C", Sector.OIL_GAS, roce = 14.0, asOf = today),
                company("D", Sector.OIL_GAS, roce = 99.0, asOf = old),
                company("E", Sector.OIL_GAS, roce = 99.0, asOf = old),
            ),
        )
        val median = SectorBenchmark.median(Sector.OIL_GAS, RatioId.ROCE, population)

        // Five peers have a figure; two of them are outside the window. Mixing them in would put a
        // figure from January into a median printed without that qualifier.
        assertEquals(2, median.excludedStale)
        assertEquals(3, median.sampleSize)
        assertFalse(median.isPublished, "only three fresh peers — below the floor")
        assertTrue(
            median.note().contains("older than the 7-day window"),
            "the sentence must say the excluded peers were excluded because they are old: " +
                median.note(),
        )
    }

    @Test
    fun `a median published over fresh peers never uses a stale figure`() {
        val subject = company("RELIANCE", Sector.OIL_GAS, roce = 18.4)
        val population = SectorPopulation.select(
            subject,
            listOf(
                subject,
                company("A", Sector.OIL_GAS, roce = 10.0, asOf = today),
                company("B", Sector.OIL_GAS, roce = 12.0, asOf = today),
                company("C", Sector.OIL_GAS, roce = 14.0, asOf = today),
                company("D", Sector.OIL_GAS, roce = 40.0, asOf = today),
                company("E", Sector.OIL_GAS, roce = 99.0, asOf = LocalDate.of(2026, 1, 1)),
            ),
        )
        val median = SectorBenchmark.median(Sector.OIL_GAS, RatioId.ROCE, population)

        assertTrue(median.isPublished)
        assertEquals(4, median.sampleSize)
        // 10/12/14/40 → 13.0. If the stale 99 had been admitted the median would be 27.
        assertEquals(13.0, requireNotNull(median.availability.value))
        assertEquals(1, median.excludedStale)
    }

    // -------------------------------------------------------------- §7.3 outranks the data

    @Test
    fun `a ratio suppressed for the sector is never mediansed`() {
        // Five healthy energy peers. Debt/equity is meaningful here, so use a sector where §7.3
        // removes the ratio outright: a bank has no debt/equity to compare against other banks.
        val subject = company("HDFCBANK", Sector.BANKING, roce = 10.0)
        val population = SectorPopulation.select(
            subject,
            listOf(subject) + (1..5).map { company("BANK$it", Sector.BANKING, roce = 10.0 * it) },
        )

        val median = SectorBenchmark.median(Sector.BANKING, RatioId.DEBT_TO_EQUITY, population)

        assertEquals(RatioState.SUPPRESSED_FOR_SECTOR, median.availability.state)
        assertEquals(0, median.sampleSize)
        assertNull(median.availability.value, "§7.3 removed the ratio; no value may survive")
        assertTrue(
            median.note().contains("not meaningful", ignoreCase = true),
            "the reason must be the sector's, not a data gap: ${median.note()}",
        )
    }

    @Test
    fun `an unsourced ratio is reported differently from a suppressed one`() {
        val subject = company("HDFCBANK", Sector.BANKING)
        val population = SectorPopulation.select(
            subject,
            listOf(subject) + (1..5).map { company("BANK$it", Sector.BANKING) },
        )

        val npa = SectorBenchmark.median(Sector.BANKING, RatioId.GNPA_NNPA, population)
        val debt = SectorBenchmark.median(Sector.BANKING, RatioId.DEBT_TO_EQUITY, population)

        assertEquals(RatioState.NOT_SOURCED, npa.availability.state)
        assertEquals(RatioState.SUPPRESSED_FOR_SECTOR, debt.availability.state)
        assertTrue(
            npa.note() != debt.note(),
            "'we cannot source it' and 'not meaningful here' must not read the same",
        )
    }

    // ------------------------------------------------------------------------ sector first

    @Test
    fun `peers are chosen by sector before any figure is looked for`() {
        val subject = company("HDFCBANK", Sector.BANKING, roce = 10.0)
        val population = SectorPopulation.select(
            subject,
            listOf(
                subject,
                company("SBIN", Sector.BANKING, roce = 12.0),
                company("ICICIBANK", Sector.BANKING, roce = 14.0),
                company("AXISBANK", Sector.BANKING, roce = 16.0),
                company("TCS", Sector.IT, roce = 90.0),
                company("INFY", Sector.IT, roce = 95.0),
                company("HINDUNILVR", Sector.FMCG, roce = 99.0),
            ),
        )

        // Three banking peers. The six non-banking rows would have carried a median an order of
        // magnitude away, which is plan.md §7.3's failure arriving through a different door.
        assertEquals(listOf("AXISBANK", "ICICIBANK", "SBIN"), population.members.map { it.symbol })
        val median = SectorBenchmark.median(Sector.BANKING, RatioId.ROCE, population)
        assertEquals(3, median.peersWithFigure)
        assertFalse(median.isPublished, "three banking peers is below the floor")
    }

    // ------------------------------------------------------------------ unresolved sectors

    @Test
    fun `an unresolved sector produces no population and says why`() {
        val subject = company("LICHSGFIN", Sector.OTHER, roce = 12.0, resolved = false)
        val population = SectorPopulation.select(
            subject,
            listOf(subject) + (1..6).map { company("PEER$it", Sector.OTHER, roce = 10.0 * it) },
        )

        assertFalse(population.isComparable)
        assertEquals(SectorPopulation.UNRESOLVED_REASON, population.notComparableReason)
        val median = SectorBenchmark.median(Sector.OTHER, RatioId.ROCE, population)
        assertFalse(median.isPublished)
        assertNull(median.availability.value)
        assertTrue(
            median.note().contains("could not classify"),
            "the reader must learn the sector is unresolved, not that data is thin: ${median.note()}",
        )
    }

    // -------------------------------------------------------------------------- the sentence

    @Test
    fun `every published median states its population`() {
        val subject = company("RELIANCE", Sector.OIL_GAS, roce = 18.4)
        val population = SectorPopulation.select(
            subject,
            listOf(subject) + (1..6).map { company("PEER$it", Sector.OIL_GAS, roce = 10.0 * it) },
        )
        val median = SectorBenchmark.median(Sector.OIL_GAS, RatioId.ROCE, population)

        val note = requireNotNull(median.populationNote)
        assertTrue(note.startsWith("n=6"), "got: $note")
        assertTrue(note.contains("6 other Oil & gas companies"), "got: $note")
        assertTrue(
            note.contains("this device holds a page for"),
            "the denominator must name where the peers came from: $note",
        )
    }

    // ------------------------------------------------------------------------- the fixture

    /** A stored page, resolved by hand so each test states its own classification. */
    private fun company(
        symbol: String,
        sector: Sector,
        roce: Double? = null,
        asOf: LocalDate = today,
        resolved: Boolean = true,
    ): SectorCompany = SectorCompany(
        symbol = symbol,
        name = "$symbol Ltd",
        resolution = SectorResolution(
            sector = sector,
            resolved = resolved,
            evidence = if (resolved) "test fixture: $sector" else "no keyword matched in $symbol",
            confidence = if (resolved) Confidence.AGREED else Confidence.SINGLE,
        ),
        asOf = asOf,
        isStale = FreshnessPolicy.inspect(
            asOf = asOf,
            today = today,
            kind = com.mia.app.domain.ratios.FreshnessKind.FUNDAMENTALS_SNAPSHOT,
        ).isStale,
        readings = mapOf(RatioId.ROCE to roce),
    )
}
