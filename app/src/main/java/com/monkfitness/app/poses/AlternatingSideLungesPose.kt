package com.monkfitness.app.poses

import com.monkfitness.app.animation.*

/**
 * Alternating Side (Lateral) Lunge — support-leg-driven.
 *
 * Each leg steps OUT to the side in turn (Side-B peaks at progress 0.25, Side-F at 0.75). The
 * pelvis COM sits at the midpoint of the feet and shifts toward the working (lunging) foot; the
 * torso leans slightly forward to counterbalance the hip push-back. The working knee tracks
 * OUTWARD over the foot (lateral pole vectors) to avoid valgus; both feet stay flat.
 */
class AlternatingSideLungesPose : BaseLungePose() {

    override val metadata = PoseMetadata(
        camera = lungeCamera,
        durationSeconds = 4.0f,
        loopMode = LoopMode.LOOP,
        motionCurve = MotionCurve.LINEAR,
        environment = lungeEnvironment
    )

    override fun build(context: PoseContext): SkeletonPose {
        val def = context.definition
        ensureHierarchy(def)

        val r = MotionDrivers.LeftPhase(context.progress)
        val l = MotionDrivers.RightPhase(context.progress)
        val depth = r + l

        // Foot targets (world). Pure lateral step (X stays 0); the lunging foot goes out in Z.
        val footBz = def.hipWidth + SIDE_STRIDE * r
        val footFz = -def.hipWidth - SIDE_STRIDE * l
        targetB.set(0f, ANKLE_Y, footBz)
        targetF.set(0f, ANKLE_Y, footFz)

        // COM follows the feet: pelvis at the midpoint in Z, between the feet.
        val pelvisZ = (footBz + footFz) * 0.5f
        val pelvisY = STAND_PELVIS_Y - LUNGE_DROP * depth
        val leanAngle = 0.12f * depth

        anchorSpine(def, 0f, pelvisY, pelvisZ, leanAngle)

        // Lateral pole vectors so the working knee tracks outward over the foot.
        poleB.set(1f, 0f, 0.6f)
        poleF.set(1f, 0f, -0.6f)
        bakeLegsPoles(def, leanAngle, targetF, targetB, poleF, poleB)

        // Gentle contra-lateral counterbalance; arms are never frozen.
        val armSwing = (r - l) * 0.5f
        bakeArms(def, leanAngle, armSwing, 0f, pelvisY)

        // Both feet stay flat in a side lunge (weight shifts laterally, no heel lift).
        applyExtremities(def, leanAngle, FOOT_PITCH_FLAT * depth, FOOT_PITCH_FLAT * depth)

        return finalizeLunge()
    }
}
