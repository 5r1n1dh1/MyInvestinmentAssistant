package com.mia.app.ui.di

import android.content.Context
import com.mia.app.data.android.repo.AccuracyRepository
import com.mia.app.data.android.repo.CandleRepository
import com.mia.app.data.android.repo.DataFailure
import com.mia.app.data.android.repo.DataResult
import com.mia.app.data.android.repo.FundamentalsRepository
import com.mia.app.data.android.repo.PatternRepository
import com.mia.app.data.android.repo.RedFlagRepository
import com.mia.app.data.android.repo.SymbolRepository
import com.mia.app.domain.model.Timeframe
import com.mia.app.domain.pattern.BreakoutEvaluator
import com.mia.app.ui.settings.CoverageGap
import com.mia.app.ui.settings.SettingsCoverage
import com.mia.app.ui.settings.SettingsDataSource
import com.mia.app.ui.settings.SettingsUnavailableException
import com.mia.app.ui.settings.SpeechAvailability
import com.mia.app.ui.share.BriefCard
import com.mia.app.ui.speech.HinglishSpeechRecognizer
import com.mia.app.work.CacheClearResult
import com.mia.app.work.MiaBackgroundDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [SettingsDataSource] over `:android-data` plus the two platform checks only Settings needs.
 *
 * ### Settings must render even when nothing else can
 * plan.md §1.4 rule 4 puts the SEBI disclaimer on this screen, so a coverage read that fails must
 * not take it down. `SettingsViewModel` already reads coverage and speech availability
 * independently and puts a failure into `coverageErrorReason` *inside* the coverage card; this
 * adapter's job is to make sure a partial read still yields the parts that succeeded.
 *
 * ### What the counts can and cannot mean
 * There is no `countSymbolsWithTimeframe` in the storage layer, so the per-symbol counts are computed
 * over the **tracked** symbols — the only set the user chose and the only set the nightly scan visits
 * (decision OQ-4). That is stated in [SettingsCoverage.sourceNotes] rather than left for the reader
 * to guess, because "12 of 3,051 symbols have fundamentals" and "12 of your 12 tracked symbols have
 * fundamentals" are different statements and only the second is measurable here.
 *
 * ### Nothing on this screen is fetched
 * Every read below is a cache read. "Refresh now" lives on `RefreshStatusSource`, not here, so
 * opening Settings can never spend a request.
 */
