package com.monkfitness.app

import com.monkfitness.app.animation.*
import com.monkfitness.app.poses.CatCowPose
import org.junit.Assert.*
import org.junit.Test

class CatCowTest {

    @Test
    fun testCatCowAnimationMeetsRequirements() {
        val poseBuilder = CatCowPose()
        val def = SkeletonDefinition.DEFAULT_ADULT
        val env = EnvironmentDefinition()
        val camera = Camera()
        val finalizer = SkeletonPoseFinalizer(def)

        val validatorConfig = ValidatorConfig(
            allowFootGroundPenetration = false,
            expectedSupportJoints = setOf(Joint.HAND_A, Joint.HAND_P, Joint.KNEE_F, Joint.KNEE_B, Joint.ANKLE_F, Joint.ANKLE_B)
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
                cycleDuration = 4000f,
                playbackSpeed = 1f,
                mirrored = false,
                phase = 0f,
                loopIndex = 0
            )

            val rawPose = poseBuilder.build(context)
            val finalizedPose = finalizer.finalize(rawPose)

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

            poses.add(finalizedPose)
            snapshots.add(ExerciseSnapshot(frame, frame * deltaTime, mockBitmap, finalizedPose))

            prePreviousPose = previousPose
            previousPose = finalizedPose
        }

        val fullReport = ValidationReport(
            isValid = overallValid,
            results = emptyList(),
            allIssues = allIssues
        )

        val sequence = ExerciseSnapshotSequence(snapshots)
        val reviewReport = ExerciseReview.review(fullReport, sequence)

        println("=== CAT-COW VALIDATION REPORT ===")
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
        println("==================================")

        assertTrue("Cat-Cow animation score must be >= 95, actual: ${reviewReport.score}", reviewReport.score >= 95)
    }
}
