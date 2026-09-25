package com.monkfitness.app.domain.usecase

import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.common.RevisionId
import com.monkfitness.app.domain.program.target.TargetOccurrencePresentation
import com.monkfitness.app.domain.program.target.TargetOccurrencePresenter
import com.monkfitness.app.domain.program.target.TargetProgramDayBinding
import com.monkfitness.app.domain.program.target.TargetScheduleDecision

/** Immutable result of applying one already-computed target schedule decision. */
data class TargetScheduleApplicationResult(
    val decision: TargetScheduleDecision,
    val presentations: List<TargetOccurrencePresentation>,
    val persistenceResult: TargetScheduleSlotPersistenceResult
)

/** Application boundary from a target schedule decision to target slot persistence. */
class TargetScheduleApplicationService(
    private val slotPersister: TargetScheduleSlotPersister
) {
    suspend fun apply(
        programId: ProgramId,
        revisionId: RevisionId,
        targetScheduleDecision: TargetScheduleDecision,
        programDayBindings: List<TargetProgramDayBinding>
    ): TargetScheduleApplicationResult {
        val presentations = TargetOccurrencePresenter.present(
            targetScheduleDecision.created,
            programDayBindings
        )
        val persistenceResult = slotPersister.persist(
            TargetScheduleSlotPersistenceInput(
                programId = programId,
                revisionId = revisionId,
                decision = targetScheduleDecision,
                presentations = presentations
            )
        )
        return TargetScheduleApplicationResult(
            decision = targetScheduleDecision,
            presentations = presentations,
            persistenceResult = persistenceResult
        )
    }
}
