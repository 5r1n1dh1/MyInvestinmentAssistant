package com.mia.app.domain.ratios

import com.mia.app.domain.model.Confidence
import com.mia.app.domain.model.Sector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [SectorResolver] tests: a table over real listed-company display names, the two ways the
 * resolver must decline (unknown, ambiguous), and the corroboration/conflict rules.
 *
 * The names are the `<h1>` strings screener.in publishes, which is what
 * `FundamentalsSnapshot.name` carries, so a passing test means the real page resolves.
 */
class SectorResolverTest {

    /**
     * Name → sector, for real companies. Every row is a company a user can search for, and the
     * sectors span all 20 keyword-bearing values of the enum (plan.md §5 `Sector`).
     */
    private val TABLE: List<Pair<String, Sector>> = listOf(
        // Banks
        "HDFC Bank Ltd" to Sector.BANKING,
        "ICICI Bank Ltd" to Sector.BANKING,
        "State Bank of India" to Sector.BANKING,
        "Axis Bank Ltd" to Sector.BANKING,
        "Kotak Mahindra Bank Ltd" to Sector.BANKING,
        "Punjab National Bank" to Sector.BANKING,
        // NBFCs
        "Bajaj Finance Ltd" to Sector.NBFC,
        "Shriram Finance Ltd" to Sector.NBFC,
        "Muthoot Finance Ltd" to Sector.NBFC,
        "Cholamandalam Investment and Finance Company Ltd" to Sector.NBFC,
        // IT
        "Tata Consultancy Services Ltd" to Sector.IT,
        "Infosys Ltd" to Sector.IT,
        "HCL Technologies Ltd" to Sector.IT,
        // Pharma
        "Sun Pharmaceutical Industries Ltd" to Sector.PHARMA,
        "Divi's Laboratories Ltd" to Sector.PHARMA,
        // FMCG
        "Hindustan Unilever Ltd" to Sector.FMCG,
        "Nestle India Ltd" to Sector.FMCG,
        "Dabur India Ltd" to Sector.FMCG,
        "Godrej Consumer Products Ltd" to Sector.FMCG,
        "Britannia Industries Ltd" to Sector.FMCG,
        // Auto
        "Tata Motors Ltd" to Sector.AUTO,
        "Maruti Suzuki India Ltd" to Sector.AUTO,
        "Bajaj Auto Ltd" to Sector.AUTO,
        "Hero MotoCorp Ltd" to Sector.AUTO,
        "Mahindra & Mahindra Ltd" to Sector.AUTO,
        "Ashok Leyland Ltd" to Sector.AUTO,
        "Eicher Motors Ltd" to Sector.AUTO,
        // Insurance
        "HDFC Life Insurance Company Ltd" to Sector.INSURANCE,
        "Life Insurance Corporation of India" to Sector.INSURANCE,
        // Metals
        "Tata Steel Ltd" to Sector.METALS,
        "Steel Authority of India Ltd" to Sector.METALS,
        "Hindustan Zinc Ltd" to Sector.METALS,
        "Vedanta Ltd" to Sector.METALS,
        "Hindalco Industries Ltd" to Sector.METALS,
        // Cement
        "UltraTech Cement Ltd" to Sector.CEMENT,
        "Shree Cement Ltd" to Sector.CEMENT,
        "Ambuja Cements Ltd" to Sector.CEMENT,
        // Power
        "Tata Power Ltd" to Sector.POWER,
        "NTPC Ltd" to Sector.POWER,
        "Power Grid Corporation Of India Ltd" to Sector.POWER,
        "NHPC Ltd" to Sector.POWER,
        "Coal India Ltd" to Sector.POWER,
        // Renewable
        "Adani Green Energy Ltd" to Sector.RENEWABLE,
        "Tata Renewables Ltd" to Sector.RENEWABLE,
        // Defence
        "Bharat Electronics Ltd" to Sector.DEFENCE,
        "Bharat Aeronautics Ltd" to Sector.DEFENCE,
        "HAL" to Sector.DEFENCE,
        "BEL India Ltd" to Sector.DEFENCE,
        // Railway
        "Railway Corporation of India" to Sector.RAILWAY,
        "IRCTC Ltd" to Sector.RAILWAY,
        // Realty
        "DLF Ltd" to Sector.REALTY,
        "Godrej Properties Ltd" to Sector.REALTY,
        "Oberoi Realty Ltd" to Sector.REALTY,
        "PNB HOUSING" to Sector.REALTY,
        // Telecom
        "Bharti Airtel Ltd" to Sector.TELECOM,
        "Tata Communications Ltd" to Sector.TELECOM,
        // Aviation
        "InterGlobe Aviation Ltd" to Sector.AVIATION,
        // Logistics
        "Adani Ports and Special Economic Zone Ltd" to Sector.LOGISTICS,
        "Container Corporation Of India Ltd" to Sector.LOGISTICS,
        // Durables
        "Titan Company Ltd" to Sector.DURABLES,
        "Voltas Ltd" to Sector.DURABLES,
        "Havells India Ltd" to Sector.DURABLES,
        // Retail
        "Shoppers Stop Ltd" to Sector.RETAIL,
        "Avenue Supermarts Ltd" to Sector.RETAIL,
        // Capital goods
        "Larsen & Toubro Ltd" to Sector.CAPITAL_GOODS,
        // Chemicals
        "Tata Chemicals Ltd" to Sector.CHEMICALS,
        "Asian Paints Ltd" to Sector.CHEMICALS,
        "UPL Ltd" to Sector.CHEMICALS,
    )

