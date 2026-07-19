package com.monkfitness.app

import com.monkfitness.app.animation.*
import com.monkfitness.app.poses.JumpingJacksPose
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class JumpingJacksPoseTest {

    private val def = SkeletonDefinition.DEFAULT_ADULT
    private val pose = JumpingJacksPose()
    private val pipeline = SkeletonPipeline(def)
    private val validator = ExerciseValidator()
    private val camera = Camera(pose.metadata.camera)

    private fun buildFrame(progress: Float): SkeletonPose {
        val context = PoseContext(
            progress = progress,
            side = Side.LEFT,
            definition = def,
            deltaTime = 0.033f,
            cycleDuration = 2000f,
            playbackSpeed = 1f,
            mirrored = false,
            phase = progress,
            loopIndex = 0
        )
        return pipeline.produceFrame(pose.build(context)).pose
    }

    @Test
    fun testJumpingJacksBuildsCorrectly() {
        assertNotNull(pose.metadata)
        assertEquals(MotionCurve.LINEAR, pose.metadata.motionCurve)

        val closed = buildFrame(0f)
        val open = buildFrame(0.5f)
        assertNotNull(closed)
        assertNotNull(open)
    }

    @Test
    fun testFeetSpreadWiderWhenOpen() {
        // progress 0 == closed (narrow stance); progress 0.5 == fully open (wide star).
        val closed = buildFrame(0f)
        val open = buildFrame(0.5f)

        val closedSpread = abs(closed.getJoint(Joint.ANKLE_F).z) + abs(closed.getJoint(Joint.ANKLE_B).z)
        val openSpread = abs(open.getJoint(Joint.ANKLE_F).z) + abs(open.getJoint(Joint.ANKLE_B).z)
        assertTrue("Feet should spread wider in the open phase: closed=$closedSpread, open=$openSpread", openSpread > closedSpread + 50f)

        // Feet stay on the ground (planted) across the whole rep.
        for (progress in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            val f = buildFrame(progress)
            assertTrue("Ankle F must stay near the ground at progress=$progress", f.getJoint(Joint.ANKLE_F).y < 20f)
            assertTrue("Ankle B must stay near the ground at progress=$progress", f.getJoint(Joint.ANKLE_B).y < 20f)
        }
    }

    @Test
    fun testArmsRaiseOverheadWhenOpen() {
        // progress 0 == arms down at sides; progress 0.5 == arms overhead.
        val closed = buildFrame(0f)
        val open = buildFrame(0.5f)

        val closedHandY = closed.getJoint(Joint.HAND_A).y
        val openHandY = open.getJoint(Joint.HAND_A).y
        assertTrue("Hands should raise overhead in the open phase: closed=$closedHandY, open=$openHandY", openHandY > closedHandY + 100f)
        assertTrue("Open-phase hands should clear the head", openHandY > open.getJoint(Joint.HEAD_POS).y)
    }

    @Test
    fun testJumpingJacksPoseMeetsBiomechanicalRequirements() {
        val totalFrames = 90
        val dt = 0.033f

        var previousPose: SkeletonPose? = null
        var prePreviousPose: SkeletonPose? = null

        val allReports = mutableListOf<ValidationReport>()

        var maxArmAsymmetry = 0f
        var maxLegAsymmetry = 0f

        for (i in 0..totalFrames) {
            val progress = i / totalFrames.toFloat()
            val frame = buildFrame(progress)

            val report = validator.validate(
                pose = frame,
                definition = def,
                environment = pose.metadata.environment,
                camera = camera,
                width = 1000f,
                height = 1000f,
                previousPose = previousPose,
                prePreviousPose = prePreviousPose,
                deltaTime = dt
            )
            allReports.add(report)

            val failedRules = report.results.filter { !it.isValid }.map { it.ruleId }
            assertTrue(
                "Frame $i (progress=$progress) failed validation rules: $failedRules. Message: ${report.allIssues.map { it.message }}",
                report.isValid
            )

            val shA = frame.getJoint(Joint.SHOULDER_A)
            val handA = frame.getJoint(Joint.HAND_A)
            val lenArmA = distance(shA, handA)
            val shP = frame.getJoint(Joint.SHOULDER_P)
            val handP = frame.getJoint(Joint.HAND_P)
            val lenArmP = distance(shP, handP)
            maxArmAsymmetry = max(maxArmAsymmetry, abs(lenArmA - lenArmP))

            val hipF = frame.getJoint(Joint.HIP_F)
            val ankleF = frame.getJoint(Joint.ANKLE_F)
            val lenLegF = distance(hipF, ankleF)
            val hipB = frame.getJoint(Joint.HIP_B)
            val ankleB = frame.getJoint(Joint.ANKLE_B)
            val lenLegB = distance(hipB, ankleB)
            maxLegAsymmetry = max(maxLegAsymmetry, abs(lenLegF - lenLegB))

            prePreviousPose = previousPose
            previousPose = frame
        }

        assertTrue("Max arm asymmetry too high: $maxArmAsymmetry", maxArmAsymmetry < 145f)
        assertTrue("Max leg asymmetry too high: $maxLegAsymmetry", maxLegAsymmetry < 145f)

        assertTrue(
            "Jumping jacks must pass validation across the full rep",
            allReports.all { it.isValid }
        )
    }

    private fun distance(v1: Vector3, v2: Vector3): Float {
        val dx = v1.x - v2.x
        val dy = v1.y - v2.y
        val dz = v1.z - v2.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }
}