package com.monkfitness.app.data.repository

import com.monkfitness.app.data.local.ProgramDao
import com.monkfitness.app.data.local.ProgramDayDao
import com.monkfitness.app.data.local.ProgramExerciseDao
import com.monkfitness.app.data.local.ProgramRevisionDao
import com.monkfitness.app.data.mapper.revisionDomain
import com.monkfitness.app.data.mapper.toEntity
import com.monkfitness.app.data.mapper.toRows
import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.common.RevisionId
import com.monkfitness.app.domain.program.ProgramRevision
import java.time.Instant

/**
 * The plan's persistence: reading a revision's structure and saving a new one.
 *
 * A revision is immutable history (§6), and this class is where that rule is enforced at the storage
 * level rather than promised: there is no method that updates a revision, no method that update-writes
 * a plan day or a plan element, and none that deletes one. A structural change is a *new* revision
 * with a new identity, written by [saveNewRevision] as new rows, and the revision it replaces keeps
 * describing exactly what it described — which is what lets a session started under it stay explained
 * (§19).
 *
 * [saveNewRevision] is the primitive and nothing more. It does not reconcile future slots, does not
 * decide which slots a new plan supersedes, does not reschedule anything and does not touch a slot at
 * all: future-slot reconciliation is the Scheduler's and the Editor's work (§20) and is explicitly not
 * this layer's. What it does do is keep the Program's `currentRevisionId` pointer true, in the same
 * transaction, because a revision that is saved but not pointed at would leave a Program mapping to a
 * plan nobody can see, and the pointer is a column of `program`, not a foreign key (§23).
 *
 * Failure handling is the same contract as everywhere in this layer: a missing row is `null`, invalid
 * persisted data throws from the mapper (an unknown token, a `FLEXIBLE_PER_WEEK` revision whose
 * frequency the version-8 schema has nowhere to store, a prescription in an unimplemented dimension),
 * and a database failure propagates untouched.
 *
 * @param inTransaction runs a block inside one database transaction; see [ProgramRepository].
 */
class ProgramPlanRepository(
    private val programDao: ProgramDao,
    private val revisionDao: ProgramRevisionDao,
    private val dayDao: ProgramDayDao,
    private val exerciseDao: ProgramExerciseDao,
    private val inTransaction: suspend (suspend () -> Unit) -> Unit
) {

    /** The revision with [revisionId] fully assembled, or `null` when none is stored. */
    suspend fun revisionById(revisionId: RevisionId): ProgramRevision? {
        val revision = revisionDao.revisionById(revisionId.value) ?: return null
        return revisionDomain(
            revision = revision,
            dayRows = dayDao.daysOfRevision(revision.revisionId),
            exerciseRows = exerciseDao.exercisesOfRevision(revision.revisionId)
        )
    }

    /**
     * The revision that currently describes [programId]'s plan, or `null` when no such Program is
     * stored.
     *
     * The pointer is read first and the revision it names second, in that order: choosing a revision
     * by number, by date or by "the newest one" would be a decision this layer does not make (§23 —
     * the revision the Program points at is the current one).
     */
    suspend fun currentRevision(programId: ProgramId): ProgramRevision? {
        val program = programDao.programById(programId.value) ?: return null
        return revisionById(RevisionId(program.currentRevisionId))
    }

    /**
     * Every revision of one Program, in revision-number order, each fully assembled.
     *
     * Earlier revisions are returned alongside the current one on purpose: they are the plan a past
     * session ran under, and reading them is what keeps a finished workout explainable.
     */
    suspend fun revisionsOf(programId: ProgramId): List<ProgramRevision> =
        revisionDao.revisionsOfProgram(programId.value).map { revision ->
            revisionDomain(
                revision = revision,
                dayRows = dayDao.daysOfRevision(revision.revisionId),
                exerciseRows = exerciseDao.exercisesOfRevision(revision.revisionId)
            )
        }

    /** How many revisions one Program has saved. */
    suspend fun countRevisionsOf(programId: ProgramId): Int =
        revisionDao.countRevisionsOf(programId.value)

    /**
     * Saves a revision as new rows and makes it the Program's current plan, in one transaction (§27).
     *
     * Both halves are required for the write to be meaningful: the revision rows alone would leave the
     * Program pointing at the plan it replaced, and the pointer alone would name a revision that is not
     * stored. The `updatedAt` stamp is the caller's, because when a plan changed is a fact the caller
     * owns (§26: a clock is injected, never read here).
     *
     * @throws Exception whatever the DAOs throw, with the transaction rolled back: a save that fails
     *   halfway leaves neither the new revision nor a moved pointer.
     */
    suspend fun saveNewRevision(revision: ProgramRevision, updatedAt: Instant) {
        val rows = revision.toRows()
        inTransaction {
            revisionDao.insertRevision(rows.revision)
            if (rows.days.isNotEmpty()) dayDao.insertDays(rows.days)
            if (rows.exercises.isNotEmpty()) exerciseDao.insertExercises(rows.exercises)
            programDao.setCurrentRevision(
                programId = revision.programId.value,
                revisionId = revision.revisionId.value,
                updatedAt = updatedAt.toEpochMilli()
            )
        }
    }
}
