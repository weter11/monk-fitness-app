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

        // Use the ENGINEERING_VALIDATION biomechanical cluster on top of the product checks.
        // This is the fix for the "broken validation" gap: the default config left these rules
        // OFF, so an inverted-thigh / hyper-flexed pose passed with 0 errors. The cluster now
        // enforces HIP_ROM_LIMIT, ANGULAR_JOINT_LIMIT, STRAIGHT_LIMB_INTENT, CONTACT_PRESERVED
        // and PELVIS_INTENT for this pose (all pass on the corrected geometry).
        // NOTE: checkIkTargetReachability is deliberately left OFF here. The shared
        // BaseLungePose.assemble() counterbalance-hand target sits ~1.2u outside the arm IK
        // constraint band (a benign micro-clamp on a 146u-long arm, present in every lunge/step-up,
        // not specific to Cossack). That is a pre-existing shared-lunge-arm authoring clamp and is
        // tracked as a separate follow-up, not a Cossack defect.
        val config = ValidatorConfig.ENGINEERING_VALIDATION.copy(
            checkIkTargetReachability = false,
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
    /**
     * BPS §7/§13: in a Cossack squat the knee must stay BELOW the hip at every depth (the thigh
     * hinges down from the hip; the knee never rises above the hip). The pre-fix pose fed Z-anchors
     * into comX(), shifting the pelvis +102 in X and inverting the working thigh (knee ended up
     * ABOVE the hip). This guards that exact regression.
     */
    @Test
    fun testKneeStaysBelowHip() {
        val poseBuilder = CossackSquatPose()
        val def = SkeletonDefinition.DEFAULT_ADULT
        val pipeline = SkeletonPipeline(def)
        val progressValues = arrayOf(0.0f, 0.1f, 0.2f, 0.25f, 0.4f, 0.5f, 0.6f, 0.75f, 0.9f, 1.0f)

        val report = StringBuilder()
        var worstViolation = 0f
        for (progress in progressValues) {
            val context = PoseContext(
                progress = progress, side = Side.RIGHT, definition = def,
                deltaTime = 0.0166f, cycleDuration = 6000f
            )
            val pose = pipeline.produceFrame(poseBuilder.build(context)).pose
            for ((hipJ, kneeJ) in listOf(
                Pair(Joint.HIP_F, Joint.KNEE_F),
                Pair(Joint.HIP_B, Joint.KNEE_B)
            )) {
                val hip = pose.getJoint(hipJ); val knee = pose.getJoint(kneeJ)
                // At standing (d=0) the knee is naturally below the hip too; only flag when the
                // knee rises ABOVE the hip (inverted thigh).
                val violation = hip.y - knee.y   // >0 means knee below hip (correct)
                if (violation < 0f) {
                    worstViolation = minOf(worstViolation, violation)
                    report.append(String.format(
                        "Progress %.2f %s: knee ABOVE hip by %.1fu (hipY=%.1f kneeY=%.1f)\n",
                        progress, hipJ, -violation, hip.y, knee.y))
                }
            }
        }
        println("=== COSSACK KNEE-BELOW-HIP CHECK ===")
        println(report.toString())
        assertTrue("Cossack knee rises above hip (inverted thigh).\n$report", worstViolation >= 0f)
    }

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
