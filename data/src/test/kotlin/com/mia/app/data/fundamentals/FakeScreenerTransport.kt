package com.mia.app.data.fundamentals

import com.mia.app.data.http.HttpRequest
import com.mia.app.data.http.HttpResponse
import com.mia.app.data.http.HttpTransport

/**
 * Scriptable [HttpTransport] for the fundamentals layer.
 *
 * Hand-written on purpose (docs/CONTRACTS.md: no mocking libraries). It records every request
 * so the robots.txt assertions can be made against **what was actually sent**, not against what
 * the URL builder was expected to produce — which is the only assertion that would catch a
 * future change quietly adding a second request shape.
 *
 * The rule is `suspend` so a test can hold a request open and observe whether a second one
 * arrives while it is still in flight.
 */
internal class FakeScreenerTransport(
    private val rule: suspend (HttpRequest) -> HttpResponse,
) : HttpTransport {

    /** Every request issued, in order. */
    val requests: MutableList<HttpRequest> = mutableListOf()

    val urls: List<String> get() = requests.map { it.url }

    val count: Int get() = requests.size

    override suspend fun execute(request: HttpRequest): HttpResponse {
        requests += request
        return rule(request)
    }

    companion object {
        fun html(body: String, code: Int = 200): HttpResponse =
            HttpResponse(code = code, body = body.toByteArray())

        fun status(code: Int, headers: Map<String, List<String>> = emptyMap()): HttpResponse =
            HttpResponse(code = code, body = ByteArray(0), headers = headers)
    }
}

/**
 * A sleeper that records instead of waiting, so the throttle interval and the backoff ladder
 * can be asserted without the suite taking half a minute.
 */
internal class RecordingSleeper {
    val waits: MutableList<Long> = mutableListOf()

    val sleeper: suspend (Long) -> Unit = { millis -> waits += millis }
}
