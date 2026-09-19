package com.monkfitness.app.domain.program

import com.monkfitness.app.domain.common.ProgramDayId
import com.monkfitness.app.domain.common.ProgramExerciseId
import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.common.RevisionId
import com.monkfitness.app.domain.prescription.RepPrescription
import com.monkfitness.app.domain.prescription.TimePrescription
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant

/**
 * The structural comparison is what decides whether a Save creates a revision, so this suite pins it
 * in both directions: every dimension §6 says *is* structural must show up as a difference, and
 * everything §6 says is *not* — a name, a description, a planned start date, a lifecycle, a selection,
 * an archive, and above all **identity** — must not.
 *
 * The identity half is the one worth stating out loud. A draft addresses its days and elements by id,
 * a saved revision re-identifies every one of them, and the editor's whole no-op guarantee rests on the
 * comparison ignoring that. [anIdentityIsNotStructure] is therefore the load-bearing test of the file.
 */
class ProgramStructureTest {

    // ------------------------------------------------------------------ identity is not content

    @Test
    fun anIdentityIsNotStructure() {
        val one = days(dayId = "day-1", elementIds = listOf("element-1", "element-2", "element-3"))
        val two = days(dayId = "day-77", elementIds = listOf("element-9", "element-8", "element-7"))

        assertEquals(
            "the same plan under different identities is the same structure — a save re-identifies " +
                "its plan and changes nothing else (§6, §23)",
            structure(one),
            structure(two)
        )
        assertTrue(structure(one).differencesFrom(structure(two)).isEmpty())
    }

    @Test
    fun positionsAreNotStructureEither() {
        val numbered = listOf(
            day(position = 1, dayId = "a", name = "Push day"),
            day(position = 2, dayId = "b", type = ProgramDayType.REST, name = "Rest", elements = emptyList())
        )
        val renumbered = listOf(
            day(position = 5, dayId = "a", name = "Push day"),
            day(position = 9, dayId = "b", type = ProgramDayType.REST, name = "Rest", elements = emptyList())
        )

        assertEquals(
            "the order is the structure, the numbering is a consequence of it",
            structure(numbered),
            structure(renumbered)
        )
    }

    @Test
    fun aRevisionAndADraftOpenedFromItHaveTheSameStructure() {
        val revision = revision(days())
        val draft = ProgramEditorDraft(
            programId = revision.programId,
            baseRevisionId = revision.revisionId,
            name = "typed but not saved",
            mode = revision.mode,
            duration = revision.duration,
            schedule = revision.schedule,
            days = revision.days
        )

        assertEquals(revision.structure, draft.structure)
        assertEquals(
            "a draft that was opened and not edited has nothing structural to save (§7)",
            emptyList<ProgramStructureAspect>(),
            draft.structure.differencesFrom(revision.structure)
        )
    }

    // ------------------------------------------------------------------ every dimension §6 names

    @Test
    fun theVocabularyIsEveryStructuralDimensionOfSectionSix() {
        assertEquals(
            "§6's list of what creates a revision, grouped as the editor presents it",
            listOf("MODE", "DURATION", "SCHEDULE", "DAYS", "EXERCISES", "PRESCRIPTIONS", "PINNING"),
            ProgramStructureAspect.entries.map { it.name }
        )
    }

