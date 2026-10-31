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
 * Start: the lifecycle transition, and only after it succeeded, one target scheduling pass.
 *
 * The policies a target run needs are stated here rather than derived by a screen, a consumer or
 * `ProgramLifecycleService`, whose whole subject is `NOT_STARTED -> RUNNING <-> PAUSED -> COMPLETED`
 * and which must not learn about windows, compositions or resolved sources.
 *
 * The start and the target pass do not share a transaction: the start lands first and a target
 * refusal is reported in its own result case rather than rolled back.
 */
class ProgramStartService(
    private val lifecycle: ProgramLifecycleService,
    private val consumer: TargetScheduleProductionConsumer,
    private val scheduleRepository: ProgramScheduleRepository,
    private val zone: ZoneId
) {

    /**
     * Runs one target pass against the context this class's policies state, and only when the start
     * succeeded.
     *
     * Ordering is the contract: a refused or failed start never reaches the target contour, so it can
     * leave no target row behind. A target-stage refusal propagates untranslated — the Program really
     * did start, and reporting it as a lifecycle refusal would misstate the user's data.
     */
    suspend fun start(programId: ProgramId): ProgramStartResult {
        val started = when (val outcome = lifecycle.startProgram(programId)) {
            is ProgramOperationResult.Success -> outcome.value
            is ProgramOperationResult.Refused ->
                return ProgramStartResult.StartRefused(outcome.programId, outcome.reason)
            is ProgramOperationResult.Failure -> return ProgramStartResult.StartFailed(outcome.cause)
        }
        // The as-of date is the *same* factual moment the transition just recorded, not a second
        // `now()` read after the fact.
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
         * Days one invocation plans over, inclusive: `asOf .. asOf + (WINDOW_DAYS - 1)` is thirty
         * dates. Deliberately not a copy of the legacy `ScheduleHorizon` — a target window that moved
         * because a legacy constant moved would not be a target policy.
         */
        const val WINDOW_DAYS: Long = 30
    }
}

/**
 * What one application-level *Start* did, with its two halves kept apart: a Program that started with
 * a target pass that did not run is not a success, and collapsing it into one would report a scheduled
 * Program when nothing about it was planned.
 */
sealed interface ProgramStartResult {

    data class Started(
        val program: Program,
        val targetScheduling: TargetScheduleRunResult.Scheduled
    ) : ProgramStartResult

    /** The lifecycle refused; the Program is still `NOT_STARTED` and no target row exists. */
    data class StartRefused(
        val programId: ProgramId,
        val reason: ProgramOperationRefusal
    ) : ProgramStartResult

    /**
     * The start itself failed: storage, or a Program that reports itself started without the factual
     * start date the pass would have to be planned against.
     */
    data class StartFailed(val cause: Throwable) : ProgramStartResult

    /**
     * The Program started and the target pass explicitly refused. The refusal is carried rather than
     * translated so a caller can still say *which* of the typed absences it was.
     */
    data class TargetSchedulingRefused(
        val program: Program,
        val targetScheduling: TargetScheduleRunResult
    ) : ProgramStartResult
}
