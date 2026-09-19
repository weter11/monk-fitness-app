package com.monkfitness.app.domain.program.generated

import com.monkfitness.app.domain.program.PLANNING_HORIZON_DAYS

/**
 * The Generated Planner's **explicit** numbers — every figure the blueprint leaves open, in one
 * value, each one an owner decision rather than a fact.
 *
 * §8 says it twice: *"soft recovery penalties"*, *"exact coefficients are not yet fixed"*. §9 says the
 * same thing about selection. A generator still has to produce *something* for those open numbers,
 * and there are only two honest ways to do it: leave the plan ungenerated, or state the numbers
 * plainly, in one place, as decisions. This type is the second.
 *
 * Three rules hold for everything here, and they are the reason this is a value rather than a
 * scattering of literals:
 *
 *  * **nothing is hidden.** A number the planner uses is a property of this type with a default in
 *    [Companion] and a sentence explaining what it decides. There is no private constant inside the
 *    allocation arithmetic, no implicit fallback and no tuned value smuggled into a comparison.
 *  * **nothing is presented as physiological truth.** No field here is a load score, a readiness
 *    percentage or a per-exercise difficulty. Where §17 forbids a universal scalar, this type offers
 *    a *count of assignments* or a *count of confirmed sets supplied by the caller* — and says so.
 *  * **nothing is a hard rule the blueprint forbids.** The recovery fields are soft preferences with
 *    a documented fallback; no field here can move a workout, change the frequency or the duration,
 *    replace a focus or cancel a plan (§14).
 *
 * Two defaults are not invented at all: the two prescription shapes are the ones §10 itself writes
 * out (`12 / 10 / 8 / 6` and `30s / 30s / 45s`), used verbatim.
 *
 * @property secondaryFocusLimit how many secondary focuses a slot may take (§8 allows `0–2`; the
 *   default is the maximum §8 permits).
 * @property cautiousSecondaryFocusLimit how many secondary focuses a slot may take when the recovery
 *   context is [com.monkfitness.app.domain.adaptive.RecoveryContext.CAUTIOUS] — §14's *"recovery can
 *   make progression more conservative"*. It never affects the primary focus, the number of slots,
 *   the schedule or the duration: a cautious context narrows what a workout stacks, and nothing else.
 * @property indefiniteCycleWeeks how many whole weeks an indefinite Program's generated plan covers.
 *   It is [PLANNING_HORIZON_DAYS] `/ 7` — the horizon §20 and §30 step 7 established for an indefinite
 *   Program, expressed in the whole weeks a plan is made of. A fixed Program uses its own duration
 *   instead ([minimumCycleWeeks]); no second calendar interpretation is introduced.
 * @property minimumCycleWeeks the shortest cycle the planner will build, whatever the duration says.
 * @property recoveryWindowSlots how many slots back a primary focus is remembered for the **soft**
 *   recovery penalty. A focus inside this window is only passed over when another eligible focus
 *   still needs exposure; when none does, the same focus may lead two workouts in a row. That is what
 *   makes it a preference and not §14's forbidden universal hard 48-hour rule — a rule with no
 *   fallback would be exactly a two-day rule expressed in slots.
 * @property recentLoadThreshold how many sets of a focus, recently performed, make that focus
 *   *loaded* for the soft load penalty. The value is a **count of sets the caller supplied**, never a
 *   scalar load score (§17) and never a conversion between focuses; nothing here compares a
 *   conditioning set with a pressing set. Like the recovery penalty it is soft: a loaded focus is
 *   passed over only while another eligible focus still needs exposure.
 * @property repPrescriptionTargets the repetition prescription a generated `REP_BASED` element
 *   carries, per set — §10's own example shape, used unchanged.
 * @property timePrescriptionTargets the duration prescription a generated `TIME_BASED` element
 *   carries, in seconds per set — §10's other example shape, used unchanged.
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

        /** §8's ceiling on secondary focuses: *"1 primary focus and 0–2 secondary focuses per slot"*. */
        const val MAX_SECONDARY_FOCUSES: Int = 2

        /** §8's ceiling, used as the default. */
        const val DEFAULT_SECONDARY_FOCUS_LIMIT: Int = MAX_SECONDARY_FOCUSES

        /**
         * One secondary focus while the recovery context is cautious — §14's *"make progression more
         * conservative"*, read as "stack less", which is the only reading that touches no focus,
         * no day and no schedule.
         */
        const val DEFAULT_CAUTIOUS_SECONDARY_FOCUS_LIMIT: Int = 1

        /**
         * An indefinite Program's plan covers the §20 horizon in whole weeks — 30 days is 4 whole
         * weeks, and the days beyond the horizon are planned when the horizon rolls forward.
         */
        const val DEFAULT_INDEFINITE_CYCLE_WEEKS: Int = PLANNING_HORIZON_DAYS / 7

        /** A plan covers at least one week, however short the Program is. */
        const val DEFAULT_MINIMUM_CYCLE_WEEKS: Int = 1

        /** Two slots back: the soft recovery window. */
        const val DEFAULT_RECOVERY_WINDOW_SLOTS: Int = 2

        /** Six sets of one focus, recently performed, is the soft load penalty's threshold. */
        const val DEFAULT_RECENT_LOAD_THRESHOLD: Int = 6

        /** §10's repetition example, used unchanged: `12 / 10 / 8 / 6`. */
        val DEFAULT_REP_PRESCRIPTION_TARGETS: List<Int> = listOf(12, 10, 8, 6)

        /** §10's duration example, used unchanged: `30s / 30s / 45s`. */
        val DEFAULT_TIME_PRESCRIPTION_TARGETS: List<Int> = listOf(30, 30, 45)

        /** The policy the composition root uses until an owner decision says otherwise. */
        val DEFAULT: GenerationPolicy = GenerationPolicy()
    }
}
