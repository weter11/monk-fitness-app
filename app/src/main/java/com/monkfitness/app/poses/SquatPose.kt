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
import kotlin.math.cos
import kotlin.math.sin

class SquatPose : PoseBuilder {
    override fun evaluate(progress: Float, side: Side): SkeletonPose {
        // progress 0 (up) to 1 (down)
        val pelvisHeight = lerp(THIGH + SHIN + 10f, 40f, progress)
        val pelvis = Vector3(0f, pelvisHeight, 0f)

        val hipF = pelvis + Vector3(0f, 0f, HIPW)
        val hipB = pelvis + Vector3(0f, 0f, -HIPW)

        val toeF = Vector3(20f + 20f * progress, 0f, HIPW * 1.5f)
        val toeB = Vector3(20f + 20f * progress, 0f, -HIPW * 1.5f)

        val legF = solveIK(hipF, toeF, THIGH, SHIN, Vector3(1f, 0f, 0f))
        val legB = solveIK(hipB, toeB, THIGH, SHIN, Vector3(1f, 0f, 0f))

        val chestLean = lerp(0.1f, 0.4f, progress)
        val chest = pelvis + Vector3(sin(chestLean) * TORSO, cos(chestLean) * TORSO, 0f)

        val shoulderA = chest + Vector3(0f, 0f, SHW)
        val shoulderP = chest + Vector3(0f, 0f, -SHW)

        val armLean = lerp(0.5f, 1.5f, progress)
        val handA = shoulderA + Vector3(sin(armLean) * (UPARM + FOREARM), -cos(armLean) * (UPARM + FOREARM), 0f)
        val handP = shoulderP + Vector3(sin(armLean) * (UPARM + FOREARM), -cos(armLean) * (UPARM + FOREARM), 0f)

        val armA = solveIK(shoulderA, handA, UPARM, FOREARM, Vector3(0f, 0f, 1f))
        val armP = solveIK(shoulderP, handP, UPARM, FOREARM, Vector3(0f, 0f, -1f))

        val headDir = Vector3(0.2f, 0.9f, 0f).normalize()
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