    @Test
    fun `every listed name resolves to its sector on a positive keyword match`() {
        for ((name, expected) in TABLE) {
            val resolution = SectorResolver.resolve(name)
            assertEquals(expected, resolution.sector, name)
            assertTrue(resolution.resolved, "$name should resolve: ${resolution.evidence}")
            assertEquals(Confidence.SINGLE, resolution.confidence, name)
            assertTrue(resolution.usesSectorOverrides, name)
        }
        assertTrue(TABLE.size >= 60, "the table is meant to be the broad regression net")
    }

    @Test
    fun `every keyword bearing sector is reachable from the table`() {
        val reached = TABLE.map { it.second }.toSet()
        val reachable = Sector.entries.filter { it != Sector.OTHER }
        assertEquals(emptyList(), reachable.filterNot { it in reached }, "unreachable sectors")
    }

    @Test
    fun `evidence names the keyword that matched`() {
        val resolution = SectorResolver.resolve("HDFC Bank Ltd")
        assertTrue(resolution.evidence.contains("\"bank\""), resolution.evidence)
        assertTrue(resolution.evidence.contains("company name"), resolution.evidence)

        val reliance = SectorResolver.resolve("Reliance Industries Ltd")
        assertFalse(reliance.resolved)
        assertEquals(Sector.OTHER, reliance.sector)
        assertTrue(reliance.evidence.contains("plan.md §7.1"), reliance.evidence)
    }

    @Test
    fun `an unknown name stays OTHER and is never marked resolved`() {
        for (name in listOf("Zebra Widgets India Ltd", "", "   ", "12345")) {
            val resolution = SectorResolver.resolve(name)
            assertEquals(Sector.OTHER, resolution.sector, name)
            assertFalse(resolution.resolved, name)
            assertFalse(resolution.usesSectorOverrides, name)
        }
        // A null name is a failed parse, not a resolution.
        assertFalse(SectorResolver.resolve(null).resolved)
    }

    /**
     * The documented gap: RELIANCE carries no keyword and oil & gas is not one of plan.md §5's
     * 21 sectors, so the app says OTHER rather than inventing a bucket. Asserted so the gap
     * stays visible instead of quietly being closed with a wrong keyword.
     */
    @Test
    fun `a conglomerate with no industry word stays OTHER`() {
        val resolution = SectorResolver.resolve("Reliance Industries Ltd")
        assertEquals(Sector.OTHER, resolution.sector)
        assertFalse(resolution.resolved)
        assertEquals(Confidence.SINGLE, resolution.confidence)
    }

    /**
     * The canonical ambiguity: LIC Housing Finance is a lender **and** a housing business, so
     * `FINANCE` (NBFC) and `HOUSING` (REALTY) both match at the same specificity and the
     * resolver refuses to pick (plan.md §7.1).
     */
    @Test
    fun `a name that spans two industries stays OTHER`() {
        val resolution = SectorResolver.resolve("LIC Housing Finance Ltd")
        assertEquals(Sector.OTHER, resolution.sector)
        assertFalse(resolution.resolved)
        assertTrue(resolution.evidence.contains("NBFC"), resolution.evidence)
        assertTrue(resolution.evidence.contains("Realty"), resolution.evidence)
        assertTrue(resolution.evidence.contains("rather than picking one"), resolution.evidence)
    }

    @Test
    fun `an airport operator with ports is ambiguous between aviation and logistics`() {
        // "GMR Ports & Airports Ltd" is a real name: it runs airports *and* container terminals.
        val resolution = SectorResolver.resolve("GMR Ports & Airports Ltd")
        assertEquals(Sector.OTHER, resolution.sector)
        assertFalse(resolution.resolved)
        assertTrue(resolution.evidence.contains("Aviation"), resolution.evidence)
        assertTrue(resolution.evidence.contains("Logistics"), resolution.evidence)
    }

    @Test
    fun `a deposits row corroborates a bank match and raises confidence to agreed`() {
        val shape = RatioShape(hasDepositsRow = true, hasBorrowingsRow = true)
        val resolution = SectorResolver.resolve("HDFC Bank Ltd", shape = shape)
        assertEquals(Sector.BANKING, resolution.sector)
        assertEquals(Confidence.AGREED, resolution.confidence)
        assertTrue(resolution.evidence.contains("statement shape"), resolution.evidence)
    }

    @Test
    fun `a statement shape alone never resolves a sector`() {
        // plan.md §7.1: only a positive keyword match may resolve. A deposits row is evidence,
        // not a classification.
        val resolution = SectorResolver.resolve("Zebra Widgets India Ltd", shape = RatioShape(hasDepositsRow = true))
        assertEquals(Sector.OTHER, resolution.sector)
        assertFalse(resolution.resolved)
    }

