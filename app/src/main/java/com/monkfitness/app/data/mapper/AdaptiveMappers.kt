package com.monkfitness.app.data.mapper

import com.monkfitness.app.data.model.AdaptiveAdjustmentEntity
import com.monkfitness.app.data.model.AdaptiveDecisionRecordEntity
import com.monkfitness.app.data.model.FamilyProgressionStateEntity
import com.monkfitness.app.domain.adaptive.AdaptiveScope
import com.monkfitness.app.domain.adaptive.AdaptiveState
import com.monkfitness.app.domain.adaptive.ConfidenceLevel
import com.monkfitness.app.domain.adaptive.EvidenceLevel
import com.monkfitness.app.domain.adaptive.FamilyProgressionState
import com.monkfitness.app.domain.adaptive.RecoveryContext
import com.monkfitness.app.domain.adaptive.decision.AdaptiveAction
import com.monkfitness.app.domain.adaptive.decision.AdaptiveAdjustment
import com.monkfitness.app.domain.adaptive.decision.AdaptiveDecision
import com.monkfitness.app.domain.adaptive.decision.AdaptiveTarget
import com.monkfitness.app.domain.adaptive.decision.DecisionOutcome
import com.monkfitness.app.domain.common.AdjustmentId
import com.monkfitness.app.domain.common.DecisionId
import com.monkfitness.app.domain.common.ProgramExerciseId
import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.common.RevisionId
import com.monkfitness.app.domain.common.SlotId
import com.monkfitness.app.domain.workout.EffectiveExercise

/**
 * The Program System's own adaptive rows ⇄ the target adaptive vocabulary: family progression state,
 * decisions and adjustments.
 *
 * These mappers cross the boundary between the target schema and the PR-1 vocabulary, and **not** the
 * boundary to the Stage-1 adaptive tables. `program_family_progression_state`,
 * `program_adaptive_decision_record` and `adaptive_adjustment` are the only tables involved; nothing
 * here reads or writes `family_progression_state` or `adaptive_decision_record`, maps into the
 * pilot's `FamilyAdaptationState`, or carries a hysteresis counter or a policy version across. The
 * two generations coexist until §30 step 15 and stay semantically separate until then (§23).
 *
 * Three links are mapped carefully, because each of them could otherwise duplicate a fact that
 * belongs to exactly one place:
 *
 *  * **an applied decision and its adjustment are linked once.** The decision table has no
 *    `adjustmentId` column (§23), so the identity of the adjustment an `APPLIED` decision produced is
 *    read from the adjustment row that names the decision and handed to [AdaptiveDecisionRecordEntity.toDomain].
 *    A decision row whose row is missing while its outcome says `APPLIED` is therefore invalid
 *    persisted data and fails loudly, rather than loading as a decision that claims an adjustment
 *    nobody recorded.
 *  * **an adjustment's Program and revision are the decision's.** The domain adjustment carries
 *    identity, the decision it came from, the slot, the changed element and the supersession chain —
 *    not the owner — while the table stores `programId` and `revisionId` as ownership columns and
 *    deletes by cascade. Writing therefore takes the owner from the context the repository already
 *    has, and reading drops the duplicate copy, which is reconstructible from the decision.
 *  * **`NOT_APPLIED` is not absence.** A decision the aggregate load guard filtered out is a stored
 *    row with that outcome and no adjustment; it maps to a `NOT_APPLIED` decision and is never turned
 *    into a missing one, and the absence of an adjustment row means exactly "no change was applied".
 */

/**
 * The domain decision of one stored decision row, with the identity of the adjustment it produced
 * when it applied one.
 *
 * @throws IllegalArgumentException when a stored vocabulary token is not a member of the domain's,
 *   when a target scope and its target id disagree (a `SESSION`-scoped decision with an id, or a
 *   scoped one without), or when the assembled value violates the decision's own rule that an
 *   `APPLIED` decision has exactly one adjustment and a `NOT_APPLIED` one has none.
 */
internal fun AdaptiveDecisionRecordEntity.toDomain(adjustmentId: AdjustmentId?): AdaptiveDecision =
    AdaptiveDecision(
        decisionId = DecisionId(decisionId),
        programId = ProgramId(programId),
        revisionId = RevisionId(revisionId),
        slotId = SlotId(slotId),
        target = targetOf(this),
        action = storedToken(action, AdaptiveAction.entries, "program_adaptive_decision_record.action"),
        outcome = storedToken(outcome, DecisionOutcome.entries, "program_adaptive_decision_record.outcome"),
        evidence = storedToken(evidence, EvidenceLevel.entries, "program_adaptive_decision_record.evidence"),
        confidence = storedToken(confidence, ConfidenceLevel.entries, "program_adaptive_decision_record.confidence"),
        recovery = storedToken(recovery, RecoveryContext.entries, "program_adaptive_decision_record.recovery"),
        decidedAt = storedInstant("program_adaptive_decision_record.decidedAt", decidedAt),
        adjustmentId = adjustmentId
    )

/**
 * The stored row of one decision. The adjustment it applied is deliberately not stored here: the
 * adjustment row owns that link (§23), so the pair cannot disagree.
 */
internal fun AdaptiveDecision.toEntity(): AdaptiveDecisionRecordEntity = AdaptiveDecisionRecordEntity(
    decisionId = decisionId.value,
    programId = programId.value,
    revisionId = revisionId.value,
    slotId = slotId.value,
    targetScope = target.scope.name,
    targetId = targetIdOf(target),
    action = action.name,
    outcome = outcome.name,
    evidence = evidence.name,
    confidence = confidence.name,
    recovery = recovery.name,
    decidedAt = storedMilliseconds(decidedAt)
)

