package com.monkfitness.app.poses

import com.monkfitness.app.animation.*

/**
 * Alternating Reverse Lunge — the **active foot steps backward**, so the stationary
 * (front) foot is the support. Biomechanically it is the mirror of the forward
 * lunge (which leg leads flips), but the load still sits on the front/support leg
 * and the pelvis still lowers from support-knee flexion, never from a rigid
 * translation. The torso stays a touch more upright than the forward lunge.
 */
class AlternatingReverseLungesPose : BaseLungePose() {

    override val mode = LungeMode.REVERSE

    override val torsoLean = 0.12f

    override val metadata = PoseMetadata(
        camera = lungeCamera,
        durationSeconds = 4.0f,
        loopMode = LoopMode.LOOP,
        motionCurve = MotionCurve.LINEAR,
        environment = lungeEnvironment
    )
}
