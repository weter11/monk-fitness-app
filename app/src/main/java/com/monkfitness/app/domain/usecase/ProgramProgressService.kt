package com.monkfitness.app.domain.usecase

import com.monkfitness.app.data.repository.ProgramRepository
import com.monkfitness.app.data.repository.ProgramScheduleRepository
import com.monkfitness.app.data.repository.WorkoutSessionRepository
import com.monkfitness.app.di.Clock
import com.monkfitness.app.domain.progress.CalendarProgress
import com.monkfitness.app.domain.progress.HistoryItem
import com.monkfitness.app.domain.progress.ProgressCalculator
import com.monkfitness.app.domain.progress.ProgressFacts
import com.monkfitness.app.domain.progress.ProgressScope
import com.monkfitness.app.domain.progress.ProgressWindow
import com.monkfitness.app.domain.progress.TrainingProgress
import java.time.ZoneId

/**
 * The Progress/History layer over the facts the repositories already expose.
 *
 * The values are the domain's ([CalendarProgress], [TrainingProgress], [HistoryItem]), the rows are the
 * repositories', and the calculation is [ProgressCalculator], which is pure. What is left here is the one
 * thing a pure calculator cannot do: decide which facts a scope is, and read them.
 *
 * It **reads and never writes** — no transaction runner, no id generator, no DAO — and it gathers from the
 * repositories that own their aggregates rather than adding a projection: `ProgramProgressRepository` is
 * deliberately not extended, because a second path to the same rows could disagree about what a session
 * is, and the presentation a workout was measured against must come from its snapshot and nowhere else.
 *
 * @param programRepository the Programs an aggregate scope covers. An empty or absent Program is not an
 *   error: an empty history produces zeroes and empty lists of the correct type.
 * @param sessionRepository the attempts of one Program, each assembled from its own snapshot and sets.
 * @param zone the calendar a workout's start date is read in. Explicit, because "which day did I train on"
 *   is a timezone question and a process default would answer it by accident.
 */
class ProgramProgressService(
    private val programRepository: ProgramRepository,
    private val scheduleRepository: ProgramScheduleRepository,
    private val sessionRepository: WorkoutSessionRepository,
    private val clock: Clock,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val calculator: ProgressCalculator = ProgressCalculator(zone)
) {

    /**
     * Calendar Progress for one Program, or for the aggregate.
     *
     * The reads are scoped: a Program scope cannot reach another Program's slots, and the aggregate keeps
     * each entry's own identity. The calculation then refuses facts that are not homogeneous for a
     * Program scope ([ProgressFacts]), so isolation is enforced at both ends.
     */
    suspend fun calendarProgress(scope: ProgressScope): CalendarProgress =
        calculator.calendar(factsFor(scope))

    /**
     * Training Progress for one Program, or for the aggregate, over [window].
     *
     * The default window is relative to today **in [zone]**, read from the injected clock. Measures whose
     * scope is the whole history ignore the window, and [TrainingProgress] documents which is which.
     */
    suspend fun trainingProgress(
        scope: ProgressScope,
        window: ProgressWindow = currentWindow()
    ): TrainingProgress = calculator.training(factsFor(scope), window)

    /**
     * History — the scope's attempts, **newest first**, because that is how history is read.
     *
     * An attempt still running appears with its exposure so far and no duration; a cancelled attempt
     * appears as cancelled with the sets it recorded, because history records what happened and not what
     * counted.
     *
     * @param limit how many of the most recent attempts to return; `null` for all of them.
     * @throws IllegalArgumentException when [limit] is not positive.
     */
    suspend fun history(scope: ProgressScope, limit: Int? = null): List<HistoryItem> {
        require(limit == null || limit > 0) {
            "history is asked for a positive number of attempts, was $limit"
        }
        val newestFirst = calculator.history(factsFor(scope)).asReversed()
        return if (limit == null) newestFirst else newestFirst.take(limit)
    }

    /**
     * The window a caller that does not name one gets: the last [DEFAULT_WINDOW_DAYS] calendar dates,
     * ending on today in [zone].
     *
     * Public so a screen offering "last 4 weeks / last 12 weeks" anchors on the same date, and so a caller
     * computing its own window can compare rather than guess.
     */
    fun currentWindow(): ProgressWindow =
        ProgressWindow.ofLastDays(DEFAULT_WINDOW_DAYS, clock.now().atZone(zone).toLocalDate())

    /**
     * The facts of one scope, read from the repositories that own them.
     *
     * The aggregate is the same two reads over every stored Program, concatenated with their identities
     * intact — that concatenation *is* "All Programs", which is why it needs no entity to store.
     */
    private suspend fun factsFor(scope: ProgressScope): ProgressFacts = when (scope) {
        is ProgressScope.OfProgram -> ProgressFacts(
            scope = scope,
            slots = scheduleRepository.slotsOfProgram(scope.programId),
            sessions = sessionRepository.sessionsOfProgram(scope.programId)
        )

        ProgressScope.AllPrograms -> {
            val programIds = programRepository.programs().map { it.programId }
            ProgressFacts(
                scope = scope,
                slots = programIds.flatMap { programId -> scheduleRepository.slotsOfProgram(programId) },
                sessions = programIds.flatMap { programId -> sessionRepository.sessionsOfProgram(programId) }
            )
        }
    }

    companion object {

        /**
         * How many calendar dates the default window spans: four weeks, the shortest span in which a
         * three-times-a-week program shows a whole number of full weeks.
         *
         * A constant rather than a setting, because a span is a presentation decision and changing it must
         * not change a stored fact.
         */
        const val DEFAULT_WINDOW_DAYS: Int = 28
    }
}
