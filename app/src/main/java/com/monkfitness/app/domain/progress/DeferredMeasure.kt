package com.monkfitness.app.domain.progress

/**
 * A §21 measure that this layer **cannot** compute from the target facts, reported as such.
 *
 * Every entry here is a measure the blueprint asks Progress to show in its first implementation and whose
 * input the target runtime does not have. The alternative to naming them is the defect this file exists to
 * prevent: a measure that is quietly reported as `0`. A zero is a *claim* — "you have no PRs", "your
 * training is spread 0% over every focus" — and a screen cannot tell an absent measure from a measured
 * one, so the measure is reported as what it is instead of as nothing.
 *
 * Each one is a boundary rather than a gap, and the stage document records who owns each of them:
 *
 *  * [FOCUS_DISTRIBUTION] belongs to the Focus Planner (§8, §30 step 10). The runtime's slots and
 *    sessions carry no focus: a `ProgramDayType` is `TRAINING` or `REST` — a *kind of day*, not the
 *    weighted exposure plan §8 describes — so converting it into a focus would be inventing the
 *    vocabulary, and "focus distribution" would then be a distribution over nothing;
 *  * [FAMILY_DISTRIBUTION] needs family membership as a target fact. Families currently exist only in the
 *    legacy generator's catalogue and in the Stage-1 adaptive state (`family_progression_state`), which
 *    is a different generation of the architecture; reading Progress out of it would mix the two (§1, §23,
 *    §30 step 15). The dependency is deferred until the target model owns family metadata;
 *  * [PROGRAM_PR] needs a comparable context that says what a record *is*. The target facts record an
 *    exercise, a unit and what was performed — no progression level, no difficulty and no variant
 *    relation — so "the best set of this exercise in this unit" is representable
 *    ([ExercisePerformanceSeries.bestRepetitions]) while a "personal record" in the sense of a *record
 *    against a comparable context* is not, and inventing the missing context is what §17 forbids.
 */
enum class ProgressMeasure {

    /** How exposure was spread over training focuses (§21). Owned by the Focus Planner (§30 step 10). */
    FOCUS_DISTRIBUTION,

    /** How exposure was spread over exercise families (§21). Needs family metadata at the target boundary. */
    FAMILY_DISTRIBUTION,

    /** The Program's personal records (§21). Needs a comparable progression context the facts do not carry. */
    PROGRAM_PR
}

/**
 * One measure reported as not computed, with the reason it cannot be (§21, §12, §17).
 *
 * The reason travels with the measure because the two audiences of this layer need different halves of
 * it: a screen needs to know that it must show nothing rather than a zero, and the owner needs to know
 * which dependency unblocks it. It is a message, not a metric — nothing here is ever summed or compared.
 *
 * @property measure which §21 measure this is.
 * @property reason why it is not computed, in one sentence a screen or a reviewer can act on.
 */
data class DeferredMeasure(val measure: ProgressMeasure, val reason: String) {

    init {
        require(reason.isNotBlank()) { "a deferred measure says why it is deferred" }
    }
}
