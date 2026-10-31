package com.monkfitness.app.data.repository

import com.monkfitness.app.data.local.ProgramSetLogDao
import com.monkfitness.app.data.local.ProgramWorkoutSlotDao
import com.monkfitness.app.data.local.WorkoutSessionDao
import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.common.SessionId
import com.monkfitness.app.domain.program.SlotStatus
import com.monkfitness.app.domain.workout.SessionStatus

/**
 * The persistence primitives Progress/History reads: how many of a Program's slots and sessions ended
 * in each way, how many sets were confirmed, and which sessions it has in order. No measure is computed
 * here — completed/missed/upcoming, volume, distributions, duration, progression and streaks are
 * computations over this data, and several need vocabularies this stage does not own.
 *
 * One prohibition shapes even the primitives: there is **no scalar amount of work** here. Confirmed
 * repetitions are not comparable across exercises or dimensions, so a "total repetitions" or "volume"
 * number would be an invented metric — the reads count *rows* (sets, sessions, slots), and a missed
 * opportunity is a count of missed slots, not a workout that scored zero.
 */
class ProgramProgressRepository(
    private val slotDao: ProgramWorkoutSlotDao,
    private val sessionDao: WorkoutSessionDao,
    private val setLogDao: ProgramSetLogDao
) {

    /**
     * How many of one Program's slots hold each [SlotStatus], every status included with a zero when
     * none does — a census of rows, never a performance measure.
     */
    suspend fun slotStatusCounts(programId: ProgramId): Map<SlotStatus, Int> =
        SlotStatus.entries.associateWith { status ->
            slotDao.countByStatus(programId.value, status.name)
        }

    /**
     * How many of one Program's sessions hold each [SessionStatus], every status included with a zero
     * when none does. Cancelled sessions are counted as cancelled, never folded into completed.
     */
    suspend fun sessionStatusCounts(programId: ProgramId): Map<SessionStatus, Int> =
        SessionStatus.entries.associateWith { status ->
            sessionDao.countByStatus(programId.value, status.name)
        }

    /** How many sets one Program's sessions have confirmed. A count of stored rows, not a volume. */
    suspend fun confirmedSetCount(programId: ProgramId): Int =
        setLogDao.countSetsOfProgram(programId.value)

    /** One Program's session identities, oldest first. */
    suspend fun sessionIdsOf(programId: ProgramId): List<SessionId> =
        sessionDao.sessionsOfProgram(programId.value).map { SessionId(it.sessionId) }
}
