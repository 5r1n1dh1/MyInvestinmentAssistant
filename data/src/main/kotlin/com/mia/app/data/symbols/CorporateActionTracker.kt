package com.mia.app.data.symbols

import com.mia.app.data.price.BhavcopyRow
import com.mia.app.domain.model.ParserHealth
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** What kind of identity event was seen on a session. */
enum class CorporateActionType {
    /** Was trading on an earlier session and is absent now. Emitted on the day it happens. */
    SYMBOL_DISAPPEARED,

    /** Not seen before, present today. A new listing or a newly published instrument. */
    SYMBOL_APPEARED,

    /** Same ticker, different ISIN — a relisting or a further corporate action (plan.md §9 req 3). */
    ISIN_CHANGED,

    /** Same ticker, different series code. */
    SERIES_CHANGED,

    /**
     * Disappeared, then came back after a gap long enough to matter.
     *
     * This is the trading-halt shape: a symbol that vanishes for weeks and returns is normally a
     * suspension, a scheme book-entry, or a corporate action in progress — not a delisting, and
     * certainly not a provider failure. A return *inside* the threshold is not an event at all;
     * see [CorporateActionTracker].
     */
    RESUMED_AFTER_GAP,
}

/**
 * One detected identity event.
 *
 * [detail] is pre-rendered and safe to show, because the person who has to explain this to a user
 * is the UI, not a second parser: the numbers behind it (how many sessions the symbol was gone,
 * which ISIN it had before) stop being re-derivable as soon as the session rows are pruned, so
 * they are baked into the string.
 */
data class CorporateActionEvent(
    val symbol: String,
    val isin: String?,
    val type: CorporateActionType,
    val detectedOn: LocalDate,
    val detail: String,
    /** ISIN the symbol had before an [CorporateActionType.ISIN_CHANGED]. */
    val previousIsin: String? = null,
    /** Series the symbol had before a [CorporateActionType.SERIES_CHANGED]. */
    val previousSeries: String? = null,
    /** Series seen today, when the source published one. */
    val series: String? = null,
    /** Consecutive sessions the symbol was missing, for [CorporateActionType.RESUMED_AFTER_GAP]. */
    val gapSessions: Int = 0,
)

/**
 * One session's view of the symbol universe, from whichever source produced it.
 *
 * **Why a snapshot rather than a [ReconciliationReport]**: the report is a single day and answers
 * "do the two sources agree"; the snapshot answers "what changed since last time", which is the
 * question plan.md §9 requirement 3 asks.
 *
 * [duplicateSymbols] is carried rather than tolerated: a snapshot with two rows for one ticker
 * would otherwise make a symbol look like it appeared and vanished on the same day.
 */
data class PresenceSnapshot(
    val date: LocalDate,
    val identities: List<SymbolIdentity>,
    /** Health of whatever produced this snapshot, carried so a broken day is never silent. */
    val health: ParserHealth = ParserHealth.healthy("presence snapshot"),
    val duplicateSymbols: List<String> = emptyList(),
) {
    companion object {
        /** From bhavcopy rows — the production path, fed by the EOD worker. */
        fun fromBhavcopy(date: LocalDate, rows: List<BhavcopyRow>, health: ParserHealth) =
            PresenceSnapshot(date, rows.map(SymbolIdentity::of), health, duplicates(rows.map { it.ticker }))

        /** From master records — used to seed the tracker's baseline on first run. */
        fun fromRecords(date: LocalDate, records: List<SymbolRecord>, health: ParserHealth = ParserHealth.healthy()) =
            PresenceSnapshot(date, records.map(SymbolIdentity::of), health, duplicates(records.map { it.symbol }))

        /** From bare identities — the shape a Room row round-trip has. */
        fun fromIdentities(date: LocalDate, identities: List<SymbolIdentity>, health: ParserHealth) =
            PresenceSnapshot(date, identities, health, duplicates(identities.map { it.symbol }))

        private fun duplicates(tickers: List<String>): List<String> {
            val counts = HashMap<String, Int>(tickers.size * 2)
            val out = sortedSetOf<String>()
            for (ticker in tickers) {
                if (counts.merge(SymbolNormaliser.tickerKey(ticker), 1, Int::plus) != 1) {
                    out += SymbolNormaliser.tickerKey(ticker)
                }
            }
            return out.toList()
        }
    }
}

