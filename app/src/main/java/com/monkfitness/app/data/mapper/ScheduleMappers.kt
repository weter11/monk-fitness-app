package com.monkfitness.app.data.mapper

import com.monkfitness.app.data.model.ProgramPauseEntity
import com.monkfitness.app.data.model.ProgramWorkoutSlotEntity
import com.monkfitness.app.domain.common.PauseId
import com.monkfitness.app.domain.common.ProgramDayId
import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.common.RevisionId
import com.monkfitness.app.domain.common.SessionId
import com.monkfitness.app.domain.common.SlotId
import com.monkfitness.app.domain.program.ProgramPause
import com.monkfitness.app.domain.program.SlotStatus
import com.monkfitness.app.domain.program.WorkoutSlot

/**
 * The scheduling rows ⇄ their domain values: one planned opportunity to train, and one pause interval.
 *
 * A slot carries an `attempts` list in the domain, and the slot table has no such column — by design
 * (§23): the link between a slot and its sessions is owned by `workout_session.slotId`, and a second
 * copy on the slot row could disagree with it. Reading therefore takes the attempts the repository
 * read from the sessions of that slot, in start order, and writing stores the slot's own columns only
 * — the sessions that make an attempt exist are written by the transaction that starts them.
 *
 * Nothing here decides a scheduling question: no date is generated, no status is derived from dates
 * or from sessions, no missed slot is inferred and no horizon is extended. The status and the
 * completion stamp are stored and returned as the schema's own `require` holds them
 * (`COMPLETED` ⇔ a completion stamp), and `every slot a program has` is answered by the repository's
 * ordered read rather than by this file (§20).
 *
 * A pause maps one-to-one: identity, owner, start and end. An open interval is `endedAt == null`, and
 * whether that agrees with the Program's lifecycle is a lifecycle rule owned by the layer that holds
 * both (§3), not a check performed here.
 */

/**
 * The domain slot of one stored slot row and the sessions attempted for it, in start order.
 */
internal fun ProgramWorkoutSlotEntity.toDomain(attempts: List<SessionId>): WorkoutSlot = WorkoutSlot(
    slotId = SlotId(slotId),
    programId = ProgramId(programId),
    revisionId = RevisionId(revisionId),
    programDayId = ProgramDayId(programDayId),
    plannedFor = storedDate("program_workout_slot.plannedFor", plannedFor),
    status = storedToken(status, SlotStatus.entries, "program_workout_slot.status"),
    attempts = attempts,
    completedAt = completedAt?.let { storedInstant("program_workout_slot.completedAt", it) }
)

/**
 * The stored representation of one slot. Its `attempts` are deliberately absent: they are the
 * sessions the slot was attempted with, stored in `workout_session`, and the schema forbids a second
 * copy of that link.
 */
internal fun WorkoutSlot.toEntity(): ProgramWorkoutSlotEntity = ProgramWorkoutSlotEntity(
    slotId = slotId.value,
    programId = programId.value,
    revisionId = revisionId.value,
    programDayId = programDayId.value,
    plannedFor = storedDateValue(plannedFor),
    status = status.name,
    completedAt = completedAt?.let { storedMilliseconds(it) }
)

/** The domain pause interval of one stored row. */
internal fun ProgramPauseEntity.toDomain(): ProgramPause = ProgramPause(
    pauseId = PauseId(pauseId),
    programId = ProgramId(programId),
    startedAt = storedInstant("program_pause.startedAt", startedAt),
    endedAt = endedAt?.let { storedInstant("program_pause.endedAt", it) }
)

/** The stored representation of one pause interval. */
internal fun ProgramPause.toEntity(): ProgramPauseEntity = ProgramPauseEntity(
    pauseId = pauseId.value,
    programId = programId.value,
    startedAt = storedMilliseconds(startedAt),
    endedAt = endedAt?.let { storedMilliseconds(it) }
)
