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
 * The plan's persistence. A revision is immutable history, enforced here by what is absent: nothing
 * updates or deletes a revision, day or element. A structural change is a *new* revision with a new
 * identity, so the one it replaces keeps describing exactly what a past session ran under.
 *
 * [saveNewRevision] is the primitive and nothing more: it inserts rows and moves the Program's
 * `currentRevisionId` in the same transaction (a revision saved but not pointed at would map to a plan
 * nobody can see). It never reconciles future slots or reschedules — that is the Scheduler's and the
 * Editor's work. A missing row is `null`, invalid persisted data throws from the mapper, and a database
 * failure propagates untouched.
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
     * stored. The pointer is read first and the revision it names second: choosing a revision by
     * number, date or "the newest one" would be a decision this layer does not make.
     */
    suspend fun currentRevision(programId: ProgramId): ProgramRevision? {
        val program = programDao.programById(programId.value) ?: return null
        return revisionById(RevisionId(program.currentRevisionId))
    }

    /**
     * Every revision of one Program, in revision-number order. Earlier revisions come back alongside
     * the current one on purpose: they are the plan a past session ran under.
     */
    suspend fun revisionsOf(programId: ProgramId): List<ProgramRevision> =
        revisionDao.revisionsOfProgram(programId.value).map { revision ->
            revisionDomain(
                revision = revision,
                dayRows = dayDao.daysOfRevision(revision.revisionId),
                exerciseRows = exerciseDao.exercisesOfRevision(revision.revisionId)
            )
        }

    suspend fun countRevisionsOf(programId: ProgramId): Int =
        revisionDao.countRevisionsOf(programId.value)

    /**
     * Saves a revision as new rows and makes it the Program's current plan, in one transaction. Both
     * halves are required: the rows alone would leave the Program pointing at the plan it replaced, and
     * the pointer alone would name a revision that is not stored. The `updatedAt` stamp is the caller's
     * — a clock is injected, never read here.
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
