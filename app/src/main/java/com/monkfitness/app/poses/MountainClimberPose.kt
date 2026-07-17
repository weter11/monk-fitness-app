package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import com.monkfitness.app.animation.SkeletonMath.solveIK
import com.monkfitness.app.animation.SkeletonMath.lerp
import kotlin.math.*

class MountainClimberPose : PoseBuilder {
    override val metadata = PoseMetadata(
        camera = CameraDefinition(defaultYaw = 1.19f, defaultPitch = 0.22f, defaultZoom = 1.3f),
        durationSeconds = 2.0f,
        loopMode = LoopMode.LOOP,
        motionCurve = MotionCurve.LINEAR,
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

        // Root is Pelvis for perfect plank mechanics
        pelvis = SkeletonNode(Joint.PELVIS)
        chest = pelvis!!.addChild(SkeletonNode(Joint.CHEST))
        neck = chest!!.addChild(SkeletonNode(Joint.NECK_END)); head = neck!!.addChild(SkeletonNode(Joint.HEAD_POS))

        shoulderA = chest!!.addChild(SkeletonNode(Joint.SHOULDER_A)); elbowA = shoulderA!!.addChild(SkeletonNode(Joint.ELBOW_A)); handA = elbowA!!.addChild(SkeletonNode(Joint.HAND_A)); palmA = handA!!.addChild(SkeletonNode(Joint.PALM_A)); knucklesA = palmA!!.addChild(SkeletonNode(Joint.KNUCKLES_A)); fingertipsA = knucklesA!!.addChild(SkeletonNode(Joint.FINGERTIPS_A))
        shoulderP = chest!!.addChild(SkeletonNode(Joint.SHOULDER_P)); elbowP = shoulderP!!.addChild(SkeletonNode(Joint.ELBOW_P)); handP = elbowP!!.addChild(SkeletonNode(Joint.HAND_P)); palmP = handP!!.addChild(SkeletonNode(Joint.PALM_P)); knucklesP = palmP!!.addChild(SkeletonNode(Joint.KNUCKLES_P)); fingertipsP = knucklesP!!.addChild(SkeletonNode(Joint.FINGERTIPS_P))

        hipF = pelvis!!.addChild(SkeletonNode(Joint.HIP_F)); kneeF = hipF!!.addChild(SkeletonNode(Joint.KNEE_F)); ankleF = kneeF!!.addChild(SkeletonNode(Joint.ANKLE_F)); heelF = ankleF!!.addChild(SkeletonNode(Joint.HEEL_F)); toeF = ankleF!!.addChild(SkeletonNode(Joint.TOE_F))
        hipB = pelvis!!.addChild(SkeletonNode(Joint.HIP_B)); kneeB = hipB!!.addChild(SkeletonNode(Joint.KNEE_B)); ankleB = kneeB!!.addChild(SkeletonNode(Joint.ANKLE_B)); heelB = ankleB!!.addChild(SkeletonNode(Joint.HEEL_B)); toeB = ankleB!!.addChild(SkeletonNode(Joint.TOE_B))

        roots = listOf(pelvis!!)
    }

