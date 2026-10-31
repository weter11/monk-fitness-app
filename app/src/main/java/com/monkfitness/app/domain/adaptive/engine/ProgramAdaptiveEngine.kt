package com.monkfitness.app.domain.adaptive.engine

import com.monkfitness.app.domain.adaptive.AdaptiveInputSnapshot
import com.monkfitness.app.domain.adaptive.AdaptiveScope
import com.monkfitness.app.domain.adaptive.AdaptiveState
import com.monkfitness.app.domain.adaptive.DensityLoad
import com.monkfitness.app.domain.adaptive.ExerciseRange
import com.monkfitness.app.domain.adaptive.ExposureLoad
import com.monkfitness.app.domain.adaptive.IntensityEntry
import com.monkfitness.app.domain.adaptive.IntensityLoad
import com.monkfitness.app.domain.adaptive.LoadProfile
import com.monkfitness.app.domain.adaptive.PerformedSetAmount
import com.monkfitness.app.domain.adaptive.VolumeLoad
import com.monkfitness.app.domain.adaptive.decision.AdaptiveAction
import com.monkfitness.app.domain.adaptive.decision.AdaptiveAdjustment
import com.monkfitness.app.domain.adaptive.decision.AdaptiveDecision
import com.monkfitness.app.domain.adaptive.decision.AdaptiveTarget
import com.monkfitness.app.domain.adaptive.decision.DecisionOutcome
import com.monkfitness.app.domain.common.AdjustmentId
import com.monkfitness.app.domain.common.DecisionId
import com.monkfitness.app.domain.prescription.Prescription
import com.monkfitness.app.domain.prescription.PrescriptionDimension
import com.monkfitness.app.domain.prescription.RepPrescription
import com.monkfitness.app.domain.prescription.TimePrescription
import com.monkfitness.app.domain.workout.EffectiveExercise
import java.time.Instant

/**
 * Everything the engine is given for one decision: the whole input surface, with no collaborator, no
 * clock and no lookup, so a decision is reproducible from this value alone. [availableExerciseIds] is
 * the user's own selection, read for a same-level realignment; a ladder step is answered by the family's
 * hierarchy, not by the plan's content.
 */
data class ProgramAdaptiveRequest(
    val decisionId: DecisionId,
    val adjustmentId: AdjustmentId,
    val decidedAt: Instant,
    val snapshot: AdaptiveInputSnapshot,
    val element: ProgramAdaptiveElement,
    val relation: ProgramProgressionRelation,
    val window: ProgramAdaptiveWindow,
    val availableExerciseIds: Set<String>,
    val familyOfExercise: Map<String, String> = emptyMap(),
    val range: ExerciseRange? = null,
    val prescriptionStep: ProgramPrescriptionStep? = null,
    val supersedesAdjustmentId: AdjustmentId? = null,
    val policy: ProgramAdaptivePolicy = ProgramAdaptivePolicy.V1
) {

    init {
        require(relation.familyId == element.familyId) {
            "a resolution is only meaningful for the family whose hierarchy produced it: " +
                "element=${element.familyId} relation=${relation.familyId}"
        }
        require(window.familyId == element.familyId) {
            "the window's facts are about the element's own family: element=${element.familyId} " +
                "window=${window.familyId}"
        }
        require(supersedesAdjustmentId != adjustmentId) {
            "an adjustment cannot supersede itself"
        }
    }
}

/**
 * A caller's request to re-prescribe the element's **own** exercise. Only a regression qualifies: the
 * step lowers what is presented, so its reason's action is `REGRESS`.
 */
data class ProgramPrescriptionStep(
    val target: Prescription,
    val reason: ProgramAdaptiveReason
) {

    init {
        require(reason.action == AdaptiveAction.REGRESS) {
            "a prescription step lowers the same exercise, so its reason accompanies a regression: " +
                "reason=$reason action=${reason.action}"
        }
    }
}

/**
 * What the adaptive stage decided. [requestedAction] is kept separate from [decision]'s action because
 * the load guard's refusal is recorded as a `HOLD` while the record still shows a `PROGRESS` was asked.
 */
