package com.monkfitness.app.data.repository

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
import com.monkfitness.app.domain.common.AdjustmentId
import com.monkfitness.app.domain.common.DecisionId
import com.monkfitness.app.domain.common.ProgramExerciseId
import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.common.RevisionId
import com.monkfitness.app.domain.common.SlotId
import com.monkfitness.app.domain.prescription.RepPrescription
import com.monkfitness.app.domain.workout.EffectiveExercise
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * `ProgramAdaptiveRepository`: the target adaptive tables only, an append-only history, and one
 * transaction for a decision and the adjustment it applied.
 */
class ProgramAdaptiveRepositoryTest {

    private val rig = ProgramDataAccessRig("a")

    private val programId = ProgramId(ProgramGraphFixture.programId("a"))

    private val revisionId = RevisionId(ProgramGraphFixture.revisionId("a"))

    private val slotId = SlotId(ProgramGraphFixture.slotId("a", 1))

    private fun decision(
        id: String,
        outcome: DecisionOutcome = DecisionOutcome.APPLIED,
        action: AdaptiveAction = AdaptiveAction.PROGRESS,
        family: String = "push-family",
        at: Instant = ProgramGraphFixture.CREATED
    ) = AdaptiveDecision(
        decisionId = DecisionId(id),
        programId = programId,
        revisionId = revisionId,
        slotId = slotId,
        target = AdaptiveTarget.Family(family),
        action = action,
        outcome = outcome,
        evidence = EvidenceLevel.STRONG,
        confidence = ConfidenceLevel.HIGH,
        recovery = RecoveryContext.FAVORABLE,
        decidedAt = at,
        adjustmentId = if (outcome == DecisionOutcome.NOT_APPLIED) null else AdjustmentId("adjustment-$id")
    )

    private fun adjustment(id: String, supersedes: String? = null) = AdaptiveAdjustment(
        adjustmentId = AdjustmentId("adjustment-$id"),
        decisionId = DecisionId(id),
        slotId = slotId,
        before = EffectiveExercise(ProgramExerciseId("plan-ex-a-1"), "pushup", RepPrescription(listOf(12, 10, 8, 6))),
        after = EffectiveExercise(ProgramExerciseId("plan-ex-a-1"), "knee_pushup", RepPrescription(listOf(10, 8, 8, 6))),
        createdAt = ProgramGraphFixture.CREATED,
        supersedesAdjustmentId = supersedes?.let { AdjustmentId("adjustment-$it") }
    )

    private suspend fun stageOneRows(): List<Map<String, String?>> {
        rig.database.exec(
            "INSERT INTO `family_progression_state` (`familyId`, `progressionLevel`, `currentExerciseId`, " +
                "`adaptationState`, `precedingProgressQualifyingWindows`, `precedingRegressQualifyingWindows`, " +
                "`precedingHighRiskWindows`, `recoveryQualifyingSessions`, " +
                "`eligibleSessionsSinceLastProgressionChange`, `programRevision`, `updatedAt`, `policyVersion`) " +
                "VALUES ('push-family', 1, 'pushup', 'PROGRESS', 2, 0, 0, 0, 4, 0, 1700000000000, 1)"
        )
        rig.database.exec(
            "INSERT INTO `adaptive_decision_record` (`familyId`, `programRevision`, `cycleNumber`, " +
                "`programDay`, `timestamp`, `previousState`, `newState`, `actions`, `reasonCode`, " +
                "`policyVersion`) VALUES ('push-family', 0, 1, 3, 1700000000000, 'HOLD', 'PROGRESS', " +
                "'PROGRESS', 'PROGRESS_CONFIRMED', 1)"
        )
        return rig.database.rows("SELECT * FROM `family_progression_state`") +
            rig.database.rows("SELECT * FROM `adaptive_decision_record`")
    }

    // ---- the target/legacy separation -------------------------------------------------------------

    @Test
    fun theTargetRepositoryReadsAndWritesOnlyTheTargetTables() = runBlocking {
        rig.createGraph()
        val legacyBefore = stageOneRows()

        val state = FamilyProgressionState(
            revisionId = revisionId,
            familyId = "push-family",
            progressionLevel = 1,
            adaptationState = AdaptiveState.PROGRESS,
            currentExerciseId = "pushup",
            updatedAt = ProgramGraphFixture.CREATED
        )
        rig.programAdaptiveRepository.saveFamilyState(state)
        rig.programAdaptiveRepository.persistDecision(decision("d1"), adjustment("d1"))

        assertEquals(
            "the target writes landed in the target tables",
            listOf("1", "1", "1"),
            listOf(
                rig.database.scalar("SELECT COUNT(*) FROM `program_family_progression_state`"),
                rig.database.scalar("SELECT COUNT(*) FROM `program_adaptive_decision_record`"),
                rig.database.scalar("SELECT COUNT(*) FROM `adaptive_adjustment`")
            )
        )
        assertEquals(
            "and the Stage-1 rows are byte-identical after every target write (§30 step 15)",
            legacyBefore,
            rig.database.rows("SELECT * FROM `family_progression_state`") +
                rig.database.rows("SELECT * FROM `adaptive_decision_record`")
        )
        assertEquals(
            "the target repository sees only its own family state",
            listOf(state.familyId),
            rig.programAdaptiveRepository.familyStates(revisionId).map { it.familyId }
        )
        assertEquals(
            "and only its own decisions, though the legacy table holds one for the same family",
            listOf("d1"),
            rig.freshAdaptiveRepository().decisionsOf(programId).map { it.decisionId.value }
        )
    }

