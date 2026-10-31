package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import com.monkfitness.app.animation.SkeletonMath.lerp
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin

/**
 * Cat-Cow on the authored-hierarchy idiom (SkeletonFactory tree + declared pelvis tilt + registered
 * package bake limbs + `fromHierarchy`), converted from the legacy world-position-built representation.
 * The quadruped base is declared as a spine TILT toward the same world direction the legacy chest offset
 * encoded, which restores the exact `torsoLength` bone (the legacy raw offset `(−torsoLength, dy)`
 * silently stretched the trunk by `sqrt(L² + dy²)`).
 *
 * **The spinal wave is carried by the SPINE (BPS §5/§9).** The published motion this class used to
 * author was a rigid trunk/root bob: the two spine articulations published IDENTITY at every sampled
 * phase, and the trunk's chord swept only `2.386°` while the body translated `5` u at the pelvis and
 * `10` u at the chest — which cannot represent Cat-Cow's spinal flexion/extension at all. The rep is now
 * authored as the exercise's own sagittal wave over the canonical two-segment spine
 * (`PELVIS -> LUMBAR -> CHEST`, Issue E):
 *
 *  * `flexion` is the wave in world space — the pelvis→chest chord's flexion, `+` at Cat (the shoulder
 *    end rises `torsoLength·sin(A) = 5.00` u) and `−` at Cow (it sinks `5.00` u);
 *  * the PELVIS carries its own **pelvic tilt**, which REVERSES with the wave (BPS §3/§7/§9: posterior
 *    "tail tuck" in Cat, anterior "tail lift" in Cow);
 *  * the LUMBAR carries the remainder of the chord's motion (`−flexion − pelvicTilt`), so the visible
 *    flexion propagates through the lower spine instead of the root;
 *  * the CHEST carries the thoracic share, and the neck/head chain follows through FK.
 *
 * Deliberately NOT changed: the pelvis's placement/height schedule (the planted leg targets, the floor
 * relationship and the four-point base all resolve against it — the whole-corpus A/B measures the
 * planted leg chain to within `3.052e-05` u), the leg targets and poles, the support base, the gaze
 * sweep, and the cycle timing. The hands stay planted flat under their shoulders (BPS §6/§8/§11): the
 * target's X/Z ride the shoulder's FK position and its height is the pose's mat level — the previous
 * form (`shoulder.y − chestPos`) was only equal to the floor in the rigid-trunk layout it was written
 * for and would have lifted the hands off the mat once the spine articulated. The authored CHEST
 * rotation also means the Finalizer's Issue F chest-frame reconstruction is skipped for this pose.
 */