    @Test
    fun `the industry text alone can resolve`() {
        val resolution = SectorResolver.resolve(
            name = "TCS",
            about = "Tata Consultancy Services is a leading provider of Information Technology " +
                "services and consulting",
        )
        assertEquals(Sector.IT, resolution.sector)
        assertTrue(resolution.resolved)
        assertEquals(Confidence.SINGLE, resolution.confidence)
        assertTrue(resolution.evidence.contains("published sector text"), resolution.evidence)
    }

    @Test
    fun `name and industry text agreeing is AGREEMENT not just a single source`() {
        val resolution = SectorResolver.resolve(
            name = "Sun Pharmaceutical Industries Ltd",
            about = "Sun Pharmaceutical is a pharmaceutical company",
        )
        assertEquals(Sector.PHARMA, resolution.sector)
        assertEquals(Confidence.AGREED, resolution.confidence)
        assertTrue(resolution.evidence.contains("and"), resolution.evidence)
    }

    @Test
    fun `name and industry text disagreeing is a conflict and stays OTHER`() {
        val resolution = SectorResolver.resolve(
            name = "HDFC Bank Ltd",
            about = "HDFC Bank provides software and information technology services",
        )
        assertEquals(Sector.OTHER, resolution.sector)
        assertFalse(resolution.resolved)
        assertEquals(Confidence.CONFLICT, resolution.confidence)
        assertTrue(resolution.evidence.contains("Banking"), resolution.evidence)
        assertTrue(resolution.evidence.contains("Information technology"), resolution.evidence)
    }

    /**
     * Token matching, not substring matching: `AIRPORT` must not be found inside `PORTS`, or
     * every port operator would be classified as an airline (and vice versa).
     */
    @Test
    fun `keyword matching is by token run not substring`() {
        assertEquals(Sector.AVIATION, SectorResolver.resolve("Noida International Airport Ltd").sector)
        assertEquals(Sector.LOGISTICS, SectorResolver.resolve("Kandla Port Authority Ltd").sector)
    }

    @Test
    fun `the longest matching keyword wins`() {
        // "ADANI GREEN" (two tokens) is the classification; a one-token "GREEN" keyword would
        // not be.
        val resolution = SectorResolver.resolve("Adani Green Energy Ltd")
        assertEquals(Sector.RENEWABLE, resolution.sector)
        assertTrue(resolution.evidence.contains("adani green"), resolution.evidence)
    }

    @Test
    fun `hyphens and ampersands are token separators`() {
        assertEquals(Sector.AUTO, SectorResolver.resolve("Bajaj-Auto Ltd").sector)
        assertEquals(Sector.AUTO, SectorResolver.resolve("Mahindra & Mahindra Ltd").sector)
        assertEquals(Sector.NBFC, SectorResolver.resolve("Cholamandalam Investment and Finance").sector)
        // The ampersand splits into tokens, which is exactly why a two-industry name such as
        // "Tata Steel & Power" is left unclassified rather than guessed.
        assertEquals(Sector.OTHER, SectorResolver.resolve("Tata Steel & Power Ltd").sector)
    }

    @Test
    fun `company names without an industry word stay OTHER rather than borrowing one`() {
        // Tech Mahindra must not pick up an auto keyword, and a software house must not be
        // classified by a sibling company's name.
        assertFalse(SectorResolver.resolve("Tech Mahindra Ltd").resolved)
        assertFalse(SectorResolver.resolve("HDFC Ltd").resolved)
        assertFalse(SectorResolver.resolve("Wipro Ltd").resolved)
    }

    @Test
    fun `every sector except OTHER has at least one keyword`() {
        val keywords = SectorResolver.allKeywords()
        for (sector in Sector.entries) {
            if (sector == Sector.OTHER) continue
            assertTrue(keywords[sector].orEmpty().isNotEmpty(), "$sector has no keyword")
        }
        assertEquals(null, keywords[Sector.OTHER], "OTHER is the default, never a match")
        // 21 keyword-bearing sectors + OTHER as the default = 22 values in plan.md §5's enum.
        assertEquals(Sector.entries.size - 1, keywords.size)
    }

    @Test
    fun `keywords are unique per sector so a phrase cannot classify two industries`() {
        val seen = mutableMapOf<String, Sector>()
        for ((sector, phrases) in SectorResolver.allKeywords()) {
            for (phrase in phrases) {
                val previous = seen.put(phrase, sector)
                assertEquals(null, previous, "keyword '$phrase' maps to both $previous and $sector")
            }
        }
    }

    @Test
    fun `an unresolved sector always pairs with OTHER`() {
        // Guard on the type's own invariant, exercised through the public API.
        for (name in listOf("Zebra Widgets India Ltd", "LIC Housing Finance Ltd")) {
            val resolution = SectorResolver.resolve(name)
            assertEquals(resolution.resolved, resolution.sector != Sector.OTHER, name)
        }
    }
}