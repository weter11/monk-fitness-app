package com.monkfitness.app.data.mapper

import com.monkfitness.app.data.model.AdaptiveDecisionRecordEntity
import com.monkfitness.app.data.model.FamilyProgressionStateEntity
import com.monkfitness.app.data.repository.ProgramGraphFixture
import com.monkfitness.app.domain.adaptive.AdaptiveScope
import com.monkfitness.app.domain.adaptive.AdaptiveState
import com.monkfitness.app.domain.adaptive.ConfidenceLevel
import com.monkfitness.app.domain.adaptive.EvidenceLevel
import com.monkfitness.app.domain.adaptive.FamilyProgressionState
import com.monkfitness.app.domain.adaptive.RecoveryContext
import com.monkfitness.app.domain.adaptive.decision.AdaptiveAction
import com.monkfitness.app.domain.adaptive.decision.AdaptiveAdjustment
import com.monkfitness.app.domain.adaptive.decision.AdaptiveDecision
import com.monkfitness.app.domain.adaptive.decision.AdaptiveTarget
import com.monkfitness.app.domain.adaptive.decision.DecisionOutcome
import com.monkfitness.app.domain.adaptive.engine.ProgramAdaptiveReason
import com.monkfitness.app.domain.common.AdjustmentId
import com.monkfitness.app.domain.common.DecisionId
import com.monkfitness.app.domain.common.ProgramExerciseId
import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.common.RevisionId
import com.monkfitness.app.domain.common.SlotId
import com.monkfitness.app.domain.prescription.RepPrescription
import com.monkfitness.app.domain.workout.EffectiveExercise
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * The Program System's adaptive rows ⇄ the target adaptive vocabulary.
 *
 * The pins are the three links the schema deliberately keeps single: a scope that agrees with its target
 * id, an applied decision that really has its adjustment, and an adjustment whose before/after are both
 * stored — plus the shape of the family state, which must not acquire the Stage-1 pilot's counters.
 */
class AdaptiveMapperTest {

    /** The declared field names of a class, without the synthetic members Kotlin adds. */
    private fun declaredFieldNames(type: Class<*>) = type.declaredFields
        .filterNot { Modifier.isStatic(it.modifiers) || it.name.startsWith("$") }
        .map { it.name }

    private val decision = AdaptiveDecision(
        decisionId = DecisionId("decision-m-1"),
        programId = ProgramId("program-m"),
        revisionId = RevisionId("revision-m"),
        slotId = SlotId("slot-m-1"),
        target = AdaptiveTarget.Family("push-family"),
        action = AdaptiveAction.PROGRESS,
        outcome = DecisionOutcome.APPLIED,
        evidence = EvidenceLevel.STRONG,
        confidence = ConfidenceLevel.HIGH,
        recovery = RecoveryContext.FAVORABLE,
        decidedAt = ProgramGraphFixture.CREATED,
        adjustmentId = AdjustmentId("adjustment-m-1"),
        reason = ProgramAdaptiveReason.SUSTAINED_POSITIVE
    )

    private val adjustment = AdaptiveAdjustment(
        adjustmentId = AdjustmentId("adjustment-m-1"),
        decisionId = DecisionId("decision-m-1"),
        slotId = SlotId("slot-m-1"),
        before = EffectiveExercise(
            ProgramExerciseId("plan-ex-m-1"),
            "pushup",
            RepPrescription(listOf(12, 10, 8, 6))
        ),
        after = EffectiveExercise(
            ProgramExerciseId("plan-ex-m-1"),
            "knee_pushup",
            RepPrescription(listOf(10, 8, 8, 6))
        ),
        createdAt = ProgramGraphFixture.CREATED,
        supersedesAdjustmentId = AdjustmentId("adjustment-m-0")
    )

    @Test
    fun aDecisionRoundTripsWithTheAdjustmentThatOwnsTheLink() {
        val row = decision.toEntity()

        assertEquals(decision, row.toDomain(decision.adjustmentId))
        assertEquals(
            "the decision row stores no adjustment id — the adjustment row owns the link (§23)",
            row,
            row.toDomain(decision.adjustmentId).toEntity()
        )
        assertTrue(
            "and the entity has no such column",
            declaredFieldNames(AdaptiveDecisionRecordEntity::class.java).none { it == "adjustmentId" }
        )
    }

