package com.monkfitness.app.domain.program.target

import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.common.SessionId
import com.monkfitness.app.domain.program.WorkoutSlot
import com.monkfitness.app.domain.workout.SessionStatus
import com.monkfitness.app.domain.workout.WorkoutSession

/**
 * The **stored execution facts** of one target occurrence: the four layers a later phase needs, kept
 * apart rather than collapsed.
 *
 * ```text
 * occurrence   1. semantic target occurrence   PersistedTargetOccurrence
 * slot         2. opportunity / slot state     WorkoutSlot
 * attempts     3. session attempt state        List<WorkoutSession>
 * performed    4. performed work               SessionExercise → SetResult, inside each attempt
 * ```
 *
 * The four are separate columns because they are separate facts, and the collapses this type
 * deliberately refuses are what make it worth having:
 *
 *  * [slot] is an **opportunity fact**, not an execution state. `SlotStatus.MISSED` is not an
 *    `OccurrenceExecution`; `SUPERSEDED` is not one either; `PLANNED` is not evidence that no
 *    historical session exists; and `SlotStatus.COMPLETED` is an outcome of the opportunity, which
 *    is a *different token* from `SessionStatus.COMPLETED` and stays distinguishable here. A
 *    `PLANNED` slot carrying a completed attempt is a legal stored state, and a `COMPLETED` slot
 *    with a cancelled last attempt is another; nothing in this type reconciles them, because no
 *    documented contract says how.
 *  * [attempts] is **every** stored attempt, in the session repository's own start order. A slot may
 *    be attempted repeatedly (§19), so a cancelled attempt followed by a later in-progress one is
 *    two facts and not a sequence to be resolved. Nothing here collapses them, filters them, picks
 *    the latest, or invents a precedence rule merely because `ExistingOccurrence` holds a single
 *    execution enum.
 *  * performed work stays as the session's own [SessionExercise] graph — `exerciseId`, the captured
 *    `prescription`, the confirmed [com.monkfitness.app.domain.workout.SetResult]s in confirmation
 *    order and the explicit `skipped` flag. It is **not** summed into target `ActualResult`s: there
 *    is no documented contract relating a session's `exerciseId` to a target occurrence's
 *    `workId`, and "sum all sets into one actual" is an invention, not a read-back.
 *
 * ### Why this is not `ExistingOccurrence`
 *
 * ```text
 * TargetOccurrenceExecutionRecord   stored facts, four layers, no verdict
 * ExistingOccurrence                the same semantics + ONE execution enum + ActualResults
 * ```
 *
 * Mapping the first onto the second needs a policy this phase has no warrant to set: with several
 * attempts there is no stored fact that says which one decides the occurrence's execution state, and
 * choosing one is exactly the "invent a precedence rule" failure this phase exists to avoid. So
 * nothing in this file constructs an `OccurrenceExecution`, an `ActualResult` or an
 * [com.monkfitness.app.domain.program.ExistingOccurrence], and the record deliberately carries no
 * verdict-shaped member. A later phase may define that policy explicitly; until then the facts are
 * readable and the decision is absent.
 *
 * ### The identity, and nothing else
 *
 * The target occurrence is identified solely by `(programId, occurrenceKey)`; [slotId] appears only
 * because the stored slot is a fact in its own right and the session graph hangs off it. No
 * `revisionId`, `programDayId`, planned date, weekday, `ProgramDay` position or name, and no parsed
 * occurrence key, stands in for the identity anywhere in this type.
 *
 * @property occurrence the Phase 14 semantic record, exactly as persisted.
 * @property slot the persisted slot that presents the occurrence, exactly as stored.
 * @property attempts every stored session attempt for [slot], in the session repository's start
 *   order — never filtered, never reordered, never collapsed.
 */
