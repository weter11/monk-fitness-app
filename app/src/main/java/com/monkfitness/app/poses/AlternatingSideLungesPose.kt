package com.monkfitness.app.poses

import com.monkfitness.app.animation.*

/**
 * Alternating Lateral (Side) Lunge.
 *
 * Biomechanics (driver = support leg):
 *  - One foot is the fixed support anchor; the other steps OUT to the side.
 *  - The swing foot lifts and travels laterally along its own arc; the knee tracks
 *    over that foot (no valgus). The pelvis drops and shifts sideways over the
 *    working leg (COM transfer), the torso stays upright (minimal forward lean).
 *  - Hands hold a light, symmetric counterbalance (no robotic locking).
 *  - Left and right alternate every half-cycle.
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

    override fun build(context: PoseContext): SkeletonPose {
        val def = context.definition
        ensureHierarchy(def)

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

        // Support foot fixed (no forward/back travel); swing foot travels laterally.
        targetF.set(0f, footRestY, plantZ)
        targetB.set(0f, footRestY + liftHeight * 4f * s * (1f - s), swingZ)

        val plantAnkle = if (plantUsesFrontHip) targetF else targetB
        val swingAnkle = if (plantUsesFrontHip) targetB else targetF

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
