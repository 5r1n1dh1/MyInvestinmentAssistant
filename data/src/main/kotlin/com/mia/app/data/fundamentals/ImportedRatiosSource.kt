package com.mia.app.data.fundamentals

import com.mia.app.domain.model.Confidence
import com.mia.app.domain.model.Ratios
import com.mia.app.domain.model.Source
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * A [ManualFundamentalsSource] fed by a CSV or JSON file the person using the app supplied.
 *
 * ### What this is for, precisely
 * plan.md §5.1 says *"merge per field, not per source… on conflict (>5% divergence): show the
 * primary value, render the other greyed beneath it with a divergence chip. Do NOT average."* All of
 * that machinery exists — `FundamentalsAggregator` implements it and the UI renders it — and none
 * of it has ever run, because only `ScreenerInSource` is wired and a merge needs two sides. This
 * is the second side, and the cheapest honest one: **no new network source, no new robots.txt
 * question, no new failure surface.**
 *
 * ### Why an import and not a scraper
 * A second scraper would mean a new host to be polite to, a second robots.txt reading, a second set
 * of rate limits and a second thing that can go down. None of that buys anything for a merge that
 * only needs *some* second number for *some* fields. A person who already pays for a terminal, or
 * maintains a spreadsheet, has exactly the data this needs — and handing them a text file to import
 * costs one parser.
 *
 * ### The honesty contract this type exists to enforce
 * An imported number is **not** measured by this app. Nobody checked it, no parser validated it,
 * and it will not be re-verified. Three rules follow, and all three are asserted in the tests:
 *
 * 1. **Confidence is `SINGLE`, never `AGREED`.** `AGREED` is produced by `FreshnessPolicy` when a
 *    merge has actually compared two numbers; claiming it here would report agreement that was
 *    never tested.
 * 2. **A missing field is `null`, never `0.0`.** A blank cell in a spreadsheet is missing data.
 *    Zero is a value, and a zero P/E would divide by it downstream.
 * 3. **A file without a date is rejected.** §5.1 shows both numbers of a conflict and lets the
 *    reader judge them; that is impossible without dates, and defaulting to today would make a
 *    three-year-old spreadsheet look current — the exact failure plan.md §10 calls the fastest way
 *    to lose trust.
 *
 * ### Formats
 * Both are one header row plus one data row, because a *fundamentals snapshot* is one company at
 * one date. A multi-row file would need a date per row and a merge per row, which is a different
 * feature (a history importer) and is deliberately not built here.
 *
 * CSV columns: `symbol,asOf,pe,roe,roce,pb,dividendYield,marketCap,operatingMargin`.
 * JSON: `{"symbol":"RELIANCE","asOf":"2026-10-02","pe":21.2,"roe":8.91,…}`.
 */
class ImportedRatiosSource : ManualFundamentalsSource {

    override val name: String = "Imported file"

    /**
     * In-memory store.
     *
     * Deliberately not persisted: `:android-data` owns storage and this wave does not write to it.
     * An import is therefore a **session** input, and [coverageNote] says so on screen rather than
     * letting the UI imply a durable second source exists. The persistence is a one-file change
     * once the storage layer is in scope.
     */
    private val imported = LinkedHashMap<String, Imported>()

    /**
     * One imported snapshot, kept with its own provenance.
     *
     * @param fieldErrors per-field parse failures. **Not** folded into the `Ratios` as nulls
     *   silently: a file with a typo in `pe` must say "that column did not parse", because a
     *   null and a typo look identical in the merged output and only one of them is a bug.
     */
    data class Imported(
        val symbol: String,
        val asOf: LocalDate,
        val ratios: Ratios,
        val fieldErrors: List<String> = emptyList(),
    )

    override fun import(symbol: String, asOf: LocalDate, ratios: Ratios) {
        val ticker = symbol.trim().uppercase()
        require(ticker.isNotEmpty()) { "an import must name the symbol it is for" }
        require(!asOf.isAfter(FundamentalsParserFacade.todayInIndia())) {
            "an import dated ${asOf} is in the future; a fundamentals snapshot is not a forecast"
        }
        imported[ticker] = Imported(symbol = ticker, asOf = asOf, ratios = ratioWithProvenance(ticker, asOf, ratios))
    }

    /**
     * Reads a CSV or JSON file and imports it.
     *
     * The format is chosen by the **content**, not by a flag, because a person who picked a file
     * has no reason to also pick a format. A leading `{` is JSON; anything else is CSV.
     *
     * @throws IllegalArgumentException when the file carries no symbol, no date, or an unparseable
     *   date. All three are mistakes in the file, and a silent import would leave the reader
     *   believing a second source exists when it does not.
     */
    fun importFrom(text: String): Imported = when {
        text.trimStart().startsWith("{") -> importJson(text)
        else -> importCsv(text)
    }

