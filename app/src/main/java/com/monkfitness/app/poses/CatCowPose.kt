package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import com.monkfitness.app.animation.SkeletonMath.solveIK
import com.monkfitness.app.animation.SkeletonMath.lerp
import kotlin.math.cos
import kotlin.math.sin

class CatCowPose : PoseBuilder {
    override fun evaluate(progress: Float, side: Side, definition: SkeletonDefinition): SkeletonPose {
        // Quadruped base
        // progress 0 (Cat - rounded) to 1 (Cow - arched)

        val pelvisPos = lerp(45f, 40f, progress)
        val pelvis = Vector3(50f, pelvisPos, 0f)

        val chestPos = lerp(45f, 35f, progress)
        val chest = pelvis + Vector3(-definition.torsoLength, chestPos - pelvisPos, 0f)

        val hipF = pelvis + Vector3(0f, 0f, definition.hipWidth)
        val hipB = pelvis + Vector3(0f, 0f, -definition.hipWidth)

        val kneeBaseR = Vector3(50f, 0f, definition.hipWidth)
        val kneeBaseL = Vector3(50f, 0f, -definition.hipWidth)

        val legF = solveIK(hipF, kneeBaseR, definition.thighLength, definition.shinLength, Vector3(-1f, 0f, 1f))
        val legB = solveIK(hipB, kneeBaseL, definition.thighLength, definition.shinLength, Vector3(-1f, 0f, -1f))

        val shoulderA = chest + Vector3(0f, 0f, definition.shoulderWidth)
        val shoulderP = chest + Vector3(0f, 0f, -definition.shoulderWidth)

        val handBaseR = shoulderA + Vector3(0f, -chestPos, 0f)
        val handBaseL = shoulderP + Vector3(0f, -chestPos, 0f)

        val armA = solveIK(shoulderA, handBaseR, definition.upperArmLength, definition.forearmLength, Vector3(0f, 0f, 1f))
        val armP = solveIK(shoulderP, handBaseL, definition.upperArmLength, definition.forearmLength, Vector3(0f, 0f, -1f))

        // Spine midpoint for arching
        // val spineMid = lerp(pelvis + Vector3(-definition.torsoLength/2, 15f, 0f), pelvis + Vector3(-definition.torsoLength/2, -10f, 0f), progress)

        val headPitch = lerp(-0.5f, 0.5f, progress)
        val headDir = Vector3(-cos(headPitch), sin(headPitch), 0f).normalize()
        val neckEnd = chest + headDir * definition.neckLength
        val headPos = chest + headDir * (definition.neckLength + definition.headRadius)

        return SkeletonPose(
            mapOf(
                Joint.PELVIS to pelvis,
                Joint.HIP_F to hipF,
                Joint.HIP_B to hipB,
                Joint.KNEE_F to legF.joint,
                Joint.ANKLE_F to legF.end,
                Joint.TOE_F to kneeBaseR + Vector3(10f, 0f, 0f),
                Joint.KNEE_B to legB.joint,
                Joint.ANKLE_B to legB.end,
                Joint.TOE_B to kneeBaseL + Vector3(10f, 0f, 0f),
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
