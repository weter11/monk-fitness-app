package com.monkfitness.app.animation

import kotlin.math.*

/**
 * SupportMath is a generic biomechanical support computation engine.
 * Exposes methods to calculate effective support centroid, body lever length,
 * and the LeverModel without exercise-specific branching.
 */
data class PushUpGeometry(
    val height: Float,
    val theta: Float,
    val handAnchorX: Float
)

object SupportMath {

    /**
     * Consistently derives push-up geometry for any support height (flat or elevated).
     * - progress: frame progress from 0.0 (top) to 1.0 (bottom)
     * - pivotSupportHeight: support height of the pivot (e.g. 0f for standard, 40f for decline box)
     * - definition: skeleton definition
     * - topBodyHeight: target shoulder height at the peak of the pushup (typically 60f)
     * - bottomBodyHeight: target shoulder height at the bottom of the pushup (typically 25f or 20f)
     */
    fun computePushUpGeometry(
        progress: Float,
        pivotSupportHeight: Float,
        definition: SkeletonDefinition,
        topBodyHeight: Float = 60f,
        bottomBodyHeight: Float = 25f
    ): PushUpGeometry {
        val totalLegLen = definition.shinLength + definition.thighLength
        // Slightly flexed legTargetLen (8 degrees target flexion) to satisfy LegConstraint limit (0.998f max)
        val legTargetLen = totalLegLen * 0.99757f

        // Pivot's actual height off the floor
        val pivotHeight = pivotSupportHeight + 25f // 25f is ankle/knee height above their support

        // Body (shoulder) height range relative to the floor
        val height = lerp(topBodyHeight, bottomBodyHeight, progress)

        // Solve drivingHeight and theta for the leg angle
        val drivingHeight = (height - pivotHeight)
        val theta = asin((drivingHeight / legTargetLen).coerceIn(-1f, 1f))

        // Peak driving height to establish static hand placement
        val maxDrivingHeight = (topBodyHeight - pivotHeight)
        val maxTheta = asin((maxDrivingHeight / legTargetLen).coerceIn(-1f, 1f))
        val handAnchorX = 60f - definition.torsoLength * cos(maxTheta)

        return PushUpGeometry(height, theta, handAnchorX)
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

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
            val joints = getJointsForContact(contact)
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
            PivotType.HIPS -> {
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

    private fun getJointsForContact(contact: SupportContact): List<Joint> {
        return when (contact) {
            SupportContact.LEFT_FOOT -> listOf(Joint.ANKLE_B)
            SupportContact.RIGHT_FOOT -> listOf(Joint.ANKLE_F)
            SupportContact.LEFT_TOES -> listOf(Joint.TOE_B)
            SupportContact.RIGHT_TOES -> listOf(Joint.TOE_F)
            SupportContact.LEFT_KNEE -> listOf(Joint.KNEE_B)
            SupportContact.RIGHT_KNEE -> listOf(Joint.KNEE_F)
            SupportContact.LEFT_HAND -> listOf(Joint.HAND_P)
            SupportContact.RIGHT_HAND -> listOf(Joint.HAND_A)
            SupportContact.LEFT_FOREARM -> listOf(Joint.ELBOW_P, Joint.HAND_P)
            SupportContact.RIGHT_FOREARM -> listOf(Joint.ELBOW_A, Joint.HAND_A)
            SupportContact.HIPS -> listOf(Joint.PELVIS)
            SupportContact.CUSTOM -> listOf(Joint.PELVIS)
        }
    }
}
