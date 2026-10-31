package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import kotlin.math.*

/**
 * Cossack Squat — a frontal-plane, single-leg-loaded squat.
 *
 * From a wide stance the weight shifts onto one leg, whose hip and knee flex deeply, while the opposite
 * leg stays straight and long. Both feet remain flat; the pelvis drops on the working side via HIP
 * adduction/flexion, NOT lumbar side-bend; the trunk stays upright and square with the arms holding a
 * light forward counterbalance; sides alternate every half-cycle.
 *
 * Declares intent only: engine carriers and helpers (declarePosture, buildSpineCurve, buildGaze,
 * buildPelvis, buildShoulders, world-only bakeIkLimb, frame-relative poles), no manual FK/IK, magic
 * offsets, counter-rotations or hand-authored contacts. [BaseLungePose] supplies `assemble()` — both ankles
 * are grounded here, unlike a lunge's lifted swing foot.
 */
class CossackSquatPose : BaseLungePose() {

    override val metadata = PoseMetadata(
        camera = lungeCamera,
        durationSeconds = 6.0f,
        loopMode = LoopMode.LOOP,
        motionCurve = MotionCurve.EASE_IN_OUT,
        environment = lungeEnvironment,
        support = SupportDefinition(
            pivot = PivotType.FEET,
            contacts = setOf(SupportContact.LEFT_FOOT, SupportContact.RIGHT_FOOT)
        ),
        exerciseFamily = "squats",
        motionType = "cossack_squat",
        bodyOrientation = "upright"
    )

    override fun onBuild(context: PoseContext): SkeletonPose {
        val def = context.definition
        ensureHierarchy(def)
        declarePosture(jointsBuffer, PostureIntent.Kind.CUSTOM)

        val standH = standingPelvisY(def)
        val bottomH = footRestY + def.shinLength * 0.62f   // deep single-leg descent
        val legLen = def.thighLength + def.shinLength
        val wideZ = legLen * 0.96f                            // straight leg must be ~leg-length from its hip to stay extended
        val nearZ = def.hipWidth * 0.35f                     // working foot kept near midline
        val leanMax = 0.10f                                  // slight, hip-driven trunk lean
        val armAmp = 30f                                     // forward counterbalance

        val d = 0.5f - 0.5f * cos(context.progress * 4f * PI.toFloat())
        // Working side flips at the half-cycle: front hip loaded for progress < 0.5.
        val workingIsFront = context.progress < 0.5f

        // The WORKING (bent) leg's foot stays under its own hip (vertical shin, knee tracks over the foot)
        // while the STRAIGHT leg's foot shoots far outward so hip->ankle ~= full leg length -> IK yields a
        // long straight leg. The pelvis stays near centre (slight working-side bias) so the straight foot is
        // genuinely far from its hip; midpointing would half-bend both legs and splay knees. Sign
        // convention: hipF sits at -hipWidth, so the working FOOT must share its own hip's Z side.
        val sideSign = if (workingIsFront) -1f else 1f
        val workingZ = sideSign * def.hipWidth * 1.0f
        val straightZ = -sideSign * SkeletonMath.lerp(def.hipWidth.toFloat(), wideZ, d)
        val pelvisZ = sideSign * SkeletonMath.lerp(0f, def.hipWidth * 0.3f, d)

        val plantAnkle = targetF.set(0f, footRestY, workingZ)
        val straightAnkle = targetB.set(0f, footRestY, straightZ)

        // Both feet sit at X=0 (a frontal straddle); the load/weight shift is in Z, not X. Feeding the
        // Z-anchors into comX() previously produced a large bogus +X pelvis shift that yanked the root off
        // the working foot and inverted the working thigh (knee ended up ABOVE the hip). The side bias is
        // carried purely in Z (pelvisZ above).
        val pelvisX = 0f
        val pelvisY = SkeletonMath.lerp(standH, bottomH, d)
        val lean = SkeletonMath.lerp(0f, leanMax, d)
        val pelvisAngle = -lean

        val a = armAmp * d
        val armAmt = a
        val armPmt = a

        return assemble(
            def = def,
            plantAnkle = plantAnkle,
            swingAnkle = straightAnkle,
            plantUsesFrontHip = workingIsFront,
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
