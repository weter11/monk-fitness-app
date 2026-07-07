package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import com.monkfitness.app.animation.SkeletonEngine.THIGH
import com.monkfitness.app.animation.SkeletonEngine.SHIN
import com.monkfitness.app.animation.SkeletonEngine.TORSO
import com.monkfitness.app.animation.SkeletonEngine.UPARM
import com.monkfitness.app.animation.SkeletonEngine.FOREARM
import com.monkfitness.app.animation.SkeletonEngine.HIPW
import com.monkfitness.app.animation.SkeletonEngine.SHW
import com.monkfitness.app.animation.SkeletonEngine.NECK
import com.monkfitness.app.animation.SkeletonEngine.HEADR
import com.monkfitness.app.animation.SkeletonMath.solveIK
import com.monkfitness.app.animation.SkeletonMath.lerp

class BirdDogPose : PoseBuilder {
    override fun evaluate(progress: Float, side: Side): SkeletonPose {
        // Quadruped base
        val pelvis = Vector3(50f, 45f, 0f)
        val chest = pelvis + Vector3(-TORSO, 0f, 0f)

        val hipF = pelvis + Vector3(0f, 0f, HIPW)
        val hipB = pelvis + Vector3(0f, 0f, -HIPW)

        val shoulderA = chest + Vector3(0f, 0f, SHW) // Right
        val shoulderP = chest + Vector3(0f, 0f, -SHW) // Left

        val s = if (side == Side.RIGHT) 1f else -1f

        // Target positions for "neutral" quadruped
        val baseHandR = shoulderA + Vector3(0f, -45f, 0f)
        val baseHandL = shoulderP + Vector3(0f, -45f, 0f)
        val baseKneeR = hipF + Vector3(0f, -45f, 0f)
        val baseKneeL = hipB + Vector3(0f, -45f, 0f)

        // Extended positions
        val extHand = if (side == Side.RIGHT) {
            shoulderP + Vector3(-UPARM - FOREARM, 10f, 0f) // Left arm extends
        } else {
            shoulderA + Vector3(-UPARM - FOREARM, 10f, 0f) // Right arm extends
        }

        val extKnee = if (side == Side.RIGHT) {
            hipF + Vector3(THIGH + SHIN, 10f, 0f) // Right leg extends
        } else {
            hipB + Vector3(THIGH + SHIN, 10f, 0f) // Left leg extends
        }

        // Lerp based on progress
        val handR = if (side == Side.LEFT) lerp(baseHandR, extHand, progress) else baseHandR
        val handL = if (side == Side.RIGHT) lerp(baseHandL, extHand, progress) else baseHandL

        val kneeR = if (side == Side.RIGHT) lerp(baseKneeR, extKnee, progress) else baseKneeR
        val kneeL = if (side == Side.LEFT) lerp(baseKneeL, extKnee, progress) else baseKneeL

        // Toe/Ankle positions
        val toeF = kneeR + Vector3(10f, -10f, 0f)
        val toeB = kneeL + Vector3(10f, -10f, 0f)

        val armA = solveIK(shoulderA, handR, UPARM, FOREARM, Vector3(0f, 0f, 1f))
        val armP = solveIK(shoulderP, handL, UPARM, FOREARM, Vector3(0f, 0f, -1f))

        val legF = solveIK(hipF, toeF, THIGH, SHIN, Vector3(-1f, 0f, 1f))
        val legB = solveIK(hipB, toeB, THIGH, SHIN, Vector3(-1f, 0f, -1f))

        val headDir = Vector3(-1f, 0.1f, 0f).normalize()
        val neckEnd = chest + headDir * NECK
        val headPos = chest + headDir * (NECK + HEADR)

        return SkeletonPose(
            mapOf(
                Joint.PELVIS to pelvis,
                Joint.HIP_F to hipF,
                Joint.HIP_B to hipB,
                Joint.KNEE_F to legF.joint,
                Joint.ANKLE_F to legF.end,
                Joint.TOE_F to toeF,
                Joint.KNEE_B to legB.joint,
                Joint.ANKLE_B to legB.end,
                Joint.TOE_B to toeB,
                Joint.CHEST to chest,
                Joint.SHOULDER_A to shoulderA,
                Joint.SHOULDER_P to shoulderP,
                Joint.ELBOW_A to armA.joint,
                Joint.HAND_A to armA.end,
                Joint.ELBOW_P to armP.joint,
                Joint.HAND_P to armP.end,
                Joint.NECK_END to neckEnd,
                Joint.HEAD_POS to headPos
            )
        )
    }
}