data class ProgramAdaptiveResult(
    val decision: AdaptiveDecision,
    val adjustment: AdaptiveAdjustment?,
    val state: AdaptiveState,
    val reason: ProgramAdaptiveReason,
    val requestedAction: AdaptiveAction,
    val signals: ProgramAdaptiveSignals,
    val guard: ProgramGuardVerdict,
    val verdict: ProgramWindowVerdict = ProgramWindowVerdict()
) {

    init {
        require((decision.outcome == DecisionOutcome.APPLIED) == (adjustment != null)) {
            "an applied decision produced exactly one adjustment and a not-applied one produced none: " +
                "outcome=${decision.outcome} adjustment=${adjustment?.adjustmentId?.value}"
        }
        require(adjustment == null || adjustment.decisionId == decision.decisionId) {
            "an adjustment belongs to the decision that produced it"
        }
        require(reason.action == decision.action) {
            "a result's reason is the reason for the action that stands: reason=$reason " +
                "action=${decision.action}"
        }
        require(guard.scope == decision.target.scope) {
            "the guard is consulted at the decision's own scope: target=${decision.target.scope} " +
                "guard=${guard.scope}"
        }
    }

    val isApplied: Boolean
        get() = decision.isApplied

    val wasFilteredByTheGuard: Boolean
        get() = guard is ProgramGuardVerdict.Filtered

    /**
     * Whether this change spends the family's progression cooldown. A correction from what the user
     * actually logged is not an adjustment of the programme: it is the next session being made doable, so
     * it must not push the next increase back by a cooldown period.
     */
    val spendsTheCooldown: Boolean
        get() = isApplied &&
            decision.action != AdaptiveAction.CHANGE_VARIANT &&
            reason != ProgramAdaptiveReason.BELOW_TARGET_CORRECTION
}

/**
 * A pure function from one request to one auditable decision.
 *
 * It owns three things: resolving the policy's action against the family's declared hierarchy, the
 * same-level variant realignment, and the two load profiles the guard compares. It owns no threshold
 * and makes no decision the policy has not already made.
 *
 * The user's own element is settled first: adaptive logic never runs against a pinned choice, and the
 * guard only speaks about automatic changes.
 */
object ProgramAdaptiveEngine {

    fun decide(request: ProgramAdaptiveRequest): ProgramAdaptiveResult {
        val policy = request.policy
        val signals = ProgramAdaptiveSignalCalculator.calculate(
            snapshot = request.snapshot,
            familyOfExercise = request.familyOfExercise,
            familyId = request.element.familyId,
            policy = policy
        )
        val policyDecision = policy.evaluate(
            ProgramAdaptiveEvidence(
                window = request.window,
                signals = signals,
                evidence = request.snapshot.evidence,
                confidence = request.snapshot.confidence,
                recovery = request.snapshot.recovery
            )
        )

        val resolution = if (!request.element.isAdaptable) {
            Resolution.None(ProgramAdaptiveReason.USER_AUTHORED_ELEMENT)
        } else {
            resolve(request, policyDecision)
        }

        val guarded = applyTheGuard(request, resolution)
        val change = guarded as? Resolution.Change
        val refused = guarded as? Resolution.Refused
        val reason = when (guarded) {
            is Resolution.Change -> guarded.reason
            is Resolution.None -> guarded.reason
            is Resolution.Refused -> ProgramAdaptiveReason.AGGREGATE_LOAD_GUARD
        }
        val target = change?.target ?: refused?.target ?: AdaptiveTarget.Family(request.element.familyId)
        val guardVerdict = guarded.guardVerdict
            ?: ProgramGuardVerdict.NotGuarded(target.scope, if (change != null) change.action else AdaptiveAction.HOLD)

        val adjustment = change?.let { resolved ->
            AdaptiveAdjustment(
                adjustmentId = request.adjustmentId,
                decisionId = request.decisionId,
                slotId = request.snapshot.slotId,
                before = request.element.presentation,
                after = resolved.after,
                createdAt = request.decidedAt,
                supersedesAdjustmentId = request.supersedesAdjustmentId
            )
        }

        val decision = AdaptiveDecision(
            decisionId = request.decisionId,
            programId = request.snapshot.programId,
            revisionId = request.snapshot.revisionId,
            slotId = request.snapshot.slotId,
            target = target,
            action = change?.action ?: AdaptiveAction.HOLD,
            outcome = if (change != null) DecisionOutcome.APPLIED else DecisionOutcome.NOT_APPLIED,
            evidence = request.snapshot.evidence,
            confidence = request.snapshot.confidence,
            recovery = request.snapshot.recovery,
            decidedAt = request.decidedAt,
            adjustmentId = adjustment?.adjustmentId,
            reason = reason
        )

        val requestedAction = request.prescriptionStep?.reason?.action ?: policyDecision.action

        return ProgramAdaptiveResult(
            decision = decision,
            adjustment = adjustment,
            state = stateAfter(policyDecision.state, change),
            reason = reason,
            requestedAction = requestedAction,
            signals = signals,
            guard = guardVerdict,
            verdict = policyDecision.verdict
        )
    }

