package com.monkfitness.app.animation

/**
 * ExerciseReview performs automated, read-only biomechanical and visual
 * analysis of an exercise using its ValidationReport and SnapshotSequence.
 */
object ExerciseReview {

    /**
     * Inspects the validation report and snapshot sequence to generate a detailed review report.
     */
    fun review(
        report: ValidationReport,
        sequence: ExerciseSnapshotSequence? = null
    ): ExerciseReviewReport {
        val recommendations = ArrayList<String>()
        var score = 100

        // Track categories we have flagged to avoid double point deductions
        var hasCameraIssues = false
        var hasGroundIssues = false
        var hasLimbAsymmetry = false
        var hasBalanceIssues = false
        var hasHandSliding = false
        var hasFootSliding = false
        var hasViewportClipping = false
        var hasDiscontinuities = false
        var hasBoneStretching = false

        // 1. Analyze Viewport Clipping & Camera Problems from ValidationReport
        var cameraProblemsMsg = "Camera settings are optimal; the model remains fully visible."
        var viewportClippingMsg = "No viewport clipping detected."
        for (i in 0 until report.allIssues.size) {
            val issue = report.allIssues[i]
            if (issue.ruleId == "HEAD_VIEWPORT") {
                hasCameraIssues = true
                hasViewportClipping = true
                cameraProblemsMsg = "Camera placement failed to keep the head fully in frame."
                viewportClippingMsg = "Viewport clipping detected: ${issue.message}"
            }
        }
        if (hasViewportClipping) {
            score -= 15
            recommendations.add("Adjust camera pitch, yaw, or zoom to prevent the model from clipping out of the viewport.")
        }

        // 2. Analyze Ground Penetration
        var groundPenetrationMsg = "No ground penetration detected; feet remain strictly on or above support level."
        for (i in 0 until report.allIssues.size) {
            val issue = report.allIssues[i]
            if (issue.ruleId == "FOOT_GROUND_PENETRATION") {
                hasGroundIssues = true
                groundPenetrationMsg = "Ground penetration detected: ${issue.message}"
            }
        }
        if (hasGroundIssues) {
            score -= 15
            recommendations.add("Correct foot positions or adjust the environment's ground level to prevent feet from penetrating the floor.")
        }

        // 3. Analyze Body Balance
        var bodyBalanceMsg = "Excellent balance; body center of mass remains stable within the support base."
        for (i in 0 until report.allIssues.size) {
            val issue = report.allIssues[i]
            if (issue.ruleId == "STATIC_SUPPORT_POLYGON") {
                hasBalanceIssues = true
                bodyBalanceMsg = "Balance compromise detected: ${issue.message}"
            }
        }
        if (hasBalanceIssues) {
            score -= 15
            recommendations.add("Re-center the Pelvis (center of mass) to remain securely within the horizontal bounds of active support joints.")
        }

        // 4. Analyze Hand Sliding
        var handSlidingMsg = "Hands remain firmly planted and stable while supporting load."
        for (i in 0 until report.allIssues.size) {
            val issue = report.allIssues[i]
            if (issue.ruleId == "HAND_SLIDING") {
                hasHandSliding = true
                handSlidingMsg = "Hand sliding detected: ${issue.message}"
            }
        }
        if (hasHandSliding) {
            score -= 15
            recommendations.add("Ensure supporting hands are firmly locked in place with zero horizontal drift during loading phases.")
        }

        // 5. Analyze Discontinuities (Joint Discontinuities)
        var jointDiscontinuitiesMsg = "Transitions between frames are smooth and continuous."
        var hasVelocityDiscontinuity = false
        var hasPositionDiscontinuity = false
        var hasAccelerationSpike = false

        for (i in 0 until report.allIssues.size) {
            val issue = report.allIssues[i]
            if (issue.ruleId == "POSITION_DISCONTINUITY") {
                hasPositionDiscontinuity = true
                hasDiscontinuities = true
            } else if (issue.ruleId == "VELOCITY_DISCONTINUITY") {
                hasVelocityDiscontinuity = true
                hasDiscontinuities = true
            } else if (issue.ruleId == "ACCELERATION_SPIKE") {
                hasAccelerationSpike = true
            }
        }

        if (hasDiscontinuities || hasAccelerationSpike) {
            val detail = when {
                hasPositionDiscontinuity && hasVelocityDiscontinuity -> "sudden positional jumps and erratic velocity changes"
                hasPositionDiscontinuity -> "jerky positional jumps"
                hasVelocityDiscontinuity -> "abrupt velocity jumps"
                else -> "high acceleration spikes/jerks"
            }
            jointDiscontinuitiesMsg = "Discontinuous motion detected: $detail occurred between frames."
            score -= if (hasDiscontinuities) 15 else 5
            recommendations.add("Apply smooth interpolations (such as MotionCurve timing) to avoid frame-to-frame kinematic jumps.")
        }

        // 6. Analyze Bone Stretching
        var boneStretchingMsg = "Bone proportions are consistent and anatomically accurate."
        var hasBoneStretchError = false
        var hasIKLimitError = false
        for (i in 0 until report.allIssues.size) {
            val issue = report.allIssues[i]
            if (issue.ruleId == "BONE_LENGTH") {
                hasBoneStretchError = true
                hasBoneStretching = true
            } else if (issue.ruleId == "IK_CONSTRAINT_LIMIT") {
                hasIKLimitError = true
                hasBoneStretching = true
            }
        }
        if (hasBoneStretching) {
            val reason = if (hasBoneStretchError && hasIKLimitError) {
                "altered bone lengths and exceeded joint IK limits"
            } else if (hasBoneStretchError) {
                "non-constant bone lengths"
            } else {
                "joints extending beyond physiological IK limits"
            }
            boneStretchingMsg = "Anatomical bone stretching detected: $reason."
            score -= 15
            recommendations.add("Ensure that all joint segments are generated strictly through the Forward Kinematics chain to keep bone lengths constant.")
        }

        // 7. Advanced sequence-driven analysis (Limb Symmetry and Foot Sliding)
        var limbSymmetryMsg = "Limb movement is symmetric and well-coordinated."
        var footSlidingMsg = "Feet remain stably planted on the ground."

        if (sequence != null && sequence.snapshots.isNotEmpty()) {
            val snapshots = sequence.snapshots
            var maxArmAsymmetry = 0f
            var maxLegAsymmetry = 0f
            var detectedFootSlide = 0f

            for (i in snapshots.indices) {
                val snapshot = snapshots[i]
                val pose = snapshot.pose
                if (pose != null) {
                    // Arm symmetry check
                    val shA = pose.getJoint(Joint.SHOULDER_A)
                    val handA = pose.getJoint(Joint.HAND_A)
                    val lenArmA = distance(shA, handA)

                    val shP = pose.getJoint(Joint.SHOULDER_P)
                    val handP = pose.getJoint(Joint.HAND_P)
                    val lenArmP = distance(shP, handP)

                    val armDiff = kotlin.math.abs(lenArmA - lenArmP)
                    if (armDiff > maxArmAsymmetry) {
                        maxArmAsymmetry = armDiff
                    }

                    // Leg symmetry check
                    val hipF = pose.getJoint(Joint.HIP_F)
                    val ankleF = pose.getJoint(Joint.ANKLE_F)
                    val lenLegF = distance(hipF, ankleF)

                    val hipB = pose.getJoint(Joint.HIP_B)
                    val ankleB = pose.getJoint(Joint.ANKLE_B)
                    val lenLegB = distance(hipB, ankleB)

                    val legDiff = kotlin.math.abs(lenLegF - lenLegB)
                    if (legDiff > maxLegAsymmetry) {
                        maxLegAsymmetry = legDiff
                    }
                }

                // Foot sliding check between consecutive snapshots
                if (i > 0) {
                    val prevPose = snapshots[i - 1].pose
                    val currPose = pose
                    if (prevPose != null && currPose != null) {
                        val feet = arrayOf(Joint.ANKLE_F, Joint.ANKLE_B, Joint.HEEL_F, Joint.HEEL_B, Joint.TOE_F, Joint.TOE_B)
                        for (f in feet) {
                            val prevF = prevPose.getJoint(f)
                            val currF = currPose.getJoint(f)
                            // Near the ground
                            if (currF.y < 12f) {
                                val dx = currF.x - prevF.x
                                val dz = currF.z - prevF.z
                                val dist = kotlin.math.sqrt(dx * dx + dz * dz)
                                if (dist > detectedFootSlide) {
                                    detectedFootSlide = dist
                                }
                            }
                        }
                    }
                }
            }

            if (maxArmAsymmetry > 15f || maxLegAsymmetry > 15f) {
                hasLimbAsymmetry = true
                limbSymmetryMsg = String.format(
                    "Limb asymmetry detected: Max difference between active and passive arms was %.1f, and legs was %.1f.",
                    maxArmAsymmetry, maxLegAsymmetry
                )
                score -= 10
                recommendations.add("Enforce symmetric coordinates and equal joint flexion/extension angles across left and right sides.")
            }

            if (detectedFootSlide > 0.5f) {
                hasFootSliding = true
                footSlidingMsg = String.format(
                    "Foot sliding detected: Ground contact joints slid horizontally by up to %.2f units.",
                    detectedFootSlide
                )
                score -= 10
                recommendations.add("Keep feet fully locked to their ground coordinates while acting as support anchors.")
            }
        }

        return ExerciseReviewReport(
            score = score.coerceIn(0, 100),
            cameraProblems = cameraProblemsMsg,
            groundPenetration = groundPenetrationMsg,
            limbSymmetry = limbSymmetryMsg,
            bodyBalance = bodyBalanceMsg,
            handSliding = handSlidingMsg,
            footSliding = footSlidingMsg,
            viewportClipping = viewportClippingMsg,
            jointDiscontinuities = jointDiscontinuitiesMsg,
            boneStretching = boneStretchingMsg,
            recommendations = recommendations
        )
    }

    private fun distance(v1: Vector3, v2: Vector3): Float {
        val dx = v1.x - v2.x
        val dy = v1.y - v2.y
        val dz = v1.z - v2.z
        return kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
    }
}
