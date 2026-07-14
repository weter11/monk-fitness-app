package com.monkfitness.app.poses

import com.monkfitness.app.animation.*

/**
 * Neutral-grip (palms facing each other) pull-up — biomechanics-first rewrite.
 *
 * The neutral handles keep the elbows tucked tight to the ribs and driving
 * down/forward; the palms face inward. The grip is at shoulder width so
 * the hands sit almost directly below the shoulders (no lateral arm
 * abduction). The chest still leads up to the bar and the pull begins
 * from the scapulae. Hands are FIXED on the bar.
 */
class NeutralGripPullUpPose : BaseVerticalPullPose() {

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
        defaultGrip = "neutral"
    )

    override val gripStyle = GripStyle.NEUTRAL
    override val gripWidthFactor = 1.0f
    override val elbowPoleA = Vector3(0.8f, -1f, -0.5f)
    override val elbowPoleP = Vector3(0.8f, -1f, 0.5f)
    override val bottomReach = 140f
    override val topReach = 96f

    override fun torsoPitchAt(rep: Float): Float = SkeletonMath.lerp(-0.05f, -0.10f, rep)
    override fun chestFlexAt(rep: Float): Float = SkeletonMath.lerp(0f, 0.14f, rep)
    override fun forwardArcAt(rep: Float): Float = SkeletonMath.lerp(0f, -10f, rep)
}
