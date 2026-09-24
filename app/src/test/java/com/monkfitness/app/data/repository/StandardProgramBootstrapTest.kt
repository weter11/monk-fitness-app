package com.monkfitness.app.data.repository

import com.monkfitness.app.bootstrap.StandardProgramBootstrap
import com.monkfitness.app.di.Clock
import com.monkfitness.app.di.IdGenerator
import com.monkfitness.app.domain.program.LifecycleStatus
import com.monkfitness.app.domain.program.ProgramSource
import com.monkfitness.app.domain.program.StandardProgram
import com.monkfitness.app.domain.usecase.ProgramScheduler
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StandardProgramBootstrapTest {
    @Test
    fun `first bootstrap creates one planned Standard graph and a repeated bootstrap is inert`() = runBlocking {
        val rig = ProgramDataAccessRig("standard-bootstrap")
        try {
            val ids = sequenceOf("revision", *Array(4) { "day$it" }, *Array(20) { "exercise$it" }, *Array(17) { "slot$it" })
            val nextIds = ids.iterator()
            val clock = Clock { Instant.parse("2026-09-24T10:00:00Z") }
            val scheduler = ProgramScheduler(
                programRepository = rig.programRepository,
                planRepository = rig.programPlanRepository,
                scheduleRepository = rig.programScheduleRepository,
                clock = clock,
                idGenerator = IdGenerator { nextIds.next() },
                zone = ZoneOffset.UTC,
                inTransaction = rig.transaction
            )
            val bootstrap = StandardProgramBootstrap(
                programRepository = rig.programRepository,
                scheduler = scheduler,
                clock = clock,
                idGenerator = IdGenerator { nextIds.next() },
                zone = ZoneOffset.UTC
            )

            bootstrap.bootstrap()
            val first = rig.programRepository.programWithCurrentRevision(StandardProgram.programId)!!
            val firstRevisionId = first.currentRevision.revisionId
            val firstDayIds = first.currentRevision.days.map { it.programDayId }
            val firstExerciseIds = first.currentRevision.days.flatMap { day -> day.exercises.map { it.programExerciseId } }
            val firstSlotCount = rig.programScheduleRepository.slotsOfProgram(StandardProgram.programId).size

            assertEquals("Standard Program", first.program.name)
            assertEquals(ProgramSource.STANDARD, first.program.source)
            assertEquals(LifecycleStatus.NOT_STARTED, first.program.lifecycleStatus)
            assertEquals("2026-09-24", first.program.plannedStartDate.toString())
            assertNull(first.program.actualStartDate)
            assertEquals(4, first.currentRevision.days.size)
            assertEquals(20, first.currentRevision.days.sumOf { it.exercises.size })
            assertEquals(17, firstSlotCount)

            bootstrap.bootstrap()

            val second = rig.programRepository.programWithCurrentRevision(StandardProgram.programId)!!
            assertEquals(first.program, second.program)
            assertEquals(firstRevisionId, second.currentRevision.revisionId)
            assertEquals(firstDayIds, second.currentRevision.days.map { it.programDayId })
            assertEquals(firstExerciseIds, second.currentRevision.days.flatMap { day -> day.exercises.map { it.programExerciseId } })
            assertEquals(firstSlotCount, rig.programScheduleRepository.slotsOfProgram(StandardProgram.programId).size)
            assertEquals("1", rig.database.scalar("SELECT COUNT(*) FROM `program` WHERE `programId` = 'standard-program'"))
            assertEquals("1", rig.database.scalar("SELECT COUNT(*) FROM `program_revision` WHERE `programId` = 'standard-program'"))
            assertEquals("4", rig.database.scalar("SELECT COUNT(*) FROM `program_day` WHERE `programDayId` IN (SELECT `programDayId` FROM `program_day` WHERE `revisionId` = (SELECT `currentRevisionId` FROM `program` WHERE `programId` = 'standard-program'))"))
            assertEquals("20", rig.database.scalar("SELECT COUNT(*) FROM `program_exercise` WHERE `programDayId` IN (SELECT `programDayId` FROM `program_day` WHERE `revisionId` = (SELECT `currentRevisionId` FROM `program` WHERE `programId` = 'standard-program'))"))
        } finally {
            rig.close()
        }
    }
}
