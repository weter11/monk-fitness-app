package com.monkfitness.app.domain.adaptive

import com.monkfitness.app.domain.common.SessionExerciseId
import com.monkfitness.app.domain.common.SessionId
import java.time.Instant

/**
 * How much of one exercise occurrence was actually executed (§12).
 *
 * There are two levels, not three, and the missing one is the point: a skipped exercise and a missed
 * slot produce **no observation at all**. "No exposure" is represented by the absence of an
 * [ExposureObservation] — never by an observation of zero work — because zero reps performed and an
 * opportunity that never happened are different facts, and only one of them says anything about the
 * user.
 */
enum class ExposureLevel {

    /** Some work happened, but less than was prescribed. */
    PARTIAL,

    /** All of the prescribed work happened (or more — extra sets are the user's business). */
    FULL
}

/**
 * One exercise occurrence, as observed: what actually happened to it (§12).
 *
 * This is the unit the adaptive stage reasons over — not the slot, not the session and not the
 * exercise in the abstract. Identity comes from the session it belongs to ([sessionId]) and the
 * occurrence within it ([sessionExerciseId]), so a full workout is many observations that happen to
 * share a session, and the same exercise on two different days is two observations that happen to
 * share an `exerciseId`.
 *
 * Four construction rules mirror the architecture's exposure rules exactly:
 *
 *  * an observation records work that happened, so it needs at least one completed set. There is no
 *    "observed nothing" instance to construct — that fact is the absence of this value;
 *  * [level] agrees with the counts: `FULL` means every prescribed set was completed (or more), and
 *    `PARTIAL` means fewer were. A partial execution cannot claim to be full and a full one cannot
 *    under-report;
 *  * a cancelled session is not a completed workout, but it may still contain partial exposure — the
 *    observations are built from what actually happened, which is why cancellation does not zero them;
 *  * [prescribedSets] is what was presented to the user (the session's own snapshot), not what today's
 *    plan says, so a later edit cannot change what this observation is measured against.
 *
 * @property exerciseId the library key of the exercise, as presented.
 * @property sessionId the session the occurrence ran in.
 * @property sessionExerciseId identity of the occurrence within that session.
 * @property level partial or full execution.
 * @property completedSets sets that were actually completed, `>= 1`.
 * @property prescribedSets sets that were presented, `>= 1`.
 * @property startedAt when the occurrence began.
 * @property finishedAt when the occurrence ended, or `null` if it has not ended.
 */
data class ExposureObservation(
    val exerciseId: String,
    val sessionId: SessionId,
    val sessionExerciseId: SessionExerciseId,
    val level: ExposureLevel,
    val completedSets: Int,
    val prescribedSets: Int,
    val startedAt: Instant,
    val finishedAt: Instant? = null
) {

    init {
        require(exerciseId.isNotBlank()) { "an observation must name the exercise it observed" }
        require(prescribedSets >= 1) {
            "an observation is of a presented exercise, which prescribes at least one set, was " +
                "$prescribedSets"
        }
        require(completedSets >= 1) {
            "an observation records work that happened; a skipped exercise or a missed slot " +
                "produces no observation rather than a zero one, was completedSets=$completedSets"
        }
        require((level == ExposureLevel.FULL) == (completedSets >= prescribedSets)) {
            "the level must agree with the counts: level=$level completedSets=$completedSets " +
                "prescribedSets=$prescribedSets"
        }
        require(finishedAt == null || finishedAt >= startedAt) {
            "an occurrence cannot end before it started: started=$startedAt finished=$finishedAt"
        }
    }

    /** Whether the whole prescription was executed. */
    val isFull: Boolean
        get() = level == ExposureLevel.FULL
}
