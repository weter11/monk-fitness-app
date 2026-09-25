package com.monkfitness.app.domain.usecase

import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.common.RevisionId
import com.monkfitness.app.domain.program.CompositionSelection
import com.monkfitness.app.domain.program.ExistingOccurrence
import com.monkfitness.app.domain.program.ProgramPauseWindow
import com.monkfitness.app.domain.program.target.ResolvedScheduleSource
import com.monkfitness.app.domain.program.target.TargetPlan
import com.monkfitness.app.domain.program.target.TargetPlanner
import com.monkfitness.app.domain.program.target.TargetProgramDayBinding
import com.monkfitness.app.domain.program.target.TargetSchedule
import com.monkfitness.app.domain.program.target.TargetScheduleDecision
import com.monkfitness.app.domain.program.target.TargetSchedulePolicy
import com.monkfitness.app.domain.program.target.TargetScheduleWindow
import java.time.LocalDate

/**
 * Every value one orchestration pass needs, supplied by the caller.
 *
 * The request is a pure value: it carries no clock, no repository, and no identity
 * generator, so the same request always describes the same semantic pass.
 */
data class TargetScheduleOrchestrationRequest(
    val programId: ProgramId,
    val revisionId: RevisionId,
    val schedules: List<TargetSchedule>,
    val window: TargetScheduleWindow,
    val selection: CompositionSelection,
    val existing: List<ExistingOccurrence>,
    val sources: Map<String, ResolvedScheduleSource>,
    val asOf: LocalDate,
    val pauses: List<ProgramPauseWindow>,
    val programDayBindings: List<TargetProgramDayBinding>
)

/**
 * The three semantic boundaries of one pass, each kept whole.
 *
 * `targetPlan` is what semantic target computation produced, `decision` is what temporal
 * classification produced, and `applicationResult` is what presentation and persistence
 * produced. None of them is flattened into the next.
 */
data class TargetScheduleOrchestrationResult(
    val request: TargetScheduleOrchestrationRequest,
    val targetPlan: TargetPlan,
    val decision: TargetScheduleDecision,
    val applicationResult: TargetScheduleApplicationResult
)

/**
 * Phase 12 orchestration: `TargetPlanner` -> `TargetSchedulePolicy` ->
 * `TargetScheduleApplicationService`.
 *
 * This boundary composes the three existing stages and adds no scheduling semantics of
 * its own. Temporal interpretation belongs to [TargetSchedulePolicy]; presentation and
 * persistence belong to [TargetScheduleApplicationService]; target planning belongs to
 * [TargetPlanner]. Typed failures from all three propagate unchanged.
 */
class TargetScheduleOrchestrator(
    private val applicationService: TargetScheduleApplicationService
) {
    suspend fun apply(
        request: TargetScheduleOrchestrationRequest
    ): TargetScheduleOrchestrationResult {
        val targetPlan = TargetPlanner.plan(
            schedules = request.schedules,
            window = request.window,
            selection = request.selection,
            existing = request.existing,
            sources = request.sources
        )
        val decision = TargetSchedulePolicy.decide(
            targetPlan = targetPlan,
            existing = request.existing,
            asOf = request.asOf,
            pauses = request.pauses
        )
        val applicationResult = applicationService.apply(
            programId = request.programId,
            revisionId = request.revisionId,
            targetScheduleDecision = decision,
            programDayBindings = request.programDayBindings
        )
        return TargetScheduleOrchestrationResult(
            request = request,
            targetPlan = targetPlan,
            decision = decision,
            applicationResult = applicationResult
        )
    }
}
