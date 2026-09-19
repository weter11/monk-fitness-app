package com.monkfitness.app.domain.adaptive.engine

import com.monkfitness.app.domain.adaptive.AdaptiveInputSnapshot
import com.monkfitness.app.domain.adaptive.AdaptiveScope
import com.monkfitness.app.domain.adaptive.AdaptiveState
import com.monkfitness.app.domain.adaptive.DensityLoad
import com.monkfitness.app.domain.adaptive.ExposureLoad
import com.monkfitness.app.domain.adaptive.IntensityEntry
import com.monkfitness.app.domain.adaptive.IntensityLoad
import com.monkfitness.app.domain.adaptive.LoadProfile
import com.monkfitness.app.domain.adaptive.VolumeLoad
import com.monkfitness.app.domain.adaptive.decision.AdaptiveAction
import com.monkfitness.app.domain.adaptive.decision.AdaptiveAdjustment
import com.monkfitness.app.domain.adaptive.decision.AdaptiveDecision
import com.monkfitness.app.domain.adaptive.decision.AdaptiveTarget
import com.monkfitness.app.domain.adaptive.decision.DecisionOutcome
import com.monkfitness.app.domain.common.AdjustmentId
import com.monkfitness.app.domain.common.DecisionId
import com.monkfitness.app.domain.prescription.PrescriptionDimension
import com.monkfitness.app.domain.workout.EffectiveExercise
import java.time.Instant

/**
 * Everything the engine is given for one decision (§11, §15, §16).
 *
 * The request is the whole input surface: a frozen window of what happened, the element the decision is
 * about, the family's own progression hierarchy, the family's maintained window facts, the user's own
 * selection, the identity and the moment. There is no collaborator, no lookup and no default that
 * reaches for one — which is what makes a decision reproducible from this value alone.
 *
 * Three of its fields are worth a sentence, because each is a fact that would otherwise be guessed:
 *
 *  * [availableExerciseIds] is **the user's own selection** (§9). A progression step whose exercise is
 *    not in it is not a target: the engine holds rather than bypassing the choice, and never returns a
 *    different exercise in its place;
 *  * [familyOfExercise] is the caller's exercise-to-family classification, the same shape the signal
 *    layer uses. The domain owns no exercise catalogue, so an element's family arrives as an input;
 *  * [decidedAt] is the moment the decision is stamped with. The engine **reads no clock** — a stage
 *    that read one would be untestable and would hide a decision the caller owns (§26) — so the caller's
 *    injected clock is read at its own boundary and handed in here.
 *
 * @property decisionId the identity this decision is recorded under.
 * @property adjustmentId the identity a produced adjustment would carry. It is required whether or not
 *   the decision turns out to change anything, so that the engine's output shape does not depend on its
 *   input shape.
 * @property decidedAt the moment the decision was taken, supplied by the caller.
 * @property snapshot the frozen facts of the window (§11).
 * @property element the plan element this decision is about, and whose it is.
 * @property relation the family's own declared progression hierarchy.
 * @property window the family's maintained facts: its position, state, counts and cooldown position.
 * @property familyOfExercise the caller's exercise-to-family classification.
 * @property availableExerciseIds the exercises the user's configuration enables.
 * @property supersedesAdjustmentId the adjustment this one replaces for the slot, or `null`. An
 *   adjustment supersedes by reference: the earlier row is never rewritten (§16).
 * @property policy the adaptive policy, thresholds and guard tolerances included.
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
 * What the adaptive stage decided, and everything needed to explain it (§15, §16, §18, §22).
 *
 * The two records travel together because they are one fact: [decision] is the auditable decision —
 * action, target, grounds, outcome — and [adjustment] is the bounded change it produced, present
 * exactly when the decision was applied and absent otherwise. A filtered decision keeps the reason that
 * filtered it, so nothing in this stage disappears silently.
 *
 * [requestedAction] is deliberately separate from the decision's own action. When §18's aggregate load
 * guard refuses a change, the decision is recorded as a `HOLD` — the change did not happen — while this
 * field still shows that a `PROGRESS` was asked for and refused, which is what makes *"the program
 * wanted to progress you and the load guard said not yet"* readable from the record instead of being
 * flattened into an ordinary hold.
 *
 * [state] is the family's adaptation state **after** this window, and it follows from what actually
 * happened rather than from what was asked for: a change that the relation could not express, an
 * element that is the user's, or a change the guard filtered all leave the family where it was. The one
 * state that is not a change is §14's recovery: entering, staying in and leaving recovery are states,
 * and they are reported here for the caller to store (§23's `FamilyProgressionState`).
 *
 * [verdict] is the window's own three-answer reading, and it travels with the decision for one reason:
 * the caller maintains the family's confirmation counts, cooldown position and recovery exit count from
 * it (§15's `ProgramAdaptiveWindow`), and no window can count itself. It is the policy's own finding,
 * reported rather than recomputed — see `ProgramWindowVerdict`.
 *
 * @property decision the auditable decision.
 * @property adjustment the change it produced, present exactly when it was applied.
 * @property state the family's adaptation state after this window.
 * @property reason the single rule that produced this result.
 * @property requestedAction what the policy asked for, before the relation, the ownership and the guard
 *   were consulted.
 * @property signals the derived signals the decision was made on, for the audit (§13).
 * @property guard what the aggregate load guard said.
 * @property verdict what the policy found in this window — the facts the caller's own bookkeeping
 *   advances between windows (§15, §30 step 12).
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

    /** Whether the decision changed what the user will be presented with (§16). */
    val isApplied: Boolean
        get() = decision.isApplied

    /** Whether the load guard refused a change (§18) — the refusal the decision's reason records. */
    val wasFilteredByTheGuard: Boolean
        get() = guard is ProgramGuardVerdict.Filtered
}

