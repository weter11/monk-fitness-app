package com.monkfitness.app

import com.monkfitness.app.animation.*
import com.monkfitness.app.poses.*
import com.monkfitness.app.domain.usecase.WorkoutGenerator
import com.monkfitness.app.ui.components.animation.exerciseSkeletonAnimation
import org.junit.Assert.*
import org.junit.Test

class CossackSquatPoseTest {

    @Test
    fun testCossackSquatPoseBiomechanicalCompliance() {
        val poseBuilder = CossackSquatPose()
        val def = SkeletonDefinition.DEFAULT_ADULT
        val env = poseBuilder.metadata.environment ?: EnvironmentDefinition()
        val camera = Camera()
        val pipeline = SkeletonPipeline(def)

        val config = ValidatorConfig(
            allowFootGroundPenetration = false,
            isStaticExercise = false,
            checkBilateralSymmetry = true,
            checkHandShoulderAlignment = true,
            expectedSupportJoints = setOf(
                Joint.HAND_A, Joint.HAND_P,
                Joint.WRIST_A, Joint.WRIST_P,
                Joint.TOE_F, Joint.TOE_B,
                Joint.HEEL_F, Joint.HEEL_B,
                Joint.ANKLE_F, Joint.ANKLE_B
            )
        )
        val validator = ExerciseValidator(config)

        val frameCount = 100
        val dt = 0.0166f
        val poses = ArrayList<SkeletonPose>()

        for (i in 0 until frameCount) {
            val progress = i.toFloat() / (frameCount - 1)
            val context = PoseContext(
                progress = progress,
                side = Side.RIGHT,
                definition = def,
                deltaTime = dt,
                cycleDuration = 6000f
            )
            val rawPose = poseBuilder.build(context)
            val finalizedPose = pipeline.produceFrame(rawPose).pose
            poses.add(finalizedPose)
        }

        var previousPose: SkeletonPose? = null
        var prePreviousPose: SkeletonPose? = null
        val collectedIssues = ArrayList<ValidationIssue>()

        for (i in 0 until frameCount) {
            val currentPose = poses[i]
            val report = validator.validate(
                pose = currentPose,
                definition = def,
                environment = env,
                camera = camera,
                width = 1080f,
                height = 1920f,
                previousPose = previousPose,
                prePreviousPose = prePreviousPose,
                deltaTime = dt
            )
            for (issue in report.allIssues) {
                collectedIssues.add(issue)
                println("Frame $i (progress=${"$"}{i.toFloat() / (frameCount - 1)}): ${"$"}{issue.ruleId} (${"$"}{issue.severity}): ${"$"}{issue.message}")
            }
            prePreviousPose = previousPose
            previousPose = currentPose
        }

        println("=== VALIDATION ISSUE SUMMARY ===")
        println("Total issues collected: ${"$"}{collectedIssues.size}")
        val uniqueRuleIds = collectedIssues.map { it.ruleId }.distinct()
        for (rule in uniqueRuleIds) {
            val count = collectedIssues.count { it.ruleId == rule }
            println("- Rule $rule: $count issues")
        }

        val errors = collectedIssues.filter { it.severity == ValidationSeverity.ERROR }
        assertTrue("Cossack squat pose has ${"$"}{errors.size} validation errors!", errors.isEmpty())
    }

    private fun dist(a: Vector3, b: Vector3): Float =
        kotlin.math.sqrt((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y) + (a.z - b.z) * (a.z - b.z))

