package com.monkfitness.app.data.repository

import com.monkfitness.app.data.local.AdaptiveAdjustmentDao
import com.monkfitness.app.data.local.ProgramAdaptiveDecisionDao
import com.monkfitness.app.data.local.ProgramFamilyProgressionStateDao
import com.monkfitness.app.data.mapper.toDomain
import com.monkfitness.app.data.mapper.toEntity
import com.monkfitness.app.domain.adaptive.FamilyProgressionState
import com.monkfitness.app.domain.adaptive.decision.AdaptiveAdjustment
import com.monkfitness.app.domain.adaptive.decision.AdaptiveDecision
import com.monkfitness.app.domain.adaptive.decision.DecisionOutcome
import com.monkfitness.app.domain.common.AdjustmentId
import com.monkfitness.app.domain.common.DecisionId
import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.common.RevisionId
import com.monkfitness.app.domain.common.SlotId
import java.time.Instant

/**
 * The Program System's own adaptive persistence: family progression state, the decision trail and the
 * adjustments decisions produced.
 *
 * It is named `ProgramAdaptiveRepository` because the shipped `AdaptiveRepository` already owns the
 * Stage-1 tables keyed by the legacy revision integer; this one owns only the target tables
 * (`program_family_progression_state`, `program_adaptive_decision_record`, `adaptive_adjustment`).
 *
 * It decides nothing: no policy, no signal, no load comparison, no progression step, no exercise pick.
 * Whether a family should transition is the engine's and the policy's call; whether a decision should
 * be applied is the aggregate load guard's. A filtered-out decision is stored with `NOT_APPLIED` and is
 * never turned into a missing row.
 *
 * History is append-only: no update or delete of a decision or an adjustment exists here or through the
 * DAOs — an adjustment supersedes an earlier one *by reference*, so the earlier row keeps describing
 * what the user was shown and a filtered-out decision stays in the trail, auditable.
 *
 * [saveFamilyState] writes its stamp from the injected [now]; nothing in the mappers reads a clock.
 */
