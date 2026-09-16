package com.monkfitness.app.data.repository

import com.monkfitness.app.data.local.AdaptiveDecisionHistoryDao
import com.monkfitness.app.data.local.FamilyProgressionStateDao
import com.monkfitness.app.data.model.AdaptiveDecisionRecord
import com.monkfitness.app.data.model.FamilyProgressionState
import com.monkfitness.app.domain.adaptive.AdaptiveDecision
import com.monkfitness.app.domain.adaptive.FamilyAdaptationState

/**
 * The adaptive persistence adapter: current family state in one table, immutable decision history in
 * another, and the domain types of Tasks 4–7 mapped across the boundary.
 *
 * It is an adapter and nothing more. It evaluates no policy, calculates no signal, resolves no
 * progression, picks no exercise, touches no calendar and applies no workout change, and it never
 * infers a family's current state from its history — the two tables are separate sources of truth,
 * and the one method that writes both writes them together rather than deriving either from the
 * other. Whether a state transition *should* happen is the engine's and the policy's decision; this
 * layer only records what it was told.
 *
 * The two timestamps — `updatedAt` on a state row and `timestamp` on a record — are persistence
 * facts, stamped here and never by `domain.adaptive`: the domain stays deterministic and clock-free,
 * while storage remembers when it stored something.
 *
 * @param inTransaction runs a block inside one Room transaction. Production passes
 *   `AppDatabase.withTransaction`; a unit test passes a block that either applies everything or
 *   rolls it all back, which is how the all-or-nothing invariant is provable on the JVM.
 */
class AdaptiveRepository(
    private val stateDao: FamilyProgressionStateDao,
    private val historyDao: AdaptiveDecisionHistoryDao,
    private val inTransaction: suspend (suspend () -> Unit) -> Unit
) {

    // --- current state ------------------------------------------------------------------------

    /** Every family state of one program revision, ordered by ascending family id. */
    suspend fun familyStates(programRevision: Int): List<FamilyProgressionState> =
        stateDao.getFamilyStates(programRevision)

    /** One family's current state in one program revision, or `null` when none is stored. */
    suspend fun familyState(programRevision: Int, familyId: String): FamilyProgressionState? =
        stateDao.getFamilyState(programRevision, familyId)

    /**
     * One program revision's family states projected into the domain type the adaptive engine's input
     * consumes, in the same deterministic order. The projection carries every hysteresis count the
     * engine reads back, so no count is defaulted or invented at this boundary.
     */
    suspend fun familyAdaptationStates(programRevision: Int): List<FamilyAdaptationState> =
        familyStates(programRevision).map { it.toAdaptationState() }

    /** Writes one family's current state, replacing that family's row rather than adding one. */
    suspend fun saveFamilyState(state: FamilyProgressionState) = stateDao.upsertFamilyState(state)

    // --- decision history ---------------------------------------------------------------------

    /**
     * The record one decision is audited as: the family-scoped decision plus the window it was taken
     * in, stamped with the moment it is stored. The decision must carry its family id — an audit
     * entry that cannot say whose decision it is has lost the only thing it exists to prove.
     */
    fun decisionRecord(
        decision: AdaptiveDecision,
        programRevision: Int,
        cycleNumber: Int,
        programDay: Int,
        timestamp: Long = System.currentTimeMillis()
    ): AdaptiveDecisionRecord {
        val familyId = requireNotNull(decision.familyId) {
            "a decision record must carry its family id; the decision for ${decision.state} has none"
        }
        return AdaptiveDecisionRecord(
            familyId = familyId,
            programRevision = programRevision,
            cycleNumber = cycleNumber,
            programDay = programDay,
            timestamp = timestamp,
            previousState = decision.previousState,
            newState = decision.state,
            actions = decision.actions,
            reasonCode = decision.reasonCode,
            policyVersion = decision.policyVersion
        )
    }

    /** Appends one record to the audit trail and returns its row identity. Nothing is replaced. */
    suspend fun appendDecision(record: AdaptiveDecisionRecord): Long = historyDao.appendDecision(record)

    /** One program revision's whole history, oldest window first. */
    suspend fun decisionHistory(programRevision: Int): List<AdaptiveDecisionRecord> =
        historyDao.getDecisionHistory(programRevision)

    /**
     * One family's whole audit trail across every revision it was adapted under, oldest first — the
     * path that keeps an earlier revision's decisions analysable after a revised program.
     */
    suspend fun decisionHistoryForFamily(familyId: String): List<AdaptiveDecisionRecord> =
        historyDao.getDecisionHistoryForFamily(familyId)

    // --- both, atomically ---------------------------------------------------------------------

    /**
     * Persists a family's updated current state and appends the decision it came from, in one
     * transaction: either both land or neither does.
     *
     * This is the only method that writes both tables, and it exists because the two writes are one
     * fact about one decision — a stored state whose audit record is missing cannot be explained
     * afterwards, and an audit record whose state write was lost describes a transition that did not
     * happen. It decides nothing: the caller has already decided that this transition is the one to
     * record.
     *
     * @throws IllegalArgumentException when the state and the record do not describe the same family
     *   in the same program revision, which would silently file one family's decision under another.
     * @throws Exception whatever the DAOs throw, un-suppressed, so a failed persistence is reported
     *   rather than half-applied.
     */
    suspend fun persistDecision(state: FamilyProgressionState, record: AdaptiveDecisionRecord) {
        require(state.familyId == record.familyId) {
            "a persisted decision must belong to the family whose state it updates, was " +
                "${state.familyId} and ${record.familyId}"
        }
        require(state.programRevision == record.programRevision) {
            "a persisted decision must belong to the revision whose state it updates, was " +
                "${state.programRevision} and ${record.programRevision}"
        }
        inTransaction {
            stateDao.upsertFamilyState(state)
            historyDao.appendDecision(record)
        }
    }
}