    private sealed interface Resolution {

        val guardVerdict: ProgramGuardVerdict?

        data class Change(
            val action: AdaptiveAction,
            val target: AdaptiveTarget,
            val after: EffectiveExercise,
            val reason: ProgramAdaptiveReason,
            override val guardVerdict: ProgramGuardVerdict? = null,
            /** The exercise's own authored span, for a change that stays inside it. */
            val authoredCeiling: LoadProfile? = null
        ) : Resolution

        data class None(
            val reason: ProgramAdaptiveReason,
            override val guardVerdict: ProgramGuardVerdict? = null
        ) : Resolution

        /**
         * A change the guard refused. It keeps the target so the record still names what was refused
         * instead of flattening it into an unattributed hold.
         */
        data class Refused(
            val target: AdaptiveTarget,
            override val guardVerdict: ProgramGuardVerdict
        ) : Resolution
    }

    private fun resolve(
        request: ProgramAdaptiveRequest,
        policyDecision: ProgramPolicyDecision
    ): Resolution {
        // A caller that has already decided a same-exercise step does not go through the family's
        // ladder: the target is the caller's own, and the only rule this stage adds is the range clamp.
        request.prescriptionStep?.let { step -> return stepWithinRange(request, step) }

        // A return after a gap is a re-prescription of the element's own exercise, never a step down the
        // family's ladder: the family's level is where the user had reached, and it is the *week* that
        // gets lighter, not the position they earned before the gap.
        if (policyDecision.reason == ProgramAdaptiveReason.RETURN_AFTER_GAP) {
            return returningWithinRange(request)
        }

        // A session that logged less than it was asked for settles the next target from what was done.
        // It is read before the policy's own direction because that direction counts sets completed, not
        // the amounts inside them: a complete set of six against an eight is a completed set, so the
        // policy would raise a target the user has just shown they cannot meet.
        belowTargetCorrection(request)?.let { return it }

        if (policyDecision.action == AdaptiveAction.HOLD) {
            return realignment(request) ?: Resolution.None(policyDecision.reason)
        }
        return when (policyDecision.action) {
            AdaptiveAction.PROGRESS -> step(request, up = true)
            AdaptiveAction.REGRESS -> step(request, up = false)
            AdaptiveAction.CHANGE_REST ->
                Resolution.None(ProgramAdaptiveReason.REST_CHANGE_UNSUPPORTED)

            AdaptiveAction.HOLD, AdaptiveAction.CHANGE_VARIANT ->
                Resolution.None(policyDecision.reason)
        }
    }

