package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import kotlin.math.*

class IsometricSidePlankPose : BasePose() {

    override val metadata = PoseMetadata(
        camera = CameraDefinition(defaultYaw = 1.19f, defaultPitch = 0.22f, defaultZoom = 1.3f),
        durationSeconds = 3.0f,
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
    private val legFPole = Vector3(0f, 1f, 0f)
    private val legBPole = Vector3(0f, -1f, 0f)
    private val armAPole = Vector3(-1f, 1f, -1f)
    private val armPPole = Vector3(-1f, -1f, 0f)
    private val upDir = Vector3(0f, 1f, 0f)
    private val spineAxis = Vector3(0f, 1f, 0f)

    // Reusable scratch to avoid per-frame allocations.
    private val targetAnkleF = Vector3()
    private val targetAnkleB = Vector3()
    private val targetHandA = Vector3()
    private val targetHandP = Vector3()

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

        // 1. Core Lift Geometry
        // Smoothly lifts from resting hip into the rigid side plank line
        val lift = context.progress
        val pelvisX = 0f
        val pelvisY = SkeletonMath.lerp(15f, 35f, lift)

        // Slight upward incline toward the supporting shoulder
        val torsoPitch = SkeletonMath.lerp(-1.57f, -1.4f, lift)

        pelvis!!.localPosition.set(pelvisX, pelvisY, 0f)
        pelvis!!.localRotation.set(axisZ, torsoPitch)

        chest!!.localPosition.set(0f, def.torsoLength, 0f)
        buildHead(neck!!, head!!, def.neckLength, upDir)

        // 2. The 3D Roll Matrix
        // Rotate the lateral joints 90 degrees around the local Spine (Y-axis).
        // This forces Side P (Right) to face the floor, and Side A (Left) to face the ceiling.
        // Computed in place on each node's localPosition (allocation-free); the lateral offset
        // is mapped from Z to X, which is uniquely side-plank biomechanics (not generic engine knowledge).
        val spineRoll = PI.toFloat() / 2f
        hipF!!.localPosition.set(0f, 0f, -def.hipWidth)
        SkeletonMath.rotAround(hipF.localPosition, spineAxis, spineRoll, hipF.localPosition)
        hipB!!.localPosition.set(0f, 0f, def.hipWidth)
        SkeletonMath.rotAround(hipB.localPosition, spineAxis, spineRoll, hipB.localPosition)
        shoulderA!!.localPosition.set(0f, 0f, -def.shoulderWidth)
        SkeletonMath.rotAround(shoulderA.localPosition, spineAxis, spineRoll, shoulderA.localPosition)
        shoulderP!!.localPosition.set(0f, 0f, def.shoulderWidth)
        SkeletonMath.rotAround(shoulderP.localPosition, spineAxis, spineRoll, shoulderP.localPosition)

        roots!!.forEach { it.updateWorldTransforms(zeroVector, identityRotation) }

        // 3. Stacking the Legs
        val legLength = def.thighLength + def.shinLength
        // Bottom Leg (Side P) supports the weight on the floor
        targetAnkleB.set(-legLength + 20f, 15f, 0f)
        // Top Leg (Side A) stacks exactly on top of the bottom leg
        targetAnkleF.set(-legLength + 20f, SkeletonMath.lerp(15f, 25f, lift), 0f)

        bakeIkLimb(hipF!!.worldPosition, targetAnkleF, def.thighLength, def.shinLength, legFPole, def.legIKConstraint, -torsoPitch, kneeF!!, ankleF!!, legFBuffer)
        bakeIkLimb(hipB!!.worldPosition, targetAnkleB, def.thighLength, def.shinLength, legBPole, def.legIKConstraint, -torsoPitch, kneeB!!, ankleB!!, legBBuffer)

        ankleF!!.localRotation.set(axisZ, -torsoPitch)
        ankleB!!.localRotation.set(axisZ, -torsoPitch)
        heelF!!.localPosition.set(-def.foot.footLength * def.foot.heelRatio, 0f, 0f)
        toeF!!.localPosition.set(def.foot.footLength * def.foot.toeRatio, 0f, 0f)
        heelB!!.localPosition.set(-def.foot.footLength * def.foot.heelRatio, 0f, 0f)
        toeB!!.localPosition.set(def.foot.footLength * def.foot.toeRatio, 0f, 0f)

        // 4. Arms Geometry
        // The arm IK origins (legacy manual rotAround) equal the hierarchy shoulders here, so the
        // engine-owned shoulderA/shoulderP.worldPosition are used directly — no mirrored-FK workaround needed.
        val chestW = chest!!.worldPosition

        // Support Arm (Side P): Forearm rests completely flat on the floor pushing the body up
        targetHandP.set(shoulderP!!.worldPosition.x + def.forearmLength * 0.8f, 15f, 0f)
        // Pole vector directs the elbow straight back and down onto the floor
        bakeIkLimb(shoulderP!!.worldPosition, targetHandP, def.upperArmLength, def.forearmLength, armPPole, def.armIKConstraint, -torsoPitch, elbowP!!, handP!!, armPBuffer)

        // Top Arm (Side A): Rest casually on the top hip
        targetHandA.set(pelvis!!.worldPosition.x, pelvis!!.worldPosition.y + 20f, pelvis!!.worldPosition.z)
        bakeIkLimb(shoulderA!!.worldPosition, targetHandA, def.upperArmLength, def.forearmLength, armAPole, def.armIKConstraint, -torsoPitch, elbowA!!, handA!!, armABuffer)

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
