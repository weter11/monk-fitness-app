package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import kotlin.math.*

/**
 * Leg Swings (`leg_swings_hold`) — the standing single-leg pendulum swing. There is no BPS and no
 * exercise copy either (`WorkoutGenerator` passes the title as every field), so the identity comes
 * from the localized names ("dynamic leg swings") and from the sibling drills this is NOT
 * (`hip_circles` owns the pelvic circle).
 *
 * The stance foot is declared and pinned for the cycle; the SWINGING foot is deliberately not — it is
 * airborne the whole time and the contact channel is per-pose.
 */
class LegSwingsPose : BaseSquatPose() {

    override val squatH = PELVIS_Y
    override val pelvisXEnd = PELVIS_X
    override val leanAngleEnd = TRUNK_LEAN
    override val armLeanEnd = 0f

    /** The swing plane is sagittal; the knees track straight over their own feet. */
    override val legPoleF = Vector3(1f, 0f, -0.15f)
    override val legPoleB = Vector3(1f, 0f, 0.15f)
    override val armPoleA = Vector3(0f, -1f, -1f)
    override val armPoleP = Vector3(0f, -1f, 1f)

    override val metadata = PoseMetadata(
        camera = CameraDefinition(defaultYaw = 1.19f, defaultPitch = 0.22f, defaultZoom = 1.3f),
        durationSeconds = 2.0f,
        loopMode = LoopMode.LOOP,
        motionCurve = MotionCurve.LINEAR,
        environment = EnvironmentDefinition(ground = GroundDefinition(visible = true, level = 0f)),
        // The STANCE foot is the drill's ground contact and is declared; the SWINGING foot leaves the
        // floor by design, and the declaration channel is per-pose (a foot that is airborne for the
        // whole cycle must not be driven onto a surface it has left).
        support = SupportDefinition(
            pivot = PivotType.FEET,
            contacts = setOf(SupportContact(SupportPoint.RIGHT_FOOT))
        ),
        exerciseFamily = "hip_mobility",
        motionType = "Dynamic Swing",
        bodyOrientation = "upright"
    )

    /** The body does not travel: the swing happens under a still pelvis. */
    override fun computePelvis(progress: Float, def: SkeletonDefinition): Triple<Float, Float, Float> =
        Triple(PELVIS_Y, PELVIS_X, TRUNK_LEAN)

    override fun fillLegTargets(
        def: SkeletonDefinition, pelvisY: Float, pelvisX: Float, leanAngle: Float, progress: Float,
        outF: Vector3, outB: Vector3
    ) {
        // The supporting leg: pinned under its own hip for the whole cycle.
        outB.set(pelvisX + STANCE_X, FLOOR_ANKLE_Y, stanceZ(def))

        // The swinging leg: a pendulum of constant radius about its own hip, in the sagittal plane.
        val hip = hipF!!.worldPosition
        val theta = swingAngle(progress)
        outF.set(
            hip.x + SWING_RADIUS * sin(theta),
            hip.y - SWING_RADIUS * cos(theta),
            hip.z
        )
    }

    override fun fillArmTargets(
        def: SkeletonDefinition, pelvisY: Float, pelvisX: Float, leanAngle: Float, progress: Float,
        outA: Vector3, outP: Vector3
    ) {
        // Hands on the hips, travelling with the (still) pelvis.
        val handX = pelvisX + HAND_FORWARD
        val handY = pelvisY + HAND_ABOVE_HIP
        outA.set(handX, handY, -(def.hipWidth + HAND_OUTBOARD))
        outP.set(handX, handY, def.hipWidth + HAND_OUTBOARD)
    }

    override fun articulateExtras(def: SkeletonDefinition, progress: Float, leanAngle: Float, footLift: Float) {
        // Both feet keep the athlete's own forward line: the swing is sagittal, not a fan.
        setHeading(Extremity.FOOT_F, Vector3(1f, 0f, 0f))
        setHeading(Extremity.FOOT_B, Vector3(1f, 0f, 0f))
    }

    companion object {
        private const val TWO_PI = 2f * PI.toFloat()

        /** The forward extreme (hip flexion) and the back extreme (hip extension), in degrees. */
        const val SWING_FORWARD_DEG = 40f
        const val SWING_BACK_DEG = 25f

        /** The pendulum's mid-angle and amplitude — a symmetric circle of the same extremes. */
        private val SWING_MID_RAD = Math.toRadians(((SWING_FORWARD_DEG - SWING_BACK_DEG) / 2f).toDouble()).toFloat()
        private val SWING_AMP_RAD = Math.toRadians(((SWING_FORWARD_DEG + SWING_BACK_DEG) / 2f).toDouble()).toFloat()

        /**
         * The pendulum's angle from vertical at [progress]: `+SWING_FORWARD_DEG` (in front of the hip)
         * at the cycle's start, `−SWING_BACK_DEG` (behind it) at its middle. A pure cosinusoid, so the
         * loop's seam is continuous and the extremes are the authored amplitudes.
         */
        fun swingAngle(progress: Float): Float = SWING_MID_RAD + SWING_AMP_RAD * cos(TWO_PI * progress)

        /** The angle [swingAngle] measures, recovered from a published joint pair (the test's reader). */
        fun angleFromVertical(dx: Float, dy: Float): Float = atan2(dx, -dy)

        /**
         * The swing's radius: `0.975 ×` the leg chain's span (`204.75 u` of `210`) — the drill is a
         * leg swing, so the knee keeps the chain's near-full extension over the whole arc, and the
         * radius stays inside the chain's `205.80 u` extension cap (no projection helper, no clamp).
         */
        const val SWING_RADIUS = 204.75f

        /**
         * The standing height: `201 u` of leg drop under the hip (the same light athletic stance the
         * corpus's standing single-leg drill authors), which leaves both legs inside their band for
         * the whole swing.
         */
        const val PELVIS_Y = 226f
        const val PELVIS_X = 0f
        const val TRUNK_LEAN = 0.03f

        /** The planted support foot: the family's floor frame, just outside its own hip. */
        private const val STANCE_X = 0f
        private const val FLOOR_ANKLE_Y = 25f

        /** `1.2 × hipWidth` each side of the mid-line — the corpus's single-leg stance (`HipCarsPose`). */
        fun stanceZ(def: SkeletonDefinition): Float = def.hipWidth * 1.2f

        private const val HAND_FORWARD = 6f
        private const val HAND_ABOVE_HIP = 6f
        private const val HAND_OUTBOARD = 5f
    }
}
