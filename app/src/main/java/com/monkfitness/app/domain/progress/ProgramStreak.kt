package com.monkfitness.app.domain.progress

import com.monkfitness.app.domain.common.ProgramId

/**
 * §21's **Program streak**: the run of opportunities the user actually took.
 *
 * The blueprint names the measure and does not define it, so the definition is stated here in full and
 * recorded as an owner decision in the stage document (`docs/PROGRAM_PROGRESS_HISTORY.md`, decision 2).
 * It is built from §20's slot facts alone:
 *
 * ```text
 * COMPLETED   — the opportunity was taken: it extends the run
 * MISSED      — the opportunity passed: it ends the run
 * SUPERSEDED  — not expected of the user: transparent, neither extends nor ends
 * PLANNED     — not decided yet: transparent, because a streak is about what happened
 * ```
 *
 * So [current] is the length of the run of taken opportunities ending at the most recent **decided**
 * opportunity — `0` when that one was missed — and [longest] is the longest run the Program has ever had.
 * A superseded or still-open opportunity between two taken ones does not break the streak, because the
 * user was not asked to train it; a missed one does, because they were.
 *
 * Two properties of this definition are worth stating rather than leaving to be inferred:
 *
 *  * it is a fact about **opportunities**, not about calendar days: the streak of a program that trains
 *    three times a week counts three opportunities a week, and it would be wrong to compare it with a
 *    daily program's, which is precisely why it is reported per Program ([TrainingProgress.streaks]) and
 *    why the aggregate scope does not merge two Programs' slot sequences into one run — a merged run
 *    would be a number neither Program ever had;
 *  * it needs **no calendar and no zone**: opportunities carry planned dates, and the run is an ordinal
 *    over their order, so "today" is not consulted and the result cannot change because a clock moved.
 *
 * @property programId the Program this streak is about.
 * @property current the run of taken opportunities at the end of what has been decided.
 * @property longest the longest such run the Program has had.
 * @property takenOpportunities how many of its opportunities were taken.
 * @property decidedOpportunities how many were decided at all — taken or passed.
 */
data class ProgramStreak(
    val programId: ProgramId,
    val current: Int,
    val longest: Int,
    val takenOpportunities: Int,
    val decidedOpportunities: Int
) {

    init {
        require(current >= 0 && longest >= 0) {
            "a run of opportunities is not negative: current=$current longest=$longest"
        }
        require(takenOpportunities >= 0 && decidedOpportunities >= 0) {
            "counts of opportunities are not negative: taken=$takenOpportunities decided=$decidedOpportunities"
        }
        require(current <= longest) {
            "the run that is still going is one of the runs this Program has had: " +
                "current=$current longest=$longest"
        }
        require(takenOpportunities <= decidedOpportunities) {
            "a taken opportunity is a decided one: taken=$takenOpportunities decided=$decidedOpportunities"
        }
    }
}
