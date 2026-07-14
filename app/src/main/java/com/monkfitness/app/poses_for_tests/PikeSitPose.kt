package com.monkfitness.app.poses_for_tests

import com.monkfitness.app.animation.*
import kotlin.math.*

/**
 * PikeSitPose — Biomechanics validation snapshot.
 *
 * Seated on the floor, legs together and fully extended forward, knees
 * locked, ankles neutral, feet together, torso perfectly upright (lumbar
 * neutral, thoracic upright), head neutral, both arms fully extended
 * forward at shoulder height with palms facing inward.
 *
 * STATIC reference pose — ignores [PoseContext.progress]. No breathing,
 * no sway, no interpolation, no animation driver.
 *
 * Engine systems validated:
 *  - SkeletonFactory + BasePose helpers
 *  - SkeletonPoseFinalizer
 *  - bakeIkLimb with frame-relative pole vectors (forward legs + forward arms)
 *  - corrected IK + corrected proportions (posterior chain + shoulders)
 *
 * Joints deliberately stressed: hips (flexion), knees (locked), ankles
 * (neutral), lumbar/thoracic (upright), shoulders (flexion), elbows
 * (locked), wrists (palms inward).
 */
class PikeSitPose : BasePose() {

    private var roots: List<SkeletonNode>? = null
    private var pelvis: SkeletonNode? = null; private var chest: SkeletonNode? = null; private var neck: SkeletonNode? = null; private var head: SkeletonNode? = null
    private var shoulderA: SkeletonNode? = null; private var elbowA: SkeletonNode? = null; private var handA: SkeletonNode? = null; private var palmA: SkeletonNode? = null; private var knucklesA: SkeletonNode? = null; private var fingertipsA: SkeletonNode? = null
    private var shoulderP: SkeletonNode? = null; private var elbowP: SkeletonNode? = null; private var handP: SkeletonNode? = null; private var palmP: SkeletonNode? = null; private var knucklesP: SkeletonNode? = null; private var fingertipsP: SkeletonNode? = null
    private var hipF: SkeletonNode? = null; private var kneeF: SkeletonNode? = null; private var ankleF: SkeletonNode? = null; private var heelF: SkeletonNode? = null; private var toeF: SkeletonNode? = null
    private var hipB: SkeletonNode? = null; private var kneeB: SkeletonNode? = null; private var ankleB: SkeletonNode? = null; private var heelB: SkeletonNode? = null; private var toeB: SkeletonNode? = null

    private val legFBuffer = SkeletonMath.IKResult(); private val legBBuffer = SkeletonMath.IKResult()
    private val armABuffer = SkeletonMath.IKResult(); private val armPBuffer = SkeletonMath.IKResult()

    private val targetF = Vector3(); private val targetB = Vector3()
    private val targetA = Vector3(); private val targetP = Vector3()
    private val poleF = Vector3(); private val poleB = Vector3()
    private val poleA = Vector3(); private val poleP = Vector3()

    override val metadata = PoseMetadata(
        camera = CameraDefinition(defaultYaw = 1.19f, defaultPitch = 0.22f, defaultZoom = 1.2f),
        durationSeconds = 0f,
        loopMode = LoopMode.HOLD,
        motionCurve = MotionCurve.LINEAR,
        environment = EnvironmentDefinition(ground = GroundDefinition(visible = true, level = 0f)),
        pivotType = PivotType.FEET,
        support = SupportDefinition(pivot = PivotType.FEET, contacts = bothFeet())
    )

    private fun ensureHierarchy(def: SkeletonDefinition) {
        if (roots != null) return
        val nodes = SkeletonFactory.createStandardSkeleton()
        roots = nodes.roots
        pelvis = nodes.pelvis; chest = nodes.chest; neck = nodes.neck; head = nodes.head
        shoulderA = nodes.shoulderA; elbowA = nodes.elbowA; handA = nodes.handA; palmA = nodes.palmA; knucklesA = nodes.knucklesA; fingertipsA = nodes.fingertipsA
        shoulderP = nodes.shoulderP; elbowP = nodes.elbowP; handP = nodes.handP; palmP = nodes.palmP; knucklesP = nodes.knucklesP; fingertipsP = nodes.fingertipsP
        hipF = nodes.hipF; kneeF = nodes.kneeF; ankleF = nodes.ankleF; heelF = nodes.heelF; toeF = nodes.toeF
        hipB = nodes.hipB; kneeB = nodes.kneeB; ankleB = nodes.ankleB; heelB = nodes.heelB; toeB = nodes.toeB
    }

