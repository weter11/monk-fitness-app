package com.monkfitness.app.poses

import com.monkfitness.app.animation.*

/**
 * Standard (overhand, shoulder-width-ish) pull-up — biomechanics-first rewrite.
 *
 * The pull is driven by a shrinking shoulder->bar reach: at the bottom the arms
 * are fully extended (dead/active hang) so the earliest motion can only be scapular
 * (shoulders depress + retract, body rises a few units, arms stay straight); only
 * as the reach shortens do the elbows bend and the chest leads up to the bar.
 * Hands are FIXED on the bar; the body is pulled up relative to them.
 */
class StandardPullUpPose : BaseVerticalPullPose() {

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
        motionType = "Vertical Pull",
        bodyOrientation = "Hanging",
        defaultGrip = "overhand"
    )

    override val gripStyle = GripStyle.OVERHAND
    override val gripWidthFactor = 1.35f
    override val elbowPoleA = Vector3(0f, -1f, -0.4f)
    override val elbowPoleP = Vector3(0f, -1f, 0.4f)
    override val bottomReach = 140f
    override val topReach = 96f

    override fun chestFlexAt(rep: Float): Float = SkeletonMath.lerp(0f, 0.12f, rep)
}
