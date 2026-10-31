package com.monkfitness.app.domain.adaptive

import com.monkfitness.app.domain.common.RevisionId
import java.time.Instant

/**
 * One family's **current** progression state, as stored: exactly one row per `(revisionId, familyId)`, so
 * a new revision starts from baseline while the previous revision's rows stay readable.
 *
 * The last five fields are the window facts a **single** window cannot state about itself, stored
 * because each window's verdict advances them. [qualifyingWindowsSinceLastChange] is `null`, not `0`,
 * when the family has never changed level: the cooldown exists to stop oscillation *after* a change.
 */
data class FamilyProgressionState(
    val revisionId: RevisionId,
    val familyId: String,
    val progressionLevel: Int,
    val adaptationState: AdaptiveState,
    val currentExerciseId: String? = null,
    val updatedAt: Instant,
    val precedingProgressQualifyingWindows: Int = 0,
    val precedingRegressQualifyingWindows: Int = 0,
    val precedingRecoveryQualifyingWindows: Int = 0,
    val qualifyingWindowsSinceLastChange: Int? = null,
    val recoveryQualifyingWindows: Int = 0
) {

    init {
        require(familyId.isNotBlank()) { "a family progression state must name its family" }
        require(precedingProgressQualifyingWindows >= 0) {
            "precedingProgressQualifyingWindows must be >= 0, was $precedingProgressQualifyingWindows"
        }
        require(precedingRegressQualifyingWindows >= 0) {
            "precedingRegressQualifyingWindows must be >= 0, was $precedingRegressQualifyingWindows"
        }
        require(precedingRecoveryQualifyingWindows >= 0) {
            "precedingRecoveryQualifyingWindows must be >= 0, was $precedingRecoveryQualifyingWindows"
        }
        require(qualifyingWindowsSinceLastChange == null || qualifyingWindowsSinceLastChange >= 0) {
            "qualifyingWindowsSinceLastChange must be null or >= 0, was " +
                "$qualifyingWindowsSinceLastChange"
        }
        require(recoveryQualifyingWindows >= 0) {
            "recoveryQualifyingWindows must be >= 0, was $recoveryQualifyingWindows"
        }
    }

    val carriesExercise: Boolean
        get() = currentExerciseId != null
}
