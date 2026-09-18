package com.monkfitness.app.domain.adaptive

import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.common.RevisionId
import com.monkfitness.app.domain.common.SlotId
import java.time.Instant

/**
 * Everything the adaptive stage reads for one decision window, frozen (§11, §13).
 *
 * The snapshot is the boundary between what happened and what the policy decides, and it has exactly
 * one job: to hold the facts of one window in one immutable value, so that a decision computed from
 * it is reproducible and explainable. It computes nothing — every derived signal the architecture
 * names (completion ratio, performance relative to target, trend, consistency, shortfall, recent load,
 * time since exposure, inactivity) is derived by the signal layer from these facts, and the evidence
 * and confidence levels arrive as inputs because they are statements about the history, not results
 * of this type.
 *
 * What it deliberately does not contain: any threshold, any window length, any coefficient and any
 * policy. The window is identified by [windowStart] and its length is the policy's business; the
 * baseline and the recent context are the two sides of the aggregate load guard's comparison (§18).
 *
 * Observational order is part of the value: observations are held chronologically, with at most one
 * observation per occurrence, so two snapshots over the same window are the same value whatever order
 * the storage layer returned the rows in.
 *
 * @property programId the Program the window belongs to.
 * @property revisionId the revision that was in effect.
 * @property slotId the slot the decision is being made for.
 * @property windowStart the earliest instant this window may consider, inclusive.
 * @property capturedAt when the snapshot was taken. Never before [windowStart].
 * @property exposures what happened, chronologically, at most one per occurrence.
 * @property evidence how much comparable history exists for the scope.
 * @property confidence how far that history can be trusted for this decision.
 * @property recovery the recovery context the decision is made in.
 * @property baselineLoad the load the plan currently prescribes for the scope.
 * @property recentLoad the load of the recent past, or `null` when there is no comparable history —
 *   which is a fact, not a zero.
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
