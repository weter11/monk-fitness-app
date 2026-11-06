package com.monkfitness.app.ui.programs

import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.program.ProgramOperationRefusal
import com.monkfitness.app.domain.progress.ProgressScope
import com.monkfitness.app.domain.usecase.ProgramProgressService
import com.monkfitness.app.domain.usecase.ProgramStartResult

/**
 * One composed Start read as the sentence the user sees, shared by every screen that can start a
 * Program so the same operation cannot be reported two ways.
 */
internal object StartNotice {

    suspend fun of(result: ProgramStartResult, progress: ProgramProgressService): ProgramNotice =
        when (result) {
            is ProgramStartResult.Started -> ProgramNotice.STARTED
            is ProgramStartResult.TargetSchedulingRefused -> scheduling(result.program.programId, progress)
            is ProgramStartResult.StartRefused -> refusalNote(result.reason)
            is ProgramStartResult.StartFailed -> ProgramNotice.STORAGE_FAILED
        }

    /**
     * A refused target pass is "nothing was planned" only when the Program really has no planned
     * opportunity afterwards. A calendar that cannot be read is not an absence of slots, so it is
     * reported as the storage failure it is rather than as an unplanned schedule.
     */
    private suspend fun scheduling(programId: ProgramId, progress: ProgramProgressService): ProgramNotice =
        try {
            if (progress.calendarProgress(ProgressScope.OfProgram(programId)).upcoming > 0) {
                ProgramNotice.STARTED
            } else {
                ProgramNotice.TARGET_SCHEDULING_UNAVAILABLE
            }
        } catch (failure: Throwable) {
            ProgramNotice.STORAGE_FAILED
        }

    /** A refusal the lifecycle states, as the sentence the user reads. */
    fun refusalNote(refusal: ProgramOperationRefusal): ProgramNotice = when (refusal) {
        ProgramOperationRefusal.StandardProgramCannotBeEdited -> ProgramNotice.STANDARD_CANNOT_BE_EDITED
        ProgramOperationRefusal.StandardProgramCannotBeDeleted -> ProgramNotice.STANDARD_CANNOT_BE_DELETED
        ProgramOperationRefusal.StructuralChangeNeedsARevision -> ProgramNotice.ILLEGAL_TRANSITION
        is ProgramOperationRefusal.HasInProgressSession -> ProgramNotice.ACTIVE_SESSION_BLOCKS_DELETE
        is ProgramOperationRefusal.ArchivingTheSelectionRequiresAnotherSelection ->
            ProgramNotice.ARCHIVE_OF_SELECTION

        is ProgramOperationRefusal.IllegalTransition -> ProgramNotice.ILLEGAL_TRANSITION
        is ProgramOperationRefusal.ProgramNotFound -> ProgramNotice.PROGRAM_NOT_FOUND
    }
}