    @Test
    fun everyTargetScopeRoundTripsAndNamesItsIdOnlyWhenItHasOne() {
        val targets = listOf(
            AdaptiveTarget.Exercise("pushup"),
            AdaptiveTarget.Family("push-family"),
            AdaptiveTarget.Focus("upper-push"),
            AdaptiveTarget.Session
        )

        targets.forEach { target ->
            val scoped = decision.copy(target = target)
            val row = scoped.toEntity()

            assertEquals("$target scope", target.scope.name, row.targetScope)
            if (target is AdaptiveTarget.Session) {
                assertNull("$target stores no target id", row.targetId)
            } else {
                assertFalse("$target stores the id it names", row.targetId.isNullOrBlank())
            }
            assertEquals("$target round trip", scoped, row.toDomain(scoped.adjustmentId))
        }
        assertNull("a session-scoped decision stores no target id", decision.copy(target = AdaptiveTarget.Session).toEntity().targetId)
    }

    @Test
    fun everyActionOutcomeEvidenceConfidenceAndRecoveryTokenRoundTrips() {
        AdaptiveAction.entries.forEach { action ->
            val outcome = if (action == AdaptiveAction.HOLD) DecisionOutcome.NOT_APPLIED else DecisionOutcome.APPLIED
            val scoped = decision.copy(
                action = action,
                outcome = outcome,
                adjustmentId = if (outcome == DecisionOutcome.APPLIED) decision.adjustmentId else null
            )

            assertEquals(
                "$action round trip",
                scoped,
                scoped.toEntity().toDomain(scoped.adjustmentId)
            )
        }
        EvidenceLevel.entries.forEach { level ->
            assertEquals(level, decision.copy(evidence = level).toEntity().toDomain(decision.adjustmentId).evidence)
        }
        ConfidenceLevel.entries.forEach { level ->
            assertEquals(level, decision.copy(confidence = level).toEntity().toDomain(decision.adjustmentId).confidence)
        }
        RecoveryContext.entries.forEach { context ->
            assertEquals(context, decision.copy(recovery = context).toEntity().toDomain(decision.adjustmentId).recovery)
        }
    }

    @Test
    fun aFilteredOutDecisionIsHistoryAndNotAbsence() {
        val filtered = decision.copy(
            action = AdaptiveAction.HOLD,
            outcome = DecisionOutcome.NOT_APPLIED,
            adjustmentId = null
        )

        val loaded = filtered.toEntity().toDomain(null)

        assertEquals("NOT_APPLIED is a stored outcome, not a missing row (§18)", DecisionOutcome.NOT_APPLIED, loaded.outcome)
        assertNull("and it produced no adjustment", loaded.adjustmentId)
        assertEquals(filtered, loaded)
    }