    private fun step(request: ProgramAdaptiveRequest, up: Boolean): Resolution {
        // The exercise's own range is climbed before the family's ladder is: more repetitions (or seconds)
        // of the same exercise is the smallest heavier prescription there is, and a harder variant is only
        // the answer once the whole span of this one has been used.
        withinRangeStep(request, up)?.let { within -> return within }

        // A generated plan's content is the planner's choice, not the user's veto: the next rung is
        // introduced even when the plan does not present it. An authored element never reaches here —
        // ownership settled it before the policy ran.
        val relation = request.relation
        val level = levelOf(request) ?: return Resolution.None(ProgramAdaptiveReason.PROGRESSION_UNAVAILABLE)
        if (!relation.declares(level)) {
            return Resolution.None(ProgramAdaptiveReason.PROGRESSION_UNAVAILABLE)
        }

        val atTheBoundary = if (up) level >= relation.highestLevel else level <= relation.lowestLevel
        val variant = (if (up) relation.stepUp(level) else relation.stepDown(level))
            ?: return Resolution.None(
                if (atTheBoundary) {
                    if (up) ProgramAdaptiveReason.CEILING_REACHED else ProgramAdaptiveReason.FLOOR_REACHED
                } else {
                    ProgramAdaptiveReason.PROGRESSION_UNAVAILABLE
                }
            )

        return Resolution.Change(
            action = if (up) AdaptiveAction.PROGRESS else AdaptiveAction.REGRESS,
            target = AdaptiveTarget.Family(relation.familyId),
            after = presentationOf(request.element.presentation, variant),
            reason = if (up) ProgramAdaptiveReason.SUSTAINED_POSITIVE else ProgramAdaptiveReason.SUSTAINED_NEGATIVE
        )
    }

    /**
     * The same exercise at a different amount inside its own range, or `null` when this exercise has no
     * span in its own dimension, or the span is already exhausted in [up]'s direction.
     *
     * Upward the base is the prescription; downward it is what the user **actually performed**, so a
     * session that fell short corrects the next one instead of being measured against work that did not
     * happen. Neither direction ever leaves the range, and a step that would change nothing is `null` so
     * the family's ladder — never a no-op adjustment — is the answer.
     */
    private fun withinRangeStep(request: ProgramAdaptiveRequest, up: Boolean): Resolution? {
        val before = request.element.presentation
        val dimension = before.prescription.dimension
        val amounts = request.range?.amountsFor(dimension) ?: return null
        val presented = before.prescription.perSetTargets
        if (presented.isEmpty()) return null

        val target = if (up) {
            if (presented.max() >= amounts.last) return null
            presented.map { amount -> (amount + request.policy.rangeStepWithin(amounts)).coerceAtMost(amounts.last) }
        } else {
            if (presented.min() <= amounts.first) return null
            correctedFromPerformed(request, presented, amounts, dimension)
        }
        if (target == presented) return null

        val reason = if (up) ProgramAdaptiveReason.SUSTAINED_POSITIVE else ProgramAdaptiveReason.SUSTAINED_NEGATIVE
        val after = EffectiveExercise(
            programExerciseId = before.programExerciseId,
            exerciseId = before.exerciseId,
            prescription = prescriptionOf(dimension, target) ?: return null,
            focus = before.focus
        )
        return Resolution.Change(
            action = if (up) AdaptiveAction.PROGRESS else AdaptiveAction.REGRESS,
            target = AdaptiveTarget.Exercise(before.exerciseId),
            after = after,
            reason = reason,
            authoredCeiling = if (up) ceilingOf(before, amounts, dimension) else null
        )
    }

    /**
     * The next target, taken from the last session's own amounts wherever they fell below what is
     * presented: `null` when every set met or beat the presentation, and `null` when this exercise has no
     * span in its own dimension to keep the correction inside.
     *
     * Only lowering is expressed here — a set above its target is the policy's business, not a raise
     * disguised as a correction.
     */
    private fun belowTargetCorrection(request: ProgramAdaptiveRequest): Resolution? {
        val before = request.element.presentation
        val dimension = before.prescription.dimension
        val amounts = request.range?.amountsFor(dimension) ?: return null
        val presented = before.prescription.perSetTargets
        if (presented.isEmpty()) return null
        val performed = performedAmountsOf(request, dimension)?.takeIf { it.isNotEmpty() } ?: return null

        // The element's own shape: one entry per presented set, taken in set order and repeated from the
        // last set where fewer sets were performed than are presented.
        val target = presented.indices.map { index ->
            performed.getOrElse(index) { performed.last() }
                .coerceIn(amounts.first, amounts.last)
                .coerceAtMost(presented[index])
        }
        if (target.indices.none { index -> target[index] < presented[index] }) return null

        return Resolution.Change(
            action = AdaptiveAction.REGRESS,
            target = AdaptiveTarget.Exercise(before.exerciseId),
            after = EffectiveExercise(
                programExerciseId = before.programExerciseId,
                exerciseId = before.exerciseId,
                prescription = prescriptionOf(dimension, target) ?: return null,
                focus = before.focus
            ),
            reason = ProgramAdaptiveReason.BELOW_TARGET_CORRECTION
        )
    }

