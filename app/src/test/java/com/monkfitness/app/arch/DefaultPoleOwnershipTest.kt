package com.monkfitness.app.arch

import com.monkfitness.app.animation.BasePose
import com.monkfitness.app.animation.HumanSkeletonDefinition
import com.monkfitness.app.animation.IKConstraint
import com.monkfitness.app.animation.IK_STAGE_ACTIVE
import com.monkfitness.app.animation.IkStage
import com.monkfitness.app.animation.Joint
import com.monkfitness.app.animation.JointRotation
import com.monkfitness.app.animation.PoseContext
import com.monkfitness.app.animation.Side
import com.monkfitness.app.animation.SkeletonDefinition
import com.monkfitness.app.animation.SkeletonMath
import com.monkfitness.app.animation.SkeletonNode
import com.monkfitness.app.animation.SkeletonPose
import com.monkfitness.app.animation.Vector3
import com.monkfitness.app.validation.poses.BaseValidationPose
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Default Pole ownership lock-in: when a pose omits the Pole, the Default Pole belongs to the
 * **Active Limb Solver** — not to Pose Authoring and not to any unrelated fallback.
 *
 * The zero-length-pole branch of every limb-solver implementation (`BasePose.bakeIkLimb` member
 * + package-level bake, `BaseValidationPose.bakeIkLimb`, and the engine-side `IkStage.apply`)
 * derives the world pole through `SkeletonMath.deriveDefaultPole` at solve time. These tests
 * prove ownership behaviourally:
 *
 *  1. An undeclared (zero-length) pole authored through each real limb path resolves to a solve
 *     bit-identical to `solveIK(root, target, L1, L2, deriveDefaultPole(root, target))` and
 *     bit-DIFFERENT from a solve driven by an unrelated fixed pole — so neither an authoring-side
 *     constant nor any other pole source can satisfy the test.
 *  2. The engine-side stage (the other implementation of the same frozen responsibility set)
 *     re-bakes the identical declaration to the identical frame (parity lock-in).
 *  3. Anti-vacuity: the declaration genuinely carries the zero-length pole, the solver window
 *     genuinely executes, and the limb genuinely bends with exact bone lengths.
 *
 * Fixture geometry: the bone lengths are the declared realization context of a production pose
 * (every real pose passes `def.thighLength`/`def.shinLength` to the bake, and the engine stage
 * realizes from the DECLARED lengths/constraint on the Limb Target rather than re-deriving
 * them), with the target inside the reach band so the limb is genuinely bent. The solver default
 * pole for this aim is a unit vector in the XY plane; the decoy pole (0,0,1) bends the same
 * chain out of plane, so the parity vs "unrelated fallback" distinction is sharp.
 */
// Fixture limb constants (file-level: the authoring probes are nested classes resolving through
// the file scope, not an outer instance). Lengths are what a production pose declares — a
// fixture with other lengths would not represent production.
private val POLE_FIXTURE_ROOT = Vector3(0f, 220f, 0f)
private val POLE_FIXTURE_TARGET = Vector3(80f, 120f, 0f)
private val POLE_FIXTURE_L1 = HumanSkeletonDefinition().thighLength
private val POLE_FIXTURE_L2 = HumanSkeletonDefinition().shinLength
private val POLE_FIXTURE_DECOY = Vector3(0f, 0f, 1f)

class DefaultPoleOwnershipTest {

    private val def = HumanSkeletonDefinition()
    private val originalFlag = IK_STAGE_ACTIVE

    private val root = POLE_FIXTURE_ROOT
    private val target = POLE_FIXTURE_TARGET
    private val l1 = POLE_FIXTURE_L1
    private val l2 = POLE_FIXTURE_L2
    private val decoyPole = POLE_FIXTURE_DECOY

    init {
        // Geometry sanity for the fixture claim in the class doc: reachable band, bent,
        // non-vertical aim (so deriveDefaultPole and the decoy diverge).
        val d = target.minus(root).mag()
        assertTrue("target must sit inside the reach band (bent, not clamped straight): d=$d",
            d > 60f && d < 0.9 * (l1 + l2))
    }

    @After fun restoreFlag() {
        IK_STAGE_ACTIVE = originalFlag
    }

