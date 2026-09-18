package com.monkfitness.app.domain.program

import com.monkfitness.app.domain.common.PauseId
import com.monkfitness.app.domain.common.ProgramDayId
import com.monkfitness.app.domain.common.ProgramExerciseId
import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.common.RevisionId
import com.monkfitness.app.domain.common.SlotId
import com.monkfitness.app.domain.common.SessionId
import com.monkfitness.app.domain.prescription.RepPrescription
import com.monkfitness.app.domain.prescription.TimePrescription
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate

/**
 * The Program domain foundation: the vocabularies the architecture locks, the fields each type is
 * allowed to own, and the invariants that make the wrong shapes unrepresentable.
 *
 * Two kinds of assertion are used deliberately. The vocabularies are pinned as exact value lists,
 * because "only MANUAL and GENERATED" is a decision that must not drift. The *field sets* of
 * [Program] and [ProgramRevision] are pinned by reflection, because the interesting rule is a
 * negative one: a Program owns no mode and no plan, a revision owns no date, no cycle number and no
 * session date, and neither owns a selection flag — so if a later change adds one of those fields,
 * this suite says so instead of letting a second source of truth appear quietly.
 */
class ProgramFoundationTest {

    private val createdAt: Instant = Instant.parse("2026-09-18T09:00:00Z")
    private val laterAt: Instant = Instant.parse("2026-09-18T11:00:00Z")

    // ------------------------------------------------------------------ vocabularies

    @Test
    fun programModeHasExactlyTwoValues() {
        assertEquals(listOf("MANUAL", "GENERATED"), ProgramMode.entries.map { it.name })
    }

    @Test
    fun lifecycleHasExactlyFourStatesAndArchiveIsNotOneOfThem() {
        assertEquals(
            listOf("NOT_STARTED", "RUNNING", "PAUSED", "COMPLETED"),
            LifecycleStatus.entries.map { it.name }
        )
        assertTrue(
            "archiving is a stamp (archivedAt), never a lifecycle state (§3)",
            LifecycleStatus.entries.none { it.name.contains("ARCHIV") }
        )
        assertFalse(LifecycleStatus.RUNNING.isTerminal)
        assertFalse(LifecycleStatus.PAUSED.isTerminal)
        assertTrue(LifecycleStatus.COMPLETED.isTerminal)
    }

    @Test
    fun slotStatusHasExactlyFourStatesAndMissedIsNotSuperseded() {
        assertEquals(
            listOf("PLANNED", "COMPLETED", "MISSED", "SUPERSEDED"),
            SlotStatus.entries.map { it.name }
        )
        assertTrue(SlotStatus.MISSED != SlotStatus.SUPERSEDED)
    }

    @Test
    fun programDayTypeNamesTheKindsOfDayAndRestIsOneOfThem() {
        assertEquals(
            listOf("TRAINING", "MOBILITY", "POSTURE_MOBILITY", "REST"),
            ProgramDayType.entries.map { it.name }
        )
    }

    @Test
    fun programSourceNamesProvenanceAndOnlyTheStandardProgramIsBuiltIn() {
        assertEquals(listOf("STANDARD", "USER", "IMPORTED"), ProgramSource.entries.map { it.name })
        assertTrue(ProgramSource.STANDARD.isBuiltIn)
        assertFalse(ProgramSource.USER.isBuiltIn)
        assertFalse(ProgramSource.IMPORTED.isBuiltIn)
    }

    // ------------------------------------------------------------------ the Program is not the plan

    @Test
    fun aProgramOwnsLifecycleAndProvenanceButNotItsPlanModeOrSelection() {
        assertEquals(
            setOf(
                "programId", "name", "description", "source", "lifecycleStatus",
                "currentRevisionId", "createdAt", "updatedAt", "plannedStartDate",
                "actualStartDate", "archivedAt"
            ),
            declaredFieldNames(Program::class.java)
        )
    }

    @Test
    fun thePlanModeAndDurationBelongToTheRevisionNotTheProgram() {
        val revisionFields = declaredFieldNames(ProgramRevision::class.java)

        assertEquals(
            setOf(
                "revisionId", "programId", "revisionNumber", "mode", "duration",
                "schedule", "days", "createdAt"
            ),
            revisionFields
        )

        // No date, no cycle number, no session date, no mode, no selection: a revision describes
        // structure, and identity is its revisionId (§1, §23).
        val forbidden = listOf(
            "cycleNumber", "sessionDate", "date", "selected", "isSelected", "active",
            "progress", "completedAt", "startedAt"
        )
        assertTrue(
            "a revision may not own these facts: ${revisionFields.intersect(forbidden.toSet())}",
            revisionFields.intersect(forbidden.toSet()).isEmpty()
        )
        assertTrue("a program may not claim to be the selected one", !declaredFieldNames(Program::class.java).any {
            it.contains("select", ignoreCase = true) || it == "isActive"
        })
    }

