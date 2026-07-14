package com.monkfitness.app.poses

import com.monkfitness.app.animation.*

/**
 * Walking Lunge — alternating forward lunge with a longer stride, deeper lean and forward
 * travel bias (walking momentum). Support-leg-driven: the pelvis COM is the midpoint of the two
 * feet plus a forward bias, and the rear foot plantarflexes. Distinct from the plain forward
 * lunge via stride length, lean and the travel bias.
 */
class WalkingLungePose : BaseLungePose() {

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
        val stride = 95f

        val footBx = stride * r
        val footFx = stride * l
        targetB.set(footBx, ANKLE_Y, def.hipWidth)
        targetF.set(footFx, ANKLE_Y, -def.hipWidth)

        val pelvisX = (footBx + footFx) * 0.5f + 10f * depth
        val pelvisY = STAND_PELVIS_Y - (LUNGE_DROP + 8f) * depth
        val leanAngle = 0.20f * depth

        anchorSpine(def, pelvisX, pelvisY, 0f, leanAngle)
        bakeLegs(def, leanAngle, targetF, targetB)

        val armSwing = r - l
        bakeArms(def, leanAngle, armSwing, pelvisX, pelvisY)

        val frontOnB = r >= l
        val fbPitch = if (frontOnB) FOOT_PITCH_FLAT * depth else FOOT_PITCH_HEEL_LIFT * depth
        val ffPitch = if (frontOnB) FOOT_PITCH_HEEL_LIFT * depth else FOOT_PITCH_FLAT * depth
        applyExtremities(def, leanAngle, fbPitch, ffPitch)

        return finalizeLunge()
    }
}
