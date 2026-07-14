package com.monkfitness.app.poses

import com.monkfitness.app.animation.*
import kotlin.math.*

/**
 * Dead hang — biomechanics-first rewrite.
 *
 * A static, bar-supported hold (no pull). The arms are fully extended
 * to the FIXED bar and the body hangs with a gentle breathing sway
 * and a slight forward leg pendulum — alive, not frozen. Because the
 * hand targets are constant, the hands stay glued to the bar while the
 * trunk and legs breathe. No elbow flexion is modeled: this is the
 * bottom-of-the-pull reference position.
 */
class HangPose : BaseVerticalPullPose() {

    override val metadata = PoseMetadata(
        camera = verticalPullCamera,
        durationSeconds = 4.0f,
        loopMode = LoopMode.LOOP,
        motionCurve = MotionCurve.LINEAR,
        environment = verticalPullEnvironment,
        pivotType = PivotType.HANDS,
        support = SupportDefinition(pivot = PivotType.HANDS, contacts = verticalPullSupportContacts),
        supportContacts = verticalPullSupportContacts,
        exerciseFamily = "vertical_pull",
        motionType = "Isometric Hang",
        bodyOrientation = "Hanging",
        defaultGrip = "overhand"
    )

    override val gripStyle = GripStyle.OVERHAND
    override val gripWidthFactor = 1.5f
    override val elbowPoleA = Vector3(0f, -1f, 0f)
    override val elbowPoleP = Vector3(0f, -1f, 0f)
    override val bottomReach = 139f
    override val topReach = 139f

    override fun scapularRetractionAt(rep: Float): Float = 0f
    override fun scapularDepressionAt(rep: Float): Float = 0f
    override fun breathWave(lift: Float): Float = sin(lift * 2f * PI.toFloat())
}