    // ------------------------------------------------------------------ Program invariants

    @Test
    fun aProgramIsAlwaysCreatedWithARevisionAndItsStampsAreOrdered() {
        val program = program()
        assertEquals(RevisionId("rev-1"), program.currentRevisionId)
        assertFalse(program.hasStarted)
        assertFalse(program.isArchived)

        assertRejects("a program updated before it was created") {
            program.copy(updatedAt = createdAt.minusSeconds(1))
        }
        assertRejects("a program archived before it was created") {
            program.copy(archivedAt = createdAt.minusSeconds(1))
        }
        assertRejects("a program with no name") { program.copy(name = "  ") }
    }

    @Test
    fun aPlannedStartDateDoesNotStartAProgram() {
        val planned = program(plannedStartDate = LocalDate.parse("2026-10-01"))

        assertEquals(LifecycleStatus.NOT_STARTED, planned.lifecycleStatus)
        assertNull(planned.actualStartDate)
        assertFalse(planned.hasStarted)

        assertRejects("a NOT_STARTED program with an actual start") {
            planned.copy(actualStartDate = createdAt.plusSeconds(60))
        }
    }

    @Test
    fun aStartedProgramHasAnActualStartAndACompletedOneIsTerminal() {
        val running = program(lifecycleStatus = LifecycleStatus.RUNNING, actualStartDate = laterAt)
        assertTrue(running.hasStarted)

        assertRejects("a RUNNING program without an actual start") {
            program(lifecycleStatus = LifecycleStatus.RUNNING)
        }
        assertRejects("a COMPLETED program without an actual start") {
            program(lifecycleStatus = LifecycleStatus.COMPLETED)
        }
        assertRejects("a program that started before it was created") {
            program(
                lifecycleStatus = LifecycleStatus.RUNNING,
                actualStartDate = createdAt.minusSeconds(1)
            )
        }
    }

    @Test
    fun archivingIsIndependentOfTheLifecycle() {
        val archived = program(archivedAt = laterAt)

        assertEquals(LifecycleStatus.NOT_STARTED, archived.lifecycleStatus)
        assertTrue(archived.isArchived)
    }

    // ------------------------------------------------------------------ the revision is a result

    @Test
    fun aSavedRevisionAlwaysCarriesAPlan() {
        assertRejects("a revision with no days") { revision(days = emptyList()) }
    }

    @Test
    fun aRevisionsDaysAreNumberedOneToNInOrder() {
        val twoDays = revision(days = listOf(day(1), day(2)))

        assertEquals(2, twoDays.days.size)
        assertEquals(ProgramDayId("day-2"), twoDays.dayAt(2)?.programDayId)
        assertNull(twoDays.dayAt(3))

        assertRejects("a revision whose days start at 0") { revision(days = listOf(day(0))) }
        assertRejects("a revision with a gap in its day numbering") {
            revision(days = listOf(day(1), day(3)))
        }
        assertRejects("a revision whose days are out of order") {
            val first = day(1).copy(programDayId = ProgramDayId("day-a"))
            val second = day(2).copy(programDayId = ProgramDayId("day-b"))
            revision(days = listOf(second, first))
        }
        assertRejects("a revision whose days share an identity") {
            val first = day(1)
            val second = day(2).copy(programDayId = first.programDayId)
            revision(days = listOf(first, second))
        }
    }

    @Test
    fun revisionNumberingStartsAtOneAndIsNotAnIdentity() {
        assertEquals(1, ProgramRevision.FIRST_REVISION_NUMBER)
        assertRejects("revision number 0") { revision(revisionNumber = 0) }

        val second = revision(revisionNumber = 2)
        assertEquals(RevisionId("rev-2"), second.revisionId)
        assertEquals(2, second.revisionNumber)
    }

    @Test
    fun aRevisionIsAValueAndACopyIsAnotherValue() {
        val original = revision()
        val renamedRev = original.copy(mode = ProgramMode.GENERATED)

        assertEquals(ProgramMode.MANUAL, original.mode)
        assertEquals(ProgramMode.GENERATED, renamedRev.mode)
        assertEquals(original.revisionId, renamedRev.revisionId)
    }

    // ------------------------------------------------------------------ days and their elements

