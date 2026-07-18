package com.monkfitness.app

import com.monkfitness.app.animation.*
import com.monkfitness.app.poses.LatStretchPose
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class LatStretchPoseTest {

    private val def = SkeletonDefinition.DEFAULT_ADULT
    private val stretchPose = LatStretchPose()
    private val pipeline = SkeletonPipeline(def)
    private val validator = ExerciseValidator()
    private val camera = Camera(stretchPose.metadata.camera)

    @Test
    fun testLatStretchPoseMeetsAllBiomechanicalRequirements() {
        val totalFrames = 90
        val dt = 0.033f

        var previousPose: SkeletonPose? = null
        var prePreviousPose: SkeletonPose? = null

        val allReports = mutableListOf<ValidationReport>()

        var maxArmAsymmetry = 0f
        var maxLegAsymmetry = 0f
        var maxFootSlide = 0f

        for (i in 0..totalFrames) {
            val progress = i / totalFrames.toFloat()

            val context = PoseContext(
                progress = progress,
                side = Side.LEFT,
                definition = def,
                deltaTime = dt,
                cycleDuration = 3000f,
                playbackSpeed = 1f,
                mirrored = false,
                phase = progress,
                loopIndex = 0
            )

            val rawPose = stretchPose.build(context)
            val pose = pipeline.produceFrame(rawPose).pose

            // 1. Validation Report
            val report = validator.validate(
                pose = pose,
                definition = def,
                environment = stretchPose.metadata.environment,
                camera = camera,
                width = 1000f,
                height = 1000f,
                previousPose = previousPose,
                prePreviousPose = prePreviousPose,
                deltaTime = dt
            )

            allReports.add(report)

            // Assert that there are no critical biomechanical errors on each frame
            val failedRules = report.results.filter { !it.isValid }.map { it.ruleId }
            assertTrue(
                "Frame $i (progress=$progress) failed validation rules: $failedRules. Message: ${report.allIssues.map { it.message }}",
                report.isValid
            )

            // 2. Symmetry Analysis
            val shA = pose.getJoint(Joint.SHOULDER_A)
            val handA = pose.getJoint(Joint.HAND_A)
            val lenArmA = distance(shA, handA)

            val shP = pose.getJoint(Joint.SHOULDER_P)
            val handP = pose.getJoint(Joint.HAND_P)
            val lenArmP = distance(shP, handP)

            val armDiff = abs(lenArmA - lenArmP)
            if (armDiff > maxArmAsymmetry) {
                maxArmAsymmetry = armDiff
            }

            val hipF = pose.getJoint(Joint.HIP_F)
            val ankleF = pose.getJoint(Joint.ANKLE_F)
            val lenLegF = distance(hipF, ankleF)

            val hipB = pose.getJoint(Joint.HIP_B)
            val ankleB = pose.getJoint(Joint.ANKLE_B)
            val lenLegB = distance(hipB, ankleB)

            val legDiff = abs(lenLegF - lenLegB)
            if (legDiff > maxLegAsymmetry) {
                maxLegAsymmetry = legDiff
            }

            // 3. Foot Sliding Analysis (against consecutive frames)
            if (previousPose != null) {
                val feet = arrayOf(Joint.ANKLE_F, Joint.ANKLE_B, Joint.HEEL_F, Joint.HEEL_B, Joint.TOE_F, Joint.TOE_B)
                for (f in feet) {
                    val prevF = previousPose.getJoint(f)
                    val currF = pose.getJoint(f)
                    // If near the ground
                    if (currF.y < 12f) {
                        val dx = currF.x - prevF.x
                        val dz = currF.z - prevF.z
                        val slide = sqrt(dx * dx + dz * dz)
                        if (slide > maxFootSlide) {
                            maxFootSlide = slide
                        }
                    }
                }
            }

            prePreviousPose = previousPose
            previousPose = pose
        }

        // Assert Symmetry limits (asymmetry is expected to be small because movement alternates perfectly)
        assertTrue("Max arm asymmetry too high: $maxArmAsymmetry", maxArmAsymmetry < 15f)
        assertTrue("Max leg asymmetry too high: $maxLegAsymmetry", maxLegAsymmetry < 15f)

        // Assert Foot sliding limits (< 0.5 units threshold)
        assertTrue("Max foot slide too high: $maxFootSlide", maxFootSlide < 0.5f)

        // Compile combined report score
        val finalReport = ValidationReport(
            isValid = allReports.all { it.isValid },
            results = emptyList(),
            allIssues = allReports.flatMap { it.allIssues }
        )

    }

    private fun distance(v1: Vector3, v2: Vector3): Float {
        val dx = v1.x - v2.x
        val dy = v1.y - v2.y
        val dz = v1.z - v2.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }
}
