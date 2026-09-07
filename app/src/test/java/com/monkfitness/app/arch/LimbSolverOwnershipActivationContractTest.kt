package com.monkfitness.app.arch

import com.monkfitness.app.animation.IK_STAGE_ACTIVE
import com.monkfitness.app.animation.IKConstraint
import com.monkfitness.app.animation.IkStage
import com.monkfitness.app.animation.Joint
import com.monkfitness.app.animation.JointRotation
import com.monkfitness.app.animation.PoseBuilder
import com.monkfitness.app.animation.PoseContext
import com.monkfitness.app.animation.Side
import com.monkfitness.app.animation.SkeletonDefinition
import com.monkfitness.app.animation.SkeletonFactory
import com.monkfitness.app.animation.SkeletonMath
import com.monkfitness.app.animation.SkeletonNodes
import com.monkfitness.app.animation.SkeletonPose
import com.monkfitness.app.animation.Vector3
import com.monkfitness.app.animation.WorldTarget
import com.monkfitness.app.animation.bakeIkLimb
import com.monkfitness.app.poses.CatCowPose
import com.monkfitness.app.poses.DeadBugPose
import com.monkfitness.app.poses.LatStretchPose
import com.monkfitness.app.poses.LegRaisePose
import com.monkfitness.app.poses.SupermanPose
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * P12 WP-A — the activation contract, RED on the pre-P12 baseline.
 *
 * Each test states a property that **FUTURE R5 ACTIVATION STATE** (plan §12.0 state 3) requires
 * and the current code does NOT provide. They fail because the architectural state is absent:
 *
 *  A. [authoringRealizationGatedWhenStageActive] — with the engine stage active, the authoring
 *     bake must still REGISTER limb intent but must NOT perform a second runtime limb
 *     realization (no stamp folds, no node writes from the bake). Today the bake realizes
 *     unconditionally: flag-ON double-solves. (§12.7a)
 *  B. [bakeRealizationCountsIntoTheSolverWindowCounter] — the strengthened single-active-solver
 *     counter must be incremented at BOTH Phase-1 realization sites (bake branch + stage) so a
 *     double solve is observable as `count != 1` even when the outputs coincide. Today only
 *     `IkStage` increments, so the authoring solver is invisible to the instrument. (§12.7b)
 *  C. [losslessStraightConstraintDecode] / [losslessBoneLengthDecode] — `IkStage` must realize a
 *     limb EXACTLY as the authoring bake declared it (per-bake constraint + bone lengths), not
 *     by recovering `definition.*IKConstraint` and lengths through the joint-name (`isArm`)
 *     heuristic. Today the decode diverges whenever the authored context differs from the
 *     definition defaults — parity that depends on coincidence. (§12.3 B-3, §12.4)
 *  D. [bypassFamilyLimbsAreRegisteredAsIntent] — every runtime limb must reach the activated
 *     stage through `limbTargets`. The direct-`solveIK` bypass family registers nothing, so its
 *     limbs exist in neither registered implementation. (§12.6 B-2)
 *  E. [hipFlexorFamilyDoesNotConsumeSolveResultsForAuthoring] — authoring must not derive
 *     declared intent from the Active Limb Solver's result (hip-flexor chain reads the solved
 *     knee world position inside build). (§12.5 B-1)
 *  F. [noUnauthorizedDirectSolveInProductionPoses] — static ownership audit: `solveIK(` may
 *     exist only inside the registered implementations (solver math, the authoring bakes, the
 *     engine stage) and the sanctioned Phase-2 Contact Re-Solve. Production pose files calling
 *     it directly is the bypass family this phase eliminates. (§12.6/§16 sweep)
 *
 * RED evidence is recorded per test in the P12 PR; every property turns green in its work
 * package (WP-B/D/E/G) and stays green through the final flag flip (WP-I).
 */
class LimbSolverOwnershipActivationContractTest {

    private val def = SkeletonDefinition.DEFAULT_ADULT
    private val originalStage = IK_STAGE_ACTIVE

    @After
    fun restoreFlag() {
        IK_STAGE_ACTIVE = originalStage
    }

    // ------------------------------------------------------------- A — realization gate (§12.7a)

