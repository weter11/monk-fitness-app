package com.monkfitness.app.poses_for_tests

import com.monkfitness.app.animation.*
import kotlin.math.*

/**
 * DeadHangPose — Biomechanics validation snapshot.
 *
 * Both hands grip a fixed pull-up bar; the body hangs relaxed with straight
 * arms, shoulders elevated naturally, spine elongated, pelvis neutral,
 * legs together, knees straight, feet relaxed, head neutral.
 *
 * The hand targets are authored constants on the bar, so the hands stay
 * perfectly fixed to the bar while the body is derived from the IK solve.
 *
 * STATIC reference pose — ignores [PoseContext.progress]. No breathing,
 * no sway, no interpolation, no animation driver.
 *
 * Engine systems validated:
 *  - SkeletonFactory + BasePose helpers
 *  - SkeletonPoseFinalizer
 *  - bakeIkLimb with frame-relative pole vectors (fixed-bar arms + hanging legs)
 *  - EnvironmentDefinition / EnvironmentAnchor (the bar)
 *  - SupportDefinition (HANDS pivot attached to the bar anchor)
 *  - corrected IK + corrected arm proportions under a fixed contact
 *
 * Joints deliberately stressed: shoulder girdle (geometry + elevation),
 * arm proportions (straight-arm reach to the bar), IK correctness
 * (hands must not detach), wrist orientation, grip orientation,
 * scapular/clavicle behaviour, ankle/foot relaxation.
 */
class DeadHangPose : BasePose() {

    private val barY = 500f

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

    private val barContacts = setOf(
        SupportContact(point = SupportPoint.LEFT_HAND, anchorId = "for_tests_pullup_bar"),
        SupportContact(point = SupportPoint.RIGHT_HAND, anchorId = "for_tests_pullup_bar")
    )

    override val metadata = PoseMetadata(
        camera = CameraDefinition(defaultYaw = 1.19f, defaultPitch = 0.22f, defaultZoom = 1.6f),
        durationSeconds = 0f,
        loopMode = LoopMode.HOLD,
        motionCurve = MotionCurve.LINEAR,
        environment = EnvironmentDefinition(
            ground = GroundDefinition(visible = true, level = 0f),
            props = listOf(
                BoxProp(center = Vector3(0f, barY - 5f, 0f), width = 8f, height = 10f, depth = 240f)
            ),
            anchors = listOf(
                EnvironmentAnchor(
                    id = "for_tests_pullup_bar",
                    type = EnvironmentAnchorType.BAR,
                    worldPosition = Vector3(0f, barY, 0f)
                )
            )
        ),
        pivotType = PivotType.HANDS,
        support = SupportDefinition(pivot = PivotType.HANDS, contacts = barContacts),
        supportContacts = barContacts
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

        // Straight arms to the fixed bar determine the whole body height.
        val gripZ = 1.5f * def.shoulderWidth
        val reach = (def.upperArmLength + def.forearmLength) * def.armIKConstraint.maximumExtensionRatio * 0.999f
        val shoulderY = barY - reach
        val pelvisY = shoulderY - def.torsoLength

        // Neutral, elongated spine — no lean.
        pelvis!!.localPosition.set(0f, pelvisY, 0f)
        pelvis!!.localRotation.set(axisZ, 0f)

        chest!!.localPosition.set(0f, def.torsoLength, 0f)
        chest!!.localRotation.set(axisZ, 0f)
        buildHead(neck!!, head!!, def.neckLength, Vector3(0f, 1f, 0f))
        buildPelvis(pelvis!!, hipF!!, hipB!!, def.hipWidth)
        shoulderA!!.localPosition.set(0f, 0f, -def.shoulderWidth)
        shoulderP!!.localPosition.set(0f, 0f, def.shoulderWidth)

        roots!!.forEach { it.updateWorldTransforms(zeroVector, identityRotation) }

        // FIXED hands on the bar (constant targets — the body hangs from them).
        targetA.set(0f, barY, -gripZ)
        targetP.set(0f, barY, gripZ)
        poleA.set(0f, -1f, 0f)
        poleP.set(0f, -1f, 0f)

        bakeIkLimb(shoulderA!!.worldPosition, targetA, def.upperArmLength, def.forearmLength, poleA, chest!!.worldRotation, def.armIKConstraint, 0f, elbowA!!, handA!!, armABuffer)
        bakeIkLimb(shoulderP!!.worldPosition, targetP, def.upperArmLength, def.forearmLength, poleP, chest!!.worldRotation, def.armIKConstraint, 0f, elbowP!!, handP!!, armPBuffer)

        handA!!.localRotation.set(axisZ, 0f); handP!!.localRotation.set(axisZ, 0f)
        palmA!!.localPosition.set(6f, 0f, 0f); knucklesA!!.localPosition.set(6f, 0f, 0f); fingertipsA!!.localPosition.set(10f, 0f, 0f)
        palmP!!.localPosition.set(6f, 0f, 0f); knucklesP!!.localPosition.set(6f, 0f, 0f); fingertipsP!!.localPosition.set(10f, 0f, 0f)

        // Legs: relaxed hanging pendulum, together, straight, feet relaxed.
        val ankleY = pelvisY - 200f
        targetF.set(0f, ankleY, -def.hipWidth * 0.9f)
        targetB.set(0f, ankleY, def.hipWidth * 0.9f)
        poleF.set(0f, 1f, 0f)
        poleB.set(0f, 1f, 0f)

        bakeIkLimb(hipF!!.worldPosition, targetF, def.thighLength, def.shinLength, poleF, pelvis!!.worldRotation, def.legIKConstraint, 0f, kneeF!!, ankleF!!, legFBuffer)
        bakeIkLimb(hipB!!.worldPosition, targetB, def.thighLength, def.shinLength, poleB, pelvis!!.worldRotation, def.legIKConstraint, 0f, kneeB!!, ankleB!!, legBBuffer)

        ankleF!!.localRotation.set(axisZ, 0f); ankleB!!.localRotation.set(axisZ, 0f)
        heelF!!.localPosition.set(-def.foot.footLength * def.foot.heelRatio, 0f, 0f); toeF!!.localPosition.set(def.foot.footLength * def.foot.toeRatio, 0f, 0f)
        heelB!!.localPosition.set(-def.foot.footLength * def.foot.heelRatio, 0f, 0f); toeB!!.localPosition.set(def.foot.footLength * def.foot.toeRatio, 0f, 0f)

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
