package com.monkfitness.app.domain.progress

import com.monkfitness.app.domain.common.ProgramId

/**
 * The run of opportunities the user actually took: `COMPLETED` extends it, `MISSED` ends it, and
 * `SUPERSEDED`/`PLANNED` are transparent — a streak is about what happened, and the user was not asked
 * to train either of those. [current] is the run ending at the most recent decided opportunity (`0`
 * when that one was missed); [longest] is the longest the Program has ever had.
 *
 * It is a fact about opportunities, not calendar days, which is why it is reported per Program and the
 * aggregate scope never merges two Programs' sequences into one run — a merged run would be a number
 * neither Program ever had. It needs no calendar and no zone: the run is an ordinal over the
 * opportunities' own order, so a moved clock cannot change it.
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
