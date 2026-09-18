package com.monkfitness.app.data.mapper

import com.monkfitness.app.data.model.ProgramRevisionEntity
import com.monkfitness.app.data.repository.ProgramGraphFixture
import com.monkfitness.app.domain.prescription.PrescriptionDimension
import com.monkfitness.app.domain.prescription.RepPrescription
import com.monkfitness.app.domain.prescription.TimePrescription
import com.monkfitness.app.domain.program.ProgramDuration
import com.monkfitness.app.domain.program.ProgramRevision
import com.monkfitness.app.domain.program.ProgramSchedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek

/**
 * The plan rows ⇄ `ProgramRevision`, pinned for the things a plan can actually get wrong: order,
 * occurrence identity, per-set prescriptions, the rest day, the two duration forms and the two
 * schedule forms.
 */
class PlanMapperTest {

    private val revision: ProgramRevision = ProgramGraphFixture.revision("m")

    private fun rows() = revision.toRows()

    private fun loaded() = rows().let { revisionDomain(it.revision, it.days, it.exercises) }

    @Test
    fun theWholePlanSurvivesTheRoundTrip() {
        val rows = rows()

        assertEquals("domain → rows → domain", revision, revisionDomain(rows.revision, rows.days, rows.exercises))
        assertEquals("rows → domain → rows", rows, loaded().toRows())
    }

    @Test
    fun aPerSetRepetitionPrescriptionIsNotCollapsedToAScalar() {
        val loaded = loaded()
        val element = loaded.days.single { it.position == 1 }.exercises.first()

        assertEquals(listOf(12, 10, 8, 6), (element.prescription as RepPrescription).perSetReps)
        assertEquals(4, element.prescription.setCount)
        assertEquals(
            "the stored targets are the four numbers, in set order",
            "12,10,8,6",
            rows().exercises.single { it.programExerciseId == "plan-ex-m-1" }.perSetTargets.joinToString(",")
        )
        assertEquals(6, element.prescription.targetForSet(4))
    }

    @Test
    fun aPerSetDurationPrescriptionIsNotCollapsedToAScalar() {
        val loaded = loaded()
        val element = loaded.days.single { it.position == 3 }.exercises.last()

        assertEquals(listOf(30, 30, 45), (element.prescription as TimePrescription).perSetSeconds)
        assertEquals(PrescriptionDimension.TIME_BASED, element.prescription.dimension)
        assertEquals(
            "a timed prescription is stored in its own dimension, next to its per-set seconds",
            "TIME_BASED",
            rows().exercises.single { it.programExerciseId == "plan-ex-m-5" }.prescriptionDimension
        )
    }

    @Test
    fun dayOrderIsRestoredFromStoredPositionsAndNotFromRowOrder() {
        val rows = rows()

        val shuffled = revisionDomain(rows.revision, rows.days.reversed(), rows.exercises)

        assertEquals(
            "the plan's order is data; a shuffled read does not reorder the plan",
            listOf(1, 2, 3),
            shuffled.days.map { it.position }
        )
        assertEquals(revision.days.map { it.programDayId }, shuffled.days.map { it.programDayId })
    }

    @Test
    fun elementOrderInsideADayIsRestoredFromStoredPositions() {
        val rows = rows()
        val dayThree = rows.days.single { it.position == 3 }.programDayId

        val loaded = revisionDomain(
            rows.revision,
            rows.days,
            rows.exercises.filterNot { it.programDayId == dayThree } +
                rows.exercises.filter { it.programDayId == dayThree }.reversed()
        )

        assertEquals(
            listOf("pushup", "pushup", "plank"),
            loaded.days.single { it.position == 3 }.exercises.map { it.exerciseId }
        )
    }

    @Test
    fun aRepeatedExerciseStaysTwoDistinctOccurrences() {
        val dayThree = loaded().days.single { it.position == 3 }

        assertEquals(listOf("pushup", "pushup", "plank"), dayThree.exercises.map { it.exerciseId })
        assertEquals(
            "one exercise used twice is two occurrences with two identities (§9)",
            3,
            dayThree.exercises.map { it.programExerciseId }.toSet().size
        )
        assertEquals(
            "nothing in the storage layer is unique on the exercise id",
            listOf(1, 2, 3),
            rows().exercises.filter { it.programDayId == dayThree.programDayId.value }.map { it.position }
        )
    }

