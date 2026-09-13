package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import com.monkfitness.app.animation.SkeletonMath.lerp
import kotlin.math.*

class KettlebellSwingPose : PoseBuilder {
    override val metadata = PoseMetadata(
        camera = CameraDefinition(defaultYaw = 1.19f, defaultPitch = 0.22f, defaultZoom = 1.3f),
        durationSeconds = 2.0f,
        loopMode = LoopMode.LOOP,
        motionCurve = MotionCurve.LINEAR,
        environment = EnvironmentDefinition(ground = GroundDefinition(visible = true, level = 0f)),
        // M8 — the planted feet (standing hinge: the feet never leave the ground), on the ONE
        // canonical support channel (`metadata.support`).
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
        // Canonical hierarchy (`SkeletonFactory.createStandardSkeleton()` — the M11 shape the migrated
        // families publish). The factory's added nodes are pass-throughs between the links this pose
        // already authored: LUMBAR is coincident with the PELVIS (identity rotation) and
        // CLAVICLE_*/SCAPULA_* are coincident with the CHEST, so every transform authored below
        // resolves exactly as before. What changes is that the five canonical joints carry their
        // authored transforms instead of publishing at the world origin.
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
        // Per-frame hygiene: clear last build's §1.1 carriers from this reused singleton buffer.
        SkeletonPose.IntentBuilder(jointsBuffer).reset()
        val def = context.definition
        ensureHierarchy(def)
        // B3 — every production pose declares its posture intent. This pose authors a
        // shape-driven root, so it opts into CUSTOM (the solver leaves the authored root untouched).
        SkeletonPose.IntentBuilder(jointsBuffer).posture(PostureIntent.Kind.CUSTOM)

        // 1. CORE HIP HINGE — the pelvis's path IS the hinge (BPS §5/§7/§9, M6).
        // The swing is a HIP HINGE, not a squat: at the bottom the hips travel BACK over
        // barely-bent knees, and the pelvis's height is whatever the leg's authored span allows.
        // Both spans are authored inside the solver's own reachable length (its
        // `effectiveExtensionRatio` cap), so the leg is realized where it is declared.
        //   top    : hips extended — 0.99 of the reachable leg length above the ankle plant
        //   hinge  : 0.98 of it, pushed HINGE_HIP_BACK units back; the kneecap therefore stays
        //            over the mid-foot (measured knee x: 24.30 at the top -> -1.05 at the hinge,
        //            shin 14.4° -> 0.6° from vertical) and the knee bends only 4.41° more than at
        //            the top ("knee flexion: only slight … this distinguishes the swing from a
        //            squat", §9; §12 lists squatting instead of hinging as mistake #1).
        // The previous authoring lerped `pelvisY` 175 -> 210 while moving the pelvis back only
        // 20 units: the hip->ankle span collapsed to 166.57 of the 210-unit leg (75° of knee
        // flexion) and the hips sank instead of pushing back.
        val u = (1f - cos(context.progress * 2f * PI.toFloat())) * 0.5f

        val legReach = reach(def.thighLength, def.shinLength, def.legIKConstraint)
        val topY = ANKLE_LEVEL + TOP_LEG_SPAN * legReach
        val hingeY = ANKLE_LEVEL + sqrt((HINGE_LEG_SPAN * legReach).pow(2) - HINGE_HIP_BACK.pow(2))
        val pelvisY = lerp(hingeY, topY, u)
        val pelvisX = lerp(-HINGE_HIP_BACK, 0f, u)
        val leanAngle = lerp(HINGE_TRUNK_TILT, 0f, u)

        pelvis!!.localPosition = Vector3(pelvisX, pelvisY, 0f)
        declarePelvisTilt(pelvis!!, jointsBuffer, Vector3(0f, 0f, 1f), -leanAngle)

        chest!!.localPosition = Vector3(0f, def.torsoLength, 0f)
        neck!!.localPosition = Vector3(0f, def.neckLength, 0f); head!!.localPosition = Vector3(0f, 18f, 0f)
        hipF!!.localPosition = Vector3(0f, 0f, -def.hipWidth)
        hipB!!.localPosition = Vector3(0f, 0f, def.hipWidth)
        shoulderA!!.localPosition = Vector3(0f, 0f, -def.shoulderWidth)
        shoulderP!!.localPosition = Vector3(0f, 0f, def.shoulderWidth)

        // Flush Spine FK to get precise Hip and Shoulder origins
        roots!!.forEach { it.updateWorldTransforms(Vector3(0f, 0f, 0f), JointRotation()) }

        // 2. LEG TARGETS (Completely planted on the floor, feet never leave their plant)
        val targetAnkleF = Vector3(0f, ANKLE_LEVEL, -def.hipWidth * 1.5f)
        val targetAnkleB = Vector3(0f, ANKLE_LEVEL, def.hipWidth * 1.5f)

        // Solve Leg IK (knees bend only slightly during hinge)
        bakeIkLimb(hipF!!.worldPosition, targetAnkleF, def.thighLength, def.shinLength, Vector3(1f, 0f, -0.2f), def.legIKConstraint, JointRotation(), kneeF!!, ankleF!!, legFBuffer, jointsBuffer)
        bakeIkLimb(hipB!!.worldPosition, targetAnkleB, def.thighLength, def.shinLength, Vector3(1f, 0f, 0.2f), def.legIKConstraint, JointRotation(), kneeB!!, ankleB!!, legBBuffer, jointsBuffer)

        // The engine derives heel/toe from the shank + the neutral ankle articulation. The flat
        // foot on the forward-leaning shank is intentionally NOT hand-authored here; if the engine
        // derivation lands the foot imperfectly that is an engine limitation left exposed.

        // 3. ARM TARGETS — the load is a STRAIGHT pendulum hung from the shoulder (BPS §6/§9/§11:
        // "the arms are straight — they do not curl or press the load"; the load travels "from low
        // (between legs) to ~horizontal/chest height (top) — driven by the hips, not shoulder
        // flexion"). The hand is therefore placed on the arm's own reachable circle around the
        // shoulder and swung through the sagittal plane, instead of being lerped between two
        // world points: the previous authoring left only 41.0 units of shoulder->hand distance at
        // the top (the arm is 146), so the elbow folded onto the solver's minimum-flexion stop
        // (published interior angle 30.0° = 150° of flexion) with the elbow flared 80.6 units
        // sideways — the "curl the load up with the arms" mistake §12 names.
        val armReach = reach(def.upperArmLength, def.forearmLength, def.armIKConstraint)
        val gripLateral = def.shoulderWidth - def.shoulderWidth * GRIP_WIDTH_RATIO
        // In-plane component of the arm so the 3D grip offset (hands inside the shoulders) leaves
        // the shoulder->hand distance exactly on the authored span.
        val armPlane = sqrt((ARM_SPAN * armReach).pow(2) - gripLateral.pow(2))
        val armAngle = lerp(ARM_ANGLE_BOTTOM, ARM_ANGLE_TOP, u)
        val targetHandX = shoulderA!!.worldPosition.x + armPlane * sin(armAngle)
        val targetHandY = shoulderA!!.worldPosition.y - armPlane * cos(armAngle)

        bakeIkLimb(shoulderA!!.worldPosition, Vector3(targetHandX, targetHandY, -def.shoulderWidth * GRIP_WIDTH_RATIO), def.upperArmLength, def.forearmLength, Vector3(0f, -1f, -1f), def.armIKConstraint, JointRotation(), elbowA!!, handA!!, armABuffer, jointsBuffer)
        bakeIkLimb(shoulderP!!.worldPosition, Vector3(targetHandX, targetHandY, def.shoulderWidth * GRIP_WIDTH_RATIO), def.upperArmLength, def.forearmLength, Vector3(0f, -1f, 1f), def.armIKConstraint, JointRotation(), elbowP!!, handP!!, armPBuffer, jointsBuffer)

        // W1: engine now derives foot/hand orientation (removed manual endpoints + tilt counter-rotation).

        SkeletonPose.fromHierarchy(roots!!, jointsBuffer)
        jointsBuffer.getJoint(Joint.WRIST_A).set(jointsBuffer.getJoint(Joint.HAND_A))
        jointsBuffer.getJoint(Joint.WRIST_P).set(jointsBuffer.getJoint(Joint.HAND_P))
        return jointsBuffer
    }

