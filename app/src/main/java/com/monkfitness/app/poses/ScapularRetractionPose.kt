package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import com.monkfitness.app.animation.SkeletonMath.lerp
import kotlin.math.*

class ScapularRetractionPose : PoseBuilder {
    override val metadata = PoseMetadata(
        camera = CameraDefinition(defaultYaw = 1.19f, defaultPitch = 0.22f, defaultZoom = 1.3f),
        durationSeconds = 3.0f,
        loopMode = LoopMode.LOOP,
        motionCurve = MotionCurve.EASE_IN_OUT,
        environment = EnvironmentDefinition(ground = GroundDefinition(visible = true, level = 0f)),
        // The planted feet, on the ONE canonical support channel (`metadata.support`).
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
        // Canonical hierarchy (`SkeletonFactory.createStandardSkeleton()`): LUMBAR is coincident with
        // PELVIS and CLAVICLE_*/SCAPULA_* with CHEST, so every transform authored below resolves as before.
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

        // standH is no longer read: every target below is authored in the chain root's own frame
        // , so the pose does not need to name the solver-owned root height.
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

        // Targets are authored in the frame this pose OWNS (the chain root's own world
        // position). The root height is solver-owned (the STANDING intent pins the pelvis after
        // this build), so a floor-anchored Y sits a whole standing root height above the hip and
        // the solver relocates the effector along that upward direction. `SkeletonMath.maxReach` is
        // the chain's own reachable length, so the authored target is realizable as declared.
        val legSpan = SkeletonMath.maxReach(def.thighLength, def.shinLength, def.legIKConstraint)
        val targetAnkleF = Vector3(0f, hipF!!.worldPosition.y - legSpan, -def.hipWidth * 1.2f)
        val targetAnkleB = Vector3(0f, hipB!!.worldPosition.y - legSpan, def.hipWidth * 1.2f)

        bakeIkLimb(hipF!!.worldPosition, targetAnkleF, def.thighLength, def.shinLength, Vector3(1f, 0f, -0.2f), def.legIKConstraint, JointRotation(), kneeF!!, ankleF!!, legFBuffer, jointsBuffer)
        bakeIkLimb(hipB!!.worldPosition, targetAnkleB, def.thighLength, def.shinLength, Vector3(1f, 0f, 0.2f), def.legIKConstraint, JointRotation(), kneeB!!, ankleB!!, legBBuffer, jointsBuffer)

        // The engine now derives foot/hand orientation (removed manual endpoints + tilt counter-rotation).

        val handX = lerp(25f, 5f, context.progress)
        // Same frame rule as the feet: chest level is measured from the shoulder the pose owns
        // at build time, not from the floor-anchored root height the solver pins later.
        val handY = shoulderA!!.worldPosition.y - 20f

        val handZ_A = lerp(-def.shoulderWidth - 10f, -def.shoulderWidth - 15f, context.progress)
        val handZ_P = lerp(def.shoulderWidth + 10f, def.shoulderWidth + 15f, context.progress)

        val targetHandA = Vector3(handX, handY, handZ_A)
        val targetHandP = Vector3(handX, handY, handZ_P)

        // The squeezed hold puts the hands closer to the shoulder than the arm chain's
        // minimum reach (`SkeletonMath.minReach` at the 30° minimum-flexion stop): the declared
        // target is projected onto the reachable band first (the reach-target helper), so the
        // realized arm is what the pose declared and the reachability reading stays 0.
        SkeletonMath.clampTargetToReach(shoulderA!!.worldPosition, targetHandA, def.upperArmLength, def.forearmLength, def.armIKConstraint, targetHandA)
        SkeletonMath.clampTargetToReach(shoulderP!!.worldPosition, targetHandP, def.upperArmLength, def.forearmLength, def.armIKConstraint, targetHandP)

        // Elbow pole vector points backward and outward to force a strong scapular retraction
        bakeIkLimb(shoulderA!!.worldPosition, targetHandA, def.upperArmLength, def.forearmLength, Vector3(-1f, 0f, -1f), def.armIKConstraint, JointRotation(), elbowA!!, handA!!, armABuffer, jointsBuffer)
        bakeIkLimb(shoulderP!!.worldPosition, targetHandP, def.upperArmLength, def.forearmLength, Vector3(-1f, 0f, 1f), def.armIKConstraint, JointRotation(), elbowP!!, handP!!, armPBuffer, jointsBuffer)

        // The engine now derives foot/hand orientation (removed manual endpoints + tilt counter-rotation).

        SkeletonPose.fromHierarchy(roots!!, jointsBuffer)
        jointsBuffer.getJoint(Joint.WRIST_A).set(jointsBuffer.getJoint(Joint.HAND_A))
        jointsBuffer.getJoint(Joint.WRIST_P).set(jointsBuffer.getJoint(Joint.HAND_P))
        return jointsBuffer
    }
}