/**
 * Learns corporate actions from a stream of daily snapshots (plan.md §9 requirement 3).
 *
 * **Pure by design.** It is fed snapshots and knows nothing about HTTP, Room, WorkManager or the
 * clock — the caller decides when a session happened and what the market contained. That is what
 * makes the `TATAMOTORS` demerger reproducible in a unit test from a hand-built series instead of
 * from a live download, and it is what leaves the Room implementation entirely to `:android-data`.
 *
 * **What counts as "previously traded".** [CorporateActionType.SYMBOL_DISAPPEARED] is only emitted
 * for symbols the tracker has already seen trade. Without that rule the first snapshot a new
 * device ever records would emit ~2,600 `SYMBOL_APPEARED` events and the UI would report
 * "everything is new today" — noise, not information.
 *
 * **Gap semantics.** A symbol that vanishes emits [CorporateActionType.SYMBOL_DISAPPEARED] on the
 * day it happens, because that is the TATAMOTORS shape and the user must see it while it is still
 * news. If it returns within [gapSessions] sessions *and* [gapDays] days, the gap is treated as a
 * blip and **no** resume event is emitted; if it returns after both thresholds it emits
 * [CorporateActionType.RESUMED_AFTER_GAP] carrying the exact length of the gap. Both bounds are
 * needed: a holiday week is ~5 sessions, and a sparse session series must not be able to fake a
 * multi-month halt.
 *
 * **Not thread-safe** — the EOD worker owns one instance and calls [record] from one coroutine.
 */