    private fun reach(l1: Float, l2: Float, constraint: IKConstraint): Float =
        (l1 + l2) * constraint.effectiveExtensionRatio

    private companion object {
        /** The level the pose plants its ankles on (the swing's feet never leave it). */
        const val ANKLE_LEVEL = 10f

        /** Backward travel of the pelvis at the hinge bottom (BPS §7 "hips push back"). */
        const val HINGE_HIP_BACK = 60f

        /** Authored leg spans as a fraction of the solver's reachable leg length. */
        const val TOP_LEG_SPAN = 0.99f
        const val HINGE_LEG_SPAN = 0.98f

        /** Trunk inclination at the hinge bottom, radians (§5 "roughly 45° or more"). */
        const val HINGE_TRUNK_TILT = 1.1f

        /** The arm's own span, as a fraction of the solver's reachable arm length. */
        const val ARM_SPAN = 0.99f

        /** Grip width as a fraction of the shoulder width (unchanged from the original authoring). */
        const val GRIP_WIDTH_RATIO = 0.8f

        /** The pendulum's swing limits, radians from the downward vertical (+ = forward). */
        const val ARM_ANGLE_BOTTOM = -0.52f  // ~30° behind: the load swings behind the knees
        const val ARM_ANGLE_TOP = 1.45f      // ~83°: the load rides at chest height, arms horizontal
    }
}
