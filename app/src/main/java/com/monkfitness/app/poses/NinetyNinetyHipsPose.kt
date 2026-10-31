package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import kotlin.math.*

/**
 * 90/90 Hips (`ninety_ninety_hips`) — the seated 90/90 hip switch. No BPS exists: the exercise's own
 * copy is the only specification. Each bent limb is built from its thigh and shin directions, which are
 * perpendicular by construction, so the knee is exactly `90°` at every phase.
 *
 * The FEET are not declared: the transition picks them up and sets them down on the other side, and the
 * contact channel is per-pose, not per-frame. The seat (`HIPS`) is.
 */
class NinetyNinetyHipsPose : BasePose() {

    private var roots: List<SkeletonNode>? = null
    private var pelvis: SkeletonNode? = null; private var chest: SkeletonNode? = null; private var neck: SkeletonNode? = null; private var head: SkeletonNode? = null
    private var shoulderA: SkeletonNode? = null; private var elbowA: SkeletonNode? = null; private var handA: SkeletonNode? = null
    private var shoulderP: SkeletonNode? = null; private var elbowP: SkeletonNode? = null; private var handP: SkeletonNode? = null
    private var hipF: SkeletonNode? = null; private var kneeF: SkeletonNode? = null; private var ankleF: SkeletonNode? = null
    private var hipB: SkeletonNode? = null; private var kneeB: SkeletonNode? = null; private var ankleB: SkeletonNode? = null

    private val legFBuffer = SkeletonMath.IKResult()
    private val legBBuffer = SkeletonMath.IKResult()
    private val armABuffer = SkeletonMath.IKResult()
    private val armPBuffer = SkeletonMath.IKResult()

    override val metadata = PoseMetadata(
        camera = CameraDefinition(defaultYaw = 1.19f, defaultPitch = 0.22f, defaultZoom = 1.3f),
        durationSeconds = 4.0f,
        loopMode = LoopMode.LOOP,
        motionCurve = MotionCurve.EASE_IN_OUT,
        environment = EnvironmentDefinition(ground = GroundDefinition(visible = true, level = 0f)),
        // The sit bones are the drill's ground contact, and so are the hands that carry the seat's
        // weight behind the hips; the feet are picked up and set down again, so they are not declared.
        support = SupportDefinition(
            pivot = PivotType.PELVIS,
            contacts = setOf(
                SupportContact(SupportPoint.HIPS),
                SupportContact(SupportPoint.LEFT_HAND),
                SupportContact(SupportPoint.RIGHT_HAND)
            )
        ),
        exerciseFamily = "hip_mobility",
        motionType = "Seated Rotation",
        bodyOrientation = "seated"
    )

    private fun ensureHierarchy() {
        if (roots != null) return
        val nodes = SkeletonFactory.createStandardSkeleton()
        roots = nodes.roots
        pelvis = nodes.pelvis; chest = nodes.chest; neck = nodes.neck; head = nodes.head
        shoulderA = nodes.shoulderA; elbowA = nodes.elbowA; handA = nodes.handA
        shoulderP = nodes.shoulderP; elbowP = nodes.elbowP; handP = nodes.handP
        hipF = nodes.hipF; kneeF = nodes.kneeF; ankleF = nodes.ankleF
        hipB = nodes.hipB; kneeB = nodes.kneeB; ankleB = nodes.ankleB
    }

