package com.monkfitness.app.domain.usecase

import com.monkfitness.app.data.repository.ProgramGraphFixture
import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.prescription.PrescriptionDimension
import com.monkfitness.app.domain.program.Focus
import com.monkfitness.app.domain.program.FocusAllocation
import com.monkfitness.app.domain.program.FocusPlan
import com.monkfitness.app.domain.program.ProgramDayType
import com.monkfitness.app.domain.program.ProgramDuration
import com.monkfitness.app.domain.program.ProgramExerciseOrigin
import com.monkfitness.app.domain.program.ProgramMode
import com.monkfitness.app.domain.program.ProgramSchedule
import com.monkfitness.app.domain.program.transfer.DurationTransfer
import com.monkfitness.app.domain.program.transfer.FocusTransfer
import com.monkfitness.app.domain.program.transfer.FocusShare
import com.monkfitness.app.domain.program.transfer.Json
import com.monkfitness.app.domain.program.transfer.JsonValue
import com.monkfitness.app.domain.program.transfer.ProgramTransferDocument
import com.monkfitness.app.domain.program.transfer.ProgramTransferFile
import com.monkfitness.app.domain.program.transfer.ProgramTransferFormat
import com.monkfitness.app.domain.program.transfer.ProgramTransferReader
import com.monkfitness.app.domain.program.transfer.ProgramTransferRejection
import com.monkfitness.app.domain.program.transfer.ProgramTransferResult
import com.monkfitness.app.domain.program.transfer.ScheduleTransfer
import java.time.DayOfWeek
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §5's export half: what a shared file **is**, and everything it is not.
 *
 * The suite is written as two lists of claims, and the second is the one §2, §15 and §16 care about:
 *
 * ```text
 * what the file carries     the Program's definition and its revision's configuration — name,
 *                           description, mode, duration, schedule, focus, days, exercises,
 *                           prescriptions, authorship and pinning — losslessly
 * what it cannot carry      an identity, a timestamp, a lifecycle state, a source, a session, a set, a
 *                           statistic, a streak, a family state, a decision or an adjustment
 * ```
 *
 * The second list is proven three ways at once: the document type has no field any of them could occupy
 * (so the bytes cannot contain one), the service holds no collaborator that could produce one (its single
 * argument is the repository that returns a Program with its current revision), and the tests read the
 * *text* of the file for the ids and the tokens the fixture actually stored. Nothing here claims "the code
 * looks like it does not write that".
 *
 * The fixture Program is deliberately non-trivial — three days with a rest day, one exercise used twice,
 * per-set repetitions and per-set durations, a pinned user-authored element beside generated ones, a live
 * session with confirmed sets and the target adaptive generation's three kinds of row — so that every
 * "absent" claim below is a claim about something that *is* there.
 */
class ProgramExportServiceTest {

    private val rig = ProgramTransferRig("export")

    @After
    fun tearDown() = rig.close()

    // ------------------------------------------------------------------ what the file carries

