package com.monkfitness.app

import com.monkfitness.app.animation.*
import com.monkfitness.app.poses.AlternatingForwardLungesPose
import com.monkfitness.app.poses.AlternatingReverseLungesPose
import com.monkfitness.app.poses.AlternatingSideLungesPose
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class LungePosesTest {

    private val def = SkeletonDefinition.DEFAULT_ADULT
    private val finalizer = SkeletonPoseFinalizer(def)
    private val validator = ExerciseValidator()
    private val dt = 0.033f

    private fun buildFrame(pose: PoseBuilder, progress: Float): SkeletonPose {
        val context = PoseContext(
            progress = progress,
            side = Side.LEFT,
            definition = def,
            deltaTime = dt,
            cycleDuration = 4000f,
            playbackSpeed = 1f,
            mirrored = false,
            phase = progress,
            loopIndex = 0
        )
        return finalizer.finalize(pose.build(context))
    }

    private fun distance(v1: Vector3, v2: Vector3): Float {
        val dx = v1.x - v2.x
        val dy = v1.y - v2.y
        val dz = v1.z - v2.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    /**
     * Validates a lunge pose frame-by-frame. Feet are allowed to translate *while
     * stepping* (that is the whole point of a lunge) but only when airborne: a foot
     * is grounded (Y < 12) only at the settled extremes where its horizontal
     * velocity is ~0, so it can never "slide" while loaded.
     */
    private fun validateLunge(pose: PoseBuilder, label: String) {
        val camera = Camera(pose.metadata.camera)
        val totalFrames = 90
        var previous: SkeletonPose? = null
        var prePrevious: SkeletonPose? = null

        var maxArmAsymmetry = 0f
        var maxLegAsymmetry = 0f
        var maxFootSlide = 0f
        var maxJointJump = 0f
        val allReports = mutableListOf<ValidationReport>()

        for (i in 0..totalFrames) {
            val progress = i / totalFrames.toFloat()
            val poseNow = buildFrame(pose, progress)

            val report = validator.validate(
                pose = poseNow,
                definition = def,
                environment = pose.metadata.environment,
                camera = camera,
                width = 1000f,
                height = 1000f,
                previousPose = previous,
                prePreviousPose = prePrevious,
                deltaTime = dt
            )
            allReports.add(report)
            val failed = report.results.filter { !it.isValid }.map { it.ruleId }
            assertTrue(
                "$label frame $i (progress=$progress) failed: $failed | ${report.allIssues.map { it.message }}",
                report.isValid
            )

            val lenArmA = distance(poseNow.getJoint(Joint.SHOULDER_A), poseNow.getJoint(Joint.HAND_A))
            val lenArmP = distance(poseNow.getJoint(Joint.SHOULDER_P), poseNow.getJoint(Joint.HAND_P))
            maxArmAsymmetry = max(maxArmAsymmetry, abs(lenArmA - lenArmP))

            val lenLegF = distance(poseNow.getJoint(Joint.HIP_F), poseNow.getJoint(Joint.ANKLE_F))
            val lenLegB = distance(poseNow.getJoint(Joint.HIP_B), poseNow.getJoint(Joint.ANKLE_B))
            maxLegAsymmetry = max(maxLegAsymmetry, abs(lenLegF - lenLegB))

            if (previous != null) {
                val feet = arrayOf(Joint.ANKLE_F, Joint.ANKLE_B, Joint.HEEL_F, Joint.HEEL_B, Joint.TOE_F, Joint.TOE_B)
                for (f in feet) {
                    val curr = poseNow.getJoint(f)
                    val prev = previous.getJoint(f)
                    if (curr.y < 12f) {
                        val slide = sqrt((curr.x - prev.x).pow(2) + (curr.z - prev.z).pow(2))
                        maxFootSlide = max(maxFootSlide, slide)
                    }
                    maxJointJump = max(maxJointJump, distance(curr, prev))
                }
            }

            prePrevious = previous
            previous = poseNow
        }

        assertTrue("$label max arm asymmetry too high: $maxArmAsymmetry", maxArmAsymmetry < 15f)
        assertTrue("$label max leg asymmetry too high: $maxLegAsymmetry", maxLegAsymmetry < 15f)
        assertTrue("$label max grounded-foot slide too high: $maxFootSlide", maxFootSlide < 0.5f)
        assertTrue("$label max per-frame joint jump too high (teleport): $maxJointJump", maxJointJump < 15f)

        val finalReport = ValidationReport(
            isValid = allReports.all { it.isValid },
            results = emptyList(),
            allIssues = allReports.flatMap { it.allIssues }
        )
        val review = ExerciseReview.review(finalReport)
        println("$label validated. score=${review.score}, armAsym=$maxArmAsymmetry, legAsym=$maxLegAsymmetry, footSlide=$maxFootSlide, jump=$maxJointJump")
        assertTrue("$label exercise review score must be >= 95, actual=${review.score}", review.score >= 95)
    }

    @Test fun forwardLunge() = validateLunge(AlternatingForwardLungesPose(), "forward")
    @Test fun reverseLunge() = validateLunge(AlternatingReverseLungesPose(), "reverse")
    @Test fun sideLunge() = validateLunge(AlternatingSideLungesPose(), "side")
}
