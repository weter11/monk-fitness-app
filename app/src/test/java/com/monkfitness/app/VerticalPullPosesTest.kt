package com.monkfitness.app

import com.monkfitness.app.animation.*
import com.monkfitness.app.poses.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class VerticalPullPosesTest {

    private val def = SkeletonDefinition.DEFAULT_ADULT
    private val pipeline = SkeletonPipeline(def)

    private data class Case(
        val name: String,
        val pose: BaseVerticalPullPose,
        val bottomReach: Float,
        val topReach: Float,
        val isPull: Boolean
    )

    private fun cases(): List<Case> = listOf(
        Case("standard", StandardPullUpPose(), 140f, 96f, true),
        Case("wide", WideGripPullUpPose(), 140f, 100f, true),
        Case("chin", UnderhandChinUpPose(), 140f, 95f, true),
        Case("neutral", NeutralGripPullUpPose(), 140f, 96f, true),
        Case("hang", HangPose(), 139f, 139f, false),
        Case("scapular", ScapularPullUpPose(), 141f, 131f, true)
    )

    private fun dist(a: Vector3, b: Vector3): Float {
        val dx = a.x - b.x; val dy = a.y - b.y; val dz = a.z - b.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    @Test
    fun testVerticalPullFamilyBiomechanics() {
        val frames = 90
        val dt = 0.033f

        for (c in cases()) {
            val camera = Camera(c.pose.metadata.camera)
            val env = c.pose.metadata.environment
            var previous: SkeletonPose? = null
            var prePrevious: SkeletonPose? = null

            val firstHandA = Vector3()
            val firstHandP = Vector3()
            var maxClamp = 0f
            var armReachAt0 = 0f
            var armReachAt1 = 0f
            var pelvisYAt0 = 0f
            var pelvisYAt1 = 0f
            var maxHandYDev = 0f
            var maxArmAsym = 0f
            var maxLegAsym = 0f

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

                val raw = c.pose.build(context)
                val pose = pipeline.produceFrame(raw).pose

                val report = ExerciseValidator().validate(
                    pose = pose,
                    definition = def,
                    environment = env,
                    camera = camera,
                    width = 1000f,
                    height = 1000f,
                    previousPose = previous,
                    prePreviousPose = prePrevious,
                    deltaTime = dt
                )

                assertTrue(
                    "${c.name} frame $i failed: ${report.allIssues.filter { it.severity == ValidationSeverity.ERROR }.map { it.ruleId }}",
                    report.isValid
                )


                val handA = pose.getJoint(Joint.HAND_A)
                val handP = pose.getJoint(Joint.HAND_P)
                val shA = pose.getJoint(Joint.SHOULDER_A)
                val shP = pose.getJoint(Joint.SHOULDER_P)
                val hipF = pose.getJoint(Joint.HIP_F)
                val hipB = pose.getJoint(Joint.HIP_B)
                val ankleF = pose.getJoint(Joint.ANKLE_F)
                val ankleB = pose.getJoint(Joint.ANKLE_B)

                // Hands must stay glued to the bar (Y = 500) across the whole rep.
                maxHandYDev = max(maxHandYDev, max(abs(handA.y - 500f), abs(handP.y - 500f)))

                // No IK clamp => the fixed hand target was reachable (no detach/stretch).
                maxClamp = max(maxClamp, pose.maxIkClampAmount)

                // Symmetry of limbs (engine clamps keep bones constant; this catches flips).
                maxArmAsym = max(maxArmAsym, abs(dist(shA, handA) - dist(shP, handP)))
                maxLegAsym = max(maxLegAsym, abs(dist(hipF, ankleF) - dist(hipB, ankleB)))

                if (i == 0) {
                    firstHandA.set(handA); firstHandP.set(handP)
                    armReachAt0 = dist(shA, handA)
                    pelvisYAt0 = pose.getJoint(Joint.PELVIS).y
                }
                if (i == frames) {
                    armReachAt1 = dist(shA, handA)
                    pelvisYAt1 = pose.getJoint(Joint.PELVIS).y
                }
                if (i > 0) {
                    // Hands must not slide horizontally between frames.
                    val slideA = sqrt((handA.x - firstHandA.x).pow(2) + (handA.z - firstHandA.z).pow(2))
                    val slideP = sqrt((handP.x - firstHandP.x).pow(2) + (handP.z - firstHandP.z).pow(2))
                    assertTrue("${c.name} hand slid vs frame 0: A=$slideA P=$slideP", slideA < 0.5f && slideP < 0.5f)
                }

                prePrevious = previous
                previous = pose
            }

            // Hands are on the bar.
            assertTrue("${c.name} hands drift off the bar (maxDev=$maxHandYDev)", maxHandYDev < 1.0f)
            // No IK unreachable / clamp.
            assertTrue("${c.name} IK clampAmount $maxClamp (hands detached from bar)", maxClamp < 0.1f)
            // Limb symmetry.
            assertTrue("${c.name} arm asymmetry $maxArmAsym", maxArmAsym < 15f)
            assertTrue("${c.name} leg asymmetry $maxLegAsym", maxLegAsym < 15f)

            if (c.isPull) {
                // Arm reach shortens from bottom to top (the pull closes the joint).
                assertTrue("${c.name} arm reach at bottom should be ~bottomReach, got $armReachAt0", abs(armReachAt0 - c.bottomReach) < 3f)
                assertTrue("${c.name} arm reach at top should be ~topReach, got $armReachAt1", abs(armReachAt1 - c.topReach) < 3f)
                // Scapular-first: the earliest part of the rep barely shortens the arm
                // (arms stay ~straight; the body rises via the scapulae first).
                val firstStage = abs(armReachAt0 - armReachAt1) * 0.4f
                // measured at i where progress=0.2
                val p02 = c.pose.build(PoseContext(0.2f, Side.LEFT, def)).let { pipeline.produceFrame(it).pose }
                val reach02 = dist(p02.getJoint(Joint.SHOULDER_A), p02.getJoint(Joint.HAND_A))
                assertTrue("${c.name} scapular-first violated: early reach change ${abs(armReachAt0 - reach02)} > firstStage $firstStage", abs(armReachAt0 - reach02) < firstStage)
                // The body rises across the rep.
                assertTrue("${c.name} pelvis did not rise (${pelvisYAt0} -> ${pelvisYAt1})", pelvisYAt1 > pelvisYAt0 + 5f)
            } else {
                // Hang: arms stay extended, body essentially static (only breathing).
                assertTrue("${c.name} hang arms should stay extended (~139), got $armReachAt0", abs(armReachAt0 - c.bottomReach) < 3f)
                assertTrue("${c.name} hang arms should stay extended at top, got $armReachAt1", abs(armReachAt1 - c.topReach) < 3f)
            }

            println("${c.name}: OK  handYDev=$maxHandYDev clamp=$maxClamp armAsym=$maxArmAsym legAsym=$maxLegAsym")
        }
    }
}