    @Test
    fun theStageOneAdaptiveRepositoryIsStillTheOnlyClassWithThatName() = runBlocking {
        val sources = java.io.File("src/main/java/com/monkfitness/app/data/repository").let {
            if (it.isDirectory) it else java.io.File("app/src/main/java/com/monkfitness/app/data/repository")
        }
        val declaring = sources.listFiles { file -> file.extension == "kt" }!!
            .filter { it.readText().contains("class AdaptiveRepository(") }
            .map { it.name }

        assertEquals(
            "the target layer took its own name; the shipped Stage-1 class was neither renamed nor " +
                "duplicated (§24)",
            listOf("AdaptiveRepository.kt"),
            declaring
        )
    }

    // ---- append-only history ---------------------------------------------------------------------

    @Test
    fun decisionsAndAdjustmentsAccumulateAndNothingRewritesThem() = runBlocking {
        rig.createGraph()
        rig.programAdaptiveRepository.persistDecision(decision("d1", at = ProgramGraphFixture.CREATED), adjustment("d1"))
        val afterFirst = rig.database.rows("SELECT * FROM `adaptive_adjustment` ORDER BY `adjustmentId`")

        rig.programAdaptiveRepository.persistDecision(
            decision("d2", at = ProgramGraphFixture.FINISHED),
            adjustment("d2", supersedes = "d1")
        )

        assertEquals(
            "both decisions remain",
            listOf("d1", "d2"),
            rig.freshAdaptiveRepository().decisionsOf(slotId).map { it.decisionId.value }
        )
        assertEquals(
            "and both adjustments, in creation order — the superseded one was not rewritten",
            listOf("adjustment-d1", "adjustment-d2"),
            rig.freshAdaptiveRepository().adjustmentsOf(slotId).map { it.adjustmentId.value }
        )
        assertEquals(
            "the superseded adjustment is byte-identical to what it was",
            afterFirst,
            rig.database.rows("SELECT * FROM `adaptive_adjustment` WHERE `adjustmentId` = 'adjustment-d1'")
        )
        assertEquals(
            "the chain is recorded by reference",
            listOf(null, "adjustment-d1"),
            rig.freshAdaptiveRepository().adjustmentsOf(slotId).map { it.supersedesAdjustmentId?.value }
        )
        assertTrue(
            "the repository exposes no way to rewrite a decision or an adjustment",
            ProgramAdaptiveRepository::class.java.declaredMethods.none { method ->
                val name = method.name.lowercase()
                name.startsWith("update") || name.startsWith("delete") || name.startsWith("remove") ||
                    name.startsWith("rewrite") || name.startsWith("clear")
            }
        )
    }

    @Test
    fun aFilteredOutDecisionStaysInTheTrailWithItsOutcome() = runBlocking {
        rig.createGraph()

        rig.programAdaptiveRepository.persistDecision(
            decision("d1", outcome = DecisionOutcome.NOT_APPLIED, action = AdaptiveAction.HOLD)
        )

        val stored = rig.freshAdaptiveRepository().decisionsOf(programId).single()

        assertEquals("the guard's filtered-out decision is history, not absence (§18)", DecisionOutcome.NOT_APPLIED, stored.outcome)
        assertNull("and it produced no adjustment", stored.adjustmentId)
        assertEquals("nothing was invented for it", "0", rig.database.scalar("SELECT COUNT(*) FROM `adaptive_adjustment`"))
    }

    @Test
    fun anAppliedDecisionWhoseAdjustmentRowIsGoneIsInvalidPersistedData() = runBlocking {
        rig.createGraph()
        rig.programAdaptiveRepository.persistDecision(decision("d1"), adjustment("d1"))
        rig.database.exec("DELETE FROM `adaptive_adjustment` WHERE `adjustmentId` = 'adjustment-d1'")

        val failure = failureOf { rig.freshAdaptiveRepository().decisionById(DecisionId("d1")) }

        assertTrue(
            "an applied decision claims a change nobody recorded: ${failure.message}",
            failure.message!!.contains("produced exactly one adjustment")
        )
    }

    // ---- transactions ----------------------------------------------------------------------------

