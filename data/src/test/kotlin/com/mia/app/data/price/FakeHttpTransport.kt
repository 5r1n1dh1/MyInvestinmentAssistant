package com.mia.app.data.price

import com.mia.app.data.http.HttpRequest
import com.mia.app.data.http.HttpResponse
import com.mia.app.data.http.HttpTransport

/**
 * Scriptable [HttpTransport] for price-layer tests.
 *
 * Hand-written on purpose (docs/CONTRACTS.md: no mocking libraries). A [Rule] decides what to
 * answer for a given URL, so a test can express "the primary host's socket dies, the mirror
 * serves the archive" without any network access — which is also what lets the
 * primary→mirror ordering be asserted rather than assumed.
 */
internal class FakeHttpTransport(
    private val rule: (HttpRequest) -> HttpResponse,
) : HttpTransport {

    constructor(vararg responses: Pair<String, HttpResponse>) : this(
        { request ->
            val hit = responses.firstOrNull { request.url.contains(it.first) }
            hit?.second ?: HttpResponse(code = 404, body = ByteArray(0))
        },
    )

    /** Every request actually issued, in order — the evidence for host-ordering claims. */
    val requests: MutableList<HttpRequest> = mutableListOf()

    val urls: List<String> get() = requests.map { it.url }

    override suspend fun execute(request: HttpRequest): HttpResponse {
        requests += request
        return rule(request)
    }

    companion object {
        fun ok(body: ByteArray, code: Int = 200) = HttpResponse(code = code, body = body)

        fun text(body: String, code: Int = 200) = HttpResponse(code = code, body = body.toByteArray())

        fun status(code: Int) = HttpResponse(code = code, body = ByteArray(0))
    }
}
