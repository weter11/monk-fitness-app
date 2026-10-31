package com.monkfitness.app.domain.progress

import com.monkfitness.app.domain.prescription.PrescriptionDimension
import com.monkfitness.app.domain.program.SlotStatus
import java.time.ZoneId

/**
 * The Progress calculations — the pure half of the stage: holds a zone and nothing else, reads no
 * store, no clock and no platform type, so two runs over the same [ProgressFacts] produce the same
 * values and the whole computation is JVM-testable.
 *
 * Three entry points, kept separate on purpose: calendar and training progress have different
 * semantics (an opportunity that passed is not a workout that scored zero; an attempt is not an
 * opportunity), so there is no single `progress(facts)` that would invite collapsing them.
 *
 * Time: the only place an [java.time.Instant] becomes a date is frequency, in the zone supplied here —
 * a constructor argument rather than a `systemDefault()` call inside a calculation, so a test or a
 * user in another timezone decides it once. Nothing else consults a date, and "today" enters only
 * through the [ProgressWindow] the caller builds.
 */
class ProgressCalculator(private val zone: ZoneId = ZoneId.systemDefault()) {

    /**
     * The scope's opportunities, chronologically. Every opportunity appears exactly once with the status
     * the Scheduler gave it: nothing is re-classified here, and a still-open slot whose date passed is
     * not turned into `MISSED` because the timing layer owns that fact. A `MISSED` entry carries no
     * amount of work — the absence is the representation.
     */
    fun calendar(facts: ProgressFacts): CalendarProgress = CalendarProgress(
        scope = facts.scope,
        entries = facts.slots
            .sortedWith(compareBy({ it.plannedFor }, { it.slotId.value }))
            .map { slot ->
                CalendarSlotEntry(
                    plannedFor = slot.plannedFor,
                    slotId = slot.slotId,
                    programId = slot.programId,
                    programDayId = slot.programDayId,
                    status = slot.status,
                    attempts = slot.attempts
                )
            }
    )

    /**
     * Every attempt of the scope, oldest first, ordered by `startedAt` with the session id as tiebreak so
     * the sequence is a function of the facts, not of the order rows came back in. Only attempts appear:
     * an opportunity nobody attempted has no session, and no empty entry is invented for it.
     *
     * Each entry reports the status as stored — a `CANCELLED` attempt stays cancelled and is never
     * counted as a completed workout — plus the actual timestamps and the exposure it recorded.
     */
    fun history(facts: ProgressFacts): List<HistoryItem> = facts.sessions
        .sortedWith(compareBy({ it.startedAt }, { it.sessionId.value }))
        .map { session -> historyItemOf(session) }

    /**
     * Training Progress over [window]:
     *
     *  * frequency counts `COMPLETED` sessions whose actual start date — in [zone] — falls inside the
     *    window; `CANCELLED` is not a quiet completion, and an `IN_PROGRESS` attempt is not counted
     *    because it is not a finished workout.
     *  * average duration measures that same set, from each attempt's own `startedAt`/`finishedAt`; a
     *    cancelled attempt's elapsed time is not a workout duration and stays visible in [history].
     *  * series and volumes are built over the scope's whole history, grouped by [ComparableContext] —
     *    one exercise in one unit. A dimension with no unit contract produces neither and appears in
     *    [deferred] instead of as a zero.
     *  * exposure survives its session's status: every confirmed set is an observation, including the
     *    sets of a later-cancelled or still-running attempt, carrying
     *    [PerformanceObservation.sessionStatus] so a reader can filter. Dropping them would erase work
     *    that happened.
     *  * streaks are per Program, so an aggregate scope reports one run per Program rather than a run
     *    over an interleaving no Program ever had.
     *  * `deferred` is not a zero: those measures cannot be computed by this layer and are named with
     *    their reason ([DeferredMeasure]) so a screen shows nothing rather than "0".
     */
    fun training(facts: ProgressFacts, window: ProgressWindow): TrainingProgress {
        val completedInWindow = facts.sessions
            .filter { it.status.isCompleted }
            .map { session -> session to session.startedAt.atZone(zone).toLocalDate() }
            .filter { (_, date) -> window.contains(date) }

        val durations = completedInWindow.mapNotNull { (session, _) -> durationOf(session) }

        val observations = facts.sessions.flatMap { session ->
            session.exercises.flatMap { occurrence ->
                if (!occurrence.prescription.dimension.hasComparableUnit) {
                    emptyList()
                } else {
                    val context = ComparableContext(occurrence.exerciseId, occurrence.prescription.dimension)
                    occurrence.results.map { set ->
                        context to PerformanceObservation(
                            programId = session.programId,
                            sessionId = session.sessionId,
                            sessionStatus = session.status,
                            setIndex = set.setIndex,
                            performedAt = set.performedAt,
                            repetitions = set.completedReps,
                            seconds = set.durationSeconds
                        )
                    }
                }
            }
        }

        val series = observations.groupBy({ (context, _) -> context }, { (_, observation) -> observation })
            .map { (context, own) ->
                ExercisePerformanceSeries(context, own.sortedWith(PerformanceObservation.ORDER))
            }
            .sortedWith(TrainingProgress.ORDER)

        return TrainingProgress(
            scope = facts.scope,
            window = window,
            frequency = TrainingFrequency(
                window = window,
                completedSessions = completedInWindow.size,
                trainingDays = completedInWindow.map { (_, date) -> date }.toSet().size
            ),
            averageSessionDuration = AverageSessionDuration(
                measuredSessions = durations.size,
                totalSeconds = durations.sumOf { it.seconds }
            ),
            series = series,
            volumes = series.map { own -> volumeOf(own) }.sortedWith(TrainingProgress.VOLUME_ORDER),
            streaks = streaks(facts),
            deferred = DEFERRED_MEASURES
        )
    }