    /**
     * Runs [block] in the **authoring configuration** (`IK_STAGE_ACTIVE = false`) and restores the
     * previous one. The deployed default is the activated configuration, and the authoring bake is
     * the Active Limb Solver only while the engine stage is disabled, so an assertion about the
     * AUTHORING BAKE has to name its configuration explicitly.
     */
    private fun <T> inAuthoringConfiguration(block: () -> T): T {
        val original = IK_STAGE_ACTIVE
        return try {
            IK_STAGE_ACTIVE = false
            block()
        } finally {
            IK_STAGE_ACTIVE = original
        }
    }

    private class LegFixture(l1: Float, l2: Float, root: Vector3) {
        val pelvis = SkeletonNode(Joint.PELVIS)
        val hip = SkeletonNode(Joint.HIP_F)
        val knee = SkeletonNode(Joint.KNEE_F)
        val ankle = SkeletonNode(Joint.ANKLE_F)

        init {
            pelvis.addChild(hip)
            hip.addChild(knee)
            knee.addChild(ankle)
            hip.localPosition.set(0f, 0f, 0f)
            knee.localPosition.set(l1, 0f, 0f)
            ankle.localPosition.set(l2, 0f, 0f)
            pelvis.localPosition.set(root.x, root.y, root.z)
            pelvis.updateWorldTransforms(Vector3(), JointRotation())
        }
    }

    /** Exercise family: BasePose.bakeIkLimb (member), pole omitted. */
    private class MemberBakeProbe : BasePose() {
        override fun onBuild(context: PoseContext): SkeletonPose {
            val f = LegFixture(POLE_FIXTURE_L1, POLE_FIXTURE_L2, POLE_FIXTURE_ROOT)
            bakeIkLimb(
                f.hip.worldPosition, POLE_FIXTURE_TARGET, POLE_FIXTURE_L1, POLE_FIXTURE_L2,
                Vector3(), // omitted pole
                IKConstraint.LegConstraint,
                f.pelvis.worldRotation, f.knee, f.ankle,
                SkeletonMath.IKResult()
            )
            return SkeletonPose.fromHierarchy(listOf(f.pelvis), jointsBuffer)
        }
    }

    /** Validation family: BaseValidationPose.bakeIkLimb, pole omitted. */
    private class ValidationBakeProbe : BaseValidationPose() {
        override fun buildStatic(definition: SkeletonDefinition): SkeletonPose {
            val f = LegFixture(POLE_FIXTURE_L1, POLE_FIXTURE_L2, POLE_FIXTURE_ROOT)
            bakeIkLimb(
                f.hip.worldPosition, POLE_FIXTURE_TARGET, POLE_FIXTURE_L1, POLE_FIXTURE_L2,
                Vector3(), // omitted pole
                IKConstraint.LegConstraint,
                f.pelvis.worldRotation, f.knee, f.ankle,
                SkeletonMath.IKResult()
            )
            return SkeletonPose.fromHierarchy(listOf(f.pelvis), jointsBuffer)
        }
    }

    private fun defaultPoleReading(): Vector3 =
        SkeletonMath.deriveDefaultPole(root, target, Vector3())

