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

class SupermanPose : PoseBuilder {
    override fun evaluate(progress: Float, side: Side): SkeletonPose {
        // Prone position
        // progress 0 (resting) to 1 (extended)

        val pelvis = Vector3(0f, 10f, 0f)
        val chestLean = lerp(0f, -0.2f, progress).toDouble()
        val chest = pelvis + Vector3(-TORSO * cos(chestLean).toFloat(), -TORSO * sin(chestLean).toFloat(), 0f)

        val hipF = pelvis + Vector3(0f, 0f, HIPW)
        val hipB = pelvis + Vector3(0f, 0f, -HIPW)

        val legLean = lerp(0f, 0.3f, progress).toDouble()
        val toeF = hipF + Vector3((THIGH + SHIN) * cos(legLean).toFloat(), (THIGH + SHIN) * sin(legLean).toFloat(), 0f)
        val toeB = hipB + Vector3((THIGH + SHIN) * cos(legLean).toFloat(), (THIGH + SHIN) * sin(legLean).toFloat(), 0f)

        val legF = solveIK(hipF, toeF, THIGH, SHIN, Vector3(0f, 1f, 0f))
        val legB = solveIK(hipB, toeB, THIGH, SHIN, Vector3(0f, 1f, 0f))

        val shoulderA = chest + Vector3(0f, 0f, SHW)
        val shoulderP = chest + Vector3(0f, 0f, -SHW)

        val armLean = lerp(0.1f, -0.4f, progress).toDouble()
        val handA = shoulderA + Vector3(-(UPARM + FOREARM) * cos(armLean).toFloat(), -(UPARM + FOREARM) * sin(armLean).toFloat(), 0f)
        val handP = shoulderP + Vector3(-(UPARM + FOREARM) * cos(armLean).toFloat(), -(UPARM + FOREARM) * sin(armLean).toFloat(), 0f)

        val armA = solveIK(shoulderA, handA, UPARM, FOREARM, Vector3(0f, 1f, 1f))
        val armP = solveIK(shoulderP, handP, UPARM, FOREARM, Vector3(0f, 1f, -1f))

        val headDir = Vector3(-1f, 0.3f, 0f).normalize()
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
