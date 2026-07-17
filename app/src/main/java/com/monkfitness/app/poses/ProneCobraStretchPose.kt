package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import kotlin.math.*

class ProneCobraStretchPose : BasePose() {

    // Shared camera (duplicated literal removed). Default pitch raised ~10% (0.22 -> 0.242)
    // so the view tilts down slightly and the prone arch stays comfortably framed
    // (yaw/zoom unchanged, no camera redesign). Mirrors the Hip Flexor audit.
    private val cobraCamera = CameraDefinition(
        defaultYaw = 1.19f,
        defaultPitch = 0.242f,
        defaultZoom = 1.25f
    )
    private val cobraGround = EnvironmentDefinition(ground = GroundDefinition(visible = true, level = 0f))

    override val metadata = PoseMetadata(
        camera = cobraCamera,
        durationSeconds = 3.0f,
        loopMode = LoopMode.PING_PONG,
        motionCurve = MotionCurve.EASE_IN_OUT,
        environment = cobraGround
    )

    private var roots: List<SkeletonNode>? = null
    private var pelvis: SkeletonNode? = null; private var chest: SkeletonNode? = null; private var neck: SkeletonNode? = null; private var head: SkeletonNode? = null
    private var shoulderA: SkeletonNode? = null; private var elbowA: SkeletonNode? = null; private var handA: SkeletonNode? = null; private var palmA: SkeletonNode? = null; private var knucklesA: SkeletonNode? = null; private var fingertipsA: SkeletonNode? = null
    private var shoulderP: SkeletonNode? = null; private var elbowP: SkeletonNode? = null; private var handP: SkeletonNode? = null; private var palmP: SkeletonNode? = null; private var knucklesP: SkeletonNode? = null; private var fingertipsP: SkeletonNode? = null
    private var hipF: SkeletonNode? = null; private var kneeF: SkeletonNode? = null; private var ankleF: SkeletonNode? = null; private var heelF: SkeletonNode? = null; private var toeF: SkeletonNode? = null
    private var hipB: SkeletonNode? = null; private var kneeB: SkeletonNode? = null; private var ankleB: SkeletonNode? = null; private var heelB: SkeletonNode? = null; private var toeB: SkeletonNode? = null

    private val legFBuffer = SkeletonMath.IKResult(); private val legBBuffer = SkeletonMath.IKResult()
    private val armABuffer = SkeletonMath.IKResult(); private val armPBuffer = SkeletonMath.IKResult()

    private fun ensureHierarchy(def: SkeletonDefinition) {
        if (roots != null) return
        val nodes = SkeletonFactory.createStandardSkeleton()
        roots = nodes.roots
        pelvis = nodes.pelvis
        chest = nodes.chest
        neck = nodes.neck
        head = nodes.head
        shoulderA = nodes.shoulderA
        elbowA = nodes.elbowA
        handA = nodes.handA
        palmA = nodes.palmA
        knucklesA = nodes.knucklesA
        fingertipsA = nodes.fingertipsA
        shoulderP = nodes.shoulderP
        elbowP = nodes.elbowP
        handP = nodes.handP
        palmP = nodes.palmP
        knucklesP = nodes.knucklesP
        fingertipsP = nodes.fingertipsP
        hipF = nodes.hipF
        kneeF = nodes.kneeF
        ankleF = nodes.ankleF
        heelF = nodes.heelF
        toeF = nodes.toeF
        hipB = nodes.hipB
        kneeB = nodes.kneeB
        ankleB = nodes.ankleB
        heelB = nodes.heelB
        toeB = nodes.toeB
    }