    @Test
    fun aRestDayCarriesNoElementsAndStillExists() {
        val rest = loaded().days.single { it.position == 2 }

        assertEquals("REST", rest.type.name)
        assertTrue("a rest day prescribes nothing (§20)", rest.exercises.isEmpty())
        assertEquals(
            "and it has no element rows of its own",
            emptyList<String>(),
            rows().exercises.filter { it.programDayId == rest.programDayId.value }.map { it.exerciseId }
        )
    }

    @Test
    fun elementPositionsAreTheElementOrderInsideItsDay() {
        val rows = rows()

        rows.days.forEach { day ->
            assertEquals(
                "day ${day.position} stores its elements as 1..n",
                (1..rows.exercises.count { it.programDayId == day.programDayId }).toList(),
                rows.exercises.filter { it.programDayId == day.programDayId }.map { it.position }
            )
        }
    }

    @Test
    fun thePinAndTheOriginSurvive() {
        val element = loaded().days.single { it.position == 1 }.exercises.last()

        assertTrue("a pinned element stays pinned (§7)", element.isPinned)
        assertEquals("USER_AUTHORED", element.origin.name)
    }

    @Test
    fun aFixedDayRevisionKeepsItsLengthAndAnIndefiniteOneKeepsItsAbsenceOfALength() {
        val fixed = rows().revision
        assertEquals(ProgramDuration.FixedDays(30), revisionDomain(fixed, rows().days, rows().exercises).duration)

        val indefinite = fixed.copy(durationType = "INDEFINITE", durationDays = null)
        assertEquals(
            "an indefinite program has no total, and a fake one is not stored (§21)",
            ProgramDuration.Indefinite,
            revisionDomain(indefinite, rows().days, rows().exercises).duration
        )
        assertNull("an indefinite revision stores no day count", indefinite.durationDays)
        assertEquals(
            "and writing an indefinite revision stores no day count either",
            null,
            revision.copy(duration = ProgramDuration.Indefinite).toEntity().durationDays
        )
    }