/**
 * §30 step 11's Adaptive Engine: a pure function from one request to one auditable decision.
 *
 * ```text
 * AdaptiveInputSnapshot ─→ ProgramAdaptiveSignalCalculator ─→ ProgramAdaptiveSignals
 *                       ─→ ProgramAdaptivePolicy            ─→ the state, the action, the reason
 *                       ─→ this engine                      ─→ the family's own hierarchy resolves it
 *                       ─→ ProgramAggregateLoadGuard        ─→ applied, or filtered to a HOLD
 * ```
 *
 * ### What the engine owns, and what it deliberately does not
 *
 * The engine owns three things and nothing more:
 *
 *  * **the resolution** — turning the policy's action into a concrete `before` → `after` of one plan
 *    element, from the family's declared hierarchy. A progression with no single declared step above
 *    the family's level is §15's ceiling; a regression with none below is its floor; a declared step
 *    whose exercise the user has not enabled is unavailable. All three are `HOLD`s with their own
 *    reason — never a substituted exercise, and never a clamped level;
 *  * **the variant realignment** — when the element presents an exercise the family's hierarchy does
 *    not declare at the family's level *and* the family declares another variant there that the user
 *    does have enabled, the change is `CHANGE_VARIANT`. This is §15's *"explicit progression
 *    relation"*: both ends are declared at the same level, so the move is bounded and neither harder
 *    nor easier, and a swap the relation does not declare is simply not an adaptation;
 *  * **the composition of the two profiles §18 compares** — the element as the plan presents it and the
 *    element as the change would present it, stated in the plan's own units. Both are derived from the
 *    element's own prescription and its family's own levels, and the recent side of the comparison is
 *    the caller's own context from the snapshot. Nothing is invented to make a comparison possible: a
 *    channel the two sides do not share a unit for is reported incomparable and blocks nothing.
 *
 * It owns no threshold — the numbers are read from the policy it was handed — and it makes no decision
 * the policy has not already made: a `REGRESS` reaches the user only because the policy asked for one.
 *
 * ### The order of the checks, and why it matters
 *
 * ```text
 * 1. whose element is it   the user's own content is not adapted at all (§15, §18)
 * 2. the policy            the family's state, the action and the reason (§15)
 * 3. the relation          the concrete before/after, or the bounded non-progressing answer
 * 4. the load guard        an automatic increase may be refused; a refusal becomes a HOLD (§18)
 * 5. the records           the decision and, when applied, the adjustment (§16, §22)
 * ```
 *
 * Step 1 comes first because §18 guards only automatic changes and §15 keeps adaptive logic off a
 * pinned choice: there is no path by which any rule below runs against an element the user owns.
 *
 * ### What it never does
 *
 * It creates no revision, mutates no revision, slot or session, writes nothing, resolves no exercise
 * metadata, reads no clock, no random source, no storage and no global, and it has no collaborator to
 * reach for: its whole input is one value. The alternative a decision produces is a value handed back
 * to the caller — persisting it, consuming it at a session's start and showing it are §30 step 12's
 * work, and this stage stops at the boundary that makes them possible.
 *
 * It is deterministic. Equal requests produce equal results — the same decision, action, outcome,
 * target, reason, before/after and ordering — whatever order the caller built its sets, maps or
 * observation lists in, because every read that could depend on an order is sorted: the observations by
 * their own stamps, the relation's variants canonically, and the comparison's channels by their own
 * canonical order. There is no tie left to break by accident.
 */
