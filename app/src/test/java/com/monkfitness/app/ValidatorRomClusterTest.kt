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

    // ---- UNI-3: individual anatomical hip-ROM limits (independently enforced) ----

    private val limits = SkeletonDefinition.DEFAULT_ADULT.hipRomLimits

    /** Places the front femur (hip -> knee) along a pelvis-local unit direction (straight leg). */
    private fun setFrontFemur(pose: SkeletonPose, dx: Float, dy: Float, dz: Float) {
        val hip = pose.getJoint(Joint.HIP_F)
        val m = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
        pose.setJoint(
            Joint.KNEE_F,
            Vector3(hip.x + dx / m * def.thighLength, hip.y + dy / m * def.thighLength, hip.z + dz / m * def.thighLength)
        )
        // Keep the shin colinear with the femur (straight leg -> no axial twist observed).
        val knee = pose.getJoint(Joint.KNEE_F)
        pose.setJoint(
            Joint.ANKLE_F,
            Vector3(knee.x + dx / m * def.shinLength, knee.y + dy / m * def.shinLength, knee.z + dz / m * def.shinLength)
        )
    }

    private fun hipReport(pose: SkeletonPose) =
        ExerciseValidator(ValidatorConfig(checkHipRom = true))
            .validate(pose, def, env, camera, 1000f, 1000f)
            .results.first { it.ruleId == "HIP_ROM_LIMIT" }

    @Test
    fun hipRomCleanForModeratelyFlexedAndAbductedHip() {
        // A hip that is simultaneously flexed forward and abducted out to the side, both within
        // anatomical range, must stay clean — the two planes must not leak into each other.
        val pose = basePose()
        val flex = Math.toRadians(70.0)   // 70° flexion (< 150 cap)
        val abd = Math.toRadians(50.0)    // 50° abduction (< 95 cap), front leg toward -Z
        val dx = (kotlin.math.sin(flex) * kotlin.math.cos(abd)).toFloat()
        val dy = -(kotlin.math.cos(flex) * kotlin.math.cos(abd)).toFloat()
        val dz = -kotlin.math.sin(abd).toFloat()
        setFrontFemur(pose, dx, dy, dz)
        assertTrue("a moderately flexed + abducted hip is within ROM", hipReport(pose).isValid)
    }

    @Test
    fun hipRomFlaggedWhenOverExtended() {
        val pose = basePose()
        // Femur swung backward (-X) past the (small) extension cap; sin(elevation) = fx.
        val a = Math.toRadians((limits.maxExtensionDegrees + 20f).toDouble())
        setFrontFemur(pose, -kotlin.math.sin(a).toFloat(), -kotlin.math.cos(a).toFloat(), 0f)
        val rule = hipReport(pose)
        assertFalse("over-extended hip must be flagged", rule.isValid)
        assertTrue(rule.issues.any { it.message.contains("extension") && it.joint == Joint.HIP_F })
    }

    @Test
    fun hipRomFlaggedWhenOverAdducted() {
        val pose = basePose()
        // Front hip adducts toward +Z (across the mid-line) past the adduction cap.
        val a = Math.toRadians((limits.maxAdductionDegrees + 20f).toDouble())
        setFrontFemur(pose, 0f, -kotlin.math.cos(a).toFloat(), kotlin.math.sin(a).toFloat())
        val rule = hipReport(pose)
        assertFalse("over-adducted hip must be flagged", rule.isValid)
        assertTrue(rule.issues.any { it.message.contains("adduction") && it.joint == Joint.HIP_F })
    }

    @Test
    fun hipRomReportsEveryViolatedLimitAtOnce() {
        val pose = basePose()
        // Simultaneously over-extend (-X, backward) and over-adduct (+Z, across mid-line): two
        // independent single-plane limits exceeded at once. Both must be reported. Keep the two
        // elevation components small enough that dx²+dz² < 1 (a valid unit direction).
        val ea = Math.toRadians((limits.maxExtensionDegrees + 8f).toDouble())   // ~33° extension
        val aa = Math.toRadians((limits.maxAdductionDegrees + 8f).toDouble())   // ~48° adduction
        val dx = -(kotlin.math.sin(ea)).toFloat()
        val dz = (kotlin.math.sin(aa)).toFloat()
        val dy = -kotlin.math.sqrt((1.0 - dx * dx.toDouble() - dz * dz.toDouble()).coerceAtLeast(0.0)).toFloat()
        setFrontFemur(pose, dx, dy, dz)
        val rule = hipReport(pose)
        assertFalse(rule.isValid)
        assertTrue("extension violation must be reported", rule.issues.any { it.message.contains("extension") })
        assertTrue("adduction violation must be reported", rule.issues.any { it.message.contains("adduction") })
    }

    @Test
    fun hipRomFlaggedWhenOverInternallyRotated() {
        val pose = basePose()
        // Author femoral internal rotation about the femur's long (local X) axis past the cap.
        // Front leg: abductionSign = -1, so a negative raw twist about +X reads as internal rotation.
        val twist = Math.toRadians((limits.maxInternalRotationDegrees + 20f).toDouble()).toFloat()
        pose.setJointRotation(Joint.HIP_F, JointRotation(Vector3(1f, 0f, 0f), -twist))
        val rule = hipReport(pose)
        assertFalse("over-internally-rotated hip must be flagged", rule.isValid)
        assertTrue(rule.issues.any { it.message.contains("internal rotation") && it.joint == Joint.HIP_F })
    }

    @Test
    fun hipRomFlaggedWhenOverExternallyRotated() {
        val pose = basePose()
        // Author femoral external rotation about +X past the (larger) external-rotation cap.
        val twist = Math.toRadians((limits.maxExternalRotationDegrees + 20f).toDouble()).toFloat()
        pose.setJointRotation(Joint.HIP_F, JointRotation(Vector3(1f, 0f, 0f), twist))
        val rule = hipReport(pose)
        assertFalse("over-externally-rotated hip must be flagged", rule.isValid)
        assertTrue(rule.issues.any { it.message.contains("external rotation") && it.joint == Joint.HIP_F })
    }

    @Test
    fun hipRomCleanForPureFlexionRotationSwing() {
        // A hip flexed about Z (pure swing, no twist) must report 0 femoral rotation.
        val pose = basePose()
        setFrontFemur(pose, kotlin.math.sin(Math.toRadians(60.0)).toFloat(), -kotlin.math.cos(Math.toRadians(60.0)).toFloat(), 0f)
        pose.setJointRotation(Joint.HIP_F, JointRotation(Vector3(0f, 0f, 1f), Math.toRadians(60.0).toFloat()))
        val rule = hipReport(pose)
        assertTrue("pure flexion swing must not read as femoral rotation", rule.isValid)
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
        // M2: route through the pipeline (the production path) so the Solver + Finalizer run in
        // fixed order, exactly as pre-M2 (the Finalizer itself no longer calls the Solver). This
        // keeps the end-to-end fixtures faithful to production and byte-identical to the baseline.
        return SkeletonPipeline(def).produceFrame(
            pose, PoseContext(progress = 0.5f, side = Side.LEFT, definition = def)
        ).pose
    }

    @Test
    fun middleSplitSurfacesDroppedStraightIntent() {
        // Middle Split is a DIAGNOSTIC INSTRUMENT, not a development target
        // (docs/VALIDATION.md §2, MIDDLE_SPLIT_DIAGNOSTIC_AUDIT.md). It requests straight limbs at
        // targets inside the proximal bone (hip→foot ≈ 58.9 < L1 112), so solveStraightLimb drops
        // the straight intent and returns a bent limb. The instrument's job is to READ that: the
        // validator must flag the dropped straight intent. It must NOT be retuned to full reach to
        // make the limbs resolve straight (that would tamper with the probe). Hip ROM stays in range.
        val pose = finalized(MiddleSplitPose())
        val report = ExerciseValidator(ValidatorConfig.ENGINEERING_VALIDATION)
            .validate(pose, def, env, camera, 1000f, 1000f)

        val straight = report.results.first { it.ruleId == "STRAIGHT_LIMB_INTENT" }
        assertFalse("Middle Split must surface the dropped straight intent, not hide it", straight.isValid)
        val hip = report.results.first { it.ruleId == "HIP_ROM_LIMIT" }
        assertTrue(hip.isValid)
    }

    @Test
    fun straightIntentStillDetectableOnBrokenReference() {
        // Complementary coverage alongside the Middle Split instrument: a purpose-built fixture
        // authors a `straight=true` limb with an unsatisfiable (too-close) target, which the UNI-9
        // fallback resolves bent. The validator must flag it. This isolates the detection logic
        // from the specific geometry of any real reference pose.
        val pose = finalized(BrokenStraightLimbPose())
        val report = ExerciseValidator(ValidatorConfig.ENGINEERING_VALIDATION)
            .validate(pose, def, env, camera, 1000f, 1000f)

        val straight = report.results.first { it.ruleId == "STRAIGHT_LIMB_INTENT" }
        assertFalse("A straight limb authored inside the proximal bone must be detected as bent", straight.isValid)
        val hip = report.results.first { it.ruleId == "HIP_ROM_LIMIT" }
        assertTrue(hip.isValid)
    }

    /**
     * Purpose-built fixture proving UNI-2 detection works independently of any real reference.
     * Authors a `straight=true` leg whose target sits 30u from the hip — well inside L1 (112) —
     * so [SkeletonMath.solveStraightLimb] falls back to the bent UNI-9 solve and the straight
     * intent is silently dropped, exactly the condition the validator must surface.
     */
    private class BrokenStraightLimbPose : BaseValidationPose() {
        override fun buildStatic(def: SkeletonDefinition): SkeletonPose {
            ensureHierarchy(def)
            pelvis!!.localPosition.set(0f, 200f, 0f)
            pelvis!!.localRotation.set(axisZ, 0f)
            chest!!.localPosition.set(0f, def.torsoLength, 0f)
            chest!!.localRotation.set(axisZ, 0f)
            buildHead(neck!!, head!!, def.neckLength, Vector3(0f, 1f, 0f))
            buildPelvis(pelvis!!, hipF!!, hipB!!, def.hipWidth)
            buildShoulders(shoulderA!!, shoulderP!!, def.shoulderWidth)
            roots!!.forEach { it.updateWorldTransforms(zeroVector, identityRotation) }

            // Unsatisfiable straight target: only 30u from the hip (L1 = 112).
            val targetF = Vector3(0f, 200f, -def.hipWidth - 30f)
            val targetB = Vector3(0f, 200f, def.hipWidth + 30f)
            val legPoleF = Vector3(0f, 1f, -1f)
            val legPoleB = Vector3(0f, 1f, 1f)
            val groundContact = ContactConstraint.ground(0f)
            bakeIkLimb(hipF!!.worldPosition, targetF, def.thighLength, def.shinLength, legPoleF, legStraightConstraint(def), pelvis!!.worldRotation, kneeF!!, ankleF!!, legFBuffer, straight = true, contact = groundContact)
            bakeIkLimb(hipB!!.worldPosition, targetB, def.thighLength, def.shinLength, legPoleB, legStraightConstraint(def), pelvis!!.worldRotation, kneeB!!, ankleB!!, legBBuffer, straight = true, contact = groundContact)
            return finalizePose()
        }
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
