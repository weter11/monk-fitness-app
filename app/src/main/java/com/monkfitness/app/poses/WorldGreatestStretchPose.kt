package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import com.monkfitness.app.animation.SkeletonMath.easeInOut
import com.monkfitness.app.animation.SkeletonMath.lerp
import com.monkfitness.app.animation.SkeletonMath.rotAround
import com.monkfitness.app.animation.SkeletonMath.solveIK

class WorldGreatestStretchPose : PoseBuilder {
    override fun build(context: PoseContext): SkeletonPose {
        val progress = context.progress
        val side = context.side
        val definition = context.definition

        val s = if (side == Side.RIGHT) 1f else -1f
        val u = progress // 0 = threaded under, 1 = open to sky

        // Root & anchors (runner's lunge, back toe on ground)
        val pelvis = Vector3(-18f, 35f + 20f * u, 0f)

        // Hip mapping: L is front/active, R is back/planted in the p5 code
        // We maintain: hipF (active/front), hipB (planted/back)
        val hipF = pelvis + Vector3(0f, 0f, definition.hipWidth * s)
        val hipB = pelvis + Vector3(0f, 0f, -definition.hipWidth * s)

        val frontAnkle = Vector3(140f, 9f, 24f * s)
        val frontToe = Vector3(175f, 0f, 27f * s)
        val backAnkle = Vector3(-170f, 18f, -26f * s)
        val backToe = Vector3(-195f, 0f, -28f * s)

        // Legs — locked-length IK, anatomical knee poles
        val legF = solveIK(hipF, frontAnkle, definition.thighLength, definition.shinLength, Vector3(1f, 0.35f, 0.15f * s), IKConstraint.LegConstraint)
        val legB = solveIK(hipB, backAnkle, definition.thighLength, definition.shinLength, Vector3(0.12f, -1f, -0.08f * s), IKConstraint.LegConstraint)

        // Spine (FK, exact torsoLength length) — rises slightly as arm opens
        val lean = Vector3(0.85f - 0.20f * u, 0.45f + 0.34f * u, 0.10f * u * s).normalize()
        val chest = pelvis + (lean * definition.torsoLength)

        // Thoracic twist: shoulder girdle rotates about the spine axis
        val tw = lerp(0.50f, -1.35f, u) * s
        val shVec = rotAround(Vector3(0f, 0f, 1f), lean, tw).normalize()
        val shoulderA = chest + (shVec * (definition.shoulderWidth * s)) // active side
        val shoulderP = chest + (shVec * (-definition.shoulderWidth * s)) // planted side

        // Tapered Torso Box
        val chestNorm = lean.cross(shVec).normalize()
        val offC = chestNorm * definition.torsoChestDepth
        val offH = chestNorm * definition.torsoHipDepth

        val tBox = TorsoBox(
            hLf = hipF + offH, hLb = hipF - offH,
            hRf = hipB + offH, hRb = hipB - offH,
            sLf = shoulderA + offC, sLb = shoulderA - offC,
            sRf = shoulderP + offC, sRb = shoulderP - offC
        )

        // Head follows the twist, gaze rises with the opening arm
        val neckBase = lerp(shoulderA, shoulderP, 0.5f)
        val headDir = (lean * 0.8f + Vector3(0f, 0.4f + 0.6f * u, 0.2f * u * s)).normalize()
        val neckEnd = neckBase + (headDir * definition.neckLength)
        val headPos = neckEnd + (headDir * definition.headRadius)

        // Planted support arm (hand fixed on ground, elbow bows outward)
        val plantHand = Vector3(70f, 0f, -20f * s)
        val armP = solveIK(shoulderP, plantHand, definition.upperArmLength, definition.forearmLength, Vector3(-0.25f, 0.15f, -0.9f * s), IKConstraint.ArmConstraint)

        // Active arm trajectory
        val D0 = Vector3(0.32f, -0.86f, -0.40f * s)
        val Dm = Vector3(0.30f, -0.05f, 0.88f * s)
        val D2 = Vector3(-0.08f, 0.98f, 0.20f * s)

        var dir: Vector3
        var r: Float
        val totalArmLen = definition.upperArmLength + definition.forearmLength

        if (u < 0.5f) {
            val k = easeInOut(u * 2f)
            dir = lerp(D0, Dm, k).normalize()
            r = lerp(0.72f, 0.92f, k) * totalArmLen
        } else {
            val k = easeInOut(u * 2f - 1f)
            dir = lerp(Dm, D2, k).normalize()
            r = lerp(0.92f, 0.985f, k) * totalArmLen
        }

        val handTargetRaw = shoulderA + (dir * r)
        val handTarget = if (handTargetRaw.y < 6f) handTargetRaw.copy(y = 6f) else handTargetRaw

        val poleA = lerp(Vector3(0f, 0f, 1f * s), Vector3(-1f, 0f, 1f * s), u)
        val armA = solveIK(shoulderA, handTarget, definition.upperArmLength, definition.forearmLength, poleA, IKConstraint.ArmConstraint)

        return SkeletonPose(
            joints = mapOf(
                Joint.PELVIS to pelvis,
                Joint.HIP_F to hipF,
                Joint.HIP_B to hipB,
                Joint.KNEE_F to legF.joint,
                Joint.ANKLE_F to legF.end,
                Joint.TOE_F to frontToe,
                Joint.KNEE_B to legB.joint,
                Joint.ANKLE_B to legB.end,
                Joint.TOE_B to backToe,
                Joint.CHEST to chest,
                Joint.SHOULDER_A to shoulderA,
                Joint.SHOULDER_P to shoulderP,
                Joint.ELBOW_A to armA.joint,
                Joint.HAND_A to armA.end,
                Joint.ELBOW_P to armP.joint,
                Joint.HAND_P to armP.end,
                Joint.NECK_END to neckEnd,
                Joint.HEAD_POS to headPos
            ),
            torsoBox = tBox
        )
    }
}