class CorporateActionTracker(
    /** Consecutive absent sessions that make a disappearance a "gap" rather than a blip. */
    val gapSessions: Int = DEFAULT_GAP_SESSIONS,
    /** Calendar days that must also pass; guards against a sparse session series. */
    val gapDays: Int = DEFAULT_GAP_DAYS,
    /** Events kept per symbol before the oldest are dropped. */
    private val historyLimit: Int = DEFAULT_HISTORY_LIMIT,
) {

    private val lastSeen = HashMap<String, LocalDate>()
    private val seenBefore = HashSet<String>()
    /** True once a snapshot has established the baseline; the first one emits nothing. */
    private var primed = false
    private val lastIsin = HashMap<String, String>()
    private val lastSeries = HashMap<String, String>()
    private val missingSince = LinkedHashMap<String, Missing>()
    private val events = LinkedHashMap<String, MutableList<CorporateActionEvent>>()

    private class Missing(
        val firstAbsentSession: Int,
        val firstAbsentOn: LocalDate,
        val lastSeenOn: LocalDate,
    )

    /** Date of the last accepted snapshot; null before the first one. */
    var lastSessionDate: LocalDate? = null
        private set

    /** Number of snapshots accepted so far. */
    var sessionCount: Int = 0
        private set

    /**
     * Feeds one session and returns the events it produced.
     *
     * **Ignores out-of-order and same-day snapshots.** Re-feeding yesterday's file after today's
     * has been processed is a real operational mistake, and it must not corrupt the gap counters
     * into a spurious `RESUMED_AFTER_GAP`. A same-day re-feed being a no-op is what makes an
     * at-least-once EOD worker safe.
     */
    fun record(snapshot: PresenceSnapshot): List<CorporateActionEvent> {
        val previousDate = lastSessionDate
        if (previousDate != null && snapshot.date <= previousDate) return emptyList()

        sessionCount++
        val session = sessionCount
        lastSessionDate = snapshot.date

        val present = HashMap<String, SymbolIdentity>(snapshot.identities.size * 2)
        for (identity in snapshot.identities) {
            present[SymbolNormaliser.tickerKey(identity.symbol)] = identity
        }

        val produced = mutableListOf<CorporateActionEvent>()

        for ((symbol, identity) in present) {
            val isin = identity.isin
            val series = identity.series

            // Identity drift first: it is the most specific event, and it is the one that changes
            // what the app may safely say about this symbol's history.
            val previousIsin = lastIsin[symbol]
            if (isin != null && previousIsin != null && isin != previousIsin) {
                produced += emit(
                    CorporateActionEvent(
                        symbol = symbol,
                        isin = isin,
                        type = CorporateActionType.ISIN_CHANGED,
                        detectedOn = snapshot.date,
                        previousIsin = previousIsin,
                        series = series,
                        detail = "$symbol now trades under ISIN $isin but was $previousIsin — the same " +
                            "ticker with a new identity, i.e. a relisting or a further corporate action.",
                    ),
                )
            }
            val previousSeries = lastSeries[symbol]
            if (series != null && previousSeries != null && series != previousSeries) {
                produced += emit(
                    CorporateActionEvent(
                        symbol = symbol,
                        isin = isin,
                        type = CorporateActionType.SERIES_CHANGED,
                        detectedOn = snapshot.date,
                        previousSeries = previousSeries,
                        series = series,
                        detail = "$symbol moved from series $previousSeries to $series " +
                            "(a series change usually means a book-entry or odd-lot phase).",
                    ),
                )
            }

            val missing = missingSince.remove(symbol)
            when {
                // Only after priming: the very first snapshot is the baseline, not news.
                missing == null && primed && !seenBefore.contains(symbol) -> produced += emit(
                    CorporateActionEvent(
                        symbol = symbol,
                        isin = isin,
                        type = CorporateActionType.SYMBOL_APPEARED,
                        detectedOn = snapshot.date,
                        series = series,
                        detail = "$symbol appeared in the bhavcopy for the first time on ${snapshot.date}.",
                    ),
                )
                missing == null -> Unit // ordinary day
                else -> {
                    val gap = session - missing.firstAbsentSession
                    val days = ChronoUnit.DAYS.between(missing.firstAbsentOn, snapshot.date).toInt()
                    // Below both thresholds the disappearance was a blip: deliberately no event,
                    // because reporting "resumed after a gap" for a one-week holiday would be noise
                    // that trains the user to ignore the chip.
                    if (gap >= gapSessions && days >= gapDays) {
                        produced += emit(
                            CorporateActionEvent(
                                symbol = symbol,
                                isin = isin,
                                type = CorporateActionType.RESUMED_AFTER_GAP,
                                detectedOn = snapshot.date,
                                series = series,
                                gapSessions = gap,
                                detail = "$symbol is trading again after $gap session(s) ($days days) " +
                                    "with no rows; last seen on ${missing.lastSeenOn}. That is the " +
                                    "shape of a trading halt or a corporate action in progress.",
                            ),
                        )
                    }
                }
            }

            if (isin != null) lastIsin[symbol] = isin
            if (series != null) lastSeries[symbol] = series
            lastSeen[symbol] = snapshot.date
            seenBefore += symbol
        }

        // Whatever is missing today that we have seen trade has gone. Iterating a copy is
        // required: `missingSince` is mutated below and `seenBefore` is not.
        //
        // `primed` gates this too: without it the first snapshot would delete all 2,593 symbols
        // that had not been seen *yet*, which is every one of them.
        val vanishedFrom = if (primed) seenBefore.toList() else emptyList()
        primed = true
        for (symbol in vanishedFrom) {
            if (symbol in present || symbol in missingSince) continue
            val lastSeenOn = lastSeen[symbol] ?: snapshot.date
            missingSince[symbol] = Missing(session, snapshot.date, lastSeenOn)
            produced += emit(
                CorporateActionEvent(
                    symbol = symbol,
                    isin = lastIsin[symbol],
                    type = CorporateActionType.SYMBOL_DISAPPEARED,
                    detectedOn = snapshot.date,
                    detail = "$symbol stopped appearing in the bhavcopy after $lastSeenOn. A symbol that " +
                        "vanishes mid-life is the corporate-action shape (plan.md §9) — the ISIN is the " +
                        "way to find where it went.",
                ),
            )
        }

        return produced
    }

    /** Events recorded for [symbol], oldest first. */
    fun eventsFor(symbol: String): List<CorporateActionEvent> =
        events[SymbolNormaliser.tickerKey(symbol)].orEmpty()

    /**
     * Events that explain [symbol]'s current state, newest first.
     *
     * Newest first because the newest event is what describes the symbol *now*. A
     * [CorporateActionType.SYMBOL_DISAPPEARED] stays in the list — the symbol really is gone, and
     * saying so is the entire point of plan.md §9.
     */
    fun activeEvents(symbol: String): List<CorporateActionEvent> = eventsFor(symbol).asReversed()

    /**
     * One-line, user-facing explanation of [symbol]'s identity state, or null when nothing
     * unusual has happened to it.
     *
     * `String?` on purpose: the UI must be able to say *nothing* for the overwhelming majority of
     * symbols, and an empty chip that says nothing is worse than no chip.
     */
    fun resolutionHint(symbol: String): String? {
        val recent = activeEvents(symbol).firstOrNull() ?: return null
        return when (recent.type) {
            CorporateActionType.SYMBOL_DISAPPEARED ->
                "${recent.symbol} is no longer in the bhavcopy (last seen ${lastSeenFor(recent.symbol)}). " +
                    "Its ticker may have changed — search the company name or look it up by ISIN."
            CorporateActionType.SYMBOL_APPEARED ->
                "${recent.symbol} is new to the bhavcopy as of ${recent.detectedOn}."
            CorporateActionType.ISIN_CHANGED ->
                "${recent.symbol} changed identity: ISIN ${recent.previousIsin} → ${recent.isin}. History " +
                    "before ${recent.detectedOn} may belong to a different entity."
            CorporateActionType.SERIES_CHANGED ->
                "${recent.symbol} changed series: ${recent.previousSeries} → ${recent.series}."
            CorporateActionType.RESUMED_AFTER_GAP ->
                "${recent.symbol} resumed after ${recent.gapSessions} sessions with no rows."
        }
    }

    /** Last session on which [symbol] was seen trading, or null when it never was. */
    fun lastSeenFor(symbol: String): LocalDate? = lastSeen[SymbolNormaliser.tickerKey(symbol)]

    /**
     * Symbols that are absent right now — the set the UI should re-check against the master,
     * because "gone from the bhavcopy" and "gone from the market" are different statements.
     */
    fun currentlyMissing(): List<String> = missingSince.keys.toList()

    /**
     * Relistings the tracker has **observed**, expressed as aliases for a [SymbolResolver].
     *
     * Only [CorporateActionType.ISIN_CHANGED] qualifies, because that is the only event that proves
     * a ticker changed identity. A [CorporateActionType.SYMBOL_DISAPPEARED] is *not* turned into
     * an alias: knowing a ticker vanished does not tell us where it went, and guessing would send a
     * user to the wrong company with full confidence. Resolve it through the master or the ISIN
     * instead.
     */
    fun detectedAliases(book: SymbolBook): List<CorporateActionAlias> =
        events.values.flatten()
            .filter { it.type == CorporateActionType.ISIN_CHANGED }
            .map { event ->
                val successor = book.find(event.symbol)
                CorporateActionAlias(
                    fromSymbol = event.symbol,
                    toSymbol = successor?.symbol ?: event.symbol,
                    event = "RELISTING",
                    effectiveFrom = event.detectedOn,
                    detail = "Observed on ${event.detectedOn}: ${event.symbol} changed ISIN from " +
                        "${event.previousIsin} to ${event.isin}.",
                )
            }

    private fun emit(event: CorporateActionEvent): CorporateActionEvent {
        val list = events.getOrPut(event.symbol) { mutableListOf() }
        list += event
        while (list.size > historyLimit) list.removeAt(0)
        return event
    }

    companion object {
        /**
         * ~20 trading sessions: long enough that a Diwali-week or exchange-holiday pause does not
         * read as a corporate action, short enough that a real suspension is caught within a month
         * of running. Overridable so a caller can be stricter or looser deliberately.
         */
        const val DEFAULT_GAP_SESSIONS: Int = 20

        /** Calendar bound on the same gap, so a sparse session series cannot fake a halt. */
        const val DEFAULT_GAP_DAYS: Int = 30

        /** Events kept per symbol before the oldest are dropped. */
        const val DEFAULT_HISTORY_LIMIT: Int = 20
    }
}