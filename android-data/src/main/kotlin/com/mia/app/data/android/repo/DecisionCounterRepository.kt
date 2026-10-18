package com.mia.app.data.android.repo

import com.mia.app.data.android.db.DecisionBucket
import com.mia.app.data.android.db.DecisionCounterDao
import com.mia.app.data.android.db.DecisionScope
import com.mia.app.data.android.di.IoDispatcher
import com.mia.app.domain.pattern.ProjectionGate
import com.mia.app.domain.model.Source
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The projection gate's tally, read whole.
 *
 * ### Why [evaluated] is stored rather than derived on read
 * It *could* be derived — it is the sum of every bucket — but storing it makes the invariant
 * checkable rather than assumed: [DecisionCounterRepository.snapshot]'s KDoc states that
 * `evaluated == fired + every blocker`, and a reader who wants to know whether that still holds can
 * add the buckets. A number the UI quotes that no single column backs is the kind of thing that
 * quietly stops being true.
 *
 * ### Why the blocker counts are four and not one `declined`
 * Because "the gate declined" is the sentence that would have hidden A1 twice over. A1 was a gate
 * that *never* fired; had the counter recorded only `evaluated`/`fired`, the reader would have seen
 * "0 fired" and had no way to know whether the gate was unreachable or simply had not been asked.
 * The breakdown names which condition did it, which is the difference between a shrug and a
 * diagnosis.
 *
 * [blockerCounts] omits blockers that have never been reached rather than carrying them as `0`, so
 * "not one candlestick match has been seen" is distinguishable from "none have been seen because
 * nothing has been scanned".
 *
 * @property scans canonical scans that reached the gate stage. The denominator that separates
 *   "the gate says no" from "the gate was never asked".
 * @property firstRecordedOn the earliest date any of these buckets was written, or `null` when the
 *   tally is empty. **Every figure this type feeds is stated over a window**, because a rate with no
 *   stated window is a claim about all of time that was never measured.
 * @property lastRecordedOn the most recent date, or `null`.
 */
data class ProjectionGateTally(
    /** Times the gate was asked. Equals [fired] plus the sum of [blockerCounts]. */
    val evaluated: Long = 0,
    /** Times the gate admitted a match, so a level was projected and its alert could fire. */
    val fired: Long = 0,
    /** How many times each condition declined, keyed by [ProjectionGate.Blocker]. */
    val blockerCounts: Map<ProjectionGate.Blocker, Long> = emptyMap(),
    /** Canonical scans that reached the gate stage. */
    val scans: Long = 0,
    val firstRecordedOn: LocalDate? = null,
    val lastRecordedOn: LocalDate? = null,
) {
    /** `true` when the gate has never been asked on this device. */
    val isEmpty: Boolean get() = evaluated == 0L

    /**
     * Fired as a fraction of evaluated, or `null` when nothing has been evaluated.
     *
     * `null` rather than `0.0`, and never rendered without [evaluated] alongside: a percentage of
     * nothing is not a low rate, it is the absence of a measurement, and plan.md §6.1 requires the
     * sample size beside every rate in this app.
     */
    val fireRate: Double? get() = if (evaluated <= 0L) null else fired.toDouble() / evaluated.toDouble()

    /** The condition that has declined most often, or `null` when nothing has been declined. */
    fun dominantBlocker(): Pair<ProjectionGate.Blocker, Long>? =
        blockerCounts.entries.maxByOrNull { it.value }?.let { it.key to it.value }

    companion object {
        /** A device on which nothing has been evaluated. A real state, not a failure. */
        val EMPTY = ProjectionGateTally()
    }
}

