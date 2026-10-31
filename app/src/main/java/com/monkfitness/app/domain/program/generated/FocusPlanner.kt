package com.monkfitness.app.domain.program.generated

import com.monkfitness.app.domain.adaptive.RecoveryContext
import com.monkfitness.app.domain.program.Focus
import com.monkfitness.app.domain.program.FocusPlan

/**
 * Which focus every slot of a planning horizon is for: one primary and `0–2` secondary focuses.
 *
 * The slots are apportioned out of one running account of what the plan still owes each focus, not
 * planned one workout at a time and then collected — that is what makes it a horizon rather than a
 * per-workout rule, and it is one fold over the slots in the code. Exposure is apportioned with
 * integer arithmetic only: no weight, coefficient or score is attached to an exercise.
 *
 * ```text
 * weight(focus)      BALANCED → every eligible focus weighs the same
 *                    FOCUSED  → the named focuses weigh the same, the others weigh nothing
 *                    CUSTOM   → the user's own percentage
 * need(focus, t)     weight(focus) · t  −  covered(focus) · total weight
 * ```
 *
 * `t` is the number of the assignment being made (1-based, counted across the whole plan) and
 * `covered` is the exposure the caller supplied plus what this pass has already assigned; the largest
 * `need` wins, and ties break on the vocabulary's canonical order — the only tie-break here, and
 * deterministic by construction. Counting `t` across the whole plan is what makes the remaining
 * horizon part of the comparison, and including the caller's exposure is what suppresses a focus that
 * has already had its share without a special case. A focus over its share has a *negative* `need`
 * and is left alone.
 *
 * Two inputs can demote the focus `need` would choose — a primary within the recent window
 * ([GenerationPolicy.recoveryWindowSlots]) and a focus whose recent load has reached
 * [GenerationPolicy.recentLoadThreshold]. Both demote it **only while another eligible focus still
 * needs exposure**; when none does, the demoted focus is chosen anyway. That fallback is what keeps
 * them preferences rather than a forbidden universal hard rule: a rule with no fallback would make
 * the same focus impossible on two consecutive slots and would be a 48-hour rule expressed in slots.
 * The recovery context ([RecoveryContext.CAUTIOUS]) narrows how many secondary focuses a slot may
 * take, and is the only effect it has.
 *
 * Determinism: no clock, no random source, no map iteration order and no mutable state — the account
 * is an immutable value rolled forward by `fold`, focuses are compared in `Focus.entries` order, and
 * the same arguments produce the same assignments every time, in any process, on any device.
 */
object FocusPlanner {

    /**
     * The focus assignment of every slot of a plan, in plan order.
     *
     * @param plannableFocuses the focuses that can actually be planned — the configuration's eligible
     *   focuses, minus the ones no usable exercise trains. Allocation runs over this set, so the plan
     *   never owes exposure to a focus it cannot train.
     */
    fun allocate(
        focus: FocusPlan,
        slotCount: Int,
        plannableFocuses: List<Focus>,
        preferences: GenerationPreferences = GenerationPreferences.NONE,
        policy: GenerationPolicy = GenerationPolicy.DEFAULT
    ): List<FocusAssignment> {
        require(slotCount >= 0) { "a horizon holds zero or more slots, was $slotCount" }
        if (slotCount == 0 || plannableFocuses.isEmpty()) return emptyList()

        val weights = weightsOf(focus, plannableFocuses)
        val totalWeight = weights.values.sum()
        if (totalWeight <= 0) return emptyList()

        val start = Account(
            assignments = emptyList(),
            covered = plannableFocuses.associateWith { plannable -> preferences.recentExposureByFocus[plannable] ?: 0 },
            assigned = 0,
            primaries = emptyList()
        )

        return (1..slotCount)
            .fold(start) { account, _ -> account.withSlot(weights, totalWeight, plannableFocuses, preferences, policy) }
            .assignments
    }

    /**
     * The relative weight of each plannable focus under [focus] — the only place a configuration
     * becomes a number.
     *
     * `BALANCED` weighs every plannable focus the same, because the user stated no share and none is
     * invented for them; `FOCUSED` weighs the named focuses the same and every other focus none;
     * `CUSTOM` uses the user's own percentages verbatim. The values are **relative** — compared with
     * each other inside one plan, never read as a quantity of anything — and exposure is subtracted
     * from them in the unit the caller reported it in.
     */
    fun weightsOf(focus: FocusPlan, plannableFocuses: List<Focus>): Map<Focus, Int> =
        when (focus) {
            FocusPlan.Balanced -> plannableFocuses.associateWith { BALANCED_WEIGHT }
            is FocusPlan.Focused -> plannableFocuses.associateWith { plannable ->
                if (plannable in focus.focuses) FOCUSED_WEIGHT else 0
            }
            is FocusPlan.Custom -> plannableFocuses.associateWith { plannable -> focus.percentFor(plannable) }
        }

    /**
     * The weight every focus carries in a `BALANCED` plan, and in the named focuses of a `FOCUSED`
     * one.
     *
     * The two forms share the number deliberately: it is a *relative* weight, both mean "the same
     * amount of attention to each of these", and one number cannot drift from itself.
     */
    private const val BALANCED_WEIGHT: Int = 1

    private const val FOCUSED_WEIGHT: Int = 1

