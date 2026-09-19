package com.monkfitness.app.domain.program.generated

import com.monkfitness.app.domain.adaptive.RecoveryContext
import com.monkfitness.app.domain.program.Focus
import com.monkfitness.app.domain.program.FocusPlan

/**
 * The **Focus Planner** (§8) — which focus every slot of a planning horizon is for.
 *
 * ### What it decides, and what it does not
 *
 * It decides, for each training slot of the plan, **one primary focus and `0–2` secondary focuses**,
 * and it decides them for the horizon *as a whole*: the slots of a plan are not planned one workout
 * at a time and then collected, they are apportioned out of one running account of what the plan
 * still owes each focus. That is the difference between §8's *"operates over the whole planning
 * horizon"* and a per-workout rule, and it is visible in the shape of the code: one fold over the
 * slots, one account of exposure, one deterministic comparison repeated until the horizon is full.
 *
 * It does **not** decide which exercise realizes a focus (that is the generator's selection), when a
 * slot is on the calendar (Scheduler, §20), whether a workout happened (session runtime, §19), or
 * anything adaptive at all: it reads [GenerationPreferences] as plain input and computes no evidence,
 * no progression and no recovery policy (§30 step 10 is before the Adaptive Engine).
 *
 * ### The arithmetic, in one place
 *
 * Exposure is apportioned with integer arithmetic only — no weight, no coefficient and no score is
 * attached to an exercise, and nothing here is multiplied by a physiological quantity:
 *
 * ```text
 * weight(focus)      BALANCED → every eligible focus weighs the same
 *                    FOCUSED  → the named focuses weigh the same, the others weigh nothing
 *                    CUSTOM   → the user's own percentage
 * need(focus, t)     weight(focus) · t  −  covered(focus) · total weight
 * ```
 *
 * where `t` is the number of the assignment being made (1-based, counted across the whole plan) and
 * `covered` is what the focus has already had — the exposure the caller supplied plus the assignments
 * this pass has already made. The focus with the largest `need` is chosen; ties are broken by the
 * vocabulary's canonical order, which is the only tie-break in this file and is deterministic by
 * construction.
 *
 * The formula has three properties worth stating, because each of them is a §8 requirement that this
 * shape satisfies rather than approximates:
 *
 *  * **target deficit** — a focus the plan owes more of has a larger `need`, and a focus that has
 *    already had more than its share has a *negative* `need` and is left alone;
 *  * **recent exposure** — because `covered` includes what the caller supplied, exposure that
 *    already happened suppresses further allocation of that focus without any special case;
 *  * **remaining horizon** — `t` counts across the whole plan, so the same request planned over three
 *    slots and over twelve produces different plans: what the horizon can still hold is part of the
 *    comparison, not a post-processing step.
 *
 * ### The soft rules, and why they are soft
 *
 * Two inputs can *demote* the focus that `need` would choose — a primary within the recent window
 * ([GenerationPolicy.recoveryWindowSlots]) and a focus whose recent load has reached
 * [GenerationPolicy.recentLoadThreshold]. Both demote it **only while another eligible focus still
 * needs exposure**; when none does, the demoted focus is chosen anyway. That fallback is the whole
 * difference between a preference and §14's forbidden universal hard rule: a rule with no fallback
 * would make the same focus impossible on two consecutive slots no matter what the plan owes, and
 * would be a 48-hour rule expressed in slots. Nothing here moves a workout, changes a frequency,
 * changes a duration, replaces a focus the user stated or cancels anything — §14's list of what
 * recovery may not do is a list of things this type has no vocabulary to express.
 *
 * The recovery *context* ([RecoveryContext.CAUTIOUS]) narrows how many secondary focuses a slot may
 * take — §14's *"more conservative"* — and is the only effect it has.
 *
 * ### Determinism
 *
 * The pass is a function of its arguments. It reads no clock, no random source, no map iteration
 * order and no mutable state: the account is an immutable value rolled forward by `fold`, focuses are
 * compared in `Focus.entries` order, and the same arguments produce the same assignments every time,
 * in any process, on any device (§9's *"same inputs must produce the same generated result"*).
 */
object FocusPlanner {

    /**
     * The focus assignment of every slot of a plan, in plan order.
     *
     * @param focus the Goals & Focus configuration being planned for.
     * @param slotCount how many slots the horizon holds.
     * @param plannableFocuses the focuses that can actually be planned — the configuration's eligible
     *   focuses, minus the ones no usable exercise trains. Allocation runs over this set, so the plan
     *   never owes exposure to a focus it cannot train.
     * @param preferences the caller's plain signals (recent exposure, recent load, recovery).
     * @param policy this stage's explicit numbers.
     * @return one [FocusAssignment] per slot, or an empty list when nothing is plannable.
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
     * The relative weight of each plannable focus under [focus] — the planner's reading of the
     * configuration, and the only place a configuration becomes a number.
     *
     * * `BALANCED` gives every plannable focus the same weight, because the user stated no share and
     *   no share is invented for them;
     * * `FOCUSED` gives the named focuses the same weight and every other focus none, because the
     *   user named focuses and stated no share;
     * * `CUSTOM` uses the user's own percentages, verbatim.
     *
     * The values are **relative** — they are compared with each other inside one plan and are never
     * read as a quantity of anything. A `BALANCED` plan's weights are not "1 unit of work" and a
     * `CUSTOM` plan's are not "100 units"; both are shares of one plan, and the arithmetic above
     * subtracts exposure from them in the same unit the caller reported exposure in.
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
     * The two forms share the number deliberately: it is a *relative* weight, and both forms mean
     * "the same amount of attention to each of these", so a plan that intends equal attention is one
     * number rather than two that could drift apart.
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
         * The secondaries are taken while a focus still has a **positive** deficit, which is what
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

        /** How many secondary focuses this slot may take, from §14's recovery context. */
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