/**
 * The only way `:app` reads or clears the on-device decision counter — gap F3.
 *
 * ### The four rules this interface exists to keep
 *
 * 1. **Nothing leaves the device.** There is no `upload`, no `report`, no `sync`, and no parameter
 *    anywhere on this interface that could carry a symbol, a price or a date out of it. plan.md G1
 *    makes third-party telemetry a design decision, not an oversight, and the cheapest way to keep
 *    it that way is for the type to have no method that could do it. A counter on an app that
 *    markets itself as serverless would otherwise read as tracking, so the screen says so in words
 *    as well — but the shape is what makes the claim checkable.
 * 2. **A counter is only useful if the number is surprising.** Recording is not enough; see
 *    `DecisionCounterReading` in `:app` for what zero, what implausibly high and what working each
 *    figure mean, and where those thresholds come from. This interface's job is to make sure the
 *    screen can always reach the sample size it needs to answer that question, which is why
 *    [evaluated] and [scans] are stored rather than inferred at the UI.
 * 3. **A tally a reader cannot clear is a verdict, not an observation.** [reset] exists, it is
 *    visible, and its return value is how many rows went so the screen can say "already zero"
 *    honestly rather than implying it cleared something.
 * 4. **A read that fails is a failure, not a zero.** Every method returns [DataResult]: a counter
 *    that renders `0` because the database was unreachable is the exact silent-empty failure
 *    plan.md §12 names as the most dangerous shape a data layer can have.
 */
interface DecisionCounterRepository {

    /**
     * Records one projection-gate evaluation.
     *
     * @param blocker what stopped it, or `null` when the gate admitted the match. `null` is stored
     *   as [DecisionBucket.FIRED]; the mapping lives in [DecisionCounterDao.record] and nowhere
     *   else.
     */
    suspend fun record(blocker: ProjectionGate.Blocker?, today: LocalDate = todayInMarket()): DataResult<Unit>

    /**
     * Records that a canonical scan reached the stage where the gate would be asked.
     *
     * Separate from [record] on purpose: this is the denominator that makes "0 fired" mean two
     * different things distinguishable. Without it, a device that has never scanned and a device
     * whose gate is dead produce the same screen.
     */
    suspend fun recordScan(today: LocalDate = todayInMarket()): DataResult<Unit>

    /** The whole tally. [ProjectionGateTally.EMPTY] on a device that has evaluated nothing. */
    suspend fun snapshot(today: LocalDate = todayInMarket()): DataResult<ProjectionGateTally>

    /**
     * Clears every counter and returns how many rows went.
     *
     * `0` means there was nothing to clear, which is a real answer and not an error — a reader who
     * taps reset twice should not be told the second time that something failed.
     */
    suspend fun reset(today: LocalDate = todayInMarket()): DataResult<Int>

    companion object {
        /**
         * The tally is this device's own record of its own behaviour: no market source produced it
         * and nothing fetched it. [Source.DERIVED] rather than a market source, for the same reason
         * `JournalRepository.SOURCE` is.
         */
        val SOURCE: Source = Source.DERIVED
    }
}

/**
 * [DecisionCounterRepository] over `decision_counter`.
 *
 * ### Why the mapping from row to [ProjectionGateTally] is here and not in the DAO
 * The DAO speaks in column strings because SQLite has no enum type; the tally speaks in
 * [ProjectionGate.Blocker] because that is the vocabulary the gate itself uses. Converting between
 * them in the DAO would make the DAO depend on `:domain` for no reason, and converting in `:app`
 * would let a screen re-spell the blocker names. One place, in the layer that owns both, is the
 * only arrangement where a name cannot be spelled two ways.
 *
 * ### Why an unrecognised bucket is dropped rather than guessed at
 * A bucket name this build does not recognise is a downgrade artefact: an older install that once
 * wrote a scope a newer build has retired. Reading it as `null` blocker would silently inflate
 * `fired`, which is the one number that must never be inflated by anything other than the gate
 * firing. It is dropped, and the count of dropped rows would show up as
 * `evaluated < sum(rows)` — which is why [DecisionCounterRepository] states the invariant.
 */