    override fun ratiosFor(symbol: String, asOf: LocalDate): Ratios? =
        imported[symbol.trim().uppercase()]?.ratios

    /** The stored snapshot for [symbol], or `null`. Used by tests and by the coverage note. */
    fun importedFor(symbol: String): Imported? = imported[symbol.trim().uppercase()]

    /** Every symbol imported this session, in import order. */
    fun symbols(): List<String> = imported.keys.toList()

    /**
     * What the coverage panel prints about this source.
     *
     * Always names the session scope and the un-verified nature of the figures, because the panel's
     * job is to say what the app did **not** check — and an imported number is the largest "not
     * checked" on the tab.
     */
    fun coverageNote(): String = when {
        imported.isEmpty() ->
            "No second fundamentals source is in use. plan.md §5.1's conflict view therefore has " +
                "nothing to disagree with, so this tab cannot show a divergence."
        else ->
            "A second fundamentals source (${name}) has been imported for " +
                "${imported.size} symbol(s): ${imported.keys.joinToString(", ")}. Those figures " +
                "were supplied as a file and are not checked by this app — they are shown beside " +
                "the page's own only where the two disagree by more than 5%, and neither is " +
                "averaged. Imports last for this session only."
    }

    // ------------------------------------------------------------------------------ CSV

    private fun importCsv(text: String): Imported {
        val lines = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toList()
        require(lines.size >= 2) {
            "the file needs a header row and one data row; found ${lines.size} non-empty line(s)"
        }
        val header = lines.first().split(',').map { it.trim().lowercase() }
        val cells = lines[1].split(',').map { it.trim() }
        val byColumn = header.zip(cells).toMap()
        val errors = mutableListOf<String>()

        val symbol = byColumn.required("symbol", errors)
        val asOf = byColumn.date("asOf", errors)
        val ratios = build {
            symbol?.let { put("symbol", it.uppercase()) }
            asOf?.let { put("asOf", it) }
            for ((column, setter) in NUMERIC_COLUMNS) {
                val raw = byColumn[column.lowercase()] ?: continue
                if (raw.isEmpty() || raw in PLACEHOLDERS) continue
                val value = raw.replace(",", "").toDoubleOrNull()
                if (value == null) errors += "column '$column' reads \"$raw\", which is not a number"
                else setter(this, value)
            }
        }
        return finish(symbol, asOf, buildMap { putAll(ratios) }, errors)
    }

    // ----------------------------------------------------------------------------- JSON

    /**
     * A flat `{"key": value}` object.
     *
     * Hand-rolled rather than pulled from a JSON library: `:data` deliberately has no JSON
     * dependency (the only one in the repo is Android-side, and `:app`/`:android-data` must not be
     * imported by `:data`'s tests), and the grammar needed here is a flat object of numbers,
     * strings and `null`s. A nested object or an array is **rejected** rather than half-parsed,
     * because a flat snapshot is the entire documented shape.
     */
    private fun importJson(text: String): Imported {
        val body = text.trim().removePrefix("{").removeSuffix("}").trim()
        require(body.isNotEmpty()) { "the JSON file has no fields" }
        val errors = mutableListOf<String>()
        val flat = LinkedHashMap<String, String>()
        for (entry in splitTopLevel(body)) {
            val colon = entry.indexOf(':')
            require(colon > 0) { "JSON entry \"$entry\" is not \"key\": value" }
            val key = entry.substring(0, colon).trim().trim('"').lowercase()
            val raw = entry.substring(colon + 1).trim()
            require(!raw.startsWith("[") && !raw.startsWith("{")) {
                "only a flat object is supported; \"$key\" is nested. A fundamentals snapshot is " +
                    "one company at one date, so a nested value would mean something else."
            }
            flat[key] = raw.trim('"')
        }
        val symbol = flat.required("symbol", errors)
        val asOf = flat.date("asOf", errors)
        val values = LinkedHashMap<String, Any>()
        symbol?.let { values["symbol"] = it.uppercase() }
        asOf?.let { values["asOf"] = it }
        for ((column, setter) in NUMERIC_COLUMNS) {
            val raw = flat[column.lowercase()] ?: continue
            if (raw.isEmpty() || raw in PLACEHOLDERS || raw == "null") continue
            val value = raw.replace(",", "").toDoubleOrNull()
            if (value == null) errors += "field \"$column\" reads \"$raw\", which is not a number"
            else setter(values, value)
        }
        return finish(symbol, asOf, values, errors)
    }

