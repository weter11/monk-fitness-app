package com.monkfitness.app.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.monkfitness.app.domain.adaptive.AdaptiveAction
import com.monkfitness.app.domain.adaptive.AdaptiveReasonCode
import com.monkfitness.app.domain.adaptive.AdaptiveState

/**
 * One immutable entry of the adaptive audit trail: what the engine ordered for one family, in one
 * decision window, under one policy version.
 *
 * History is deliberately NOT the source of current progression truth — [FamilyProgressionState] is.
 * A record answers "what was ordered, when, and under which policy", and the current state answers
 * "where the family stands now"; neither is derived from the other, and the repository exposes no
 * update or delete for this type, because an audit entry that can be rewritten is not an audit entry.
 *
 * The stored vocabulary is the domain's own: [AdaptiveState] for the transition,
 * [AdaptiveAction] for what was ordered and [AdaptiveReasonCode] for why — stable enum names, not
 * user-facing prose and not a second action/reason vocabulary invented here. The UI localizes the
 * names; the audit trail never carries text.
 *
 * [familyId] is part of the record because Task 6 made decisions family-scoped: an audit trail that
 * discards it cannot say whose decision it is. [programRevision] is carried for the same reason — a
 * revised program starts from baseline, and the record of an earlier revision must stay
 * identifiable as that revision's.
 *
 * @param id the row identity, assigned by the database.
 * @param familyId the exercise family the decision applies to.
 * @param programRevision the program revision the decision was taken under (`0` is the program as
 *   first started, matching `SettingsManager.PROGRAM_REVISION`).
 * @param cycleNumber the program cycle the decision window belonged to, as the existing calendar
 *   defines it.
 * @param programDay the program day within that cycle (1-based).
 * @param timestamp when the record was stored, in epoch milliseconds — a persistence stamp, taken by
 *   the repository and never by the adaptive domain.
 * @param previousState the adaptation state the caller reported before the decision.
 * @param newState the adaptation state the decision ordered.
 * @param actions the ordered actions the decision ordered; at least one.
 * @param reasonCode why the decision came out the way it did.
 * @param policyVersion the `AdaptivePolicy` version that produced the decision.
 */
@Entity(tableName = "adaptive_decision_record")
data class AdaptiveDecisionRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val familyId: String,
    val programRevision: Int = 0,
    val cycleNumber: Int,
    val programDay: Int,
    val timestamp: Long,
    val previousState: AdaptiveState,
    val newState: AdaptiveState,
    val actions: List<AdaptiveAction>,
    val reasonCode: AdaptiveReasonCode,
    val policyVersion: Int
) {

    init {
        require(familyId.isNotBlank()) { "a decision record must carry its family id" }
        require(programRevision >= 0) { "programRevision must be >= 0, was $programRevision" }
        require(cycleNumber >= 1) { "cycleNumber must be >= 1, was $cycleNumber" }
        require(programDay >= 1) { "programDay must be >= 1, was $programDay" }
        require(actions.isNotEmpty()) { "a decision record carries at least one action" }
        require(policyVersion >= 1) { "policyVersion must be >= 1, was $policyVersion" }
    }
}