    /**
     * The amounts the next session should ask for after a shortfall: what was performed, in the shape the
     * presentation asks for and never above it. A user who performed what was asked and fell short only in
     * *sets* still gets a step down, taken from the prescription, so the correction is never a no-op.
     */
    private fun correctedFromPerformed(
        request: ProgramAdaptiveRequest,
        presented: List<Int>,
        amounts: IntRange,
        dimension: PrescriptionDimension
    ): List<Int> {
        val performed = performedAmountsOf(request, dimension)
        val corrected = if (performed == null) {
            presented
        } else {
            presented.indices.map { index ->
                performed.getOrElse(index) { performed.last() }.coerceIn(amounts.first, amounts.last)
            }
        }
        val capped = corrected.zip(presented).map { (correction, current) -> minOf(correction, current) }
        return if (capped != presented) {
            capped
        } else {
            presented.map { amount ->
                (amount - request.policy.rangeStepWithin(amounts)).coerceAtLeast(amounts.first)
            }
        }
    }

    /** The span of [presentation] as a load profile, so the guard can bound a change by authored content. */
    private fun ceilingOf(
        presentation: EffectiveExercise,
        amounts: IntRange,
        dimension: PrescriptionDimension
    ): LoadProfile {
        val sets = presentation.prescription.setCount
        val repetitions = if (dimension == PrescriptionDimension.REP_BASED) amounts.last * sets else 0
        val seconds = if (dimension == PrescriptionDimension.TIME_BASED) amounts.last * sets else 0
        return LoadProfile(
            scope = AdaptiveScope.EXERCISE,
            volume = VolumeLoad(sets = sets, repetitions = repetitions, durationSeconds = seconds),
            intensity = IntensityLoad(),
            density = DensityLoad(workingSeconds = seconds, restSeconds = 0),
            exposure = ExposureLoad(opportunities = 0, completedOpportunities = 0)
        )
    }

    private fun prescriptionOf(dimension: PrescriptionDimension, amounts: List<Int>): Prescription? =
        when (dimension) {
            PrescriptionDimension.REP_BASED -> RepPrescription(amounts)
            PrescriptionDimension.TIME_BASED -> TimePrescription(amounts)
            PrescriptionDimension.SET_BASED,
            PrescriptionDimension.DIFFICULTY_BASED,
            PrescriptionDimension.REST_BASED -> null
        }

    /**
     * The element's own exercise, re-prescribed within its catalogue range: the exercise is kept and
     * only the amounts change, and a missing or wrong-unit range makes the step unavailable rather than
     * leaving it unclamped.
     */
    private fun stepWithinRange(
        request: ProgramAdaptiveRequest,
        step: ProgramPrescriptionStep
    ): Resolution {
        val before = request.element.presentation
        val dimension = before.prescription.dimension
        val amounts = request.range?.amountsFor(dimension)
            ?: return Resolution.None(ProgramAdaptiveReason.PROGRESSION_UNAVAILABLE)
        if (step.target.dimension != dimension) {
            return Resolution.None(ProgramAdaptiveReason.PROGRESSION_UNAVAILABLE)
        }

        val clamped = step.target.perSetTargets.map { amount -> amount.coerceIn(amounts.first, amounts.last) }
        val afterPrescription = when (dimension) {
            PrescriptionDimension.REP_BASED -> RepPrescription(clamped)
            PrescriptionDimension.TIME_BASED -> TimePrescription(clamped)
            else -> return Resolution.None(ProgramAdaptiveReason.PROGRESSION_UNAVAILABLE)
        }
        val after = EffectiveExercise(
            programExerciseId = before.programExerciseId,
            exerciseId = before.exerciseId,
            prescription = afterPrescription,
            focus = before.focus
        )
        if (after == before) {
            val askedBelowTheFloor = step.target.perSetTargets.any { amount -> amount < amounts.first }
            return Resolution.None(
                if (askedBelowTheFloor) {
                    ProgramAdaptiveReason.FLOOR_REACHED
                } else {
                    ProgramAdaptiveReason.PROGRESSION_UNAVAILABLE
                }
            )
        }

        return Resolution.Change(
            action = step.reason.action,
            target = AdaptiveTarget.Exercise(before.exerciseId),
            after = after,
            reason = step.reason
        )
    }