    @Test
    fun authoringRealizationGatedWhenStageActive() {
        // An unreachable straight target: the clamped solve produces a NON-ZERO Clamp Stamp.
        // Under state 3 the bake (authoring) only registers the WorldTarget; the Stamp must be
        // produced by the realized path, so immediately after the bake — stage active — the
        // carrier may NOT carry a realization reading yet.
        val pose = SkeletonPose()
        val nodes = SkeletonFactory.createStandardSkeleton()
        nodes.roots.forEach { it.updateWorldTransforms(Vector3(), JointRotation()) }
        IK_STAGE_ACTIVE = true
        bakeIkLimb(
            Vector3(0f, 50f, 0f), Vector3(250f, 50f, 0f), def.thighLength, def.shinLength,
            Vector3(1f, 0f, 0f), def.legIKConstraint, JointRotation(),
            nodes.kneeF, nodes.ankleF, SkeletonMath.IKResult(), pose,
            straight = true
        )
        assertEquals(
            "registration effect (Limb Target) must still run while the stage is active " +
                "(§12.5 acceptance: the bake keeps its registration/stamp effects)",
            1, pose.limbTargets.size
        )
        assertEquals(
            "with IK_STAGE_ACTIVE=true the authoring bake must NOT perform the limb " +
                "realization (no realization stamp folds) — today it double-solves with the " +
                "stage (state 2); state 3 gates the node-realization effect at the bake " +
                "(plan §12.7a)",
            0f, pose.maxIkClampAmount, 0f
        )
    }

    // ---------------------------------------------------- B — strengthened counter site (§12.7b)

    @Test
    fun bakeRealizationCountsIntoTheSolverWindowCounter() {
        // While the stage is DISABLED the bake IS the Active Limb Solver, so its realization
        // must leave execution evidence on the same carrier counter the pipeline's R5 window
        // check reads — otherwise a bake+stage double solve is invisible to the instrument even
        // though both implementations wrote geometry this frame (output equality cannot prove
        // it; only per-implementation execution evidence can — §12.7 enforcement honesty).
        val pose = SkeletonPose()
        val nodes = SkeletonFactory.createStandardSkeleton()
        nodes.roots.forEach { it.updateWorldTransforms(Vector3(), JointRotation()) }
        IK_STAGE_ACTIVE = false
        bakeIkLimb(
            Vector3(0f, 50f, 0f), Vector3(60f, 50f, 0f), def.thighLength, def.shinLength,
            Vector3(1f, 0f, 0f), def.legIKConstraint, JointRotation(),
            nodes.kneeF, nodes.ankleF, SkeletonMath.IKResult(), pose
        )
        assertTrue(
            "the bake realization branch must increment the solver-window counter (§12.7b); " +
                "today only IkStage increments, so the check can never observe the authoring " +
                "solver at all",
            pose.limbSolverExecutions > 0
        )
    }

    // ----------------------------------------------------------- C — lossless decode (B-3)

    private fun runStage(pose: SkeletonPose, definition: SkeletonDefinition) {
        // Drive the gate directly: the stage reads the flag in `apply`; the strengthened
        // per-frame pipeline check belongs to the WP-G enforcement tests, not this contract.
        val original = IK_STAGE_ACTIVE
        try {
            IK_STAGE_ACTIVE = true
            IkStage.apply(pose, definition)
        } finally {
            IK_STAGE_ACTIVE = original
        }
    }

    /** Flatten a tree into the carrier (world transforms) so the joint map reflects the solve. */
    private fun snapshot(nodes: SkeletonNodes, into: SkeletonPose): SkeletonPose =
        SkeletonPose.fromHierarchy(nodes.roots, into)

    private fun hipTree(rootPos: Vector3): SkeletonNodes {
        val nodes = SkeletonFactory.createStandardSkeleton()
        nodes.hipF.localPosition.set(rootPos.x, rootPos.y, rootPos.z)
        nodes.kneeF.localPosition.set(0f, 0f, 0f)
        nodes.ankleF.localPosition.set(0f, 0f, 0f)
        nodes.roots.forEach { it.updateWorldTransforms(Vector3(), JointRotation()) }
        return nodes
    }