    @Test
    fun aDecisionAndItsAdjustmentLandTogetherOrNotAtAll() = runBlocking {
        rig.createGraph()
        rig.faults.failAdjustmentInsert = true

        val failure = failureOf {
            rig.programAdaptiveRepository.persistDecision(decision("d1"), adjustment("d1"))
        }

        assertTrue(failure.message!!.contains("planted fault"))
        assertEquals(
            "no decision without its adjustment and no adjustment without its decision",
            listOf("0", "0"),
            listOf(
                rig.database.scalar("SELECT COUNT(*) FROM `program_adaptive_decision_record`"),
                rig.database.scalar("SELECT COUNT(*) FROM `adaptive_adjustment`")
            )
        )
    }

    @Test
    fun aPairThatIsNotOneFactIsRefusedBeforeAnyWrite() = runBlocking {
        rig.createGraph()

        val withoutAdjustment = failureOf {
            rig.programAdaptiveRepository.persistDecision(decision("d1"), null)
        }
        val wrongDecision = failureOf {
            rig.programAdaptiveRepository.persistDecision(decision("d1"), adjustment("d2"))
        }
        val wrongSlot = failureOf {
            rig.programAdaptiveRepository.persistDecision(
                decision("d1"),
                adjustment("d1").copy(slotId = SlotId(ProgramGraphFixture.slotId("a", 2)))
            )
        }

        assertTrue(withoutAdjustment.message!!.contains("APPLIED decision produced exactly one"))
        assertTrue(wrongDecision.message!!.contains("belongs to the decision that produced it"))
        assertTrue(wrongSlot.message!!.contains("applies to the slot of its decision"))
        assertEquals("nothing was written", "0", rig.database.scalar("SELECT COUNT(*) FROM `program_adaptive_decision_record`"))
    }

    // ---- state -----------------------------------------------------------------------------------

    @Test
    fun familyStateIsScopedByRevisionAndStampedByTheInjectedClock() = runBlocking {
        rig.createGraph()
        val state = FamilyProgressionState(
            revisionId = revisionId,
            familyId = "push-family",
            progressionLevel = 2,
            adaptationState = AdaptiveState.HOLD,
            currentExerciseId = null,
            updatedAt = ProgramGraphFixture.CREATED
        )

        val stored = rig.programAdaptiveRepository.saveFamilyState(state)
        assertEquals("the write stamp is the repository's, from the injected clock", ProgramGraphFixture.CREATED, stored.updatedAt)

        rig.now = ProgramGraphFixture.FINISHED
        val restamped = rig.programAdaptiveRepository.saveFamilyState(state)

        assertEquals(ProgramGraphFixture.FINISHED, restamped.updatedAt)
        assertEquals(
            "one current state per family per revision — the second write replaced the first",
            "1",
            rig.database.scalar("SELECT COUNT(*) FROM `program_family_progression_state`")
        )
        assertEquals(
            "and the state's own values are stored as handed in",
            stored.copy(updatedAt = ProgramGraphFixture.FINISHED),
            rig.freshAdaptiveRepository().familyState(revisionId, "push-family")
        )
        assertNull(
            "a family with no recorded state reads as absent, not as a default (§11)",
            rig.freshAdaptiveRepository().familyState(revisionId, "squat-family")
        )
        assertEquals(
            "another revision keeps its own baseline (a new revision starts from scratch, §16)",
            emptyList<String>(),
            rig.freshAdaptiveRepository().familyStates(RevisionId("revision-a-2")).map { it.familyId }
        )
    }

    @Test
    fun adjustmentsAreReadableBySlotProgramAndRevision() = runBlocking {
        rig.createGraph()
        rig.programAdaptiveRepository.persistDecision(decision("d1"), adjustment("d1"))

        assertEquals(1, rig.freshAdaptiveRepository().adjustmentsOf(slotId).size)
        assertEquals(1, rig.freshAdaptiveRepository().adjustmentsOf(programId).size)
        assertEquals(1, rig.freshAdaptiveRepository().adjustmentsOf(revisionId).size)
        assertEquals(
            "and the by-id read returns the same change",
            AdjustmentId("adjustment-d1"),
            rig.freshAdaptiveRepository().adjustmentById(AdjustmentId("adjustment-d1"))!!.adjustmentId
        )
        assertNull(rig.freshAdaptiveRepository().adjustmentById(AdjustmentId("adjustment-nowhere")))
    }

    @Test
    fun deletingTheProgramTakesTheAdaptiveHistoryWithItAndNothingElseStays() = runBlocking {
        rig.createGraph()
        rig.programAdaptiveRepository.persistDecision(decision("d1"), adjustment("d1"))
        rig.programAdaptiveRepository.saveFamilyState(
            FamilyProgressionState(revisionId, "push-family", 1, AdaptiveState.HOLD, null, ProgramGraphFixture.CREATED)
        )

        rig.programRepository.deleteProgram(programId)

        assertEquals(
            "the adaptive rows are Program-owned (§29)",
            listOf("0", "0", "0"),
            listOf(
                rig.database.scalar("SELECT COUNT(*) FROM `program_family_progression_state`"),
                rig.database.scalar("SELECT COUNT(*) FROM `program_adaptive_decision_record`"),
                rig.database.scalar("SELECT COUNT(*) FROM `adaptive_adjustment`")
            )
        )
    }
}
