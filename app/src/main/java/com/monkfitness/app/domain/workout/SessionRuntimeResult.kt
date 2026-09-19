package com.monkfitness.app.domain.workout

import com.monkfitness.app.domain.common.AdjustmentId
import com.monkfitness.app.domain.common.ProgramDayId
import com.monkfitness.app.domain.common.ProgramExerciseId
import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.common.RevisionId
import com.monkfitness.app.domain.common.SessionExerciseId
import com.monkfitness.app.domain.common.SessionId
import com.monkfitness.app.domain.common.SlotId
import com.monkfitness.app.domain.prescription.PrescriptionDimension

/**
 * The outcome of one session-runtime operation (§28's *"expected states are results, not exceptions"*).
 *
 * The five operations of §30 step 8 answer questions a user is allowed to ask and whose answers are
 * ordinary: *this slot is already being worked out*, *this attempt was cancelled*, *this set is not in
 * the unit that exercise is prescribed in*, *this occurrence is skipped*. Each of those is a
 * [Refused] a screen can present, with the rule as a typed value rather than a sentence to be matched
 * on. A [Failure] is the third case — §28's `SYSTEM_FAILURE` / `INVALID_DATA`, surfaced rather than
 * absorbed, because a repository that turned a storage failure into a `null` or an empty list is
 * exactly the *"catch `Exception` and return empty result"* §33 forbids.
 *
 * The envelope is this stage's own rather than [com.monkfitness.app.domain.program.ProgramOperationResult]'s
 * for the same reason the Scheduler's is: what an operation produces here is a [WorkoutSession] or a
 * [SessionCompletion], and the refusals are about one attempt at one opportunity. The *sentences* are
 * shared where a rule is shared — a Program that is not stored is the same fact for every layer, and a
 * revision that belongs to another Program is the same identity rule the schedule and the plan are
 * read under.
 */
sealed interface SessionRuntimeResult<out T> {

    /** The operation succeeded and produced [value]. */
    data class Success<T>(val value: T) : SessionRuntimeResult<T>

    /**
     * The operation was refused by a rule of §19 or §16. Nothing was written: every refusal is decided
     * before the first row of the operation, and the one that is decided *by* a write — a second
     * `IN_PROGRESS` attempt at one slot — refuses inside the transaction that would have written it.
     */
    data class Refused(val reason: SessionRefusal) : SessionRuntimeResult<Nothing>

    /** The operation failed for a reason that is not one of those rules: a storage failure, or data that is invalid. */
    data class Failure(val cause: Throwable) : SessionRuntimeResult<Nothing>
}

/**
 * The rules that can refuse a session operation (§19, §16, §28), each carrying the sentence that
 * explains it.
 *
 * Typed rather than a message string, for the same reason the Scheduler's refusals are: a screen wants
 * the sentence, and a test wants to assert *which* rule fired without matching on prose. Every case
 * names the identity it is about, so a refusal is actionable without the caller re-reading the value
 * it passed in.
 */
sealed interface SessionRefusal {

    /** The user-facing sentence. */
    val message: String

    /** The Slot an operation names is not stored. */
    data class SlotNotFound(val slotId: SlotId) : SessionRefusal {
        override val message: String = "no opportunity with id '${slotId.value}' is stored (§20)"
    }

    /** The Session an operation names is not stored. */
    data class SessionNotFound(val sessionId: SessionId) : SessionRefusal {
        override val message: String = "no session with id '${sessionId.value}' is stored (§19)"
    }

    /**
     * The revision a slot names is not stored, so the presentation it was planned from cannot be read.
     *
     * Reported rather than treated as *"nothing to present"*: a slot always names a revision, so a
     * missing one is invalid data, not an empty workout (§23).
     */
    data class RevisionNotFound(val revisionId: RevisionId) : SessionRefusal {
        override val message: String =
            "the revision '${revisionId.value}' this opportunity was scheduled from is not stored (§23)"
    }

