package com.monkfitness.app

import com.monkfitness.app.animation.*
import com.monkfitness.app.poses.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class LungePosesTest {

    private val def = SkeletonDefinition.DEFAULT_ADULT
    private val finalizer = SkeletonPoseFinalizer(def)

    private data class FrameMetrics(
        var maxArmAsymmetry: Float = 0f,
        var maxLegAsymmetry: Float = 0f,
        var maxFootSlide: Float = 0f,
        var supportFootDrift: Float = 0f
    )

    private fun validate(
        pose: PoseBuilder,
        frames: Int,
        isLunge: Boolean,
        isStepUp: Boolean
    ): Pair<ValidationReport, FrameMetrics> {
        val camera = Camera(pose.metadata.camera)
        val validator = ExerciseValidator()
        val env = pose.metadata.environment
        val dt = 1f / 30f

        val allIssues = ArrayList<ValidationIssue>()
        val metrics = FrameMetrics()
        var previousPose: SkeletonPose? = null
        var previousPre: SkeletonPose? = null
        var previousSupportXZ: Vector3? = null

        for (i in 0..frames) {
            val progress = i / frames.toFloat()
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

            val raw = pose.build(context)
            val p = finalizer.finalize(raw)

            val report = validator.validate(
                pose = p,
                definition = def,
                environment = env,
                camera = camera,
                width = 1000f,
                height = 1000f,
                previousPose = previousPose,
                prePreviousPose = previousPre,
                deltaTime = dt
            )
            for (issue in report.allIssues) allIssues.add(issue)

            val failed = report.results.filter { !it.isValid }.map { it.ruleId }
            assertTrue(
                "Frame $i (p=$progress) of ${pose.javaClass.simpleName} failed: $failed :: ${report.allIssues.map { it.message }}",
                report.isValid
            )

            // Limb asymmetry (shoulder->hand, hip->ankle)
            val armA = distance(p.getJoint(Joint.SHOULDER_A), p.getJoint(Joint.HAND_A))
            val armP = distance(p.getJoint(Joint.SHOULDER_P), p.getJoint(Joint.HAND_P))
            metrics.maxArmAsymmetry = max(metrics.maxArmAsymmetry, abs(armA - armP))
            val legF = distance(p.getJoint(Joint.HIP_F), p.getJoint(Joint.ANKLE_F))
            val legB = distance(p.getJoint(Joint.HIP_B), p.getJoint(Joint.ANKLE_B))
            metrics.maxLegAsymmetry = max(metrics.maxLegAsymmetry, abs(legF - legB))

            // Foot sliding: only when a foot is near the floor/step (grounded contact)
            if (previousPose != null) {
                val feet = arrayOf(Joint.ANKLE_F, Joint.ANKLE_B, Joint.HEEL_F, Joint.HEEL_B, Joint.TOE_F, Joint.TOE_B)
                for (f in feet) {
                    val curr = p.getJoint(f)
                    val prev = previousPose!!.getJoint(f)
                    if (curr.y < 12f) {
                        val slide = sqrt((curr.x - prev.x).pow(2) + (curr.z - prev.z).pow(2))
                        metrics.maxFootSlide = max(metrics.maxFootSlide, slide)
                    }
                }
            }

            // Support-foot stability: the anchored foot must not drift
            if (isLunge) {
                val plantIsFront = progress < 0.5f
                val plantAnkle = if (plantIsFront) p.getJoint(Joint.ANKLE_F) else p.getJoint(Joint.ANKLE_B)
                metrics.supportFootDrift = max(metrics.supportFootDrift, abs(plantAnkle.x))
                metrics.supportFootDrift = max(metrics.supportFootDrift, abs(plantAnkle.z - footSepZ(plantIsFront)))
            }
            if (isStepUp) {
                val lead = p.getJoint(Joint.ANKLE_F)
                if (lead.y > 30f) {
                    val xz = Vector3(lead.x, 0f, lead.z)
                    if (previousSupportXZ != null) {
                        metrics.supportFootDrift = max(metrics.supportFootDrift, distance(xz, previousSupportXZ!!))
                    }
                    previousSupportXZ = xz
                }
            }

            previousPre = previousPose
            previousPose = p
        }

        val combined = ValidationReport(
            isValid = allIssues.none { it.severity == ValidationSeverity.ERROR },
            results = emptyList(),
            allIssues = allIssues
        )
        return combined to metrics
    }

    private fun footSepZ(front: Boolean): Float {
        val sep = def.hipWidth * 1.15f
        return if (front) -sep else sep
    }

    private fun distance(a: Vector3, b: Vector3): Float {
        return sqrt((a.x - b.x).pow(2) + (a.y - b.y).pow(2) + (a.z - b.z).pow(2))
    }

    @Test
    fun testForwardLungeBiomechanics() {
        val (report, m) = validate(AlternatingForwardLungesPose(), 180, isLunge = true, isStepUp = false)
        println("[Forward] armAsym=${m.maxArmAsymmetry} legAsym=${m.maxLegAsymmetry} footSlide=${m.maxFootSlide} supportDrift=${m.supportFootDrift}")
        assertTrue("Arm asymmetry ${m.maxArmAsymmetry}", m.maxArmAsymmetry < 15f)
        assertTrue("Leg asymmetry ${m.maxLegAsymmetry}", m.maxLegAsymmetry < 15f)
        assertTrue("Foot slide ${m.maxFootSlide}", m.maxFootSlide < 0.5f)
        assertTrue("Support foot drift ${m.supportFootDrift}", m.supportFootDrift < 0.5f)
        val review = ExerciseReview.review(report)
        println("[Forward] score=${review.score}")
        assertTrue("Score ${review.score}", review.score >= 95)
    }

    @Test
    fun testReverseLungeBiomechanics() {
        val (report, m) = validate(AlternatingReverseLungesPose(), 180, isLunge = true, isStepUp = false)
        println("[Reverse] armAsym=${m.maxArmAsymmetry} legAsym=${m.maxLegAsymmetry} footSlide=${m.maxFootSlide} supportDrift=${m.supportFootDrift}")
        assertTrue("Arm asymmetry ${m.maxArmAsymmetry}", m.maxArmAsymmetry < 15f)
        assertTrue("Leg asymmetry ${m.maxLegAsymmetry}", m.maxLegAsymmetry < 15f)
        assertTrue("Foot slide ${m.maxFootSlide}", m.maxFootSlide < 0.5f)
        assertTrue("Support foot drift ${m.supportFootDrift}", m.supportFootDrift < 0.5f)
        val review = ExerciseReview.review(report)
        println("[Reverse] score=${review.score}")
        assertTrue("Score ${review.score}", review.score >= 95)
    }

    @Test
    fun testSideLungeBiomechanics() {
        val (report, m) = validate(AlternatingSideLungesPose(), 180, isLunge = true, isStepUp = false)
        println("[Side] armAsym=${m.maxArmAsymmetry} legAsym=${m.maxLegAsymmetry} footSlide=${m.maxFootSlide} supportDrift=${m.supportFootDrift}")
        assertTrue("Arm asymmetry ${m.maxArmAsymmetry}", m.maxArmAsymmetry < 15f)
        assertTrue("Leg asymmetry ${m.maxLegAsymmetry}", m.maxLegAsymmetry < 15f)
        assertTrue("Foot slide ${m.maxFootSlide}", m.maxFootSlide < 0.5f)
        assertTrue("Support foot drift ${m.supportFootDrift}", m.supportFootDrift < 0.5f)
        val review = ExerciseReview.review(report)
        println("[Side] score=${review.score}")
        assertTrue("Score ${review.score}", review.score >= 95)
    }

    @Test
    fun testStepUpBiomechanics() {
        val (report, m) = validate(StepUpPose(), 180, isLunge = false, isStepUp = true)
        println("[StepUp] armAsym=${m.maxArmAsymmetry} legAsym=${m.maxLegAsymmetry} footSlide=${m.maxFootSlide} supportDrift=${m.supportFootDrift}")
        assertTrue("Arm asymmetry ${m.maxArmAsymmetry}", m.maxArmAsymmetry < 15f)
        assertTrue("Leg asymmetry ${m.maxLegAsymmetry}", m.maxLegAsymmetry < 15f)
        assertTrue("Foot slide ${m.maxFootSlide}", m.maxFootSlide < 0.5f)
        assertTrue("Support foot drift ${m.supportFootDrift}", m.supportFootDrift < 0.5f)
        val review = ExerciseReview.review(report)
        println("[StepUp] score=${review.score}")
        assertTrue("Score ${review.score}", review.score >= 95)
    }
}
