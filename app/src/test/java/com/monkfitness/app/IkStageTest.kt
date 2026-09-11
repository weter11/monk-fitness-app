package com.monkfitness.app

import com.monkfitness.app.animation.*
import com.monkfitness.app.poses.*
import com.monkfitness.app.validation.poses.*
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B1 (RFC_BRANCH_B_IMPLEMENTATION §2 B1) — the `IkStage` §1.1 carrier, in the ACTIVATED state.
 *
 * B1 introduced the pipeline-owned `IkStage` that consumes the `limbTargets` carrier (now populated
 * by every `bakeIkLimb` forward). P12 activated it: `IK_STAGE_ACTIVE` defaults to **true** and the
 * stage is the sole Active Limb Solver (§12.0 state 3), while the authoring bakes keep their
 * registration effects and their node-realization branch is gated off (§12.7a).
 *
 * **§12.8 retirement (WP-I).** Three state-2-only probes that used to live here are retired — they
 * were guarantees ABOUT state 2, not about the architecture:
 *  - `productionPosesByteIdenticalStageOnVsOff` / `contactPosesByteIdenticalStageOnVsOff` — a
 *    flag-OFF-vs-flag-ON byte-identity smoke test over 11 production + 4 contact fixtures, which
 *    treated the OFF/ON pair itself as the runtime contract (post-activation "flag-OFF" is the
 *    legacy authoring configuration, so the comparison says nothing about the deployed state).
 *    §12.9 replaced it with the complete cross-configuration corpus (`ActivationEquivalenceTest`:
 *    39 entries × progress sweep — all 33 joint transforms + the full §4.4 stamp set + settlement
 *    state + publish markers + kinematic state, 0 raw-bit deltas) — the same comparison over a
 *    strictly larger observation, run for the deployed configuration as well.
 *  - `flagDefaultsFalse` — asserted `IK_STAGE_ACTIVE` defaults to false, i.e. that state 2 is the
 *    deployed state. Replaced by the §12.7 configuration-surface audit
 *    (`RuntimeSolverOwnershipAuditTest.ikStageFlagIsDeclarationOnlyAndReadOnlyAtRealizationDecisionSites`)
 *    and the §12.10 activation gate (`ActivationGateTest.productionConfigurationIsStateThree`),
 *    which assert the deployed state and the single-writer contract rather than a stale default.
 * Nothing was hidden: no ignore annotation was applied, no assumption-based skip was introduced, and
 * no assertion was conditionalized on the flag. The §12.8 disposition is itself pinned by
 * `ActivationEquivalenceTest.retiredAndStateTwoOnlyProbesAreExplicitlyClassified`.
 *
 * The carrier coverage below is state-agnostic and survives activation: `limbTargets` is populated in
 * BOTH configurations (§12.5 — registration is intent; only the realization moved).
 */
class IkStageTest {

    private val def = SkeletonDefinition.DEFAULT_ADULT

    private fun poseFactories(): List<Pair<String, () -> PoseBuilder>> = listOf(
        "StandardPullUp" to { StandardPullUpPose() },
        "StandardPushUp" to { StandardPushUpPose() },
        "AirSquat" to { AirSquatPose() },
        "SumoSquat" to { SumoSquatPose() },
        "ForwardLunge" to { AlternatingForwardLungesPose() },
        "BirdDog" to { BirdDogPose() },
        "StaticForearmPlank" to { StaticForearmPlankPose() },
        "HamstringStretch" to { HamstringStretchPose() },
        "JumpSquat" to { JumpSquatPose() },
        "PikePushUp" to { PikePushUpPose() },
        "QuadrupedThoracicRotations" to { QuadrupedThoracicRotationsPose() }
    )

    private fun contactPoseFactories(): List<Pair<String, () -> BaseValidationPose>> = listOf(
        "DeepOverheadSquat" to { DeepOverheadSquatPose() },
        "DeadHang" to { DeadHangPose() },
        "MiddleSplit" to { MiddleSplitPose() },
        "PikeSit" to { PikeSitPose() }
    )

    @Test
    fun limbTargetsCarrierIsLiveAfterB1() {
        for ((name, factory) in poseFactories()) {
            val pose = factory().build(PoseContext(0.5f, Side.LEFT, def))
            assertTrue("$name must populate limbTargets (B1 dead→live) got=${pose.limbTargets.size}", pose.limbTargets.size > 0)
        }
        for ((name, factory) in contactPoseFactories()) {
            val pose = factory().build(PoseContext(0.5f, Side.LEFT, def))
            assertTrue("$name must populate limbTargets (B1 dead→live) got=${pose.limbTargets.size}", pose.limbTargets.size > 0)
        }
    }
}