@Singleton
class RealSettingsDataSource @Inject constructor(
    @ApplicationContext private val context: Context,
    private val symbolRepository: SymbolRepository,
    private val candleRepository: CandleRepository,
    private val fundamentalsRepository: FundamentalsRepository,
    private val patternRepository: PatternRepository,
    private val redFlagRepository: RedFlagRepository,
    private val accuracyRepository: AccuracyRepository,
    @MiaBackgroundDispatcher private val backgroundDispatcher: CoroutineDispatcher,
) : SettingsDataSource {

    override suspend fun coverage(): SettingsCoverage = onBackground {
        val notes = mutableListOf<String>()
        // The tracked list is the one read that must succeed: without it the counts would be over an
        // unknown denominator, which is a fabricated statement rather than an incomplete one.
        val tracked = symbolRepository.watchlist().orThrowTyped()
        val tickers = tracked.map { it.summary.symbol }.distinct().sorted()

        var daily = 0
        var weekly = 0
        var withFundamentals = 0
        var latestBar: LocalDate? = null
        var latestFundamentals: LocalDate? = null

        for (ticker in tickers) {
            val dailySeries = candleRepository.storedSeries(ticker, Timeframe.D1, COVERAGE_BARS)
                .valueOrNull()
            if (dailySeries != null) {
                daily++
                latestBar = maxDate(latestBar, dailySeries.asOf)
            }
            val weeklySeries = candleRepository.storedSeries(ticker, Timeframe.W1, COVERAGE_BARS)
                .valueOrNull()
            if (weeklySeries != null) {
                weekly++
                latestBar = maxDate(latestBar, weeklySeries.asOf)
            }
            val fundamentalsAsOf = fundamentalsRepository.asOf(ticker)
            if (fundamentalsAsOf != null) {
                withFundamentals++
                latestFundamentals = maxDate(latestFundamentals, fundamentalsAsOf)
            }
        }

        // The whole-market session date, when one is cached. `reconciliation` compares the bundled master
        // against the rolling bhavcopy row and publishes that row's date, which is a stronger "newest
        // stored bar" than anything derivable from the tracked list alone. It answers a typed failure
        // when no bhavcopy has been cached, which is simply "there is no market-wide date yet" — not
        // something the coverage card needs to apologise for.
        val marketAsOf = symbolRepository.reconciliation().valueOrNull()?.asOf

        val withPatterns = tickers.count { ticker ->
            patternRepository.storedMatches(ticker, Timeframe.D1).valueOrNull()?.isNotEmpty() == true
        }
        val withOutcomes = accuracyRepository.report().valueOrNull()?.perSymbol
            ?.mapNotNull { it.symbol }
            ?.distinct()
            ?.size
            ?: 0
        // `storedCount` is a global `Int` over the `patterns` table. It is only ever used to explain a
        // mismatch between the global table and the tracked list, never as the `symbolsWithPatterns`
        // figure — a global count would overstate what the reader tracks.
        val storedDetections = patternRepository.storedCount()

        notes += TRACKED_SCOPE_NOTE
        if (withOutcomes == 0) {
            notes += NO_OUTCOMES_NOTE
        }
        if (storedDetections > 0 && withPatterns == 0) {
            // A non-zero global count with nothing on the tracked list means detections exist for
            // symbols that are no longer tracked. Said rather than left as two numbers that appear to
            // contradict each other.
            notes += "$storedDetections stored detection(s) exist, none of them on a symbol that is " +
                "still tracked, so they are not counted above."
        }

        SettingsCoverage(
            // "Nothing has been read" rather than "nothing exists": with no tracked symbol, no stored
            // bar and no stored page there is genuinely nothing this device knows.
            isFirstRun = tickers.isEmpty() && daily == 0 && withFundamentals == 0 &&
                storedDetections == 0 && withOutcomes == 0,
            trackedSymbolCount = tickers.size,
            symbolsWithDailyBars = daily,
            symbolsWithWeeklyBars = weekly,
            symbolsWithFundamentals = withFundamentals,
            symbolsWithPatterns = withPatterns,
            symbolsWithOutcomes = withOutcomes,
            latestBarDate = maxDate(latestBar, marketAsOf),
            latestFundamentalsDate = latestFundamentals,
            // Facts about what this build cannot do, from the screen's own declared list. They do not
            // change with a refresh, which is why they are a constant and not a computed count.
            declaredGaps = CoverageGap.DEFAULT_GAPS,
            sourceNotes = notes,
        )
    }

    /**
     * The share brief for [symbol], or `null` when nothing is stored for it.
     *
     * Built from cached data only — no fetch — because the image renders whatever is cached and
     * labels its age, and a share flow that blocks on the network is a share flow that fails on a
     * train. `BriefCard.fromCandles` does the price arithmetic (last close, day change, bar count);
     * the three fields it cannot derive — the breakout condition, the track record and the notes —
     * are added by `copy` afterwards, so `fromCandles` stays the single place that arithmetic lives.
     *
     * `null` is distinct from "stored but empty": nothing stored is the one case the share section
     * renders as a sentence instead of producing an image of nothing.
     */
    override suspend fun briefFor(symbol: String): BriefCard? = onBackground {
        val ticker = symbol.trim().uppercase()
        if (ticker.isEmpty()) return@onBackground null

        val series = candleRepository.storedSeries(ticker, Timeframe.D1).valueOrNull() ?: return@onBackground null
        val candles = series.ordered()
        if (candles.isEmpty()) return@onBackground null

        // The company name is a nicety on the image, not the image itself, so an unreadable identity
        // row yields `null` and `BriefCardContent` omits the line rather than the build failing.
        val companyName = symbolRepository.record(ticker).valueOrNull()?.name

        // The top match is what the brief is about. An unreadable scan leaves the card without one and
        // the image says so; it never invents a match.
        val scan = patternRepository.scan(ticker, Timeframe.D1).valueOrNull()
        val topMatch = scan?.matches?.firstOrNull()

        // Flags are carried with their evidence, and an absent set is an empty list on the card — which
        // `BriefCardContent` renders as "none found" rather than as a green tick.
        val flags = redFlagRepository.flags(ticker, Timeframe.D1).valueOrNull()?.flags.orEmpty()

        val trackRecords = TrackRecordReader(accuracyRepository) { failure -> SettingsUnavailableException(
            reason = failure.userFacingReason(),
            isTransient = failure.isWorthRetrying(),
        ) }

        BriefCard.fromCandles(
            symbol = ticker,
            companyName = companyName,
            timeframe = Timeframe.D1,
            candles = candles,
            minimumBars = series.minBars,
            topMatch = topMatch,
        ).copy(
            // The breakout condition travels with the brief but is never folded into the geometry
            // (decision D2): `BriefCard` has one field for each and `BriefCardContent` renders them
            // separately.
            breakoutTrigger = topMatch?.measuredMove?.let { move ->
                BreakoutEvaluator.trigger(
                    candles = candles,
                    level = move.triggerLevel,
                    direction = move.direction,
                    subject = TRIGGER_SUBJECT,
                    timeframe = Timeframe.D1,
                )
            },
            trackRecord = trackRecords.hitRate(ticker, topMatch?.name),
            trackRecordForSymbol = trackRecords.hitRate(ticker, null),
            redFlags = flags,
            notes = series.notes + scan?.notes.orEmpty(),
        )
    }

    /**
     * Clears cached market data and history.
     *
     * ### What this build cannot do, stated rather than faked
     * `:android-data` exposes **no** capability for it: every repository is read-only or
     * write-once, and the `DELETE` statements that do exist are all per-symbol
     * (`WatchlistDao.delete`, `FundamentalsDao.clearQuarters` and friends) and none of them is
     * reachable through a public repository method. So there is nothing to call, and this returns an
     * honest "nothing was removed, and here is why" instead of a count.
     *
     * `removedEntries = null` is exactly the documented meaning of that field: "the implementation
     * cannot count what it removed". The message is rendered verbatim by `CacheSection`'s caller, so
     * it has to read as an explanation rather than as a failure to read — and it must **not** claim
     * the tracked list was touched, because nothing was.
     *
     * To make this work, `:android-data` needs one method (for example
     * `CandleRepository.clearCachedMarketData(): Int`) plus the three `DELETE`s on the DAOs; that is
     * a change in a module this file does not own, and it is reported rather than worked around.
     */
    override suspend fun clearCache(): CacheClearResult = onBackground {
        CacheClearResult(
            removedEntries = null,
            message = "Nothing was removed. This build has no way to delete stored bars, " +
                "fundamentals or pattern detections, so the cache cannot be cleared from here. " +
                "Your tracked symbols and their track record are untouched either way.",
        )
    }

    /**
     * Whether voice search can work on this device.
     *
     * Asked through [HinglishSpeechRecognizer] rather than `SpeechRecognizer` directly, so the
     * settings row and the recogniser itself cannot disagree: one static availability check, one
     * permission check, one implementation. `isRecognizerPresent` uses
     * `SpeechRecognizer.isRecognitionAvailable` (the static query), which is the only safe way to ask
     * — the constructor throws on a device with no recognition service.
     *
     * Never rendered as *ready* on a guess: a false "available" would send a reader to a button that
     * cannot work.
     */
    override suspend fun speechAvailability(): SpeechAvailability = onBackground {
        val recognizer = HinglishSpeechRecognizer(context.applicationContext)
        SpeechAvailability(
            isRecognizerPresent = recognizer.isAvailable(),
            isMicrophoneGranted = recognizer.hasMicrophonePermission(),
        )
    }

    // --------------------------------------------------------------------------- internals

    private fun maxDate(current: LocalDate?, candidate: LocalDate?): LocalDate? = when {
        candidate == null -> current
        current == null -> candidate
        else -> if (candidate.isAfter(current)) candidate else current
    }

    private suspend fun <T> onBackground(block: suspend () -> T): T =
        withContext(backgroundDispatcher) { block() }

    /** The value, or this screen's typed exception carrying the failure's own words. */
    private fun <T : Any> DataResult<T>.orThrowTyped(): T =
        valueOrNull() ?: throw unavailable(failureOrNull()!!)

    private fun unavailable(failure: DataFailure) = SettingsUnavailableException(
        reason = failure.userFacingReason(),
        isTransient = failure.isWorthRetrying(),
    )

    private companion object {
        /**
         * One bar per timeframe for the coverage counts.
         *
         * `storedSeries` returns a typed failure for an empty result rather than an empty series, so
         * one row is enough to answer "does this symbol hold any bar of this timeframe" and keeps the
         * settings read to two one-row queries per tracked symbol.
         */
        const val COVERAGE_BARS: Int = 1

        /** See `RealTechnicalsDataSource`; identical wording so both surfaces word it the same way. */
        const val TRIGGER_SUBJECT: String = "pattern boundary"

        /**
         * Stated in `sourceNotes` so the counts cannot be read as market-wide coverage.
         */
        const val TRACKED_SCOPE_NOTE: String =
            "The per-symbol counts above cover the symbols you track, which is the only set this " +
                "build measures coverage for; they are not counts across the whole market."

        const val NO_OUTCOMES_NOTE: String =
            "No pattern outcome has settled on this device, so there is no hit rate to show yet. " +
                "That is the state of every install before the nightly scan has run a few sessions."
    }
}