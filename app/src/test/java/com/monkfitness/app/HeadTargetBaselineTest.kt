package com.monkfitness.app

import com.monkfitness.app.animation.*
import com.monkfitness.app.poses.*
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Phase 7 (Gap 7 / F8 / W17) regression guard for gaze-as-`headTarget`.
 *
 * The Finalizer's `resolveHeadTarget` is the sole writer of the neck/head chain (the legacy
 * direction-based `buildHead` fallback was removed once byte-identity was proven — the removal
 * commit's message records the measured maxDeviation ~6e-5). This guard keeps the resolver
 * honest going forward: for every gaze-declaring pose family across the whole rep, the resolved
 * head must NOT collapse onto the neck (the failure mode the resolver's subtree re-propagation
 * fixes — `BONE_LENGTH NECK_END->HEAD_POS`), i.e. the neck→head bone stays at its ~18-unit length.
 */
class HeadTargetBaselineTest {

    private val def = SkeletonDefinition.DEFAULT_ADULT
    private val finalizer = SkeletonPoseFinalizer(def)

    private fun poses(): List<Pair<String, PoseBuilder>> = listOf(
        "StandardPullUp" to StandardPullUpPose(),
        "Hang" to HangPose(),
        "StandardPushUp" to StandardPushUpPose(),
        "PikePushUp" to PikePushUpPose(),
        "KneePushUp" to KneePushUpPose(),
        "AirSquat" to AirSquatPose(),
        "SumoSquat" to SumoSquatPose(),
        "JumpSquat" to JumpSquatPose(),
        "DeepSquatHold" to DeepSquatHoldPose(),
        "ForwardLunge" to AlternatingForwardLungesPose(),
        "ReverseLunge" to AlternatingReverseLungesPose(),
        "SideLunge" to AlternatingSideLungesPose(),
        "StepUp" to StepUpPose(),
        "BirdDog" to BirdDogPose(),
        "StaticBirdDogHold" to StaticBirdDogHoldPose(),
        "StaticForearmPlank" to StaticForearmPlankPose(),
        "IsometricSidePlank" to IsometricSidePlankPose(),
        "ProneCobraStretch" to ProneCobraStretchPose(),
        "HamstringStretch" to HamstringStretchPose(),
        "ThoracicExtension" to ThoracicExtensionPose(),
        "QuadrupedThoracicRotations" to QuadrupedThoracicRotationsPose(),
        "DynamicWorldsGreatestStretch" to DynamicWorldsGreatestStretchPose(),
        "CouchStretch" to CouchStretchPose(),
        "HalfKneelingStretch" to HalfKneelingStretchPose()
    )

    private fun dist(a: Vector3, b: Vector3): Float {
        val dx = a.x - b.x; val dy = a.y - b.y; val dz = a.z - b.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    @Test
    fun gazeResolverKeepsHeadBoneIntact() {
        val frames = 30
        val expectedHeadBone = 18f // BasePose.buildHead / resolveHeadTarget: head.local = dir * 18
        var worstErr = 0f
        var worst = ""

        for ((name, pose) in poses()) {
            for (i in 0..frames) {
                val p = i / frames.toFloat()
                val f = finalizer.finalize(pose.build(PoseContext(p, Side.LEFT, def)))
                val neck = f.getJoint(Joint.NECK_END)
                val head = f.getJoint(Joint.HEAD_POS)
                val headBone = dist(neck, head)
                val err = abs(headBone - expectedHeadBone)
                if (err > worstErr) { worstErr = err; worst = "$name @progress=$p (NECK_END->HEAD_POS=$headBone)" }
            }
        }

        // 1% of the 18-unit bone, matching the validator's BONE_LENGTH tolerance.
        assertTrue(
            "Gaze resolver collapsed/stretched the head bone by $worstErr at $worst",
            worstErr < expectedHeadBone * 0.01f
        )
        println("HeadTargetBaselineTest: OK  worstHeadBoneErr=$worstErr")
    }
}
