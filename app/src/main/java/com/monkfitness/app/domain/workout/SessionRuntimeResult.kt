package com.monkfitness.app.domain.workout

import com.monkfitness.app.domain.common.DecisionId
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
 * The outcome of one session-runtime operation: expected states are results, not exceptions.
 *
 * The questions a user is allowed to ask have ordinary answers — this slot is already being worked out,
 * this attempt was cancelled, this set is not in the prescribed unit, this occurrence is skipped — so
 * each is a [Refused] a screen can present, with the rule as a typed value rather than a sentence to be
 * matched on. A [Failure] is the third case, surfaced rather than absorbed: a repository turning a
 * storage failure into `null` or an empty list is the catch-all this model forbids.
 *
 * The envelope is this stage's own because an operation here produces a [WorkoutSession] or a
 * [SessionCompletion], and its refusals are about one attempt at one opportunity.
 */
sealed interface SessionRuntimeResult<out T> {

    data class Success<T>(val value: T) : SessionRuntimeResult<T>

    /**
     * The operation was refused by a rule. Nothing was written: every refusal is decided before the first
     * row of the operation, and the one decided by a write — a second `IN_PROGRESS` attempt at one slot —
     * refuses inside the transaction that would have written it.
     */
    data class Refused(val reason: SessionRefusal) : SessionRuntimeResult<Nothing>

    /** A storage failure or invalid data — not one of those rules. */
    data class Failure(val cause: Throwable) : SessionRuntimeResult<Nothing>
}

/**
 * The rules that can refuse a session operation, each carrying the sentence that explains it. Typed
 * rather than a message string so a screen gets the sentence and a test can assert which rule fired
 * without matching on prose; every case names the identity it is about, so a refusal is actionable
 * without the caller re-reading the value it passed in.
 */
sealed interface SessionRefusal {

    /** The user-facing sentence. */
    val message: String

    data class SlotNotFound(val slotId: SlotId) : SessionRefusal {
        override val message: String = "no opportunity with id '${slotId.value}' is stored (§20)"
    }

    data class SessionNotFound(val sessionId: SessionId) : SessionRefusal {
        override val message: String = "no session with id '${sessionId.value}' is stored (§19)"
    }

    /**
     * The revision a slot names is not stored, so the presentation it was planned from cannot be read.
     * A slot always names a revision, so a missing one is invalid data, not an empty workout.
     */
    data class RevisionNotFound(val revisionId: RevisionId) : SessionRefusal {
        override val message: String =
            "the revision '${revisionId.value}' this opportunity was scheduled from is not stored (§23)"
    }

    /**
     * The revision a slot names belongs to a different Program than the slot does. The identity triple is
     * one fact — an opportunity is bound to a Program, the revision it was scheduled from and a plan day
     * of that revision — so whichever row is wrong, the session would be bound to an identity that does
     * not hold.
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
     * The plan day a slot names is not one of the revision's days, so it describes a presentation that was
     * never planned.
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
     * A completed session left the slot `COMPLETED`. A second attempt would have to overwrite the stamp of
     * the workout that took it. This is not a refusal to attempt a slot twice — several attempts are legal,
     * and a cancelled attempt leaves the opportunity open exactly as it was.
     */
    data class SlotIsAlreadyCompleted(val slotId: SlotId) : SessionRefusal {
        override val message: String =
            "the opportunity '${slotId.value}' was already taken by a completed session (§19); " +
                "several attempts are allowed, but not at an opportunity that is already completed"
    }

    /**
     * A later plan stopped presenting the opportunity, so the user was not expected to train it —
     * `SUPERSEDED` is not `MISSED`.
     */
    data class SlotIsSuperseded(val slotId: SlotId) : SessionRefusal {
        override val message: String =
            "the opportunity '${slotId.value}' was superseded by a later plan, so it is not trained (§20)"
    }

    /**
     * The plan day presents nothing to perform — a rest day may exist as a slot without a session. A
     * session for it would have no exercise, no set and no measurement, and the runtime does not invent
     * one to have something to store.
     */
    data class PlanDayPresentsNothing(val slotId: SlotId, val programDayId: ProgramDayId) : SessionRefusal {
        override val message: String =
            "the plan day '${programDayId.value}' presents no exercise, so the opportunity " +
                "'${slotId.value}' has no workout to start (§20: a rest day may exist as a slot without a session)"
    }

