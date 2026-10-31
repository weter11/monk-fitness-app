package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import com.monkfitness.app.animation.SkeletonMath.lerp
import kotlin.math.*

class ArmCirclesPose : PoseBuilder {
    override val metadata = PoseMetadata(
        camera = CameraDefinition(defaultYaw = 1.19f, defaultPitch = 0.22f, defaultZoom = 1.3f),
        durationSeconds = 3.0f,
        loopMode = LoopMode.LOOP,
        motionCurve = MotionCurve.LINEAR,
        environment = EnvironmentDefinition(ground = GroundDefinition(visible = true, level = 0f)),
        // The planted feet, declared on the ONE canonical support channel; the engine lays each
        // foot in the declared surface plane from the declaration alone.
        support = SupportDefinition(
            pivot = PivotType.FEET,
            contacts = setOf(SupportContact.LEFT_FOOT, SupportContact.RIGHT_FOOT)
        )
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
        // Canonical hierarchy: LUMBAR is coincident with the PELVIS and CLAVICLE_*/SCAPULA_* with the
        // CHEST (identity rotation), so every transform authored below resolves exactly as before.
        val nodes = SkeletonFactory.createStandardSkeleton()
        roots = nodes.roots
        pelvis = nodes.pelvis; chest = nodes.chest; neck = nodes.neck; head = nodes.head
        shoulderA = nodes.shoulderA; elbowA = nodes.elbowA; handA = nodes.handA
        palmA = nodes.palmA; knucklesA = nodes.knucklesA; fingertipsA = nodes.fingertipsA
        shoulderP = nodes.shoulderP; elbowP = nodes.elbowP; handP = nodes.handP
        palmP = nodes.palmP; knucklesP = nodes.knucklesP; fingertipsP = nodes.fingertipsP
        hipF = nodes.hipF; kneeF = nodes.kneeF; ankleF = nodes.ankleF; heelF = nodes.heelF; toeF = nodes.toeF
        hipB = nodes.hipB; kneeB = nodes.kneeB; ankleB = nodes.ankleB; heelB = nodes.heelB; toeB = nodes.toeB
    }

    override fun build(context: PoseContext): SkeletonPose {
        SkeletonPose.IntentBuilder(jointsBuffer).reset()
        val def = context.definition
        ensureHierarchy(def)

        // The coarse pelvis height is owned by the ConstraintSolver (STANDING seed == standH), so
        // the pose declares the intent and no longer hand-writes pelvis.y.
        SkeletonPose.IntentBuilder(jointsBuffer).posture(PostureIntent.Kind.STANDING)

        pelvis!!.localPosition = Vector3(0f, 0f, 0f)
        declarePelvisTilt(pelvis!!, jointsBuffer, Vector3(0f, 0f, 1f), 0f)

        // standH is no longer read: the hand circle is authored around the shoulder the pose owns
        // at build time, not around the floor-anchored root height the solver pins later.

        chest!!.localPosition = Vector3(0f, def.torsoLength, 0f)
        neck!!.localPosition = Vector3(0f, def.neckLength, 0f)
        head!!.localPosition = Vector3(0f, 18f, 0f)

        hipF!!.localPosition = Vector3(0f, 0f, -def.hipWidth)
        hipB!!.localPosition = Vector3(0f, 0f, def.hipWidth)
        shoulderA!!.localPosition = Vector3(0f, 0f, -def.shoulderWidth)
        shoulderP!!.localPosition = Vector3(0f, 0f, def.shoulderWidth)

        roots!!.forEach { it.updateWorldTransforms(Vector3(0f, 0f, 0f), JointRotation()) }

        // Every IK target below is authored in the frame this pose OWNS (the chain root's own
        // world position). The root height is solver-owned (the solver pins the pelvis after this build), so a
        // floor-anchored target (`def.foot.ankleHeight`) sits a whole standing root height above the hip
        // — below the chain's minimum reach — and the solver answers by relocating the effector along
        // that upward direction. [legSpan] is the chain's own `maxReach`, so the target is realizable
        // exactly as declared.
        val legSpan = SkeletonMath.maxReach(def.thighLength, def.shinLength, def.legIKConstraint)
        val targetAnkleF = Vector3(0f, hipF!!.worldPosition.y - legSpan, -def.hipWidth * 1.2f)
        val targetAnkleB = Vector3(0f, hipB!!.worldPosition.y - legSpan, def.hipWidth * 1.2f)

        bakeIkLimb(hipF!!.worldPosition, targetAnkleF, def.thighLength, def.shinLength, Vector3(1f, 0f, -0.2f), def.legIKConstraint, JointRotation(), kneeF!!, ankleF!!, legFBuffer, jointsBuffer)
        bakeIkLimb(hipB!!.worldPosition, targetAnkleB, def.thighLength, def.shinLength, Vector3(1f, 0f, 0.2f), def.legIKConstraint, JointRotation(), kneeB!!, ankleB!!, legBBuffer, jointsBuffer)

        // The engine now derives foot/hand orientation (removed manual endpoints + tilt counter-rotation).

        val radius = (def.upperArmLength + def.forearmLength) * 0.88f // slightly bent to satisfy IK constraint
        val theta = context.progress * 2.0f * kotlin.math.PI.toFloat()

        val handX = radius * cos(theta)
        // Same frame rule as the feet: the circle is centred on the shoulder the pose actually
        // has at build time, not on the floor-anchored height the solver pins later.
        val handY = shoulderA!!.worldPosition.y + radius * sin(theta)

        val targetHandA = Vector3(handX, handY, -def.shoulderWidth - 10f)
        val targetHandP = Vector3(handX, handY, def.shoulderWidth + 10f)

        bakeIkLimb(shoulderA!!.worldPosition, targetHandA, def.upperArmLength, def.forearmLength, Vector3(0f, -1f, -1f), def.armIKConstraint, JointRotation(), elbowA!!, handA!!, armABuffer, jointsBuffer)
        bakeIkLimb(shoulderP!!.worldPosition, targetHandP, def.upperArmLength, def.forearmLength, Vector3(0f, -1f, 1f), def.armIKConstraint, JointRotation(), elbowP!!, handP!!, armPBuffer, jointsBuffer)

        // The engine now derives foot/hand orientation (removed manual endpoints + tilt counter-rotation).

        SkeletonPose.fromHierarchy(roots!!, jointsBuffer)
        jointsBuffer.getJoint(Joint.WRIST_A).set(jointsBuffer.getJoint(Joint.HAND_A))
        jointsBuffer.getJoint(Joint.WRIST_P).set(jointsBuffer.getJoint(Joint.HAND_P))
        return jointsBuffer
    }
}
