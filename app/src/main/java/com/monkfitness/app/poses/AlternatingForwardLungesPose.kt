package com.monkfitness.app.poses

import com.monkfitness.app.animation.*

/**
 * Alternating Forward Lunge — support-leg-driven.
 *
 * Each leg steps forward in turn (Side-B peaks at progress 0.25, Side-F at 0.75). The planted
 * forward foot is the support; the pelvis COM is derived from the two foot targets (it sits at
 * their midpoint and drops with depth) rather than being a free pelvis translation. The rear
 * foot plantarflexes (heel lift) and the arms counter-swing.
 */
class AlternatingForwardLungesPose : BaseLungePose() {

    override val metadata = PoseMetadata(
        camera = lungeCamera,
        durationSeconds = 4.0f,
        loopMode = LoopMode.LOOP,
        // LINEAR is intentional: the sine phase map below governs acceleration continuously.
        motionCurve = MotionCurve.LINEAR,
        environment = lungeEnvironment
    )

    override fun build(context: PoseContext): SkeletonPose {
        val def = context.definition
        ensureHierarchy(def)

        val r = MotionDrivers.LeftPhase(context.progress)   // Side-B steps forward, peaks at p = 0.25
        val l = MotionDrivers.RightPhase(context.progress)  // Side-F steps forward, peaks at p = 0.75
        val depth = r + l                                   // 0 standing -> 1 at each lunge bottom

        // Foot targets (world). Both feet alternate stepping forward (+X).
        val footBx = DEFAULT_STRIDE * r
        val footFx = DEFAULT_STRIDE * l
        targetB.set(footBx, ANKLE_Y, def.hipWidth)
        targetF.set(footFx, ANKLE_Y, -def.hipWidth)

        // COM follows the support feet: pelvis is the midpoint of the feet, dropped by depth.
        val pelvisX = (footBx + footFx) * 0.5f
        val pelvisY = STAND_PELVIS_Y - LUNGE_DROP * depth
        val leanAngle = 0.16f * depth

        anchorSpine(def, pelvisX, pelvisY, 0f, leanAngle)
        bakeLegs(def, leanAngle, targetF, targetB)

        // Contra-lateral counterbalance: when the right (Side-B) leg is forward, the left arm swings forward.
        val armSwing = r - l
        bakeArms(def, leanAngle, armSwing, pelvisX, pelvisY)

        // The forward (support) foot stays flat; the rear foot lifts its heel.
        val frontOnB = r >= l
        val fbPitch = if (frontOnB) FOOT_PITCH_FLAT * depth else FOOT_PITCH_HEEL_LIFT * depth
        val ffPitch = if (frontOnB) FOOT_PITCH_HEEL_LIFT * depth else FOOT_PITCH_FLAT * depth
        applyExtremities(def, leanAngle, fbPitch, ffPitch)

        return finalizeLunge()
    }
}