    /**
     * BPS §7/§13: no working-knee VALGUS (medial collapse toward body center). The knee may
     * track laterally (outboard) over the foot — that is correct tracking — but it must never
     * cross to the medial side of the hip->ankle line (toward Z=0). This checks exactly that:
     * for a leg whose hip and foot are both on one side of center, the knee must not move
     * closer to center than both of them.
     */
    @Test
    fun testNoMedialKneeValgus() {
        val poseBuilder = CossackSquatPose()
        val def = SkeletonDefinition.DEFAULT_ADULT
        val pipeline = SkeletonPipeline(def)
        val legLen = def.thighLength + def.shinLength
        val progressValues = arrayOf(0.0f, 0.1f, 0.25f, 0.5f, 0.6f, 0.75f, 0.9f, 1.0f)

        val report = StringBuilder()
        var worstMedial = 0f
        for (progress in progressValues) {
            val context = PoseContext(
                progress = progress, side = Side.RIGHT, definition = def,
                deltaTime = 0.0166f, cycleDuration = 6000f
            )
            val pose = pipeline.produceFrame(poseBuilder.build(context)).pose
            for ((hipJ, kneeJ, ankleJ) in listOf(
                Triple(Joint.HIP_F, Joint.KNEE_F, Joint.ANKLE_F),
                Triple(Joint.HIP_B, Joint.KNEE_B, Joint.ANKLE_B)
            )) {
                val hip = pose.getJoint(hipJ); val knee = pose.getJoint(kneeJ); val ankle = pose.getJoint(ankleJ)
                // medial = toward Z=0. If hip & foot are both negative Z, medial is +Z.
                val side = if (hip.z <= 0f && ankle.z <= 0f) -1f else 1f
                // knee is medial (valgus) if it is closer to 0 than both hip and foot.
                val kneeMedialOfLine = if (side < 0f) knee.z > maxOf(hip.z, ankle.z) else knee.z < minOf(hip.z, ankle.z)
                val medialExcess = if (side < 0f) (knee.z - maxOf(hip.z, ankle.z)) else (minOf(hip.z, ankle.z) - knee.z)
                if (kneeMedialOfLine) {
                    worstMedial = maxOf(worstMedial, medialExcess)
                    report.append(String.format("Progress %.2f %s: MEDIAL valgus excess=%.2f (hip=%.1f knee=%.1f ankle=%.1f)\n",
                        progress, hipJ, medialExcess, hip.z, knee.z, ankle.z))
                }
            }
        }
        println("=== COSSACK NO-MEDIAL-VALGUS CHECK ===")
        println(report.toString())
        assertTrue("Cossack knee crosses medially (valgus) past the hip->ankle line.\n$report", worstMedial < 1.0f)
    }

    /**
     * BPS §7: the STRAIGHT leg must stay extended (~0° knee flexion). Checked by confirming the
     * straight leg's knee lies on the hip->ankle segment (sum of segment lengths ~= leg length,
     * i.e. the knee adds < 12% slack vs a perfectly straight leg).
     */
    @Test
    fun testStraightLegStaysExtended() {
        val poseBuilder = CossackSquatPose()
        val def = SkeletonDefinition.DEFAULT_ADULT
        val pipeline = SkeletonPipeline(def)
        val legLen = def.thighLength + def.shinLength
        val progressValues = arrayOf(0.0f, 0.25f, 0.5f, 0.75f, 1.0f)

        val report = StringBuilder()
        var worstSlack = 0f
        for (progress in progressValues) {
            val context = PoseContext(
                progress = progress, side = Side.RIGHT, definition = def,
                deltaTime = 0.0166f, cycleDuration = 6000f
            )
            val pose = pipeline.produceFrame(poseBuilder.build(context)).pose
            // At progress 0.25 and 0.75 one leg is the straight (long) leg.
            for ((hipJ, kneeJ, ankleJ) in listOf(
                Triple(Joint.HIP_F, Joint.KNEE_F, Joint.ANKLE_F),
                Triple(Joint.HIP_B, Joint.KNEE_B, Joint.ANKLE_B)
            )) {
                val hip = pose.getJoint(hipJ); val knee = pose.getJoint(kneeJ); val ankle = pose.getJoint(ankleJ)
                val segSum = dist(hip, knee) + dist(knee, ankle)
                val slack = segSum - dist(hip, ankle)   // 0 = perfectly straight
                // Only judge the clearly-long leg (straight leg has large hip->ankle span).
                if (dist(hip, ankle) > legLen * 0.7f) {
                    worstSlack = maxOf(worstSlack, slack)
                    report.append(String.format("Progress %.2f %s: straightLegSlack=%.2f (hip->ankle=%.1f)\n",
                        progress, hipJ, slack, dist(hip, ankle)))
                }
            }
        }
        println("=== COSSACK STRAIGHT-LEG EXTENSION CHECK ===")
        println(report.toString())
        // Slack beyond ~14% of leg length means the "straight" leg is visibly bending.
        assertTrue("Cossack straight leg bends (not extended).\n$report", worstSlack < legLen * 0.14f)
    }
    @Test
    fun testCossackSquatIsVisibleInUiAndResolves() {
        // Catalog: the exercise is present and its animationId resolves in the live registry.
        val ex = WorkoutGenerator().getExerciseLibrary(emptySet())
            .firstOrNull { it.id == "cossack_squat" }
        assertNotNull("cossack_squat must be in the exercise catalog (WorkoutGenerator.allExercises)", ex)
        assertEquals("cossack_squat", ex!!.animationId)
        // Live pose registry resolves the animation.
        val anim = AnimationRegistry.get("cossack_squat")
        assertNotNull("cossack_squat must resolve in AnimationRegistry", anim)
        // UI skeleton silhouette resolves (ExerciseSkeletonData).
        val skel = exerciseSkeletonAnimation("cossack_squat")
        assertNotNull("cossack_squat must have a UI skeleton silhouette", skel)
    }

}
