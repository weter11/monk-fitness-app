package com.monkfitness.app.domain.usecase

import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.program.SessionType
import com.monkfitness.app.domain.program.structure
import com.monkfitness.app.domain.program.transfer.ProgramTransferFile
import com.monkfitness.app.domain.program.transfer.ProgramTransferResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The session-type selection through the whole boundary: a stored revision's selection, the file it is
 * written to, and the revision the imported file comes back as.
 *
 * The claim is the one a revision's Mixed reading makes necessary — a selection is configuration the user
 * stated, so it must survive the file, and *no* selection must come back as Mixed rather than as the whole
 * vocabulary spelled out.
 */
class ProgramTransferSessionTypesTest {

    private val rig = ProgramTransferRig("sessiontypes")

    @After
    fun tearDown() = rig.close()

    @Test
    fun aSelectionSurvivesTheRoundTripAndMixedStaysMixed() = runBlocking {
        val selections = listOf(
            "Mixed" to emptySet<SessionType>(),
            "one type" to setOf(SessionType.MOBILITY),
            "several types" to setOf(SessionType.STRENGTH, SessionType.POSTURE)
        )
        val source = rig.storeSourceProgram()

        selections.forEachIndexed { index, (description, selection) ->
            rig.storeRevision(
                source = source,
                revisionNumber = index + 2,
                sessionTypes = selection
            )

            val exportedFromA = rig.exported(source.program.programId)
            val imported = rig.saved(exportedFromA.bytes)
            val importedRevision = rig.currentRevision(imported.programId)!!

            assertEquals(
                "$description: the selection is revision content, so the import restores it exactly",
                selection,
                importedRevision.sessionTypes
            )
            assertEquals(
                "$description: and the whole structure is equal, not just the selection",
                rig.currentRevision(source.program.programId)!!.structure,
                importedRevision.structure
            )
            assertTrue(
                "$description: a second export is byte-identical, so the field is written " +
                    "deterministically and read back as written",
                exportedFromA.hasTheSameBytesAs(rig.exported(imported.programId))
            )
        }
    }

    @Test
    fun aMixedRevisionWritesNoSessionTypeFieldAtAll() = runBlocking {
        val source = rig.storeSourceProgram()

        val text = String(rig.exported(source.program.programId).bytes, Charsets.UTF_8)

        assertTrue(
            "Mixed is the absent field, which is what a file shared before the selection existed " +
                "already says: $text",
            "sessionTypes" !in text
        )
    }

    private suspend fun ProgramTransferRig.exported(programId: ProgramId): ProgramTransferFile =
        when (val result = exportService.export(programId)) {
            is ProgramTransferResult.Success -> result.value
            else -> throw AssertionError("expected the export to succeed, was $result")
        }

    private suspend fun ProgramTransferRig.saved(bytes: ByteArray) =
        when (val reviewed = importService.review(bytes)) {
            is ProgramTransferResult.Success -> when (val saved = importService.save(reviewed.value, false)) {
                is ProgramTransferResult.Success -> saved.value
                else -> throw AssertionError("expected the import to succeed, was $saved")
            }
            else -> throw AssertionError("expected the file to be accepted, was $reviewed")
        }
}
