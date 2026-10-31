package com.monkfitness.app.data.repository

import com.monkfitness.app.data.local.ProgramDao
import com.monkfitness.app.data.local.ProgramDayDao
import com.monkfitness.app.data.local.ProgramExerciseDao
import com.monkfitness.app.data.local.ProgramRevisionDao
import com.monkfitness.app.data.local.ProgramWorkoutSlotDao
import com.monkfitness.app.data.mapper.revisionDomain
import com.monkfitness.app.data.mapper.toDomain
import com.monkfitness.app.data.mapper.toEntity
import com.monkfitness.app.data.mapper.toRows
import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.program.Program
import com.monkfitness.app.domain.program.ProgramRevision
import com.monkfitness.app.domain.program.WorkoutSlot

/**
 * The Program aggregate's persistence: its identity, its non-structural facts, its atomic creation and
 * its deletion.
 *
 * It is not a lifecycle layer: it does not start, pause, resume, complete, archive or select a Program,
 * does not choose a current revision, does not fall back to the Standard Program when a selection
 * disappears, and does not check whether a Program may be deleted — those are decisions made by the
 * layer that owns them.
 *
 * Three properties the callers depend on: creation is one transaction ([createProgram] writes the
 * Program, its first revision, that plan and the initial slots, or nothing — a Program always has a
 * current revision); deletion is the database's own `ON DELETE CASCADE`, never a hand-written graph,
 * because the schema is the authority on ownership; and failures are not absorbed — a missing row reads
 * as `null`, invalid persisted data throws from the mapper, and a database failure (a delete the
 * database refuses while `app_state.selectedProgramId` names the Program, included) reaches the caller.
 * Nothing here turns a failure into an empty list, a `null` or a `false`.
 *
 * @param inTransaction production passes `AppDatabase.withTransaction`; a test passes a block that
 *   commits or rolls back, which is how the all-or-nothing guarantee is provable on the JVM.
 */
class ProgramRepository(
    private val programDao: ProgramDao,
    private val revisionDao: ProgramRevisionDao,
    private val dayDao: ProgramDayDao,
    private val exerciseDao: ProgramExerciseDao,
    private val slotDao: ProgramWorkoutSlotDao,
    private val inTransaction: suspend (suspend () -> Unit) -> Unit
) {

    /** The Program with [programId], or `null` when none is stored. No revision is loaded. */
    suspend fun programById(programId: ProgramId): Program? =
        programDao.programById(programId.value)?.toDomain()

    /** Every stored Program, oldest first. */
    suspend fun programs(): List<Program> = programDao.programs().map { it.toDomain() }

    suspend fun countPrograms(): Int = programDao.countPrograms()

    /**
     * The Program together with the revision that currently describes its plan, or `null` when no
     * Program with [programId] is stored.
     *
     * `program.currentRevisionId` is a pointer, not a foreign key, so assembling the aggregate means
     * reading the revision the pointer names. A pointer naming a revision that is not stored is invalid
     * persisted data and fails loudly rather than loading as a Program without a plan.
     */
    suspend fun programWithCurrentRevision(programId: ProgramId): ProgramWithCurrentRevision? {
        val program = programById(programId) ?: return null
        val revision = revisionDao.revisionById(program.currentRevisionId.value)
            ?: throw IllegalStateException(
                "program '${program.programId.value}' points at revision " +
                    "'${program.currentRevisionId.value}', which is not stored; a Program always has " +
                    "a current revision (§23)"
            )
        return ProgramWithCurrentRevision(
            program = program,
            currentRevision = revisionDomain(
                revision = revision,
                dayRows = dayDao.daysOfRevision(revision.revisionId),
                exerciseRows = exerciseDao.exercisesOfRevision(revision.revisionId)
            )
        )
    }

    /**
     * Writes every mutable, non-structural fact of an existing Program: its name, description, source,
     * lifecycle, revision pointer, stamps, planned start, actual start and archive stamp.
     *
     * None of those is a structural change, which is why this method cannot touch a plan row. The row
     * must already exist: an update of a Program that was never created is a no-op in SQLite, so it is
     * only used with a value that came from [programById] or [createProgram].
     */
    suspend fun updateProgram(program: Program) {
        programDao.updateProgram(program.toEntity())
    }

    /**
     * Persists a Program, its first revision, that revision's plan and the initial slots as one unit.
     *
     * The caller decides what is created; this method only guarantees that it lands completely. The
     * checks below are structural rather than policy: the revision must belong to the Program and be the
     * one the Program points at (a Program always has a current revision), and every slot must present a
     * day of that revision for that Program — a slot the plan does not contain would otherwise be stored
     * as an opportunity to train something the Program does not plan.
     *
     * @throws IllegalArgumentException when the graph is not self-consistent.
     * @throws Exception whatever the DAOs throw — a constraint failure included — with the transaction
     *   rolled back, so no partial Program survives.
     */
    suspend fun createProgram(
        program: Program,
        firstRevision: ProgramRevision,
        initialSlots: List<WorkoutSlot> = emptyList()
    ) {
        require(firstRevision.programId == program.programId) {
            "the first revision must belong to the Program being created: program=" +
                "'${program.programId.value}' revision='${firstRevision.revisionId.value}'"
        }
        require(program.currentRevisionId == firstRevision.revisionId) {
            "a created Program points at the revision it is created with: program " +
                "'${program.programId.value}' points at '${program.currentRevisionId.value}' but the " +
                "revision being created is '${firstRevision.revisionId.value}' (§23, §27)"
        }
        val dayIds = firstRevision.days.map { it.programDayId }.toSet()
        val straySlot = initialSlots.firstOrNull { slot ->
            slot.programId != program.programId ||
                slot.revisionId != firstRevision.revisionId ||
                slot.programDayId !in dayIds
        }
        require(straySlot == null) {
            "every initial slot must present a day of the revision being created: slot " +
                "'${straySlot?.slotId?.value}' of program '${straySlot?.programId?.value}' names day " +
                "'${straySlot?.programDayId?.value}' of revision '${straySlot?.revisionId?.value}'"
        }

        val rows = firstRevision.toRows()
        inTransaction {
            programDao.insertProgram(program.toEntity())
            revisionDao.insertRevision(rows.revision)
            if (rows.days.isNotEmpty()) dayDao.insertDays(rows.days)
            if (rows.exercises.isNotEmpty()) exerciseDao.insertExercises(rows.exercises)
            if (initialSlots.isNotEmpty()) slotDao.insertSlots(initialSlots.map { it.toEntity() })
        }
    }

    /**
     * Deletes one Program and everything it owns, through the schema's cascade.
     *
     * A delete is not refused by this method: whether deleting this Program is *allowed* — "no Program
     * may be deleted with an `IN_PROGRESS` session" — is a decision of the layer that can see the
     * sessions and the lifecycle. What this method does not do is make the delete succeed at any cost:
     * when `app_state.selectedProgramId` still names this Program the database refuses the delete
     * (`ON DELETE NO ACTION`), and that failure is reported to the caller rather than worked around by
     * clearing the selection here.
     */
    suspend fun deleteProgram(programId: ProgramId) {
        programDao.deleteProgram(programId.value)
    }
}

/**
 * A Program together with the revision that currently describes its plan. It is a data-layer holder of
 * two domain values, not a third model: nothing is derived, and the two members are exactly what was
 * stored.
 */
data class ProgramWithCurrentRevision(
    val program: Program,
    val currentRevision: ProgramRevision
)