    /**
     * The revision a slot names belongs to a **different** Program than the slot does.
     *
     * The identity triple is one fact: an opportunity is bound to a Program, to the revision it was
     * scheduled from, and to a plan day of that revision. A stored slot whose revision names another
     * Program cannot be started — whichever of the two rows is wrong, the session it would produce
     * would be bound to an identity that does not hold.
     */
    data class RevisionIsOfAnotherProgram(
        val revisionId: RevisionId,
        val programId: ProgramId,
        val ownerProgramId: ProgramId
    ) : SessionRefusal {
        override val message: String =
            "the revision '${revisionId.value}' belongs to Program '${ownerProgramId.value}', not to " +
                "'${programId.value}' (§19: a session is bound to one Program, one revision and one slot)"
    }

    /**
     * The plan day a slot names is not one of the revision's days.
     *
     * A slot presents a concrete day of a concrete revision (§20), so a slot naming a day that revision
     * does not have describes a presentation that was never planned.
     */
    data class SlotNamesAPlanDayTheRevisionDoesNotPresent(
        val slotId: SlotId,
        val programDayId: ProgramDayId,
        val revisionId: RevisionId
    ) : SessionRefusal {
        override val message: String =
            "the plan day '${programDayId.value}' this opportunity names is not part of the revision " +
                "'${revisionId.value}' (§19)"
    }

    /**
     * The opportunity was taken: a completed session left the slot `COMPLETED`.
     *
     * A completed opportunity is history and a second attempt at it would have to overwrite the stamp
     * of the workout that took it, so it is refused rather than started. This is **not** a refusal to
     * attempt a slot twice: several attempts are legal (§19), and what a second one needs is a first
     * that ended — a cancelled attempt leaves the opportunity open exactly as it was.
     */
    data class SlotIsAlreadyCompleted(val slotId: SlotId) : SessionRefusal {
        override val message: String =
            "the opportunity '${slotId.value}' was already taken by a completed session (§19); " +
                "several attempts are allowed, but not at an opportunity that is already completed"
    }

    /**
     * The opportunity was withdrawn: a later plan stopped presenting it, so the user was not expected
     * to train it (§20 — `SUPERSEDED` is not `MISSED`).
     */
    data class SlotIsSuperseded(val slotId: SlotId) : SessionRefusal {
        override val message: String =
            "the opportunity '${slotId.value}' was superseded by a later plan, so it is not trained (§20)"
    }

    /**
     * The plan day presents nothing to perform — a rest day.
     *
     * §20 states it directly: *"REST may exist as a Slot without Session"*. A rest day is an
     * opportunity with no workout, so a session for it would be a session with no exercise, no set and
     * no measurement — and the runtime does not invent one to have something to store.
     */
    data class PlanDayPresentsNothing(val slotId: SlotId, val programDayId: ProgramDayId) : SessionRefusal {
        override val message: String =
            "the plan day '${programDayId.value}' presents no exercise, so the opportunity " +
                "'${slotId.value}' has no workout to start (§20: a rest day may exist as a slot without a session)"
    }

    /**
     * A stored adjustment of this slot names a plan element the slot's own revision does not present.
     *
     * §16's composition is `ProgramRevision + AdaptiveAdjustment = EffectiveWorkout`, and an adjustment
     * is expressed against the element it changes: `before` and `after` are plan elements, per set.
     * When the element is not in the revision the opportunity was scheduled from, the composition is
     * **undefined** for this slot — and the runtime refuses rather than applying what it can and
     * starting a workout that silently omits a change the user's stored data says applies to it.
     */
    data class StoredAdjustmentIsNotOfThisRevision(
        val slotId: SlotId,
        val adjustmentId: AdjustmentId,
        val programExerciseId: ProgramExerciseId
    ) : SessionRefusal {
        override val message: String =
            "the adjustment '${adjustmentId.value}' still standing for the opportunity " +
                "'${slotId.value}' changes the plan element '${programExerciseId.value}', which this " +
                "opportunity's revision does not present (§16)"
    }