    override fun onBuild(context: PoseContext): SkeletonPose {
        val def = context.definition
        ensureHierarchy()
        // Shape-driven root (the seat is authored arithmetic), so the solver leaves it untouched.
        declarePosture(jointsBuffer, PostureIntent.Kind.CUSTOM)

        pelvis!!.localPosition.set(SEAT_X, SEAT_Y, 0f)
        declarePelvisTilt(pelvis!!, jointsBuffer, axisZ, -SEATED_LEAN)
        chest!!.localPosition.set(0f, def.torsoLength, 0f)
        // The trunk carries on up its own line; the slight lean is the seat's, not the head's.
        buildGaze(neck!!, head!!, def.neckLength, Vector3(sin(SEATED_LEAN), cos(SEATED_LEAN), 0f))
        buildPelvis(pelvis!!, hipF!!, hipB!!, def.hipWidth)
        buildShoulders(shoulderA!!, shoulderP!!, def.shoulderWidth)

        for (root in roots!!) root.updateWorldTransforms(zeroVector, identityRotation)

        val azimuthF = thighAzimuthF(context.progress)
        val azimuthB = thighAzimuthB(context.progress)
        val elevation = thighElevation(context.progress)
        val shinSwing = shinSwingRad(context.progress)

        bakeBentLeg(def, hipF!!, kneeF!!, ankleF!!, azimuthF, elevation, shinSwing, legFBuffer)
        bakeBentLeg(def, hipB!!, kneeB!!, ankleB!!, azimuthB, elevation, shinSwing, legBBuffer)

        val handY = FLOOR_HAND_Y
        bakeIkLimb(shoulderA!!.worldPosition, Vector3(SEAT_X - HAND_BEHIND, handY, -(def.hipWidth + HAND_OUTBOARD)), def.upperArmLength, def.forearmLength, armPoleA, def.armIKConstraint, chest!!.worldRotation, elbowA!!, handA!!, armABuffer)
        bakeIkLimb(shoulderP!!.worldPosition, Vector3(SEAT_X - HAND_BEHIND, handY, def.hipWidth + HAND_OUTBOARD), def.upperArmLength, def.forearmLength, armPoleP, def.armIKConstraint, chest!!.worldRotation, elbowP!!, handP!!, armPBuffer)

        SkeletonPose.fromHierarchy(roots!!, jointsBuffer)
        jointsBuffer.getJoint(Joint.WRIST_A).set(jointsBuffer.getJoint(Joint.HAND_A))
        jointsBuffer.getJoint(Joint.WRIST_P).set(jointsBuffer.getJoint(Joint.HAND_P))
        return jointsBuffer
    }

    /**
     * Declares one bent leg through the registered authoring bake: the pose owns the chain root, the end
     * target and the pole, and both are derived from the limb's own authored directions — so the
     * published knee is the authored knee.
     */
    private fun bakeBentLeg(
        def: SkeletonDefinition,
        hip: SkeletonNode,
        knee: SkeletonNode,
        ankle: SkeletonNode,
        azimuthRad: Float,
        elevationRad: Float,
        shinSwingRad: Float,
        buffer: SkeletonMath.IKResult
    ) {
        val geometry = legGeometry(azimuthRad, elevationRad, shinSwingRad)
        val hipWorld = hip.worldPosition
        val target = Vector3(
            hipWorld.x + geometry.thigh.x * def.thighLength + geometry.shin.x * def.shinLength,
            hipWorld.y + geometry.thigh.y * def.thighLength + geometry.shin.y * def.shinLength,
            hipWorld.z + geometry.thigh.z * def.thighLength + geometry.shin.z * def.shinLength
        )
        // The pole states where this chain bends: the authored knee's own offset off the hip→ankle
        // chord (`a` is the chord foot of a 2-bone chain at the authored 90-degree interior angle).
        val ux = target.x - hipWorld.x; val uy = target.y - hipWorld.y; val uz = target.z - hipWorld.z
        val d = sqrt(ux * ux + uy * uy + uz * uz)
        val a = def.thighLength * def.thighLength / d
        val kneeX = hipWorld.x + geometry.thigh.x * def.thighLength
        val kneeY = hipWorld.y + geometry.thigh.y * def.thighLength
        val kneeZ = hipWorld.z + geometry.thigh.z * def.thighLength
        val pole = Vector3(
            kneeX - (hipWorld.x + ux / d * a),
            kneeY - (hipWorld.y + uy / d * a),
            kneeZ - (hipWorld.z + uz / d * a)
        )
        bakeIkLimb(hipWorld, target, def.thighLength, def.shinLength, pole, def.legIKConstraint, pelvis!!.worldRotation, knee, ankle, buffer)
    }

