package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import kotlin.math.*

/**
 * Step-Up (driven by support-leg extension, a different model from the lunges).
 *
 * Biomechanics:
 *  - The LEAD foot is the support: it is placed on the STEP's tread during the ascent and then
 *    stays fixed there while the lead leg extends and the body rises.
 *  - The step the pose declares (`metadata.environment`) is the ONE source of the support geometry:
 *    the tread the planted foot must fit on, the lateral run the athlete's stance must fit inside,
 *    and the top SURFACE the planted foot rides above. `stepTop` is a surface height, never a joint
 *    height — using it as a joint height is what left a foot "on" the step with its contact plane 25
 *    units below the step's top (and the athlete 25 units short of the climb) while the pose still
 *    claimed the step as its support.
 *  - A foot standing on a surface sits `footRestY` above it: that is this family's own floor-contact
 *    height (`footRestY`), the engine's contact-radius convention (`PushUpPlank.ankleHeight =
 *    BASE_ANKLE_HEIGHT + supportElevation`) and the measured relationship on the one other
 *    foot-on-a-prop production pose, `DeclinePushUpPose`, whose foot contacts ride 25 units above its
 *    40-unit box. So a foot planted on the step's top is at `stepTop + footRestY`, and the ascent buys
 *    exactly the step's height.
 *  - The body rises with the lead (support) leg's extension: the pelvis rides `legSpan` above the
 *    lowest supporting foot (pelvisY = min(leadY, trailY) + legSpan), so it can never over-extend
 *    either leg and never floats or teleports.
 *  - The TRAILING (rear) foot starts on the floor and follows the lead's rise immediately, staying on
 *    the floor's side of the tread (it is lifted clear at the top — BPS §7 "the trailing foot is
 *    lifted or following"; it does not stand on the tread in this variant). The lag is deliberately
 *    small: both legs are ~`legSpan` long, so a large ankle-height divergence is exactly what this
 *    pose's own limb-asymmetry acceptance band (`maxLegAsymmetry < 15`) forbids.
 *  - Torso stays upright (minimal lean); arms hold a light symmetric counterbalance.
 */
class StepUpPose : BaseLungePose() {

    // -----------------------------------------------------------------------------------------------
    // The step — ONE set of numbers, used by BOTH the declared prop and the foot placement.
    //
    // The previous authoring derived the feet from `hipWidth * 1.15` (a body-relative stance) while
    // declaring a prop from independent literals, so neither foot ever landed on the step's footprint
    // (audit M1: `Z = ∓25.3` against a `Z ∈ [−22, +22]` run).
    // -----------------------------------------------------------------------------------------------

    /** The step's top SURFACE height. The ascent buys exactly this much pelvis rise. */
    private val stepTop = 36f

    /** Tread depth (X): deep enough for the whole planted foot, with margin at both ends. */
    private val stepTread = 44f

    /** Lateral run (Z). The athlete's floor stance is wider than this, so the planted foot is placed
     *  INSIDE the run (see [onStepZ]) instead of overhanging it. */
    private val stepRun = 44f

    /** The planted (lead) foot's X. */
    private val leadFootX = 12f

    /** The trailing foot's X: the floor stance behind the step's near edge. */
    private val trailFootX = -12f

    /**
     * The tread's X centre — centred on the PLANTED FOOT, not on the body, so the whole foot (not
     * just its ankle) lands on the tread. The offset is the default 35-unit foot's mid-point
     * (`(toeRatio − heelRatio) · footLength / 2` = `0.42 · 17.5`).
     */
    private val treadCenterX = leadFootX + 7.35f

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
                    center = Vector3(treadCenterX, stepTop * 0.5f, 0f),
                    width = stepTread,
                    height = stepTop,
                    depth = stepRun
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

    override fun onBuild(context: PoseContext): SkeletonPose {
        val def = context.definition
        ensureHierarchy(def)
        // B3 — every production pose declares its posture intent. This pose authors a
        // shape-driven root, so it opts into CUSTOM (the solver leaves the authored root untouched).
        declarePosture(jointsBuffer, PostureIntent.Kind.CUSTOM)

        val floorStanceZ = def.hipWidth * 1.15f
        val liftHeight = 4f
        val armAmp = 18f

        // The ankle height of a foot STANDING ON the step's top: the step's surface plus the foot's
        // own contact radius (the same relationship this pose has to the floor at rest).
        val onStepY = stepTop + footRestY

        // The planted foot's lateral placement on the tread: the run's near edge inset by the
        // placement margin, i.e. the athlete's stance on the step is the run's, not the floor's.
        val onStepZ = -(stepRun * 0.5f - 3f)

        // Single up/down rep: 0 at the seam (both on ground), 1 at mid (lead foot on the step).
        val u = (1f - cos(context.progress * 2f * PI.toFloat())) * 0.5f

        // Lead foot reaches the step first and the trailing foot follows it immediately: the two feet
        // must stay inside this pose's limb-asymmetry band (`maxLegAsymmetry < 15`, asserted by
        // StepUpPoseTest/LungePosesTest), and both legs are ~`legSpan` long, so the trailing ankle has to
        // track the lead's height within that band all rep (an 0.30→0.85 lag opens a 32.4-unit gap once
        // the ascent is the step's full height; 0.05→0.50 holds it at 9.9).
        val leadUp = smoothstep(0.0f, 0.45f, u)
        val trailUp = smoothstep(0.05f, 0.50f, u)

        val liftBump = sin(leadUp * PI.toFloat()) * liftHeight
        val leadY = SkeletonMath.lerp(footRestY, onStepY, leadUp) + liftBump
        val trailY = SkeletonMath.lerp(footRestY, onStepY, trailUp)
        // Placement and height travel together: the foot is on the tread by the time it is at the
        // step's plane, and back at the floor stance at the rep's endpoints.
        val leadZ = SkeletonMath.lerp(-floorStanceZ, onStepZ, leadUp)

        // Pelvis rises with the support leg; capped by the lowest supporting foot.
        val breath = breathWave(context.progress) * 2f
        val pelvisY = minOf(leadY, trailY) + legSpan + breath
        val pelvisX = (leadFootX + trailFootX) * 0.5f
        val pelvisZ = 0f
        val lean = 0.10f * sin(u * PI.toFloat())
        val pelvisAngle = -lean

        targetF.set(leadFootX, leadY, leadZ)
        targetB.set(trailFootX, trailY, floorStanceZ)

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
