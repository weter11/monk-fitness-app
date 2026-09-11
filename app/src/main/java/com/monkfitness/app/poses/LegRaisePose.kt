package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import com.monkfitness.app.animation.SkeletonMath.lerp
import kotlin.math.*

/**
 * P12 (§12.6): converted from the legacy world-position-built representation
 * (`solveIK -> setJoint(result) -> fromJointPositions`) to the authored-hierarchy idiom
 * (SkeletonFactory tree + declared pelvis tilt + registered package bake limbs +
 * `fromHierarchy`). The elevation math is unchanged; only the limb solve route moved from
 * the bypass into the canonical authoring path.
 */
class LegRaisePose : PoseBuilder {
    override val metadata = PoseMetadata(
        camera = CameraDefinition(defaultYaw = 1.19f, defaultPitch = 0.22f, defaultZoom = 1.3f),
        durationSeconds = 3.0f,
        loopMode = LoopMode.LOOP,
        motionCurve = MotionCurve.LINEAR,
        environment = EnvironmentDefinition(ground = GroundDefinition(visible = true, level = 0f))
    )

    private var roots: List<SkeletonNode>? = null
    private var pelvis: SkeletonNode? = null; private var chest: SkeletonNode? = null; private var neck: SkeletonNode? = null; private var head: SkeletonNode? = null
    private var shoulderA: SkeletonNode? = null; private var elbowA: SkeletonNode? = null; private var handA: SkeletonNode? = null
    private var shoulderP: SkeletonNode? = null; private var elbowP: SkeletonNode? = null; private var handP: SkeletonNode? = null
    private var hipF: SkeletonNode? = null; private var kneeF: SkeletonNode? = null; private var ankleF: SkeletonNode? = null
    private var hipB: SkeletonNode? = null; private var kneeB: SkeletonNode? = null; private var ankleB: SkeletonNode? = null

    private val jointsBuffer = SkeletonPose()
    private val legFBuffer = SkeletonMath.IKResult()
    private val legBBuffer = SkeletonMath.IKResult()
    private val armABuffer = SkeletonMath.IKResult()
    private val armPBuffer = SkeletonMath.IKResult()

    private fun ensureHierarchy(def: SkeletonDefinition) {
        if (roots != null) return
        val nodes = SkeletonFactory.createStandardSkeleton()
        roots = nodes.roots
        pelvis = nodes.pelvis; chest = nodes.chest; neck = nodes.neck; head = nodes.head
        shoulderA = nodes.shoulderA; elbowA = nodes.elbowA; handA = nodes.handA
        shoulderP = nodes.shoulderP; elbowP = nodes.elbowP; handP = nodes.handP
        hipF = nodes.hipF; kneeF = nodes.kneeF; ankleF = nodes.ankleF
        hipB = nodes.hipB; kneeB = nodes.kneeB; ankleB = nodes.ankleB
    }

    override fun build(context: PoseContext): SkeletonPose {
        // Per-frame hygiene: clear last build's §1.1 carriers from this reused singleton buffer.
        SkeletonPose.IntentBuilder(jointsBuffer).reset()
        // B3 — every production pose declares its posture intent. Shape-driven root, so CUSTOM.
        SkeletonPose.IntentBuilder(jointsBuffer).posture(PostureIntent.Kind.CUSTOM)
        val def = context.definition
        ensureHierarchy(def)

        // 1. Supine Core Positioning — authored hierarchy (see DeadBugPose for the +90°
        // supine-declaration note; identical layout to the legacy world positions).
        val pelvisX = 15f
        val pelvisY = 12f
        pelvis!!.localPosition.set(pelvisX, pelvisY, 0f)
        declarePelvisTilt(pelvis!!, jointsBuffer, Vector3(0f, 0f, 1f), PI.toFloat() / 2f)
        chest!!.localPosition.set(0f, def.torsoLength, 0f)
        neck!!.localPosition.set(0f, def.neckLength, 0f)
        head!!.localPosition.set(0f, 18f, 0f)
        hipF!!.localPosition.set(0f, 0f, -def.hipWidth)
        hipB!!.localPosition.set(0f, 0f, def.hipWidth)
        shoulderA!!.localPosition.set(0f, 0f, -def.shoulderWidth)
        shoulderP!!.localPosition.set(0f, 0f, def.shoulderWidth)

        roots!!.forEach { it.updateWorldTransforms(Vector3(0f, 0f, 0f), JointRotation()) }

        // 2. Continuous Leg Elevation Angle Calculation
        // Smooth C2 cosine wave mapping progress 0.0 -> 0.5 (up) -> 1.0 (down)
        val u = (1f - cos(context.progress * 2f * PI.toFloat())) * 0.5f
        val maxElevation = 85f * PI.toFloat() / 180f
        val theta = lerp(0f, maxElevation, u)

        // 3. ARM TARGETS (Resting flat beside glutes)
        val targetHandA = Vector3(pelvisX + 15f, 10f, -def.shoulderWidth * 1.2f)
        val targetHandP = Vector3(pelvisX + 15f, 10f, def.shoulderWidth * 1.2f)

        // P12 (§12.6): arms declared through the registered authoring bake (slightly flexed
        // elbow pointing upward/outward via the same poles).
        bakeIkLimb(shoulderA!!.worldPosition, targetHandA, def.upperArmLength, def.forearmLength, Vector3(0f, 1f, -1f), def.armIKConstraint, chest!!.worldRotation, elbowA!!, handA!!, armABuffer, jointsBuffer)
        bakeIkLimb(shoulderP!!.worldPosition, targetHandP, def.upperArmLength, def.forearmLength, Vector3(0f, 1f, 1f), def.armIKConstraint, chest!!.worldRotation, elbowP!!, handP!!, armPBuffer, jointsBuffer)

        // 4. LEG TARGETS (Double legs elevating together in perfect unison)
        val L = (def.thighLength + def.shinLength) * 0.96f
        val targetAnkleF = Vector3(hipF!!.worldPosition.x + L * cos(theta), hipF!!.worldPosition.y + L * sin(theta), hipF!!.worldPosition.z)
        val targetAnkleB = Vector3(hipB!!.worldPosition.x + L * cos(theta), hipB!!.worldPosition.y + L * sin(theta), hipB!!.worldPosition.z)

        // P12 (§12.6): legs declared through the registered authoring bake (knees straight,
        // flexed forward slightly via the same poles).
        bakeIkLimb(hipF!!.worldPosition, targetAnkleF, def.thighLength, def.shinLength, Vector3(-1f, 1f, 0f), def.legIKConstraint, pelvis!!.worldRotation, kneeF!!, ankleF!!, legFBuffer, jointsBuffer)
        bakeIkLimb(hipB!!.worldPosition, targetAnkleB, def.thighLength, def.shinLength, Vector3(-1f, 1f, 0f), def.legIKConstraint, pelvis!!.worldRotation, kneeB!!, ankleB!!, legBBuffer, jointsBuffer)

        // W1: engine derives heel/toe + palm/hand orientation (legacy raw toe offsets along the
        // elevation direction are superseded by the declared-intent derivation — §12.9).

        SkeletonPose.fromHierarchy(roots!!, jointsBuffer)
        jointsBuffer.getJoint(Joint.WRIST_A).set(jointsBuffer.getJoint(Joint.HAND_A))
        jointsBuffer.getJoint(Joint.WRIST_P).set(jointsBuffer.getJoint(Joint.HAND_P))
        return jointsBuffer
    }
}
