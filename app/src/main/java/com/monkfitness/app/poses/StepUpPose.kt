package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import kotlin.math.*

/**
 * Step-Up (driven by support-leg extension, a different model from the lunges).
 *
 * Biomechanics:
 *  - The LEAD foot is the support: it lifts onto the step and then stays fixed there.
 *  - The body rises because the lead (support) leg EXTENDS; the pelvis height is
 *    derived from the lowest supporting foot (pelvisY = min(leadY, trailY) + legSpan),
 *    so it can never over-extend either leg and never floats or teleports.
 *  - The TRAILING (rear) foot naturally leaves the floor only AFTER the lead foot
 *    is on the step and the pelvis has begun to rise, then it is placed on the step.
 *  - Torso stays upright (minimal lean); arms hold a light symmetric counterbalance.
 */
class StepUpPose : BaseLungePose() {

    private val stepTop = 36f
    private val legSpan = 203f // thigh + shin - soft bend, keeps every leg < 210

    override val metadata = PoseMetadata(
        camera = lungeCamera,
        durationSeconds = 3.0f,
        loopMode = LoopMode.PING_PONG,
        motionCurve = MotionCurve.EASE_IN_OUT,
        environment = EnvironmentDefinition(
            ground = GroundDefinition(visible = true, level = 0f),
            props = listOf(
                StepProp(
                    center = Vector3(12f, stepTop * 0.5f, 0f),
                    width = 44f,
                    height = stepTop,
                    // M1: feet land at Z = ±(hipWidth * 1.15) = ±25.3; a depth of 44 only
                    // spanned Z ∈ [−22, +22], so both feet overhung the step. Widen to 60
                    // (±30) so the planted feet sit fully on the step surface.
                    depth = 60f
                )
            )
        ),
        support = SupportDefinition(
            pivot = PivotType.FEET,
            contacts = setOf(SupportContact.LEFT_FOOT, SupportContact.RIGHT_FOOT)
        ),
        exerciseFamily = "step_ups",
        motionType = "step_up"
    )

    override fun build(context: PoseContext): SkeletonPose {
        val def = context.definition
        ensureHierarchy(def)
        // B3 — every production pose declares its posture intent. This pose authors a
        // shape-driven root, so it opts into CUSTOM (the solver leaves the authored root untouched).
        declarePosture(jointsBuffer, PostureIntent.Kind.CUSTOM)

        val footSepZ = def.hipWidth * 1.15f
        val leadX = 12f
        val trailX = -12f
        val leadZ = -footSepZ
        val trailZ = footSepZ
        val liftHeight = 4f
        val armAmp = 18f

        // Single up/down rep: 0 at the seam (both on ground), 1 at mid (both on step).
        val u = (1f - cos(context.progress * 2f * PI.toFloat())) * 0.5f

        // Lead foot reaches the step first; the trailing foot follows after the push begins.
        val leadUp = smoothstep(0.0f, 0.45f, u)
        val trailUp = smoothstep(0.30f, 0.85f, u)

        val liftBump = sin(leadUp * PI.toFloat()) * liftHeight
        val leadY = SkeletonMath.lerp(footRestY, stepTop, leadUp) + liftBump
        val trailY = SkeletonMath.lerp(footRestY, stepTop, trailUp)

        // Pelvis rises with the support leg; capped by the lowest supporting foot.
        val breath = breathWave(context.progress) * 2f
        val pelvisY = minOf(leadY, trailY) + legSpan + breath
        val pelvisX = (leadX + trailX) * 0.5f
        val pelvisZ = 0f
        val lean = 0.10f * sin(u * PI.toFloat())
        val pelvisAngle = -lean

        targetF.set(leadX, leadY, leadZ)
        targetB.set(trailX, trailY, trailZ)

        // Symmetric counterbalance swing (no robotic locking, zero arm asymmetry).
        val armSwing = armAmp * sin(u * PI.toFloat())

        return assemble(
            def = def,
            plantAnkle = targetF,
            swingAnkle = targetB,
            plantUsesFrontHip = true,
            pelvisX = pelvisX,
            pelvisY = pelvisY,
            pelvisZ = pelvisZ,
            pelvisAngle = pelvisAngle,
            chestPitch = 0f,
            armAmt = armSwing,
            armPmt = armSwing,
            poleFrontLocal = POLE_LEG_FRONT,
            poleBackLocal = POLE_LEG_BACK,
            poleArmALocal = POLE_ARM_A,
            poleArmPLocal = POLE_ARM_P
        )
    }
}