data class TargetOccurrenceExecutionRecord(
    val occurrence: PersistedTargetOccurrence,
    val slot: WorkoutSlot,
    val attempts: List<WorkoutSession> = emptyList()
) {

    init {
        require(occurrence.occurrenceKey == slot.targetOccurrenceKey) {
            "a target execution record is read through one target identity: occurrence " +
                "'${occurrence.occurrenceKey}' cannot present slot '${slot.slotId.value}', which " +
                "stores '${slot.targetOccurrenceKey}'"
        }
        require(occurrence.programId == slot.programId) {
            "a slot belongs to the Program whose occurrence it presents: occurrence " +
                "'${occurrence.occurrenceKey}' is of ${occurrence.programId.value} but slot " +
                "'${slot.slotId.value}' is of ${slot.programId.value}"
        }
        require(attempts.all { attempt -> attempt.slotId == slot.slotId }) {
            "every attempt in a target execution record attempted the record's own slot: slot " +
                "'${slot.slotId.value}' got " +
                attempts.filter { it.slotId != slot.slotId }.map { it.sessionId.value }
        }
        require(attempts.all { attempt -> attempt.programId == occurrence.programId }) {
            "every attempt in a target execution record belongs to the record's Program: " +
                "${occurrence.programId.value} got " +
                attempts.filter { it.programId != occurrence.programId }.map { it.sessionId.value }
        }
        require(attempts.map { it.sessionId }.toSet().size == attempts.size) {
            "a session attempt is stored once, so it cannot be listed twice for one slot"
        }
    }

    /** The Program that holds this target occurrence. */
    val programId: ProgramId get() = occurrence.programId

    /** The occurrence's own key — read, never parsed. */
    val occurrenceKey: String get() = occurrence.occurrenceKey

    /**
     * The slot's stored status, kept exactly as persisted.
     *
     * This is an **opportunity outcome**, not an execution state, and it is exposed under its own
     * name precisely so it cannot be read as one. A `MISSED` slot is not a cancelled occurrence, and
     * a `COMPLETED` slot is not a `SessionStatus.COMPLETED` session.
     */
    val slotStatus get() = slot.status

    /** The identities of the stored attempts, in start order, as the slot itself records them. */
    val attemptIds: List<SessionId> get() = attempts.map { it.sessionId }

    /**
     * The exact stored `SessionStatus` of every attempt, in start order.
     *
     * A list rather than one value, because the storage model permits several attempts and a single
     * token would already be a precedence decision. It is read from each session's own persisted
     * `status`; it is never derived from `finishedAt`, which §19's contract already ties to the
     * status and which therefore carries no independent information.
     */
    val attemptStatuses: List<SessionStatus> get() = attempts.map { it.status }

    /** Whether any attempt is stored at all — a fact about the store, not a verdict about it. */
    val hasAttempts: Boolean get() = attempts.isNotEmpty()

    /**
     * Every confirmed set across every attempt, flattened **in attempt order, then set order**.
     *
     * The two orders are the stored ones: the session repository's start order for attempts, and
     * §27's `1..n` confirmation order inside each. Nothing is summed, aggregated or deduplicated
     * here, because a sum is a rule and this is a read.
     */
    fun confirmedSets() = attempts.flatMap { attempt ->
        attempt.exercises.flatMap { exercise -> exercise.results }
    }

    /**
     * The exercises the user explicitly skipped, in attempt order, then presentation order.
     *
     * A skipped exercise observed nothing (§12), so it is preserved as its own fact rather than
     * being folded into the confirmed sets or dropped as empty.
     */
    fun skippedExercises() = attempts.flatMap { attempt ->
        attempt.exercises.filter { exercise -> exercise.skipped }
    }
}

/**
 * What a later phase may reason about **without being handed a verdict**: how many attempts exist and
 * how many sit in each stored status.
 *
 * This is a pure aggregation over [TargetOccurrenceExecutionRecord] and it stops there. It counts
 * what is stored and it reports which statuses are present; it does **not** reduce them to one
 * `OccurrenceExecution`, does not pick a representative attempt, and does not rank or filter
 * attempts. With `CANCELLED → IN_PROGRESS` and `CANCELLED → COMPLETED` both legal histories, "which
 * attempt counts" is a policy, and no stored fact answers it.
 *
 * The distinction is deliberate: an empty [TargetOccurrenceExecutionRecord.attemptStatuses] is a
 * planned occurrence and reads as a count of zero, while a record holding several attempts of mixed
 * statuses is a genuinely undecided state that this vocabulary can describe and cannot resolve.
 *
 * @property totalAttempts how many attempts are stored for the slot.
 * @property byStatus how many stored attempts hold each status, for every stored status.
 */
data class TargetOccurrenceExecutionCounts(
    val totalAttempts: Int,
    val byStatus: Map<SessionStatus, Int>
) {

    /** The stored count for [status], or zero when no attempt holds it. */
    fun countOf(status: SessionStatus): Int = byStatus[status] ?: 0
}

/**
 * The pure aggregation boundary of §30 step 15.
 *
 * Everything here is a reduction over an already-read [TargetOccurrenceExecutionRecord], so it is
 * collaborator-free: no repository, no clock, no identity generator, no planner and no policy. It
 * exists so a later phase has a *counting* vocabulary to build on without this phase having to
 * supply the *decision* vocabulary that counting implies.
 */
object TargetOccurrenceExecutionFacts {

    /** How many attempts each stored status holds, over every stored attempt. */
    fun countsOf(record: TargetOccurrenceExecutionRecord): TargetOccurrenceExecutionCounts =
        TargetOccurrenceExecutionCounts(
            totalAttempts = record.attempts.size,
            byStatus = record.attemptStatuses.groupingBy { it }.eachCount()
        )

    /**
     * Every stored attempt holding [status], in the stored start order.
     *
     * A filter over stored facts, not a choice: it returns all of them or none, and it never
     * reorders what it returns.
     */
    fun attemptsWithStatus(
        record: TargetOccurrenceExecutionRecord,
        status: SessionStatus
    ): List<WorkoutSession> = record.attempts.filter { it.status == status }
}

