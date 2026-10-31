package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import com.monkfitness.app.animation.SkeletonMath.lerp
import kotlin.math.*

class FacePullPose : PoseBuilder {
    override val metadata = PoseMetadata(
        camera = CameraDefinition(defaultYaw = 1.19f, defaultPitch = 0.22f, defaultZoom = 1.3f),
        durationSeconds = 2.5f,
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

        // STANDING posture: the solver owns the coarse pelvis height (seed == standH), and every
        // target below is authored in the chain root's own frame.
        SkeletonPose.IntentBuilder(jointsBuffer).posture(PostureIntent.Kind.STANDING)
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

        // Targets are authored in the frame this pose OWNS (the chain root's world position). The root
        // height is solver-owned, so a floor-anchored Y sits a whole standing root height above the hip and
        // the solver relocates the effector along that upward direction. [legSpan] is the chain's own
        // `maxReach`, so the target is realizable as declared.
        val legSpan = SkeletonMath.maxReach(def.thighLength, def.shinLength, def.legIKConstraint)
        val targetAnkleF = Vector3(0f, hipF!!.worldPosition.y - legSpan, -def.hipWidth * 1.2f)
        val targetAnkleB = Vector3(0f, hipB!!.worldPosition.y - legSpan, def.hipWidth * 1.2f)

        bakeIkLimb(hipF!!.worldPosition, targetAnkleF, def.thighLength, def.shinLength, Vector3(1f, 0f, -0.2f), def.legIKConstraint, JointRotation(), kneeF!!, ankleF!!, legFBuffer, jointsBuffer)
        bakeIkLimb(hipB!!.worldPosition, targetAnkleB, def.thighLength, def.shinLength, Vector3(1f, 0f, 0.2f), def.legIKConstraint, JointRotation(), kneeB!!, ankleB!!, legBBuffer, jointsBuffer)

        // The engine now derives foot/hand orientation (removed manual endpoints + tilt counter-rotation).

        // Hands move from fully extended forward (holding band) to high and wide back near ears.
        val startHandX = 40f
        val endHandX = -15f
        val handX = lerp(startHandX, endHandX, context.progress)

        // Same frame rule as the feet: the band's hand travel is authored around the shoulder
        // the pose owns at build time (−10 → 0 from it), not around the floor-anchored root height.
        val startHandY = shoulderA!!.worldPosition.y - 10f
        val endHandY = shoulderA!!.worldPosition.y
        val handY = lerp(startHandY, endHandY, context.progress)

        val startHandZ_A = -10f
        val endHandZ_A = -def.shoulderWidth - 15f
        val handZ_A = lerp(startHandZ_A, endHandZ_A, context.progress)

        val startHandZ_P = 10f
        val endHandZ_P = def.shoulderWidth + 15f
        val handZ_P = lerp(startHandZ_P, endHandZ_P, context.progress)

        val targetHandA = Vector3(handX, handY, handZ_A)
        val targetHandP = Vector3(handX, handY, handZ_P)

        // The authored fold at the top of the pull is tighter than the arm chain's minimum reach, so the
        // declared target is projected onto the reachable band before the bake: the realized arm is exactly
        // what the pose declared, and the reachability signal the validator reads stays 0 instead of an
        // artifact of the fold.
        SkeletonMath.clampTargetToReach(shoulderA!!.worldPosition, targetHandA, def.upperArmLength, def.forearmLength, def.armIKConstraint, targetHandA)
        SkeletonMath.clampTargetToReach(shoulderP!!.worldPosition, targetHandP, def.upperArmLength, def.forearmLength, def.armIKConstraint, targetHandP)

        // Elbow pole vector points high and wide outwards/backwards
        bakeIkLimb(shoulderA!!.worldPosition, targetHandA, def.upperArmLength, def.forearmLength, Vector3(-1f, 1f, -1f), def.armIKConstraint, JointRotation(), elbowA!!, handA!!, armABuffer, jointsBuffer)
        bakeIkLimb(shoulderP!!.worldPosition, targetHandP, def.upperArmLength, def.forearmLength, Vector3(-1f, 1f, 1f), def.armIKConstraint, JointRotation(), elbowP!!, handP!!, armPBuffer, jointsBuffer)

        // The engine now derives foot/hand orientation (removed manual endpoints + tilt counter-rotation).

        SkeletonPose.fromHierarchy(roots!!, jointsBuffer)
        jointsBuffer.getJoint(Joint.WRIST_A).set(jointsBuffer.getJoint(Joint.HAND_A))
        jointsBuffer.getJoint(Joint.WRIST_P).set(jointsBuffer.getJoint(Joint.HAND_P))
        return jointsBuffer
    }
}