    override fun build(context: PoseContext): SkeletonPose {
        val def = context.definition
        ensureHierarchy(def)
        // B3 — every production pose declares its posture intent. This pose authors a
        // shape-driven root, so it opts into CUSTOM (the solver leaves the authored root untouched).
        declarePosture(jointsBuffer, PostureIntent.Kind.CUSTOM)

        // 1. Core Anchoring (Lying Flat)
        val pelvisX = 0f
        val pelvisY = 15f // Rest perfectly flat on the ground

        // Torso transitions from lying flat (-90 deg) to an arched extension
        val torsoPitch = SkeletonMath.lerp(-1.57f, -0.9f, context.progress)

        pelvis!!.localPosition.set(pelvisX, pelvisY, 0f)
        pelvis!!.localRotation.set(axisZ, torsoPitch)

        chest!!.localPosition.set(0f, def.torsoLength, 0f)

        // Head tilts up dynamically to follow the cobra stretch. Declared as a gaze target
        // (Phase 7 Gap 7) while the legacy direction path still writes the head.
        val headTilt = SkeletonMath.lerp(0f, -0.3f, context.progress)
        val headDir = SkeletonMath.rotAround(Vector3(0.2f, 1f, 0f), axisZ, headTilt, Vector3()).normalize()
        buildGaze(neck!!, head!!, def.neckLength, headDir)

        buildPelvis(pelvis!!, hipF!!, hipB!!, def.hipWidth)
        buildShoulders(shoulderA!!, shoulderP!!, def.shoulderWidth)

        roots!!.forEach { it.updateWorldTransforms(zeroVector, identityRotation) }

        // 2. Legs (Stretched flat on the floor)
        // IK pulls the ankles backward along the floor line (-X direction)
        val targetAnkleX = pelvisX - def.thighLength - def.shinLength + 15f
        val targetAnkleF = Vector3(targetAnkleX, 15f, -def.hipWidth)
        val targetAnkleB = Vector3(targetAnkleX, 15f, def.hipWidth)

        bakeIkLimb(hipF!!.worldPosition, targetAnkleF, def.thighLength, def.shinLength, Vector3(0f, 1f, 0f), def.legIKConstraint, pelvis!!.worldRotation, kneeF!!, ankleF!!, legFBuffer)
        bakeIkLimb(hipB!!.worldPosition, targetAnkleB, def.thighLength, def.shinLength, Vector3(0f, 1f, 0f), def.legIKConstraint, pelvis!!.worldRotation, kneeB!!, ankleB!!, legBBuffer)

        // W1: engine now derives foot/hand orientation (removed manual endpoints + tilt counter-rotation).

        // 3. Arms (Active Reach Backward)
        val chestW = chest!!.worldPosition

        // Hands lift off the ground and sweep back towards the hips/heels
        val reachX = SkeletonMath.lerp(chestW.x, pelvisX - 50f, context.progress)
        val reachY = SkeletonMath.lerp(15f, 40f, context.progress)

        val targetHandA = Vector3(reachX, reachY, -def.shoulderWidth * 1.5f)
        val targetHandP = Vector3(reachX, reachY, def.shoulderWidth * 1.5f)

        // Pole vectors orient elbows upward and outward to squeeze the shoulder blades
        bakeIkLimb(shoulderA!!.worldPosition, targetHandA, def.upperArmLength, def.forearmLength, Vector3(0f, 1f, -1f), def.armIKConstraint, chest!!.worldRotation, elbowA!!, handA!!, armABuffer)
        bakeIkLimb(shoulderP!!.worldPosition, targetHandP, def.upperArmLength, def.forearmLength, Vector3(0f, 1f, 1f), def.armIKConstraint, chest!!.worldRotation, elbowP!!, handP!!, armPBuffer)

        // Hands parallel to the floor, palms facing down
        // W1: engine now derives hand orientation (removed tilt counter-rotation + 6/6/10 offsets).

        SkeletonPose.fromHierarchy(roots!!, jointsBuffer)
        jointsBuffer.getJoint(Joint.WRIST_A).set(jointsBuffer.getJoint(Joint.HAND_A))
        jointsBuffer.getJoint(Joint.WRIST_P).set(jointsBuffer.getJoint(Joint.HAND_P))
        return jointsBuffer
    }
}