/**
 * The refusals a target execution read-back can produce.
 *
 * Every one of them is **corrupted or mismatched stored data**, never an ordinary absence. A target
 * occurrence that simply has no session attempts yet is a valid record with an empty [attempts] list
 * — that is the normal state of a planned workout, and it reads as an empty list rather than as a
 * failure. What is not normal is a semantic record with no slot, a slot under someone else's
 * Program, a session pointing at another slot, or a session graph that does not hold together; each
 * is reported rather than skipped, repaired, defaulted or turned into an empty record.
 *
 * The hierarchy is [IllegalStateException] because these describe a database whose stored target
 * execution graph disagrees with itself, not a caller's bad argument — a caller cannot construct any
 * of these situations without writing to storage first.
 */
sealed class TargetOccurrenceExecutionReadException(
    message: String
) : IllegalStateException(message) {

    /**
     * The requested target occurrence has no stored semantic record.
     *
     * @property programId the Program the lookup was made in.
     * @property occurrenceKey the requested key, as text and never as a source of anything.
     */
    data class MissingTargetOccurrence(
        val programId: ProgramId,
        val occurrenceKey: String
    ) : TargetOccurrenceExecutionReadException(
        "target occurrence '$occurrenceKey' of ${programId.value} has no stored semantic record"
    )

    /**
     * A stored target occurrence exists, but the slot that should present it does not.
     *
     * Stage 14 writes the two in one transaction, so this is unreachable through that path; it is
     * reported because a row written by any other means — a restore, a hand-edited database, a
     * partially rolled back import — must fail loudly rather than read back as an occurrence with
     * no opportunity.
     *
     * @property programId the Program the lookup was made in.
     * @property occurrenceKey the requested key.
     */
    data class MissingTargetSlot(
        val programId: ProgramId,
        val occurrenceKey: String
    ) : TargetOccurrenceExecutionReadException(
        "target occurrence '$occurrenceKey' of ${programId.value} is stored with no slot " +
            "presenting it"
    )

    /**
     * The slot found for the key belongs to a different Program than the one requested.
     *
     * @property requestedProgramId the Program the lookup was made in.
     * @property slotProgramId the Program the stored slot actually belongs to.
     * @property occurrenceKey the requested key.
     */
    data class SlotBelongsToAnotherProgram(
        val requestedProgramId: ProgramId,
        val slotProgramId: ProgramId,
        val occurrenceKey: String
    ) : TargetOccurrenceExecutionReadException(
        "the slot presenting target occurrence '$occurrenceKey' of ${requestedProgramId.value} " +
            "belongs to ${slotProgramId.value}"
    )

    /**
     * The slot found for the key stores a different target key than the one requested.
     *
     * @property requestedOccurrenceKey the key the lookup asked for.
     * @property storedOccurrenceKey the key the found slot actually stores.
     */
    data class SlotTargetKeyMismatch(
        val requestedOccurrenceKey: String,
        val storedOccurrenceKey: String?,
        val slotIdValue: String
    ) : TargetOccurrenceExecutionReadException(
        "slot '$slotIdValue' was found for target occurrence '$requestedOccurrenceKey' but stores " +
            "'$storedOccurrenceKey'"
    )

    /**
     * A session returned for the slot belongs to a different Program.
     *
     * @property requestedProgramId the Program of the record being read.
     * @property sessionProgramId the Program the stored session actually belongs to.
     * @property sessionIdValue the offending session's identity.
     */
    data class SessionBelongsToAnotherProgram(
        val requestedProgramId: ProgramId,
        val sessionProgramId: ProgramId,
        val sessionIdValue: String
    ) : TargetOccurrenceExecutionReadException(
        "session '$sessionIdValue' attempted a slot of ${requestedProgramId.value} but belongs to " +
            "${sessionProgramId.value}"
    )

    /**
     * A session returned for the slot was started under a different revision than the slot's.
     *
     * §19 binds a started session to the slot it was started at, and the stored
     * `session_snapshot` carries the same triple, so a session under another revision than its slot
     * is a broken stored graph rather than a legitimate later attempt.
     *
     * @property slotIdValue the slot the session was returned for.
     * @property sessionIdValue the offending session's identity.
     * @property slotRevisionIdValue the revision the slot was scheduled from.
     * @property sessionRevisionIdValue the revision the session was started under.
     */
    data class SessionBelongsToAnotherRevision(
        val slotIdValue: String,
        val sessionIdValue: String,
        val slotRevisionIdValue: String,
        val sessionRevisionIdValue: String
    ) : TargetOccurrenceExecutionReadException(
        "session '$sessionIdValue' attempted slot '$slotIdValue' of revision " +
            "'$slotRevisionIdValue' but was started under revision '$sessionRevisionIdValue'"
    )

    /**
     * A session was returned for a slot it does not attempt.
     *
     * The link lives on `workout_session.slotId` (§23) with no second copy on the slot row, so this
     * means the read followed a link that the stored rows disagree about.
     *
     * @property slotIdValue the slot the session was returned for.
     * @property sessionIdValue the offending session's identity.
     * @property sessionSlotIdValue the slot the session actually attempts.
     */
    data class SessionReferencesAnotherSlot(
        val slotIdValue: String,
        val sessionIdValue: String,
        val sessionSlotIdValue: String
    ) : TargetOccurrenceExecutionReadException(
        "session '$sessionIdValue' was returned for slot '$slotIdValue' but attempts slot " +
            "'$sessionSlotIdValue'"
    )
}
