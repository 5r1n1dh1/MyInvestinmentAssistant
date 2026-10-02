package com.mia.app.data.http

/**
 * The single seam between this app and the network.
 *
 * plan.md §4.3 requires `:data` to be unit-testable without a device, and several sources
 * need precise request choreography (Yahoo's cookie+crumb handshake, plan.md §3.2). Both
 * are only verifiable when the transport is injectable.
 *
 * The production implementation lives in `:android-data` (OkHttp). Tests use a fake.
 */

data class HttpRequest(
    val url: String,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
)

data class HttpResponse(
    val code: Int,
    val body: ByteArray,
    val headers: Map<String, List<String>> = emptyMap(),
    /** Raw `Set-Cookie` values, in order, so a caller can build its own cookie store. */
    val setCookies: List<String> = emptyList(),
) {
    val isSuccess: Boolean get() = code in 200..299
    val text: String get() = body.toString(Charsets.UTF_8)

    fun header(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }
            ?.value?.firstOrNull()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HttpResponse) return false
        return code == other.code && text == other.text && setCookies == other.setCookies
    }

    override fun hashCode(): Int = (code * 31 + text.hashCode()) * 31 + setCookies.hashCode()
}

interface HttpTransport {
    /**
     * Executes [request] and returns the raw response including non-2xx codes — callers
     * decide what a 404 or 429 means, because for bhavcopy a 404 is an expected answer
     * (today's file is not published yet).
     */
    suspend fun execute(request: HttpRequest): HttpResponse
}

/** Raised when a source is unusable in a way the caller must surface, not swallow. */
class SourceUnavailableException(
    val source: String,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/** Raised when a response is not parseable into the expected shape. */
class ParseFailedException(
    val source: String,
    val detail: String,
    cause: Throwable? = null,
) : Exception("$source: $detail", cause)