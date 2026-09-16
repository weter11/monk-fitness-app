package com.monkfitness.app.domain.adaptive

import com.monkfitness.app.domain.adaptive.FamilyAdaptationState.Companion.MAX_LEVEL
import com.monkfitness.app.domain.adaptive.FamilyAdaptationState.Companion.MIN_LEVEL

/**
 * One family's adaptive outcome for one session: the [decision] the policy took for that family and
 * the [resolution] its own ladder produced in the direction that decision ordered.
 *
 * The two are kept side by side rather than collapsed because they answer different questions — the
 * decision says *whether* the family's stimulus should change, the resolution says *which step* of
 * that family it changed to — and because a session that has to explain itself needs both: a
 * resolution of HOLD under a PROGRESS decision is a fact (the step was unavailable), not a missing
 * value.
 */
data class AdaptiveFamilyResolution(
    val familyId: String,
    val decision: AdaptiveDecision,
    val resolution: ProgressionResolution
) {

    init {
        require(familyId.isNotBlank()) { "a family resolution must name its family" }
        require(decision.familyId == familyId) {
            "a family resolution must carry that family's decision, was $familyId and ${decision.familyId}"
        }
        require(resolution.familyId == familyId) {
            "a family resolution must carry that family's resolution, was $familyId and ${resolution.familyId}"
        }
    }
}

/**
 * The progression plan of one session: every family the decision window evaluated, resolved on its
 * own ladder and at its own stored level.
 *
 * This is the join between the two halves of Stage 1 that already existed separately — the adaptive
 * engine's per-family decision and the progression layer's per-family step — and it is deliberately
 * the *only* thing that joins them. It decides nothing itself: each family's level is the level its
 * caller stored, each family's direction is the action the policy ordered for that family, and each
 * family's target is whatever [ProgressionResolver] returns for that request. There is no global
 * difficulty level here, no second ladder and no second progression algorithm, and a family with no
 * profile is not given an invented one.
 *
 * What it answers for a generation path:
 *
 *  * [resolvedExerciseIds] — the exercises the families resolved to. For a STEP this is the ladder's
 *    step at the new level; for a FALLBACK it is the variation the family stays on; for a HOLD there
 *    is nothing, because there is nothing to prefer.
 *  * [adjustments] — the low-level rep/duration step each of those exercises carries, to be applied
 *    through the data layer's existing difficulty-adjustment mechanism. This layer adjusts nothing.
 *  * [families] — the full per-family audit of the window, ordered by ascending family id, so a
 *    session can say which family held and why without re-evaluating anything.
 */
