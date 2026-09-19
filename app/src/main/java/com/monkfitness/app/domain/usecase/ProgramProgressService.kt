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
 * The Progress/History layer — §30 step 9, over the facts the repositories already expose.
 *
 * ### The shape of the layer
 *
 * The blueprint's layering is `Room entity ⇄ mapper ⇄ domain ⇄ use case ⇄ UI` (§25), and this is the
 * fourth of those. The *values* are the domain's ([CalendarProgress], [TrainingProgress], [HistoryItem]);
 * the *rows* are the repositories'; the *calculation* is [ProgressCalculator], which is pure and knows
 * nothing about storage. What is left for this class is the one thing a pure calculator cannot do: **decide
 * which facts a scope is**, and read them.
 *
 * ```text
 * ProgramProgressService
 *     ├── ProgramRepository           ← the Programs an aggregate scope is over (§21)
 *     ├── ProgramScheduleRepository    ← the opportunities of one Program (§20)
 *     ├── WorkoutSessionRepository     ← the attempts, assembled from their snapshot and sets (§19)
 *     ├── clock: Clock                 ← "today", for the default window and nothing else (§26)
 *     ├── zone: ZoneId                 ← the calendar a workout's start is counted on
 *     └── calculator: ProgressCalculator ← the pure computation (§30 step 9's domain half)
 * ```
 *
 * ### What it deliberately is not
 *
 * This layer **reads and never writes**, and its collaborators are the statement of that: there is no
 * transaction runner, no id generator and no DAO, because nothing here creates, updates or deletes a row —
 * Progress is a view of facts other layers own (§24: *"DAO: persistence only; no business logic"*; §21:
 * All Programs is *"an aggregation view, not an entity"*, so there is nothing to store for it). There is
 * also no scheduler, no policy, no generator and no Focus Planner: every vocabulary a measure would need
 * from a later stage is reported as deferred rather than obtained from one (§30 steps 10–12).
 *
 * ### Why the repositories are read directly
 *
 * §24 says the repositories of the target architecture are the ones that own their aggregates, and each of
 * the three facts this stage needs already has an owner that returns it as a typed domain value:
 * opportunities from `ProgramScheduleRepository`, attempts — with their captured presentation and their
 * confirmed sets — from `WorkoutSessionRepository`, and the Program list from `ProgramRepository`.
 * `ProgramProgressRepository` is deliberately **not** extended by this stage. Adding a projection there
 * would be a second path to the same rows, and the two paths could disagree about what a session is:
 * `WorkoutSessionRepository` is what assembles a session from its own snapshot, and the whole point of
 * §19 is that the presentation a workout was measured against comes from that snapshot and nowhere else.
 * The alternative — reading the rows a second time through new SQL — would be §33's prohibition (*"let
 * Repository expose Room Entity"*, and with it a second reader nobody owns) in a subtler form.
 *
 * ### The one thing "today" is used for
 *
 * [trainingProgress] needs a span for the rate-like measures, and a caller that does not name one gets
 * [currentWindow]: the last [DEFAULT_WINDOW_DAYS] calendar dates, ending on today **in [zone]** — the one
 * place the injected clock is read in this file (§26). The window is part of the result
 * ([TrainingProgress.window]), so what "recently" meant is never implicit, and every other measure is
 * computed from stored facts alone.
 *
 * @param programRepository the Programs the aggregate scope covers. An empty or absent Program is not an
 *   error: §21 requires an empty history to produce zeroes and empty lists of the correct type.
 * @param scheduleRepository the opportunities of one Program, with their statuses and attempts as stored.
 * @param sessionRepository the attempts of one Program, each assembled from its own snapshot and sets —
 *   never from the live plan (§19).
 * @param clock the injected clock, read once per call that needs a default window (§26).
 * @param zone the calendar a workout's actual start date is read in. Explicit, because "which day did I
 *   train on" is a timezone question and a process default answers it by accident.
 * @param calculator the pure computation. It defaults to one over the same [zone], which is the only
 *   dependency it has.
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
     * §21's Calendar Progress for one Program, or for the aggregate.
     *
     * The opportunities are read scoped: a Program scope reads that Program's slots and cannot reach
     * another Program's, and the aggregate reads every Program's and keeps each entry's own program
     * identity. The calculation itself receives facts that are homogeneous for a Program scope and refuses
     * to build them otherwise ([ProgressFacts]), so isolation is enforced at both ends — the read and the
     * value.
     */
    suspend fun calendarProgress(scope: ProgressScope): CalendarProgress =
        calculator.calendar(factsFor(scope))

    /**
     * §21's Training Progress for one Program, or for the aggregate, over [window].
     *
     * The default window is relative to today **in [zone]**, read from the injected clock. Every measure
     * whose scope is the whole history ignores the window, and [TrainingProgress] documents which is which.
     */
    suspend fun trainingProgress(
        scope: ProgressScope,
        window: ProgressWindow = currentWindow()
    ): TrainingProgress = calculator.training(factsFor(scope), window)

    /**
     * §21's history — the scope's attempts, **newest first**, because that is how history is read.
     *
     * `limit` is optional and inclusive: `history(scope, 10)` is the ten most recent attempts, and
     * `history(scope)` is all of them. An attempt still running appears with its exposure so far and no
     * duration (§19); a cancelled attempt appears as cancelled with the sets it recorded (§12), because
     * history is a record of what happened and not of what counted.
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
     * It is public because a screen that wants to offer "the last 4 weeks / the last 12 weeks" needs the
     * same anchor this default uses, and because a caller computing its own window should be able to
     * compare it with the default rather than guess it.
     */
    fun currentWindow(): ProgressWindow =
        ProgressWindow.ofLastDays(DEFAULT_WINDOW_DAYS, clock.now().atZone(zone).toLocalDate())

    /**
     * The facts of one scope, read from the repositories that own them.
     *
     * A Program scope is two reads, each already scoped by Program in SQL. The aggregate is the same reads
     * over every stored Program, concatenated with their own identities intact — that concatenation *is*
     * §21's "All Programs", and it is why the aggregate needs no entity: there is nothing to create, only
     * facts to gather.
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
         * How many calendar dates the default window spans: four weeks.
         *
         * Four weeks is the shortest span in which a three-times-a-week program shows a whole number of
         * full weeks, which is what makes the frequency of a weekly plan readable without arithmetic. It is
         * a constant of this layer rather than a setting, because a span is a presentation decision and
         * changing it must not change any stored fact.
         */
        const val DEFAULT_WINDOW_DAYS: Int = 28
    }
}
