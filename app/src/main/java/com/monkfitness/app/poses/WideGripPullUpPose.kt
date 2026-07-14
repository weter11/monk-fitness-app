package com.monkfitness.app.poses

import com.monkfitness.app.animation.*

/**
 * Wide-grip (overhand) pull-up — biomechanics-first rewrite.
 *
 * The wider grip abducts the shoulders, so the hands sit well outside the
 * shoulders and the elbows flare outward and down. Because the lateral span
 * "uses up" part of the arm length, the shoulders already sit higher at the
 * bottom and the vertical shoulder travel to a chin-over-bar top is larger
 * (this is why the wide grip is harder). The pull still begins from the
 * scapulae — the bottom is the extended hang, elbows flare only as the
 * reach shortens. Hands are FIXED on the bar.
 */
class WideGripPullUpPose : BaseVerticalPullPose() {

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
        defaultGrip = "wide overhand"
    )

    override val gripStyle = GripStyle.OVERHAND
    override val gripWidthFactor = 2.0f
    override val elbowPoleA = Vector3(0f, -1f, -2.6f)
    override val elbowPoleP = Vector3(0f, -1f, 2.6f)
    override val bottomReach = 140f
    override val topReach = 100f

    override fun chestFlexAt(rep: Float): Float = SkeletonMath.lerp(0f, 0.08f, rep)
    override fun scapularRetractionAt(rep: Float): Float = SkeletonMath.lerp(0f, 6f, rep)
}