    @Test
    fun losslessStraightConstraintDecode() {
        // Authored context: a straight reference limb at FULL reach under the validation
        // family's opted-in FULL-EXTENSION constraint (MiddleSplit/DeadHang/PikeSit pattern).
        // The bake realizes it perfectly straight (extended cap = 1.0·(L1+L2)); an
        // `isArm`-heuristic decode recovers `definition.legIKConstraint` (0.98 cap) and pulls
        // the limb ~2% short. Lossless activation means the stage must reproduce the DECLARED
        // solve exactly — the Limb Target has to carry the constraint (plan §12.4).
        val root = Vector3(0f, 50f, 0f)
        val reach = def.thighLength + def.shinLength
        val fullTarget = Vector3(root.x + reach, root.y, root.z)
        val authored = def.legIKConstraint.fullyExtended()

        val expected = SkeletonPose()
        val eNodes = hipTree(root)
        bakeIkLimb(
            eNodes.hipF.worldPosition, fullTarget, def.thighLength, def.shinLength,
            Vector3(1f, 0f, 0f), authored, JointRotation(),
            eNodes.kneeF, eNodes.ankleF, SkeletonMath.IKResult(), expected,
            straight = true
        )
        snapshot(eNodes, expected)

        val actual = SkeletonPose()
        val aNodes = hipTree(root)
        actual.limbTargets.add(
            WorldTarget(
                Joint.ANKLE_F, fullTarget, Vector3(1f, 0f, 0f), straight = true,
                length1 = def.thighLength, length2 = def.shinLength, constraint = authored
            )
        )
        actual.roots = aNodes.roots
        runStage(actual, def)
        snapshot(aNodes, actual)

        // The decode is lossless only because the declared constraint is honored: an
        // undeclared Limb Target must fail fast under the active stage, NEVER silently
        // recover definition defaults (that recovery was the B-3 heuristic).
        val undeclared = SkeletonPose()
        val uNodes = hipTree(root)
        undeclared.limbTargets.add(
            WorldTarget(Joint.ANKLE_F, fullTarget, Vector3(1f, 0f, 0f), straight = true)
        )
        undeclared.roots = uNodes.roots
        val thrown = runCatching { runStage(undeclared, def) }.exceptionOrNull()
        assertTrue(
            "undeclared realization context must fail fast (no silent definition recovery)",
            thrown is IllegalStateException && thrown.message!!.contains("R5 violation")
        )

        val dev = maxOf(
            abs(actual.getJoint(Joint.KNEE_F).x - expected.getJoint(Joint.KNEE_F).x),
            abs(actual.getJoint(Joint.KNEE_F).y - expected.getJoint(Joint.KNEE_F).y),
            abs(actual.getJoint(Joint.ANKLE_F).x - expected.getJoint(Joint.ANKLE_F).x),
            abs(actual.getJoint(Joint.ANKLE_F).y - expected.getJoint(Joint.ANKLE_F).y)
        )
        // Anti-vacuity: the expected decode must itself have placed the limb (non-zero reach),
        // and the two decodes must actually be compared against a declared straight solve.
        val expectedReach = abs(expected.getJoint(Joint.ANKLE_F).x - root.x)
        assertTrue(
            "fixture guard: authored straight solve must realize the full reach",
            expectedReach > reach * 0.9f
        )
        assertEquals(
            "activated stage must decode the DECLARED constraint losslessly; the joint-name " +
                "heuristic + definition recovery diverges from the authored solve whenever the " +
                "authored context differs from the definition defaults (B-3: parity-by-" +
                "coincidence is not an activation criterion)",
            0f, dev, 1e-5f
        )
    }

    @Test
    fun losslessBoneLengthDecode() {
        // Authored context: a limb baked with explicit bone lengths (2/1) that do NOT match the
        // definition the stage receives (112/98 legs on DEFAULT_ADULT). The stage must place the
        // chain from the DECLARED lengths carried on the Limb Target, not by re-deriving them
        // from the definition through the heuristic.
        val root = Vector3(0f, 50f, 0f)
        val target = Vector3(38f, 20f, 0f)

        val nodes = SkeletonFactory.createStandardSkeleton()
        nodes.hipF.localPosition.set(0f, 50f, 0f)
        nodes.roots.forEach { it.updateWorldTransforms(Vector3(), JointRotation()) }

        val expected = SkeletonPose()
        bakeIkLimb(
            root, target, 2f, 1f, Vector3(1f, 0f, 0f), IKConstraint.LegConstraint,
            JointRotation(), nodes.kneeF, nodes.ankleF, SkeletonMath.IKResult(), expected
        )
        snapshot(nodes, expected)

        val actual = SkeletonPose()
        actual.limbTargets.add(
            WorldTarget(
                Joint.ANKLE_F, target, Vector3(1f, 0f, 0f),
                length1 = 2f, length2 = 1f, constraint = IKConstraint.LegConstraint
            )
        )
        actual.roots = nodes.roots
        runStage(actual, def)
        snapshot(nodes, actual)

        val dev = maxOf(
            abs(actual.getJoint(Joint.ANKLE_F).x - expected.getJoint(Joint.ANKLE_F).x),
            abs(actual.getJoint(Joint.ANKLE_F).y - expected.getJoint(Joint.ANKLE_F).y)
        )
        assertTrue(
            "fixture guard: the declared tiny-limb solve must NOT be the definition-limb solve " +
                "(otherwise the comparison is vacuous)",
            expected.getJoint(Joint.ANKLE_F).x - root.x < 4f
        )
        assertEquals(
            "lossless recovery: the Limb Target must carry the authoritative bone lengths " +
                "(plan §12.4 WorldTarget extension); the definition-recovering heuristic " +
                "realizes a different limb than the one declared",
            0f, dev, 1e-5f
        )
    }

