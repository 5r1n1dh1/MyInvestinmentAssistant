package com.mia.app.data.android.net

import com.mia.app.data.http.HttpRequest
import com.mia.app.data.http.HttpResponse
import com.mia.app.data.http.HttpTransport
import com.mia.app.data.http.SourceUnavailableException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * The production [HttpTransport]: OkHttp, and nothing else in the app knows OkHttp exists.
 *
 * ### Why the transport is the only network seam
 * plan.md §4.3 requires `:data` to be unit-testable with no device, and plan.md §3.2's Yahoo
 * handshake needs precise request choreography (cookie → crumb → chart) that can only be
 * verified when the transport is injectable. So `:data` sees [HttpRequest]/[HttpResponse] and
 * this class is the single place those become sockets.
 *
 * ### Failure is typed, never silent
 * An `IOException` becomes a [SourceUnavailableException] — "the phone is offline / DNS
 * failed" is a different thing from "the server said no", and every caller in `:data` renders
 * the two differently. A non-2xx code is **not** an error here: for the bhavcopy a 404 is an
 * expected answer (today's file is not published yet, plan.md §3.1), so the status is handed
 * back untouched and the caller decides.
 *
 * ### Threading
 * Every call is wrapped in `withContext(dispatcher)` where `dispatcher` is the `@IoDispatcher`
 * qualifier, so a test can substitute `UnconfinedTestDispatcher` and no repository method can
 * ever run on the main thread by accident.
 */
class OkHttpTransport(
    private val client: OkHttpClient,
    private val dispatcher: CoroutineDispatcher,
) : HttpTransport {

    override suspend fun execute(request: HttpRequest): HttpResponse = withContext(dispatcher) {
        val call = client.newCall(build(request))
        try {
            call.execute().use { response ->
                val body = response.body?.bytes() ?: ByteArray(0)
                HttpResponse(
                    code = response.code,
                    body = body,
                    headers = response.headers.toMultimap(),
                    setCookies = response.headers.values(SET_COOKIE),
                )
            }
        } catch (e: IOException) {
            throw SourceUnavailableException(
                source = "okhttp",
                message = "${request.method} ${request.url} failed: ${e.javaClass.simpleName}: ${e.message}",
                cause = e,
            )
        }
    }

    private fun build(request: HttpRequest): Request {
        val builder = Request.Builder().url(request.url)
        for ((name, value) in request.headers) {
            builder.header(name, value)
        }
        // `method(String, RequestBody?)` accepts a null body for GET/HEAD/DELETE and rejects
        // it for POST/PUT, which is exactly the contract; the callers in `:data` only ever
        // issue GETs today, and this keeps a future POST honest.
        val body = request.body?.toRequestBody(TEXT_MEDIA_TYPE)
        return builder.method(request.method, body).build()
    }

    private companion object {
        const val SET_COOKIE: String = "Set-Cookie"

        val TEXT_MEDIA_TYPE = "text/plain; charset=utf-8".toMediaType()
    }
}