    private fun smootherStep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * t * (t * (t * 6f - 15f) + 10f)
    }

    override fun build(context: PoseContext): SkeletonPose {
        val def = context.definition
        ensureHierarchy(def)
        // B3 — every production pose declares its posture intent. This pose authors a
        // shape-driven root, so it opts into CUSTOM (the solver leaves the authored root untouched).
        SkeletonPose.IntentBuilder(jointsBuffer).posture(PostureIntent.Kind.CUSTOM)

        // 1. Rigid Plank Core Positioning
        val pelvisX = 15f
        val pelvisY = 75f
        val leanAngle = 1.50f // Lying horizontally

        pelvis!!.localPosition = Vector3(pelvisX, pelvisY, 0f)
        pelvis!!.localRotation.set(Vector3(0f, 0f, 1f), -leanAngle)

        chest!!.localPosition = Vector3(0f, def.torsoLength, 0f)
        neck!!.localPosition = Vector3(0f, def.neckLength, 0f); head!!.localPosition = Vector3(0f, 18f, 0f)
        hipF!!.localPosition = Vector3(0f, 0f, -def.hipWidth)
        hipB!!.localPosition = Vector3(0f, 0f, def.hipWidth)
        shoulderA!!.localPosition = Vector3(0f, 0f, -def.shoulderWidth)
        shoulderP!!.localPosition = Vector3(0f, 0f, def.shoulderWidth)

        // Flush Spine FK to get precise Hip and Shoulder origins
        roots!!.forEach { it.updateWorldTransforms(Vector3(0f, 0f, 0f), JointRotation()) }

        // 2. Dual-Sided Contralateral Alternation
        val cycle = context.progress * 2f * PI.toFloat()
        val rawSin = sin(cycle)
        val actF = if (rawSin > 0f) smootherStep(0f, 1f, rawSin) else 0f
        val actB = if (rawSin < 0f) smootherStep(0f, 1f, -rawSin) else 0f

        // 3. ARM TARGETS (Planted firmly on the ground)
        val targetHandA = Vector3(chest!!.worldPosition.x, 15f, -def.shoulderWidth * 1.2f)
        val targetHandP = Vector3(chest!!.worldPosition.x, 15f, def.shoulderWidth * 1.2f)

        // Solve Arm IK (straightened vertical support arms)
        val armA = solveIK(shoulderA!!.worldPosition, targetHandA, def.upperArmLength, def.forearmLength, Vector3(-1f, -1f, -1f), def.armIKConstraint, armABuffer)
        val armP = solveIK(shoulderP!!.worldPosition, targetHandP, def.upperArmLength, def.forearmLength, Vector3(-1f, -1f, 1f), def.armIKConstraint, armPBuffer)

        // Set Arm joint local coordinates (Phase 4: lean-cancel removed; engine keeps the
        // planted arm flat automatically, so the IK offset is written directly)
        elbowA!!.localPosition.set(armA.joint.x - shoulderA!!.worldPosition.x, armA.joint.y - shoulderA!!.worldPosition.y, armA.joint.z - shoulderA!!.worldPosition.z)
        handA!!.localPosition.set(armA.end.x - armA.joint.x, armA.end.y - armA.joint.y, armA.end.z - armA.joint.z)
        elbowP!!.localPosition.set(armP.joint.x - shoulderP!!.worldPosition.x, armP.joint.y - shoulderP!!.worldPosition.y, armP.joint.z - shoulderP!!.worldPosition.z)
        handP!!.localPosition.set(armP.end.x - armP.joint.x, armP.end.y - armP.joint.y, armP.end.z - armP.joint.z)

        // W1: engine now derives foot/hand orientation (removed manual endpoints + tilt counter-rotation).

        // 4. LEG TARGETS (Alternating knee pulls)
        val totalLegLen = def.thighLength + def.shinLength
        val extendedAnkleX = pelvis!!.worldPosition.x - totalLegLen * 0.94f
        val extendedAnkleY = 15f

        val bentAnkleX = pelvis!!.worldPosition.x - totalLegLen * 0.45f
        val bentAnkleY = 28f

        // Interpolate target ankle coordinates
        val targetAnkleF = Vector3(
            lerp(extendedAnkleX, bentAnkleX, actF),
            lerp(extendedAnkleY, bentAnkleY, actF) + sin(actF * PI.toFloat()) * 12f,
            -def.hipWidth * 1.2f
        )
        val targetAnkleB = Vector3(
            lerp(extendedAnkleX, bentAnkleX, actB),
            lerp(extendedAnkleY, bentAnkleY, actB) + sin(actB * PI.toFloat()) * 12f,
            def.hipWidth * 1.2f
        )

        // Solve Leg IK (knees bend up under torso)
        val legF = solveIK(hipF!!.worldPosition, targetAnkleF, def.thighLength, def.shinLength, Vector3(0f, 1f, 0f), def.legIKConstraint, legFBuffer)
        val legB = solveIK(hipB!!.worldPosition, targetAnkleB, def.thighLength, def.shinLength, Vector3(0f, 1f, 0f), def.legIKConstraint, legBBuffer)

        // Set Leg joint local coordinates (Phase 4: lean-cancel removed; the IK offset is written directly)
        kneeF!!.localPosition.set(legF.joint.x - hipF!!.worldPosition.x, legF.joint.y - hipF!!.worldPosition.y, legF.joint.z - hipF!!.worldPosition.z)
        ankleF!!.localPosition.set(legF.end.x - legF.joint.x, legF.end.y - legF.joint.y, legF.end.z - legF.joint.z)
        kneeB!!.localPosition.set(legB.joint.x - hipB!!.worldPosition.x, legB.joint.y - hipB!!.worldPosition.y, legB.joint.z - hipB!!.worldPosition.z)
        ankleB!!.localPosition.set(legB.end.x - legB.joint.x, legB.end.y - legB.joint.y, legB.end.z - legB.joint.z)

        // The engine derives heel/toe from the shank + the neutral ankle articulation. The flat
        // foot on the forward-leaning shank is intentionally NOT hand-authored here; if the engine
        // derivation lands the foot imperfectly that is an engine limitation left exposed.

        SkeletonPose.fromHierarchy(roots!!, jointsBuffer)
        jointsBuffer.getJoint(Joint.WRIST_A).set(jointsBuffer.getJoint(Joint.HAND_A))
        jointsBuffer.getJoint(Joint.WRIST_P).set(jointsBuffer.getJoint(Joint.HAND_P))
        return jointsBuffer
    }
}
