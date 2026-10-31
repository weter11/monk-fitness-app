package com.monkfitness.app.domain.adaptive

import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.common.RevisionId
import com.monkfitness.app.domain.common.SlotId
import java.time.Instant

/**
 * Everything the adaptive stage reads for one decision window, frozen: the boundary between what happened
 * and what the policy decides.
 *
 * It computes nothing — every derived signal comes from the signal layer — and holds no threshold, window
 * length, coefficient or policy. Observations are held chronologically with at most one per occurrence, so
 * two snapshots over the same window are the same value whatever order storage returned the rows in.
 */
data class AdaptiveInputSnapshot(
    val programId: ProgramId,
    val revisionId: RevisionId,
    val slotId: SlotId,
    val windowStart: Instant,
    val capturedAt: Instant,
    val exposures: List<ExposureObservation> = emptyList(),
    val evidence: EvidenceLevel,
    val confidence: ConfidenceLevel,
    val recovery: RecoveryContext,
    val baselineLoad: LoadProfile,
    val recentLoad: LoadProfile? = null
) {

    init {
        require(capturedAt >= windowStart) {
            "a window cannot be captured before it starts: windowStart=$windowStart " +
                "capturedAt=$capturedAt"
        }
        require(exposures.all { it.startedAt >= windowStart }) {
            "an observation outside the window does not belong to it: windowStart=$windowStart"
        }
        require(exposures == exposures.sortedBy { it.startedAt }) {
            "observations are held chronologically, got ${exposures.map { it.startedAt }}"
        }
        require(exposures.map { it.sessionExerciseId }.toSet().size == exposures.size) {
            "one observation per occurrence, got ${exposures.map { it.sessionExerciseId }}"
        }
    }
}
