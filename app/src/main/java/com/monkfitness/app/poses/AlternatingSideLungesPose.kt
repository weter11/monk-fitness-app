package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import kotlin.math.*

/**
 * Alternating Lateral (Side) Lunge. One foot is the fixed anchor; the other steps OUT to the side,
 * knee tracking over that foot (no valgus), while the pelvis drops and shifts sideways and the torso
 * stays upright. Hands hold a light symmetric counterbalance.
 */
class AlternatingSideLungesPose : BaseLungePose() {

    override val metadata = PoseMetadata(
        camera = lungeCamera,
        durationSeconds = 4.0f,
        loopMode = LoopMode.LOOP,
        motionCurve = MotionCurve.EASE_IN_OUT,
        environment = lungeEnvironment,
        support = SupportDefinition(
            pivot = PivotType.FEET,
            contacts = setOf(SupportContact.LEFT_FOOT, SupportContact.RIGHT_FOOT)
        ),
        exerciseFamily = "lunges",
        motionType = "alternating_lateral_lunge"
    )

    override fun onBuild(context: PoseContext): SkeletonPose {
        val def = context.definition
        ensureHierarchy(def)
        // Every production pose declares its posture intent. This pose authors a
        // shape-driven root, so it opts into CUSTOM (the solver leaves the authored root untouched).
        declarePosture(jointsBuffer, PostureIntent.Kind.CUSTOM)

        val standH = standingPelvisY(def)
        val bottomH = footRestY + def.shinLength * 0.92f
        val footSepZ = def.hipWidth * 1.15f
        val stride = 72f
        val leanMax = 0.14f
        val liftHeight = 8f
        val armAmp = 26f

        val s = (1f - cos(context.progress * 4f * PI.toFloat())) * 0.5f
        val swingIsFront = context.progress >= 0.5f
        val plantUsesFrontHip = !swingIsFront

        val plantZ = if (plantUsesFrontHip) -footSepZ else footSepZ
        val swingZ = if (swingIsFront) -footSepZ - stride * s else footSepZ + stride * s

        // R3 (lunge support anchoring): author plant/swing targets directly so the fixed anchor
        // keeps its own side of the track when the planted foot swaps at the half cycle. The swing
        // foot travels laterally (in Z). See AlternatingForwardLungesPose for the full rationale.
        val plantAnkle = targetF.set(0f, footRestY, plantZ)
        val swingAnkle = targetB.set(0f, footRestY + liftHeight * 4f * s * (1f - s), swingZ)

        // COM shifts sideways toward the working leg.
        val pelvisX = 0f
        val pelvisZ = (plantZ + swingZ) * 0.5f
        val pelvisY = SkeletonMath.lerp(standH, bottomH, s)
        val lean = SkeletonMath.lerp(0f, leanMax, s)
        val pelvisAngle = -lean

        // Symmetric counterbalance (hands drift slightly forward together).
        val a = armAmp * s
        val armAmt = a
        val armPmt = a

        return assemble(
            def = def,
            plantAnkle = plantAnkle,
            swingAnkle = swingAnkle,
            plantUsesFrontHip = plantUsesFrontHip,
            pelvisX = pelvisX,
            pelvisY = pelvisY,
            pelvisZ = pelvisZ,
            pelvisAngle = pelvisAngle,
            chestPitch = 0f,
            armAmt = armAmt,
            armPmt = armPmt,
            poleFrontLocal = POLE_LEG_FRONT,
            poleBackLocal = POLE_LEG_BACK,
            poleArmALocal = POLE_ARM_A,
            poleArmPLocal = POLE_ARM_P
        )
    }
}
