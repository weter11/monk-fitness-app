package com.monkfitness.app.data.mapper

import com.monkfitness.app.data.repository.ProgramGraphFixture
import com.monkfitness.app.domain.program.LifecycleStatus
import com.monkfitness.app.domain.program.Program
import com.monkfitness.app.domain.program.ProgramSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `program` row ⇄ `Program` mapper, pinned value by value.
 *
 * A round trip is asserted in both directions — domain → entity → domain and entity → domain → entity
 * — because the two are not the same claim: the first proves nothing is lost on the way to storage,
 * the second that a row written by an earlier build still reads back as the row it was.
 */
class ProgramMapperTest {

    private val program: Program = ProgramGraphFixture.program("m")

    @Test
    fun everyStoredFieldSurvivesTheRoundTripInBothDirections() {
        val entity = program.toEntity()

        assertEquals("domain → entity → domain", program, entity.toDomain())
        assertEquals("entity → domain → entity", entity, entity.toDomain().toEntity())
    }

    @Test
    fun aProgramWithNeitherAPlannedStartNorAnArchiveStampRoundTrips() {
        val notStarted = ProgramGraphFixture.program(
            "m",
            lifecycleStatus = LifecycleStatus.NOT_STARTED,
            plannedStartDate = null
        )
        val entity = notStarted.toEntity()

        assertNull("the planned start date is a plan, and absent is absent", entity.plannedStartDate)
        assertNull("a program that never started has no start stamp", entity.actualStartDate)
        assertEquals(notStarted, entity.toDomain())
    }

    @Test
    fun anArchivedProgramKeepsItsArchiveStampAndItsLifecycle() {
        val archived = program.copy(archivedAt = ProgramGraphFixture.FINISHED)
        val entity = archived.toEntity()

        assertEquals(
            "archiving is a stamp, not a lifecycle state (§29)",
            archived,
            entity.toDomain()
        )
        assertEquals(LifecycleStatus.RUNNING.name, entity.lifecycleStatus)
    }

    @Test
    fun everySourceAndEveryLifecycleTokenIsStoredAsItsOwnName() {
        val row = program.toEntity()

        ProgramSource.entries.forEach { source ->
            assertEquals("source $source", source, row.copy(source = source.name).toDomain().source)
        }
        listOf(
            LifecycleStatus.NOT_STARTED to null,
            LifecycleStatus.RUNNING to ProgramGraphFixture.STARTED,
            LifecycleStatus.PAUSED to ProgramGraphFixture.STARTED,
            LifecycleStatus.COMPLETED to ProgramGraphFixture.STARTED
        ).forEach { (status, started) ->
            assertEquals(
                "$status is stored by name and read back as itself",
                status,
                row.copy(lifecycleStatus = status.name, actualStartDate = started?.toEpochMilli())
                    .toDomain()
                    .lifecycleStatus
            )
        }
    }

    @Test
    fun theTypedIdentitiesAreReconstructedAsTheirOwnTypes() {
        val loaded = program.toEntity().toDomain()

        assertEquals(program.programId, loaded.programId)
        assertEquals(program.currentRevisionId, loaded.currentRevisionId)
        assertEquals("programId", "program-m", loaded.programId.value)
    }

    @Test
    fun anUnknownStoredSourceIsRefusedRatherThanDefaulted() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            program.toEntity().copy(source = "ARCHIVED_TEMPLATE").toDomain()
        }

        assertTrue(
            "the message names the column, the value and the vocabulary: ${failure.message}",
            failure.message!!.contains("program.source") &&
                failure.message!!.contains("ARCHIVED_TEMPLATE") &&
                failure.message!!.contains("STANDARD")
        )
    }

    @Test
    fun anUnknownStoredLifecycleIsRefusedRatherThanDefaulted() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            program.toEntity().copy(lifecycleStatus = "ACTIVE").toDomain()
        }

        assertTrue(
            "an unknown token is invalid data, not a default state: ${failure.message}",
            failure.message!!.contains("program.lifecycleStatus") && failure.message!!.contains("ACTIVE")
        )
    }

    @Test
    fun aRowWhoseLifecycleContradictsItsStampsIsNotRepaired() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            program.toEntity()
                .copy(lifecycleStatus = LifecycleStatus.NOT_STARTED.name)
                .toDomain()
        }

        assertTrue(
            "the domain constructor decides, the mapper does not rewrite the row: ${failure.message}",
            failure.message!!.contains("NOT_STARTED")
        )
    }

    @Test
    fun aMalformedPlannedStartDateIsRefusedRatherThanCoerced() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            program.toEntity().copy(plannedStartDate = "14.09.2026").toDomain()
        }

        assertTrue(
            "a stored date is ISO YYYY-MM-DD or invalid: ${failure.message}",
            failure.message!!.contains("program.plannedStartDate") &&
                failure.message!!.contains("14.09.2026")
        )
    }
}