class ProgramAdaptiveRepository(
    private val familyStateDao: ProgramFamilyProgressionStateDao,
    private val decisionDao: ProgramAdaptiveDecisionDao,
    private val adjustmentDao: AdaptiveAdjustmentDao,
    private val now: () -> Instant,
    private val inTransaction: suspend (suspend () -> Unit) -> Unit
) {

    suspend fun familyState(revisionId: RevisionId, familyId: String): FamilyProgressionState? =
        familyStateDao.stateOf(revisionId.value, familyId)?.toDomain()

    /** Every family state of one revision, in ascending family id order. */
    suspend fun familyStates(revisionId: RevisionId): List<FamilyProgressionState> =
        familyStateDao.statesOfRevision(revisionId.value).map { it.toDomain() }

    /**
     * Writes one family's current state for one revision and returns it as stored, stamped with the
     * moment of the write. The stamp is the repository's, not the caller's: `updatedAt` records when the
     * row was written, which is a persistence fact. The state's own values — level, adaptation state,
     * current exercise — are stored exactly as handed in.
     */
    suspend fun saveFamilyState(state: FamilyProgressionState): FamilyProgressionState {
        val stored = state.copy(updatedAt = now())
        familyStateDao.upsertState(stored.toEntity())
        return stored
    }

    suspend fun decisionById(decisionId: DecisionId): AdaptiveDecision? {
        val decision = decisionDao.decisionById(decisionId.value) ?: return null
        return decide(listOf(decision)).single()
    }

    /** One Program's decisions, oldest first. Filtered-out decisions are included. */
    suspend fun decisionsOf(programId: ProgramId): List<AdaptiveDecision> =
        decide(decisionDao.decisionsOfProgram(programId.value))

    /** One revision's decisions, oldest first. */
    suspend fun decisionsOf(revisionId: RevisionId): List<AdaptiveDecision> =
        decide(decisionDao.decisionsOfRevision(revisionId.value))

    /** One slot's decisions, oldest first. */
    suspend fun decisionsOf(slotId: SlotId): List<AdaptiveDecision> =
        decide(decisionDao.decisionsOfSlot(slotId.value))

    suspend fun adjustmentById(adjustmentId: AdjustmentId): AdaptiveAdjustment? =
        adjustmentDao.adjustmentById(adjustmentId.value)?.toDomain()

    /** The adjustment chain of one slot, oldest first. A superseded adjustment stays readable. */
    suspend fun adjustmentsOf(slotId: SlotId): List<AdaptiveAdjustment> =
        adjustmentDao.adjustmentsOfSlot(slotId.value).map { it.toDomain() }

    /** Every adjustment of one Program, oldest first. */
    suspend fun adjustmentsOf(programId: ProgramId): List<AdaptiveAdjustment> =
        adjustmentDao.adjustmentsOfProgram(programId.value).map { it.toDomain() }

    /** Every adjustment made under one revision, oldest first. */
    suspend fun adjustmentsOf(revisionId: RevisionId): List<AdaptiveAdjustment> =
        adjustmentDao.adjustmentsOfRevision(revisionId.value).map { it.toDomain() }

    /**
     * Appends a decision and, when it applied one, the adjustment it produced — in one transaction.
     *
     * The pair is one fact, so it is written as one: an `APPLIED` decision whose adjustment row failed
     * to land would claim a change nobody recorded, and an adjustment without its decision would have
     * no ground. Both directions are checked before the write, the `NOT_APPLIED`/absent pairing
     * included, and a mismatch fails loudly instead of being stored.
     *
     * @param adjustment the change the decision applied, or `null` for a decision that applied none.
     * @throws IllegalArgumentException when the decision and the adjustment are not one fact.
     * @throws Exception whatever the DAOs throw, with the transaction rolled back.
     */
    suspend fun persistDecision(decision: AdaptiveDecision, adjustment: AdaptiveAdjustment? = null) {
        require((decision.outcome == DecisionOutcome.APPLIED) == (adjustment != null)) {
            "an APPLIED decision produced exactly one adjustment and a NOT_APPLIED one produced " +
                "none: decision='${decision.decisionId.value}' outcome=${decision.outcome} " +
                "adjustment=${adjustment?.adjustmentId?.value}"
        }
        require(adjustment == null || adjustment.decisionId == decision.decisionId) {
            "an adjustment belongs to the decision that produced it: adjustment " +
                "'${adjustment?.adjustmentId?.value}' names decision " +
                "'${adjustment?.decisionId?.value}' (${decision.decisionId.value} was handed in)"
        }
        require(adjustment == null || adjustment.slotId == decision.slotId) {
            "an adjustment applies to the slot of its decision: adjustment " +
                "'${adjustment?.adjustmentId?.value}' names slot '${adjustment?.slotId?.value}' " +
                "(${decision.slotId.value} was handed in)"
        }

        inTransaction {
            decisionDao.insertDecision(decision.toEntity())
            adjustment?.let {
                adjustmentDao.insertAdjustment(it.toEntity(decision.programId, decision.revisionId))
            }
        }
    }

    /**
     * The decisions of one row set, each carrying the identity of the adjustment it produced.
     *
     * A decision row does not store its adjustment (one link, owned by the adjustment), so the chain is
     * read from the adjustment rows that name each decision — one query per distinct decision, which is
     * exact: "an `APPLIED` decision produced exactly one adjustment" is the schema's own rule, so more
     * than one adjustment naming the same decision is invalid persisted data and fails loudly rather
     * than being resolved by picking a row.
     */
    private suspend fun decide(
        decisions: List<com.monkfitness.app.data.model.AdaptiveDecisionRecordEntity>
    ): List<AdaptiveDecision> = decisions.map { decision ->
        val adjustments = adjustmentDao.adjustmentsOfDecision(decision.decisionId)
        require(adjustments.size <= 1) {
            "an APPLIED decision produced exactly one adjustment: decision " +
                "'${decision.decisionId}' is named by ${adjustments.size} adjustments"
        }
        decision.toDomain(adjustments.singleOrNull()?.let { AdjustmentId(it.adjustmentId) })
    }
}
