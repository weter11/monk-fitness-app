package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import com.monkfitness.app.animation.SkeletonMath.lerp
import kotlin.math.*

class HipCarsPose : PoseBuilder {
    override val metadata = PoseMetadata(
        camera = CameraDefinition(defaultYaw = 1.19f, defaultPitch = 0.22f, defaultZoom = 1.3f),
        durationSeconds = 3.0f,
        loopMode = LoopMode.LOOP,
        motionCurve = MotionCurve.LINEAR,
        environment = EnvironmentDefinition(ground = GroundDefinition(visible = true, level = 0f)),
        // The STANCE foot (B/right: F is "the active working leg", B "the support leg") on the declared
        // ground for the whole rep. The circling foot is deliberately NOT declared: the foot derivation has no
        // "planted" gate, so declaring a foot the exercise lifts would drive it against a surface it has left.
        support = SupportDefinition(
            pivot = PivotType.FEET,
            contacts = setOf(SupportContact.RIGHT_FOOT)
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
        // Canonical hierarchy: the factory's added nodes are pass-throughs — LUMBAR is coincident with
        // the PELVIS and CLAVICLE_*/SCAPULA_* with the CHEST (identity) — so every transform authored
        // below resolves exactly as before.
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

        // STANDING posture: the solver owns the coarse pelvis height (seed == standH).
        SkeletonPose.IntentBuilder(jointsBuffer).posture(PostureIntent.Kind.STANDING)

        // Every target below is authored in the chain root's own frame, so the pose does not need to
        // name the solver-owned root height.
        pelvis!!.localPosition = Vector3(0f, 0f, 0f)
        declarePelvisTilt(pelvis!!, jointsBuffer, Vector3(0f, 0f, 1f), 0f)

        chest!!.localPosition = Vector3(0f, def.torsoLength, 0f)
        neck!!.localPosition = Vector3(0f, def.neckLength, 0f)
        head!!.localPosition = Vector3(0f, 18f, 0f)

        hipF!!.localPosition = Vector3(0f, 0f, -def.hipWidth)
        hipB!!.localPosition = Vector3(0f, 0f, def.hipWidth)
        shoulderA!!.localPosition = Vector3(0f, 0f, -def.shoulderWidth)
        shoulderP!!.localPosition = Vector3(0f, 0f, def.shoulderWidth)

        roots!!.forEach { it.updateWorldTransforms(Vector3(0f, 0f, 0f), JointRotation()) }

        // The working leg keeps the author's own circle, expressed against the standing foot line
        // this pose authors, so the lift is realizable as written instead of being clamped. The STANCE
        // ankle sits one reachable leg-span below the hip: the root height is solver-owned (the
        // STANDING intent pins the pelvis after this build), so a floor-anchored Y would sit a whole
        // standing root height above the hip and the solver would relocate the effector along that up
        // direction.
        val legSpan = SkeletonMath.maxReach(def.thighLength, def.shinLength, def.legIKConstraint)
        val stanceAnkleB = Vector3(0f, hipB!!.worldPosition.y - legSpan, def.hipWidth * 1.2f)
        bakeIkLimb(hipB!!.worldPosition, stanceAnkleB, def.thighLength, def.shinLength, Vector3(1f, 0f, 0.2f), def.legIKConstraint, JointRotation(), kneeB!!, ankleB!!, legBBuffer, jointsBuffer)

        // Active working leg (Foreground side F) circles smoothly in 3D space, lifted 18 ± 12 above
        // the standing foot line the stance leg just declared.
        val theta = context.progress * 2.0f * kotlin.math.PI.toFloat()
        val circleRadiusX = 15f
        val circleRadiusY = 12f
        val activeAnkleX = circleRadiusX * cos(theta)
        val activeAnkleY = stanceAnkleB.y + 18f + circleRadiusY * sin(theta)
        val targetAnkleF = Vector3(activeAnkleX, activeAnkleY, -def.hipWidth * 1.4f)

        bakeIkLimb(hipF!!.worldPosition, targetAnkleF, def.thighLength, def.shinLength, Vector3(1f, 0f, -0.2f), def.legIKConstraint, JointRotation(), kneeF!!, ankleF!!, legFBuffer, jointsBuffer)

        // The engine now derives foot/hand orientation (removed manual endpoints + tilt counter-rotation).

        // 2. Arms stay static on hips (measured from the shoulder the pose owns at build time;
        // `standH − 20` was the floor-anchored form of the same point).
        val targetHandA = Vector3(0f, shoulderA!!.worldPosition.y - def.torsoLength - 20f, -def.shoulderWidth - 5f)
        val targetHandP = Vector3(0f, shoulderP!!.worldPosition.y - def.torsoLength - 20f, def.shoulderWidth + 5f)

        bakeIkLimb(shoulderA!!.worldPosition, targetHandA, def.upperArmLength, def.forearmLength, Vector3(0f, -1f, -1f), def.armIKConstraint, JointRotation(), elbowA!!, handA!!, armABuffer, jointsBuffer)
        bakeIkLimb(shoulderP!!.worldPosition, targetHandP, def.upperArmLength, def.forearmLength, Vector3(0f, -1f, 1f), def.armIKConstraint, JointRotation(), elbowP!!, handP!!, armPBuffer, jointsBuffer)

        // The engine now derives foot/hand orientation (removed manual endpoints + tilt counter-rotation).

        SkeletonPose.fromHierarchy(roots!!, jointsBuffer)
        jointsBuffer.getJoint(Joint.WRIST_A).set(jointsBuffer.getJoint(Joint.HAND_A))
        jointsBuffer.getJoint(Joint.WRIST_P).set(jointsBuffer.getJoint(Joint.HAND_P))
        return jointsBuffer
    }
}
