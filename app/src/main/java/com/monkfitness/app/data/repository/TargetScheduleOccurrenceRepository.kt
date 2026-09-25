package com.monkfitness.app.data.repository

import com.monkfitness.app.data.local.ProgramTargetOccurrenceDao
import com.monkfitness.app.data.mapper.toComponentEntities
import com.monkfitness.app.data.mapper.toDomain
import com.monkfitness.app.data.mapper.toEntity
import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.program.target.PersistedTargetOccurrence
import com.monkfitness.app.domain.program.target.TargetOccurrencePersistenceException

/**
 * The target occurrence's own persistence and read-back (§30 step 14).
 *
 * A persisted target slot remembers only `targetOccurrenceKey`; this repository is where the rest
 * of that occurrence lives. It stores a whole [PersistedTargetOccurrence] and gives one back, and the
 * two directions are exact inverses: what [store] writes, [occurrenceOf] reproduces, including every
 * component field and the component order.
 *
 * ### Idempotency and refusal
 *
 * [store] is idempotent **only** when the persisted payload is identical. Writing the same
 * `(programId, occurrenceKey)` with the same payload writes nothing at all. Writing it with a
 * different payload — a changed `plannedFor`, a changed component identity, a changed component
 * order — is refused with
 * [TargetOccurrencePersistenceException.ConflictingSemanticPayload] and leaves the stored record
 * byte-for-byte as it was. There is no overwrite, no merge and no update statement anywhere in this
 * path, so "never silently overwritten" is a property of the code rather than a promise about it.
 *
 * ### What this class deliberately does not do
 *
 * It consults no scheduler, planner, resolver, composer, policy, presenter, `ProgramSchedule`,
 * `ProgramDay` or legacy table, and it holds no clock and no identity generator: nothing here reads
 * the device's date, mints an id, or reaches for ambient state. It stores what a caller decided and
 * reads back what was stored. Anything that would reconstruct an `ExistingOccurrence` — an execution
 * state, an actual result, a started-versus-cancelled reading — is absent by design, because
 * `WorkoutSlot.status` does not encode it, attempts live in `WorkoutSession` and performed work lives
 * in the session graph. That belongs to a later phase with its own rules.
 *
 * ### Membership
 *
 * The membership identity is exactly `(programId, occurrenceKey)`. Two Programs may hold the same
 * key independently, one Program cannot hold it twice, and a stored row can never be moved to
 * another Program — the pair is the primary key, and there is no statement here that updates one.
 */
class TargetScheduleOccurrenceRepository(
    private val occurrenceDao: ProgramTargetOccurrenceDao
) {

    /**
     * Stores one occurrence's whole semantic payload, or refuses the write.
     *
     * The check-then-write is a read of the stored record followed, only when there is none, by the
     * inserts — so an identical repeat touches no row, and a conflicting repeat throws before it
     * writes anything. Both halves belong to the caller's transaction: this method never opens one of
     * its own, because the target slot and this occurrence are one unit and the caller is the layer
     * that knows that (§30 step 10's boundary).
     */
    suspend fun store(occurrence: PersistedTargetOccurrence) {
        val stored = occurrenceOf(occurrence.programId, occurrence.occurrenceKey)
        if (stored != null) {
            if (stored.hasSamePayloadAs(occurrence)) return
            throw TargetOccurrencePersistenceException.ConflictingSemanticPayload(
                programId = occurrence.programId,
                occurrenceKey = stored.occurrenceKey,
                storedPayload = stored.occurrence,
                requestedPayload = occurrence.occurrence
            )
        }
        occurrenceDao.insertOccurrences(listOf(occurrence.toEntity()))
        occurrenceDao.insertComponents(occurrence.toComponentEntities())
    }

    /**
     * The stored occurrence of one Program under one key, or `null` when there is none.
     *
     * Reconstructed from the stored parent row and the stored component rows and nothing else. The
     * key is used only to *find* the record; it is never read for a value, and the components are
     * never taken from a slot, a plan day, a date or the key's own text.
     */
    suspend fun occurrenceOf(
        programId: ProgramId,
        occurrenceKey: String
    ): PersistedTargetOccurrence? {
        val stored = occurrenceDao.occurrenceOf(programId.value, occurrenceKey) ?: return null
        return stored.toDomain(occurrenceDao.componentsOf(programId.value, occurrenceKey))
    }

    /**
     * Every stored target occurrence of one Program, earliest planned date first, key as tiebreak.
     *
     * The order is a **read** order over stored `plannedFor` values, not a decision about which
     * occurrences a Program ought to have: no horizon, no limit and no generation happens here.
     */
    suspend fun occurrencesOfProgram(programId: ProgramId): List<PersistedTargetOccurrence> =
        occurrenceDao.occurrencesOfProgram(programId.value).map { stored ->
            stored.toDomain(occurrenceDao.componentsOf(programId.value, stored.occurrenceKey))
        }
}
