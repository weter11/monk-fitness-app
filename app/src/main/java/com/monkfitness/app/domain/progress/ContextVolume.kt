package com.monkfitness.app.domain.progress

import com.monkfitness.app.domain.prescription.PrescriptionDimension

/**
 * §21's **volume**, in one comparable context — which is the only place §17 allows volume to exist.
 *
 * §17 is unusually precise about this and the type follows it exactly:
 *
 * ```text
 * volume is meaningful primarily within comparable exercise/family contexts
 * no universal scalar load score
 * no invented conversion such as "1 push-up = X load units"
 * ```
 *
 * So a volume here is *one exercise, one unit*, and the unit is not optional: a repetition context carries
 * [repetitions] and **no** seconds, a timed context carries [seconds] and **no** repetitions. The other
 * side is `null` — not `0` — because a zero would be a value that adds, and the one operation that must
 * never be possible is adding a repetition total to a duration total. The invariants below make that
 * unrepresentable rather than merely discouraged, and there is deliberately **no `total`, no `amount`
 * and no cross-context sum** in or beside this type (§17, §21's *"Do not create a universal scalar
 * volume"*).
 *
 * A "context" is (§12, §17) one exercise in one dimension — see [ComparableContext]. Family-level volume
 * is §21's *family distribution*, and it is **not** produced here: the target runtime has no family
 * vocabulary of its own (the only family metadata in the app belongs to the legacy generator and the
 * Stage-1 adaptive state), so a family total would have to be built on the architecture §30 step 15 is
 * retiring. That measure is reported as deferred ([ProgressMeasure.FAMILY_DISTRIBUTION]) rather than
 * guessed.
 *
 * @property context the exercise and the unit this volume is measured in.
 * @property performedSets how many sets the context accumulated.
 * @property repetitions the repetitions the context accumulated, or `null` in a timed context.
 * @property seconds the seconds the context accumulated, or `null` in a repetition context.
 */
data class ContextVolume(
    val context: ComparableContext,
    val performedSets: Int,
    val repetitions: Int?,
    val seconds: Int?
) {

    init {
        require(performedSets >= 1) {
            "a volume exists because sets were performed; a context with none has no volume"
        }
        when (context.dimension) {
            PrescriptionDimension.REP_BASED -> {
                require(repetitions != null && repetitions >= 0) {
                    "a repetition context accumulates repetitions, was $repetitions"
                }
                require(seconds == null) {
                    "a repetition context carries no seconds: adding them to a duration total is the " +
                        "invented conversion §17 forbids"
                }
            }

            PrescriptionDimension.TIME_BASED -> {
                require(seconds != null && seconds >= 0) {
                    "a timed context accumulates seconds, was $seconds"
                }
                require(repetitions == null) {
                    "a timed context carries no repetitions: adding them to a repetition total is the " +
                        "invented conversion §17 forbids"
                }
            }

            else -> throw IllegalArgumentException(
                "a volume is only defined in a dimension with a unit contract (§17): ${context.dimension}"
            )
        }
    }
}
