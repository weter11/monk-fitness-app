package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import kotlin.math.*

class HamstringStretchPose : BasePose() {

    override val metadata = PoseMetadata(
        camera = CameraDefinition(defaultYaw = 1.19f, defaultPitch = 0.22f, defaultZoom = 1.25f),
        durationSeconds = 4.0f,
        loopMode = LoopMode.PING_PONG,
        motionCurve = MotionCurve.EASE_IN_OUT,
        environment = EnvironmentDefinition(ground = GroundDefinition(visible = true, level = 0f))
    )

    private var roots: List<SkeletonNode>? = null
    private var pelvis: SkeletonNode? = null; private var chest: SkeletonNode? = null; private var neck: SkeletonNode? = null; private var head: SkeletonNode? = null
    private var shoulderA: SkeletonNode? = null; private var elbowA: SkeletonNode? = null; private var handA: SkeletonNode? = null; private var palmA: SkeletonNode? = null; private var knucklesA: SkeletonNode? = null; private var fingertipsA: SkeletonNode? = null
    private var shoulderP: SkeletonNode? = null; private var elbowP: SkeletonNode? = null; private var handP: SkeletonNode? = null; private var palmP: SkeletonNode? = null; private var knucklesP: SkeletonNode? = null; private var fingertipsP: SkeletonNode? = null
    private var hipF: SkeletonNode? = null; private var kneeF: SkeletonNode? = null; private var ankleF: SkeletonNode? = null; private var heelF: SkeletonNode? = null; private var toeF: SkeletonNode? = null
    private var hipB: SkeletonNode? = null; private var kneeB: SkeletonNode? = null; private var ankleB: SkeletonNode? = null; private var heelB: SkeletonNode? = null; private var toeB: SkeletonNode? = null

    private val legFBuffer = SkeletonMath.IKResult()
    private val legBBuffer = SkeletonMath.IKResult()
    private val armABuffer = SkeletonMath.IKResult()
    private val armPBuffer = SkeletonMath.IKResult()

    // Constant IK poles (allocated once at construction, reused every frame — no per-frame allocation).
    private val frontLegPole = Vector3(0f, 1f, 0f)
    private val backLegPole = Vector3(0f, 0f, 2f)
    private val armAPole = Vector3(0f, 1f, -1f)
    private val armPPole = Vector3(0f, 1f, 1f)

    // Reusable scratch to avoid per-frame allocations.
    private val headDir = Vector3()
    private val targetAnkleF = Vector3()
    private val targetAnkleB = Vector3()
    private val targetHandA = Vector3()
    private val targetHandP = Vector3()
    private val shoulderAW = Vector3()
    private val shoulderPW = Vector3()

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

        // 1. Static Seated Root Anchor
        val pelvisX = -30f
        val pelvisY = 15f // Rest perfectly flat on the ground

        // Torso dynamically folds forward towards the leg
        val torsoPitch = SkeletonMath.lerp(0.1f, 0.9f, context.progress)

        pelvis!!.localPosition.set(pelvisX, pelvisY, 0f)
        pelvis!!.localRotation.set(axisZ, -torsoPitch)

        chest!!.localPosition.set(0f, def.torsoLength, 0f)
        headDir.set(0.1f, 1f, 0f).normalize()
        buildHead(neck!!, head!!, def.neckLength, headDir)

        buildPelvis(pelvis!!, hipF!!, hipB!!, def.hipWidth)
        buildShoulders(shoulderA!!, shoulderP!!, def.shoulderWidth)

        roots!!.forEach { it.updateWorldTransforms(zeroVector, identityRotation) }

        // 2. Asymmetric Leg Geometry
        // Leg F (Front Leg): Stretched perfectly straight forward
        targetAnkleF.set(pelvisX + def.thighLength + def.shinLength - 5f, 15f, -def.hipWidth)
        bakeIkLimb(hipF!!.worldPosition, targetAnkleF, def.thighLength, def.shinLength, frontLegPole, def.legIKConstraint, torsoPitch, kneeF!!, ankleF!!, legFBuffer)

