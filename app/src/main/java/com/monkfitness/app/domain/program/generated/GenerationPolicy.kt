package com.monkfitness.app.domain.program.generated

import com.monkfitness.app.domain.program.PLANNING_HORIZON_DAYS

/**
 * The Generated Planner's **explicit** numbers — every figure the blueprint leaves open, in one value,
 * each one an owner decision rather than a fact.
 *
 * A generator still has to produce *something* for numbers the spec leaves unfixed, and there are only
 * two honest ways to do it: leave the plan ungenerated, or state the numbers plainly in one place. This
 * type is the second. Three rules hold for everything here: a number the planner uses is a property with
 * a default and a sentence saying what it decides, never a private constant inside the arithmetic; no
 * field is presented as physiological truth — no load score, no readiness percentage, no per-exercise
 * difficulty, but a *count of assignments* or a *count of sets the caller supplied*; and no field is a
 * hard rule the blueprint forbids — the recovery fields are soft preferences with a documented fallback
 * and cannot move a workout, change a frequency or duration, replace a focus or cancel a plan.
 *
 * The two prescription shapes are the ones the blueprint itself writes out (`12 / 10 / 8 / 6` and
 * `30s / 30s / 45s`), used verbatim — but as the **fallback**, not the rule: an exercise whose catalogue
 * metadata states a progression range is prescribed at that range's lower bound for the catalogue's own
 * set count, and these lists answer only for a candidate that states no range. They are kept so that
 * "the canonical shapes, verbatim" stays expressible by a caller that wants it.
 *
 * @property cautiousSecondaryFocusLimit how many secondary focuses a slot may take when the recovery
 *   context is [com.monkfitness.app.domain.adaptive.RecoveryContext.CAUTIOUS]. It never affects the
 *   primary focus, the number of slots, the schedule or the duration: a cautious context narrows what a
 *   workout stacks, and nothing else.
 * @property indefiniteCycleWeeks how many whole weeks an indefinite Program's generated plan covers —
 *   [PLANNING_HORIZON_DAYS] `/ 7`. A fixed Program uses its own duration instead
 *   ([minimumCycleWeeks]); no second calendar interpretation is introduced.
 * @property recoveryWindowSlots how many slots back a primary focus is remembered for the **soft**
 *   recovery penalty. A focus inside this window is only passed over when another eligible focus still
 *   needs exposure; when none does, the same focus may lead two workouts in a row — which is what makes
 *   it a preference and not a forbidden universal 48-hour rule.
 * @property recentLoadThreshold how many recently performed sets of a focus make it *loaded* for the
 *   soft load penalty. The value is a **count of sets the caller supplied**, never a scalar load score
 *   and never a conversion between focuses. Like the recovery penalty it is soft: a loaded focus is
 *   passed over only while another eligible focus still needs exposure.
 * @property repPrescriptionTargets the repetition prescription a generated `REP_BASED` element carries,
 *   per set — read only for a candidate whose metadata states no repetition range.
 * @property timePrescriptionTargets the duration prescription a generated `TIME_BASED` element carries,
 *   in seconds per set — the same fallback rule, for a candidate with no duration range.
 */
data class GenerationPolicy(
    val secondaryFocusLimit: Int = DEFAULT_SECONDARY_FOCUS_LIMIT,
    val cautiousSecondaryFocusLimit: Int = DEFAULT_CAUTIOUS_SECONDARY_FOCUS_LIMIT,
    val indefiniteCycleWeeks: Int = DEFAULT_INDEFINITE_CYCLE_WEEKS,
    val minimumCycleWeeks: Int = DEFAULT_MINIMUM_CYCLE_WEEKS,
    val recoveryWindowSlots: Int = DEFAULT_RECOVERY_WINDOW_SLOTS,
    val recentLoadThreshold: Int = DEFAULT_RECENT_LOAD_THRESHOLD,
    val repPrescriptionTargets: List<Int> = DEFAULT_REP_PRESCRIPTION_TARGETS,
    val timePrescriptionTargets: List<Int> = DEFAULT_TIME_PRESCRIPTION_TARGETS
) {

    init {
        require(secondaryFocusLimit in 0..MAX_SECONDARY_FOCUSES) {
            "a slot takes 0..$MAX_SECONDARY_FOCUSES secondary focuses, so the limit is within that " +
                "range, was $secondaryFocusLimit"
        }
        require(cautiousSecondaryFocusLimit in 0..secondaryFocusLimit) {
            "a cautious context narrows the secondary limit ($secondaryFocusLimit), it never raises " +
                "it; was $cautiousSecondaryFocusLimit"
        }
        require(indefiniteCycleWeeks >= 1) {
            "an indefinite plan covers at least one week, was $indefiniteCycleWeeks"
        }
        require(minimumCycleWeeks >= 1) {
            "a cycle is at least one week long, was $minimumCycleWeeks"
        }
        require(recoveryWindowSlots >= 0) {
            "the recovery window is a count of slots back, was $recoveryWindowSlots"
        }
        require(recentLoadThreshold >= 1) {
            "the load penalty is a threshold on a count of sets, was $recentLoadThreshold"
        }
        require(repPrescriptionTargets.isNotEmpty() && repPrescriptionTargets.all { it > 0 }) {
            "a generated repetition prescription asks for work every set, was $repPrescriptionTargets"
        }
        require(timePrescriptionTargets.isNotEmpty() && timePrescriptionTargets.all { it > 0 }) {
            "a generated duration prescription asks for work every set, was $timePrescriptionTargets"
        }
    }

    companion object {

        /** The ceiling on secondary focuses: 1 primary and 0–2 secondary focuses per slot. */
        const val MAX_SECONDARY_FOCUSES: Int = 2

        /** The ceiling, used as the default. */
        const val DEFAULT_SECONDARY_FOCUS_LIMIT: Int = MAX_SECONDARY_FOCUSES

        /**
         * One secondary focus while the recovery context is cautious — "make progression more
         * conservative" read as "stack less", the only reading that touches no focus, no day and no
         * schedule.
         */
        const val DEFAULT_CAUTIOUS_SECONDARY_FOCUS_LIMIT: Int = 1

        /**
         * An indefinite Program's plan covers the planning horizon in whole weeks — 30 days is 4 whole
         * weeks, and the days beyond the horizon are planned when the horizon rolls forward.
         */
        const val DEFAULT_INDEFINITE_CYCLE_WEEKS: Int = PLANNING_HORIZON_DAYS / 7

        /** A plan covers at least one week, however short the Program is. */
        const val DEFAULT_MINIMUM_CYCLE_WEEKS: Int = 1

        /** Two slots back: the soft recovery window. */
        const val DEFAULT_RECOVERY_WINDOW_SLOTS: Int = 2

        /** Six sets of one focus, recently performed, is the soft load penalty's threshold. */
        const val DEFAULT_RECENT_LOAD_THRESHOLD: Int = 6

        /** The repetition example shape, used unchanged — the fallback for a candidate with no range. */
        val DEFAULT_REP_PRESCRIPTION_TARGETS: List<Int> = listOf(12, 10, 8, 6)

        /** The duration example shape, used unchanged — the same fallback, in seconds. */
        val DEFAULT_TIME_PRESCRIPTION_TARGETS: List<Int> = listOf(30, 30, 45)

        /** The policy the composition root uses until an owner decision says otherwise. */
        val DEFAULT: GenerationPolicy = GenerationPolicy()
    }
}
