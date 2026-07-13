package com.monkfitness.app.animation

import kotlin.math.*

/**
 * Immutable driver class representing alternating body and limb motion.
 * Typically used for exercises with a clear left-right alternation sequence
 * (e.g., alternating lunges, mountain climbers, running, high knees, marching, skaters).
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

    // --- PHASE-BASED PURE MATHEMATICAL primitves (allocation-free, stateless, deterministic) ---

    @JvmStatic
    fun PositiveHalfSine(progress: Float): Float = max(0f, sin(progress * TWO_PI_F))

    @JvmStatic
    fun NegativeHalfSine(progress: Float): Float = max(0f, -sin(progress * TWO_PI_F))

    @JvmStatic
    fun FullSine(progress: Float): Float = sin(progress * TWO_PI_F)

    @JvmStatic
    fun Cosine(progress: Float): Float = cos(progress * TWO_PI_F)

    @JvmStatic
    fun WeightShift(progress: Float): Float = 0.5f + 0.5f * sin(progress * TWO_PI_F)

    @JvmStatic
    fun ForwardBack(progress: Float): Float = cos(progress * TWO_PI_F)

    @JvmStatic
    fun VerticalLift(progress: Float): Float = 0.5f - 0.5f * cos(progress * TWO_PI_F)

    @JvmStatic
    fun PushPhase(progress: Float): Float = 0.5f - 0.5f * cos(progress * TWO_PI_F)

    @JvmStatic
    fun PullPhase(progress: Float): Float = 0.5f + 0.5f * cos(progress * TWO_PI_F)

    @JvmStatic
    fun AlternatingLeftRight(progress: Float): Float = PositiveHalfSine(progress)

    @JvmStatic
    fun AlternatingRightLeft(progress: Float): Float = NegativeHalfSine(progress)

    @JvmStatic
    fun LeftPhase(progress: Float): Float = PositiveHalfSine(progress)

    @JvmStatic
    fun RightPhase(progress: Float): Float = NegativeHalfSine(progress)

    @JvmStatic
    fun Pulse(progress: Float): Float = 0.5f - 0.5f * cos(progress * TWO_PI_F)

    @JvmStatic
    fun DoublePulse(progress: Float): Float = 0.5f - 0.5f * cos(progress * 2f * TWO_PI_F)

    @JvmStatic
    fun ParabolicLift(t: Float): Float = 4f * t * (1f - t)

    @JvmStatic
    fun EaseInOut(t: Float): Float {
        val tc = t.coerceIn(0f, 1f)
        return tc * tc * (3f - 2f * tc)
    }

    // Lowercase aliases for developer ergonomics
    @JvmStatic fun positiveHalfSine(progress: Float) = PositiveHalfSine(progress)
    @JvmStatic fun negativeHalfSine(progress: Float) = NegativeHalfSine(progress)
    @JvmStatic fun fullSine(progress: Float) = FullSine(progress)
    @JvmStatic fun cosine(progress: Float) = Cosine(progress)
    @JvmStatic fun weightShift(progress: Float) = WeightShift(progress)
    @JvmStatic fun forwardBack(progress: Float) = ForwardBack(progress)
    @JvmStatic fun verticalLift(progress: Float) = VerticalLift(progress)
    @JvmStatic fun pushPhase(progress: Float) = PushPhase(progress)
    @JvmStatic fun pullPhase(progress: Float) = PullPhase(progress)
    @JvmStatic fun alternatingLeftRight(progress: Float) = AlternatingLeftRight(progress)
    @JvmStatic fun alternatingRightLeft(progress: Float) = AlternatingRightLeft(progress)
    @JvmStatic fun leftPhase(progress: Float) = LeftPhase(progress)
    @JvmStatic fun rightPhase(progress: Float) = RightPhase(progress)
    @JvmStatic fun pulse(progress: Float) = Pulse(progress)
    @JvmStatic fun doublePulse(progress: Float) = DoublePulse(progress)
    @JvmStatic fun parabolicLift(t: Float) = ParabolicLift(t)
    @JvmStatic fun easeInOut(t: Float) = EaseInOut(t)


    // --- Existing composite state models preserved for backwards compatibility ---

    /**
     * Generates normalized drivers for alternating left-right movements.
     */
    fun alternating(progress: Float): AlternatingMotion {
        val p = progress.coerceIn(0f, 1f)

        val tLeft = (p * 2f).coerceIn(0f, 1f)
        val tRight = ((p - 0.5f) * 2f).coerceIn(0f, 1f)

        val left = 0.5f * (1f - cos(TWO_PI_F * tLeft))
        val right = 0.5f * (1f - cos(TWO_PI_F * tRight))

        val weightShift = 0.5f + 0.5f * sin(TWO_PI_F * p)
        val transition = 0.5f + 0.5f * cos(TWO_PI_F * p)

        val lift = 0.5f - 0.5f * cos(TWO_PI_F * p * 2f)
        val pelvisDrop = 0.5f - 0.5f * cos(TWO_PI_F * p * 2f)

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
     */
    fun bilateral(progress: Float): BilateralMotion {
        val p = progress.coerceIn(0f, 1f)

        val phase = 0.5f - 0.5f * cos(TWO_PI_F * p)

        val left = phase
        val right = phase

        val transition = 0.5f - 0.5f * cos(PI_F * p)

        val lift = 0.5f + 0.5f * cos(TWO_PI_F * p)

        val weightShift = 0.5f

        val armSwing = phase

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
     */
    fun jump(progress: Float): JumpMotion {
        val p = progress.coerceIn(0f, 1f)

        val tFlight = ((p - 0.25f) * 2f).coerceIn(0f, 1f)
        val lift = 0.5f * (1f - cos(TWO_PI_F * tFlight))

        val left = lift
        val right = lift

        val transition = p

        val weightShift = 0.5f

        val tPrep = (p * 4f).coerceIn(0f, 1f)
        val prepCrouch = 0.5f * (1f - cos(TWO_PI_F * tPrep))

        val tLanding = ((p - 0.75f) * 4f).coerceIn(0f, 1f)
        val landingCrouch = 0.5f * (1f - cos(TWO_PI_F * tLanding))

        val pelvisDrop = prepCrouch + landingCrouch

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
     */
    fun continuous(progress: Float): ContinuousMotion {
        val p = progress.coerceIn(0f, 1f)

        val phase = p

        val left = 0.5f + 0.5f * sin(TWO_PI_F * p)
        val right = 0.5f - 0.5f * sin(TWO_PI_F * p)

        val transition = 0.5f + 0.5f * cos(TWO_PI_F * p)

        val lift = 0.5f + 0.5f * sin(TWO_PI_F * p * 2f)

        val weightShift = 0.5f + 0.5f * sin(TWO_PI_F * p)

        val armSwing = 0.5f + 0.5f * cos(TWO_PI_F * p)

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
