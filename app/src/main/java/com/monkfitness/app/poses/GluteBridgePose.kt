package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import com.monkfitness.app.animation.SkeletonMath.solveIK
import com.monkfitness.app.animation.SkeletonMath.lerp
import kotlin.math.*

class GluteBridgePose : PoseBuilder {
    override val metadata = PoseMetadata(
        camera = CameraDefinition(defaultYaw = 1.19f, defaultPitch = 0.22f, defaultZoom = 1.3f),
        durationSeconds = 3.0f,
        loopMode = LoopMode.LOOP,
        motionCurve = MotionCurve.EASE_IN_OUT,
        environment = EnvironmentDefinition(ground = GroundDefinition(visible = true, level = 0f))
    )

    private var roots: List<SkeletonNode>? = null
    private var pelvis: SkeletonNode? = null; private var chest: SkeletonNode? = null; private var neck: SkeletonNode? = null; private var head: SkeletonNode? = null
    private var shoulderA: SkeletonNode? = null; private var elbowA: SkeletonNode? = null; private var handA: SkeletonNode? = null; private var palmA: SkeletonNode? = null; private var knucklesA: SkeletonNode? = null; private var fingertipsA: SkeletonNode? = null
    private var shoulderP: SkeletonNode? = null; private var elbowP: SkeletonNode? = null; private var handP: SkeletonNode? = null; private var palmP: SkeletonNode? = null; private var knucklesP: SkeletonNode? = null; private var fingertipsP: SkeletonNode? = null
    private var hipF: SkeletonNode? = null; private var kneeF: SkeletonNode? = null; private var ankleF: SkeletonNode? = null; private var heelF: SkeletonNode? = null; private var toeF: SkeletonNode? = null
    private var hipB: SkeletonNode? = null; private var kneeB: SkeletonNode? = null; private var ankleB: SkeletonNode? = null; private var heelB: SkeletonNode? = null; private var toeB: SkeletonNode? = null

    private val jointsBuffer = SkeletonPose()
    private val legFBuffer = SkeletonMath.IKResult()
    private val legBBuffer = SkeletonMath.IKResult()
    private val armABuffer = SkeletonMath.IKResult()
    private val armPBuffer = SkeletonMath.IKResult()

    private fun ensureHierarchy(def: SkeletonDefinition) {
        if (roots != null) return

        pelvis = SkeletonNode(Joint.PELVIS)
        chest = pelvis!!.addChild(SkeletonNode(Joint.CHEST))
        neck = chest!!.addChild(SkeletonNode(Joint.NECK_END))
        head = neck!!.addChild(SkeletonNode(Joint.HEAD_POS))

        shoulderA = chest!!.addChild(SkeletonNode(Joint.SHOULDER_A))
        elbowA = shoulderA!!.addChild(SkeletonNode(Joint.ELBOW_A))
        handA = elbowA!!.addChild(SkeletonNode(Joint.HAND_A))
        palmA = handA!!.addChild(SkeletonNode(Joint.PALM_A))
        knucklesA = palmA!!.addChild(SkeletonNode(Joint.KNUCKLES_A))
        fingertipsA = knucklesA!!.addChild(SkeletonNode(Joint.FINGERTIPS_A))

        shoulderP = chest!!.addChild(SkeletonNode(Joint.SHOULDER_P))
        elbowP = shoulderP!!.addChild(SkeletonNode(Joint.ELBOW_P))
        handP = elbowP!!.addChild(SkeletonNode(Joint.HAND_P))
        palmP = handP!!.addChild(SkeletonNode(Joint.PALM_P))
        knucklesP = palmP!!.addChild(SkeletonNode(Joint.KNUCKLES_P))
        fingertipsP = knucklesP!!.addChild(SkeletonNode(Joint.FINGERTIPS_P))

        hipF = pelvis!!.addChild(SkeletonNode(Joint.HIP_F))
        kneeF = hipF!!.addChild(SkeletonNode(Joint.KNEE_F))
        ankleF = kneeF!!.addChild(SkeletonNode(Joint.ANKLE_F))
        heelF = ankleF!!.addChild(SkeletonNode(Joint.HEEL_F))
        toeF = ankleF!!.addChild(SkeletonNode(Joint.TOE_F))

        hipB = pelvis!!.addChild(SkeletonNode(Joint.HIP_B))
        kneeB = hipB!!.addChild(SkeletonNode(Joint.KNEE_B))
        ankleB = kneeB!!.addChild(SkeletonNode(Joint.ANKLE_B))
        heelB = ankleB!!.addChild(SkeletonNode(Joint.HEEL_B))
        toeB = ankleB!!.addChild(SkeletonNode(Joint.TOE_B))

        roots = listOf(pelvis!!)
    }

