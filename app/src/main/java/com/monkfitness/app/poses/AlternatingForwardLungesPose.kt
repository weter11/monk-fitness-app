package com.monkfitness.app.poses

import com.monkfitness.app.animation.*

/**
 * Alternating Forward Lunge — a sagittal lunge where the **active foot steps
 * forward** and becomes the support. The pelvis lowers as a consequence of the
 * support-knee flexion (derived from [BaseLungePose.bottomHeight]), the COM shifts
 * forward over the front foot, and the contralateral arm counter-swings.
 *
 * Timing: the internal stance/swing drivers make progress 0 / 0.5 / 1 identical
 * (mid-stance standing), so a closed [LoopMode.LOOP] is continuous with no snap.
 */
class AlternatingForwardLungesPose : BaseLungePose() {

    override val mode = LungeMode.FORWARD

    override val metadata = PoseMetadata(
        camera = lungeCamera,
        durationSeconds = 4.0f,
        loopMode = LoopMode.LOOP,
        motionCurve = MotionCurve.LINEAR,
        environment = lungeEnvironment
    )
}
