package com.mia.app.data.android.di

import android.content.Context
import com.mia.app.data.android.net.HostRateLimiter
import com.mia.app.data.android.net.OkHttpTransport
import com.mia.app.data.android.net.RetryInterceptor
import com.mia.app.data.android.net.assetReaderOf
import com.mia.app.data.http.HttpTransport
import com.mia.app.data.symbols.AssetReader
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * Network + asset bindings.
 *
 * ### Timeout configuration, and why these numbers
 *
 * | Timeout | Value | Reasoning |
 * |---|---|---|
 * | connect | 15 s | A TLS handshake to `archives.nseindia.com` on a 3G rural link is the documented failure mode (plan.md §3.1: the primary host fails the handshake and then times out). 15 s is long enough not to give up on a slow-but-progressing handshake. |
 * | read | 30 s | The bhavcopy zip is 208 KB and the legacy delivery CSV 401 KB (plan.md §14); on a slow link that is tens of seconds. |
 * | write | 15 s | Every request in this app is a GET (`:data` only ever builds GETs), so this exists for completeness, not for a code path. |
 * | call | 60 s | Above the others so the call timeout is the backstop, not the read timeout cutting off a nearly-complete download. |
 *
 * ### Interceptor order matters and is fixed
 * ```
 * HostRateLimiter  →  RetryInterceptor  →  …  →  server
 * ```
 * Rate limiting first so that the retries the second interceptor creates are spaced as well; the
 * reverse order would let a single logical request become three back-to-back requests.
 */
@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    /**
     * The default `User-Agent` for a request that does not set its own.
     *
     * Not a browser string. The NSE files need no auth (plan.md §3.1) and screener.in's required
     * browser headers are supplied per-request by `ScreenerInSource.HEADERS`, where they were
     * verified against the captured fixtures — so this only ever identifies the app, which is
     * what plan.md §3.6 asks of a good citizen of somebody else's host.
     */
    const val USER_AGENT: String = "MyInvestmentAssistant/1.0 (on-device; NSE bhavcopy reader)"

    @Provides
    @Singleton
    fun provideOkHttpClient(
        rateLimiter: HostRateLimiter,
        retryInterceptor: RetryInterceptor,
    ): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .addInterceptor(rateLimiter)
        .addInterceptor(retryInterceptor)
        .addInterceptor { chain ->
            val request = chain.request()
            val userAgent = request.header(HEADER_USER_AGENT) ?: USER_AGENT
            chain.proceed(request.newBuilder().header(HEADER_USER_AGENT, userAgent).build())
        }
        .build()

    @Provides
    @Singleton
    fun provideHostRateLimiter(): HostRateLimiter = HostRateLimiter(
        minIntervalMillis = HostRateLimiter.DEFAULT_MIN_INTERVAL_MILLIS,
        throttledHosts = HostRateLimiter.DEFAULT_HOSTS,
    )

    @Provides
    @Singleton
    fun provideRetryInterceptor(): RetryInterceptor = RetryInterceptor()

    @Provides
    @Singleton
    fun provideHttpTransport(
        client: OkHttpClient,
        @IoDispatcher dispatcher: CoroutineDispatcher,
    ): HttpTransport = OkHttpTransport(client, dispatcher)

    /**
     * The bundled symbol master seam (plan.md §9 requirement 1). `SymbolBookSource.load` handles
     * a `null` return as "not bundled in this build" and reports it as health rather than
     * throwing, so a stripped debug APK shows a message instead of a crash.
     */
    @Provides
    @Singleton
    fun provideAssetReader(@ApplicationContext context: Context): AssetReader =
        assetReaderOf(context)

    const val HEADER_USER_AGENT: String = "User-Agent"

    const val CONNECT_TIMEOUT_SECONDS: Long = 15L
    const val READ_TIMEOUT_SECONDS: Long = 30L
    const val WRITE_TIMEOUT_SECONDS: Long = 15L
    const val CALL_TIMEOUT_SECONDS: Long = 60L
}