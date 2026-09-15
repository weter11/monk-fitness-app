package com.monkfitness.app.domain.adaptive

/**
 * Turns the workout a session was built from plus the rows that session persisted into a pure
 * [SessionObservation] — the only place that decides which stored value is trusted for which number.
 *
 * This is a pure function of its inputs with no Android, Room, DataStore or coroutine dependency, so
 * the source-of-truth mapping it encodes is unit-testable on the JVM exactly as production runs it.
 *
 * ## Dependency direction
 *
 * The mapper takes a [PlannedExercise] — a pure domain representation — and not the data-layer
 * `Exercise`. The data layer knows the exercise library; the adaptive domain knows what was
 * prescribed and what was observed, and the two never meet here.
 *
 * ## Source of truth
 *
 *  * **the plan** — [PlannedExercise]s the presented workout prescribed. The caller supplies the plan
 *    it can prove for that historical day (the generator's deterministic output for that day); this
 *    mapper never regenerates, filters or re-orders it, so a later library or configuration change
 *    cannot silently rewrite what an old session prescribed.
 *  * **day-level completion** — a `UserProgress`-equivalent row whose completion flag is set. That
 *    flag, and only that flag, establishes [SessionOutcome.COMPLETED].
 *  * **per-set work** — one confirmed-set row per performed set. Its `repsCompleted` /
 *    `durationSeconds` ARE the observed amounts; a set with no row was not performed.
 *
 * ## What this mapper refuses to do
 *
 *  * infer that a set was performed because the day is completed — completion and performance are
 *    facts from different sources, so a completed day may report `completedSets < plannedSets` and
 *    an exposure well below `1.0`;
 *  * promote a configured target to completed work — for repetition exercises `completedReps` comes
 *    only from the set rows;
 *  * reduce repetitions and seconds to one scalar — the two channels are aggregated side by side
 *    into [Workload], never added;
 *  * fabricate a timestamp — `startedAt` is the earliest confirmed set and `finishedAt` is the
 *    day-level completion stamp, and when persistence has neither, the stamp is `null`;
 *  * discard a persisted row — a row is kept unless a higher-level caller filters it. The write
 *    path does not prove timestamp uniqueness, so two confirmed sets sharing a millisecond are two
 *    performed sets, not a duplicated one. A cap keeps their aggregate inside the plan.
 */
internal object SessionObservationMapper {

    /**
     * @param cycleNumber the program cycle the session belongs to (1-based).
     * @param programDay the program day within the cycle (1..56).
     * @param plannedExercises the exercises the presented workout prescribed, in workout order.
     * @param sessionDate the calendar date the session ran, as the set rows record it.
     * @param setLogs the confirmed-set rows attributed to this session, any order (sorted here).
     * @param isCompleted whether the day-level completion source marks this workout completed.
     * @param completedAt the completion stamp, if the day-level source records one.
     */
    fun toObservation(
        cycleNumber: Int,
        programDay: Int,
        plannedExercises: List<PlannedExercise>,
        sessionDate: String,
        setLogs: List<SessionSetLog>,
        isCompleted: Boolean,
        completedAt: Long?
    ): SessionObservation {
        val exerciseResults = plannedExercises.map { exercise ->
            exerciseResult(exercise, setLogs.forExercise(exercise, sessionDate))
        }

        val plannedWork = exerciseResults.map { it.plannedWorkload() }.foldWorkloads()
        val actualWork = exerciseResults.map { it.actualWorkload() }.foldWorkloads()
        val completedExercises = exerciseResults.count { it.completedSets > 0 }

        val startedAt = setLogs.minOfOrNull { it.timestamp }
        val finishedAt = if (isCompleted) completedAt else null

        return SessionObservation(
            cycleNumber = cycleNumber,
            programDay = programDay,
            startedAt = startedAt,
            finishedAt = finishedAt,
            outcome = outcome(isCompleted, startedAt, actualWork),
            plannedExercises = plannedExercises.size,
            completedExercises = completedExercises,
            plannedWork = plannedWork,
            actualWork = actualWork,
            exerciseResults = exerciseResults
        )
    }

