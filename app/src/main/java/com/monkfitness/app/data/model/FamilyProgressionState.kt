package com.monkfitness.app.data.model

import androidx.room.Entity
import com.monkfitness.app.domain.adaptive.AdaptivePolicy
import com.monkfitness.app.domain.adaptive.AdaptiveState
import com.monkfitness.app.domain.adaptive.FamilyAdaptationState

/**
 * The persisted current adaptation state of one exercise family — the mutable half of the adaptive
 * contract, as opposed to [AdaptiveDecisionRecord], which is the immutable audit trail.
 *
 * This row is the source of current progression truth: the reviewer of a stored decision reads this
 * table, never the history, to find out where a family stands now. It is deliberately *not*
 * derivable from the decision history — a decision record proves what was ordered in one window,
 * while a level, a cooldown position and a recovery-session count are carried facts that only the
 * layer that owns them can update.
 *
 * The identity is `(programRevision, familyId)`, so it is a primary key and not merely a caller
 * convention: an upsert can never leave two rows claiming to be the same family's current state, and
 * two families can never share one. It is *not* cycle-scoped, because the 56-day calendar and
 * adaptive progression are independent by design — a cycle rollover preserves a family's level and
 * state — whereas a new program revision starts from baseline while the previous revision's rows
 * stay readable.
 *
 * Every field mirrors the vocabulary the adaptive domain already uses: [AdaptiveState] is the
 * four-state enum and `MIN_LEVEL`..`MAX_LEVEL` is the level range, both taken from the domain rather
 * than restated, so the persisted representation cannot drift into a second meaning. The level is an
 * abstract family position on its own progression axis — not a universal reps delta.
 *
 * @param familyId the exercise family this state belongs to.
 * @param progressionLevel the family's abstract level, within
 *   [FamilyAdaptationState.MIN_LEVEL]..[FamilyAdaptationState.MAX_LEVEL].
 * @param currentExerciseId the exercise id the family is currently on, or `null` when it carries
 *   none. Kept because the progression resolver's fallback path reads it and never infers it from
 *   history.
 * @param adaptationState the adaptation state the family currently holds.
 * @param precedingProgressQualifyingWindows consecutive prior decision windows in which the PROGRESS
 *   conditions held — the hysteresis counter the engine reads back but never updates.
 * @param precedingRegressQualifyingWindows consecutive prior decision windows in which the REGRESS
 *   conditions held.
 * @param precedingHighRiskWindows consecutive prior decision windows that qualified as high risk.
 * @param recoveryQualifyingSessions qualifying sessions completed while in RECOVERY, the count
 *   RECOVERY's exit gate is measured in.
 * @param eligibleSessionsSinceLastProgressionChange eligible sessions since the family's last level
 *   change, or `null` when it has never had one — no default, for the same reason the domain type has
 *   none: an assumed cooldown position would let the program expand faster than the user has earned.
 * @param programRevision the program revision this state belongs to (`0` is the program as first
 *   started, matching `SettingsManager.PROGRAM_REVISION`).
 * @param updatedAt when this row was last written, in epoch milliseconds — the timestamp convention
 *   the rest of the data layer uses (`SetLog.timestamp`, `ProgramDayState.completedAt`). It belongs
 *   to persistence and is stamped by the repository, never by the adaptive domain.
 * @param policyVersion the `AdaptivePolicy` version this state was last updated under.
 */
@Entity(
    tableName = "family_progression_state",
    primaryKeys = ["programRevision", "familyId"],
)
data class FamilyProgressionState(
    val familyId: String,
    val progressionLevel: Int = 0,
    val currentExerciseId: String? = null,
    val adaptationState: AdaptiveState = AdaptiveState.HOLD,
    val precedingProgressQualifyingWindows: Int = 0,
    val precedingRegressQualifyingWindows: Int = 0,
    val precedingHighRiskWindows: Int = 0,
    val recoveryQualifyingSessions: Int = 0,
    val eligibleSessionsSinceLastProgressionChange: Int?,
    val programRevision: Int = 0,
    val updatedAt: Long,
    val policyVersion: Int = AdaptivePolicy.V1_VERSION,
) {

    init {
        require(familyId.isNotBlank()) { "familyId must not be blank" }
        require(progressionLevel in MIN_LEVEL..MAX_LEVEL) {
            "progressionLevel must be within $MIN_LEVEL..$MAX_LEVEL, was $progressionLevel"
        }
        require(precedingProgressQualifyingWindows >= 0) {
            "precedingProgressQualifyingWindows must be >= 0, was $precedingProgressQualifyingWindows"
        }
        require(precedingRegressQualifyingWindows >= 0) {
            "precedingRegressQualifyingWindows must be >= 0, was $precedingRegressQualifyingWindows"
        }
        require(precedingHighRiskWindows >= 0) {
            "precedingHighRiskWindows must be >= 0, was $precedingHighRiskWindows"
        }
        require(recoveryQualifyingSessions >= 0) {
            "recoveryQualifyingSessions must be >= 0, was $recoveryQualifyingSessions"
        }
        require(
            eligibleSessionsSinceLastProgressionChange == null ||
                eligibleSessionsSinceLastProgressionChange >= 0
        ) {
            "eligibleSessionsSinceLastProgressionChange must be null or >= 0, was " +
                "$eligibleSessionsSinceLastProgressionChange"
        }
        require(programRevision >= 0) { "programRevision must be >= 0, was $programRevision" }
        require(policyVersion >= 1) { "policyVersion must be >= 1, was $policyVersion" }
    }

    /**
     * The pure domain representation of this row, for the adaptive engine's per-family input. The
     * projection drops the three fields no domain type carries — the current exercise, the stored
     * revision and the row's own write stamp — and keeps every value the engine is allowed to read.
     */
    fun toAdaptationState(): FamilyAdaptationState = FamilyAdaptationState(
        familyId = familyId,
        progressionLevel = progressionLevel,
        adaptationState = adaptationState,
        precedingProgressQualifyingWindows = precedingProgressQualifyingWindows,
        precedingRegressQualifyingWindows = precedingRegressQualifyingWindows,
        precedingHighRiskWindows = precedingHighRiskWindows,
        recoveryQualifyingSessions = recoveryQualifyingSessions,
        eligibleSessionsSinceLastProgressionChange = eligibleSessionsSinceLastProgressionChange
    )

    companion object {
        /** The documented progression-level bounds, taken from the domain type that owns them. */
        const val MIN_LEVEL: Int = FamilyAdaptationState.MIN_LEVEL
        const val MAX_LEVEL: Int = FamilyAdaptationState.MAX_LEVEL
    }
}
