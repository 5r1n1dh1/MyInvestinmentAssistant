package com.mia.app.data.symbols

import com.mia.app.data.price.BhavcopyParseResult
import com.mia.app.data.price.NseBhavcopyParser
import com.mia.app.domain.model.ParserHealth
import java.io.ByteArrayInputStream
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * The real captured files, loaded once per JVM.
 *
 * Golden tests must run against the shipped bytes, not against a hand-written stand-in: the
 * whole reason this package exists is that the real files have leading-space headers, an
 * undocumented format and 2,593 real-world tickers. A synthetic fixture would have tested
 * nothing that matters.
 */
object SymbolFixtures {

    /** Real NSE symbol master, bundled at `data/src/main/resources/assets/EQUITY_L.csv`. */
    const val MASTER_RESOURCE: String = "assets/EQUITY_L.csv"

    /** Real NSE EOD bhavcopy for 2026-10-01, 3,712 instruments. */
    const val BHAVCOPY_RESOURCE: String = "fixtures/bhavcopy_20261001.csv"

    /** The one trading session in the fixture set. */
    val TRADING_DAY: LocalDate = LocalDate.parse("2026-10-01")

    private val masterParse: SymbolBookParseResult by lazy {
        SymbolBookParser.parse(resource(MASTER_RESOURCE))
    }

    private val master: SymbolBook by lazy { SymbolBook.of(masterParse) }

    private val bhavcopy: BhavcopyParseResult by lazy {
        NseBhavcopyParser.parseCsv(
            text(BHAVCOPY_RESOURCE),
            NseBhavcopyParser.DEFAULT_SERIES,
        )
    }

    /** Parsed master: the real 2,593 rows. */
    fun masterParse(): SymbolBookParseResult = masterParse

    /** The real, fully indexed symbol book. */
    fun book(): SymbolBook = master

    /** A [SymbolResolver] over the real book with the seeded corporate-action aliases. */
    fun resolver(tracker: CorporateActionTracker? = null): SymbolResolver = SymbolResolver(master, tracker = tracker)

    /** The real bhavcopy parse (`EQ` + `SM` kept, so 3,051 rows). */
    fun bhavcopy(): BhavcopyParseResult = bhavcopy

    /** The real reconciliation of the master against the 2026-10-01 bhavcopy. */
    fun reconciliation(): ReconciliationReport = BhavcopyReconciler.reconcile(master, bhavcopy)

    fun resource(name: String) =
        SymbolFixtures::class.java.classLoader.getResourceAsStream(name)
            ?: error("fixture '$name' is not on the test classpath")

    fun text(name: String) = resource(name).reader(Charsets.UTF_8).readText()

    /** Reads master CSV from a string, for the resilience tests. */
    fun parseMaster(csv: String) = SymbolBookParser.parse(csv)

    /** An [AssetReader] over an in-memory string — the production seam, faked. */
    fun assetReader(name: String, content: String): AssetReader = AssetReader { requested ->
        if (requested == name) ByteArrayInputStream(content.toByteArray(Charsets.UTF_8)) else null
    }

    /**
     * A synthetic session calendar of weekdays between [from] and [to], inclusive.
     *
     * **Deliberately not the exchange calendar.** The real one is unavailable here (it comes from
     * an NSE endpoint, `:data` test fixtures do not carry it) and inventing a 21-holiday list to
     * make the count land on 372 would be fabricating data. Weekdays are honest and deterministic,
     * and every assertion built on top of them is computed from [sessions]' own length, so the
     * expected values are exact even though the calendar is not the real one.
     *
     * The *real* shape of the demerger — 140 sessions of `TATAMOTORS` then 232 without it, last
     * seen 2025-10-23 — is asserted separately as literal constants in
     * `TataMotorsCorporateActionTest`, where it comes from
     * `domain/src/test/resources/fixtures/eod_history.json`.
     */
    fun sessions(from: LocalDate, to: LocalDate): List<LocalDate> {
        val out = ArrayList<LocalDate>(512)
        var day = from
        while (!day.isAfter(to)) {
            if (day.dayOfWeek != DayOfWeek.SATURDAY && day.dayOfWeek != DayOfWeek.SUNDAY) out += day
            day = day.plusDays(1)
        }
        return out
    }

    /** Builds a snapshot from bare `(symbol, isin, series)` triples — the shape tests hand-build. */
    fun snapshot(
        date: LocalDate,
        entries: List<Triple<String, String, String>>,
        health: ParserHealth = ParserHealth.healthy("test snapshot"),
    ) = PresenceSnapshot.fromIdentities(
        date,
        entries.map { SymbolIdentity(it.first, it.second, it.third) },
        health,
    )

    /** One symbol's identity, as `(symbol, isin, series)`. */
    fun id(symbol: String, isin: String, series: String = "EQ") = Triple(symbol, isin, series)

    /** A hand-built record for the resilience/ladder tests, where the master is not the subject. */
    fun record(
        symbol: String,
        name: String = "$symbol Limited",
        series: String = "EQ",
        isin: String = "INEZZZ0000${symbol.take(1)}",
        listingDate: LocalDate? = null,
    ) = SymbolRecord(
        symbol = symbol.uppercase(),
        name = name,
        series = series,
        isin = isin.uppercase(),
        listingDate = listingDate,
        paidUpValue = 10.0,
        lotSize = 1L,
        faceValue = 10.0,
    )

    // ---------------------------------------------------------------------------------------
    // Values measured from the real files on 2026-10-02. Asserted in the tests below so a
    // changed asset cannot pass unnoticed; see the report for provenance.
    // ---------------------------------------------------------------------------------------

    /** Real master data-row count. */
    const val EXPECTED_MASTER_ROWS: Int = 2593

    /** Real master series distribution: `EQ 2319, BE 248, BZ 26`. */
    val EXPECTED_MASTER_SERIES: Map<String, Int> = mapOf("BE" to 248, "BZ" to 26, "EQ" to 2319)

    /** `EQ` + `SM` rows of the 2026-10-01 bhavcopy. */
    const val EXPECTED_BHAVCOPY_KEPT: Int = 3051

    /** Every row of the 2026-10-01 bhavcopy, before the series filter. */
    const val EXPECTED_BHAVCOPY_TOTAL: Int = 3712

    /** Master tickers that trade in the bhavcopy. */
    const val EXPECTED_MATCHED: Int = 2313

    /** Master tickers absent from the `EQ`+`SM` bhavcopy: 248 `BE` + 26 `BZ` + 6 `EQ`. */
    const val EXPECTED_MASTER_ONLY: Int = 280

    /** The 6 `EQ` master tickers absent from the whole bhavcopy, all listed 2026-04/2026-08. */
    val EXPECTED_MASTER_ONLY_EQUITY: List<String> =
        listOf("ALFREDHE", "SAB", "SAYAJIHOTL", "SHERVANI", "SONAL", "TERAI")

    /** Bhavcopy `EQ`+`SM` tickers with no master row: 349 `EQ` + 389 `SM`. */
    const val EXPECTED_BHAVCOPY_ONLY: Int = 738
}