package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import com.monkfitness.app.animation.SkeletonMath.solveIK
import com.monkfitness.app.animation.SkeletonMath.lerp
import kotlin.math.*

class PushUpPose : PoseBuilder {
    // Persistent Scene Graph hierarchy to eliminate per-frame allocations
    private var roots: List<SkeletonNode>? = null

    // Cached node references for high-speed access
    private var ankleF: SkeletonNode? = null
    private var kneeF: SkeletonNode? = null
    private var hipF: SkeletonNode? = null
    private var pelvis: SkeletonNode? = null
    private var chest: SkeletonNode? = null

    private var shoulderA: SkeletonNode? = null
    private var elbowA: SkeletonNode? = null
    private var handA: SkeletonNode? = null

    private var shoulderP: SkeletonNode? = null
    private var elbowP: SkeletonNode? = null
    private var handP: SkeletonNode? = null

    private var ankleB: SkeletonNode? = null
    private var kneeB: SkeletonNode? = null
    private var hipB: SkeletonNode? = null

    private fun ensureHierarchy(def: SkeletonDefinition) {
        if (roots != null) return

        // Final Biomechanical chain: Ankle -> Knee -> Hip -> Pelvis -> Chest -> Shoulders -> Arms -> Hands
        ankleF = SkeletonNode(Joint.ANKLE_F)
        kneeF = ankleF!!.addChild(SkeletonNode(Joint.KNEE_F))
        hipF = kneeF!!.addChild(SkeletonNode(Joint.HIP_F))

        // Pelvis is the bridge between legs
        pelvis = hipF!!.addChild(SkeletonNode(Joint.PELVIS))
        chest = pelvis!!.addChild(SkeletonNode(Joint.CHEST))

        shoulderA = chest!!.addChild(SkeletonNode(Joint.SHOULDER_A))
        elbowA = shoulderA!!.addChild(SkeletonNode(Joint.ELBOW_A))
        handA = elbowA!!.addChild(SkeletonNode(Joint.HAND_A))

        shoulderP = chest!!.addChild(SkeletonNode(Joint.SHOULDER_P))
        elbowP = shoulderP!!.addChild(SkeletonNode(Joint.ELBOW_P))
        handP = elbowP!!.addChild(SkeletonNode(Joint.HAND_P))

        // Right leg (Back) connects back to Pelvis in this rigid-body chain
        // to ensure it's derived from the single root's rotation.
        // Actually, to make both legs planted, they should both be roots or one is derived.
        // The user requested "AnklePivot should be the Root node for a rigid push-up".
        // This implies a single ankle is the world anchor.

        hipB = pelvis!!.addChild(SkeletonNode(Joint.HIP_B))
        kneeB = hipB!!.addChild(SkeletonNode(Joint.KNEE_B))
        ankleB = kneeB!!.addChild(SkeletonNode(Joint.ANKLE_B))

        roots = listOf(ankleF!!)
    }

    override fun build(context: PoseContext): SkeletonPose {
        val progress = context.progress
        val def = context.definition
        ensureHierarchy(def)

        // Target: Pelvis world height 60 to 25
        val height = lerp(60f, 25f, progress)
        val totalLegLen = def.shinLength + def.thighLength
        val theta = asin((height / totalLegLen).coerceIn(-1f, 1f))

        // AnkleX stays fixed for zero regression (Pelvis X = 60)
        val horizontalDist = totalLegLen * cos(theta)
        val ankleX = 60f + horizontalDist

        // 1. Update Legs and Body Trunk via FK
        ankleF!!.localPosition = Vector3(ankleX, 0f, def.hipWidth)
        ankleF!!.localRotationAngle = -theta

        kneeF!!.localPosition = Vector3(-def.shinLength, 0f, 0f)
        hipF!!.localPosition = Vector3(-def.thighLength, 0f, 0f)

        pelvis!!.localPosition = Vector3(0f, 0f, -def.hipWidth)
        chest!!.localPosition = Vector3(-def.torsoLength, 0f, 0f)

        // Right leg positions relative to pelvis
        hipB!!.localPosition = Vector3(0f, 0f, -def.hipWidth)
        kneeB!!.localPosition = Vector3(def.thighLength, 0f, 0f) // Inverse direction
        ankleB!!.localPosition = Vector3(def.shinLength, 0f, 0f)

        // 2. Perform FK pass to compute intermediate world positions
        roots!!.forEach { it.updateWorldTransforms(Vector3(0f, 0f, 0f), 0f) }

        val chestWorld = chest!!.worldPosition
        val shoulderAWorld = chestWorld + Vector3(0f, 0f, def.shoulderWidth)
        val shoulderPWorld = chestWorld + Vector3(0f, 0f, -def.shoulderWidth)

        // 3. Solve Upper Body IK (Hands planted on floor Y=0)
        val targetHandA = Vector3(chestWorld.x, 0f, def.shoulderWidth * 1.5f)
        val targetHandP = Vector3(chestWorld.x, 0f, -def.shoulderWidth * 1.5f)

        val armA = solveIK(shoulderAWorld, targetHandA, def.upperArmLength, def.forearmLength, Vector3(1f, 0f, 1f), IKConstraint.ArmConstraint)
        val armP = solveIK(shoulderPWorld, targetHandP, def.upperArmLength, def.forearmLength, Vector3(1f, 0f, -1f), IKConstraint.ArmConstraint)

        // 4. Finalize Hierarchy transforms
        shoulderA!!.localPosition = Vector3(0f, 0f, def.shoulderWidth)
        elbowA!!.localPosition = armA.joint - shoulderAWorld
        handA!!.localPosition = armA.end - armA.joint

        shoulderP!!.localPosition = Vector3(0f, 0f, -def.shoulderWidth)
        elbowP!!.localPosition = armP.joint - shoulderPWorld
        handP!!.localPosition = armP.end - armP.joint

        // 5. Export Pose
        val pose = SkeletonPose.fromHierarchy(roots!!)
        val joints = pose.joints.toMutableMap()

        // 6. Manual overrides for non-hierarchical joints (Toes/Head) to maintain zero regression
        joints[Joint.NECK_END] = chestWorld + Vector3(-1f, 0.2f, 0f).normalize() * def.neckLength
        joints[Joint.HEAD_POS] = chestWorld + Vector3(-1f, 0.2f, 0f).normalize() * (def.neckLength + 18f)
        joints[Joint.TOE_F] = Vector3(ankleX + 10f, 0f, def.hipWidth)
        joints[Joint.TOE_B] = Vector3(ankleX + 10f, 0f, -def.hipWidth)

        return SkeletonPose(joints, pose.roots)
    }
}
