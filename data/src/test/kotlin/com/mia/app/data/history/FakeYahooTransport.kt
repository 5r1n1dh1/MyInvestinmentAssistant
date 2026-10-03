package com.mia.app.data.history

import com.mia.app.data.http.HttpRequest
import com.mia.app.data.http.HttpResponse
import com.mia.app.data.http.HttpTransport

/**
 * Scriptable [HttpTransport] for the history layer.
 *
 * Hand-written because docs/CONTRACTS.md allows no mocking libraries, and because the property
 * that matters here — *the request sequence* — cannot be expressed by a mock any better: the
 * transport records every request with its headers, so a test can assert that the chart request
 * carried both the session cookie and the crumb.
 *
 * ### The verbs
 * * [respond] — match on a substring of the URL, in order, each rule consumed once.
 * * [always] — match on a substring forever (used for handshake replies, which repeat).
 * * [failWith] — throw, to prove that a transport blow-up becomes a typed failure rather than a
 *   crash.
 */
internal class FakeYahooTransport : HttpTransport {

    private val rules = mutableListOf<Rule>()
    private val permanent = mutableListOf<Rule>()

    /** Every request issued, in order, with headers — the evidence for sequencing claims. */
    val requests: MutableList<HttpRequest> = mutableListOf()

    val urls: List<String> get() = requests.map { it.url }

    /** How many times [url] was requested. */
    fun countOf(url: String): Int = requests.count { it.url == url }

    fun requestsTo(fragment: String): List<HttpRequest> = requests.filter { it.url.contains(fragment) }

    /** One-shot rule for the first matching request. */
    fun respond(fragment: String, response: HttpResponse) = apply {
        rules += Rule(fragment, response)
    }

    fun respondText(fragment: String, body: String, code: Int = 200) =
        respond(fragment, HttpResponse(code = code, body = body.toByteArray()))

    fun respondStatus(fragment: String, code: Int) =
        respond(fragment, HttpResponse(code = code, body = ByteArray(0)))

    /** Rule that answers every matching request. */
    fun always(fragment: String, response: HttpResponse) = apply {
        permanent += Rule(fragment, response)
    }

    fun alwaysText(fragment: String, body: String, code: Int = 200) =
        always(fragment, HttpResponse(code = code, body = body.toByteArray()))

    fun alwaysStatus(fragment: String, code: Int, headers: Map<String, List<String>> = emptyMap()) =
        always(fragment, HttpResponse(code = code, body = ByteArray(0), headers = headers))

    fun alwaysCookies(fragment: String, cookies: List<String>, code: Int = 200) =
        always(fragment, HttpResponse(code = code, body = ByteArray(0), setCookies = cookies))

    /** Make every request to [fragment] throw [error]. */
    fun failWith(fragment: String, error: () -> Throwable) = apply {
        permanent += Rule(fragment, null, error)
    }

    override suspend fun execute(request: HttpRequest): HttpResponse {
        requests += request
        permanent.firstOrNull { request.url.contains(it.fragment) }?.let { rule ->
            rule.error?.let { throw it() }
            return rule.response ?: HttpResponse(code = 404, body = ByteArray(0))
        }
        val index = rules.indexOfFirst { request.url.contains(it.fragment) }
        if (index < 0) return HttpResponse(code = 404, body = ByteArray(0))
        val rule = rules.removeAt(index)
        rule.error?.let { throw it() }
        return rule.response ?: HttpResponse(code = 404, body = ByteArray(0))
    }

    private class Rule(
        val fragment: String,
        val response: HttpResponse?,
        val error: (() -> Throwable)? = null,
    )

    companion object {
        /** The cookie `https://fc.yahoo.com` is verified to set (plan.md §3.2 step 1). */
        val SESSION_COOKIES: List<String> = listOf(
            "A3=abc123; Domain=.yahoo.com; Path=/; Secure; HttpOnly",
            "GUC=AQAB; Domain=.yahoo.com; Path=/; Expires=Wed, 09 Jun 2027 10:18:14 GMT; Secure",
        )

        /** The cookie header the session must produce from [SESSION_COOKIES]. */
        const val SESSION_COOKIE_HEADER: String = "A3=abc123; GUC=AQAB"

        const val CRUMB: String = "Q7dR8kZ2vN1mX9pL"

        /** Builds the standard happy-path transport: cookie, crumb, then a chart per host. */
        fun happyPath(chartJson: String, onHost: String = "query1"): FakeYahooTransport =
            FakeYahooTransport()
                .alwaysCookies(YahooEndpoints.SESSION_URL, SESSION_COOKIES)
                .alwaysText("/v1/test/getcrumb", CRUMB)
                .respondText("/v8/finance/chart/", chartJson)

        /** Cookie + crumb but nothing else; used to prove the source still terminates. */
        fun handshakeOnly(): FakeYahooTransport = FakeYahooTransport()
            .alwaysCookies(YahooEndpoints.SESSION_URL, SESSION_COOKIES)
            .alwaysText("/v1/test/getcrumb", CRUMB)
    }
}
