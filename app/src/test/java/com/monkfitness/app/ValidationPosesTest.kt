package com.monkfitness.app

import com.monkfitness.app.animation.*
import com.monkfitness.app.validation.ValidationPoseRegistry
import com.monkfitness.app.validation.poses.DeadHangPose
import com.monkfitness.app.validation.poses.DeepOverheadSquatPose
import com.monkfitness.app.validation.poses.MiddleSplitPose
import com.monkfitness.app.validation.poses.PikeSitPose
import org.junit.Assert.*
import org.junit.Test

/**
 * Temporary engineering-validation harness for the four ValidationPose registry entries.
 *
 * Mirrors the production [AirSquatPoseTest] pattern: build each pose via its [PoseBuilder],
 * run it through [SkeletonPoseFinalizer], then validate it with [ExerciseValidator].
 * Every pose must build without biomechanical validation errors using only the public
 * engine APIs (bakeIkLimb / build* helpers / solveIK).
 */
class ValidationPosesTest {

    private val def = SkeletonDefinition.DEFAULT_ADULT

    private fun validatePose(builder: PoseBuilder): List<ValidationIssue> {
        val finalizer = SkeletonPoseFinalizer(def)
        val context = PoseContext(
            progress = 0.5f,
            side = Side.LEFT,
            definition = def,
            deltaTime = 0.0166f,
            cycleDuration = 2500f
        )
        val rawPose = builder.build(context)
        val pose = finalizer.finalize(rawPose)

        val config = ValidatorConfig(
            allowFootGroundPenetration = false,
            isStaticExercise = false,
            checkBilateralSymmetry = true,
            expectedSupportJoints = setOf(
                Joint.ANKLE_F, Joint.ANKLE_B,
                Joint.TOE_F, Joint.TOE_B,
                Joint.HEEL_F, Joint.HEEL_B
            )
        )
        val validator = ExerciseValidator(config)
        val env = builder.metadata.environment
        val camera = Camera(builder.metadata.camera)
        val report = validator.validate(
            pose = pose,
            definition = def,
            environment = env,
            camera = camera,
            width = 1080f,
            height = 1920f
        )
        return report.allIssues.filter { it.severity == ValidationSeverity.ERROR }
    }

    @Test
    fun testMiddleSplitBuildsWithoutErrors() {
        val errors = validatePose(MiddleSplitPose())
        assertTrue("MiddleSplitPose has ${errors.size} validation errors: $errors", errors.isEmpty())
    }

    @Test
    fun testPikeSitBuildsWithoutErrors() {
        val errors = validatePose(PikeSitPose())
        assertTrue("PikeSitPose has ${errors.size} validation errors: $errors", errors.isEmpty())
    }

    @Test
    fun testDeepOverheadSquatBuildsWithoutErrors() {
        val errors = validatePose(DeepOverheadSquatPose())
        assertTrue("DeepOverheadSquatPose has ${errors.size} validation errors: $errors", errors.isEmpty())
    }

    @Test
    fun testDeadHangBuildsWithoutErrors() {
        val errors = validatePose(DeadHangPose())
        assertTrue("DeadHangPose has ${errors.size} validation errors: $errors", errors.isEmpty())
    }

    @Test
    fun testAllRegistryPosesBuildWithoutErrors() {
        for (vp in ValidationPoseRegistry.poses) {
            val errors = validatePose(vp.builder)
            assertTrue("${vp.id} has ${errors.size} validation errors: $errors", errors.isEmpty())
        }
    }
}