    /** What the plan has done so far: its assignments, its account of exposure, and its primaries. */
    private data class Account(
        val assignments: List<FocusAssignment>,
        val covered: Map<Focus, Int>,
        val assigned: Int,
        val primaries: List<Focus>
    ) {

        /** This account with one more slot allocated. */
        fun withSlot(
            weights: Map<Focus, Int>,
            totalWeight: Int,
            plannableFocuses: List<Focus>,
            preferences: GenerationPreferences,
            policy: GenerationPolicy
        ): Account {
            val chosen = chooseFocuses(weights, totalWeight, plannableFocuses, preferences, policy)
            if (chosen.isEmpty()) return this
            val assignment = FocusAssignment(
                primary = chosen.first(),
                secondary = FocusPlan.canonical(chosen.drop(1))
            )
            val updatedCovered = assignment.focuses.fold(covered) { running, served ->
                running + (served to (running.getValue(served) + 1))
            }
            return Account(
                assignments = assignments + assignment,
                covered = updatedCovered,
                assigned = assigned + assignment.focuses.size,
                primaries = primaries + assignment.primary
            )
        }

        /**
         * The focuses this slot serves: the primary (always one) and the secondaries the horizon
         * still owes (none, one or two).
         *
         * Secondaries are taken only while a focus still has a **positive** deficit, which is what
         * makes `0` secondaries a real outcome rather than a special case: once every plannable focus
         * has had at least its share, a slot is built around one focus instead of stacking more.
         */
        private fun chooseFocuses(
            weights: Map<Focus, Int>,
            totalWeight: Int,
            plannableFocuses: List<Focus>,
            preferences: GenerationPreferences,
            policy: GenerationPolicy
        ): List<Focus> {
            val limit = secondaryLimit(preferences.recovery, policy)
            val primary = choose(weights, totalWeight, plannableFocuses, preferences, policy, chosen = emptyList(), mustOweExposure = false)
                ?: return emptyList()
            return (1..limit).fold(listOf(primary)) { chosen, _ ->
                val next = choose(weights, totalWeight, plannableFocuses, preferences, policy, chosen = chosen, mustOweExposure = true)
                if (next == null) chosen else chosen + next
            }
        }

        /** How many secondary focuses this slot may take, from the recovery context. */
        private fun secondaryLimit(recovery: RecoveryContext, policy: GenerationPolicy): Int =
            when (recovery) {
                RecoveryContext.CAUTIOUS -> policy.cautiousSecondaryFocusLimit
                RecoveryContext.FAVORABLE, RecoveryContext.UNKNOWN -> policy.secondaryFocusLimit
            }

        /**
         * One focus for this slot, or `null` when nothing qualifies.
         *
         * @param chosen the focuses already chosen for this slot — each is excluded, so a focus is
         *   served once per slot.
         * @param mustOweExposure whether the focus must still be owed exposure (a secondary) or the
         *   slot simply needs a primary whether or not anything is owed.
         */
        private fun choose(
            weights: Map<Focus, Int>,
            totalWeight: Int,
            plannableFocuses: List<Focus>,
            preferences: GenerationPreferences,
            policy: GenerationPolicy,
            chosen: List<Focus>,
            mustOweExposure: Boolean
        ): Focus? {
            val assignmentNumber = assigned + chosen.size + 1
            val candidates = plannableFocuses.filterNot { it in chosen }
            val eligible = if (mustOweExposure) {
                candidates.filter { deficit(it, assignmentNumber, weights, totalWeight) > 0 }
            } else {
                candidates
            }
            if (eligible.isEmpty()) return null

            val avoided = avoidedFocuses(preferences, policy)
            val ranked = eligible.sortedWith(
                compareByDescending<Focus> { deficit(it, assignmentNumber, weights, totalWeight) }
                    .thenBy { it.ordinal }
            )
            return ranked.firstOrNull { it !in avoided } ?: ranked.first()
        }

        /**
         * The focuses the *soft* rules demote for this slot: a focus that led a recent slot, and a
         * focus whose recent load has reached the policy's threshold.
         *
         * Both are read through map lookups keyed by the focus itself, so the order of the caller's
         * maps cannot influence the outcome, and the window is read from the account's own primaries
         * — the plan's own recent history — never from a clock.
         */
        private fun avoidedFocuses(
            preferences: GenerationPreferences,
            policy: GenerationPolicy
        ): Set<Focus> {
            val recentPrimaries = primaries
                .takeLast(policy.recoveryWindowSlots)
                .toSet()
            val loaded = Focus.entries.filter { focus ->
                (preferences.recentLoadByFocus[focus] ?: 0) >= policy.recentLoadThreshold
            }
            return recentPrimaries + loaded
        }

        /**
         * What the plan still owes [focus] at assignment [assignmentNumber].
         *
         * Positive means the focus has had less than its share, `0` exactly its share, negative more
         * than its share. A focus that owes nothing is never taken as a secondary and is only taken
         * as a primary when nothing else is available at all.
         */
        private fun deficit(
            focus: Focus,
            assignmentNumber: Int,
            weights: Map<Focus, Int>,
            totalWeight: Int
        ): Int = weights.getValue(focus) * assignmentNumber - covered.getValue(focus) * totalWeight
    }
}