    override fun build(context: PoseContext): SkeletonPose {
        val def = context.definition
        ensureHierarchy(def)

        // Seated, centered, perfectly upright neutral spine.
        val pelvisY = 0f
        pelvis!!.localPosition.set(0f, pelvisY, 0f)
        pelvis!!.localRotation.set(axisZ, 0f)

        chest!!.localPosition.set(0f, def.torsoLength, 0f)
        chest!!.localRotation.set(axisZ, 0f)
        buildHead(neck!!, head!!, def.neckLength, Vector3(0f, 1f, 0f))
        buildPelvis(pelvis!!, hipF!!, hipB!!, def.hipWidth)
        shoulderA!!.localPosition.set(0f, 0f, -def.shoulderWidth)
        shoulderP!!.localPosition.set(0f, 0f, def.shoulderWidth)

        roots!!.forEach { it.updateWorldTransforms(zeroVector, identityRotation) }

        // Legs together, fully extended forward, knees locked, feet together.
        val legReach = (def.thighLength + def.shinLength) * def.legIKConstraint.maximumExtensionRatio * 0.985f
        targetF.set(legReach, 0f, -2f)
        targetB.set(legReach, 0f, 2f)
        poleF.set(0f, 1f, 0f)
        poleB.set(0f, 1f, 0f)

        bakeIkLimb(hipF!!.worldPosition, targetF, def.thighLength, def.shinLength, poleF, pelvis!!.worldRotation, def.legIKConstraint, 0f, kneeF!!, ankleF!!, legFBuffer)
        bakeIkLimb(hipB!!.worldPosition, targetB, def.thighLength, def.shinLength, poleB, pelvis!!.worldRotation, def.legIKConstraint, 0f, kneeB!!, ankleB!!, legBBuffer)

        ankleF!!.localRotation.set(axisZ, 0f); ankleB!!.localRotation.set(axisZ, 0f)
        heelF!!.localPosition.set(-def.foot.footLength * def.foot.heelRatio, 0f, 0f); toeF!!.localPosition.set(def.foot.footLength * def.foot.toeRatio, 0f, 0f)
        heelB!!.localPosition.set(-def.foot.footLength * def.foot.heelRatio, 0f, 0f); toeB!!.localPosition.set(def.foot.footLength * def.foot.toeRatio, 0f, 0f)

        // Arms fully extended forward, elbows locked, palms facing inward.
        val armReach = (def.upperArmLength + def.forearmLength) * def.armIKConstraint.maximumExtensionRatio * 0.985f
        val shoulderY = pelvisY + def.torsoLength
        targetA.set(armReach, shoulderY, -def.shoulderWidth * 0.45f)
        targetP.set(armReach, shoulderY, def.shoulderWidth * 0.45f)
        poleA.set(0f, -1f, 0f)
        poleP.set(0f, -1f, 0f)

        bakeIkLimb(shoulderA!!.worldPosition, targetA, def.upperArmLength, def.forearmLength, poleA, chest!!.worldRotation, def.armIKConstraint, 0f, elbowA!!, handA!!, armABuffer)
        bakeIkLimb(shoulderP!!.worldPosition, targetP, def.upperArmLength, def.forearmLength, poleP, chest!!.worldRotation, def.armIKConstraint, 0f, elbowP!!, handP!!, armPBuffer)

        // Palms facing inward: fingertip stub points toward the body centerline.
        handA!!.localRotation.set(axisZ, 0f)
        handP!!.localRotation.set(axisZ, 0f)
        palmA!!.localPosition.set(0f, 0f, 6f); knucklesA!!.localPosition.set(0f, 0f, 6f); fingertipsA!!.localPosition.set(0f, 0f, 10f)
        palmP!!.localPosition.set(0f, 0f, -6f); knucklesP!!.localPosition.set(0f, 0f, -6f); fingertipsP!!.localPosition.set(0f, 0f, -10f)

        return finalizeStaticPose()
    }

    private fun finalizeStaticPose(): SkeletonPose {
        SkeletonPose.fromHierarchy(roots!!, jointsBuffer)
        jointsBuffer.getJoint(Joint.WRIST_A).set(jointsBuffer.getJoint(Joint.HAND_A))
        jointsBuffer.getJoint(Joint.WRIST_P).set(jointsBuffer.getJoint(Joint.HAND_P))
        jointsBuffer.maxIkClampAmount = maxOf(
            legFBuffer.clampAmount, legBBuffer.clampAmount,
            armABuffer.clampAmount, armPBuffer.clampAmount
        )
        return jointsBuffer
    }
}