        // Leg B (Tucked Leg): Ankle pulled close to groin, knee falls outwards (Side Z)
        targetAnkleB.set(pelvisX + 35f, 15f, def.hipWidth * 0.5f)
        // Pole vector heavily points to +Z to force the knee outwards in a seated butterfly fold
        bakeIkLimb(hipB!!.worldPosition, targetAnkleB, def.thighLength, def.shinLength, backLegPole, def.legIKConstraint, torsoPitch, kneeB!!, ankleB!!, legBBuffer)

        // Front foot points to sky, back foot lays flat sideways
        ankleF!!.localRotation.set(axisZ, torsoPitch - 1.57f)
        ankleB!!.localRotation.set(axisZ, torsoPitch)

        heelF!!.localPosition.set(def.foot.footLength * def.foot.heelRatio, 0f, 0f)
        toeF!!.localPosition.set(-def.foot.footLength * def.foot.toeRatio, 0f, 0f)
        heelB!!.localPosition.set(-def.foot.footLength * def.foot.heelRatio, 0f, 0f)
        toeB!!.localPosition.set(def.foot.footLength * def.foot.toeRatio, 0f, 0f)

        // 3. Dynamic Forward Reach
        val chestW = chest!!.worldPosition
        // Shoulders reach from the chest; mirrored forward-fold frame (preserves the legacy FK so the
        // arm IK origin matches the originally-rendered geometry exactly — no visual regression).
        shoulderAW.set(0f, 0f, -def.shoulderWidth)
        SkeletonMath.rotAround(shoulderAW, axisZ, torsoPitch, shoulderAW)
        shoulderAW.add(chestW)
        shoulderPW.set(0f, 0f, def.shoulderWidth)
        SkeletonMath.rotAround(shoulderPW, axisZ, torsoPitch, shoulderPW)
        shoulderPW.add(chestW)

        // Arms reach from the chest down toward the extended front ankle
        val startHandX = chestW.x + 30f
        val startHandY = chestW.y + 20f
        val reachX = targetAnkleF.x - 10f
        val reachY = targetAnkleF.y + 20f

        val handTargetX = SkeletonMath.lerp(startHandX, reachX, context.progress)
        val handTargetY = SkeletonMath.lerp(startHandY, reachY, context.progress)

        targetHandA.set(handTargetX, handTargetY, -def.shoulderWidth * 0.8f)
        targetHandP.set(handTargetX, handTargetY, def.shoulderWidth * 0.8f)

        // Pole vectors flare elbows slightly outward and upward
        bakeIkLimb(shoulderAW, targetHandA, def.upperArmLength, def.forearmLength, armAPole, def.armIKConstraint, -torsoPitch, elbowA!!, handA!!, armABuffer)
        bakeIkLimb(shoulderPW, targetHandP, def.upperArmLength, def.forearmLength, armPPole, def.armIKConstraint, -torsoPitch, elbowP!!, handP!!, armPBuffer)

        handA!!.localRotation.set(axisZ, -torsoPitch); handP!!.localRotation.set(axisZ, -torsoPitch)
        palmA!!.localPosition.set(6f, 0f, 0f); knucklesA!!.localPosition.set(6f, 0f, 0f); fingertipsA!!.localPosition.set(10f, 0f, 0f)
        palmP!!.localPosition.set(6f, 0f, 0f); knucklesP!!.localPosition.set(6f, 0f, 0f); fingertipsP!!.localPosition.set(10f, 0f, 0f)

        SkeletonPose.fromHierarchy(roots!!, jointsBuffer)
        jointsBuffer.getJoint(Joint.WRIST_A).set(jointsBuffer.getJoint(Joint.HAND_A))
        jointsBuffer.getJoint(Joint.WRIST_P).set(jointsBuffer.getJoint(Joint.HAND_P))
        jointsBuffer.maxIkClampAmount = maxOf(legFBuffer.clampAmount, legBBuffer.clampAmount, armABuffer.clampAmount, armPBuffer.clampAmount)
        return jointsBuffer
    }
}