    /**
     * Bit-identical to the solver's own `deriveDefaultPole` solve, bit-different from the
     * decoy-pole solve, bone lengths exact, limb genuinely bent.
     */
    private fun assertSolvedFromSolverDefault(kneeWorld: Vector3, ankleWorld: Vector3, label: String) {
        val dPole = defaultPoleReading()
        val expected = SkeletonMath.IKResult()
        SkeletonMath.solveIK(root, target, l1, l2, dPole, IKConstraint.LegConstraint, expected, null)
        val decoy = SkeletonMath.IKResult()
        SkeletonMath.solveIK(root, target, l1, l2, decoyPole, IKConstraint.LegConstraint, decoy, null)

        // The two pole readings must be raw-bit distinct and must place the knee differently —
        // otherwise the comparison below proves nothing.
        assertTrue("$label: fixture must make default and decoy poles diverge",
            expected.joint.x.toRawBits() != decoy.joint.x.toRawBits() ||
                expected.joint.z.toRawBits() != decoy.joint.z.toRawBits())
        assertEquals("$label: default pole is unit length", 1f, dPole.mag(), 1e-6f)

        assertEquals("$label knee.x", expected.joint.x.toRawBits(), kneeWorld.x.toRawBits())
        assertEquals("$label knee.y", expected.joint.y.toRawBits(), kneeWorld.y.toRawBits())
        assertEquals("$label knee.z", expected.joint.z.toRawBits(), kneeWorld.z.toRawBits())
        assertEquals("$label ankle.x", expected.end.x.toRawBits(), ankleWorld.x.toRawBits())
        assertEquals("$label ankle.y", expected.end.y.toRawBits(), ankleWorld.y.toRawBits())
        assertEquals("$label ankle.z", expected.end.z.toRawBits(), ankleWorld.z.toRawBits())

        // Anti-vacuity on the geometry: the middle genuinely left the root->end axis and the
        // chain kept both bone lengths exact.
        val axis = target.minus(root).normalize()
        val off = kneeWorld.minus(root)
        val along = off.dot(axis)
        val perp = Vector3(off.x - axis.x * along, off.y - axis.y * along, off.z - axis.z * along)
        assertTrue("$label: limb must actually bend", perp.mag() > 1f)
        assertEquals("$label bone length 1 exact", l1, kneeWorld.minus(root).mag(), 1e-3f)
        assertEquals("$label bone length 2 exact", l2, ankleWorld.minus(kneeWorld).mag(), 1e-3f)
    }

    private fun assertOmittedPoleDeclared(pose: SkeletonPose) {
        val declared = pose.limbTargets.single()
        assertEquals("omitted pole must stay declared as zero-length", 0f, declared.pole.mag(), 0f)
        assertEquals("fixture limb is the declared target", Joint.ANKLE_F, declared.joint)
    }

    @Test
    fun exerciseBakeResolvesOmittedPoleThroughSolverDefault() {
        // Asserts what the AUTHORING BAKE realizes, so it names the authoring configuration.
        val pose = inAuthoringConfiguration { MemberBakeProbe().build(PoseContext(0f, Side.LEFT, def)) }
        assertOmittedPoleDeclared(pose)
        assertSolvedFromSolverDefault(pose.getJoint(Joint.KNEE_F), pose.getJoint(Joint.ANKLE_F), "member bake")
    }

    @Test
    fun packageLevelBakeResolvesOmittedPoleThroughSolverDefault() {
        // The package-level bakeIkLimb (BasePose.kt) is the third mirror of the same
        // responsibility, for poses implementing PoseBuilder directly.
        val f = LegFixture(l1, l2, root)
        val buffer = SkeletonPose()
        inAuthoringConfiguration {
            com.monkfitness.app.animation.bakeIkLimb(
            f.hip.worldPosition, target, l1, l2,
            Vector3(), // omitted pole
            IKConstraint.LegConstraint,
            f.pelvis.worldRotation, f.knee, f.ankle,
            SkeletonMath.IKResult(),
            buffer
        )
        }
        val pose = SkeletonPose.fromHierarchy(listOf(f.pelvis), buffer)
        assertOmittedPoleDeclared(pose)
        assertSolvedFromSolverDefault(pose.getJoint(Joint.KNEE_F), pose.getJoint(Joint.ANKLE_F), "package bake")
    }

    @Test
    fun validationBakeResolvesOmittedPoleThroughSolverDefault() {
        // The validation bake realizes only in the authoring configuration.
        val pose = inAuthoringConfiguration { ValidationBakeProbe().build(PoseContext(0f, Side.LEFT, def)) }
        assertOmittedPoleDeclared(pose)
        assertSolvedFromSolverDefault(pose.getJoint(Joint.KNEE_F), pose.getJoint(Joint.ANKLE_F), "validation bake")
    }

