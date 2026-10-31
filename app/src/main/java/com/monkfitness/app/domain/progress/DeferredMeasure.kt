package com.monkfitness.app.domain.progress

/**
 * A measure Progress is asked to show whose input the target facts do not have, reported as such.
 *
 * The alternative is the defect this file exists to prevent: a measure quietly reported as `0`. A zero is
 * a claim — "you have no PRs", "your training is spread 0% over every focus" — and a screen cannot tell
 * an absent measure from a measured one.
 *
 * Each is a boundary, not a gap: [FOCUS_DISTRIBUTION] belongs to the Focus Planner, and a
 * `ProgramDayType` is a kind of day, not the weighted exposure plan, so converting it into a focus
 * would invent the vocabulary; [FAMILY_DISTRIBUTION] needs family membership as a target fact, which
 * exists only in the legacy catalogue and in adaptive state Progress must not read — mixing the two
 * generations is forbidden; [PROGRAM_PR] needs a comparable context that says what a record is, and
 * the facts carry no progression level or difficulty, so the best set is reported
 * ([ExercisePerformanceSeries.bestRepetitions]) and a PR is not invented.
 */
enum class ProgressMeasure {

    FOCUS_DISTRIBUTION,

    FAMILY_DISTRIBUTION,

    PROGRAM_PR
}

/**
 * One measure reported as not computed, with the reason. The reason travels with it because a screen
 * needs to know it must show nothing rather than a zero, and the owner needs to know which dependency
 * unblocks it. It is a message, not a metric — nothing here is ever summed or compared.
 */
data class DeferredMeasure(val measure: ProgressMeasure, val reason: String) {

    init {
        require(reason.isNotBlank()) { "a deferred measure says why it is deferred" }
    }
}
