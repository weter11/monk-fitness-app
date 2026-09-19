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
 * Exporting a Program — §30 step 13, the first half of §5.
 *
 * ### The flow
 *
 * ```text
 * ProgramId → the Program and its current revision → transfer document → deterministic JSON → file
 * ```
 *
 * Nothing else takes part. The layer reads exactly two things — the Program's own facts and the plan its
 * current revision describes — and it holds **one collaborator**, the repository that returns them
 * together. That constructor is the guarantee §15 and §16 ask for, in the same form every other stage of
 * this system states one: there is no session repository, no progress repository, no adaptive repository,
 * no clock and no id generator in this object, so a session, a set log, a statistic, a streak, a family
 * state, a decision record or an adjustment is not merely forbidden here — it is unreachable. §2's
 * *"lifecycle state, createdAt, session ids, adaptive state"* have no representation in the document
 * either ([com.monkfitness.app.domain.program.transfer.ProgramTransferDocument]), so both halves of the
 * prohibition are structural.
 *
 * ### What "current configuration" means (§17)
 *
 * The revision exported is the one `currentRevisionId` **points at** — not the newest by number, not the
 * one with the latest timestamp. A Program's current revision is the pointer's decision (§23), and a
 * revision is immutable, so an export is a snapshot of the plan as it stands rather than of a history:
 * earlier revisions are not read, are not counted and are not referenced.
 *
 * ### Why the clock is absent
 *
 * An export writes no timestamp, and none of the values it writes is time-derived: the document has no
 * `createdAt`, no `updatedAt`, no `archivedAt` and no date at all except the schedule's own weekdays
 * (§18). A clock here would be a collaborator the layer had nothing to do with — and its absence makes
 * *"same Program input → byte-equivalent exported JSON"* a property of the object's shape rather than of
 * its restraint.
 *
 * ### Failure is never an empty file
 *
 * A Program that is not stored is a refusal ([com.monkfitness.app.domain.program.transfer
 * .ProgramTransferRejection.ProgramNotFound]), and a Program whose pointer names a revision that is not
 * stored is invalid persisted data and propagates as [ProgramTransferResult.Failed] rather than being
 * reported as a Program with no plan (§23, §33). No path here produces an empty or partial file: a file
 * exists or the call answers with a refusal.
 *
 * @param programRepository the Program aggregate and the revision its pointer names, read together. It is
 *   the only collaborator, and it is read-only here.
 */
class ProgramExportService(
    private val programRepository: ProgramRepository
) {

    /**
     * [programId]'s transferable definition, as the file a share carries.
     *
     * @return the file — always [ProgramTransferFormat.FILE_NAME] wide, always
     *   [ProgramTransferFormat.MIME_TYPE] typed, always the format's own UTF-8 bytes.
     */
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
