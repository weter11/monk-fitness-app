package com.monkfitness.app.animation

import kotlin.math.*

/**
 * SupportMath is a generic biomechanical support computation engine.
 * Exposes methods to calculate effective support centroid, body lever length,
 * and the LeverModel without exercise-specific branching.
 */
data class PushUpGeometry(
    val pelvisHeight: Float,
    val theta: Float,
    val ankleX: Float,
    val handAnchorX: Float
)

object SupportMath {

    private val jointsForContactMap: Map<SupportPoint, List<Joint>> = SupportPoint.values().associateWith { point ->
        when (point) {
            SupportPoint.LEFT_FOOT -> listOf(Joint.ANKLE_B)
            SupportPoint.RIGHT_FOOT -> listOf(Joint.ANKLE_F)
            SupportPoint.LEFT_TOES -> listOf(Joint.TOE_B)
            SupportPoint.RIGHT_TOES -> listOf(Joint.TOE_F)
            SupportPoint.LEFT_KNEE -> listOf(Joint.KNEE_B)
            SupportPoint.RIGHT_KNEE -> listOf(Joint.KNEE_F)
            SupportPoint.LEFT_HAND -> listOf(Joint.HAND_P)
            SupportPoint.RIGHT_HAND -> listOf(Joint.HAND_A)
            SupportPoint.LEFT_ELBOW -> listOf(Joint.ELBOW_P)
            SupportPoint.RIGHT_ELBOW -> listOf(Joint.ELBOW_A)
            SupportPoint.LEFT_FOREARM -> listOf(Joint.ELBOW_P, Joint.HAND_P)
            SupportPoint.RIGHT_FOREARM -> listOf(Joint.ELBOW_A, Joint.HAND_A)
            SupportPoint.HIPS, SupportPoint.PELVIS -> listOf(Joint.PELVIS)
            SupportPoint.BACK -> listOf(Joint.PELVIS)
            SupportPoint.CUSTOM -> listOf(Joint.PELVIS)
        }
    }

    /**
     * Consistently derives the required push-up geometry for any support height,
     * ensuring that changing the pivot height automatically updates all dependent values
     * (pelvis height, leg angle theta, ankle position, and hand anchor position)
     * to keep the shoulder heights and arm distances physically reachable.
     */
    fun derivePushUpGeometry(
        progress: Float,
        supportHeight: Float,
        legTargetLen: Float,
        torsoLength: Float,
        fixedPelvisX: Float = 60f,
        pelvisOffsetTop: Float = 35f,
        pelvisOffsetBottom: Float = 0f
    ): PushUpGeometry {
        val ankleHeight = 25f + supportHeight

        // Pelvis height is derived as a relative offset above ankle height
        val relativeHeight = pelvisOffsetTop + (pelvisOffsetBottom - pelvisOffsetTop) * progress
        val pelvisHeight = ankleHeight + relativeHeight

        val theta = asin((relativeHeight / legTargetLen).coerceIn(-1f, 1f))

        // At the top of the rep (progress = 0.0), relative pelvis height is pelvisOffsetTop
        val thetaTop = asin((pelvisOffsetTop / legTargetLen).coerceIn(-1f, 1f))

        val handAnchorX = fixedPelvisX - torsoLength * cos(thetaTop)
        val ankleX = fixedPelvisX + legTargetLen * cos(theta)

        return PushUpGeometry(pelvisHeight, theta, ankleX, handAnchorX)
    }

    /**
     * Determines the automatic/effective body lever length based on the PivotType.
     * - FEET: shin + thigh + torso
     * - KNEES: thigh + torso
     * - HANDS: torso
     * - Others / CUSTOM: custom or fallback (e.g., 0f or user-defined)
     */
    fun computeLeverLength(pivot: PivotType, definition: SkeletonDefinition, customLength: Float = 0f): Float {
        return when (pivot) {
            PivotType.FEET -> definition.shinLength + definition.thighLength + definition.torsoLength
            PivotType.KNEES -> definition.thighLength + definition.torsoLength
            PivotType.HANDS -> definition.torsoLength
            PivotType.CUSTOM -> customLength
            else -> 0f
        }
    }

    /**
     * Computes the support centroid (the average position of all active support contacts).
     * If the set of contacts is empty, falls back to the pivot position or zero.
     */
    fun computeSupportCentroid(
        pose: SkeletonPose,
        contacts: Set<SupportContact>,
        fallback: Vector3 = Vector3(0f, 0f, 0f)
    ): Vector3 {
        if (contacts.isEmpty()) {
            return fallback.copy()
        }
        val sum = Vector3(0f, 0f, 0f)
        var count = 0
        for (contact in contacts) {
            val joints = getJointsForContact(contact.point)
            for (joint in joints) {
                sum.add(pose.getJoint(joint))
                count++
            }
        }
        if (count > 0) {
            sum.divide(count.toFloat())
        } else {
            sum.set(fallback)
        }
        return sum
    }

    /**
     * Determines the pivot position in world coordinates from the pose based on PivotType.
     */
    fun getPivotPosition(pose: SkeletonPose, pivot: PivotType): Vector3 {
        return when (pivot) {
            PivotType.FEET -> {
                val left = pose.getJoint(Joint.ANKLE_B)
                val right = pose.getJoint(Joint.ANKLE_F)
                Vector3((left.x + right.x) / 2f, (left.y + right.y) / 2f, (left.z + right.z) / 2f)
            }
            PivotType.KNEES -> {
                val left = pose.getJoint(Joint.KNEE_B)
                val right = pose.getJoint(Joint.KNEE_F)
                Vector3((left.x + right.x) / 2f, (left.y + right.y) / 2f, (left.z + right.z) / 2f)
            }
            PivotType.HANDS -> {
                val left = pose.getJoint(Joint.HAND_P)
                val right = pose.getJoint(Joint.HAND_A)
                Vector3((left.x + right.x) / 2f, (left.y + right.y) / 2f, (left.z + right.z) / 2f)
            }
            PivotType.HIPS, PivotType.PELVIS -> {
                pose.getJoint(Joint.PELVIS).copy()
            }
            PivotType.ELBOWS -> {
                val left = pose.getJoint(Joint.ELBOW_P)
                val right = pose.getJoint(Joint.ELBOW_A)
                Vector3((left.x + right.x) / 2f, (left.y + right.y) / 2f, (left.z + right.z) / 2f)
            }
            PivotType.CUSTOM -> {
                pose.getJoint(Joint.PELVIS).copy()
            }
        }
    }

    /**
     * Computes the full LeverModel.
     */
    fun computeLeverModel(
        pose: SkeletonPose,
        definition: SkeletonDefinition,
        support: SupportDefinition,
        customLeverLength: Float = 0f
    ): LeverModel {
        val pivotPos = getPivotPosition(pose, support.pivot)
        pivotPos.add(Vector3(support.offsetX, support.offsetY, support.offsetZ))
        val len = computeLeverLength(support.pivot, definition, customLeverLength)
        return LeverModel(len, pivotPos)
    }

    private fun getJointsForContact(point: SupportPoint): List<Joint> {
        return jointsForContactMap[point] ?: emptyList()
    }

    fun findAnchor(environment: EnvironmentDefinition, id: String): EnvironmentAnchor? {
        val size = environment.anchors.size
        for (i in 0 until size) {
            val anchor = environment.anchors[i]
            if (anchor.id == id) {
                return anchor
            }
        }
        return null
    }

    fun getAnchorPosition(environment: EnvironmentDefinition, id: String, fallback: Vector3): Vector3 {
        return findAnchor(environment, id)?.worldPosition ?: fallback
    }
}
