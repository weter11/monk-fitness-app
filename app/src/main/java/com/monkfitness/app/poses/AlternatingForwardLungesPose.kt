package com.monkfitness.app.poses

import com.monkfitness.app.animation.*

/**
 * Alternating Forward Lunge.
 *
 * Biomechanics (driver = support leg, never the pelvis):
 *  - One foot is the FIXED support anchor for the whole rep; it never slides.
 *  - The swing foot lifts, travels forward along its own arc and lands in front.
 *  - The pelvis drops between the two feet as the rep deepens (COM transfer),
 *    the torso hinges forward over the front foot, and the contralateral arm swings.
 *  - Left and right alternate every half-cycle (two reps per loop).
 */
class AlternatingForwardLungesPose : BaseLungePose() {

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
        motionType = "alternating_forward_lunge"
    )

    override fun build(context: PoseContext): SkeletonPose {
        val def = context.definition
        ensureHierarchy(def)

        val standH = standingPelvisY(def)
        val bottomH = footRestY + def.shinLength * 0.98f
        val footSepZ = def.hipWidth * 1.15f
        val stride = 88f
        val leanMax = 0.30f
        val liftHeight = 8f
        val armAmp = 36f

        // Two smooth humps per loop (zero velocity at every standing point).
        val s = (1f - cos(context.progress * 4f * PI.toFloat())) * 0.5f
        val swingIsFront = context.progress >= 0.5f
        val plantUsesFrontHip = !swingIsFront

        val plantZ = if (plantUsesFrontHip) -footSepZ else footSepZ
        val swingZ = if (swingIsFront) -footSepZ else footSepZ

        // Support foot is fixed; swing foot follows its arc.
        targetF.set(0f, footRestY, plantZ)
        targetB.set(stride * s, footRestY + liftHeight * 4f * s * (1f - s), swingZ)

        val plantAnkle = if (plantUsesFrontHip) targetF else targetB
        val swingAnkle = if (plantUsesFrontHip) targetB else targetF

        val pelvisX = comX(0f, stride * s, s)
        val pelvisY = SkeletonMath.lerp(standH, bottomH, s)
        val lean = SkeletonMath.lerp(0f, leanMax, s)
        val pelvisAngle = -lean

        // Contralateral arm swing: the arm opposite the forward leg leads forward.
        val a = armAmp * s
        val (armAmt, armPmt) = if (swingIsFront) Pair(-a, a) else Pair(a, -a)

        return assemble(
            def = def,
            plantAnkle = plantAnkle,
            swingAnkle = swingAnkle,
            plantUsesFrontHip = plantUsesFrontHip,
            pelvisX = pelvisX,
            pelvisY = pelvisY,
            pelvisZ = 0f,
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
