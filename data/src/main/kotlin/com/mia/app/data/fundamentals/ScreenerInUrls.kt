package com.mia.app.data.fundamentals

/**
 * URL construction for screener.in, and the robots.txt gate that keeps it honest.
 *
 * ### The one permitted shape (plan.md §3.6, binding)
 *
 * ```
 * https://www.screener.in/company/{SYMBOL}/consolidated/
 * ```
 *
 * screener.in's robots.txt is:
 *
 * ```
 * User-agent: *
 * Disallow: /user/ + *
 * Disallow: /[*]?q=          <- search
 * Disallow: /[*]?sort=
 * Disallow: /[*]?limit=
 * Disallow: /[*]?page=       <- pagination
 * Disallow: /company/source/quarter/ + *
 * ```
 *
 * So there is exactly **one** URL this app may ever request: the consolidated company page.
 * There is deliberately no search helper here — symbol search runs against the bundled
 * `EQUITY_L.csv` (plan.md §9), which is offline, instant and compliant. There is deliberately no
 * paging helper, and [isPermitted] rejects any URL carrying a query string at all, so a future
 * change cannot quietly reintroduce `?q=` or `?page=` and still pass [isPermitted].
 *
 * ### Symbol validation
 * [companyPage] validates against a conservative allow-list *before* interpolating, so no
 * symbol can inject a path separator, a query string, a fragment, or an extra host.
 *
 * The allowed set is derived from the bundled NSE master, not guessed: all 2,593 symbols in
 * `EQUITY_L.csv` match `[A-Z0-9][A-Z0-9&-]*` (longest is 10 characters). Punctuation that
 * genuinely occurs is therefore allowed — `M&M`, `GVT&D`, `BAJAJ-AUTO`, `20MICRONS` — and
 * `.` is **not** allowed, because no NSE symbol uses it and permitting it would let
 * `evil.example` through as a plausible-looking symbol.
 */
object ScreenerInUrls {

    /** Host from plan.md §3.3. Verified `200` + ~231 KB with the `Referer` header below. */
    const val BASE: String = "https://www.screener.in"

    /** plan.md §3.3: the page refuses to serve without a `Referer` pointing at the site root. */
    const val REFERER: String = "https://www.screener.in/"

    /** Path template. `%s` is the validated symbol. */
    const val COMPANY_PATH: String = "/company/%s/consolidated/"

    /** Longest NSE symbol seen in `EQUITY_L.csv` is comfortably under this. */
    const val MAX_SYMBOL_LENGTH: Int = 20

    private val SAFE_SYMBOL = Regex("^[A-Za-z0-9][A-Za-z0-9&\\-]{0,19}$")
    private val PERMITTED_PATH = Regex("^/company/[A-Za-z0-9][A-Za-z0-9&\\-]{0,19}/consolidated/$")

    /**
     * The single URL this app is allowed to request for [symbol].
     *
     * @throws IllegalArgumentException when [symbol] contains anything outside the allow-list.
     *   Callers that must not throw ([ScreenerInSource.fetch]) catch this and turn it into a
     *   not-ok [com.mia.app.domain.model.ParserHealth].
     */
    fun companyPage(symbol: String): String {
        val trimmed = symbol.trim()
        require(trimmed.isNotEmpty()) { "symbol is blank" }
        require(trimmed.length <= MAX_SYMBOL_LENGTH) {
            "symbol '$trimmed' is longer than $MAX_SYMBOL_LENGTH characters"
        }
        require(SAFE_SYMBOL.matches(trimmed)) {
            "symbol '$trimmed' contains characters outside [A-Za-z0-9&-]"
        }
        return BASE + COMPANY_PATH.format(trimmed.uppercase())
    }

    /** Non-throwing check, for callers that would rather handle a bad symbol than trap. */
    fun isValidSymbol(symbol: String): Boolean {
        val trimmed = symbol.trim()
        return trimmed.isNotEmpty() &&
            trimmed.length <= MAX_SYMBOL_LENGTH &&
            SAFE_SYMBOL.matches(trimmed)
    }

    /**
     * True only for the exact permitted shape.
     *
     * Any query string at all is refused, which covers every `Disallow` entry that matters
     * (`?q=`, `?page=`, `?sort=`, `?limit=`) without having to enumerate them. `/user/` and
     * `/company/source/quarter/` paths are refused by the path shape.
     */
    fun isPermitted(url: String): Boolean {
        if (!url.startsWith("$BASE/")) return false
        val path = url.removePrefix(BASE)
        if (path.contains('?')) return false
        if (path.contains('#')) return false
        return PERMITTED_PATH.matches(path)
    }
}
