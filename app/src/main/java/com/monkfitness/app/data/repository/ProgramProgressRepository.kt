package com.monkfitness.app.data.repository

import com.monkfitness.app.data.local.ProgramSetLogDao
import com.monkfitness.app.data.local.ProgramWorkoutSlotDao
import com.monkfitness.app.data.local.WorkoutSessionDao
import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.common.SessionId
import com.monkfitness.app.domain.program.SlotStatus
import com.monkfitness.app.domain.workout.SessionStatus

/**
 * The persistence primitives the Progress/History stage reads: how many of a Program's opportunities
 * ended in each way, how many of its sessions ended in each way, how many sets were confirmed, and
 * which sessions it has in order.
 *
 * ### What is deliberately absent
 *
 * §21 lists the measures progress will show — completed/missed/upcoming, frequency, volume, focus
 * distribution, family distribution, average session duration, performance progression, PRs, streak —
 * and **none of them is computed here**. They are computations over this data, they belong to §30 step
 * 9, and several of them need vocabularies this stage does not own (focuses, families, progression
 * relations). Computing them in a repository would make the data layer the analytics engine the
 * blueprint forbids (§24).
 *
 * One prohibition shapes what may appear even as a primitive: there is **no scalar amount of work**
 * anywhere in this class. Confirmed repetitions are not comparable across exercises or dimensions, so
 * a "total repetitions" or a "volume" number is exactly the invented metric §17 forbids — the reads
 * count *rows* (sets, sessions, slots), not work, and a missed opportunity is a count of missed slots
 * rather than a workout that scored zero (§12).
 *
 * Every read is scoped by Program, deterministic in its order, and returns either a count per
 * vocabulary value or an ordered identity list, so a later stage can page through the raw facts
 * without this layer having guessed what it wants to show.
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
     * when none does. Cancelled sessions are counted as cancelled, never folded into completed (§19).
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
