package com.monkfitness.app

import com.monkfitness.app.animation.*
import com.monkfitness.app.poses.*
import com.monkfitness.app.validation.poses.*
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `IkStage` carrier, in the ACTIVATED state.
 *
 * The pipeline-owned `IkStage` consumes the `limbTargets` carrier (populated by every `bakeIkLimb`
 * forward). `IK_STAGE_ACTIVE` defaults to true and the stage is the sole Active Limb Solver, while the
 * authoring bakes keep their registration effects and their node-realization branch is gated off.
 *
 * The carrier coverage below is state-agnostic and survives activation: `limbTargets` is populated in
 * BOTH configurations — registration is intent; only the realization moved.
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