    // ------------------------------------------------ D — bypass family registration (B-2)

    @Test
    fun bypassFamilyLimbsAreRegisteredAsIntent() {
        // LatStretchPose + DeadBugPose solve four limbs each via direct `solveIK` (the §12.6
        // family). Under the activation contract every runtime limb must reach `IkStage` through
        // the Limb Target carrier, so a built bypass pose must carry its declared limbs — today
        // it carries none, which is exactly why flag-ON leaves those limbs solved in no
        // registered implementation (state-"nothing" for the B-2 family).
        for ((name, builder) in listOf<Pair<String, PoseBuilder>>(
            "LatStretch" to LatStretchPose(),
            "DeadBug" to DeadBugPose()
        )) {
            val pose = builder.build(PoseContext(0.5f, Side.LEFT, def))
            assertTrue(
                "$name: all four runtime limbs must be declared on the limb-target carrier " +
                    "before activation (B-2 migration to the registered bake) — got " +
                    "${pose.limbTargets.size}",
                pose.limbTargets.size >= 4
            )
        }
    }

    @Test
    fun worldBuiltPosesUseAuthoredHierarchyNotPositionReconstruction() {
        // §12.6 hierarchy audit (WP-D): the four world-position-built bypass poses must reach
        // the pipeline through the authored-hierarchy idiom — a built pose carries the
        // SkeletonFactory tree (knee/ankle authored as node LOCALS), and NO production pose
        // file may call the legacy `fromJointPositions` reconstruction (solve-result world
        // positions embedded into initial Settled Geometry — the B-1-shaped breakage).
        val files = listOf(
            "poses/DeadBugPose.kt",
            "poses/LegRaisePose.kt",
            "poses/SupermanPose.kt",
            "poses/CatCowPose.kt"
        )
        val root = productionJavaRoot()
        val offenders = mutableListOf<String>()
        for (rel in files) {
            File(root, rel).readLines().forEachIndexed { i, raw ->
                val line = raw.trim()
                if (line.startsWith("//") || line.startsWith("*") || line.startsWith("/*")) return@forEachIndexed
                if (line.substringBefore("//").contains("fromJointPositions")) {
                    offenders.add("$rel:${i + 1}")
                }
            }
        }
        assertEquals(
            "no migrated pose may use fromJointPositions (the world-position reconstruction " +
                "the bypass family depended on):\n" + offenders.joinToString("\n"),
            emptyList<String>(), offenders
        )
        // Behavioral half: each converted pose's built carrier must have a node hierarchy whose
        // knee nodes carry the SOLVED local offsets (hierarchy exists before the bake; the bake
        // wrote authored nodes), and all four limbs registered as Limb Targets.
        for ((name, builder) in listOf<Pair<String, PoseBuilder>>(
            "DeadBug" to DeadBugPose(),
            "LegRaise" to LegRaisePose(),
            "Superman" to SupermanPose(),
            "CatCow" to CatCowPose()
        )) {
            val pose = builder.build(PoseContext(0.5f, Side.LEFT, def))
            assertTrue("$name: authored hierarchy required", pose.roots.isNotEmpty())
            val kneeF = findNode(pose.roots[0], Joint.KNEE_F)
            assertTrue("$name: KNEE_F node must exist on the authored tree", kneeF != null)
            assertTrue(
                "$name: bake must have written the knee's LOCAL offset (thigh, ~L1 long)",
                abs(kneeF!!.localPosition.mag() - def.thighLength) < 1f
            )
            assertEquals("$name: four limbs declared", 4, pose.limbTargets.size)
            for (t in pose.limbTargets) {
                assertFalse("$name:${t.joint}: declared realization context required", t.length1.isNaN())
                assertTrue("$name:${t.joint}: constraint declared", t.constraint != null)
            }
        }
    }

