package com.monkfitness.app.domain.progress

import com.monkfitness.app.domain.prescription.PrescriptionDimension
import com.monkfitness.app.domain.program.SlotStatus
import java.time.ZoneId

/**
 * The Progress calculations — §30 step 9's pure half.
 *
 * ### What this class is
 *
 * The blueprint's flow for this stage is `Room entity → repository raw facts → pure calculator → use case
 * → UI` (§25). This is the third arrow, and it is **pure in the strict sense**: it holds a zone and
 * nothing else, it reads no database, no repository, no clock and no platform type, and every one of its
 * three entry points is a function of the [ProgressFacts] it is handed. Two runs over the same facts
 * produce the same values, which is why the whole computation is testable on the JVM without a device and
 * without a store.
 *
 * ### The three aggregations, and why they are three
 *
 * They are separate methods returning separate types, and the caller asks for the one it needs:
 *
 * ```text
 * calendar(facts)               → CalendarProgress   what the plan's calendar came to (§21)
 * history(facts)                → List<HistoryItem>  every attempt, chronologically (§19, §21)
 * training(facts, window)       → TrainingProgress   the measures over those facts (§21)
 * ```
 *
 * There is deliberately no `progress(facts)` that returns one object holding all three: §21's calendar
 * and training progress have different semantics (an opportunity that passed is not a workout that scored
 * zero; an attempt is not an opportunity), and a single state type would invite exactly the collapsing the
 * blueprint forbids. [TrainingProgress] composes the *training* measures only, and it derives nothing from
 * the calendar.
 *
 * ### Time, and the one thing "today" is used for
 *
 * The class takes a [ZoneId] and no clock, and that is the timezone contract of this layer:
 *
 *  * the only place an [java.time.Instant] becomes a calendar date is the frequency of a completed
 *    workout, and that conversion happens in **this** zone, explicitly supplied by the caller. The
 *    process's default zone is read once, at construction, by the default value — never implicitly inside
 *    a calculation;
 *  * nothing else here consults a date at all. [calendar] and [history] are functions of stored facts —
 *    planned dates, actual instants — and the streak is an ordinal over the opportunities' own order, so
 *    it needs no calendar and cannot change because a clock moved;
 *  * "today" enters through the *window* the caller builds ([ProgressWindow.ofLastDays]) and nowhere else.
 *    The blueprints's rule is followed literally: the clock is read only where a measure genuinely depends
 *    on the current moment (§26), which here is the default span of a rate — and the window it produced
 *    is part of the answer ([TrainingProgress.window]), so the reader can see what "recently" meant.
 *
 * @param zone the calendar a session's `startedAt` is read in when a measure counts it on a date. It is a
 *   constructor argument rather than a call to `ZoneId.systemDefault()` inside a calculation so that a
 *   test, or a user in another timezone, decides it once.
 */
class ProgressCalculator(private val zone: ZoneId = ZoneId.systemDefault()) {

    /**
     * §21's Calendar Progress: the scope's opportunities, chronologically, with their counts.
     *
     * Every opportunity appears exactly once, with the status the Scheduler gave it — this method decides
     * nothing about what happened to an opportunity, and in particular does not re-classify a still-open
     * one as missed because its date has passed (§20, §33: the timing layer owns that fact). A `MISSED`
     * entry carries no amount of work, because a slot carries none: the absence is the representation
     * (§12).
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
     * §21's history: every attempt of the scope, oldest first.
     *
     * The order is `startedAt` first with the session identity as the tiebreak, so the sequence is a
     * function of the stored facts rather than of the order rows came back in. Only attempts appear: an
     * opportunity nobody attempted has no session, and no empty entry is invented for it — that invention
     * is the same defect as reading a missed opportunity as a workout that scored zero.
     *
     * What each entry exposes is defined by [historyItemOf], which is the only place one is built: the
     * status as stored (a `CANCELLED` attempt stays cancelled and is never counted as a completed
     * workout), the actual timestamps, and the exposure the attempt recorded, whatever its status (§12).
     */
    fun history(facts: ProgressFacts): List<HistoryItem> = facts.sessions
        .sortedWith(compareBy({ it.startedAt }, { it.sessionId.value }))
        .map { session -> historyItemOf(session) }

    /**
     * §21's Training Progress over [window].
     *
     * The rules, each in one line, each with the reason it is the rule:
     *
     *  * **frequency** counts sessions whose status is `COMPLETED` (§19: `CANCELLED` is not a quiet
     *    completion) and whose actual start date — in [zone] — falls inside the window. An `IN_PROGRESS`
     *    attempt is not counted: it is not a finished workout, and it has no date to be counted on beyond
     *    the moment it started.
     *  * **average duration** measures the same set of attempts, from their own `startedAt` and
     *    `finishedAt`. Nothing is measured for an attempt that has not finished, and a cancelled attempt's
     *    elapsed time is not counted as a workout's duration — it stays visible in [history], where it
     *    belongs.
     *  * **comparable series and volumes** are built over the scope's **whole** history, from the confirmed
     *    sets of every attempt, grouped by [ComparableContext] — one exercise in one unit. A plan element
     *    prescribed in a dimension with no unit contract produces neither, and appears in [deferred]
     *    instead of as a zero (§10, §17).
     *  * **exposure survives its session's status.** Every confirmed set is an observation, including the
     *    sets of an attempt that was cancelled afterwards and of an attempt that is still running (§12:
     *    *"partial execution = partial Exposure"*, *"cancelled Session may still contain partial
     *    Exposure"*). The observation carries [PerformanceObservation.sessionStatus] so a reader can filter
     *    on it, while the facts themselves are never rewritten: dropping a cancelled attempt's sets would
     *    be erasing work that happened, and promoting them to a completed workout's would be a lie of the
     *    other direction.
     *  * **streaks** are computed per Program (§21's *Program streak*), so an aggregate scope reports one
     *    run per Program rather than a run over an interleaving no Program ever had.
     *  * **`deferred` is not a zero.** The measures in it are the ones this layer cannot compute, named
     *    with their reason ([DeferredMeasure]) so a screen shows nothing rather than "0".
     *
     * @param window the calendar range the rate-like measures are computed over.
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
     * One context's volume: its sets, and the amount accumulated **in that context's own unit** (§17).
     *
     * The other unit is `null` rather than zero, so no code path exists that could add a repetition total to
     * a duration total, and there is no cross-context sum anywhere: a volume is read per context or not at
     * all.
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
     * The opportunity runs, one per Program in the scope.
     *
     * The three statuses that do not decide a run are treated differently on purpose (see [ProgramStreak]):
     * a `PLANNED` opportunity has not happened and a `SUPERSEDED` one was never expected, so neither
     * extends nor ends a run, while a `MISSED` one ends it. A Program scope reports exactly one streak —
     * its own Program's — even when nothing has been recorded yet, because the scope names it; an aggregate
     * reports one per Program its facts hold.
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
         * The §21 measures this layer reports as deferred, in a fixed order, on every result.
         *
         * They are reported unconditionally rather than only when the facts happen to contain the
         * vocabulary: a measure is deferred because the *model* cannot express it, not because the data is
         * unlucky, and a caller that had to interpret an empty list would be back to guessing whether "no
         * focus distribution" meant *nothing to show* or *nothing that can be shown*.
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
