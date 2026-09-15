package com.monkfitness.app.domain.adaptive

/**
 * An amount of prescribed or performed work, kept in the units the program actually prescribes.
 *
 * Repetitions and timed holds are different measurements, so this model reports them **side by side**
 * and never adds them together: [reps] and [durationSeconds] are independent channels, and two
 * workloads are equal only when every channel matches. The shape mirrors the existing program
 * statistics convention (`sets` / `reps` / `timerSeconds`).
 *
 * A session-level workload aggregates exercises of both kinds, so both channels are normally
 * populated at that level; a per-exercise [ExerciseResult] populates exactly one of them. Reducing
 * the channels into a single normalized scalar is deliberately NOT done here — that belongs to the
 * policy/signal layer, where it is explicitly defined and unit-tested.
 */
data class Workload(
    val sets: Int = 0,
    val reps: Int = 0,
    val durationSeconds: Int = 0
) {

    init {
        require(sets >= 0 && reps >= 0 && durationSeconds >= 0) {
            "workload amounts must be >= 0, were sets=$sets reps=$reps durationSeconds=$durationSeconds"
        }
    }

    /** True when nothing at all was prescribed or performed in any unit. */
    val isZero: Boolean
        get() = sets == 0 && reps == 0 && durationSeconds == 0
}

/**
 * One program session as a normalized observation: the workout that was presented plus what the
 * session actually observed, in a shape the adaptive domain can consume without touching Room,
 * DataStore, Android or UI types.
 *
 * Identity is the calendar position ([cycleNumber], [programDay]) — the same day of the same cycle
 * is the same session opportunity. The stamps say when the session ran (epoch milliseconds, as the
 * rest of the app stores them): `NOT_STARTED` has neither stamp, a session that started has
 * [startedAt], and [finishedAt] is set once the session stopped being active — either because the
 * workout was completed or because it was abandoned, which is why a `PARTIAL` observation may carry
 * one.
 *
 * [plannedWork] and [actualWork] are session aggregates across exercises, in the units of [Workload];
 * [exerciseResults] holds the per-exercise detail they are aggregated from, in session order. The
 * counts [plannedExercises] / [completedExercises] describe how many exercises were planned and how
 * many observed any work at all.
 *
 * The outcome and the observed amounts are held consistent at construction:
 *
 *  * `NOT_STARTED` has no stamps, no completed exercises and zero actual work;
 *  * `PARTIAL` has a start stamp and actual work greater than zero;
 *  * `COMPLETED` has both stamps, and may still be far below full exposure.
 *
 * There is deliberately no abandonment field: at this layer an abandoned workout is simply a
 * `PARTIAL` observation, and the evidence that separates it from a session still in progress is
 * already in the stamps — a `PARTIAL` observation that carries [finishedAt] was ended without
 * satisfying the completion condition, while one without it has not ended yet. Turning that into a
 * recovery-risk or adherence signal belongs to the policy layer that consumes these observations.
 */
data class SessionObservation(
    val cycleNumber: Int,
    val programDay: Int,
    val startedAt: Long?,
    val finishedAt: Long?,
    val outcome: SessionOutcome,
    val plannedExercises: Int,
    val completedExercises: Int,
    val plannedWork: Workload,
    val actualWork: Workload,
    val exerciseResults: List<ExerciseResult> = emptyList()
) {

    init {
        require(cycleNumber >= 1) { "cycleNumber must be >= 1, was $cycleNumber" }
        require(programDay >= 1) { "programDay must be >= 1, was $programDay" }
        require(plannedExercises >= 0) { "plannedExercises must be >= 0, was $plannedExercises" }
        require(completedExercises in 0..plannedExercises) {
            "completedExercises must be within 0..plannedExercises ($plannedExercises), was $completedExercises"
        }

        when (outcome) {
            SessionOutcome.NOT_STARTED -> {
                require(startedAt == null && finishedAt == null) {
                    "a session that never started has no stamps, was startedAt=$startedAt finishedAt=$finishedAt"
                }
                require(actualWork.isZero) {
                    "NOT_STARTED requires zero actual work, was $actualWork"
                }
                require(completedExercises == 0) {
                    "NOT_STARTED requires zero completed exercises, was $completedExercises"
                }
            }

            SessionOutcome.PARTIAL -> {
                require(startedAt != null) { "PARTIAL requires the moment the session started" }
                require(!actualWork.isZero) {
                    "PARTIAL requires actual work greater than zero, was $actualWork"
                }
            }

            SessionOutcome.COMPLETED -> {
                require(startedAt != null) { "COMPLETED requires the moment the session started" }
                require(finishedAt != null) { "COMPLETED requires the moment the session finished" }
            }
        }
    }
}