    companion object {
        private const val TWO_PI = 2f * PI.toFloat()

        /** The sit-bone layer: the pelvis publishes at the corpus's seated/supine height. */
        const val SEAT_Y = 14f
        const val SEAT_X = 0f

        /** `§2`'s "lean slightly over the front shin" — a constant seat lean (`~6.9°`). */
        const val SEATED_LEAN = 0.12f

        /**
         * `§1`'s 90/90: the F leg's thigh points forward (slightly outboard), the B leg's thigh points
         * to the side — exactly `90°` apart in azimuth, i.e. one hip internally rotated and the other
         * externally, which is what the exercise's name counts.
         */
        const val THIGH_AZIMUTH_F0_DEG = -20f
        const val THIGH_AZIMUTH_B0_DEG = 70f

        /** `§3`'s switch: both thighs rotate by this much together, keeping their `90°` separation. */
        const val SWITCH_SWEEP_DEG = -70f

        /**
         * The knees lift this far off the floor at mid-transition (the drill presses up and sweeps the
         * knees across); the two ends of the cycle are the named 90/90 configurations, where the lift
         * is exactly zero.
         */
        const val KNEE_LIFT_DEG = 12f

        /** The lower leg swings this far about its own thigh axis — the windshield-wiper transition. */
        const val SHIN_SWING_DEG = 180f

        /** The authored 90/90 knee angle — fixed by construction, asserted on the published frame. */
        const val KNEE_ANGLE_DEG = 90f

        /** The hands rest on the floor beside/behind the seat (the copy says nothing about the arms). */
        private const val HAND_BEHIND = 42f
        private const val HAND_OUTBOARD = 16f
        private const val FLOOR_HAND_Y = 10f

        private val armPoleA = Vector3(0f, -1f, -0.4f)
        private val armPoleP = Vector3(0f, -1f, 0.4f)

        /** The F leg's thigh azimuth at [progress]: forward, sweeping to the side over one switch. */
        fun thighAzimuthF(progress: Float): Float =
            Math.toRadians(THIGH_AZIMUTH_F0_DEG.toDouble()).toFloat() +
                Math.toRadians(SWITCH_SWEEP_DEG.toDouble()).toFloat() * progress

        /** The B leg's thigh azimuth at [progress]: to the side, sweeping to the front. */
        fun thighAzimuthB(progress: Float): Float =
            Math.toRadians(THIGH_AZIMUTH_B0_DEG.toDouble()).toFloat() +
                Math.toRadians(SWITCH_SWEEP_DEG.toDouble()).toFloat() * progress

        /** The thigh's elevation: zero at both named configurations, lifted mid-switch. */
        fun thighElevation(progress: Float): Float =
            Math.toRadians(KNEE_LIFT_DEG.toDouble()).toFloat() * sin(PI.toFloat() * progress)

        /** The lower leg's swing about its own thigh axis: `0 → 180°` across the switch. */
        fun shinSwingRad(progress: Float): Float = PI.toFloat() * progress

        /** One leg's authored directions (both unit; perpendicular by construction ⇒ a 90° knee). */
        class LegGeometry(val thigh: Vector3, val shin: Vector3)

        /**
         * The authored geometry of one 90/90 leg, from its thigh azimuth, its elevation and the shin's
         * swing about that thigh's axis. The shin is built in a basis PERPENDICULAR to the thigh, so
         * `|uS| = 1` and `uS ⟂ uT` hold exactly and the knee is `90°` for every input. `shinSwing = 0`
         * lies the shin in the floor plane crossing medially; `π/2` lifts it vertical; `π` lies it
         * across laterally — the transition the drill performs.
         */
        fun legGeometry(azimuthRad: Float, elevationRad: Float, shinSwingRad: Float): LegGeometry {
            val ca = cos(azimuthRad); val sa = sin(azimuthRad)
            val ce = cos(elevationRad); val se = sin(elevationRad)
            val thigh = Vector3(ca * ce, se, sa * ce)
            // the floor-plane perpendicular to the thigh's horizontal projection (azimuth + 90°)
            val b1 = Vector3(-sa, 0f, ca)
            // the up-facing perpendicular (b1 x uT has a positive Y for every elevation in [0, pi/2))
            val b2 = b1.cross(thigh).normalize()
            val shin = Vector3(
                b1.x * cos(shinSwingRad) + b2.x * sin(shinSwingRad),
                b1.y * cos(shinSwingRad) + b2.y * sin(shinSwingRad),
                b1.z * cos(shinSwingRad) + b2.z * sin(shinSwingRad)
            )
            return LegGeometry(thigh, shin)
        }
    }
}
