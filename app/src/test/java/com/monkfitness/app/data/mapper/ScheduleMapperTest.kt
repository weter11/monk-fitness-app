package com.monkfitness.app.data.mapper

import com.monkfitness.app.data.model.ProgramWorkoutSlotEntity
import com.monkfitness.app.data.repository.ProgramGraphFixture
import com.monkfitness.app.domain.common.PauseId
import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.common.SessionId
import com.monkfitness.app.domain.program.ProgramPause
import com.monkfitness.app.domain.program.SlotStatus
import com.monkfitness.app.domain.program.WorkoutSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * `program_workout_slot` ⇄ `WorkoutSlot` and `program_pause` ⇄ `ProgramPause`.
 *
 * Two things are pinned here beyond the round trip: that a slot row has **no** column that could hold
 * an amount of work (§12), and that the attempts a slot reports are the sessions of that slot rather
 * than a second copy of the link.
 */
class ScheduleMapperTest {

    /**
     * The class's own fields: statics (the companion's constants) and the synthetic members Kotlin adds
     * are not part of what a row stores.
     */
    private fun declaredFieldsOf(type: Class<*>) = type.declaredFields
        .filterNot { Modifier.isStatic(it.modifiers) || it.name.startsWith("$") }
        .map { it.name }

    private val open: WorkoutSlot = ProgramGraphFixture.slots("m").first()

    private val completed: WorkoutSlot = open.copy(
        status = SlotStatus.COMPLETED,
        attempts = listOf(SessionId("session-1"), SessionId("session-2")),
        completedAt = ProgramGraphFixture.FINISHED
    )

    @Test
    fun anOpenSlotRoundTripsWithNoAttemptsAndNoStamp() {
        val entity = open.toEntity()

        assertNull("an open opportunity has not happened", entity.completedAt)
        assertEquals(open, entity.toDomain(emptyList()))
        assertEquals(entity, entity.toDomain(emptyList()).toEntity())
    }

    @Test
    fun aLegacySlotKeepsANullTargetOccurrenceKey() {
        val entity = open.toEntity()

        assertNull("a legacy row has no invented target identity", entity.targetOccurrenceKey)
        assertNull(entity.toDomain(emptyList()).targetOccurrenceKey)
    }

    @Test
    fun aTargetOccurrenceKeyRoundTripsWithoutTransformation() {
        val target = open.copy(targetOccurrenceKey = "strength:2026-10-05")

        val entity = target.toEntity()

        assertEquals("strength:2026-10-05", entity.targetOccurrenceKey)
        assertEquals("strength:2026-10-05", entity.toDomain(emptyList()).targetOccurrenceKey)
    }

    @Test
    fun aBlankTargetOccurrenceKeyIsRefused() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            open.copy(targetOccurrenceKey = "  ")
        }

        assertTrue(failure.message!!.contains("targetOccurrenceKey"))
    }

    @Test
    fun aCompletedSlotRoundTripsWithItsAttemptsInStartOrder() {
        val entity = completed.toEntity()

        assertEquals(completed, entity.toDomain(completed.attempts))
        assertEquals(
            "several attempts are legal and none is dropped (§19)",
            listOf("session-1", "session-2"),
            entity.toDomain(completed.attempts).attempts.map { it.value }
        )
        assertEquals(ProgramGraphFixture.FINISHED.toEpochMilli(), entity.completedAt)
    }

    @Test
    fun theSlotRowStoresNoAmountOfWorkAndNoSecondCopyOfTheAttempts() {
        val columns = declaredFieldsOf(ProgramWorkoutSlotEntity::class.java)

        assertEquals(
            "the slot row is identity, ownership, date, status and stamp — nothing else",
            listOf(
                "slotId",
                "programId",
                "revisionId",
                "programDayId",
                "plannedFor",
                "status",
                "completedAt",
                "targetOccurrenceKey"
            ),
            columns
        )
        assertTrue(
            "a missed slot cannot be read as a workout that scored zero (§12)",
            columns.none { it.contains("rep", true) || it.contains("duration", true) ||
                it.contains("score", true) || it.contains("amount", true) || it.contains("progress", true) }
        )
    }

    @Test
    fun anUnknownStoredStatusIsRefusedRatherThanDefaulted() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            open.toEntity().copy(status = "SKIPPED").toDomain(emptyList())
        }

        assertTrue(failure.message!!.contains("program_workout_slot.status"))
        assertTrue(failure.message!!.contains("SUPERSEDED"))
    }

    @Test
    fun aCompletedSlotWithoutACompletionStampIsNotRepaired() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            open.toEntity().copy(status = SlotStatus.COMPLETED.name, completedAt = null).toDomain(emptyList())
        }

        assertTrue(
            "only a completed slot happened, and a completed slot says when: ${failure.message}",
            failure.message!!.contains("completedAt")
        )
    }

    @Test
    fun aCompletedSlotWithNoAttemptsIsNotRepairableByTheMapper() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            completed.toEntity().toDomain(emptyList())
        }

        assertTrue(
            "a slot is completed by a session, not by itself: ${failure.message}",
            failure.message!!.contains("completed by a session")
        )
    }

    @Test
    fun aMalformedPlannedDateIsRefusedRatherThanCoerced() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            open.toEntity().copy(plannedFor = "2026/09/21").toDomain(emptyList())
        }

        assertTrue(failure.message!!.contains("program_workout_slot.plannedFor"))
    }

    @Test
    fun anOpenAndAClosedPauseBothRoundTrip() {
        val openPause = ProgramPause(PauseId("pause-1"), ProgramId("program-m"), ProgramGraphFixture.CREATED)
        val closedPause = openPause.copy(endedAt = ProgramGraphFixture.FINISHED)

        assertEquals(openPause, openPause.toEntity().toDomain())
        assertEquals(closedPause, closedPause.toEntity().toDomain())
        assertNull("an open interval is open, not zero-length", openPause.toEntity().endedAt)
        assertEquals("pause-1", openPause.toEntity().pauseId)
    }

    @Test
    fun aPauseThatEndsBeforeItStartedIsNotRepaired() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            ProgramPause(
                PauseId("pause-1"),
                ProgramId("program-m"),
                ProgramGraphFixture.CREATED
            ).toEntity().copy(endedAt = ProgramGraphFixture.CREATED.minusSeconds(60).toEpochMilli()).toDomain()
        }

        assertTrue(failure.message!!.contains("cannot end before it started"))
    }
}
