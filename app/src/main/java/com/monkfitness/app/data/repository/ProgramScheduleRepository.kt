package com.monkfitness.app.data.repository

import com.monkfitness.app.data.local.ProgramPauseDao
import com.monkfitness.app.data.local.ProgramWorkoutSlotDao
import com.monkfitness.app.data.local.SessionAttemptRow
import com.monkfitness.app.data.local.WorkoutSessionDao
import com.monkfitness.app.data.mapper.toDomain
import com.monkfitness.app.data.model.ProgramWorkoutSlotEntity
import com.monkfitness.app.data.mapper.toEntity
import com.monkfitness.app.domain.common.PauseId
import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.common.RevisionId
import com.monkfitness.app.domain.common.SessionId
import com.monkfitness.app.domain.common.SlotId
import com.monkfitness.app.domain.program.ProgramPause
import com.monkfitness.app.domain.program.SlotStatus
import com.monkfitness.app.domain.program.WorkoutSlot
import java.time.Instant
import java.time.LocalDate

/**
 * The schedule's persistence: the planned opportunities to train and a Program's pause intervals.
 *
 * A slot is an *opportunity*, never an amount of work (§20), and this class keeps it that way: reads
 * return identity, ownership, the planned date, the status, the completion stamp and the sessions that
 * attempted it, and [countSlots] counts rows with a status. There is no query here that could sum a
 * missed opportunity into a zero-valued workout, because the slot has nothing to sum (§12).
 *
 * What is deliberately absent is the entire set of scheduling decisions: this class does not generate
 * a date, does not decide whether a slot was missed, does not slide a schedule after a missed workout,
 * does not supersede a slot when a revision changes, does not extend an indefinite program's horizon
 * and does not decide how many slots a week holds. Those are the Scheduler's (§20); [addSlots] stores
 * the slots the Scheduler decided on and nothing more, and a status write records the outcome a caller
 * already determined.
 *
 * `attempts` on a slot is assembled from the sessions of that slot rather than read from the slot row,
 * because the schema keeps the link on `workout_session.slotId` and forbids a second copy of it
 * (§23). Pauses map one-to-one; [closePause] records that an interval ended, and whether a pause
 * should start or end is lifecycle (§3).
 */
class ProgramScheduleRepository(
    private val slotDao: ProgramWorkoutSlotDao,
    private val sessionDao: WorkoutSessionDao,
    private val pauseDao: ProgramPauseDao
) {

    // --- slots ------------------------------------------------------------------------------------

    /** The slot with [slotId] and the sessions attempted for it, or `null` when none is stored. */
    suspend fun slotById(slotId: SlotId): WorkoutSlot? {
        val slot = slotDao.slotById(slotId.value) ?: return null
        val attempts = sessionDao.sessionsOfSlot(slot.slotId).map { SessionId(it.sessionId) }
        return slot.toDomain(attempts)
    }

    /** Every slot of one Program, earliest planned date first, each with its attempts in start order. */
    suspend fun slotsOfProgram(programId: ProgramId): List<WorkoutSlot> =
        withAttempts(
            slots = slotDao.slotsOfProgram(programId.value),
            attempts = sessionDao.attemptsOfProgram(programId.value)
        )

    /** Every slot scheduled from one revision, earliest planned date first, with its attempts. */
    suspend fun slotsOfRevision(revisionId: RevisionId): List<WorkoutSlot> =
        withAttempts(
            slots = slotDao.slotsOfRevision(revisionId.value),
            attempts = sessionDao.attemptsOfRevision(revisionId.value)
        )

    /** The slots of one Program from [fromDate] on that currently hold [status], earliest first. */
    suspend fun slotsFrom(
        programId: ProgramId,
        fromDate: LocalDate,
        status: SlotStatus
    ): List<WorkoutSlot> = withAttempts(
        slots = slotDao.slotsFrom(programId.value, fromDate.toString(), status.name),
        attempts = sessionDao.attemptsOfProgram(programId.value)
    )

    /** How many slots of one Program hold [status]. A row count, not a performance measure. */
    suspend fun countSlots(programId: ProgramId, status: SlotStatus): Int =
        slotDao.countByStatus(programId.value, status.name)

    /**
     * Stores the slots a caller has decided on — the Scheduler's future opportunities, or a Program's
     * initial ones. Which slots should exist, and for which dates, is not this method's question.
     */
    suspend fun addSlots(slots: List<WorkoutSlot>) {
        if (slots.isEmpty()) return
        slotDao.insertSlots(slots.map { it.toEntity() })
    }

    /**
     * Records what happened to one slot. The status and the stamp are the caller's decision: this
     * method never derives them from the slot's sessions or from the date (§19, §20).
     */
    suspend fun recordSlotOutcome(slotId: SlotId, status: SlotStatus, completedAt: Instant?) {
        slotDao.updateOutcome(slotId.value, status.name, completedAt?.toEpochMilli())
    }

    /**
     * The domain slots of one row set, each with the sessions attempted for it.
     *
     * The attempts arrive as one read for the whole set and are grouped here rather than queried per
     * slot: the link lives on the session rows (§23), so a slot's attempts are exactly the sessions that
     * named it, in start order.
     */
    private fun withAttempts(
        slots: List<ProgramWorkoutSlotEntity>,
        attempts: List<SessionAttemptRow>
    ): List<WorkoutSlot> {
        val attemptsBySlot = attempts
            .groupBy { it.slotId }
            .mapValues { (_, rows) -> rows.map { SessionId(it.sessionId) } }
        return slots.map { it.toDomain(attemptsBySlot[it.slotId].orEmpty()) }
    }

    // --- pauses -----------------------------------------------------------------------------------

    /** The pause interval with [pauseId], or `null` when none is stored. */
    suspend fun pauseById(pauseId: PauseId): ProgramPause? = pauseDao.pauseById(pauseId.value)?.toDomain()

    /** Every pause interval of one Program, earliest start first. */
    suspend fun pausesOfProgram(programId: ProgramId): List<ProgramPause> =
        pauseDao.pausesOfProgram(programId.value).map { it.toDomain() }

    /** Stores one pause interval. Whether a pause should begin is lifecycle (§3), not a choice here. */
    suspend fun addPause(pause: ProgramPause) {
        pauseDao.insertPause(pause.toEntity())
    }

    /** Records when an interval ended. The interval itself is not rewritten or removed. */
    suspend fun closePause(pauseId: PauseId, endedAt: Instant) {
        pauseDao.closePause(pauseId.value, endedAt.toEpochMilli())
    }
}
