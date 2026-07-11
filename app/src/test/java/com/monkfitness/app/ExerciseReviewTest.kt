package com.monkfitness.app

import android.graphics.Bitmap
import com.monkfitness.app.animation.*
import org.junit.Assert.*
import org.junit.Test

class ExerciseReviewTest {

    private val emptyResults = emptyList<ValidationResult>()
    private val emptyIssues = emptyList<ValidationIssue>()

    @Test
    fun testReviewIdealCase() {
        // Ideal case: no issues, no snapshots
        val report = ValidationReport(isValid = true, results = emptyResults, allIssues = emptyIssues)
        val reviewReport = ExerciseReview.review(report)

        assertEquals(100, reviewReport.score)
        assertTrue(reviewReport.recommendations.isEmpty())
        assertEquals("Camera settings are optimal; the model remains fully visible.", reviewReport.cameraProblems)
        assertEquals("No ground penetration detected; feet remain strictly on or above support level.", reviewReport.groundPenetration)
        assertEquals("Limb movement is symmetric and well-coordinated.", reviewReport.limbSymmetry)
        assertEquals("Excellent balance; body center of mass remains stable within the support base.", reviewReport.bodyBalance)
        assertEquals("Hands remain firmly planted and stable while supporting load.", reviewReport.handSliding)
        assertEquals("Feet remain stably planted on the ground.", reviewReport.footSliding)
        assertEquals("No viewport clipping detected.", reviewReport.viewportClipping)
        assertEquals("Transitions between frames are smooth and continuous.", reviewReport.jointDiscontinuities)
        assertEquals("Bone proportions are consistent and anatomically accurate.", reviewReport.boneStretching)
    }

    @Test
    fun testReviewWithViewportAndGroundIssues() {
        // Viewport and Ground issues reported in ValidationReport
        val issues = listOf(
            ValidationIssue("HEAD_VIEWPORT", "Head clipped out", ValidationSeverity.ERROR, Joint.HEAD_POS),
            ValidationIssue("FOOT_GROUND_PENETRATION", "Foot below floor", ValidationSeverity.ERROR, Joint.ANKLE_F)
        )
        val report = ValidationReport(isValid = false, results = emptyResults, allIssues = issues)
        val reviewReport = ExerciseReview.review(report)

        // Deductions: 100 - 15 (viewport) - 15 (ground) = 70
        assertEquals(70, reviewReport.score)
        assertEquals(2, reviewReport.recommendations.size)
        assertTrue(reviewReport.viewportClipping.contains("Head clipped out"))
        assertTrue(reviewReport.groundPenetration.contains("Foot below floor"))
    }

    @Test
    fun testReviewWithAsymmetryAndFootSliding() {
        val report = ValidationReport(isValid = true, results = emptyResults, allIssues = emptyIssues)

        // Let's create two mock snapshots containing poses
        val pose1 = SkeletonPose()
        // Initialize left arm length to 10f, right arm length to 40f (asymmetry > 15f)
        pose1.setJoint(Joint.SHOULDER_A, Vector3(0f, 100f, 0f))
        pose1.setJoint(Joint.HAND_A, Vector3(0f, 90f, 0f)) // len = 10f

        pose1.setJoint(Joint.SHOULDER_P, Vector3(0f, 100f, 0f))
        pose1.setJoint(Joint.HAND_P, Vector3(0f, 60f, 0f)) // len = 40f

        // Initial foot at (0, 5, 0) - Y is 5f (< 12f)
        pose1.setJoint(Joint.ANKLE_F, Vector3(0f, 5f, 0f))

        val pose2 = SkeletonPose()
        // Keep same arm length to focus on foot sliding
        pose2.setJoint(Joint.SHOULDER_A, Vector3(0f, 100f, 0f))
        pose2.setJoint(Joint.HAND_A, Vector3(0f, 90f, 0f))
        pose2.setJoint(Joint.SHOULDER_P, Vector3(0f, 100f, 0f))
        pose2.setJoint(Joint.HAND_P, Vector3(0f, 60f, 0f))

        // Foot slid to (10f, 5f, 0f) - displacement of 10f (> 0.5f)
        pose2.setJoint(Joint.ANKLE_F, Vector3(10f, 5f, 0f))

        val mockBitmap = try {
            Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        } catch (e: Throwable) {
            null
        }

        if (mockBitmap != null) {
            val snapshots = listOf(
                ExerciseSnapshot(0, 0f, mockBitmap, pose1),
                ExerciseSnapshot(1, 1f, mockBitmap, pose2)
            )
            val sequence = ExerciseSnapshotSequence(snapshots)

            val reviewReport = ExerciseReview.review(report, sequence)

            // Deductions: 100 - 10 (asymmetry) - 10 (foot slide) = 80
            assertEquals(80, reviewReport.score)
            assertTrue(reviewReport.limbSymmetry.contains("Limb asymmetry detected"))
            assertTrue(reviewReport.footSliding.contains("Foot sliding detected"))
            assertEquals(2, reviewReport.recommendations.size)
        }
    }
}
