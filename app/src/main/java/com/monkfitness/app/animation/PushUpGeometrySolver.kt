package com.monkfitness.app.animation

import kotlin.math.*

class PushUpSolverResult {
    var pelvisHeight: Float = 0f
    var theta: Float = 0f
    var ankleX: Float = 0f
    var ankleHeight: Float = 0f
    var handAnchorX: Float = 0f
    var kneeHeight: Float = 0f
    var kneeX: Float = 0f

    fun set(
        pelvisHeight: Float,
        theta: Float,
        ankleX: Float,
        ankleHeight: Float,
        handAnchorX: Float,
        kneeHeight: Float,
        kneeX: Float
    ): PushUpSolverResult {
        this.pelvisHeight = pelvisHeight
        this.theta = theta
        this.ankleX = ankleX
        this.ankleHeight = ankleHeight
        this.handAnchorX = handAnchorX
        this.kneeHeight = kneeHeight
        this.kneeX = kneeX
        return this
    }
}

object PushUpGeometrySolver {

    // Biomechanical alignment and spacing constants
    const val PELVIS_ANCHOR_X = 60f
    const val BASE_ANKLE_HEIGHT = 25f
    const val BASE_KNEE_HEIGHT = 15f

    // Anatomical and legacy compatibility constants
    const val KNEE_OFFSET_TOP_RATIO = 0.4018f     // Ratio of thigh length to derive the knee-pivot pelvis offset at top of rep
    const val KNEE_OFFSET_BOTTOM_RATIO = 0.3125f  // Ratio of thigh length to derive the knee-pivot pelvis offset at bottom of rep
    const val LEG_OFFSET_TOP_RATIO = 1f / 6f      // Ratio of leg target length to derive the feet-pivot pelvis offset at top of rep
    const val LEG_OFFSET_BOTTOM_RATIO = 1f / 14f  // Ratio of leg target length to derive the feet-pivot pelvis offset at bottom of rep

    const val TARGET_KNEE_FLEXION_DEGREES = 8f    // Small knee flexion to prevent joint lockout and satisfy leg constraints
    const val SHIN_PITCH_ANGLE = (PI / 4.0).toFloat() // 45 degrees upward angle of shins to prevent ground foot penetration in knee-pivot

    fun solve(
        definition: SkeletonDefinition,
        support: SupportDefinition,
        gripWidthMultiplier: Float,
        progress: Float,
        result: PushUpSolverResult
    ): PushUpSolverResult {
        val pivot = support.pivot
        val supportElevation = support.supportHeight

        val shinL = definition.shinLength
        val thighL = definition.thighLength
        val targetFlexionDegrees = TARGET_KNEE_FLEXION_DEGREES
        val phi = targetFlexionDegrees * PI.toFloat() / 180f
        val legTargetLen = sqrt(shinL * shinL + thighL * thighL + 2f * shinL * thighL * cos(phi))

        // Derive pelvis offsets from leg proportions to eliminate magic numbers
        val pelvisOffsetTop = if (pivot == PivotType.KNEES) {
            thighL * KNEE_OFFSET_TOP_RATIO
        } else {
            legTargetLen * LEG_OFFSET_TOP_RATIO
        }

        val pelvisOffsetBottom = if (pivot == PivotType.KNEES) {
            thighL * KNEE_OFFSET_BOTTOM_RATIO
        } else {
            legTargetLen * LEG_OFFSET_BOTTOM_RATIO
        }

        // Linearly interpolate pelvis offset based on progress
        val pelvisOffset = pelvisOffsetTop + (pelvisOffsetBottom - pelvisOffsetTop) * progress

        val baseHeight = if (pivot == PivotType.KNEES) BASE_KNEE_HEIGHT else BASE_ANKLE_HEIGHT
        val pivotHeight = baseHeight + supportElevation
        val pelvisHeight = pivotHeight + pelvisOffset

        val baseLen = if (pivot == PivotType.KNEES) thighL else legTargetLen
        val theta = asin((pelvisOffset / baseLen).coerceIn(-1f, 1f))

        // Hand anchor X is computed relative to pelvis anchor X using the top-of-rep theta
        val thetaTop = asin((pelvisOffsetTop / baseLen).coerceIn(-1f, 1f))
        val handAnchorX = PELVIS_ANCHOR_X - definition.torsoLength * cos(thetaTop)

        val ankleHeight: Float
        val ankleX: Float
        val kneeHeight: Float
        val kneeX: Float

        if (pivot == PivotType.KNEES) {
            kneeHeight = BASE_KNEE_HEIGHT + supportElevation
            kneeX = PELVIS_ANCHOR_X + thighL * cos(theta)

            val shinPitch = SHIN_PITCH_ANGLE
            ankleHeight = kneeHeight + shinL * sin(shinPitch)
            ankleX = kneeX + shinL * cos(shinPitch)
        } else {
            ankleHeight = BASE_ANKLE_HEIGHT + supportElevation
            ankleX = PELVIS_ANCHOR_X + legTargetLen * cos(theta)
            kneeHeight = BASE_KNEE_HEIGHT + supportElevation
            kneeX = 0f
        }

        return result.set(
            pelvisHeight = pelvisHeight,
            theta = theta,
            ankleX = ankleX,
            ankleHeight = ankleHeight,
            handAnchorX = handAnchorX,
            kneeHeight = kneeHeight,
            kneeX = kneeX
        )
    }
}
