package com.monkfitness.app.poses

import com.monkfitness.app.animation.*

/**
 * Chin-up (underhand / supinated, narrow grip) — the supinated grip lets the biceps dominate and the
 * elbows track close to the ribs, so the sternum drives up to the bar while the pelvis sits slightly
 * behind the fixed hands. The pull still begins from the scapulae.
 */
class UnderhandChinUpPose : BaseVerticalPullPose() {

    override val metadata = PoseMetadata(
        camera = verticalPullCamera,
        durationSeconds = 3.0f,
        loopMode = LoopMode.PING_PONG,
        motionCurve = MotionCurve.EASE_IN_OUT,
        environment = verticalPullEnvironment,
        support = SupportDefinition(pivot = PivotType.HANDS, contacts = verticalPullSupportContacts),
        exerciseFamily = "vertical_pull",
        motionType = "Vertical Pull",
        bodyOrientation = "Hanging",
        defaultGrip = "underhand"
    )

    override val gripStyle = GripStyle.UNDERHAND
    override val gripWidthFactor = 0.9f
    override val elbowPoleA = Vector3(0.7f, -1f, 0f)
    override val elbowPoleP = Vector3(0.7f, -1f, 0f)
    override val bottomReach = 140f
    override val topReach = 95f

    override fun torsoPitchAt(rep: Float): Float = SkeletonMath.lerp(-0.04f, -0.16f, rep)
    override fun chestFlexAt(rep: Float): Float = SkeletonMath.lerp(0f, 0.18f, rep)
    override fun forwardArcAt(rep: Float): Float = SkeletonMath.lerp(0f, -14f, rep)
}