    /**
     * The base is what the user **last performed**, not what the plan prescribed, and nothing is raised:
     * a session in which the user outperformed the prescription cannot come back as a heavier week.
     */
    private fun returningWithinRange(request: ProgramAdaptiveRequest): Resolution {
        val before = request.element.presentation
        val dimension = before.prescription.dimension
        val amounts = request.range?.amountsFor(dimension)
            ?: return Resolution.None(ProgramAdaptiveReason.PROGRESSION_UNAVAILABLE)

        val performed = performedAmountsOf(request, dimension)
            ?: return Resolution.None(ProgramAdaptiveReason.PROGRESSION_UNAVAILABLE)
        if (performed.any { amount -> amount <= 0 }) {
            return Resolution.None(ProgramAdaptiveReason.PROGRESSION_UNAVAILABLE)
        }

        // The element's own shape: as many entries as the plan presents, taken in set order from what was
        // performed and repeated from its last set where the user performed fewer sets than are presented.
        val presented = before.prescription.perSetTargets
        val attempted = presented.indices.map { index -> performed.getOrElse(index) { performed.last() } }
        val target = request.policy.returnAfterGapTargets(attempted, amounts, request.window.gap)
        val capped = target.mapIndexed { index, amount -> minOf(amount, presented[index]) }

        val prescription = when (dimension) {
            PrescriptionDimension.REP_BASED -> RepPrescription(capped)
            PrescriptionDimension.TIME_BASED -> TimePrescription(capped)
            else -> return Resolution.None(ProgramAdaptiveReason.PROGRESSION_UNAVAILABLE)
        }
        return stepWithinRange(
            request,
            ProgramPrescriptionStep(
                target = prescription,
                reason = ProgramAdaptiveReason.RETURN_AFTER_GAP
            )
        )
    }

    private fun amountIn(amount: PerformedSetAmount, dimension: PrescriptionDimension): Int =
        when (dimension) {
            PrescriptionDimension.REP_BASED -> amount.completedReps
            PrescriptionDimension.TIME_BASED -> amount.durationSeconds
            else -> 0
        }

    /**
     * The amounts the family's most recent observed session actually performed, in [dimension]'s unit, or
     * `null` when nothing in the window was recorded. Ordered by stamp then occurrence identity, the order
     * the snapshot requires, so "the last one" is a fact about time rather than about list order.
     */
    private fun performedAmountsOf(
        request: ProgramAdaptiveRequest,
        dimension: PrescriptionDimension
    ): List<Int>? = request.snapshot.exposures
        .filter { exposure -> familyOf(exposure.exerciseId, request) == request.element.familyId }
        .filter { exposure -> exposure.performedAmounts != null }
        .sortedWith(compareBy({ it.startedAt }, { it.sessionExerciseId.value }))
        .lastOrNull()
        ?.performedAmounts
        ?.map { amount -> amountIn(amount, dimension) }

    private fun familyOf(exerciseId: String, request: ProgramAdaptiveRequest): String =
        request.familyOfExercise[exerciseId] ?: exerciseId

