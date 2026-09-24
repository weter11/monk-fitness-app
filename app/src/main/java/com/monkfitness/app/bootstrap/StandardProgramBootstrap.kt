package com.monkfitness.app.bootstrap

import com.monkfitness.app.data.repository.ProgramRepository
import com.monkfitness.app.di.Clock
import com.monkfitness.app.di.IdGenerator
import com.monkfitness.app.domain.common.ProgramDayId
import com.monkfitness.app.domain.common.ProgramExerciseId
import com.monkfitness.app.domain.common.RevisionId
import com.monkfitness.app.domain.program.LifecycleStatus
import com.monkfitness.app.domain.program.Program
import com.monkfitness.app.domain.product.StandardProgramDefinition
import com.monkfitness.app.domain.program.ProgramDay
import com.monkfitness.app.domain.program.ProgramExercise
import com.monkfitness.app.domain.program.ProgramExerciseOrigin
import com.monkfitness.app.domain.program.ProgramRevision
import com.monkfitness.app.domain.program.ProgramSchedulingResult
import com.monkfitness.app.domain.program.ProgramSource
import com.monkfitness.app.domain.program.StandardProgram
import com.monkfitness.app.domain.usecase.ProgramScheduler
import java.time.ZoneId

/** Production bootstrap for the product-owned Standard Program. */
class StandardProgramBootstrap(
    private val programRepository: ProgramRepository,
    private val scheduler: ProgramScheduler,
    private val clock: Clock,
    private val idGenerator: IdGenerator,
    private val zone: ZoneId
) {
    suspend fun bootstrap() {
        if (programRepository.programById(StandardProgram.programId) != null) return

        val now = clock.now()
        val revisionId = RevisionId(idGenerator.newId())
        val revision = ProgramRevision(
            revisionId = revisionId,
            programId = StandardProgram.programId,
            revisionNumber = ProgramRevision.FIRST_REVISION_NUMBER,
            mode = StandardProgramDefinition.mode,
            duration = StandardProgramDefinition.duration,
            schedule = StandardProgramDefinition.schedule,
            days = StandardProgramDefinition.days.mapIndexed { index, definitionDay ->
                ProgramDay(
                    programDayId = ProgramDayId(idGenerator.newId()),
                    position = index + 1,
                    type = definitionDay.type,
                    name = definitionDay.name,
                    exercises = definitionDay.exercises.map { definitionExercise ->
                        ProgramExercise(
                            programExerciseId = ProgramExerciseId(idGenerator.newId()),
                            exerciseId = definitionExercise.exerciseId,
                            prescription = definitionExercise.prescription,
                            origin = ProgramExerciseOrigin.USER_AUTHORED
                        )
                    }
                )
            },
            createdAt = now
        )
        val program = Program(
            programId = StandardProgram.programId,
            name = StandardProgram.NAME,
            description = "",
            source = ProgramSource.STANDARD,
            lifecycleStatus = LifecycleStatus.NOT_STARTED,
            currentRevisionId = revisionId,
            createdAt = now,
            updatedAt = now,
            plannedStartDate = now.atZone(zone).toLocalDate(),
            actualStartDate = null,
            archivedAt = null
        )
        val slots = when (val result = scheduler.initialSlotsFor(program, revision)) {
            is ProgramSchedulingResult.Success -> result.value
            is ProgramSchedulingResult.Refused -> error("Standard Program scheduling refused: ${result.reason}")
            is ProgramSchedulingResult.Failure -> throw result.cause
        }
        programRepository.createProgram(program, revision, slots)
    }
}