data class AdaptiveProgressionPlan(
    val programDay: Int,
    val programCycle: Int,
    val programType: ProgramType,
    val policyVersion: Int,

    /** One resolution per evaluated family, ordered by ascending family id. */
    val families: List<AdaptiveFamilyResolution>
) {

    init {
        require(programDay >= 1) { "programDay must be >= 1, was $programDay" }
        require(programCycle >= 1) { "programCycle must be >= 1, was $programCycle" }
        require(policyVersion >= 1) { "policyVersion must be >= 1, was $policyVersion" }
        val familyIds = families.map { it.familyId }
        require(familyIds.toSet().size == familyIds.size) {
            "a family is resolved once in a plan, were $familyIds"
        }
        require(familyIds == familyIds.sorted()) {
            "family resolutions must be ordered by ascending family id, were $familyIds"
        }
    }

    /** The exercises the families resolved to, in no particular order. */
    val resolvedExerciseIds: Set<String>
        get() = families.mapNotNull { it.resolution.exerciseId }.toSet()

    /** The low-level adjustment each resolved exercise carries, `0` where its step carries none. */
    val adjustments: Map<String, Int>
        get() = families.mapNotNull { family ->
            family.resolution.exerciseId?.let { exerciseId -> exerciseId to family.resolution.adjustment }
        }.toMap()

    /** The resolution of [familyId], or `null` when the window evaluated no such family. */
    fun family(familyId: String): AdaptiveFamilyResolution? =
        families.firstOrNull { it.familyId == familyId }

    companion object {

        /**
         * The plan for one decided window.
         *
         * @param decision the program decision the engine produced for this window.
         * @param currentStates the caller's adaptation state per family, exactly as it is stored. A
         *   family the caller has no state for sits at [MIN_LEVEL]..[MAX_LEVEL]'s baseline, `0`, the
         *   same position [FamilyAdaptationState.notYetTracked] documents.
         * @param permittedExerciseIds the exercise ids the session may use: the effective
         *   configuration intersected with what the available equipment supports. The resolver
         *   returns no target outside it, and this layer adds none.
         * @param currentExerciseIdOf the exercise the caller's family state currently carries, or
         *   `null`. Read by the resolver's fallback path only, and never inferred from history.
         * @param profileOf the family's progression ladder, or `null` when the family has none. A
         *   `null` is not a gap to fill with a default: a family whose ladder has not been designed
         *   cannot be progressed, so it holds at its stored level.
         */
        fun of(
            decision: AdaptiveProgramDecision,
            currentStates: List<FamilyAdaptationState>,
            permittedExerciseIds: Set<String>,
            currentExerciseIdOf: (String) -> String? = { null },
            profileOf: (String) -> ProgressionProfile? = PilotProgressionProfiles::forFamily
        ): AdaptiveProgressionPlan {
            val statesByFamily = currentStates.associateBy { it.familyId }

            val families = decision.families.map { familyDecision ->
                val familyId = requireNotNull(familyDecision.familyId) {
                    "a decided window carries a family id per decision, but one ${familyDecision.state} " +
                        "decision carries none"
                }
                val level = statesByFamily[familyId]?.progressionLevel ?: BASELINE_LEVEL
                val profile = profileOf(familyId)
                val resolution = if (profile == null) {
                    // No ladder is designed for this family, so there is no step to take. Holding is
                    // the honest answer: the alternative would be inventing a ladder for it.
                    ProgressionResolution(
                        familyId = familyId,
                        outcome = ProgressionOutcome.HOLD,
                        level = level
                    )
                } else {
                    ProgressionResolver.resolve(
                        familyId = familyId,
                        level = level,
                        direction = directionOf(familyDecision),
                        currentExerciseId = currentExerciseIdOf(familyId),
                        allowedExerciseIds = permittedExerciseIds,
                        profile = profile
                    )
                }

                AdaptiveFamilyResolution(
                    familyId = familyId,
                    decision = familyDecision,
                    resolution = resolution
                )
            }

            return AdaptiveProgressionPlan(
                programDay = decision.programDay,
                programCycle = decision.programCycle,
                programType = decision.programType,
                policyVersion = decision.policyVersion,
                families = families
            )
        }

        /**
         * The direction one family's decision orders.
         *
         * The decision's action list is the Task 4 vocabulary, and Stage 1's policy emits exactly one
         * action per decision. A decision carrying several actions would need a composition rule that
         * does not exist yet, so it is refused loudly rather than resolved by picking one: silently
         * preferring the first action of a multi-action decision is how a future per-family expansion
         * would become a wrong session without anyone noticing.
         */
        private fun directionOf(decision: AdaptiveDecision): AdaptiveAction {
            require(decision.actions.size == 1) {
                "a family decision orders exactly one action at this stage, but " +
                    "${decision.familyId} carried ${decision.actions}"
            }
            return decision.actions.single()
        }

        /** The level a family with no stored state sits at: the ladder's own baseline. */
        const val BASELINE_LEVEL: Int = 0

        init {
            require(BASELINE_LEVEL in MIN_LEVEL..MAX_LEVEL) {
                "the baseline level must be inside the documented range $MIN_LEVEL..$MAX_LEVEL, " +
                    "was $BASELINE_LEVEL"
            }
        }
    }
}