    /**
     * A stored adjustment of this slot names a plan element the slot's own revision does not present, so
     * the composition `revision + adjustment` is undefined for this slot. The runtime refuses rather than
     * applying what it can and starting a workout that silently omits a change the stored data says
     * applies.
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
     * The slot already holds an `IN_PROGRESS` attempt, and at most one per opportunity is allowed. The rule
     * is decided by the write, not by this refusal: the insert stores the session only when the slot holds
     * no other attempt in that status, so this is what a refused write is reported as, atomically.
     * [sessionId] names the attempt holding the slot when it can be read back, so a screen can offer to
     * resume it.
     */
    data class SlotIsAlreadyBeingWorkedOut(val slotId: SlotId, val sessionId: SessionId?) : SessionRefusal {
        override val message: String =
            "the opportunity '${slotId.value}' is already being worked out" +
                (sessionId?.let { " by session '${it.value}'" } ?: "") +
                ": no more than one session per slot is IN_PROGRESS (§19)"
    }

    /**
     * The session is not `IN_PROGRESS`, so nothing continues it: `CANCELLED` is not a quiet `COMPLETED`, it
     * does not complete its opportunity, and confirming a set into it, finishing it or cancelling it again
     * are all this one rule.
     */
    data class SessionIsNotInProgress(val sessionId: SessionId, val status: SessionStatus) : SessionRefusal {
        override val message: String =
            "the session '${sessionId.value}' is ${status.name}, so it cannot be continued: a session " +
                "is worked in while it is IN_PROGRESS (§19)"
    }

    /**
     * A set belongs to the occurrence it was performed in, so a set for another session's occurrence — or
     * for one never started — would attach work to a workout that did not do it.
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
     * A skipped exercise observed nothing: skipping already means "not this one", and a confirmed set
     * beside it would be two contradicting records of the same occurrence.
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
     * A repetition set carries repetitions and no time, a timed set time and no repetitions, and a set that
     * was not performed is absent rather than a set of zeroes. So a set with neither amount, with both, or
     * with the one the prescription does not measure is refused rather than stored.
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
     * Why an adaptive decision may not be recorded by one completion. The rule is one sentence — an
     * adaptive decision belongs to a future opportunity of the same Program and revision as the completion
     * it was taken on — and each member names one way a decision can fail it. They stay separate because
     * they mean different things: a decision about another Program is a wiring defect, one about the
     * opportunity just completed can never be consumed, one whose day has passed can no longer be
     * consumed, and one no longer ahead by status is stale and has to be re-taken.
     */
    enum class AdaptiveTargetRefusal {

        ANOTHER_PROGRAM,

        /** Adaptive state is revision-scoped. */
        ANOTHER_REVISION,

        /**
         * The decision names the opportunity this completion just took. Its adjustment applies to a future
         * opportunity and is consumed when that opportunity's snapshot is taken, so the completed one can
         * never present it — recording it there would file a change against a history that is over.
         */
        THE_COMPLETED_SLOT_ITSELF,

        NO_SUCH_SLOT,

        SLOT_IS_OF_ANOTHER_PLAN,

        SLOT_IS_NOT_AHEAD_OF_THE_USER,

        /**
         * The named slot's day is not strictly after the day the decision was taken on. Being planned,
         * unstarted and startable is not being ahead: a `MISSED` opportunity is startable too and its day
         * has passed. The producer chooses the target with the same comparison and this is the consumer
         * checking it against the decision's own moment, so no clock is read here and both sides cannot
         * disagree about which day the decision belongs to.
         */
        SLOT_IS_NOT_STRICTLY_AHEAD_OF_THE_DECISION,

        /** Its presentation is frozen and cannot consume an adjustment. */
        SLOT_IS_ALREADY_STARTED
    }

    /**
     * The adaptive decision handed to a completion is not about a future opportunity of this completion.
     * An adjustment applies to a concrete future slot and is consumed by snapshot creation, so the
     * decision must name the same Program, the same revision and a slot still ahead of the user, and must
     * not name the slot the completion just took. [reason] says which clause of that rule it broke.
     */
    data class AdaptiveDecisionIsNotAboutAFutureOpportunityOfThisCompletion(
        val sessionId: SessionId,
        val completedSlotId: SlotId,
        val decisionId: DecisionId,
        val decisionSlotId: SlotId,
        val reason: AdaptiveTargetRefusal
    ) : SessionRefusal {
        override val message: String =
            "the completion of session '${sessionId.value}' (opportunity " +
                "'${completedSlotId.value}') can only record an adaptive decision about a future " +
                "opportunity of the same Program and revision, but decision '${decisionId.value}' " +
                "names opportunity '${decisionSlotId.value}': $reason"
    }
}