    @Test
    fun eachStructuralDimensionIsReportedAsItsOwnDifference() {
        val base = structure(days())

        assertEquals(
            listOf(ProgramStructureAspect.MODE),
            structure(days(), mode = ProgramMode.GENERATED).differencesFrom(base)
        )
        assertEquals(
            listOf(ProgramStructureAspect.DURATION),
            structure(days(), duration = ProgramDuration.FixedDays(30)).differencesFrom(base)
        )
        assertEquals(
            listOf(ProgramStructureAspect.SCHEDULE),
            structure(days(), schedule = ProgramSchedule.FixedWeekdays(setOf(DayOfWeek.TUESDAY)))
                .differencesFrom(base)
        )
        assertEquals(
            "the day list — how many, in what order, of which type, under which names",
            listOf(ProgramStructureAspect.DAYS),
            structure(days() + day(position = 3, dayId = "day-3", type = ProgramDayType.REST, elements = emptyList()))
                .differencesFrom(base)
        )
        assertEquals(
            "the day's own name is content too: it is a column of the revision's plan",
            listOf(ProgramStructureAspect.DAYS),
            structure(days().map { it.copy(name = "Renamed") }).differencesFrom(base)
        )
        assertEquals(
            listOf(ProgramStructureAspect.DAYS),
            structure(days().map { it.copy(type = ProgramDayType.MOBILITY) }).differencesFrom(base)
        )
        assertEquals(
            "reordering the days is a structural change (§6: ordering)",
            listOf(ProgramStructureAspect.DAYS),
            structure(days().reversed()).differencesFrom(base)
        )
        assertEquals(
            listOf(ProgramStructureAspect.EXERCISES),
            structure(replacingElement(exerciseId = "knee_pushup")).differencesFrom(base)
        )
        assertEquals(
            "reordering the elements of a day is a structural change too",
            listOf(ProgramStructureAspect.EXERCISES),
            structure(
                listOf(
                    day(
                        position = 1,
                        dayId = "day-1",
                        elements = listOf(
                            element("element-1", "pushup"),
                            element("element-2", "pike_pushup")
                        )
                    )
                ).map { it.copy(exercises = it.exercises.reversed()) }
            ).differencesFrom(
                structure(
                    listOf(
                        day(
                            position = 1,
                            dayId = "day-1",
                            elements = listOf(
                                element("element-1", "pushup"),
                                element("element-2", "pike_pushup")
                            )
                        )
                    )
                )
            )
        )
        assertEquals(
            "who authored an element is content: it is what reconciliation keeps or replaces (§7)",
            listOf(ProgramStructureAspect.EXERCISES),
            structure(replacingElement(origin = ProgramExerciseOrigin.GENERATED)).differencesFrom(base)
        )
        assertEquals(
            listOf(ProgramStructureAspect.PRESCRIPTIONS),
            structure(replacingElement(prescription = RepPrescription(listOf(9, 9, 9, 9))))
                .differencesFrom(base)
        )
        assertEquals(
            listOf(ProgramStructureAspect.PINNING),
            structure(replacingElement(isPinned = true)).differencesFrom(base)
        )
    }

    @Test
    fun severalDimensionsAtOnceAreReportedInAFixedOrder() {
        val base = structure(days())
        val changed = structure(
            days = days().map { day ->
                day.copy(
                    exercises = day.exercises.map {
                        it.copy(prescription = RepPrescription(listOf(1)), isPinned = true)
                    }
                )
            },
            mode = ProgramMode.GENERATED,
            schedule = ProgramSchedule.FlexiblePerWeek(5)
        )

        assertEquals(
            "a Review screen lists the dimensions in the declaration's order, once each",
            listOf(
                ProgramStructureAspect.MODE,
                ProgramStructureAspect.SCHEDULE,
                ProgramStructureAspect.PRESCRIPTIONS,
                ProgramStructureAspect.PINNING
            ),
            changed.differencesFrom(base)
        )
        assertEquals(
            "and a difference is symmetric",
            changed.differencesFrom(base),
            base.differencesFrom(changed)
        )
    }

    // ------------------------------------------------------------------ the plan's own totals

    @Test
    fun theTotalsCountThePlanTheSameWayTheReviewPresentsIt() {
        val plan = structure(days())

        assertEquals(2, plan.dayCount)
        assertEquals("a rest day is not a work day", 1, plan.days.count { !it.isWorkDay })
        assertEquals("three occurrences, one of them a repeat", 3, plan.exerciseCount)
        assertEquals(
            "a repeated occurrence counts twice — it is two elements (§9)",
            2,
            plan.days.first().exercises.count { it.exerciseId == "pushup" }
        )
        assertEquals(
            "sets are counted per prescription and per dimension, never added across dimensions",
            4 + 2 + 1,
            plan.setCount
        )
        assertFalse("a manual plan may prescribe any sets it likes (§10)", plan.days.isEmpty())
        assertNotEquals(plan.dayCount, plan.exerciseCount)
    }

