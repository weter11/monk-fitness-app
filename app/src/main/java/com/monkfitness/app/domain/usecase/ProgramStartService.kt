package com.monkfitness.app.domain.usecase

import com.monkfitness.app.data.repository.ProgramScheduleRepository
import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.program.CompositionSelection
import com.monkfitness.app.domain.program.Program
import com.monkfitness.app.domain.program.ProgramOperationRefusal
import com.monkfitness.app.domain.program.ProgramOperationResult
import com.monkfitness.app.domain.program.target.TargetScheduleWindow
import java.time.ZoneId

/**
 * §30 step 21 — **the first controlled production invocation of the target scheduling contour**, and
 * the one application operation the UI is given for *Start*.
 *
 * ```text
 * ProgramsController.start(programId)
 *     ↓  ProgramStartService.start
 * ProgramLifecycleService.startProgram(programId)      → refused | failed | the started Program
 *     ↓  the started Program's factual actualStartDate
 *     ↓  TargetScheduleRunContext — window, selection, sources, asOf, pauses (all stated below)
 * TargetScheduleProductionConsumer.run(programId, context)
 * ```
 *
 * ### Why this is the boundary, and not the controller
 *
 * The five values a target run needs that storage does not state are five *policies*. A controller,
 * a screen or a view model that built any of them would be making a scheduling decision in the UI
 * layer (§16, §33); a consumer that defaulted any of them would be a second policy wearing a
 * consumer's clothes. So the UI sees exactly one operation and the policies live here, next to the
 * lifecycle transition they are derived from — never inside `ProgramLifecycleService`, whose whole
 * subject is `NOT_STARTED → RUNNING ↕ PAUSED → COMPLETED` and which must not learn about windows,
 * compositions or resolved sources.
 *
 * ### The controlled lifecycle point
 *
 * `startProgram()` is the only transition that means *"the Program is now actually started and
 * usable"*, and it is the only one that produces a **factual** start date. This stage therefore
 * invokes target scheduling from that one point and from no other: not from a screen, not from the
 * save boundary, not from the import boundary, not from the session runtime, and not from the
 * legacy planning path.
 *
 * ### The five values, stated rather than inferred
 *
 * | value | this stage's production policy | why this one |
 * | --- | --- | --- |
 * | `asOf` | the started `Program.actualStartDate`, read in [zone] | the same factual moment the transition just recorded, not a second `now()` read milliseconds later. A pass whose as-of date is not the start date is classifying a Program against a moment that never existed. |
 * | `window` | `TargetScheduleWindow(asOf, asOf + 29)` — [WINDOW_DAYS] inclusive | a bounded, named **target** horizon. It is not `ScheduleWindow`, not `PLANNING_HORIZON_DAYS`, not `plannedStartDate`, not `revision.duration` and not the legacy `ScheduleHorizon`: those are the other generation's policy, and a compatibility alias for them would mean the target window is decided by a legacy constant. |
 * | `selection` | `CompositionSelection()` — empty | no implicit cross-rule composition. Deriving the grouping from a rule count, a day name, a legacy schedule or a workout identity would be a second composition policy. |
 * | `sources` | `emptyMap()` | authored definitions and *resolved* sources are different facts. A `DerivedExcluding` rule whose source this pass does not state is refused by the resolver, in the resolver's own words; that refusal is the correct behaviour to surface, and manufacturing a source for it is the one thing this stage must not do. |
 * | `pauses` | [TargetSchedulePauseAdapter] over the persisted intervals, in [zone] | the conversion exists once, here, rather than in a caller. An open interval has no honest conversion and is refused rather than given an invented end. |
 *
 * ### What this is not
 *
 * This is **not** a cutover. `ProgramScheduler` remains the production scheduler, `SlotPlanner` and
 * `ScheduleCalendar` are untouched, the legacy slots are never read, written or reconciled here, and
 * nothing converts a legacy slot into a target occurrence or the other way round. A target pass
 * creates and updates target-owned persistence only.
 *
 * ### Transaction boundary
 *
 * `startProgram()` and the target pass **do not share one transaction**. The lifecycle transition is
 * written by `ProgramRepository.updateProgram` and the target rows by the target persister inside
 * `TargetScheduleOrchestrator`; the composition root's transaction runner is held by the
 * repositories, and no abstraction here spans both. Rather than invent a transaction layer for one
 * stage, the ordering is stated and the failure is surfaced: the start lands first, the target pass
 * runs second, and a target refusal is reported in its own result case. Making the two atomic
 * across those boundaries is a separate decision, recorded as a gap in the stage document.
 */