    /**
     * Offered only when both ends are declared at the family's own level. The replacement is taken in
     * the relation's canonical order, so the choice is a read of the hierarchy, not a preference here.
     */
    private fun realignment(request: ProgramAdaptiveRequest): Resolution? {
        if (!request.policy.variantRealignmentEnabled) return null
        val relation = request.relation
        val level = levelOf(request) ?: return null
        if (!relation.declares(level)) return null

        val declaredHere = relation.declared(request.element.exerciseId)?.takeIf { it.level == level }
        if (declaredHere != null && declaredHere.exerciseId in request.availableExerciseIds) {
            // The element already presents the variant this family declares here, and the user has it:
            // there is nothing to re-point, and a change with no justified target is not a change.
            return null
        }

        val replacement = relation.variantsAt(level)
            .firstOrNull { it.exerciseId != request.element.exerciseId && it.exerciseId in request.availableExerciseIds }
            ?: return null

        return Resolution.Change(
            action = AdaptiveAction.CHANGE_VARIANT,
            target = AdaptiveTarget.Exercise(request.element.exerciseId),
            after = presentationOf(request.element.presentation, replacement),
            reason = ProgramAdaptiveReason.VARIANT_REALIGNED
        )
    }

    private fun levelOf(request: ProgramAdaptiveRequest): Int? =
        request.window.level ?: request.relation.declared(request.element.exerciseId)?.level

    /**
     * Only an increase is submitted: a hold changes nothing and a regression takes load away. A refusal
     * becomes the `HOLD` the guard requires — the guard never invents a regression in its place.
     */
    private fun applyTheGuard(
        request: ProgramAdaptiveRequest,
        resolution: Resolution
    ): Resolution {
        val change = resolution as? Resolution.Change ?: return resolution
        val verdict = ProgramAggregateLoadGuard.guard(
            action = change.action,
            baselineLoad = loadProfileOf(request, request.element.presentation, change.target.scope),
            candidateLoad = loadProfileOf(request, change.after, change.target.scope),
            recentBaselineLoad = request.snapshot.baselineLoad,
            recentLoad = request.snapshot.recentLoad,
            policy = request.policy,
            authoredCeiling = change.authoredCeiling
        )
        return if (verdict is ProgramGuardVerdict.Filtered) {
            Resolution.Refused(target = change.target, guardVerdict = verdict)
        } else {
            change.copy(guardVerdict = verdict)
        }
    }

    /**
     * Every number is read from the presentation and the family's level; nothing is converted, so
     * repetitions and seconds never become one number.
     */
    private fun loadProfileOf(
        request: ProgramAdaptiveRequest,
        presentation: EffectiveExercise,
        scope: AdaptiveScope
    ): LoadProfile {
        val prescription = presentation.prescription
        val repetitions = if (prescription.dimension == PrescriptionDimension.REP_BASED) {
            prescription.perSetTargets.sum()
        } else {
            0
        }
        val seconds = if (prescription.dimension == PrescriptionDimension.TIME_BASED) {
            prescription.perSetTargets.sum()
        } else {
            0
        }
        val level = request.relation.declared(presentation.exerciseId)?.level ?: request.window.level

        return LoadProfile(
            scope = scope,
            volume = VolumeLoad(
                sets = prescription.setCount,
                repetitions = repetitions,
                durationSeconds = seconds
            ),
            intensity = IntensityLoad(
                levels = level?.let { listOf(IntensityEntry(request.element.familyId, it)) } ?: emptyList()
            ),
            density = DensityLoad(workingSeconds = seconds, restSeconds = 0),
            exposure = ExposureLoad(opportunities = 1, completedOpportunities = 1)
        )
    }

    private fun presentationOf(
        presentation: EffectiveExercise,
        variant: ProgramProgressionVariant
    ): EffectiveExercise = EffectiveExercise(
        programExerciseId = presentation.programExerciseId,
        exerciseId = variant.exerciseId,
        prescription = variant.prescription,
        focus = presentation.focus
    )

    /**
     * Recovery is reported whether or not anything was adapted: a safety state that a user-authored
     * element could suppress would be no safety state at all.
     */
    private fun stateAfter(state: AdaptiveState, change: Resolution.Change?): AdaptiveState = when {
        state == AdaptiveState.RECOVERY -> AdaptiveState.RECOVERY
        change != null && change.action != AdaptiveAction.CHANGE_VARIANT -> state
        else -> AdaptiveState.HOLD
    }
}