    @Test
    fun theExportedFileIsTheProgramsDefinitionAndItsRevisionLosslessly() = runBlocking {
        val source = rig.storeSourceProgram()

        val file = rig.exported(rig.sourceProgramId)
        val document = ProgramTransferReader.read(file.text)

        assertEquals("the format's own filename", ProgramTransferFormat.FILE_NAME, file.fileName)
        assertEquals("the type of the bytes", ProgramTransferFormat.MIME_TYPE, file.mimeType)
        assertEquals(
            "the Program's own facts, and the two of them",
            source.program.name to source.program.description,
            document.name to document.description
        )
        assertEquals(ProgramMode.MANUAL, document.revision.mode)
        assertEquals(DurationTransfer.FixedDays(30), document.revision.duration)
        assertEquals(
            "the schedule carries the user's weekdays, in ISO order",
            ScheduleTransfer.FixedWeekdays(
                listOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)
            ),
            document.revision.schedule
        )
        assertEquals(
            "the plan is carried whole: three days, their types and their names",
            listOf(
                ProgramDayType.TRAINING to "Push day",
                ProgramDayType.REST to null,
                ProgramDayType.TRAINING to "Repeat day"
            ),
            document.revision.days.map { day -> day.type to day.name }
        )
        assertEquals(
            "every occurrence is carried in plan order, the same exercise twice included (§9)",
            listOf("pushups", "pike_pushups", "pushups", "pushups", "plank"),
            document.revision.days.flatMap { day -> day.exercises.map { element -> element.exerciseId } }
        )
    }

    @Test
    fun prescriptionsRemainLossless() = runBlocking {
        rig.storeSourceProgram()

        val document = ProgramTransferReader.read(rig.exported(rig.sourceProgramId).text)
        val elements = document.revision.days.flatMap { day -> day.exercises }

        assertEquals(
            "12/10/8/6 is not 10/10/10/10: the per-set list is carried as it is written (§10)",
            listOf(listOf(12, 10, 8, 6), listOf(8, 8), listOf(5), listOf(5), listOf(30, 30, 45)),
            elements.map { element -> element.prescription.perSetTargets }
        )
        assertEquals(
            "and so is the dimension each one is stated in",
            listOf(
                PrescriptionDimension.REP_BASED,
                PrescriptionDimension.REP_BASED,
                PrescriptionDimension.REP_BASED,
                PrescriptionDimension.REP_BASED,
                PrescriptionDimension.TIME_BASED
            ),
            elements.map { element -> element.prescription.dimension }
        )
    }

    @Test
    fun authorshipAndPinningAreCarriedBecauseTheyAreContent() = runBlocking {
        rig.storeSourceProgram()

        val document = ProgramTransferReader.read(rig.exported(rig.sourceProgramId).text)
        val elements = document.revision.days.flatMap { day -> day.exercises }

        assertEquals(
            "who authored an element is what a regenerate pass keeps or replaces (§7)",
            listOf(
                ProgramExerciseOrigin.GENERATED,
                ProgramExerciseOrigin.USER_AUTHORED,
                ProgramExerciseOrigin.GENERATED,
                ProgramExerciseOrigin.GENERATED,
                ProgramExerciseOrigin.USER_AUTHORED
            ),
            elements.map { element -> element.origin }
        )
        assertEquals(
            "and whether it is exempt from automatic change is content too",
            listOf(false, true, false, false, false),
            elements.map { element -> element.isPinned }
        )
    }

    @Test
    fun theCurrentRevisionIsTheOneCarriedAndItsConfigurationIsCarriedAsStated() = runBlocking {
        val source = rig.storeSourceProgram()
        rig.storeRevision(
            source,
            mode = ProgramMode.GENERATED,
            duration = ProgramDuration.Indefinite,
            schedule = ProgramSchedule.FlexiblePerWeek(4),
            focus = FocusPlan.custom(
                listOf(FocusAllocation(Focus.PUSH, 70), FocusAllocation(Focus.MOBILITY, 30))
            )
        )

        val document = ProgramTransferReader.read(rig.exported(rig.sourceProgramId).text)

        assertEquals(ProgramMode.GENERATED, document.revision.mode)
        assertEquals(
            "the schedule's other form carries the frequency the user stated",
            ScheduleTransfer.FlexiblePerWeek(4),
            document.revision.schedule
        )
        assertEquals(
            "an indefinite program states no end (§20)",
            DurationTransfer.Indefinite,
            document.revision.duration
        )
        assertEquals(
            "a custom configuration keeps every share, in the vocabulary's own order (§8)",
            FocusTransfer.Custom(
                listOf(FocusShare(Focus.PUSH, 70), FocusShare(Focus.MOBILITY, 30))
            ),
            document.revision.focus
        )
    }

    @Test
    fun anEarlierRevisionIsNotCarriedAndThePlanDoesNotAccumulateIt() = runBlocking {
        val source = rig.storeSourceProgram()
        rig.storeRevision(source, mode = ProgramMode.GENERATED, duration = ProgramDuration.Indefinite)

        val file = rig.exported(rig.sourceProgramId)
        val document = ProgramTransferReader.read(file.text)

        assertEquals(
            "an export is the current configuration, not a revision history (§17): one revision is " +
                "described, and the document has no place for a second",
            1,
            Regex("\"mode\":").findAll(file.text).count()
        )
        assertEquals(
            "and the current revision is the one the Program points at",
            ProgramMode.GENERATED,
            document.revision.mode
        )
        assertTrue(
            "no revision identity, current or historical, is anywhere in the file",
            !file.text.contains(source.revision.revisionId.value)
        )
    }

    // ------------------------------------------------------------------ what the file cannot carry

    @Test
    fun theExportedTextContainsNoIdentityTimestampLifecycleOrRuntimeFact() = runBlocking {
        val source = rig.storeSourceProgram()
        rig.startSessionOnSource()
        rig.seedAdaptiveStateOnSource()

        val text = rig.exported(rig.sourceProgramId).text

        val forbidden = listOf(
            // §2: no identity of any kind.
            source.program.programId.value,
            source.revision.revisionId.value,
            ProgramGraphFixture.dayId("export", 1),
            ProgramGraphFixture.dayId("export", 2),
            ProgramGraphFixture.dayId("export", 3),
            ProgramGraphFixture.planExerciseId("export", 1),
            ProgramGraphFixture.slotId("export", 1),
            "session-export",
            "set-export",
            "decision-source",
            "adjustment-source",
            "pushups-family",
            // §2: no runtime, lifecycle or provenance fact.
            "programId", "revisionId", "programDayId", "programExerciseId", "revisionNumber",
            "lifecycleStatus", "NOT_STARTED", "RUNNING", "PAUSED", "COMPLETED", "archived",
            "createdAt", "updatedAt", "plannedStartDate", "actualStartDate",
            "source", "STANDARD", "IMPORTED",
            // §15: no adaptive vocabulary at all.
            "adaptive", "Adaptive", "family", "decision", "Decision", "adjustment", "Adjustment",
            "exposure", "recovery", "streak", "policy",
            // §16: no session, set, statistic or progress vocabulary.
            "session", "setLog", "SetLog", "attempt", "completedSets", "progress", "statistics",
            "cycleNumber", "set_log", "user_progress"
        )
        val present = forbidden.filter { token -> text.contains(token) }

        assertTrue(
            "the file carries the program definition and nothing about the Program that owns it: $present",
            present.isEmpty()
        )
    }

    @Test
    fun theDocumentIsExactlyTheAllowlistsFieldsAtEveryLevel() = runBlocking {
        rig.storeSourceProgram()

        val root = ProgramTransferReader.read(rig.exported(rig.sourceProgramId).text)
        val json = Json.read(rig.exported(rig.sourceProgramId).text) as JsonValue.JsonObject
        val program = json.named("program") as JsonValue.JsonObject
        val revision = json.named("revision") as JsonValue.JsonObject
        val days = (revision.named("days") as JsonValue.JsonArray).elements
            .map { element -> element as JsonValue.JsonObject }
        val elements =
            (days[0].named("exercises") as JsonValue.JsonArray).elements
                .map { element -> element as JsonValue.JsonObject }
        val prescription = elements[0].named("prescription") as JsonValue.JsonObject

        assertEquals(
            "the document's own fields, and every one of them a fact §2 names as transferable",
            listOf("format", "formatVersion", "program", "revision"),
            json.names
        )
        assertEquals(
            "the Program's definition: a name and a description",
            listOf("name", "description"),
            program.names
        )
        assertEquals(
            "the revision's configuration: mode, duration, schedule, focus, plan",
            listOf("mode", "duration", "schedule", "focus", "days"),
            revision.names
        )
        assertEquals(
            "a day: what it is, what it is called, what it plans",
            listOf("type", "name", "exercises"),
            days[0].names
        )
        assertEquals(
            "a plan element: the exercise, its prescription, its authorship, its pin",
            listOf("exerciseId", "prescription", "origin", "pinned"),
            elements[0].names
        )
        assertEquals(
            "a prescription: its dimension and its per-set targets",
            listOf("dimension", "perSetTargets"),
            prescription.names
        )
        assertEquals(
            "and the whole document describes the same Program the stored one is",
            listOf("Push day", "Repeat day"),
            storeOf(root)
        )
    }

    @Test
    fun theExportedFileIsByteIdenticalForTheSameProgramEveryTime() = runBlocking {
        rig.storeSourceProgram()

        val first = rig.exported(rig.sourceProgramId)
        val second = rig.exported(rig.sourceProgramId)
        val third = rig.exported(rig.sourceProgramId)

        assertTrue(
            "the same program in produces the same bytes out: no clock, no random value and no " +
                "unordered collection takes part in the write (§11)",
            first.hasTheSameBytesAs(second) && second.hasTheSameBytesAs(third)
        )
        assertTrue(
            "and the bytes are the format's own UTF-8 encoding of the text",
            first.bytes.contentEquals(ProgramTransferFormat.encode(first.text))
        )
        assertTrue("the file is text, ending with one newline", first.text.endsWith("}\n"))
    }

    @Test
    fun exportingWritesNothingAtAll() = runBlocking {
        rig.storeSourceProgram()
        rig.startSessionOnSource()
        rig.seedAdaptiveStateOnSource()
        val before = rig.tableCounts()

        rig.exported(rig.sourceProgramId)
        rig.exported(rig.sourceProgramId)

        assertEquals(
            "an export is a read: it creates no row anywhere, in any table — the shipped ones included",
            before,
            rig.tableCounts()
        )
    }

    // ------------------------------------------------------------------ its refusals

    @Test
    fun aProgramThatIsNotStoredIsRefused() = runBlocking {
        rig.storeSourceProgram()

        val result = rig.exportService.export(ProgramId("program-nobody"))

        val rejection = (result as? ProgramTransferResult.Rejected)?.rejection
        assertEquals(
            "a missing Program is a typed refusal, never an empty or partial file (§28, §33)",
            "program-nobody",
            (rejection as? ProgramTransferRejection.ProgramNotFound)?.programId?.value
        )
    }

    // ------------------------------------------------------------------ helpers

    /** A lookup used once: the document's days as the (type, name) list a reader can check by eye. */
    private fun storeOf(document: ProgramTransferDocument) =
        document.revision.days.mapNotNull { day -> day.name }
}

/** One exported file, or the failure as the test's own assertion. */
private suspend fun ProgramTransferRig.exported(programId: ProgramId): ProgramTransferFile =
    when (val result = exportService.export(programId)) {
        is ProgramTransferResult.Success -> result.value
        else -> throw AssertionError("expected the export to succeed, was $result")
    }
