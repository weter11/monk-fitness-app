package com.monkfitness.app.poses

import com.monkfitness.app.animation.*

/**
 * Scapular pull-up (dead-hang -> active-hang) — biomechanics-first rewrite.
 *
 * This is the FIRST phase of every pull-up, isolated: the scapulae
 * depress and retract to raise the body a few units while the arms
 * stay essentially straight. The reach only shortens a little (141 -> 131
 * of 146 max), so the elbows barely flex — the motion is scapular,
 * not humeral/elbow. PING_PONG bounces dead-hang <-> active-hang.
 * Hands are FIXED on the bar; the body rises relative to them.
 */
class ScapularPullUpPose : BaseVerticalPullPose() {

    override val metadata = PoseMetadata(
        camera = verticalPullCamera,
        durationSeconds = 3.0f,
        loopMode = LoopMode.PING_PONG,
        motionCurve = MotionCurve.EASE_IN_OUT,
        environment = verticalPullEnvironment,
        pivotType = PivotType.HANDS,
        support = SupportDefinition(pivot = PivotType.HANDS, contacts = verticalPullSupportContacts),
        supportContacts = verticalPullSupportContacts,
        exerciseFamily = "vertical_pull",
        motionType = "Scapular Activation",
        bodyOrientation = "Hanging"
    )

    override val gripStyle = GripStyle.OVERHAND
    override val gripWidthFactor = 1.5f
    override val elbowPoleA = Vector3(0f, -1f, -0.3f)
    override val elbowPoleP = Vector3(0f, -1f, 0.3f)
    override val bottomReach = 141f
    override val topReach = 131f

    override fun chestFlexAt(rep: Float): Float = SkeletonMath.lerp(0f, 0.05f, rep)
}