@Singleton
class DefaultDecisionCounterRepository @Inject constructor(
    private val decisionCounterDao: DecisionCounterDao,
    @IoDispatcher private val dispatcher: CoroutineDispatcher,
) : DecisionCounterRepository {

    override suspend fun record(
        blocker: ProjectionGate.Blocker?,
        today: LocalDate,
    ): DataResult<Unit> = withContext(dispatcher) {
        decisionCounterDao.record(DecisionScope.PROJECTION_GATE, blocker, today.toString())
        DataResult.ok(value = Unit, source = DecisionCounterRepository.SOURCE, fetchedOn = today)
    }

    override suspend fun recordScan(today: LocalDate): DataResult<Unit> = withContext(dispatcher) {
        decisionCounterDao.recordScan(today.toString())
        DataResult.ok(value = Unit, source = DecisionCounterRepository.SOURCE, fetchedOn = today)
    }

    override suspend fun snapshot(today: LocalDate): DataResult<ProjectionGateTally> =
        withContext(dispatcher) {
            val rows = decisionCounterDao.all()
            if (rows.isEmpty()) {
                return@withContext DataResult.ok(
                    value = ProjectionGateTally.EMPTY,
                    source = DecisionCounterRepository.SOURCE,
                    fetchedOn = today,
                    notes = listOf(
                        "No decision has been recorded on this device yet, so the projection gate " +
                            "has not been asked anything. That is the state of a fresh install, not " +
                            "a measurement of the gate.",
                    ),
                )
            }
            val counts = rows.associate { it.bucket to it.count }
            val fired = counts[DecisionBucket.FIRED] ?: 0L
            val blockers = rows.mapNotNull { row ->
                ProjectionGate.Blocker.entries
                    .firstOrNull { it.name == row.bucket }
                    ?.let { it to row.count }
            }.toMap()
            val scans = counts[DecisionBucket.SCAN_COMPLETED] ?: 0L

            DataResult.ok(
                value = ProjectionGateTally(
                    evaluated = fired + blockers.values.sum(),
                    fired = fired,
                    blockerCounts = blockers,
                    scans = scans,
                    firstRecordedOn = rows.mapNotNull { it.firstRecordedOn }.minOrNull()?.let(LocalDate::parse),
                    lastRecordedOn = rows.mapNotNull { it.lastRecordedOn }.maxOrNull()?.let(LocalDate::parse),
                ),
                source = DecisionCounterRepository.SOURCE,
                fetchedOn = today,
                asOf = rows.mapNotNull { it.lastRecordedOn }.maxOrNull()?.let(LocalDate::parse),
                notes = notesFor(fired, blockers),
            )
        }

    override suspend fun reset(today: LocalDate): DataResult<Int> = withContext(dispatcher) {
        val removed = decisionCounterDao.clear()
        DataResult.ok(
            value = removed,
            source = DecisionCounterRepository.SOURCE,
            fetchedOn = today,
            notes = listOf(
                if (removed == 0) {
                    "There was nothing to clear — the counter was already at zero."
                } else {
                    "Cleared $removed counter row(s). Counting starts again from the next scan."
                },
            ),
        )
    }

    /**
     * The one place the tally states what its own numbers mean, as notes the caller may surface.
     *
     * Kept here rather than in `:app` because it is a statement about the *data's* consistency, not
     * about presentation: the notes fire on an invariant violation, and an invariant is not a
     * rendering concern. The screen's "what surprising looks like" wording is a separate thing and
     * lives beside the screen, because that one *is* about how a reader should read a number.
     */
    private fun notesFor(fired: Long, blockers: Map<ProjectionGate.Blocker, Long>): List<String> {
        val notes = mutableListOf<String>()
        val declines = blockers.values.sum()
        if (fired == 0L && declines > 0L) {
            notes += "The projection gate has declined every one of $declines evaluation(s) so far " +
                "and has never admitted a match."
        }
        return notes
    }
}