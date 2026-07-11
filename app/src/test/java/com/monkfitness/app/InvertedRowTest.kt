package com.monkfitness.app

import com.monkfitness.app.animation.*
import com.monkfitness.app.poses.InvertedRowPose
import org.junit.Assert.*
import org.junit.Test

class InvertedRowTest {

    @Test
    fun testInvertedRowAnimationMeetsRequirements() {
        val poseBuilder = InvertedRowPose()
        val def = SkeletonDefinition.DEFAULT_ADULT
        val env = EnvironmentDefinition()
        val camera = Camera()
        val finalizer = SkeletonPoseFinalizer(def)

        // Define the validator with appropriate expected support joints if any, and other config
        val validatorConfig = ValidatorConfig(
            allowFootGroundPenetration = false,
            expectedSupportJoints = setOf(Joint.HAND_A, Joint.HAND_P, Joint.HEEL_F, Joint.HEEL_B, Joint.ANKLE_F, Joint.ANKLE_B)
        )
        val validator = ExerciseValidator(validatorConfig)

        val numFrames = 30
        val deltaTime = 0.033f
        val poses = ArrayList<SkeletonPose>()
        val snapshots = ArrayList<ExerciseSnapshot>()

        var previousPose: SkeletonPose? = null
        var prePreviousPose: SkeletonPose? = null
        var overallValid = true
        val allIssues = ArrayList<ValidationIssue>()

        val mockBitmap = org.mockito.Mockito.mock(android.graphics.Bitmap::class.java)

        for (frame in 0 until numFrames) {
            val progress = frame.toFloat() / (numFrames - 1)
            val context = PoseContext(
                progress = progress,
                side = Side.LEFT,
                definition = def,
                deltaTime = deltaTime * 1000f,
                cycleDuration = 3000f,
                playbackSpeed = 1f,
                mirrored = false,
                phase = 0f,
                loopIndex = 0
            )

            // Build raw pose
            val rawPose = poseBuilder.build(context)
            // Finalize the pose (computes all anatomical details and runs Forward Kinematics traversal)
            val finalizedPose = finalizer.finalize(rawPose)

            // Validate
            val report = validator.validate(
                pose = finalizedPose,
                definition = def,
                environment = env,
                camera = camera,
                width = 1000f,
                height = 1000f,
                previousPose = previousPose,
                prePreviousPose = prePreviousPose,
                deltaTime = deltaTime
            )

            if (!report.isValid) {
                overallValid = false
            }
            allIssues.addAll(report.allIssues)

            // Keep track of poses and snapshots
            poses.add(finalizedPose)
            snapshots.add(ExerciseSnapshot(frame, frame * deltaTime, mockBitmap, finalizedPose))

            prePreviousPose = previousPose
            previousPose = finalizedPose
        }

        // Assemble the validation results report for the whole sequence
        val fullReport = ValidationReport(
            isValid = overallValid,
            results = emptyList(), // ExerciseReview only uses allIssues
            allIssues = allIssues
        )

        // Evaluate using ExerciseReview
        val sequence = ExerciseSnapshotSequence(snapshots)
        val reviewReport = ExerciseReview.review(fullReport, sequence)

        println("=== INVERTED ROW VALIDATION REPORT ===")
        println("Overall Score: ${reviewReport.score}/100")
        println("Camera Problems: ${reviewReport.cameraProblems}")
        println("Ground Penetration: ${reviewReport.groundPenetration}")
        println("Limb Symmetry: ${reviewReport.limbSymmetry}")
        println("Body Balance: ${reviewReport.bodyBalance}")
        println("Hand Sliding: ${reviewReport.handSliding}")
        println("Foot Sliding: ${reviewReport.footSliding}")
        println("Viewport Clipping: ${reviewReport.viewportClipping}")
        println("Joint Discontinuities: ${reviewReport.jointDiscontinuities}")
        println("Bone Stretching: ${reviewReport.boneStretching}")
        println("All Individual Issues:")
        allIssues.forEach { println("  [${it.ruleId}] ${it.message}") }
        if (reviewReport.recommendations.isNotEmpty()) {
            println("Recommendations:")
            reviewReport.recommendations.forEach { println(" - $it") }
        }
        println("======================================")

        // Verify score >= 95
        assertTrue("Inverted Row animation score must be >= 95, actual: ${reviewReport.score}", reviewReport.score >= 95)
    }
}
