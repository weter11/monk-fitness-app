package com.monkfitness.app.domain.progress

import com.monkfitness.app.domain.prescription.PrescriptionDimension

/**
 * Volume in one comparable context — the only place it is allowed to exist: one exercise, one unit.
 *
 * The unit is not optional: a repetition context carries [repetitions] and no seconds, a timed context
 * [seconds] and no repetitions. The other side is `null`, not `0`, because a zero is a value that adds,
 * and adding a repetition total to a duration total is the one operation that must never be possible.
 * There is deliberately no `total`, no `amount` and no cross-context sum in or beside this type.
 *
 * Family-level volume is not produced here: the runtime has no family vocabulary of its own (family
 * metadata lives only in the legacy generator and in adaptive state this layer must not read), so that
 * measure is reported as deferred rather than guessed.
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
