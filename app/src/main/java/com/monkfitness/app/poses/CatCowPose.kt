package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import com.monkfitness.app.animation.SkeletonMath.lerp
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sqrt
import kotlin.math.sin

/**
 * P12 (§12.6): converted from the legacy world-position-built representation
 * (`solveIK -> setJoint(result) -> fromJointPositions`) to the authored-hierarchy idiom
 * (SkeletonFactory tree + declared pelvis tilt + registered package bake limbs +
 * `fromHierarchy`). The quadruped base is now declared as a spine TILT toward the same
 * world direction the legacy chest offset encoded, which additionally restores the exact
 * `torsoLength` bone (the legacy raw offset (−torsoLength, dy) silently stretched the
 * trunk by sqrt(L² + dy²) — a representation correction §12.9 quantifies). Knee/ankle
 * targets, poles, and the alternating cat/cow choreography are unchanged; the solved leg
 * targets ride the canonical bake, the solved world positions of the legacy path are
 * reproduced exactly through the same parent-frame inverse (the reconstruction helper's
 * `rotAround(−parentRot)` == the bake's `toLocalDirection`). Legacy raw toe/head world
 * writes are superseded by the W1 engine derivation and the declared Head Target
 * (canonical Phase-7 path).
 */
class CatCowPose : PoseBuilder {
    private var roots: List<SkeletonNode>? = null
    private var pelvis: SkeletonNode? = null; private var chest: SkeletonNode? = null; private var neck: SkeletonNode? = null; private var head: SkeletonNode? = null
    private var shoulderA: SkeletonNode? = null; private var elbowA: SkeletonNode? = null; private var handA: SkeletonNode? = null
    private var shoulderP: SkeletonNode? = null; private var elbowP: SkeletonNode? = null; private var handP: SkeletonNode? = null
    private var hipF: SkeletonNode? = null; private var kneeF: SkeletonNode? = null; private var ankleF: SkeletonNode? = null
    private var hipB: SkeletonNode? = null; private var kneeB: SkeletonNode? = null; private var ankleB: SkeletonNode? = null

    private val jointsBuffer = SkeletonPose()
    private val legFIK = SkeletonMath.IKResult()
    private val legBIK = SkeletonMath.IKResult()
    private val armAIK = SkeletonMath.IKResult()
    private val armPIK = SkeletonMath.IKResult()
    private val tempV1 = Vector3()

    override val metadata = PoseMetadata(
        camera = CameraDefinition(defaultYaw = 1.19f,
        defaultPitch = 0.22f,
        defaultZoom = 1.3f),
        durationSeconds = 4.0f,
        loopMode = LoopMode.LOOP,
        motionCurve = MotionCurve.SINE,
        environment = EnvironmentDefinition(ground = GroundDefinition(visible = true, level = 0f)),
        // M12-b (P11 audit §3 row M12 / §4 "TODO — P1" item 2): the quadruped's four-point base,
        // declared on the ONE canonical support channel (`metadata.support`). `Cat-Cow (Reps)` BPS §8
        // — "both hands (palm/carpal arch) and both knees (patella/shin on a padded surface) remain in
        // contact with the floor" — is exactly the `KneePushUpPose` base (hands + knees, pivot
        // KNEES), and the pose declared none of it, so `SkeletonPose.supportedPoints` published EMPTY
        // and the declaration-driven derivations were inert for a pose that rests on four points.
        support = SupportDefinition(
            pivot = PivotType.KNEES,
            contacts = setOf(
                SupportContact.LEFT_HAND,
                SupportContact.RIGHT_HAND,
                SupportContact.LEFT_KNEE,
                SupportContact.RIGHT_KNEE
            )
        )
    )

