package com.monkfitness.app.domain.usecase

import com.monkfitness.app.data.repository.ProgramRepository
import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.program.transfer.ExportProgramMissing
import com.monkfitness.app.domain.program.transfer.ProgramTransferFile
import com.monkfitness.app.domain.program.transfer.ProgramTransferFormat
import com.monkfitness.app.domain.program.transfer.ProgramTransferJson
import com.monkfitness.app.domain.program.transfer.ProgramTransferMapper
import com.monkfitness.app.domain.program.transfer.ProgramTransferResult
import com.monkfitness.app.domain.program.transfer.rejecting
import com.monkfitness.app.domain.program.transfer.transferResult

/**
 * Exporting a Program: the stored Program and the plan its current revision describes, mapped to the
 * transfer document, written as deterministic JSON, returned as a file.
 *
 * The revision exported is the one `currentRevisionId` **points at** — not the newest by number, not the
 * latest timestamp. The pointer is the decision, and revisions are immutable, so this is a snapshot of
 * the plan as it stands; earlier revisions are never read or counted.
 *
 * There is no clock here because nothing written is time-derived: the document has no timestamps, only
 * the schedule's weekdays. Same Program input therefore yields byte-equivalent JSON.
 *
 * Failure is never an empty file: a missing Program is a refusal, and a pointer naming an unstored
 * revision is invalid persisted data that propagates as [ProgramTransferResult.Failed] rather than being
 * reported as a Program with no plan.
 *
 * @param programRepository the Program aggregate and the revision its pointer names, read together — the
 *   only collaborator, read-only here.
 */
class ProgramExportService(
    private val programRepository: ProgramRepository
) {

    /** [programId]'s transferable definition, as the file a share carries. */
    suspend fun export(programId: ProgramId): ProgramTransferResult<ProgramTransferFile> =
        transferResult {
            val stored = programRepository.programWithCurrentRevision(programId)
                ?: throw ExportProgramMissing(programId)
            val text = ProgramTransferJson.write(
                ProgramTransferMapper.documentOf(stored.program, stored.currentRevision)
            )
            ProgramTransferFile(
                fileName = ProgramTransferFormat.FILE_NAME,
                mimeType = ProgramTransferFormat.MIME_TYPE,
                bytes = ProgramTransferFormat.encode(text)
            )
        }.rejecting()
}
