package com.monkfitness.app.poses

import com.monkfitness.app.animation.*

/**
 * Chin-up (underhand / supinated, narrow grip) — biomechanics-first rewrite.
 *
 * The supinated grip lets the biceps dominate and the elbows track close
 * to the ribs and forward, so the sternum drives up to the bar. The
 * body arcs: the pelvis sits slightly behind the fixed hands while the chest
 * reaches forward to the bar at the top (extra forward lean + thoracic
 * extension). The pull still begins from the scapulae — the bottom is
 * the extended hang; elbows only bend once the reach shortens. Hands are
 * FIXED on the bar.
 */
class UnderhandChinUpPose : BaseVerticalPullPose() {

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
