package com.monkfitness.app.poses

import com.monkfitness.app.animation.*

/**
 * Static Split Squat — a FIXED split stance held for the whole rep. Both feet are planted (the
 * support foot never moves) and the pelvis dips straight down and back up via a PING_PONG pulse,
 * so there is no snap at the turnaround. The pelvis is biased forward over the front/support foot.
 * Arms counter-swing gently and are never frozen.
 */
class StaticSplitSquatPose : BaseLungePose() {

    override val metadata = PoseMetadata(
        camera = lungeCamera,
        durationSeconds = 5.0f,
        loopMode = LoopMode.PING_PONG,
        motionCurve = MotionCurve.EASE_IN_OUT,
        environment = lungeEnvironment
    )

    override fun build(context: PoseContext): SkeletonPose {
        val def = context.definition
        ensureHierarchy(def)

        val depth = MotionDrivers.Pulse(context.progress) // 0 -> 1 -> 0
        val stride = 50f

        // Fixed split stance: front foot forward, rear foot behind. Neither moves.
        val footBx = stride
        val footFx = -stride * 0.6f
        targetB.set(footBx, ANKLE_Y, def.hipWidth)
        targetF.set(footFx, ANKLE_Y, -def.hipWidth)

        val pelvisX = (footBx + footFx) * 0.5f + 12f * depth
        val pelvisY = STAND_PELVIS_Y - 60f * depth
        val leanAngle = 0.14f * depth

        anchorSpine(def, pelvisX, pelvisY, 0f, leanAngle)
        bakeLegs(def, leanAngle, targetF, targetB)

        // Gentle counterbalance swing (never frozen).
        val armSwing = MotionDrivers.FullSine(context.progress) * 0.3f
        bakeArms(def, leanAngle, armSwing, pelvisX, pelvisY)

        // Front (support) foot flat; rear foot lifts its heel.
        applyExtremities(def, leanAngle, FOOT_PITCH_FLAT * depth, FOOT_PITCH_HEEL_LIFT * depth)

        return finalizeLunge()
    }
}