    private fun findNode(node: com.monkfitness.app.animation.SkeletonNode, joint: Joint): com.monkfitness.app.animation.SkeletonNode? {
        if (node.joint == joint) return node
        for (c in node.children) {
            val f = findNode(c, joint)
            if (f != null) return f
        }
        return null
    }

    // --------------------------------------------- E — hip-flexor authoring consumers (B-1)

    @Test
    fun hipFlexorFamilyDoesNotConsumeSolveResultsForAuthoring() {
        // B-1 blocker: `BaseHipFlexorPose.solveFrontLeg` handed the bake's IKResult to the
        // variants, which derived the arm targets from the SOLVED KNEE world position inside
        // build. Under activation the stage realizes the leg later, so the solved knee is not
        // an authoring input at that point. The sanctioned shape (§12.4b): intent may be
        // composed from a `planLimbPlacement` result (planning solve: no registration, no node
        // writes, no stamps) — NEVER from the return value of a `bakeIkLimb` call.
        val files = listOf(
            "poses/BaseHipFlexorPose.kt",
            "poses/CouchStretchPose.kt",
            "poses/HalfKneelingStretchPose.kt"
        )
        val root = productionJavaRoot()
        val offenders = mutableListOf<String>()
        for (rel in files) {
            File(root, rel).readLines().forEachIndexed { i, raw ->
                val line = raw.trim()
                if (line.startsWith("//") || line.startsWith("*") || line.startsWith("/*")) return@forEachIndexed
                val code = line.substringBefore("//")
                if (Regex("""=\s*bakeIkLimb\s*\(""").containsMatchIn(code)) {
                    offenders.add("$rel:${i + 1}: $line")
                }
            }
        }
        assertEquals(
            "authoring must not consume a registered bake's realization result; the only " +
                "allowed composition source in this family is planLimbPlacement (§12.4b):\n" +
                offenders.joinToString("\n"),
            emptyList<String>(), offenders
        )
    }

    // ------------------------------------------- F — static solveIK ownership audit (§12.6)

    @Test
    fun noUnauthorizedDirectSolveInProductionPoses() {
        // Registered Phase-1 limb-solver implementations + the solver math itself + the
        // sanctioned Phase-2 Contact Re-Solve + the §12.4b planning-solve primitive are the ONLY
        // production files allowed to invoke `solveIK(`. Production POSE files calling it
        // directly are the B-2 bypass family.
        val allowed = setOf(
            "SkeletonMath.kt",        // the math itself
            "BasePose.kt",            // authoring bake (registered implementations) + planLimbPlacement (§12.4b)
            "IkStage.kt",             // engine stage (registered implementation)
            "BaseValidationPose.kt",  // validation-family authoring bake (registered authoring implementation)
            "ConstraintSolver.kt"     // Phase-2 Contact Re-Solve (settlement, not a limb solver)
        )
        val srcDir = File(productionJavaRoot())
        val offenders = mutableListOf<String>()
        srcDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { f ->
            if (f.name in allowed) return@forEach
            f.readLines().forEachIndexed { i, raw ->
                val t = raw.trim()
                if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) return@forEachIndexed
                val line = t.substringBefore("//")
                if (line.contains("solveIK(")) {
                    offenders.add("${f.name}:${i + 1}")
                }
            }
        }
        assertEquals(
            "direct solveIK calls outside the registered implementations are the bypass " +
                "family P12 eliminates (route through bakeIkLimb — §12.6):\n" +
                offenders.joinToString("\n"),
            emptyList<String>(), offenders
        )
    }

    // ---------------------------------------------------- helpers

    private fun productionJavaRoot(): String {
        var dir = File(System.getProperty("user.dir"))
        for (attempt in 0 until 8) {
            val candidate = File(dir, "src/main/java/com/monkfitness/app")
            if (candidate.isDirectory) return candidate.absolutePath
            dir = dir.parentFile ?: break
        }
        error("Could not locate app module root from ${System.getProperty("user.dir")}")
    }
}
