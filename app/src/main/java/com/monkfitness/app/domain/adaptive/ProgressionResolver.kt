package com.monkfitness.app.domain.adaptive

/**
 * What a progression resolution decided, named explicitly rather than encoded in nullable fields.
 */
enum class ProgressionOutcome {
    /** The family moved one level, and the target is the ladder's own step for that level. */
    STEP,

    /**
     * The step the request ordered was unavailable, so the level is unchanged and the variation the
     * family is already on is adjusted instead. Only a profile that declares a fallback can produce
     * this outcome.
     */
    FALLBACK,

    /** No valid progression path: nothing changes. */
    HOLD
}

/**
 * One progression resolution for one family: the outcome, the level it leaves the family on, and —
 * only where there is one — the concrete target.
 *
 * [exerciseId] and [adjustment] are absent for [ProgressionOutcome.HOLD] and present otherwise, and
 * [outcome] says which shape this is, so a caller never has to read "no path" out of a null.
 * [exerciseId] is always an exercise the profile's ladder declares and the caller's allowed set
 * contains: the resolver returns no other id, and invents none. [adjustment] is the low-level
 * rep/duration step the caller applies through the data layer's existing difficulty-adjustment
 * mechanism; it is `0` for a target that carries none, and the caller applies it to the target
 * exercise as the library defines it — this layer adjusts nothing itself.
 */
data class ProgressionResolution(
    val familyId: String,

    val outcome: ProgressionOutcome,

    /** The level the family is left on, always within [ProgressionProfile.LEVELS]. */
    val level: Int,

    /** The target exercise, present for every outcome except [ProgressionOutcome.HOLD]. */
    val exerciseId: String? = null,

    /** The low-level adjustment step to apply to [exerciseId], `0` when the target carries none. */
    val adjustment: Int = 0
) {
    init {
        require(familyId.isNotBlank()) { "a progression resolution must carry its family id" }
        require(level in ProgressionProfile.LEVELS) {
            "a progression resolution level must be within ${ProgressionProfile.LEVELS}, was $level"
        }
        val hasTarget = outcome != ProgressionOutcome.HOLD
        require(hasTarget == (exerciseId != null)) {
            "a $outcome resolution must ${if (hasTarget) "carry" else "not carry"} a target exercise, " +
                "was $exerciseId"
        }
        require(hasTarget || adjustment == 0) { "a HOLD resolution carries no adjustment, was $adjustment" }
        require(outcome != ProgressionOutcome.FALLBACK || adjustment != 0) {
            "a FALLBACK resolution must carry the adjustment step it orders, was $adjustment"
        }
    }
}

/**
 * The progression layer of Stage 1: a pure function from one family's abstract level and the action
 * the adaptation layer ordered to the concrete target that family's profile allows — or to the fact
 * that there is none.
 *
 * ## What a level means
 *
 * Not this class's business, and deliberately so. [ProgressionProfile] supplies the meaning per
 * family: the resolver moves the level by one in the requested direction and returns that profile's
 * own step for the resulting level. It never reads a level as a reps delta, never infers a target
 * from the caller's history, and never selects an exercise no profile declares.
 *
 * ## Direction
 *
 * The direction is the Task 4 action vocabulary, not a second one.
 * [AdaptiveAction.INCREASE_STIMULUS] is the next step and [AdaptiveAction.REDUCE_STIMULUS] the
 * previous one, [AdaptiveAction.MAINTAIN_STIMULUS] changes nothing, and [AdaptiveAction.RECOVERY_LOAD]
 * is not a progression-level move at all: the recovery session's load is the recovery profile's
 * business, and a hard stretch is not a regression — so the stored level is left exactly where it is.
 *
 * ## The three ways a request can come up empty
 *
 *  * **The boundary.** At `+2` there is no fifth level and at `-2` no fourth: HOLD, never a clamped
 *    level and never a substitute exercise. The fallback does not apply here either — it replaces a
 *    step that exists but is unavailable, and at the boundary there is no such step.
 *  * **The user's configuration.** The step's exercise may be disabled. The resolver never bypasses
 *    that: it returns the step only when the caller allows it, and otherwise falls back or holds.
 *  * **The fallback itself.** One adjustment step, in the requested direction, on the variation the
 *    current level declares — the level does not move, because the level's own step was what was
 *    unavailable. It is offered only where the profile declares
 *    [ProgressionProfile.fallbackStep], only when the caller's current exercise *is* that variation,
 *    only when that exercise is allowed, and never when the adjustment would leave the low-level
 *    adjustment range. A profile that declares none cannot reach this path at all.
 *
 * ## What it does not do
 *
 * It reads no clock, no random source and no storage, calls no workout generator, resolves no
 * exercise metadata and adjusts no exercise: it returns the step and leaves applying it to the
 * caller. It mutates nothing — the level it reports is its own value, not a rewritten state — and it
 * is deterministic: the same request always resolves to the same resolution.
 */