    @Test
    fun anAppliedDecisionWithoutItsAdjustmentIsInvalidPersistedData() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            decision.toEntity().toDomain(null)
        }

        assertTrue(
            "an applied decision claims a change nobody recorded: ${failure.message}",
            failure.message!!.contains("produced exactly one adjustment")
        )
    }

    @Test
    fun aNotAppliedDecisionThatCarriesAnAdjustmentIsRefused() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            decision.copy(outcome = DecisionOutcome.NOT_APPLIED).toEntity().toDomain(AdjustmentId("adjustment-m-1"))
        }

        assertTrue(failure.message!!.contains("NOT_APPLIED"))
    }

    @Test
    fun aScopeAndItsTargetIdMustAgree() {
        val missingId = assertThrows(IllegalArgumentException::class.java) {
            decision.toEntity().copy(targetScope = AdaptiveScope.FAMILY.name, targetId = null)
                .toDomain(decision.adjustmentId)
        }
        val strayId = assertThrows(IllegalArgumentException::class.java) {
            decision.toEntity()
                .copy(targetScope = AdaptiveScope.SESSION.name, targetId = "push-family")
                .toDomain(decision.adjustmentId)
        }

        assertTrue(missingId.message!!.contains("names its family target id"))
        assertTrue(strayId.message!!.contains("names no target id"))
    }

    @Test
    fun anUnknownScopeActionOutcomeEvidenceConfidenceOrRecoveryTokenIsRefused() {
        val row = decision.toEntity()
        val adjustments = decision.adjustmentId

        listOf(
            "targetScope" to row.copy(targetScope = "PROGRAM"),
            "action" to row.copy(action = "INCREASE"),
            "outcome" to row.copy(outcome = "PENDING"),
            "evidence" to row.copy(evidence = "WEAK"),
            "confidence" to row.copy(confidence = "CERTAIN"),
            "recovery" to row.copy(recovery = "GOOD")
        ).forEach { (column, broken) ->
            val failure = assertThrows(IllegalArgumentException::class.java) {
                broken.toDomain(adjustments)
            }

            assertTrue(
                "an unknown $column token is invalid data: ${failure.message}",
                failure.message!!.contains("program_adaptive_decision_record.$column")
            )
        }
    }

    @Test
    fun anAdjustmentRoundTripsWithItsOwnerSuppliedByTheDecision() {
        val row = adjustment.toEntity(decision.programId, decision.revisionId)

        assertEquals(adjustment, row.toDomain())
        assertEquals(
            "the owner is stored for ownership, not duplicated into the value (§29)",
            "program-m",
            row.programId
        )
        assertEquals("revision-m", row.revisionId)
        assertEquals(
            "the changed occurrence is named once and is the same before and after",
            adjustment.before.programExerciseId.value,
            row.programExerciseId
        )
    }

    @Test
    fun beforeAndAfterPrescriptionsAreStoredInFull() {
        val row = adjustment.toEntity(decision.programId, decision.revisionId)

        assertEquals(listOf(12, 10, 8, 6), row.beforePerSetTargets)
        assertEquals(listOf(10, 8, 8, 6), row.afterPerSetTargets)
        assertEquals("pushup", row.beforeExerciseId)
        assertEquals("knee_pushup", row.afterExerciseId)
        assertEquals("adjustment-m-0", row.supersedesAdjustmentId)
    }

    @Test
    fun anAdjustmentThatChangesNothingIsUnrepresentable() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            adjustment.copy(after = adjustment.before).toEntity(decision.programId, decision.revisionId)
        }

        assertTrue(
            "a change that changes nothing is NothingToChange, a result (§28): ${failure.message}",
            failure.message!!.contains("NothingToChange")
        )
    }

    @Test
    fun anAdjustmentCannotSupersedeItself() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            adjustment.copy(supersedesAdjustmentId = adjustment.adjustmentId)
                .toEntity(decision.programId, decision.revisionId)
        }

        assertTrue(failure.message!!.contains("cannot supersede itself"))
    }

    @Test
    fun aFamilyProgressionStateRoundTripsWithTheWindowBookkeepingAndWithoutTheStageOneCounters() {
        val state = FamilyProgressionState(
            revisionId = RevisionId("revision-m"),
            familyId = "push-family",
            progressionLevel = 3,
            adaptationState = AdaptiveState.PROGRESS,
            currentExerciseId = "pushup",
            updatedAt = ProgramGraphFixture.CREATED
        )

        assertEquals(state, state.toEntity().toDomain())
        assertEquals(
            "the target state is §23's own fields followed by the window bookkeeping §30 step 12 " +
                "stores, in that order",
            listOf(
                "revisionId",
                "familyId",
                "progressionLevel",
                "adaptationState",
                "currentExerciseId",
                "updatedAt",
                "precedingProgressQualifyingWindows",
                "precedingRegressQualifyingWindows",
                "precedingRecoveryQualifyingWindows",
                "qualifyingWindowsSinceLastChange",
                "recoveryQualifyingWindows"
            ),
            declaredFieldNames(FamilyProgressionState::class.java)
        )
        assertTrue(
            "no Stage-1 hysteresis counter and no policy version is resurrected: the pilot's state is " +
                "scoped by the legacy revision integer and its counters belong to its own state machine " +
                "(§23). The five that *are* here are the target engine's own window facts — a window " +
                "cannot count itself, so the caller carries them — and each one is a field of " +
                "`ProgramAdaptiveWindow`.",
            declaredFieldNames(FamilyProgressionState::class.java).none {
                it.contains("policyVersion") || it.contains("programRevision") ||
                    it.contains("hysteresis") || it.contains("cycleNumber")
            }
        )
    }

    @Test
    fun theWindowBookkeepingRoundTripsThroughTheStoredCounts() {
        val state = FamilyProgressionState(
            revisionId = RevisionId("revision-m"),
            familyId = "push-family",
            progressionLevel = 3,
            adaptationState = AdaptiveState.RECOVERY,
            currentExerciseId = "pushup",
            updatedAt = ProgramGraphFixture.CREATED,
            precedingProgressQualifyingWindows = 2,
            precedingRegressQualifyingWindows = 0,
            precedingRecoveryQualifyingWindows = 1,
            qualifyingWindowsSinceLastChange = 4,
            recoveryQualifyingWindows = 1
        )

        assertEquals(
            "every count the engine reads as an input is stored and read back as it was written",
            state,
            state.toEntity().toDomain()
        )
        assertEquals(
            "and the column an upgraded row leaves absent reads back as the count a family with no " +
                "preceding window has, while \"never changed level\" stays absent",
            listOf(0, 0, 0, null, 0),
            FamilyProgressionStateEntity(
                revisionId = "revision-m",
                familyId = "push-family",
                progressionLevel = 3,
                adaptationState = "HOLD",
                currentExerciseId = null,
                updatedAt = ProgramGraphFixture.CREATED.toEpochMilli()
            ).toDomain().let {
                listOf(
                    it.precedingProgressQualifyingWindows,
                    it.precedingRegressQualifyingWindows,
                    it.precedingRecoveryQualifyingWindows,
                    it.qualifyingWindowsSinceLastChange,
                    it.recoveryQualifyingWindows
                )
            }
        )
    }

    @Test
    fun theDecisionReasonRoundsTripsAndAnUnknownTokenIsRefused() {
        val stored = decision.toEntity()

        assertEquals(
            "the rule that answered is stored as its stable token, and it is the only piece of the " +
                "engine's reasoning the row keeps (§13, §22)",
            ProgramAdaptiveReason.SUSTAINED_POSITIVE.name,
            stored.reason
        )
        assertEquals(
            "and it comes back as the domain value it was written as",
            decision.copy(reason = ProgramAdaptiveReason.SUSTAINED_POSITIVE),
            stored.toDomain(adjustmentId = decision.adjustmentId)
        )
        assertEquals(
            "a decision with no reason stores none, and a row with no reason loads as none — the " +
                "absence is never guessed at",
            null,
            decision.copy(reason = null).toEntity().reason
        )

        val refusal = assertThrows(IllegalArgumentException::class.java) {
            stored.copy(reason = "PROGRESS_FELT_GOOD").toDomain(adjustmentId = null)
        }
        assertTrue(
            "a stored token outside the vocabulary fails loudly as invalid data (§28): " +
                "${refusal.message}",
            refusal.message!!.contains("program_adaptive_decision_record.reason")
        )
    }

    @Test
    fun aFamilyStateWithoutACurrentExerciseRoundTripsAsNull() {
        val state = FamilyProgressionState(
            revisionId = RevisionId("revision-m"),
            familyId = "push-family",
            progressionLevel = 0,
            adaptationState = AdaptiveState.HOLD,
            currentExerciseId = null,
            updatedAt = ProgramGraphFixture.CREATED
        )

        assertNull(state.toEntity().currentExerciseId)
        assertEquals(state, state.toEntity().toDomain())
    }

    @Test
    fun anUnknownAdaptationStateTokenIsRefused() {
        val row = FamilyProgressionState(
            RevisionId("revision-m"), "push-family", 0, AdaptiveState.HOLD, null, ProgramGraphFixture.CREATED
        ).toEntity()

        val failure = assertThrows(IllegalArgumentException::class.java) {
            row.copy(adaptationState = "PLATEAU").toDomain()
        }

        assertTrue(failure.message!!.contains("program_family_progression_state.adaptationState"))
        assertTrue(failure.message!!.contains("HOLD"))
    }
}