    /**
     * One context's volume, accumulated in that context's own unit. The other unit is `null` rather than
     * zero, so no path could add a repetition total to a duration total; there is no cross-context sum
     * anywhere — a volume is read per context or not at all.
     */
    private fun volumeOf(series: ExercisePerformanceSeries): ContextVolume = ContextVolume(
        context = series.context,
        performedSets = series.performedSets,
        repetitions = when (series.context.dimension) {
            PrescriptionDimension.REP_BASED -> series.observations.sumOf { it.repetitions }
            else -> null
        },
        seconds = when (series.context.dimension) {
            PrescriptionDimension.TIME_BASED -> series.observations.sumOf { it.seconds }
            else -> null
        }
    )

    /**
     * One run per Program in the scope. A `PLANNED` opportunity has not happened and a `SUPERSEDED` one
     * was never expected, so neither extends nor ends a run, while a `MISSED` one ends it. A Program
     * scope reports exactly one streak — its own — even when nothing was recorded, because the scope
     * names it; an aggregate reports one per Program its facts hold.
     */
    private fun streaks(facts: ProgressFacts): List<ProgramStreak> {
        val programIds = when (val scope = facts.scope) {
            is ProgressScope.OfProgram -> listOf(scope.programId)
            ProgressScope.AllPrograms -> facts.slots.map { it.programId }.distinct().sortedBy { it.value }
        }

        return programIds.map { programId ->
            val slots = facts.slots
                .filter { it.programId == programId }
                .sortedWith(compareBy({ it.plannedFor }, { it.slotId.value }))

            var running = 0
            var longest = 0
            var taken = 0
            var decided = 0

            slots.forEach { slot ->
                when (slot.status) {
                    SlotStatus.COMPLETED -> {
                        running += 1
                        taken += 1
                        decided += 1
                        if (running > longest) longest = running
                    }

                    SlotStatus.MISSED -> {
                        running = 0
                        decided += 1
                    }

                    SlotStatus.PLANNED, SlotStatus.SUPERSEDED -> Unit
                }
            }

            ProgramStreak(
                programId = programId,
                current = running,
                longest = longest,
                takenOpportunities = taken,
                decidedOpportunities = decided
            )
        }
    }

    companion object {

        /**
         * The measures this layer reports as deferred, in a fixed order, on every result — unconditionally,
         * because a measure is deferred by the model, not by unlucky data. A caller interpreting an empty
         * list would be back to guessing whether "no focus distribution" meant nothing to show or
         * nothing that can be shown.
         */
        val DEFERRED_MEASURES: List<DeferredMeasure> = listOf(
            DeferredMeasure(
                ProgressMeasure.FOCUS_DISTRIBUTION,
                "the target runtime records no focus for a slot or a session; the weighted exposure plan " +
                    "of §8 belongs to the Focus Planner (§30 step 10), and a ProgramDayType is the kind " +
                    "of day, not a focus"
            ),
            DeferredMeasure(
                ProgressMeasure.FAMILY_DISTRIBUTION,
                "family membership is not a target fact: it exists in the legacy generator's catalogue " +
                    "and in the Stage-1 adaptive state only, and Progress must not read across the two " +
                    "generations (§23, §30 step 15)"
            ),
            DeferredMeasure(
                ProgressMeasure.PROGRAM_PR,
                "a record needs a comparable context to be a record against; the facts record the " +
                    "exercise, the unit and what was performed, and no progression level, difficulty or " +
                    "variant relation — so the best set of a context is reported " +
                    "(ExercisePerformanceSeries.bestRepetitions) and a Program PR is not invented (§17)"
            )
        )
    }
}