class ProgramStartService(
    private val lifecycle: ProgramLifecycleService,
    private val consumer: TargetScheduleProductionConsumer,
    private val scheduleRepository: ProgramScheduleRepository,
    private val zone: ZoneId
) {

    /**
     * Starts [programId] and, only if the start succeeded, runs one target scheduling pass against
     * the context this stage's policy states.
     *
     * The ordering is the contract: the target pass is invoked **after** a successful
     * `startProgram()` and **never** after a refusal or a failure. A Program the lifecycle policy
     * refuses to start never reaches the target contour, so a refused start cannot leave a target
     * row behind.
     *
     * A typed refusal raised by the target stages themselves — the resolver refusing a derived rule
     * whose resolved source was not stated, the input boundary refusing a duplicate rule identity,
     * the presenter refusing a missing plan-day binding — propagates unchanged and untranslated. It
     * is never caught here and turned into a success, and it is never reported as a lifecycle
     * refusal: the Program really did start, and saying otherwise would be a lie about the user's
     * data.
     *
     * @return [ProgramStartResult.Started] when both halves ran, a distinct case for a start the
     *   lifecycle refused, a distinct case for a start that failed, and a distinct case for a start
     *   that succeeded while the target pass was explicitly refused.
     */
    suspend fun start(programId: ProgramId): ProgramStartResult {
        val started = when (val outcome = lifecycle.startProgram(programId)) {
            is ProgramOperationResult.Success -> outcome.value
            is ProgramOperationResult.Refused ->
                return ProgramStartResult.StartRefused(outcome.programId, outcome.reason)
            is ProgramOperationResult.Failure -> return ProgramStartResult.StartFailed(outcome.cause)
        }
        // The as-of date is the *same* factual moment the transition just recorded, read in the
        // calendar this operation is defined against — not a second `now()` read after the fact.
        val asOf = started.actualStartDate?.atZone(zone)?.toLocalDate()
            ?: return ProgramStartResult.StartFailed(
                IllegalStateException(
                    "a Program reported as started states no factual start date to schedule against"
                )
            )
        val context = TargetScheduleRunContext(
            window = TargetScheduleWindow(asOf, asOf.plusDays(WINDOW_DAYS - 1)),
            selection = CompositionSelection(),
            sources = emptyMap(),
            asOf = asOf,
            pauses = TargetSchedulePauseAdapter.windowsOf(
                scheduleRepository.pausesOfProgram(programId),
                zone
            )
        )
        return when (val run = consumer.run(programId, context)) {
            is TargetScheduleRunResult.Scheduled -> ProgramStartResult.Started(started, run)
            is TargetScheduleRunResult.ProgramNotFound,
            is TargetScheduleRunResult.CurrentRevisionMissing,
            is TargetScheduleRunResult.SourceMissing,
            is TargetScheduleRunResult.SourceMalformed ->
                ProgramStartResult.TargetSchedulingRefused(started, run)
        }
    }

    companion object {

        /**
         * How many calendar days one controlled invocation plans over, **inclusive of the first
         * day**. `asOf .. asOf + (WINDOW_DAYS - 1)` is therefore exactly thirty dates.
         *
         * This is target scheduling's own horizon. It is deliberately not a second copy of the
         * legacy `ScheduleHorizon` and it does not read `PLANNING_HORIZON_DAYS`: a target window
         * that moved because a legacy constant moved would not be a target policy at all.
         */
        const val WINDOW_DAYS: Long = 30
    }
}

/**
 * What one application-level *Start* did, with its two halves kept apart.
 *
 * §28's vocabulary is unchanged: a lifecycle refusal is still a `ProgramOperationRefusal` and a
 * lifecycle failure is still a `Throwable`. What is new is that the operation is now a
 * **composition**, and a composition has an outcome the composed parts do not: the Program started
 * *and* the target pass did not run. Collapsing that into the start's own success would report a
 * scheduled Program when nothing about it was ever planned, so it is its own case here.
 */
sealed interface ProgramStartResult {

    /**
     * The Program started and the target pass ran against it.
     *
     * @property program the started Program, carrying the factual `actualStartDate` the pass was
     *   planned against.
     * @property targetScheduling what the target pass actually did, unchanged.
     */
    data class Started(
        val program: Program,
        val targetScheduling: TargetScheduleRunResult.Scheduled
    ) : ProgramStartResult

    /**
     * The lifecycle refused the start, so the target pass was never invoked.
     *
     * This is §28's `Refused` in the start operation's own clothing: the Program is still
     * `NOT_STARTED` and no target row exists.
     */
    data class StartRefused(
        val programId: ProgramId,
        val reason: ProgramOperationRefusal
    ) : ProgramStartResult

    /**
     * The start itself failed — storage, or a Program that reports itself started without the
     * factual start date the pass would have to be planned against. The target pass was not
     * invoked.
     */
    data class StartFailed(val cause: Throwable) : ProgramStartResult

    /**
     * The Program started and the target pass **explicitly refused**: the current revision states no
     * target source, or states one that does not read, or the Program's own pointers are
     * inconsistent.
     *
     * @property targetScheduling the refusal, exactly as the target boundary reported it. It is
     *   carried rather than translated, so a caller can still say *which* of the typed absences it
     *   was.
     */
    data class TargetSchedulingRefused(
        val program: Program,
        val targetScheduling: TargetScheduleRunResult
    ) : ProgramStartResult
}