    @Test
    fun engineStageRebakesOmittedPoleFromItsOwnSolverDefault() {
        // Author the declaration through the normal path (flag off => stage skipped by gate).
        IK_STAGE_ACTIVE = false
        val authored = MemberBakeProbe().build(PoseContext(0f, Side.LEFT, def))
        assertOmittedPoleDeclared(authored)
        val authoredKnee = Vector3().set(authored.getJoint(Joint.KNEE_F))
        val authoredAnkle = Vector3().set(authored.getJoint(Joint.ANKLE_F))
        // The counter covers BOTH realization sites, so a flag-OFF build carries exactly ONE
        // authoring-window increment; the stage itself has not run yet — the delta assertions
        // below observe its window directly.
        assertEquals("the authoring bake window must be counted exactly once", 1, authored.limbSolverExecutions)

        // Now run the engine-side stage on the SAME declaration with the pole still omitted —
        // the other implementation of the identical frozen responsibility set.
        IK_STAGE_ACTIVE = true
        val beforeKneeLocal = Vector3().set(
            authored.roots[0].children[0].children[0].localPosition
        )
        val expectedProbe = SkeletonMath.IKResult()
        SkeletonMath.solveIK(root, target, l1, l2, defaultPoleReading(), IKConstraint.LegConstraint, expectedProbe, null)
        IkStage.apply(authored, def)
        IK_STAGE_ACTIVE = originalFlag
        SkeletonPose.fromHierarchy(authored.roots, authored)

        assertEquals(
            "IkStage window must have executed exactly once on top of the authoring count " +
                "(anti-vacuity; §12.7b per-window evidence)",
            2, authored.limbSolverExecutions
        )
        // The stage re-solved (its toLocalDirection write is the same offset — bit-stable) ...
        assertEquals(
            "re-bake must be numerically stable", beforeKneeLocal.x.toRawBits(),
            authored.roots[0].children[0].children[0].localPosition.x.toRawBits()
        )
        // ... and the re-baked frame is bit-identical to the authoring solve,
        // both equal to the solver's deriveDefaultPole reading.
        assertSolvedFromSolverDefault(authored.getJoint(Joint.KNEE_F), authored.getJoint(Joint.ANKLE_F), "engine stage")
        assertEquals("stage knee.x", authoredKnee.x.toRawBits(), authored.getJoint(Joint.KNEE_F).x.toRawBits())
        assertEquals("stage knee.y", authoredKnee.y.toRawBits(), authored.getJoint(Joint.KNEE_F).y.toRawBits())
        assertEquals("stage knee.z", authoredKnee.z.toRawBits(), authored.getJoint(Joint.KNEE_F).z.toRawBits())
        assertEquals("stage ankle.x", authoredAnkle.x.toRawBits(), authored.getJoint(Joint.ANKLE_F).x.toRawBits())
        assertEquals("stage ankle.y", authoredAnkle.y.toRawBits(), authored.getJoint(Joint.ANKLE_F).y.toRawBits())
        assertEquals("stage ankle.z", authoredAnkle.z.toRawBits(), authored.getJoint(Joint.ANKLE_F).z.toRawBits())
    }

    @Test
    fun defaultPoleDerivationLivesOnlyInLimbSolverImplementations() {
        // Static half of the ownership rule: every production call site supplying a Default
        // Pole sits in one of the Active Limb Solver implementations (exercise bake member +
        // package-level mirror, validation bake, engine stage). A pole-default source in any
        // other production file is a violation and must fail this contract.
        var dir = File(System.getProperty("user.dir"))
        var moduleRoot: File? = null
        for (attempt in 0 until 8) {
            if (File(dir, "src/main/java/com/monkfitness/app").isDirectory) {
                moduleRoot = dir
                break
            }
            dir = dir.parentFile ?: break
        }
        val root = moduleRoot ?: error("Could not locate app module root from user.dir")
        val srcDir = File(root, "src/main/java")
        val deriveCall = Regex("""\bSkeletonMath\.deriveDefaultPole\(""")
        val sites = srcDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { f -> f.readLines().filter { deriveCall.containsMatchIn(it) }.map { f.name } }
            .groupingBy { it }
            .eachCount()
        assertEquals(
            "Default-Pole derivation sites must match the limb-solver inventory",
            mapOf(
                "BasePose.kt" to 2, // member bakeIkLimb + package-level mirror
                "BaseValidationPose.kt" to 1,
                "IkStage.kt" to 1,
            ),
            sites,
        )
    }
}
