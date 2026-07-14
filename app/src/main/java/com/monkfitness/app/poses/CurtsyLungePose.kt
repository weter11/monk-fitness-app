package com.monkfitness.app.poses

import com.monkfitness.app.animation.*

/**
 * Curtsy Lunge — the rear foot crosses BEHIND and to the opposite side (the curtsy). The working
 * (front) foot plants forward; the crossing foot tracks behind-and-across, so the pelvis shifts
 * back and to the crossing side. Knees track forward over their feet (no valgus). Alternates sides.
 */
class CurtsyLungePose : BaseLungePose() {

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

        val r = MotionDrivers.LeftPhase(context.progress)   // Side-B works (front-right)
        val l = MotionDrivers.RightPhase(context.progress)  // Side-F works (front-left)
        val depth = r + l
        val stride = 70f
        val cross = 45f
        val crossZ = 45f

        // Working foot forward on its side; crossing foot behind and to the opposite side.
        targetB.set(stride * r - cross * l, ANKLE_Y, def.hipWidth + crossZ * l)
        targetF.set(stride * l - cross * r, ANKLE_Y, -def.hipWidth - crossZ * r)

        val pelvisX = (targetB.x + targetF.x) * 0.5f
        val pelvisZ = (targetB.z + targetF.z) * 0.5f
        val pelvisY = STAND_PELVIS_Y - LUNGE_DROP * depth
        val leanAngle = 0.18f * depth

        anchorSpine(def, pelvisX, pelvisY, pelvisZ, leanAngle)
        bakeLegs(def, leanAngle, targetF, targetB)

        val armSwing = r - l
        bakeArms(def, leanAngle, armSwing, pelvisX, pelvisY)

        // Working foot flat; crossing (rear) foot lifts its heel.
        val frontOnB = r >= l
        val fbPitch = if (frontOnB) FOOT_PITCH_FLAT * depth else FOOT_PITCH_HEEL_LIFT * depth
        val ffPitch = if (frontOnB) FOOT_PITCH_HEEL_LIFT * depth else FOOT_PITCH_FLAT * depth
        applyExtremities(def, leanAngle, fbPitch, ffPitch)

        return finalizeLunge()
    }
}