/** What one stored decision is about, at the granularity its scope states. */
private fun targetOf(entity: AdaptiveDecisionRecordEntity): AdaptiveTarget =
    when (val scope = storedToken(
        entity.targetScope,
        AdaptiveScope.entries,
        "program_adaptive_decision_record.targetScope"
    )) {
        AdaptiveScope.EXERCISE -> AdaptiveTarget.Exercise(
            requireNotNull(entity.targetId) {
                "an ${AdaptiveScope.EXERCISE}-scoped decision in '${entity.decisionId}' names its " +
                    "exercise target id"
            }
        )
        AdaptiveScope.FAMILY -> AdaptiveTarget.Family(
            requireNotNull(entity.targetId) {
                "an ${AdaptiveScope.FAMILY}-scoped decision in '${entity.decisionId}' names its " +
                    "family target id"
            }
        )
        AdaptiveScope.FOCUS -> AdaptiveTarget.Focus(
            requireNotNull(entity.targetId) {
                "a ${AdaptiveScope.FOCUS}-scoped decision in '${entity.decisionId}' names its " +
                    "focus target id"
            }
        )
        AdaptiveScope.SESSION -> {
            require(entity.targetId == null) {
                "a ${AdaptiveScope.SESSION}-scoped decision names no target id, but " +
                    "'${entity.decisionId}' stores '${entity.targetId}'"
            }
            AdaptiveTarget.Session
        }
    }

/** The id one target is named by, or `null` for the session-scoped target that has none. */
private fun targetIdOf(target: AdaptiveTarget): String? = when (target) {
    is AdaptiveTarget.Exercise -> target.exerciseId
    is AdaptiveTarget.Family -> target.familyId
    is AdaptiveTarget.Focus -> target.focusId
    AdaptiveTarget.Session -> null
}

/** The domain adjustment of one stored adjustment row. */
internal fun AdaptiveAdjustmentEntity.toDomain(): AdaptiveAdjustment = AdaptiveAdjustment(
    adjustmentId = AdjustmentId(adjustmentId),
    decisionId = DecisionId(decisionId),
    slotId = SlotId(slotId),
    before = EffectiveExercise(
        programExerciseId = ProgramExerciseId(programExerciseId),
        exerciseId = beforeExerciseId,
        prescription = prescriptionOf(
            beforePrescriptionDimension,
            beforePerSetTargets,
            "adaptive_adjustment '$adjustmentId' before"
        )
    ),
    after = EffectiveExercise(
        programExerciseId = ProgramExerciseId(programExerciseId),
        exerciseId = afterExerciseId,
        prescription = prescriptionOf(
            afterPrescriptionDimension,
            afterPerSetTargets,
            "adaptive_adjustment '$adjustmentId' after"
        )
    ),
    createdAt = storedInstant("adaptive_adjustment.createdAt", createdAt),
    supersedesAdjustmentId = supersedesAdjustmentId?.let { AdjustmentId(it) }
)

/**
 * The stored row of one adjustment, owned by [programId] and [revisionId].
 *
 * The owner is an argument rather than a field of the value because the domain adjustment does not
 * carry it: it is the decision's Program and revision, and the table stores them so ownership can
 * cascade (§29).
 */
internal fun AdaptiveAdjustment.toEntity(
    programId: ProgramId,
    revisionId: RevisionId
): AdaptiveAdjustmentEntity = AdaptiveAdjustmentEntity(
    adjustmentId = adjustmentId.value,
    decisionId = decisionId.value,
    programId = programId.value,
    revisionId = revisionId.value,
    slotId = slotId.value,
    programExerciseId = before.programExerciseId.value,
    beforeExerciseId = before.exerciseId,
    beforePrescriptionDimension = before.prescription.dimension.name,
    beforePerSetTargets = before.prescription.perSetTargets,
    afterExerciseId = after.exerciseId,
    afterPrescriptionDimension = after.prescription.dimension.name,
    afterPerSetTargets = after.prescription.perSetTargets,
    createdAt = storedMilliseconds(createdAt),
    supersedesAdjustmentId = supersedesAdjustmentId?.value
)

/**
 * One stored family progression state.
 *
 * The stamp is mapped as stored, so an entity → domain → entity round trip is total; the repository
 * is what decides the moment a state is written (§26: a clock is injected, never hidden).
 */
internal fun FamilyProgressionStateEntity.toDomain(): FamilyProgressionState = FamilyProgressionState(
    revisionId = RevisionId(revisionId),
    familyId = familyId,
    progressionLevel = progressionLevel,
    adaptationState = storedToken(
        adaptationState,
        AdaptiveState.entries,
        "program_family_progression_state.adaptationState"
    ),
    currentExerciseId = currentExerciseId,
    updatedAt = storedInstant("program_family_progression_state.updatedAt", updatedAt)
)

/** The stored row of one family progression state. */
internal fun FamilyProgressionState.toEntity(): FamilyProgressionStateEntity = FamilyProgressionStateEntity(
    revisionId = revisionId.value,
    familyId = familyId,
    progressionLevel = progressionLevel,
    adaptationState = adaptationState.name,
    currentExerciseId = currentExerciseId,
    updatedAt = storedMilliseconds(updatedAt)
)