class CatCowPose : PoseBuilder {
    private var roots: List<SkeletonNode>? = null
    private var pelvis: SkeletonNode? = null; private var lumbar: SkeletonNode? = null; private var chest: SkeletonNode? = null; private var neck: SkeletonNode? = null; private var head: SkeletonNode? = null
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
        // The quadruped's four-point base, declared on the ONE canonical support channel. `Cat-Cow
        // (Reps)` BPS §8 — hands and knees both in contact with the floor — is exactly the
        // `KneePushUpPose` base (hands + knees, pivot KNEES), and the pose declared none of it, so
        // `supportedPoints` published EMPTY and the declaration-driven derivations were inert.
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
        pelvis = nodes.pelvis; lumbar = nodes.lumbar; chest = nodes.chest; neck = nodes.neck; head = nodes.head
        shoulderA = nodes.shoulderA; elbowA = nodes.elbowA; handA = nodes.handA
        shoulderP = nodes.shoulderP; elbowP = nodes.elbowP; handP = nodes.handP
        hipF = nodes.hipF; kneeF = nodes.kneeF; ankleF = nodes.ankleF
        hipB = nodes.hipB; kneeB = nodes.kneeB; ankleB = nodes.ankleB
    }

    override fun build(context: PoseContext): SkeletonPose {
        SkeletonPose.IntentBuilder(jointsBuffer).reset()
        // Every production pose declares its posture intent. Shape-driven root, so CUSTOM.
        SkeletonPose.IntentBuilder(jointsBuffer).posture(PostureIntent.Kind.CUSTOM)
        val progress = context.progress
        val definition = context.definition
        ensureHierarchy(definition)

        // Quadruped placement: progress 0 (Cat) to 1 (Cow). The pelvis's height schedule is the pose's
        // authored PLACEMENT, not its motion: the planted leg targets and the four-point base all resolve
        // against it, so the correction below leaves it (and the whole leg chain) untouched.
        val ankleHeight = definition.foot.ankleHeight
        val pelvisPos = lerp(45f, 40f, progress) + ankleHeight

        // `flexion` is the WAVE in world space: + = Cat (the pelvis->chest chord's shoulder end
        // rises), - = Cow (it sinks). The spine's articulations below carry it.
        val flexion = lerp(CAT_FLEXION, -COW_EXTENSION, progress)
        // The pelvis's OWN pelvic tilt, reversing with the wave (BPS §3/§7/§9). Positive = posterior
        // ("tail tuck" in Cat); in Cow it is the anterior direction ("tail lift").
        val pelvicTilt = lerp(CAT_PELVIC_TILT, -COW_PELVIC_TILT, progress)
        // The thoracic segment's share of the same wave (BPS §5: thoracic flexion/extension is
        // maximal at each end); the neck/head chain follows it through FK.
        val thoracic = THORACIC_SHARE * flexion

        // The three articulations are all about the pose's own lateral axis Z (BPS §5/§11: the spine moves
        // purely in the sagittal plane). The chord's world angle is `pelvisRotation + lumbarRotation`; the
        // CHEST's rotation is the thoracic segment's own and carries the rib cage, neck and head.
        val pelvisRotation = QUADRUPED_PITCH + pelvicTilt
        val lumbarRotation = -flexion - pelvicTilt
        val chestRotation = -thoracic

        pelvis!!.localPosition.set(50f, pelvisPos, 0f)
        declarePelvisTilt(pelvis!!, jointsBuffer, AXIS_Z, pelvisRotation)
        lumbar!!.localPosition.set(0f, 0f, 0f)
        chest!!.localPosition.set(0f, definition.torsoLength, 0f)
        lumbar!!.localRotation.set(AXIS_Z, lumbarRotation)
        chest!!.localRotation.set(AXIS_Z, chestRotation)
        // Carrier-backed spinal articulation: the same declaration the base poses make through
        // `buildSpineCurve`, recorded on the ONE intent channel so the Finalizer re-derives exactly the nodes
        // written here (byte-identical to the bare `localRotation.set`).
        SkeletonPose.IntentBuilder(jointsBuffer).spine(lumbarRotation, chestRotation, AXIS_Z)
        SkeletonPose.IntentBuilder(jointsBuffer).joint(Joint.LUMBAR, JointRotation(AXIS_Z, lumbarRotation))
        SkeletonPose.IntentBuilder(jointsBuffer).joint(Joint.CHEST, JointRotation(AXIS_Z, chestRotation))

        neck!!.localPosition.set(0f, definition.neckLength, 0f)
        head!!.localPosition.set(0f, 18f, 0f)
        hipF!!.localPosition.set(0f, 0f, -definition.hipWidth)
        hipB!!.localPosition.set(0f, 0f, definition.hipWidth)
        shoulderA!!.localPosition.set(0f, 0f, -definition.shoulderWidth)
        shoulderP!!.localPosition.set(0f, 0f, definition.shoulderWidth)

        roots!!.forEach { it.updateWorldTransforms(Vector3(0f, 0f, 0f), JointRotation()) }

        // LEG TARGETS: ankles planted at the knee-base floor points (unchanged targets/poles).
        // The floor-frame literals below
        // are inside the leg chain's own minimum reach at EVERY phase — measured `42.5 … 45.0` against
        // `minReach(112, 98, 30°) = 56.0090` — so the engine relocated the realized foot instead of realizing
        // the declared point (`maxIkClampAmount` up to `16.0090`, realized `ANKLE_F` 13.5090 units off at
        // p = 0.5). Each target is projected onto its chain's band with the engine's own reach-target helper, which is a
        // no-op for a target already inside the band and leaves the reachability signal live.
        val kneeBaseR = Vector3(50f, ankleHeight, -definition.hipWidth)
        SkeletonMath.clampTargetToReach(hipF!!.worldPosition, kneeBaseR, definition.thighLength, definition.shinLength, IKConstraint.LegConstraint, kneeBaseR)
        bakeIkLimb(hipF!!.worldPosition, kneeBaseR, definition.thighLength, definition.shinLength, Vector3(-1f, 0f, -1f), IKConstraint.LegConstraint, pelvis!!.worldRotation, kneeF!!, ankleF!!, legFIK, jointsBuffer)
        val kneeBaseL = Vector3(50f, ankleHeight, definition.hipWidth)
        SkeletonMath.clampTargetToReach(hipB!!.worldPosition, kneeBaseL, definition.thighLength, definition.shinLength, IKConstraint.LegConstraint, kneeBaseL)
        bakeIkLimb(hipB!!.worldPosition, kneeBaseL, definition.thighLength, definition.shinLength, Vector3(-1f, 0f, 1f), IKConstraint.LegConstraint, pelvis!!.worldRotation, kneeB!!, ankleB!!, legBIK, jointsBuffer)

        // ARM TARGETS: the hands stay planted flat on the floor directly under their shoulders (BPS
        // §6/§8/§11). The target's X/Z ride the shoulder's own FK position, so the arms stay vertical as the
        // wave moves the girdle; its height is the pose's own mat level.
        val handBaseR = Vector3(shoulderA!!.worldPosition.x, MAT_LEVEL, shoulderA!!.worldPosition.z)
        val handBaseL = Vector3(shoulderP!!.worldPosition.x, MAT_LEVEL, shoulderP!!.worldPosition.z)
        bakeIkLimb(shoulderA!!.worldPosition, handBaseR, definition.upperArmLength, definition.forearmLength, Vector3(0f, 0f, -1f), IKConstraint.ArmConstraint, chest!!.worldRotation, elbowA!!, handA!!, armAIK, jointsBuffer)
        bakeIkLimb(shoulderP!!.worldPosition, handBaseL, definition.upperArmLength, definition.forearmLength, Vector3(0f, 0f, 1f), IKConstraint.ArmConstraint, chest!!.worldRotation, elbowP!!, handP!!, armPIK, jointsBuffer)

        // Gaze: the legacy headPitch sweep becomes the declared Head Target (Finalizer-owned head),
        // so the head leads each end range as BPS §4/§11 ask (chin tuck in Cat, gaze up in Cow) and follows
        // the spine's wave through the chest frame it is resolved in.
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

    private companion object {
        /** The pose's own lateral axis — the sagittal plane's bend axis (BPS §5/§11). */
        val AXIS_Z = Vector3(0f, 0f, 1f)

        /** The quadruped layout's pelvis/trunk pitch: the trunk chord lies horizontally. */
        val QUADRUPED_PITCH = (PI / 2.0).toFloat()

        /**
         * The rep's chord amplitude at each end range: `asin(CHORD_RISE / TRUNK_LENGTH) = asin(5/120)`
         * ≈ 0.0417 rad.
         *
         * This is the **strict pre-fix motion envelope**, by owner decision: the pre-correction rep's own
         * largest trunk angle was the Cow end's `5` u sag (`2.3867°`), and the Cat end mirrors that same
         * `5` u as a rise instead of exceeding it. The first cut authored `0.12` rad (`14.36` u) and was
         * rejected as ROM growth — the correction re-owns the existing motion rather than growing it.
         * Both ends are the same amplitude, so the chord's total excursion is `10.00` u: exactly the pre-fix
         * rep's own chest excursion, re-expressed as curvature.
         */
        val CAT_FLEXION = asin(CHORD_RISE / TRUNK_LENGTH)

        /** The trunk bone's end-to-end vertical offset at each end range, u (the pre-fix Cow sag). */
        private const val CHORD_RISE = 5f

        /** `SkeletonDefinition.DEFAULT_ADULT.torsoLength`, u — the length the amplitude is derived in. */
        private const val TRUNK_LENGTH = 120f

        /**
         * The Cow end range's world-space chord extension: `5.00` u of drop at the shoulder end — the pose's
         * authored Cow extreme (BPS §5), preserved exactly from the pre-correction authoring. The literal is
         * the pre-fix authored value; it equals `CAT_FLEXION` to `1.3e-5` rad, so both ends are the same `5` u.
         */
        const val COW_EXTENSION = 0.0417f

        /**
         * The PELVIS's own pelvic tilt at each end range: posterior ("tail tuck") at Cat, anterior ("tail
         * lift") at Cow (BPS §3/§7/§9). This is the pelvis's justified share — the wave itself is carried by
         * the spine segments, so the drill stays a spinal flow and not a pelvis-driven movement.
         */
        const val CAT_PELVIC_TILT = 0.045f
        const val COW_PELVIC_TILT = 0.045f

        /**
         * The thoracic segment's share of the wave (the CHEST articulation, which carries the rib cage and
         * through FK the neck and head). A share, not the whole: the remainder is the LUMBAR's, so both spine
         * segments articulate (BPS §5).
         */
        const val THORACIC_SHARE = 0.25f

        /** The pose's mat level: where the hands are planted (the declared ground plane). */
        const val MAT_LEVEL = 0f
    }
}
