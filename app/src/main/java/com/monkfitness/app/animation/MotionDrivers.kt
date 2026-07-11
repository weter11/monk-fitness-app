package com.monkfitness.app.animation

import kotlin.math.*

/**
 * Immutable driver class representing alternating body and limb motion.
 * Typically used for exercises with a clear left-right alternation sequence
 * (e.g., alternating lunges, mountain climbers, running, high knees, marching, skaters).
 *
 * @property phase Represents the continuous, overall progression of the movement cycle, normalized to [0, 1].
 * @property left Represents the active movement/intensity value of the left side, normalized to [0, 1].
 *                 Reaches a peak during the first half of the progress cycle and remains smoothly zero in the second half.
 * @property right Represents the active movement/intensity value of the right side, normalized to [0, 1].
 *                  Reaches a peak during the second half of the progress cycle and remains smoothly zero in the first half.
 * @property transition Represents the smooth weight transition from left dominance (1.0) to right dominance (0.0) and back.
 *                      Normalized to [0, 1].
 * @property lift Represents the vertical foot/knee lift or active body bounce, pulsing twice per full cycle.
 *                 Normalized to [0, 1].
 * @property weightShift Represents the lateral weight displacement from the left side (0.0) to the right side (1.0).
 *                        Normalized to [0, 1], with 0.5 representing a centered posture.
 * @property armSwing Represents a smooth alternating upper-body arm swing sequence. Normalized to [0, 1].
 * @property pelvisDrop Represents a double-frequency pelvis drop or hip depression during the transition/load of each side.
 *                      Normalized to [0, 1].
 */
data class AlternatingMotion(
    val phase: Float,
    val left: Float,
    val right: Float,
    val transition: Float,
    val lift: Float,
    val weightShift: Float,
    val armSwing: Float,
    val pelvisDrop: Float
)

/**
 * Immutable driver class representing symmetrical, bilateral body and limb motion.
 * Typically used for exercises where left and right sides move in unison
 * (e.g., standard air squats, push-ups, symmetrical jumps, symmetrical extensions).
 *
 * @property phase Represents the primary symmetrical movement phase, normalized to [0, 1].
 *                 Smoothly rises from 0.0 to 1.0 (at midpoint) and returns to 0.0.
 * @property left Represents the movement progression/activation of the left side. Identical to right in symmetrical motion.
 *                 Normalized to [0, 1].
 * @property right Represents the movement progression/activation of the right side. Identical to left in symmetrical motion.
 *                  Normalized to [0, 1].
 * @property transition Represents a smooth transition progression from takeoff/downward phase to landing/upward phase.
 *                      Normalized to [0, 1].
 * @property lift Represents symmetrical vertical elevation or limb extension. Normalized to [0, 1].
 * @property weightShift Represents lateral balance weight shift. Symmetrical movements remain centered, normalized to 0.5.
 * @property armSwing Represents a symmetrical arm swing or reach movement, peaking at the midpoint of the exercise.
 *                     Normalized to [0, 1].
 * @property pelvisDrop Represents the vertical pelvis displacement or crouch depth. Normalized to [0, 1].
 */
data class BilateralMotion(
    val phase: Float,
    val left: Float,
    val right: Float,
    val transition: Float,
    val lift: Float,
    val weightShift: Float,
    val armSwing: Float,
    val pelvisDrop: Float
)

/**
 * Immutable driver class representing explosive jump patterns.
 * Models preparation/crouch, flight (airborne), landing absorption, and recovery phases.
 *
 * @property phase Represents the primary progression of the jump cycle, normalized to [0, 1].
 * @property left Represents leg tuck or extension state of the left side. Normalized to [0, 1].
 * @property right Represents leg tuck or extension state of the right side. Normalized to [0, 1].
 * @property transition Represents the transition progression of the jump (crouch, launch, landing, recovery).
 *                      Normalized to [0, 1].
 * @property lift Represents the airborne flight height or vertical displacement. Rises smoothly from 0.0,
 *                 peaks at the apex of the jump (0.5 progress), and returns to 0.0. Remaining at 0.0 during crouch and landing.
 *                 Normalized to [0, 1].
 * @property weightShift Represents lateral balance. Symmetrical jumps remain centered, normalized to 0.5.
 * @property armSwing Represents the rapid downward arm swing preparation, upward launch assist, and recovery sequence.
 *                     Normalized to [0, 1].
 * @property pelvisDrop Represents the combined crouch/preparation drop and landing impact absorption drop.
 *                      Normalized to [0, 1].
 */
data class JumpMotion(
    val phase: Float,
    val left: Float,
    val right: Float,
    val transition: Float,
    val lift: Float,
    val weightShift: Float,
    val armSwing: Float,
    val pelvisDrop: Float
)

