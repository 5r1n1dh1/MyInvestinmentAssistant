package com.mia.app.data.price

import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Parser resilience (plan.md §12: "Parser resilience tests: malformed HTML, missing fields,
 * changed class names… a parser must return a health flag, not an empty list").
 *
 * The invariant every test here defends: an unusable payload produces `health.ok == false`
 * **and** a reason, never a silent empty list.
 */
class NseBhavcopyParserResilienceTest {

    private val tradeDate = LocalDate.of(2026, 10, 1)

    // ------------------------------------------------------------ header normalisation

    @Test
    fun `lowercase whitespace padded and BOM prefixed headers parse identically`() {
        val messy = buildString {
            append('\uFEFF')
            append("  traddt , bizdt , isin ,  TCKRSYMB , sctysrs , opnpric , hghpric , lwpric , clspric , ttltradgvol \n")
            append("2026-10-01,2026-10-01,INE002A01018,RELIANCE,EQ,1180.10,1183.90,1160.80,1167.70,16771221\n")
        }

        val result = NseBhavcopyParser.parseCsv(messy)

        assertTrue(result.isUsable)
        assertEquals(1, result.candles.size)
        val row = assertNotNull(result.rows.single())
        assertEquals("RELIANCE", row.ticker)
        assertEquals("INE002A01018", row.isin)
        assertEquals(1167.70, row.close)
        assertEquals(16_771_221L, row.volume)
        // The header above is only a subset of the real one, so the health must say so rather
        // than quietly reporting a fully-normalised file.
        assertFalse(result.health.ok)
        assertTrue(result.health.hasMissingSections)
        assertEquals(0, result.rowsSkipped)
    }

    @Test
    fun `underscores and inner whitespace in a header name are ignored`() {
        val text = "Trad_Dt,Tckr_Symb,Scty Srs,Opn_Pric,Hgh_Pric,Lw_Pric,Cls_Pric,Ttl_Tradg_Vol\n" +
            "2026-10-01,RELIANCE,EQ,1180.10,1183.90,1160.80,1167.70,16771221\n"

        val result = NseBhavcopyParser.parseCsv(text)

        assertEquals(1, result.candles.size)
        assertEquals("RELIANCE", result.candles.single().symbol)
        assertEquals(1167.70, result.candles.single().close)
    }

    @Test
    fun `CRLF line endings and a trailing blank line parse cleanly`() {
        val text = "TradDt,TckrSymb,SctySrs,OpnPric,HghPric,LwPric,ClsPric,TtlTradgVol\r\n" +
            "2026-10-01,RELIANCE,EQ,1180.10,1183.90,1160.80,1167.70,16771221\r\n" +
            "2026-10-01,TCS,EQ,2052.60,2093.90,2045.10,2075.00,3428501\r\n" +
            "\r\n"

        val result = NseBhavcopyParser.parseCsv(text)

        assertEquals(2, result.candles.size)
        assertEquals(2, result.rowsTotal)
        assertEquals(0, result.rowsSkipped)
        assertEquals(tradeDate, result.tradDt)
    }

    @Test
    fun `an unknown extra column is ignored and does not shift any value`() {
        val text = "TradDt,SomethingNew,TckrSymb,SctySrs,OpnPric,HghPric,LwPric,ClsPric,TtlTradgVol\n" +
            "2026-10-01,zzz,RELIANCE,EQ,1180.10,1183.90,1160.80,1167.70,16771221\n"

        val result = NseBhavcopyParser.parseCsv(text)

        assertTrue(result.isUsable)
        val row = assertNotNull(result.rows.single())
        assertEquals("RELIANCE", row.ticker)
        assertEquals(1180.10, row.open)
        assertEquals(1160.80, row.low)
        assertEquals(1167.70, row.close)
    }

    @Test
    fun `a missing optional column parses but the health names what is missing`() {
        val text = "TradDt,TckrSymb,SctySrs,OpnPric,HghPric,LwPric,ClsPric,TtlTradgVol\n" +
            "2026-10-01,RELIANCE,EQ,1180.10,1183.90,1160.80,1167.70,16771221\n"

        val result = NseBhavcopyParser.parseCsv(text)

        // Still usable — the bars are correct.
        assertTrue(result.isUsable)
        assertEquals(1167.70, result.candles.single().close)
        // But not silently so (plan.md §12).
        assertFalse(result.health.ok)
        assertTrue(result.health.hasMissingSections)
        assertTrue(result.health.sectionsMissing.contains("ISIN"), "got ${result.health.sectionsMissing}")
        assertTrue(result.health.sectionsMissing.contains("NEWBRDLOTQTY"))
        assertFalse(result.health.sectionsMissing.contains("CLSPRIC"))
        assertTrue(result.health.notes.any { it.contains("ISIN") })
        // A missing optional column is not a malformed row.
        assertEquals(0, result.rowsSkipped)
        assertNull(result.candles.single().deliveryPercent)
    }

    @Test
    fun `a missing required column fails loudly instead of returning nothing`() {
        val text = "TradDt,TckrSymb,SctySrs,OpnPric,HghPric,LwPric,TtlTradgVol\n" +
            "2026-10-01,RELIANCE,EQ,1180.10,1183.90,1160.80,16771221\n"

        val result = NseBhavcopyParser.parseCsv(text)

        assertFalse(result.health.ok)
        assertTrue(result.candles.isEmpty())
        assertEquals(FetchFailure.UNPARSEABLE, result.fetchFailure)
        assertTrue(result.health.notes.single().contains("CLSPRIC"), "got ${result.health.notes}")
    }

    // ------------------------------------------------------------------- malformed rows

    @Test
    fun `a row with a non-numeric price is skipped counted and flagged`() {
        val text = "TradDt,TckrSymb,SctySrs,OpnPric,HghPric,LwPric,ClsPric,TtlTradgVol\n" +
            "2026-10-01,RELIANCE,EQ,1180.10,1183.90,1160.80,1167.70,16771221\n" +
            "2026-10-01,BADPRICE,EQ,1180.10,1183.90,1160.80,n/a,12345\n" +
            "2026-10-01,TCS,EQ,2052.60,2093.90,2045.10,2075.00,3428501\n"

        val result = NseBhavcopyParser.parseCsv(text)

        assertEquals(listOf("RELIANCE", "TCS"), result.candles.map { it.symbol })
        assertEquals(3, result.rowsTotal)
        assertEquals(1, result.rowsSkipped)
        assertEquals(1, result.skippedSamples.size)
        assertTrue(result.skippedSamples.single().startsWith("line 3: unparseable value"))
        assertTrue(result.skippedSamples.single().contains("BADPRICE"))
        assertFalse(result.health.ok, "skipped rows must degrade the health")
        assertEquals(2, result.candles.size)
    }

    @Test
    fun `a short row is skipped rather than read with shifted columns`() {
        val text = "TradDt,TckrSymb,SctySrs,OpnPric,HghPric,LwPric,ClsPric,TtlTradgVol\n" +
            "2026-10-01,RELIANCE,EQ,1180.10,1183.90,1160.80,1167.70,16771221\n" +
            "2026-10-01,SHORTROW,EQ,1180.10,1183.90\n"

        val result = NseBhavcopyParser.parseCsv(text)

        assertEquals(1, result.candles.size)
        assertEquals(1, result.rowsSkipped)
        assertTrue(result.skippedSamples.single().contains("expected 8 cells, got 5"))
    }

    @Test
    fun `an unparseable TradDt is a skip and not a guess`() {
        val text = "TradDt,TckrSymb,SctySrs,OpnPric,HghPric,LwPric,ClsPric,TtlTradgVol\n" +
            "01/10/2026,RELIANCE,EQ,1180.10,1183.90,1160.80,1167.70,16771221\n"

        val result = NseBhavcopyParser.parseCsv(text)

        assertTrue(result.candles.isEmpty())
        assertEquals(1, result.rowsSkipped)
        assertNull(result.tradDt)
    }

    @Test
    fun `skipped samples are capped so a broken file cannot flood memory`() {
        val text = buildString {
            append("TradDt,TckrSymb,SctySrs,OpnPric,HghPric,LwPric,ClsPric,TtlTradgVol\n")
            repeat(50) { append("2026-10-01,BAD$it,EQ,1,2,3,oops,1\n") }
        }

        val result = NseBhavcopyParser.parseCsv(text)

        assertEquals(50, result.rowsSkipped)
        assertEquals(NseBhavcopyParser.SKIPPED_SAMPLE_LIMIT, result.skippedSamples.size)
    }

    // ------------------------------------------------------------------ unusable payloads

    @Test
    fun `empty text fails with a reason and never an empty success`() {
        val result = NseBhavcopyParser.parseCsv("")

        assertFalse(result.health.ok)
        assertTrue(result.candles.isEmpty())
        assertEquals(FetchFailure.UNPARSEABLE, result.fetchFailure)
        assertTrue(result.health.notes.single().contains("no header line"))
    }

    @Test
    fun `whitespace only text fails with a reason`() {
        val result = NseBhavcopyParser.parseCsv("\n\n   \n")

        assertFalse(result.health.ok)
        assertTrue(result.health.notes.single().contains("no header line"))
    }

    @Test
    fun `garbage text with no recognisable columns fails naming the absent columns`() {
        val result = NseBhavcopyParser.parseCsv("<html><body>Access Denied</body></html>")

        assertFalse(result.health.ok)
        assertTrue(result.candles.isEmpty())
        assertEquals(FetchFailure.UNPARSEABLE, result.fetchFailure)
        assertTrue(result.health.notes.single().contains("TRADDT"))
    }

    @Test
    fun `a header with rows but nothing in the series filter fails with counts`() {
        val text = "TradDt,TckrSymb,SctySrs,OpnPric,HghPric,LwPric,ClsPric,TtlTradgVol\n" +
            "2026-10-01,SGBJUN28,GB,14848.00,14848.00,14712.00,14739.50,31\n"

        val result = NseBhavcopyParser.parseCsv(text)

        assertFalse(result.health.ok, "an empty result must never look healthy")
        assertTrue(result.candles.isEmpty())
        assertEquals(1, result.rowsTotal)
        assertEquals(0, result.rowsSkipped)
        assertTrue(result.health.notes.single().contains("series filter [EQ, SM]"))
    }

    @Test
    fun `a zip that is not a zip fails with a reason`() {
        val result = NseBhavcopyParser.parseZip("<html>403 Forbidden</html>".toByteArray())

        assertFalse(result.health.ok)
        assertTrue(result.candles.isEmpty())
        assertEquals(FetchFailure.UNPARSEABLE, result.fetchFailure)
        assertTrue(result.health.notes.single().contains("not a zip"))
    }

    @Test
    fun `an html error page served instead of the archive names its own bytes`() {
        val result = NseBhavcopyParser.parseZip("<html>403 Forbidden</html>".toByteArray())

        assertFalse(result.health.ok)
        // '<htm' in hex: the diagnostic proves what actually arrived.
        assertTrue(result.health.notes.single().contains("3C 68 74 6D"), "got ${result.health.notes}")
    }

    @Test
    fun `an empty byte array fails with a reason`() {
        val result = NseBhavcopyParser.parseZip(ByteArray(0))

        assertFalse(result.health.ok)
        assertTrue(result.health.notes.single().contains("0 bytes"))
    }

    @Test
    fun `a zip with no csv member fails naming what it did find`() {
        val result = NseBhavcopyParser.parseZip(zipOf("readme.txt" to "not a bhavcopy"))

        assertFalse(result.health.ok)
        assertTrue(result.health.notes.single().contains("no .csv member"))
        assertTrue(result.health.notes.single().contains("readme.txt"))
    }

    @Test
    fun `a zip with two csvs picks the lexicographically smallest name and says so`() {
        // Documented, deterministic rule: ZipInputStream yields archive order, which the writer
        // controls, so selection is by name instead.
        val archive = zipOf(
            "zz_second.csv" to Fixtures.bhavcopyCsv,
            "aa_first.csv" to minimalCsv("FIRSTROW"),
        )

        val result = NseBhavcopyParser.parseZip(archive)

        assertTrue(result.isUsable)
        assertEquals(listOf("FIRSTROW"), result.candles.map { it.symbol })
        assertTrue(
            result.health.notes.any { it.contains("more than one csv member") && it.contains("aa_first.csv") },
            "got ${result.health.notes}",
        )
    }

    @Test
    fun `a zip with two csvs picks the same member regardless of archive order`() {
        val first = zipOf(
            "aa_first.csv" to minimalCsv("FIRSTROW"),
            "zz_second.csv" to Fixtures.bhavcopyCsv,
        )
        val second = zipOf(
            "zz_second.csv" to Fixtures.bhavcopyCsv,
            "aa_first.csv" to minimalCsv("FIRSTROW"),
        )

        assertEquals(
            NseBhavcopyParser.parseZip(first).candles.map { it.symbol },
            NseBhavcopyParser.parseZip(second).candles.map { it.symbol },
        )
    }

    @Test
    fun `a csv nested in a folder inside the zip is still found`() {
        val result = NseBhavcopyParser.parseZip(zipOf("content/cm/BhavCopy.csv" to minimalCsv("NESTED")))

        assertEquals(listOf("NESTED"), result.candles.map { it.symbol })
    }

    // --------------------------------------------------------------------- csv splitting

    @Test
    fun `a quoted instrument name containing a comma does not shift the prices`() {
        // NSE does not quote today (verified: zero quote characters in 3,713 lines), but
        // FinInstrmNm is free text and this must never silently mis-price a stock.
        val text = "TradDt,TckrSymb,SctySrs,FinInstrmNm,OpnPric,HghPric,LwPric,ClsPric,TtlTradgVol\n" +
            "2026-10-01,ABC,EQ,\"ACME, HOLDINGS LTD\",10.00,12.00,9.00,11.00,5000\n"

        val result = NseBhavcopyParser.parseCsv(text)

        val row = assertNotNull(result.rows.single())
        assertEquals("ACME, HOLDINGS LTD", row.name)
        assertEquals(10.00, row.open)
        assertEquals(12.00, row.high)
        assertEquals(9.00, row.low)
        assertEquals(11.00, row.close)
        assertEquals(5000L, row.volume)
    }

    @Test
    fun `an escaped double quote inside a quoted field survives`() {
        assertEquals(listOf("A\"B", "C"), NseBhavcopyParser.splitCsvLine("\"A\"\"B\",C"))
    }

    @Test
    fun `a comma decimal separator is rejected instead of being silently mis-scaled`() {
        // Double.parseDouble is locale-independent, so a de-DE device cannot read "1.167,70"
        // as 1167.70 or 1.16770 — the row is skipped and counted.
        val text = "TradDt,TckrSymb,SctySrs,OpnPric,HghPric,LwPric,ClsPric,TtlTradgVol\n" +
            "2026-10-01,EURO,EQ,1.167,70,1.200,00,1.100,00,1.150,00,1000\n"

        val result = NseBhavcopyParser.parseCsv(text)

        assertTrue(result.candles.isEmpty())
        assertEquals(1, result.rowsSkipped)
    }

    @Test
    fun `header normalisation is stable across casing whitespace and BOM`() {
        assertEquals("ISIN", NseBhavcopyParser.normaliseHeader(" isin "))
        assertEquals("ISIN", NseBhavcopyParser.normaliseHeader("\uFEFFISIN"))
        assertEquals("FININSTRMID", NseBhavcopyParser.normaliseHeader(" Fin Instrm Id "))
        assertEquals("FININSTRMNM", NseBhavcopyParser.normaliseHeader("FinInstrmNm"))
        assertEquals("TCKRSYMB", NseBhavcopyParser.normaliseHeader("Tckr_Symb"))
    }

    @Test
    fun `rows after a blank first line are still parsed`() {
        val text = "\n\nTradDt,TckrSymb,SctySrs,OpnPric,HghPric,LwPric,ClsPric,TtlTradgVol\n" +
            "2026-10-01,RELIANCE,EQ,1180.10,1183.90,1160.80,1167.70,16771221\n"

        val result = NseBhavcopyParser.parseCsv(text)

        assertEquals(1, result.candles.size)
        assertEquals(1, result.rowsTotal)
    }

    @Test
    fun `withoutRows drops the raw copy but keeps the candles and health`() {
        val result = NseBhavcopyParser.parseZip(Fixtures.bhavcopyZip).withoutRows()

        assertEquals(3051, result.candles.size)
        assertEquals(0, result.rowsKept)
        assertTrue(result.health.ok)
        assertEquals(tradeDate, result.tradDt)
    }

    // ------------------------------------------------------------------------- helpers

    private fun minimalCsv(ticker: String) =
        "TradDt,TckrSymb,SctySrs,OpnPric,HghPric,LwPric,ClsPric,TtlTradgVol\n" +
            "2026-10-01,$ticker,EQ,10.00,12.00,9.00,11.00,5000\n"

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, body) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(body.toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