    @Test
    fun eachOccurrenceOfAnExerciseIsItsOwnElement() {
        val repeated = ProgramDay(
            programDayId = ProgramDayId("day-1"),
            position = 1,
            type = ProgramDayType.TRAINING,
            exercises = listOf(
                exercise(id = "element-1", exerciseId = "pushups"),
                exercise(id = "element-2", exerciseId = "pushups")
            )
        )

        // The same exercise twice is legal; the two occurrences are separately addressable (§9).
        assertEquals(listOf("pushups", "pushups"), repeated.plannedExerciseIds)
        assertEquals(2, repeated.exercises.map { it.programExerciseId }.toSet().size)

        assertRejects("a day whose two elements share an identity") {
            repeated.copy(exercises = listOf(exercise(id = "element-1"), exercise(id = "element-1")))
        }
    }

    @Test
    fun aRestDayPrescribesNoExercises() {
        val rest = ProgramDay(ProgramDayId("day-1"), 1, ProgramDayType.REST)
        assertTrue(rest.exercises.isEmpty())

        assertRejects("a rest day with exercises") {
            rest.copy(exercises = listOf(exercise(id = "element-1")))
        }
    }

    @Test
    fun aPlanElementCarriesItsPrescriptionItsProvenanceAndItsPin() {
        val generated = exercise(id = "element-1")
        assertEquals(ProgramExerciseOrigin.GENERATED, generated.origin)
        assertFalse(generated.isPinned)

        val pinned = generated.copy(isPinned = true, origin = ProgramExerciseOrigin.USER_AUTHORED)
        assertTrue(pinned.isPinned)
        assertEquals(ProgramExerciseOrigin.USER_AUTHORED, pinned.origin)

        // A manual program may prescribe seconds where a generated one prescribes repetitions.
        val timed = generated.copy(prescription = TimePrescription(listOf(30, 45)))
        assertEquals(2, timed.prescription.setCount)

        assertRejects("a plan element with no exercise") { generated.copy(exerciseId = " ") }
    }

    @Test
    fun aDayIsPresentedByItsPositionAndOptionalName() {
        val unnamed = day(1)
        assertNull(unnamed.name)

        val named = unnamed.copy(name = "Push day")
        assertEquals("Push day", named.name)

        assertRejects("a day with a blank name") { unnamed.copy(name = " ") }
        assertRejects("a day at position 0") { unnamed.copy(position = 0) }
    }

    // ------------------------------------------------------------------ duration and schedule

    @Test
    fun aDurationIsEitherAFixedNumberOfDaysOrIndefinite() {
        assertEquals(28, (ProgramDuration.FixedDays(28) as ProgramDuration.FixedDays).days)
        assertRejects("a fixed duration of zero days") { ProgramDuration.FixedDays(0) }
        assertEquals(ProgramDuration.Indefinite, ProgramDuration.Indefinite)
        assertTrue(ProgramDuration.Indefinite is ProgramDuration)
    }

    @Test
    fun aScheduleIsEitherFixedWeekdaysOrADeterministicFrequency() {
        val fixed = ProgramSchedule.FixedWeekdays(setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY))
        assertEquals(2, (fixed as ProgramSchedule.FixedWeekdays).weekdays.size)

        val flexible = ProgramSchedule.FlexiblePerWeek(3)
        assertEquals(3, (flexible as ProgramSchedule.FlexiblePerWeek).sessionsPerWeek)