    override fun build(context: PoseContext): SkeletonPose {
        val def = context.definition
        ensureHierarchy(def)
        // B3 — every production pose declares its posture intent. This pose authors a
        // shape-driven root, so it opts into CUSTOM (the solver leaves the authored root untouched).
        SkeletonPose.IntentBuilder(jointsBuffer).posture(PostureIntent.Kind.CUSTOM)

        // Supine glute bridge: lying on the back
        // progress 0 (resting on floor) to 1 (bridge peak)
        val pelvisY = lerp(14f, 54f, context.progress)
        val angleOffset = lerp(0f, 0.35f, context.progress)
        val torsoAngle = 1.5708f + angleOffset

        pelvis!!.localPosition = Vector3(0f, pelvisY, 0f)
        declarePelvisTilt(pelvis!!, jointsBuffer, Vector3(0f, 0f, 1f), torsoAngle)
        SkeletonPose.IntentBuilder(jointsBuffer).joint(Joint.PELVIS, JointRotation(Vector3(0f, 0f, 1f), torsoAngle))

        chest!!.localPosition = Vector3(0f, def.torsoLength, 0f)

        // Neck/Head stay flat on the ground.
        // World rotation of neck/head is horizontal (1.5708f pointing along negative X).
        // Since chest world rotation is torsoAngle, localRotation of neck is set to -angleOffset.
        neck!!.localPosition = Vector3(0f, def.neckLength, 0f)
        neck!!.localRotation.set(Vector3(0f, 0f, 1f), -angleOffset)
        // B4a — carrier-backed neck ROM: record the neck articulation as a joint intent so the
        // Finalizer (B2) consumes it idempotently (mixed mode, byte-identical to the bare write).
        SkeletonPose.IntentBuilder(jointsBuffer).joint(Joint.NECK_END, JointRotation(Vector3(0f, 0f, 1f), -angleOffset))
        head!!.localPosition = Vector3(0f, 18f, 0f)

        hipF!!.localPosition = Vector3(0f, 0f, -def.hipWidth)
        hipB!!.localPosition = Vector3(0f, 0f, def.hipWidth)
        shoulderA!!.localPosition = Vector3(0f, 0f, -def.shoulderWidth)
        shoulderP!!.localPosition = Vector3(0f, 0f, def.shoulderWidth)

        // Compute Spine transforms
        roots!!.forEach { it.updateWorldTransforms(Vector3(0f, 0f, 0f), JointRotation()) }

        // 1. Static Foot Placements (prevent foot sliding and penetration)
        val targetAnkleF = Vector3(45f, def.foot.ankleHeight, -def.hipWidth)
        val targetAnkleB = Vector3(45f, def.foot.ankleHeight, def.hipWidth)

        // Solve leg IK (knee points upwards/forwards, so use upper pole vector)
        val legFIK = solveIK(hipF!!.worldPosition, targetAnkleF, def.thighLength, def.shinLength, Vector3(0.5f, 1f, 0f), def.legIKConstraint, legFBuffer)
        val legBIK = solveIK(hipB!!.worldPosition, targetAnkleB, def.thighLength, def.shinLength, Vector3(0.5f, 1f, 0f), def.legIKConstraint, legBBuffer)

        // Set knee and ankle positions (Phase 4: lean-cancel removed; the IK offset is written directly)
        kneeF!!.localPosition.set(legFIK.joint.x - hipF!!.worldPosition.x, legFIK.joint.y - hipF!!.worldPosition.y, legFIK.joint.z - hipF!!.worldPosition.z)
        ankleF!!.localPosition.set(legFIK.end.x - legFIK.joint.x, legFIK.end.y - legFIK.joint.y, legFIK.end.z - legFIK.joint.z)

        kneeB!!.localPosition.set(legBIK.joint.x - hipB!!.worldPosition.x, legBIK.joint.y - hipB!!.worldPosition.y, legBIK.joint.z - hipB!!.worldPosition.z)
        ankleB!!.localPosition.set(legBIK.end.x - legBIK.joint.x, legBIK.end.y - legBIK.joint.y, legBIK.end.z - legBIK.joint.z)

        // W1 migration: the engine now derives heel/toe from the shank + the ankle articulation
        // relative to the shank, cancelling the inherited pelvis/torso rotation automatically. The
        // old `ankle.localRotation = -torsoAngle` counter-rotation and the manual 0.29/0.71 heel/toe
        // authoring are removed; a neutral (identity) ankle lays the foot flat against the floor.

        // 2. Arm placement (lying flat alongside the body)
        // Hand coordinates are static (prevents hand sliding)
        val targetHandA = Vector3(-35f, 12f, -def.shoulderWidth - 5f)
        val targetHandP = Vector3(-35f, 12f, def.shoulderWidth + 5f)

        val armAIK = solveIK(shoulderA!!.worldPosition, targetHandA, def.upperArmLength, def.forearmLength, Vector3(0f, -1f, -1f), def.armIKConstraint, armABuffer)
        val armPIK = solveIK(shoulderP!!.worldPosition, targetHandP, def.upperArmLength, def.forearmLength, Vector3(0f, -1f, 1f), def.armIKConstraint, armPBuffer)

        // Set elbow and hand positions (Phase 4: lean-cancel removed; the IK offset is written directly)
        elbowA!!.localPosition.set(armAIK.joint.x - shoulderA!!.worldPosition.x, armAIK.joint.y - shoulderA!!.worldPosition.y, armAIK.joint.z - shoulderA!!.worldPosition.z)
        handA!!.localPosition.set(armAIK.end.x - armAIK.joint.x, armAIK.end.y - armAIK.joint.y, armAIK.end.z - armAIK.joint.z)

        elbowP!!.localPosition.set(armPIK.joint.x - shoulderP!!.worldPosition.x, armPIK.joint.y - shoulderP!!.worldPosition.y, armPIK.joint.z - shoulderP!!.worldPosition.z)
        handP!!.localPosition.set(armPIK.end.x - armPIK.joint.x, armPIK.end.y - armPIK.joint.y, armPIK.end.z - armPIK.joint.z)

        // W1 migration: the engine derives palm/knuckles/fingertips from the forearm + the wrist
        // articulation relative to the forearm, so the old `hand.localRotation = -torsoAngle`
        // counter-rotation and the manual 6/6/10 hand authoring are removed; a neutral wrist lays
        // the hand flat along the forearm.

        SkeletonPose.fromHierarchy(roots!!, jointsBuffer)
        jointsBuffer.getJoint(Joint.WRIST_A).set(jointsBuffer.getJoint(Joint.HAND_A))
        jointsBuffer.getJoint(Joint.WRIST_P).set(jointsBuffer.getJoint(Joint.HAND_P))
        return jointsBuffer
    }
}