    @Test
    fun bothPrescriptionDimensionsAreCarriedWholeAndNotFlattened() {
        val plan = listOf(
            day(
                position = 1,
                dayId = "day-1",
                elements = listOf(
                    element("element-1", "plank", TimePrescription(listOf(30, 30, 45))),
                    element("element-2", "pushup", RepPrescription(listOf(12, 10, 8, 6)))
                )
            )
        )
        val planStructure = structure(plan)

        assertEquals(
            "`30s / 30s / 45s` is one prescription and `12 / 10 / 8 / 6` is another (§10)",
            listOf(30, 30, 45),
            planStructure.days.single().exercises.first().prescription.perSetTargets
        )
        assertEquals(
            "each element keeps its own set count, in its own dimension",
            listOf(3, 4),
            planStructure.days.single().exercises.map { it.prescription.setCount }
        )
        assertEquals(7, planStructure.setCount)
    }

    // ------------------------------------------------------------------ helpers

    /**
     * A two-day plan:
     *
     * ```text
     * day 1  TRAINING  pushup 12/10/8/6 (user), pike_pushup 8/8 (user), pushup 1 (user)
     * day 2  REST      no elements
     * ```
     *
     * The repeated `pushup` is deliberate: an occurrence is an element, and the structure has to say
     * so (§9).
     */
    private fun days(
        dayId: String = "day-1",
        elementIds: List<String> = listOf("element-1", "element-2", "element-3")
    ): List<ProgramDay> = listOf(
        day(
            position = 1,
            dayId = dayId,
            elements = listOf(
                element(elementIds[0], "pushup", RepPrescription(listOf(12, 10, 8, 6))),
                element(elementIds[1], "pike_pushup", RepPrescription(listOf(8, 8))),
                element(elementIds[2], "pushup", RepPrescription(listOf(1)))
            )
        ),
        day(position = 2, dayId = "$dayId-rest", type = ProgramDayType.REST, elements = emptyList())
    )

    private fun day(
        position: Int,
        dayId: String,
        type: ProgramDayType = ProgramDayType.TRAINING,
        name: String? = "Day $position",
        elements: List<ProgramExercise> = listOf(element("element-$position"))
    ): ProgramDay = ProgramDay(
        programDayId = ProgramDayId(dayId),
        position = position,
        type = type,
        name = name,
        exercises = elements
    )

    private fun element(
        id: String = "element-1",
        exerciseId: String = "pushup",
        prescription: com.monkfitness.app.domain.prescription.Prescription =
            RepPrescription(listOf(12, 10, 8, 6)),
        origin: ProgramExerciseOrigin = ProgramExerciseOrigin.USER_AUTHORED,
        isPinned: Boolean = false
    ): ProgramExercise = ProgramExercise(
        programExerciseId = ProgramExerciseId(id),
        exerciseId = exerciseId,
        prescription = prescription,
        origin = origin,
        isPinned = isPinned
    )

    /** The plan with one dimension of every element replaced, to isolate one aspect. */
    private fun replacingElement(
        plan: List<ProgramDay> = days(),
        exerciseId: String? = null,
        prescription: com.monkfitness.app.domain.prescription.Prescription? = null,
        origin: ProgramExerciseOrigin? = null,
        isPinned: Boolean? = null
    ): List<ProgramDay> = plan.map { day ->
        day.copy(
            exercises = day.exercises.map { current ->
                current.copy(
                    exerciseId = exerciseId ?: current.exerciseId,
                    prescription = prescription ?: current.prescription,
                    origin = origin ?: current.origin,
                    isPinned = isPinned ?: current.isPinned
                )
            }
        )
    }

    private fun structure(
        days: List<ProgramDay>,
        mode: ProgramMode = ProgramMode.MANUAL,
        duration: ProgramDuration = ProgramDuration.FixedDays(28),
        schedule: ProgramSchedule = ProgramSchedule.FlexiblePerWeek(3)
    ): ProgramStructure = ProgramStructure.of(mode, duration, schedule, days)

    private fun revision(days: List<ProgramDay>): ProgramRevision = ProgramRevision(
        revisionId = RevisionId("revision-1"),
        programId = ProgramId("program-1"),
        revisionNumber = 1,
        mode = ProgramMode.MANUAL,
        duration = ProgramDuration.FixedDays(28),
        schedule = ProgramSchedule.FlexiblePerWeek(3),
        days = days,
        createdAt = Instant.parse("2026-09-19T08:00:00Z")
    )
}