        assertRejects("a fixed-weekday schedule with no weekdays") {
            ProgramSchedule.FixedWeekdays(emptySet())
        }
        assertRejects("a frequency of zero sessions") { ProgramSchedule.FlexiblePerWeek(0) }
        assertRejects("a frequency of eight sessions") { ProgramSchedule.FlexiblePerWeek(8) }
    }

    // ------------------------------------------------------------------ slots and pauses

    @Test
    fun onlyACompletedSlotHappenedAndItNamesTheSessionThatCompletedIt() {
        val planned = slot()
        assertTrue(planned.isOpen)
        assertFalse(planned.hasBeenAttempted)

        val completed = planned.copy(
            status = SlotStatus.COMPLETED,
            attempts = listOf(SessionId("session-1")),
            completedAt = laterAt
        )
        assertTrue(completed.hasBeenAttempted)
        assertFalse(completed.isOpen)

        assertRejects("a COMPLETED slot with no completion stamp") {
            completed.copy(completedAt = null)
        }
        assertRejects("a COMPLETED slot no session completed") {
            completed.copy(attempts = emptyList())
        }
        assertRejects("a PLANNED slot that claims to have been completed") {
            planned.copy(completedAt = laterAt)
        }
        assertRejects("a MISSED slot that claims to have been completed") {
            planned.copy(status = SlotStatus.MISSED, completedAt = laterAt)
        }
        assertRejects("a slot attempted by the same session twice") {
            completed.copy(attempts = completed.attempts + completed.attempts)
        }
    }

    @Test
    fun aSlotKeepsEveryAttemptWithoutJudgingThem() {
        val session1 = SessionId("session-1")
        val session2 = SessionId("session-2")

        // A missed opportunity that was attempted twice and not completed: the attempts are facts,
        // and nothing here says how much work they contained.
        val missed = slot(status = SlotStatus.MISSED, attempts = listOf(session1, session2))

        assertEquals(listOf(session1, session2), missed.attempts)
        assertTrue(missed.hasBeenAttempted)
        assertEquals(SlotStatus.MISSED, missed.status)
    }

    @Test
    fun aPauseIsAnIntervalThatCannotEndBeforeItStarts() {
        val open = ProgramPause(PauseId("pause-1"), ProgramId("program-1"), createdAt)
        assertTrue(open.isOpen)

        val closed = open.copy(endedAt = laterAt)
        assertFalse(closed.isOpen)

        assertRejects("a pause that ended before it started") {
            open.copy(endedAt = createdAt.minusSeconds(1))
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun program(
        lifecycleStatus: LifecycleStatus = LifecycleStatus.NOT_STARTED,
        plannedStartDate: LocalDate? = null,
        actualStartDate: Instant? = null,
        archivedAt: Instant? = null
    ) = Program(
        programId = ProgramId("program-1"),
        name = "My program",
        description = "",
        source = ProgramSource.USER,
        lifecycleStatus = lifecycleStatus,
        currentRevisionId = RevisionId("rev-1"),
        createdAt = createdAt,
        updatedAt = createdAt,
        plannedStartDate = plannedStartDate,
        actualStartDate = actualStartDate,
        archivedAt = archivedAt
    )

    private fun exercise(
        id: String,
        exerciseId: String = "pushups",
        reps: List<Int> = listOf(10, 8)
    ) = ProgramExercise(
        programExerciseId = ProgramExerciseId(id),
        exerciseId = exerciseId,
        prescription = RepPrescription(reps),
        origin = ProgramExerciseOrigin.GENERATED
    )

    private fun day(
        position: Int,
        type: ProgramDayType = ProgramDayType.TRAINING,
        exercises: List<ProgramExercise> = listOf(exercise(id = "element-$position"))
    ) = ProgramDay(
        programDayId = ProgramDayId("day-$position"),
        position = position,
        type = type,
        name = null,
        exercises = exercises
    )

    private fun revision(
        days: List<ProgramDay> = listOf(day(1)),
        revisionNumber: Int = 1
    ) = ProgramRevision(
        revisionId = RevisionId("rev-$revisionNumber"),
        programId = ProgramId("program-1"),
        revisionNumber = revisionNumber,
        mode = ProgramMode.MANUAL,
        duration = ProgramDuration.FixedDays(28),
        schedule = ProgramSchedule.FlexiblePerWeek(3),
        days = days,
        createdAt = createdAt
    )

    private fun slot(
        status: SlotStatus = SlotStatus.PLANNED,
        attempts: List<SessionId> = emptyList()
    ) = WorkoutSlot(
        slotId = SlotId("slot-1"),
        programId = ProgramId("program-1"),
        revisionId = RevisionId("rev-1"),
        programDayId = ProgramDayId("day-1"),
        plannedFor = LocalDate.parse("2026-09-18"),
        status = status,
        attempts = attempts
    )

    /**
     * The property fields a class declares, with the Compose compiler's synthetic `$stable` marker
     * and any other synthetic member filtered out.
     */
    private fun declaredFieldNames(cls: Class<*>): Set<String> =
        cls.declaredFields
            .filterNot { it.isSynthetic || it.name.startsWith("$") || Modifier.isStatic(it.modifiers) }
            .map { it.name }
            .toSet()

    private fun assertRejects(what: String, block: () -> Any) {
        try {
            block()
            throw AssertionError("$what must not be constructible")
        } catch (expected: IllegalArgumentException) {
            assertNotNull("$what must explain the refusal", expected.message)
        }
    }

    @Test
    fun theHelperItselfFindsTheDeclaredFieldsItIsSupposedToPin() {
        // Guards the reflection helper: if the filter stopped matching, the field-set assertions
        // above would pass vacuously against an empty set.
        assertEquals(11, declaredFieldNames(Program::class.java).size)
        assertEquals(8, declaredFieldNames(ProgramRevision::class.java).size)
    }
}