/**
 * Immutable driver class representing continuous, non-alternating cyclic motion.
 * Typically used for exercises with continuous, phase-shifted loops (e.g., bicycle crunches, skaters, thoracic rotations).
 *
 * @property phase Represents the continuous overall progress of the cyclic movement, normalized to [0, 1].
 * @property left Represents the continuous activation/flexion of the left side. Phase-shifted with right.
 *                 Normalized to [0, 1].
 * @property right Represents the continuous activation/flexion of the right side. Phase-shifted with left.
 *                  Normalized to [0, 1].
 * @property transition Represents a continuous transitional phase wave. Normalized to [0, 1].
 * @property lift Represents the smooth cyclic vertical lift or elevation. Normalized to [0, 1].
 * @property weightShift Represents the smooth continuous weight transfer or sway. Normalized to [0, 1].
 * @property armSwing Represents a smooth continuous cyclic arm reach or swing. Normalized to [0, 1].
 * @property pelvisDrop Represents a smooth continuous pelvis vertical movement. Normalized to [0, 1].
 */
data class ContinuousMotion(
    val phase: Float,
    val left: Float,
    val right: Float,
    val transition: Float,
    val lift: Float,
    val weightShift: Float,
    val armSwing: Float,
    val pelvisDrop: Float
)

/**
 * Stateless authoring utility producing normalized, smooth, branchless biomechanical drivers for exercise poses.
 *
 * This utility completely eliminates redundant trigonometric and algebraic phase calculations in pose builders,
 * ensuring zero velocity spikes and mathematically continuous transitions in the animation pipeline.
 */
object MotionDrivers {

    private const val PI_F = Math.PI.toFloat()
    private const val TWO_PI_F = PI_F * 2f

    /**
     * Generates normalized drivers for alternating left-right movements.
     *
     * Example Usage in a PoseBuilder:
     * ```
     * override fun build(context: PoseContext, skeleton: SkeletonPose) {
     *     val drivers = MotionDrivers.alternating(context.progress)
     *
     *     // Map left leg knee drive and right leg support
     *     val leftKneeTargetY = baseHeight + drivers.left * maxKneeLift
     *     val rightKneeTargetY = baseHeight
     *
     *     // Map arm swing
     *     val leftArmReach = drivers.armSwing * maxReach
     *     val rightArmReach = (1f - drivers.armSwing) * maxReach
     * }
     * ```
     *
     * @param progress Normalized exercise progress, expected to be in range [0, 1].
     * @return An immutable [AlternatingMotion] instance.
     */
    fun alternating(progress: Float): AlternatingMotion {
        val p = progress.coerceIn(0f, 1f)

        // Split progress into left/right halves using clamp to avoid branch-based discontinuities
        val tLeft = (p * 2f).coerceIn(0f, 1f)
        val tRight = ((p - 0.5f) * 2f).coerceIn(0f, 1f)

        // Raised cosine waves for smooth left and right activations with 0 derivative at transition points
        val left = 0.5f * (1f - cos(TWO_PI_F * tLeft))
        val right = 0.5f * (1f - cos(TWO_PI_F * tRight))

        // Weight shift transitions smoothly from 0.0 (left side) to 1.0 (right side)
        val weightShift = 0.5f + 0.5f * sin(TWO_PI_F * p)

        // Symmetrical transition indicator
        val transition = 0.5f + 0.5f * cos(TWO_PI_F * p)

        // Foot lifts and pelvis drops pulse twice per cycle (once per side)
        val lift = 0.5f - 0.5f * cos(TWO_PI_F * p * 2f)
        val pelvisDrop = 0.5f - 0.5f * cos(TWO_PI_F * p * 2f)

        // Arm swing is anti-phase to weight shift or transition
        val armSwing = 0.5f + 0.5f * cos(TWO_PI_F * p)

        return AlternatingMotion(
            phase = p,
            left = left,
            right = right,
            transition = transition,
            lift = lift,
            weightShift = weightShift,
            armSwing = armSwing,
            pelvisDrop = pelvisDrop
        )
    }

    /**
     * Generates normalized drivers for bilateral (symmetrical) movements.
     *
     * Example Usage in a PoseBuilder:
     * ```
     * override fun build(context: PoseContext, skeleton: SkeletonPose) {
     *     val drivers = MotionDrivers.bilateral(context.progress)
     *
     *     // Symmetrical pelvis drop for a squat
     *     val pelvisY = baseHeight - drivers.pelvisDrop * maxSquatDepth
     *
     *     // Arms reach out as the pelvis drops
     *     val armReach = drivers.armSwing * maxReach
     * }
     * ```
     *
     * @param progress Normalized exercise progress, expected to be in range [0, 1].
     * @return An immutable [BilateralMotion] instance.
     */
    fun bilateral(progress: Float): BilateralMotion {
        val p = progress.coerceIn(0f, 1f)

        // Primary phase rises from 0 to 1 at the midpoint (0.5 progress) and returns to 0
        val phase = 0.5f - 0.5f * cos(TWO_PI_F * p)

        // Symmetrical movement means left and right activations follow the main phase
        val left = phase
        val right = phase

        // Symmetrical transition progression looping smoothly at boundaries
        val transition = 0.5f - 0.5f * cos(PI_F * p)

        // Lift reaches maximum at full extension (progress 0.0 and 1.0) and minimum at the bottom
        val lift = 0.5f + 0.5f * cos(TWO_PI_F * p)

        // Weight shift is perfectly centered
        val weightShift = 0.5f

        // Arm swing peaks with the primary phase (arms forward at the bottom of the movement)
        val armSwing = phase

        // Pelvis drop corresponds exactly with the active phase
        val pelvisDrop = phase

        return BilateralMotion(
            phase = p,
            left = left,
            right = right,
            transition = transition,
            lift = lift,
            weightShift = weightShift,
            armSwing = armSwing,
            pelvisDrop = pelvisDrop
        )
    }