object ProgramAdaptiveEngine {

    /** The deterministic result for one request. */
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

        return ProgramAdaptiveResult(
            decision = decision,
            adjustment = adjustment,
            state = stateAfter(policyDecision.state, change),
            reason = reason,
            requestedAction = policyDecision.action,
            signals = signals,
            guard = guardVerdict,
            verdict = policyDecision.verdict
        )
    }

    // ---------------------------------------------------------------------------------------------
    // The family's hierarchy: what the policy's action resolves to, or why it resolves to nothing.
    // ---------------------------------------------------------------------------------------------

    /** Either a concrete change to one element, or the reason there is none. */
    private sealed interface Resolution {

        /** The guard's verdict travels with the resolution so the result can report what it saw. */
        val guardVerdict: ProgramGuardVerdict?

        data class Change(
            val action: AdaptiveAction,
            val target: AdaptiveTarget,
            val after: EffectiveExercise,
            val reason: ProgramAdaptiveReason,
            override val guardVerdict: ProgramGuardVerdict? = null
        ) : Resolution

        data class None(
            val reason: ProgramAdaptiveReason,
            override val guardVerdict: ProgramGuardVerdict? = null
        ) : Resolution

        /**
         * A change the policy asked for and §18's guard refused.
         *
         * It keeps the target the change was about, so a filtered decision still names what was refused
         * and at what granularity — which is what makes *"the program wanted to progress this family and
         * the load guard said not yet"* readable from the record rather than flattened into an
         * unattributed hold.
         */
        data class Refused(
            val target: AdaptiveTarget,
            override val guardVerdict: ProgramGuardVerdict
        ) : Resolution
    }

    /** The policy's answer, resolved against the family's own hierarchy and the user's own selection. */
    private fun resolve(
        request: ProgramAdaptiveRequest,
        policyDecision: ProgramPolicyDecision
    ): Resolution {
        if (policyDecision.action == AdaptiveAction.HOLD) {
            return realignment(request) ?: Resolution.None(policyDecision.reason)
        }
        return when (policyDecision.action) {
            AdaptiveAction.PROGRESS -> step(request, up = true)
            AdaptiveAction.REGRESS -> step(request, up = false)
            // The vocabulary's fifth action arrives with `REST_BASED` (§10), which the domain cannot
            // express yet — the policy reports it unsupported rather than this stage faking a target.
            AdaptiveAction.CHANGE_REST ->
                Resolution.None(ProgramAdaptiveReason.REST_CHANGE_UNSUPPORTED)

            AdaptiveAction.HOLD, AdaptiveAction.CHANGE_VARIANT ->
                Resolution.None(policyDecision.reason)
        }
    }

    /**
     * One step along the family's own hierarchy, in the ordered direction.
     *
     * The family's level is the caller's maintained one when it was supplied, and otherwise the level
     * the hierarchy declares for the exercise the element presents. Everything the step needs is then
     * the relation's own answer: no step above is the ceiling, no step below is the floor, a level the
     * relation does not declare or a step it does not single out is unavailable, and a step whose
     * exercise the user has not enabled is unavailable too — a bounded non-progressing result, never a
     * different exercise and never a clamped position.
     */
    private fun step(request: ProgramAdaptiveRequest, up: Boolean): Resolution {
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
        if (variant.exerciseId !in request.availableExerciseIds) {
            return Resolution.None(ProgramAdaptiveReason.PROGRESSION_UNAVAILABLE)
        }

        return Resolution.Change(
            action = if (up) AdaptiveAction.PROGRESS else AdaptiveAction.REGRESS,
            target = AdaptiveTarget.Family(relation.familyId),
            after = presentationOf(request.element.presentation, variant),
            reason = if (up) ProgramAdaptiveReason.SUSTAINED_POSITIVE else ProgramAdaptiveReason.SUSTAINED_NEGATIVE
        )
    }

    /**
     * The same-level variant change §15 permits, or `null` when there is nothing to realign.
     *
     * A realignment is offered only when both ends are declared at the family's own level: the element's
     * exercise is not a variant the family declares **there** (or not one the user enabled), and another
     * variant is declared there and is enabled. The replacement is taken in the relation's canonical
     * order, so the choice between two declared alternatives is a read of the hierarchy and not a
     * preference of this file's. A family that declares one variant per level has no realignment at all.
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

    /** The family's level: the caller's maintained one, or the one the hierarchy declares. */
    private fun levelOf(request: ProgramAdaptiveRequest): Int? =
        request.window.level ?: request.relation.declared(request.element.exerciseId)?.level

    // ---------------------------------------------------------------------------------------------
    // §18: baseline + adaptive delta + recent context, compared at the decision's own scope.
    // ---------------------------------------------------------------------------------------------

    /**
     * Lets the aggregate load guard speak about an automatic increase.
     *
     * Only an increase is submitted to it: a `HOLD` changes nothing and a `REGRESS` takes load away, so
     * the guard's own first answer is that they are not its business. When it refuses the change, the
     * resolution becomes the `HOLD` §18 requires — the guard never invents a regression in its place —
     * and the decision keeps the refusal as its reason.
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
            policy = request.policy
        )
        return if (verdict is ProgramGuardVerdict.Filtered) {
            Resolution.Refused(target = change.target, guardVerdict = verdict)
        } else {
            change.copy(guardVerdict = verdict)
        }
    }

    /**
     * The load of one presentation of the element, in the four dimensions §17 keeps apart.
     *
     * Every number here is read from the presentation itself — the sets it prescribes, the targets it
     * writes per set and the dimension it writes them in — and from the family's own level. Nothing is
     * converted: a repetition-based element has repetitions and no seconds, a time-based one has seconds
     * and no repetitions, and the two never become one number.
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

    /** The element's own presentation of [variant]: the same plan element, presenting that variant. */
    private fun presentationOf(
        presentation: EffectiveExercise,
        variant: ProgramProgressionVariant
    ): EffectiveExercise = EffectiveExercise(
        programExerciseId = presentation.programExerciseId,
        exerciseId = variant.exerciseId,
        prescription = variant.prescription
    )

    /**
     * The family's state after the window: what actually happened, not what was asked for.
     *
     * A change that was not expressed — because the relation has no step, because the user's selection
     * excludes it, because the guard filtered it or because the element is the user's own — leaves the
     * family where it was. The one state that is not a change to the element is §14's recovery, which is
     * reported as the state the caller should store whether or not anything was adapted: a safety state
     * that a user-authored element could suppress would be no safety state at all.
     */
    private fun stateAfter(state: AdaptiveState, change: Resolution.Change?): AdaptiveState = when {
        state == AdaptiveState.RECOVERY -> AdaptiveState.RECOVERY
        change != null && change.action != AdaptiveAction.CHANGE_VARIANT -> state
        else -> AdaptiveState.HOLD
    }
}
