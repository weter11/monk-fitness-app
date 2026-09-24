package com.monkfitness.app.bootstrap

import com.monkfitness.app.data.repository.ProgramDataAccessRig
import com.monkfitness.app.data.repository.ProgramGraphFixture
import com.monkfitness.app.di.Clock
import com.monkfitness.app.di.IdGenerator
import com.monkfitness.app.domain.program.ProgramOperationResult
import com.monkfitness.app.domain.program.ProgramSource
import com.monkfitness.app.domain.program.StandardProgram
import com.monkfitness.app.domain.usecase.ProgramLifecycleService
import com.monkfitness.app.domain.usecase.ProgramScheduler
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StandardProgramBootstrapIntegrationTest {
    @Test
    fun `production path copies Standard and falls back to it after deleting a selected user Program`() = runBlocking {
        val rig = ProgramDataAccessRig("standard-lifecycle")
        try {
            val clock = Clock { Instant.parse("2026-09-24T10:00:00Z") }
            val ids = generateSequence(0) { it + 1 }.map { "standard-$it" }.iterator()
            val scheduler = ProgramScheduler(
                rig.programRepository, rig.programPlanRepository, rig.programScheduleRepository,
                clock, IdGenerator { ids.next() }, ZoneOffset.UTC, rig.transaction
            )
            val bootstrap = StandardProgramBootstrap(
                rig.programRepository, scheduler, clock, IdGenerator { ids.next() }, ZoneOffset.UTC
            )
            bootstrap.bootstrap()
            val lifecycle = ProgramLifecycleService(
                rig.programRepository,
                rig.programScheduleRepository,
                rig.workoutSessionRepository,
                rig.appStateRepository,
                rig.programPlanRepository,
                clock,
                IdGenerator { ids.next() },
                StandardProgram.programId,
                rig.transaction
            )

            val copy = lifecycle.copyProgram(StandardProgram.programId, "My Standard Copy")
            val copied = (copy as ProgramOperationResult.Success<com.monkfitness.app.domain.program.Program>).value
            assertTrue(copied.programId != StandardProgram.programId)
            assertEquals(ProgramSource.USER, copied.source)
            assertEquals(4, rig.programRepository.programWithCurrentRevision(copied.programId)!!.currentRevision.days.size)

            val userA = ProgramGraphFixture.graph("a").program.programId
            val userB = ProgramGraphFixture.graph("b").program.programId
            rig.programRepository.createProgram(
                ProgramGraphFixture.graph("a").program,
                ProgramGraphFixture.graph("a").revision,
                ProgramGraphFixture.graph("a").slots
            )
            rig.programRepository.createProgram(
                ProgramGraphFixture.graph("b").program,
                ProgramGraphFixture.graph("b").revision,
                ProgramGraphFixture.graph("b").slots
            )
            lifecycle.selectProgram(userA)
            lifecycle.deleteProgram(userA)

            assertEquals(StandardProgram.programId, rig.appStateRepository.state()!!.selectedProgramId)
            assertEquals(true, rig.programRepository.programById(StandardProgram.programId) != null)
            assertEquals(null, rig.programRepository.programById(userA))
            assertEquals(true, rig.programRepository.programById(userB) != null)
        } finally {
            rig.close()
        }
    }
}
