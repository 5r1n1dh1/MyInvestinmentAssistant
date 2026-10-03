package com.mia.app.data.fundamentals

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * The `top-ratios` block: the headline valuation and returns figures.
 *
 * ### Why it is captured as a map and not just the nine named fields
 * plan.md §3.3 lists nine ratios, but it is the *shape* of the block that has to survive a page
 * change, not the count. Screener.in lets a user add ratios to this list server-side, so the
 * field set is user- and page-dependent. Verified against the five golden fixtures captured
 * 2026-10-02: **all five publish exactly the same nine fields** —
 *
 * ```
 * Market Cap · Current Price · High / Low · Stock P/E · Book Value
 * Dividend Yield · ROCE · ROE · Face Value
 * ```
 *
 * (plan.md §14's "163–169 `info` fields" is **not** reproducible against these fixtures: the
 * current page carries 9 `<span class="name">` in `#company-info` and 9 in `#top-ratios`.) The
 * block is therefore captured as [fields] — every published label with its raw text and its
 * parsed number — and the named accessors are a convenience view over it. An unknown or newly
 * added key is captured and ignored gracefully; it can never break the parse.
 *
 * ### The `High / Low` cell holds two numbers
 * `<span class="nowrap value">₹ <span class="number">1,612</span> / <span class="number">1,161</span></span>`
 * so it is read through `numberTextsOf`, not `valueTextOf`.
 */
data class TopRatiosSnapshot(
    /** Every published label (normalised to lower case, no punctuation) with raw text and number. */
    val fields: Map<String, TopRatioField>,
) {
    /** Market capitalisation, in ₹ crore, exactly as published (the page prints `Cr.`). */
    val marketCapCr: Double? get() = number("marketcap")

    /** Last traded price in ₹. */
    val currentPrice: Double? get() = number("currentprice")

    /** 52-week high in ₹. First figure of the `High / Low` cell. */
    val high52Week: Double? get() = pair("highlow")?.firstOrNull()

    /** 52-week low in ₹. Second figure of the `High / Low` cell. */
    val low52Week: Double? get() = pair("highlow")?.getOrNull(1)

    /** Stock P/E, as published. */
    val stockPe: Double? get() = number("stockpe")

    /** Book value per share in ₹. */
    val bookValue: Double? get() = number("bookvalue")

    /** Dividend yield in percent, as published (the page prints `%`). */
    val dividendYieldPercent: Double? get() = number("dividendyield")

    /** Return on capital employed in percent, as published. plan.md §7.2 rank 1. */
    val rocePercent: Double? get() = number("roce")

    /** Return on equity in percent, as published. plan.md §7.2 rank 6. */
    val roePercent: Double? get() = number("roe")

    /** Face value per share in ₹. */
    val faceValue: Double? get() = number("facevalue")

    /** Debt/Equity. **Never published by this page** — see plan.md §7.3 and the report. */
    val debtToEquity: Double? get() = number("debttoequity")

    /** All `number` spans of the cell for [key], in published order. Empty when absent. */
    fun pair(key: String): List<Double?>? = fields[key]?.numbers?.takeIf { it.isNotEmpty() }

    private fun number(key: String): Double? = fields[key]?.number

    /** True when the page published no `top-ratios` field at all. */
    val isEmpty: Boolean get() = fields.isEmpty()

    /** Labels present on the page, in published order, for diagnostics and tests. */
    val keys: List<String> get() = fields.keys.toList()

    /** One published ratio: the label as printed, the raw cell text, and the parsed number(s). */
    data class TopRatioField(
        val label: String,
        val rawText: String,
        /** Parsed number of the *first* figure, or `null` when the cell has none. */
        val number: Double?,
        /** Every figure in the cell, in published order (`High / Low` has two). */
        val numbers: List<Double?>,
    )
}

/** Reads the `top-ratios` block out of a screener.in page. */
object TopRatiosParser {

    /**
     * Parses `#top-ratios`.
     *
     * Returns an **empty** [TopRatiosSnapshot] when the block is absent; the caller is
     * responsible for reporting that as a missing section (plan.md §12 — never a silent empty).
     */
    fun parse(doc: Document): TopRatiosSnapshot = parse(doc.getElementById(ScreenerHtml.TOP_RATIOS))

    fun parse(root: Element?): TopRatiosSnapshot {
        if (root == null) return TopRatiosSnapshot(emptyMap())
        val fields = LinkedHashMap<String, TopRatiosSnapshot.TopRatioField>()
        for (li in root.select("li")) {
            val nameEl = li.getElementsByClass("name").firstOrNull()
            val name = ScreenerHtml.normalise(nameEl?.text())
            if (name.isEmpty()) continue
            val key = ScreenerHtml.labelKey(name)
            if (key.isEmpty() || key == "rawpdf") continue

            // plan.md §3.3 landmine 1: class is "nowrap value", digits in a child .number span.
            // Substring matching, so "value nowrap" would also work.
            val valueEl = ScreenerHtml.elementsWithClassToken(li, "value").firstOrNull()
            val rawText = valueEl?.let { ScreenerHtml.valueTextOf(it) } ?: ScreenerHtml.normalise(li.text())
            val numbers = valueEl
                ?.let { ScreenerHtml.numberTextsOf(it) }
                ?.map { ScreenerHtml.parseNumber(it) }
                .orEmpty()
            val number = numbers.firstOrNull()
                ?: valueEl?.let { ScreenerHtml.parseNumber(ScreenerHtml.valueTextOf(it)) }

            fields[key] = TopRatiosSnapshot.TopRatioField(name, rawText, number, numbers)
        }
        return TopRatiosSnapshot(fields)
    }

    /**
     * The company's display name (`<h1>`, e.g. `"Reliance Industries Ltd"`).
     *
     * `top-ratios` does not carry it and `<title>` is padded with SEO text
     * (`"… share price | About … | Key Insights - Screener"`), so `h1` is the only clean source.
     */
    fun companyName(doc: Document): String? =
        doc.selectFirst("h1")?.let { ScreenerHtml.normalise(it.text()) }?.ifBlank { null }

    /**
     * screener.in's own industry classification, broadest first, as one sentence.
     *
     * Returns e.g. `"Energy, Oil, Gas & Consumable Fuels, Petroleum Products, Refineries &
     * Marketing"` for RELIANCE, or `null` when the page carries no industry block.
     *
     * ### Why this is the source and not the company's name
     * plan.md §7.1 lets the sector come from screener.in's sector page, and
     * [com.mia.app.domain.ratios.SectorResolver]'s highest-precedence input is exactly this
     * sentence. Without it the resolver could only match a word in the company's *name*, which is
     * why `RELIANCE` used to be unresolvable: "Reliance Industries Ltd" contains no industry word
     * at all, while the page has always said `Energy` in as many words.
     *
     * ### Why the whole chain and not just the deepest link
     * The links are a taxonomy path — `/market/IN03/IN0301/IN030103/IN030103001/` — broadest first.
     * Any single link can be ambiguous on its own (`Petroleum Products` could be a chemicals
     * name), while the path disambiguates itself. Joining them is also what lets the resolver
     * reach `Confidence.AGREED`, which is defined against name and industry *agreeing*. Order is
     * preserved because "broadest first" is the order a human reads them in.
     */
    fun industryChain(doc: Document): String? =
        doc.select("a[href^=/market/]")
            .asSequence()
            .map { ScreenerHtml.normalise(it.text()) }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()
            .takeIf { it.isNotEmpty() }
            ?.joinToString(", ")
}
