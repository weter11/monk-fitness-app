package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import kotlin.math.*

class JumpSquatPose : BaseSquatPose() {

    override val squatH = 0f
    override val pelvisXEnd = -25f
    override val leanAngleEnd = 0.45f
    override val armLeanEnd = 0f

    override val legPoleF = Vector3(1f, 0f, -0.3f)
    override val legPoleB = Vector3(1f, 0f, 0.3f)
    override val armPoleA = Vector3(0f, -1f, -1f)
    override val armPoleP = Vector3(0f, -1f, 1f)

    override val metadata = PoseMetadata(
        camera = CameraDefinition(defaultYaw = 1.19f, defaultPitch = 0.22f, defaultZoom = 1.2f),
        durationSeconds = 2.5f, loopMode = LoopMode.LOOP,
        // LINEAR preserves the internal ballistic sine wave without double-easing the physics clock.
        motionCurve = MotionCurve.LINEAR,
        environment = EnvironmentDefinition(ground = GroundDefinition(visible = true, level = 0f)),
        support = SupportDefinition(
            pivot = PivotType.FEET,
            contacts = setOf(
                SupportContact(SupportPoint.LEFT_FOOT),
                SupportContact(SupportPoint.RIGHT_FOOT)
            )
        )
    )

    // Continuous branchless ballistic phase map: progress=0 -> deep squat (-PI/2), progress=0.5 -> peak flight.
    override fun computePelvis(progress: Float, def: SkeletonDefinition): Triple<Float, Float, Float> {
        val cycle = (progress * 2f * PI.toFloat()) - (PI.toFloat() / 2f)
        val rawSin = sin(cycle) // -1 (deep squat) .. 1 (peak flight)
        val squatFactor = max(0f, -rawSin)
        val standH = def.shinLength + def.thighLength + 25f
        val pelvisY = standH + (rawSin * 40f)
        val pelvisX = squatFactor * -25f
        val leanAngle = squatFactor * 0.45f
        return Triple(pelvisY, pelvisX, leanAngle)
    }

    /**
     * The ballistic cycle authors the root at `standH + rawSin · 40` while the feet trail it at
     * `25 + max(0, rawSin) · 25`, which demands a `210.288`-u hip→ankle chord at the seam frames and
     * `225.269` u at the apex from a `112 + 98 = 210`-u limb — `107 %` of its length, impossible for any
     * chain geometry (measured relocation `4.488 … 19.469` u). Unintended authoring error, not a deliberate
     * ROM limit: `projectLegTargetsToReach` makes the declaration the reachable point ON the authored ray,
     * and the published ballistics are preserved (the solver's relocation WAS that boundary projection).
     */
    override fun fillLegTargets(
        def: SkeletonDefinition, pelvisY: Float, pelvisX: Float, leanAngle: Float, progress: Float,
        outF: Vector3, outB: Vector3
    ) {
        val cycle = (progress * 2f * PI.toFloat()) - (PI.toFloat() / 2f)
        val rawSin = sin(cycle)
        val flightFactor = max(0f, rawSin)
        val footLift = flightFactor * 25f
        outF.set(0f, 25f + footLift, -def.hipWidth * 1.5f)
        outB.set(0f, 25f + footLift, def.hipWidth * 1.5f)
        projectLegTargetsToReach(def, outF, outB)
    }

    /**
     * The inverse-wave swing keeps the hands at `23.396 … 45.321` u from their own shoulder; the
     * tightest frames ask for a `15.36°` interior elbow against the `30°` stop (measured relocation
     * `0.605 … 16.739` u). `projectArmTargetsToReach` moves only the radius onto the annulus, so the
     * published swing is preserved.
     */
    override fun fillArmTargets(
        def: SkeletonDefinition, pelvisY: Float, pelvisX: Float, leanAngle: Float, progress: Float,
        outA: Vector3, outP: Vector3
    ) {
        val cycle = (progress * 2f * PI.toFloat()) - (PI.toFloat() / 2f)
        val rawSin = sin(cycle)
        val handTargetX = pelvisX + (-rawSin * 35f) + 5f
        val handTargetY = pelvisY + def.torsoLength - 10f + (-rawSin * 15f)
        outA.set(handTargetX, handTargetY, -def.shoulderWidth * 1.5f)
        outP.set(handTargetX, handTargetY, def.shoulderWidth * 1.5f)
        projectArmTargetsToReach(def, outA, outP)
    }

    // Plantar flexion + wrist flick during flight (Branch C intent carriers).
    override fun articulateExtras(def: SkeletonDefinition, progress: Float, leanAngle: Float, footLift: Float) {
        val cycle = (progress * 2f * PI.toFloat()) - (PI.toFloat() / 2f)
        val rawSin = sin(cycle)
        val flightFactor = max(0f, rawSin)
        val footPitch = flightFactor * 0.6f
        buildAnkleArticulation(Extremity.FOOT_F, leanAngle - footPitch, 0f, ankleF!!)
        buildAnkleArticulation(Extremity.FOOT_B, leanAngle - footPitch, 0f, ankleB!!)
        buildWristArticulation(Extremity.HAND_A, leanAngle + (flightFactor * 0.3f), 0f, handA!!)
        buildWristArticulation(Extremity.HAND_P, leanAngle + (flightFactor * 0.3f), 0f, handP!!)
    }
}