    @Test
    fun fixedWeekdaysAreStoredByNameAndComeBackAsADaySet() {
        val rows = rows()

        assertEquals(
            setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY),
            rows.revision.scheduleWeekdays
        )
        assertEquals(
            ProgramSchedule.FixedWeekdays(setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)),
            revisionDomain(rows.revision, rows.days, rows.exercises).schedule
        )
    }

    @Test
    fun aFlexibleWeeklyFrequencyCrossesTheBoundaryLosslesslyInBothDirections() {
        ProgramRevisionEntity.SESSIONS_PER_WEEK_RANGE.forEach { frequency ->
            val schedule = ProgramSchedule.FlexiblePerWeek(frequency)
            val flexibleRevision = revision.copy(schedule = schedule)

            val stored = flexibleRevision.toRows().revision

            assertEquals("the form is stored as its own discriminator", "FLEXIBLE_PER_WEEK", stored.scheduleType)
            assertEquals("with the number it runs at, not a default", frequency, stored.scheduleSessionsPerWeek)
            assertNull("and with no weekday set: the two forms do not mix", stored.scheduleWeekdays)

            assertEquals(
                "rows → domain: $frequency sessions a week is read back as the same schedule",
                schedule,
                revisionDomain(stored, rows().days, rows().exercises).schedule
            )
            assertEquals(
                "rows → domain → rows is the identity, so nothing is lost or invented",
                flexibleRevision.toRows(),
                revisionDomain(stored, rows().days, rows().exercises).toRows()
            )
            assertEquals(
                "domain → rows → domain is the identity",
                flexibleRevision,
                revisionDomain(
                    flexibleRevision.toRows().revision,
                    flexibleRevision.toRows().days,
                    flexibleRevision.toRows().exercises
                )
            )
        }
    }

    @Test
    fun aWeekdayScheduleStoresNoFrequencyAndAnUnrepresentableFrequencyRowIsRefused() {
        val stored = revision.toEntity()

        assertEquals("FIXED_WEEKDAYS", stored.scheduleType)
        assertEquals(
            setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY),
            stored.scheduleWeekdays
        )
        assertNull("the weekday form has no frequency, and none is invented for it", stored.scheduleSessionsPerWeek)
        assertEquals(revision, revisionDomain(stored, rows().days, rows().exercises))

        val withoutAFrequency = assertThrows(IllegalArgumentException::class.java) {
            rows().revision.copy(
                scheduleType = "FLEXIBLE_PER_WEEK",
                scheduleWeekdays = null,
                scheduleSessionsPerWeek = null
            )
        }
        assertTrue(
            "a flexible-frequency revision without its frequency is not representable — the mapper " +
                "cannot invent the number §20 requires: ${withoutAFrequency.message}",
            withoutAFrequency.message!!.contains("sessions-per-week frequency")
        )

        val outOfRange = assertThrows(IllegalArgumentException::class.java) {
            rows().revision.copy(
                scheduleType = "FLEXIBLE_PER_WEEK",
                scheduleWeekdays = null,
                scheduleSessionsPerWeek = 9
            )
        }
        assertTrue(
            "and a stored frequency outside the domain's own range is refused, never clamped: " +
                "${outOfRange.message}",
            outOfRange.message!!.contains("1..7")
        )
    }

    @Test
    fun aPrescriptionInADimensionTheDomainDoesNotImplementCannotBeLoaded() {
        val element = rows().exercises.first()

        listOf(
            PrescriptionDimension.SET_BASED,
            PrescriptionDimension.DIFFICULTY_BASED,
            PrescriptionDimension.REST_BASED
        ).forEach { dimension ->
            val failure = assertThrows(IllegalArgumentException::class.java) {
                element.copy(prescriptionDimension = dimension.name).toDomain()
            }

            assertTrue(
                "${dimension.name} is named by §10 and has no subtype: the row is not loadable",
                failure.message!!.contains(dimension.name) &&
                    failure.message!!.contains("does not implement yet")
            )
        }
    }

    @Test
    fun anUnknownDimensionTokenIsRefused() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            rows().exercises.first().copy(prescriptionDimension = "REPS").toDomain()
        }

        assertTrue(failure.message!!.contains("prescriptionDimension"))
        assertTrue(failure.message!!.contains("REP_BASED"))
    }

    @Test
    fun anUnknownDayTypeOrOriginTokenIsRefused() {
        val rows = rows()

        val dayFailure = assertThrows(IllegalArgumentException::class.java) {
            rows.days.first().copy(type = "ACTIVE_RECOVERY").toDomain(emptyList())
        }
        val originFailure = assertThrows(IllegalArgumentException::class.java) {
            rows.exercises.first().copy(origin = "SYSTEM").toDomain()
        }

        assertTrue(dayFailure.message!!.contains("program_day.type"))
        assertTrue(originFailure.message!!.contains("program_exercise.origin"))
    }

    @Test
    fun anUnknownModeOrDurationDiscriminatorIsRefused() {
        val rows = rows()

        val modeFailure = assertThrows(IllegalArgumentException::class.java) {
            revisionDomain(rows.revision.copy(mode = "SEMI_AUTOMATIC"), rows.days, rows.exercises)
        }
        val durationFailure = assertThrows(IllegalArgumentException::class.java) {
            revisionDomain(rows.revision.copy(durationType = "FOREVER"), rows.days, rows.exercises)
        }

        assertTrue(modeFailure.message!!.contains("program_revision.mode"))
        assertTrue(durationFailure.message!!.contains("durationType"))
    }

    @Test
    fun anElementWhoseDayIsNotInTheRevisionIsRefusedInsteadOfDropped() {
        val rows = rows()
        val stray = rows.exercises.first().copy(programDayId = "day-somewhere-else")

        val failure = assertThrows(IllegalArgumentException::class.java) {
            revisionDomain(rows.revision, rows.days, rows.exercises + stray)
        }

        assertTrue(
            "a plan element outside the revision would silently shorten a plan: ${failure.message}",
            failure.message!!.contains("day-somewhere-else")
        )
    }

    @Test
    fun aRevisionWithoutItsDaysOrWithGapsIsRefused() {
        val rows = rows()

        val noDays = assertThrows(IllegalArgumentException::class.java) {
            revisionDomain(rows.revision, emptyList(), emptyList())
        }
        val withAGap = assertThrows(IllegalArgumentException::class.java) {
            revisionDomain(
                rows.revision,
                rows.days.filterNot { it.position == 2 },
                rows.exercises
            )
        }

        assertTrue(noDays.message!!.contains("carries a plan"))
        assertTrue(withAGap.message!!.contains("numbered 1.."))
    }

    @Test
    fun everyRevisionNumberAndCreatedStampSurvives() {
        val rows = rows()

        assertEquals(1, rows.revision.revisionNumber)
        assertEquals(
            ProgramGraphFixture.CREATED.toEpochMilli(),
            rows.revision.createdAt
        )
        assertEquals(ProgramGraphFixture.CREATED, revisionDomain(rows.revision, rows.days, rows.exercises).createdAt)
    }
}
