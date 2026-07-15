package com.monkfitness.app

import com.monkfitness.app.animation.*
import com.monkfitness.app.poses.*
import com.monkfitness.app.validation.poses.*
import org.junit.Assert.*
import org.junit.Test

/**
 * UNI-2 / UNI-3 / UNI-6 — validator / ROM cluster.
 *
 * These exercise the new authored-intent and hip-ROM rules directly (crafted poses + contacts)
 * and end-to-end through the engineering reference pipeline (so the global solver rework from
 * UNI-1 is confirmed to still reproduce the references while Middle Split becomes detectable).
 */
class ValidatorRomClusterTest {

    private val def = SkeletonDefinition.DEFAULT_ADULT
    private val env = EnvironmentDefinition()
    private val camera = Camera()

    private fun basePose(): SkeletonPose {
        val pose = SkeletonPose()
        val pelvisY = 210f
        pose.setJoint(Joint.PELVIS, Vector3(0f, pelvisY, 0f))
        pose.setJoint(Joint.HIP_F, Vector3(0f, pelvisY, -def.hipWidth))
        pose.setJoint(Joint.HIP_B, Vector3(0f, pelvisY, def.hipWidth))
        // Straight front leg (hip -> knee -> ankle collinear, correct bone lengths).
        pose.setJoint(Joint.KNEE_F, Vector3(0f, pelvisY - def.thighLength, -def.hipWidth))
        pose.setJoint(Joint.ANKLE_F, Vector3(0f, pelvisY - def.thighLength - def.shinLength, -def.hipWidth))
        pose.setJoint(Joint.KNEE_B, Vector3(0f, pelvisY - def.thighLength, def.hipWidth))
        pose.setJoint(Joint.ANKLE_B, Vector3(0f, pelvisY - def.thighLength - def.shinLength, def.hipWidth))
        pose.setJointRotation(Joint.PELVIS, JointRotation(Vector3(0f, 1f, 0f), 0f))
        return pose
    }

    private fun legContact(straight: Boolean): ContactSpec {
        return ContactSpec(
            endJoint = Joint.ANKLE_F,
            rootJoint = Joint.HIP_F,
            parentRotationJoint = Joint.PELVIS,
            middleJoint = Joint.KNEE_F,
            targetWorld = Vector3(0f, 0f, -def.hipWidth),
            pole = Vector3(0f, 1f, 0f),
            length1 = def.thighLength,
            length2 = def.shinLength,
            constraint = IKConstraint.LegConstraint,
            straight = straight,
            contact = null
        )
    }

    @Test
    fun straightIntentFlaggedWhenLimbBent() {
        val pose = basePose()
        // Bend the front leg while keeping authored straight=true.
        pose.setJoint(Joint.KNEE_F, Vector3(97.0f, 154f, -def.hipWidth))
        pose.setJoint(Joint.ANKLE_F, Vector3(44.7f, 71f, -def.hipWidth))
        pose.contacts.add(legContact(straight = true))

        val report = ExerciseValidator(ValidatorConfig(checkStraightLimbIntent = true))
            .validate(pose, def, env, camera, 1000f, 1000f)
        val rule = report.results.first { it.ruleId == "STRAIGHT_LIMB_INTENT" }
        assertFalse("a bent limb authored straight must be flagged", rule.isValid)
        assertTrue(rule.issues.any { it.joint == Joint.KNEE_F })
    }

    @Test
    fun straightIntentHonoredForStraightLimb() {
        val pose = basePose()
        pose.contacts.add(legContact(straight = true))

        val report = ExerciseValidator(ValidatorConfig(checkStraightLimbIntent = true))
            .validate(pose, def, env, camera, 1000f, 1000f)
        val rule = report.results.first { it.ruleId == "STRAIGHT_LIMB_INTENT" }
        assertTrue("a genuinely straight limb must honour the intent", rule.isValid)
    }

    @Test
    fun straightIntentIgnoredWhenNotAuthored() {
        val pose = basePose()
        pose.setJoint(Joint.KNEE_F, Vector3(97.0f, 154f, -def.hipWidth))
        pose.setJoint(Joint.ANKLE_F, Vector3(44.7f, 71f, -def.hipWidth))
        // straight=false -> the bend is intentional, not a dropped intent.
        pose.contacts.add(legContact(straight = false))

        val report = ExerciseValidator(ValidatorConfig(checkStraightLimbIntent = true))
            .validate(pose, def, env, camera, 1000f, 1000f)
        val rule = report.results.first { it.ruleId == "STRAIGHT_LIMB_INTENT" }
        assertTrue(rule.isValid)
    }