    /**
     * Generates normalized drivers for explosive jump exercises.
     *
     * Example Usage in a PoseBuilder:
     * ```
     * override fun build(context: PoseContext, skeleton: SkeletonPose) {
     *     val drivers = MotionDrivers.jump(context.progress)
     *
     *     // Lift represents airborne vertical displacement
     *     val yOffset = drivers.lift * maxJumpHeight
     *
     *     // Pelvis drop represents crouch prep and landing absorption
     *     val pelvisY = baseHeight + yOffset - drivers.pelvisDrop * crouchDepth
     * }
     * ```
     *
     * @param progress Normalized exercise progress, expected to be in range [0, 1].
     * @return An immutable [JumpMotion] instance.
     */
    fun jump(progress: Float): JumpMotion {
        val p = progress.coerceIn(0f, 1f)

        // Flight phase/lift: 0 during crouch (0.0 to 0.25), a smooth arc during flight (0.25 to 0.75), 0 during landing (0.75 to 1.0)
        val tFlight = ((p - 0.25f) * 2f).coerceIn(0f, 1f)
        val lift = 0.5f * (1f - cos(TWO_PI_F * tFlight))

        // Symmetrical leg tuck or extension mirrors the airborne phase
        val left = lift
        val right = lift

        // Transition tracks progress linearly across the cycle
        val transition = p

        // Weight shift is centered in a symmetrical jump
        val weightShift = 0.5f

        // Pelvis drop is split into a preparation crouch (0.0..0.25) and a landing absorption (0.75..1.0)
        val tPrep = (p * 4f).coerceIn(0f, 1f)
        val prepCrouch = 0.5f * (1f - cos(TWO_PI_F * tPrep))

        val tLanding = ((p - 0.75f) * 4f).coerceIn(0f, 1f)
        val landingCrouch = 0.5f * (1f - cos(TWO_PI_F * tLanding))

        // Non-overlapping addition keeps the overall pelvis drop continuous with continuous derivative
        val pelvisDrop = prepCrouch + landingCrouch

        // Arm swing swings back during prep, reaches high during launch, and recovers on landing
        val armSwing = 0.5f - 0.5f * cos(TWO_PI_F * p - PI_F * 0.25f)

        return JumpMotion(
            phase = p,
            left = left,
            right = right,
            transition = transition,
            lift = lift,
            weightShift = weightShift,
            armSwing = armSwing,
            pelvisDrop = pelvisDrop
        )
    }

    /**
     * Generates normalized drivers for continuous, non-alternating cyclic exercises.
     *
     * Example Usage in a PoseBuilder:
     * ```
     * override fun build(context: PoseContext, skeleton: SkeletonPose) {
     *     val drivers = MotionDrivers.continuous(context.progress)
     *
     *     // Smoothly alternate bicycle crunch rotation
     *     val torsoRotation = (drivers.left - drivers.right) * maxRotationAngle
     * }
     * ```
     *
     * @param progress Normalized exercise progress, expected to be in range [0, 1].
     * @return An immutable [ContinuousMotion] instance.
     */
    fun continuous(progress: Float): ContinuousMotion {
        val p = progress.coerceIn(0f, 1f)

        // Continuous phase progresses linearly
        val phase = p

        // Left and right are continuous anti-phase sine/cosine oscillations
        val left = 0.5f + 0.5f * sin(TWO_PI_F * p)
        val right = 0.5f - 0.5f * sin(TWO_PI_F * p)

        // Smooth transition wave
        val transition = 0.5f + 0.5f * cos(TWO_PI_F * p)

        // Continuous lift oscillations (double-frequency)
        val lift = 0.5f + 0.5f * sin(TWO_PI_F * p * 2f)

        // Continuous weight shift tracks the active side
        val weightShift = 0.5f + 0.5f * sin(TWO_PI_F * p)

        // Arm swing tracks continuous alternation
        val armSwing = 0.5f + 0.5f * cos(TWO_PI_F * p)

        // Smooth pelvis vertical movement
        val pelvisDrop = 0.5f - 0.5f * cos(TWO_PI_F * p)

        return ContinuousMotion(
            phase = phase,
            left = left,
            right = right,
            transition = transition,
            lift = lift,
            weightShift = weightShift,
            armSwing = armSwing,
            pelvisDrop = pelvisDrop
        )
    }
}