    /** Outcome from the two sources persistence actually exposes, in the order the contract pins. */
    private fun outcome(
        isCompleted: Boolean,
        startedAt: Long?,
        actualWork: Workload
    ): SessionOutcome = when {
        isCompleted -> SessionOutcome.COMPLETED
        startedAt == null || actualWork.isZero -> SessionOutcome.NOT_STARTED
        else -> SessionOutcome.PARTIAL
    }

    /**
     * The confirmed-set rows for one planned exercise.
     *
     * Rows are matched by exercise id AND by the session's date, so a row of a DIFFERENT exercise or
     * a different session date is never counted as this exercise's work. They are then sorted by
     * timestamp for a stable order, but never collapsed: the write path does not prove that a
     * millisecond identifies at most one set, so two rows sharing a timestamp are two persisted
     * confirmed sets and both are kept.
     */
    private fun List<SessionSetLog>.forExercise(
        exercise: PlannedExercise,
        sessionDate: String
    ): List<SessionSetLog> = asSequence()
        .filter { it.exerciseId == exercise.exerciseId && it.sessionDate == sessionDate }
        .sortedBy { it.timestamp }
        .toList()

    /**
     * One exercise's plan vs observed work, in the unit that exercise is prescribed in.
     *
     * Planned amounts are session totals — the prescribed per-set amount times the prescribed set
     * count. Observed amounts are the sum over the confirmed-set rows, clamped to the plan: a
     * repetition exercise accumulates reps and reports `0` seconds, and a timer exercise accumulates
     * elapsed seconds and reports `0` reps, because the session records exactly one of the two and
     * the other channel carries no work. Over-completion is clamped rather than reported, so a
     * duplicated row cannot make actual work exceed the plan.
     */
    private fun exerciseResult(
        exercise: PlannedExercise,
        rows: List<SessionSetLog>
    ): ExerciseResult {
        val plannedSets = exercise.sets.coerceAtLeast(0)
        val completedSets = rows.size.coerceAtMost(plannedSets)

        val isTimerBased = exercise.isTimerBased
        val plannedReps = if (isTimerBased) 0 else exercise.repsPerSet * plannedSets
        val plannedDuration = if (isTimerBased) exercise.durationSecondsPerSet * plannedSets else 0

        val completedReps = if (isTimerBased) {
            0
        } else {
            rows.sumOf { it.repsCompleted.coerceAtLeast(0) }.coerceAtMost(plannedReps)
        }
        val completedDuration = if (isTimerBased) {
            rows.sumOf { it.durationSeconds.coerceAtLeast(0) }.coerceAtMost(plannedDuration)
        } else {
            0
        }

        return ExerciseResult(
            exerciseId = exercise.exerciseId,
            plannedSets = plannedSets,
            completedSets = completedSets,
            plannedReps = plannedReps,
            completedReps = completedReps,
            plannedDurationSeconds = plannedDuration,
            completedDurationSeconds = completedDuration
        )
    }

    /** The planned workload one exercise prescribes: its sets, and its one populated unit channel. */
    private fun ExerciseResult.plannedWorkload(): Workload =
        Workload(sets = plannedSets, reps = plannedReps, durationSeconds = plannedDurationSeconds)

    /** The observed workload one exercise accumulated: its performed sets, in the same channels. */
    private fun ExerciseResult.actualWorkload(): Workload =
        Workload(sets = completedSets, reps = completedReps, durationSeconds = completedDurationSeconds)

    /** Workloads add only within a channel; no repetition is ever added to a second. */
    private operator fun Workload.plus(other: Workload): Workload = Workload(
        sets = sets + other.sets,
        reps = reps + other.reps,
        durationSeconds = durationSeconds + other.durationSeconds
    )

    private fun List<Workload>.foldWorkloads(): Workload =
        fold(Workload()) { acc, workload -> acc + workload }
}