    @Test
    fun hipRomFlaggedWhenOverRange() {
        val pose = basePose()
        // Swing the front femur straight up through the torso (over-range hip).
        pose.setJoint(Joint.KNEE_F, Vector3(0f, 210f + def.thighLength, -def.hipWidth))

        val report = ExerciseValidator(ValidatorConfig(checkHipRom = true))
            .validate(pose, def, env, camera, 1000f, 1000f)
        val rule = report.results.first { it.ruleId == "HIP_ROM_LIMIT" }
        assertFalse("a femur through the torso must exceed hip ROM", rule.isValid)
        assertTrue(rule.issues.any { it.joint == Joint.HIP_F })
    }

    @Test
    fun hipRomPassedForNormalPose() {
        val pose = basePose()
        val report = ExerciseValidator(ValidatorConfig(checkHipRom = true))
            .validate(pose, def, env, camera, 1000f, 1000f)
        val rule = report.results.first { it.ruleId == "HIP_ROM_LIMIT" }
        assertTrue(rule.isValid)
    }

    @Test
    fun pelvisIntentWarnsOnLargeDisplacement() {
        val pose = basePose()
        pose.rootTranslationDelta = 50f
        val report = ExerciseValidator(ValidatorConfig(checkPelvisIntent = true))
            .validate(pose, def, env, camera, 1000f, 1000f)
        val rule = report.results.first { it.ruleId == "PELVIS_INTENT" }
        assertFalse(rule.isValid)
    }

    @Test
    fun contactPreservationWarnsOnLargeMiss() {
        val pose = basePose()
        pose.contacts.add(legContact(straight = true))
        // Hand-author a wildly different end position than the registered anchor.
        pose.setJoint(Joint.ANKLE_F, Vector3(200f, 200f, 200f))
        val report = ExerciseValidator(ValidatorConfig(checkContactPreservation = true))
            .validate(pose, def, env, camera, 1000f, 1000f)
        val rule = report.results.first { it.ruleId == "CONTACT_PRESERVED" }
        assertFalse(rule.isValid)
    }

    // ---- End-to-end: the UNI-1 solver rework must still reproduce references ----

    private fun finalized(pose: BaseValidationPose): SkeletonPose {
        return SkeletonPoseFinalizer(def).finalize(pose.build(PoseContext(progress = 0.5f, side = Side.LEFT, definition = def)))
    }

    @Test
    fun middleSplitDetectableUnderEngineeringValidation() {
        val pose = finalized(MiddleSplitPose())
        val report = ExerciseValidator(ValidatorConfig.ENGINEERING_VALIDATION)
            .validate(pose, def, env, camera, 1000f, 1000f)

        // UNI-2: the straight intent is silently dropped (bent legs/arms) -> now detectable.
        val straight = report.results.first { it.ruleId == "STRAIGHT_LIMB_INTENT" }
        assertFalse("Middle Split's straight limbs must be detected as bent", straight.isValid)
        // The hip ROM itself stays within anatomical range (the split is valid, just not straight).
        val hip = report.results.first { it.ruleId == "HIP_ROM_LIMIT" }
        assertTrue(hip.isValid)
    }

    @Test
    fun goodReferencesStayCleanUnderEngineeringValidation() {
        val validator = ExerciseValidator(ValidatorConfig.ENGINEERING_VALIDATION)
        for (p in listOf(DeadHangPose(), PikeSitPose(), DeepOverheadSquatPose())) {
            val pose = finalized(p)
            val report = validator.validate(pose, def, env, camera, 1000f, 1000f)
            assertTrue(
                "${p.javaClass.simpleName} straight intent must be honoured",
                report.results.first { it.ruleId == "STRAIGHT_LIMB_INTENT" }.isValid
            )
            assertTrue(
                "${p.javaClass.simpleName} hip ROM must be within range",
                report.results.first { it.ruleId == "HIP_ROM_LIMIT" }.isValid
            )
        }
    }
}
