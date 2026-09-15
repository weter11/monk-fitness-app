package com.monkfitness.app.domain.adaptive

/**
 * One exercise a session prescribed, as the adaptive domain sees it — a **pure** representation that
 * carries no data-layer, Room or Android type.
 *
 * The session-history adapter builds these from the workout that was presented and hands them to
 * [SessionObservationMapper]; the data-layer `Exercise` is converted at that boundary and never
 * crosses into `domain.adaptive`. This is the only thing the mapper needs to know about a planned
 * exercise: which exercise it is, how many sets were prescribed, and how much work each set
 * prescribed in the single unit that exercise is measured in.
 *
 * An exercise is prescribed in exactly one unit, and the model keeps them distinct:
 *  * a **repetition** exercise populates [repsPerSet] and leaves [durationSecondsPerSet] at `0`;
 *  * a **timer** exercise populates [durationSecondsPerSet] and leaves [repsPerSet] at `0`. The
 *    placeholder repetition count the exercise library carries for timed holds is not prescribed
 *    repetition work, so the adapter normalizes it to `0` rather than passing it through.
 *
 * @param exerciseId the stable identifier the exercise library and `SetLog` rows share.
 * @param sets the number of prescribed sets, `>= 0`.
 * @param repsPerSet the repetitions one set prescribed — `0` for a timer exercise.
 * @param durationSecondsPerSet the hold seconds one set prescribed — `0` for a repetition exercise.
 * @param isTimerBased whether the exercise is prescribed in time rather than in repetitions.
 */
data class PlannedExercise(
    val exerciseId: String,
    val sets: Int,
    val repsPerSet: Int,
    val durationSecondsPerSet: Int,
    val isTimerBased: Boolean
) {

    init {
        require(exerciseId.isNotBlank()) { "exerciseId must not be blank" }
        require(sets >= 0) { "sets must be >= 0, were $sets" }
        require(repsPerSet >= 0) { "repsPerSet must be >= 0, were $repsPerSet" }
        require(durationSecondsPerSet >= 0) {
            "durationSecondsPerSet must be >= 0, was $durationSecondsPerSet"
        }
        require(repsPerSet == 0 || durationSecondsPerSet == 0) {
            "an exercise is prescribed in repetitions or in time, never both: " +
                "repsPerSet=$repsPerSet durationSecondsPerSet=$durationSecondsPerSet"
        }
    }
}