    /**
     * Splits on commas that are not inside a quoted string.
     *
     * A quoted value may itself contain a comma (`"Reliance Industries, Ltd"`), and splitting on
     * every comma would turn one field into two and report the second as an unrecognised column.
     */
    private fun splitTopLevel(body: String): List<String> {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        for (ch in body) {
            when {
                ch == '"' -> { quoted = !quoted; current.append(ch) }
                ch == ',' && !quoted -> { parts += current.toString(); current.clear() }
                else -> current.append(ch)
            }
        }
        parts += current.toString()
        return parts.map { it.trim() }.filter { it.isNotEmpty() }
    }

    // -------------------------------------------------------------------------- internals

    private fun finish(
        symbol: String?,
        asOf: LocalDate?,
        values: Map<String, Any>,
        errors: List<String>,
    ): Imported {
        require(!symbol.isNullOrBlank()) {
            "the file has no \"symbol\": a second source with no company to attach to is not a " +
                "second source" + errors.prefixed()
        }
        require(asOf != null) {
            "the file has no \"asOf\" date" + errors.prefixed()
        }
        val ticker = symbol.trim().uppercase()
        val ratios = ratioWithProvenance(
            symbol = ticker,
            asOf = asOf,
            ratios = Ratios(
                symbol = ticker,
                sector = com.mia.app.domain.model.Sector.OTHER,
                // Imported, so the sector is unresolved on purpose: plan.md §7.1 forbids guessing
                // and an imported file carries no classification this app can trust.
                asOf = asOf,
                source = Source.SCREENER_IN,
                confidence = Confidence.SINGLE,
                pe = values["pe"] as? Double,
                roe = values["roe"] as? Double,
                roce = values["roce"] as? Double,
                pb = values["pb"] as? Double,
                dividendYield = values["dividendYield"] as? Double,
                marketCap = values["marketCap"] as? Double,
                operatingMargin = values["operatingMargin"] as? Double,
            ),
        )
        val imported = Imported(ticker, asOf, ratios, errors)
        this.imported[ticker] = imported
        return imported
    }

    /**
     * Stamps the imported snapshot with the provenance the merge needs.
     *
     * [Confidence.SINGLE] is the load-bearing part and is not a default: it says *one* source had
     * the field and no cross-check was performed. `AGREED` would be a claim about agreement this
     * class cannot have made.
     */
    private fun ratioWithProvenance(symbol: String, asOf: LocalDate, ratios: Ratios): Ratios =
        ratios.copy(
            symbol = ratios.symbol.ifBlank { symbol },
            asOf = asOf,
            confidence = Confidence.SINGLE,
        )

    private fun Map<String, String>.required(key: String, errors: MutableList<String>): String? {
        val value = this[key]?.trim()
        if (value.isNullOrBlank()) errors += "no \"$key\" column"
        return value
    }

    private fun Map<String, String>.date(key: String, errors: MutableList<String>): LocalDate? {
        val value = this[key]?.trim()
        if (value.isNullOrBlank()) {
            errors += "no \"$key\" column"
            return null
        }
        return try {
            LocalDate.parse(value)
        } catch (e: DateTimeParseException) {
            errors += "column \"$key\" reads \"$value\", which is not an ISO date (2026-10-02)"
            null
        }
    }

    private fun List<String>.prefixed(): String =
        if (isEmpty()) "" else " (" + joinToString("; ") + ")"

    private companion object {
        /** Cells that mean "no value", never zero. Mirrors `ScreenerHtml.PLACEHOLDERS`. */
        val PLACEHOLDERS = setOf("", "-", "--", "na", "n/a", "null", "nil", "none")

        /**
         * Column → setter, as data.
         *
         * A [MutableMap] builder rather than a `Ratios` copy chain so CSV and JSON fill the same
         * fields the same way; a column added here reaches both formats at once and cannot be
         * spelled differently in each.
         */
        val NUMERIC_COLUMNS: List<Pair<String, (MutableMap<String, Any>, Double) -> Unit>> = listOf(
            "pe" to { map, v -> map["pe"] = v },
            "roe" to { map, v -> map["roe"] = v },
            "roce" to { map, v -> map["roce"] = v },
            "pb" to { map, v -> map["pb"] = v },
            "dividendYield" to { map, v -> map["dividendYield"] = v },
            "marketCap" to { map, v -> map["marketCap"] = v },
            "operatingMargin" to { map, v -> map["operatingMargin"] = v },
        )
    }
}