    private fun ensureHierarchy(definition: SkeletonDefinition) {
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
        val progress = context.progress
        val definition = context.definition
        ensureHierarchy(definition)

        // Quadruped base: progress 0 (Cat - rounded) to 1 (Cow - arched)
        val ankleHeight = definition.foot.ankleHeight
        val pelvisPos = lerp(45f, 40f, progress) + ankleHeight
        val chestPos = lerp(45f, 35f, progress) + ankleHeight

        // Declared spine: direction legacy encoded as (−torsoLength, chestPos − pelvisPos).
        val dx = -definition.torsoLength
        val dy = chestPos - pelvisPos
        val mag = sqrt(dx * dx + dy * dy)
        // rotZ(θ)·(+Y) = (−sinθ, cosθ) must equal (dx, dy)/mag → θ = atan2(−dx, dy).
        val spineTilt = atan2(-dx, dy)
        pelvis!!.localPosition.set(50f, pelvisPos, 0f)
        declarePelvisTilt(pelvis!!, jointsBuffer, Vector3(0f, 0f, 1f), spineTilt)
        chest!!.localPosition.set(0f, definition.torsoLength, 0f)
        neck!!.localPosition.set(0f, definition.neckLength, 0f)
        head!!.localPosition.set(0f, 18f, 0f)
        hipF!!.localPosition.set(0f, 0f, -definition.hipWidth)
        hipB!!.localPosition.set(0f, 0f, definition.hipWidth)
        shoulderA!!.localPosition.set(0f, 0f, -definition.shoulderWidth)
        shoulderP!!.localPosition.set(0f, 0f, definition.shoulderWidth)

        roots!!.forEach { it.updateWorldTransforms(Vector3(0f, 0f, 0f), JointRotation()) }

        // LEG TARGETS: ankles planted at the knee-base floor points (unchanged targets/poles).
        // M12-a (P11 audit §3 row M12: "raw world positions"): the floor-frame literals below are
        // inside the leg chain's own minimum reach at EVERY phase — measured `42.5 … 45.0` against
        // `SkeletonMath.minReach(112, 98, 30°) = 56.0090` — so the engine relocated the realized foot
        // instead of realizing the declared point (`maxIkClampAmount = 11.0090 … 16.0090`, the
        // realized `ANKLE_F` 13.5090 units off the declaration at p = 0.5), the M8-second-clause /
        // M13 defect class. The authored direction and stance are unchanged; each target is projected
        // onto its chain's reachable band with the engine's own R2 helper
        // (`SkeletonMath.clampTargetToReach` — the same reachable-by-construction fix the M8 pass
        // applied to the five standing poses and M13 to the hamstring reach), which is a no-op for a
        // target already inside the band and leaves the reachability signal live rather than muted.
        val kneeBaseR = Vector3(50f, ankleHeight, -definition.hipWidth)
        SkeletonMath.clampTargetToReach(hipF!!.worldPosition, kneeBaseR, definition.thighLength, definition.shinLength, IKConstraint.LegConstraint, kneeBaseR)
        bakeIkLimb(hipF!!.worldPosition, kneeBaseR, definition.thighLength, definition.shinLength, Vector3(-1f, 0f, -1f), IKConstraint.LegConstraint, pelvis!!.worldRotation, kneeF!!, ankleF!!, legFIK, jointsBuffer)
        val kneeBaseL = Vector3(50f, ankleHeight, definition.hipWidth)
        SkeletonMath.clampTargetToReach(hipB!!.worldPosition, kneeBaseL, definition.thighLength, definition.shinLength, IKConstraint.LegConstraint, kneeBaseL)
        bakeIkLimb(hipB!!.worldPosition, kneeBaseL, definition.thighLength, definition.shinLength, Vector3(-1f, 0f, 1f), IKConstraint.LegConstraint, pelvis!!.worldRotation, kneeB!!, ankleB!!, legBIK, jointsBuffer)

        // ARM TARGETS: hands under the shoulders (same offsets, now read from FK).
        val handBaseR = Vector3(shoulderA!!.worldPosition.x, shoulderA!!.worldPosition.y - chestPos, shoulderA!!.worldPosition.z)
        val handBaseL = Vector3(shoulderP!!.worldPosition.x, shoulderP!!.worldPosition.y - chestPos, shoulderP!!.worldPosition.z)
        bakeIkLimb(shoulderA!!.worldPosition, handBaseR, definition.upperArmLength, definition.forearmLength, Vector3(0f, 0f, -1f), IKConstraint.ArmConstraint, chest!!.worldRotation, elbowA!!, handA!!, armAIK, jointsBuffer)
        bakeIkLimb(shoulderP!!.worldPosition, handBaseL, definition.upperArmLength, definition.forearmLength, Vector3(0f, 0f, 1f), IKConstraint.ArmConstraint, chest!!.worldRotation, elbowP!!, handP!!, armPIK, jointsBuffer)

        // Gaze: the legacy headPitch sweep (−0.5 → +0.5 rad, direction (−cos, sin, 0) from
        // the chest) becomes the declared Head Target (Finalizer-owned head, Phase 7).
        val headPitch = lerp(-0.5f, 0.5f, progress)
        val gazeDir = tempV1.set(-cos(headPitch), sin(headPitch), 0f).normalize()
        val nw = neck!!.worldPosition
        SkeletonPose.IntentBuilder(jointsBuffer).headTarget(
            Vector3(nw.x + gazeDir.x * 100f, nw.y + gazeDir.y * 100f, nw.z + gazeDir.z * 100f)
        )

        SkeletonPose.fromHierarchy(roots!!, jointsBuffer)
        jointsBuffer.getJoint(Joint.WRIST_A).set(jointsBuffer.getJoint(Joint.HAND_A))
        jointsBuffer.getJoint(Joint.WRIST_P).set(jointsBuffer.getJoint(Joint.HAND_P))
        return jointsBuffer
    }
}