    /**
     * The slot already holds an `IN_PROGRESS` attempt, and §19 allows **at most one** per opportunity.
     *
     * The rule is decided by the write, not by this refusal: the start's insert stores the session only
     * when the slot holds no other attempt in the same status, so this refusal is what a *refused write*
     * is reported as, and it is atomic — the second attempt leaves nothing behind. [sessionId] names
     * the attempt that holds the slot when the runtime can read it back, so a screen can offer to
     * resume it.
     */
    data class SlotIsAlreadyBeingWorkedOut(val slotId: SlotId, val sessionId: SessionId?) : SessionRefusal {
        override val message: String =
            "the opportunity '${slotId.value}' is already being worked out" +
                (sessionId?.let { " by session '${it.value}'" } ?: "") +
                ": no more than one session per slot is IN_PROGRESS (§19)"
    }

    /**
     * The session is not `IN_PROGRESS`, so an operation that continues it cannot apply.
     *
     * `CANCELLED` is not a quiet `COMPLETED` §19: a cancelled attempt is not a completed workout, it
     * does not complete its opportunity, and nothing continues it afterwards. Confirming a set into it,
     * finishing it or cancelling it a second time are all this one rule.
     */
    data class SessionIsNotInProgress(val sessionId: SessionId, val status: SessionStatus) : SessionRefusal {
        override val message: String =
            "the session '${sessionId.value}' is ${status.name}, so it cannot be continued: a session " +
                "is worked in while it is IN_PROGRESS (§19)"
    }

    /**
     * The exercise occurrence a set names is not one this session runs.
     *
     * A set belongs to the occurrence it was performed in, so a set for an occurrence another session
     * holds — or for one that was never started — would attach work to a workout that did not do it.
     */
    data class OccurrenceIsNotOfThisSession(
        val sessionId: SessionId,
        val sessionExerciseId: SessionExerciseId
    ) : SessionRefusal {
        override val message: String =
            "the occurrence '${sessionExerciseId.value}' is not one of the session " +
                "'${sessionId.value}', so no set of it is logged here (§19)"
    }

    /**
     * The occurrence was skipped, so there is no set of it to confirm.
     *
     * §12's rule is that a skipped exercise observed nothing: skipping already means *"not this one"*,
     * and a confirmed set beside it would be two contradicting records of the same occurrence.
     */
    data class OccurrenceWasSkipped(
        val sessionId: SessionId,
        val sessionExerciseId: SessionExerciseId
    ) : SessionRefusal {
        override val message: String =
            "the occurrence '${sessionExerciseId.value}' was skipped, so it observed no sets (§12); " +
                "un-skip it before logging work against it"
    }

    /**
     * The set is not logged in the unit its prescription was written in (§10).
     *
     * A repetition set carries repetitions and no time, a timed set carries time and no repetitions —
     * and a *set that was not performed* is not a set of zeroes at all, it is absent (§12). So a set
     * with neither amount, or with both, or with the one the prescription does not measure, is refused
     * rather than stored.
     */
    data class SetIsNotInThePrescribedUnit(
        val sessionExerciseId: SessionExerciseId,
        val dimension: PrescriptionDimension,
        val completedReps: Int,
        val durationSeconds: Int
    ) : SessionRefusal {
        override val message: String =
            "the occurrence '${sessionExerciseId.value}' is prescribed in $dimension, so a confirmed " +
                "set of it is logged in that unit: reps=$completedReps seconds=$durationSeconds (§10)"
    }

    /**
     * The adaptive decision handed to a completion is about another opportunity.
     *
     * A decision is *"about one slot of one revision, never about a program in the abstract"* (§16), and
     * the completion writes the decision and the session as one fact — so the decision must be the one
     * this completion produced: the same Program, the same revision and the same slot as the session.
     * The alternative would file a change about one opportunity under another one's history.
     */
    data class AdaptiveDecisionIsOfAnotherOpportunity(
        val sessionId: SessionId,
        val programId: ProgramId,
        val revisionId: RevisionId,
        val slotId: SlotId,
        val decisionProgramId: ProgramId,
        val decisionRevisionId: RevisionId,
        val decisionSlotId: SlotId
    ) : SessionRefusal {
        override val message: String =
            "the completion of session '${sessionId.value}' can only record a decision about its own " +
                "opportunity (${programId.value}/${revisionId.value}/${slotId.value}), but the decision " +
                "named (${decisionProgramId.value}/${decisionRevisionId.value}/${decisionSlotId.value})"
    }
}
