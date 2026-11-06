package com.monkfitness.app.domain.usecase

import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.program.ProgramSchedule
import com.monkfitness.app.domain.program.structure
import com.monkfitness.app.domain.program.transfer.ProgramTransferFile
import com.monkfitness.app.domain.program.transfer.ProgramTransferFixture
import com.monkfitness.app.domain.program.transfer.ProgramTransferFormat
import com.monkfitness.app.domain.program.transfer.ProgramTransferIssue
import com.monkfitness.app.domain.program.transfer.ProgramTransferRejection
import com.monkfitness.app.domain.program.transfer.ProgramTransferResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Every other day" through the whole transfer boundary: the form and its interval in the file, the
 * revision they come back as, and the refusals a file that states neither must get.
 *
 * The document is written by this app and read by it here, and the refusals are read from hand-edited
 * documents — which is the only way an import meets a file this app did not write.
 */
class ProgramTransferEveryNDaysTest {

    private val rig = ProgramTransferRig("everyndays")

    @After
    fun tearDown() = rig.close()

    @Test
    fun anEveryOtherDayRevisionSurvivesTheRoundTripWithItsInterval() = runBlocking {
        val source = rig.storeSourceProgram()
        rig.storeRevision(
            source = source,
            revisionNumber = 2,
            schedule = ProgramSchedule.EveryNDays(2)
        )

        val exportedFromA = rig.exported(source.program.programId)
        val text = String(exportedFromA.bytes, Charsets.UTF_8)
        val imported = rig.saved(exportedFromA.bytes)

        assertTrue(
            "the file states the interval form and its number, so no importer has to guess which " +
                "rhythm it describes: $text",
            "\"kind\": \"EVERY_N_DAYS\"" in text && "\"days\": 2" in text
        )
        assertEquals(
            "and the imported revision runs at the same interval",
            ProgramSchedule.EveryNDays(2),
            rig.currentRevision(imported.programId)!!.schedule
        )
        assertEquals(
            "with the whole structure equal, not just the schedule",
            rig.currentRevision(source.program.programId)!!.structure,
            rig.currentRevision(imported.programId)!!.structure
        )
        assertTrue(
            "and a second export is byte-identical, so the interval is written deterministically",
            exportedFromA.hasTheSameBytesAs(rig.exported(imported.programId))
        )
    }

    @Test
    fun anEveryNDaysScheduleWithoutItsIntervalIsRefusedAsAMissingField() = runBlocking {
        val refused = review(
            ProgramTransferFixture.edited(
                """{ "kind": "FIXED_WEEKDAYS", "weekdays": ["MONDAY", "WEDNESDAY", "FRIDAY"] }""",
                """{ "kind": "EVERY_N_DAYS" }"""
            )
        )

        val schema = refused as ProgramTransferRejection.SchemaInvalid
        assertTrue(
            "an interval without its number describes no rhythm, and it is not read as some other " +
                "schedule: ${schema.issues.map { it.message }}",
            schema.issues.any { issue ->
                issue is ProgramTransferIssue.MissingField && issue.name == "days"
            }
        )
    }

    @Test
    fun anEveryNDaysScheduleWithAnIntervalTheDomainRefusesIsRefused() = runBlocking {
        val refused = review(
            ProgramTransferFixture.edited(
                """{ "kind": "FIXED_WEEKDAYS", "weekdays": ["MONDAY", "WEDNESDAY", "FRIDAY"] }""",
                """{ "kind": "EVERY_N_DAYS", "days": 1 }"""
            )
        )

        val semantic = refused as ProgramTransferRejection.SemanticallyInvalid
        assertTrue(
            "one day between sessions is every day, which the weekly forms state — the file is refused " +
                "rather than clamped to two: ${semantic.issues.map { it.message }}",
            semantic.issues.any { issue ->
                issue.path == "revision.schedule.days" && issue.message.contains("at least 2 days")
            }
        )
    }

    @Test
    fun aFileWrittenBeforeTheIntervalExistedStillImports() = runBlocking {
        val imported = rig.saved(ProgramTransferFixture.VALID_BYTES)

        assertEquals(
            "the weekday form in an older file is unchanged by the new one: it imports as itself",
            ProgramSchedule.FixedWeekdays(
                setOf(java.time.DayOfWeek.MONDAY, java.time.DayOfWeek.WEDNESDAY, java.time.DayOfWeek.FRIDAY)
            ),
            rig.currentRevision(imported.programId)!!.schedule
        )
    }

    private suspend fun review(text: String): ProgramTransferRejection =
        when (val result = rig.importService.review(ProgramTransferFormat.encode(text))) {
            is ProgramTransferResult.Rejected -> result.rejection
            else -> throw AssertionError("expected the file to be refused, was $result")
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