object ProgressionResolver {

    /**
     * The target [profile] orders for [direction] at [level], or the documented outcome saying there
     * is none.
     *
     * @param familyId the family this request is about. It must be the profile's own family, because
     *   a resolution is only meaningful for the family whose ladder produced it.
     * @param level the family's current abstract level, within [ProgressionProfile.LEVELS].
     * @param direction the action the adaptation layer ordered for the family's next session.
     * @param currentExerciseId the exercise id the caller's family state currently carries, or `null`
     *   when it carries none. Used for the fallback path only, and never inferred from history.
     * @param allowedExerciseIds the exercise ids the user's configuration currently enables. A
     *   target outside it is not a target.
     * @param profile the family's progression profile.
     */
    fun resolve(
        familyId: String,
        level: Int,
        direction: AdaptiveAction,
        currentExerciseId: String? = null,
        allowedExerciseIds: Set<String>,
        profile: ProgressionProfile
    ): ProgressionResolution {
        require(familyId == profile.familyId) {
            "family $familyId cannot be resolved with the profile of family ${profile.familyId}"
        }
        require(level in ProgressionProfile.LEVELS) {
            "progression level must be within ${ProgressionProfile.LEVELS}, was $level"
        }

        val delta = when (direction) {
            AdaptiveAction.INCREASE_STIMULUS -> 1
            AdaptiveAction.REDUCE_STIMULUS -> -1
            AdaptiveAction.MAINTAIN_STIMULUS, AdaptiveAction.RECOVERY_LOAD -> 0
        }
        if (delta == 0) return hold(familyId, level)

        val targetLevel = level + delta
        if (targetLevel !in ProgressionProfile.LEVELS) return hold(familyId, level)

        val step = profile.stepAt(targetLevel)
        if (step.exerciseId in allowedExerciseIds) {
            return ProgressionResolution(
                familyId = familyId,
                outcome = ProgressionOutcome.STEP,
                level = targetLevel,
                exerciseId = step.exerciseId,
                adjustment = step.adjustment
            )
        }

        return fallbackOrHold(familyId, level, delta, currentExerciseId, allowedExerciseIds, profile)
    }

    /**
     * The profile's declared alternative when the step's exercise is disabled: one adjustment step in
     * the requested direction on the variation this level declares.
     *
     * Every precondition is required, because a fallback that is not fully justified is not a
     * fallback: the profile must declare one, the caller's current exercise must be the step this
     * level declares (so the adjustment is an adjustment of the family's own variation and of
     * nothing else), that exercise must be allowed, and the adjustment must stay inside the low-level
     * range.
     */
    private fun fallbackOrHold(
        familyId: String,
        level: Int,
        delta: Int,
        currentExerciseId: String?,
        allowedExerciseIds: Set<String>,
        profile: ProgressionProfile
    ): ProgressionResolution {
        val fallbackStep = profile.fallbackStep ?: return hold(familyId, level)
        val current = profile.stepAt(level)
        if (current.exerciseId != currentExerciseId) return hold(familyId, level)
        if (current.exerciseId !in allowedExerciseIds) return hold(familyId, level)

        val adjustment = current.adjustment + if (delta > 0) fallbackStep else -fallbackStep
        if (adjustment !in ProgressionStep.MIN_ADJUSTMENT..ProgressionStep.MAX_ADJUSTMENT) {
            return hold(familyId, level)
        }

        return ProgressionResolution(
            familyId = familyId,
            outcome = ProgressionOutcome.FALLBACK,
            level = level,
            exerciseId = current.exerciseId,
            adjustment = adjustment
        )
    }

    private fun hold(familyId: String, level: Int): ProgressionResolution =
        ProgressionResolution(familyId = familyId, outcome = ProgressionOutcome.HOLD, level = level)
}
