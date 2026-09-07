package com.monkfitness.app.arch

import com.monkfitness.app.animation.IK_STAGE_ACTIVE
import com.monkfitness.app.animation.PoseContext
import com.monkfitness.app.animation.SkeletonDefinition
import com.monkfitness.app.animation.SkeletonPipeline
import com.monkfitness.app.animation.Side
import com.monkfitness.app.validation.poses.DeepOverheadSquatPose
import com.monkfitness.app.validation.poses.DeadHangPose
import com.monkfitness.app.validation.poses.MiddleSplitPose
import com.monkfitness.app.validation.poses.PikeSitPose
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * P12 WP-F — B-4 validation-path reconciliation: ownership + probe re-certification contract.
 *
 * The validation family (`BaseValidationPose` + its four instruments) is the ONLY production
 * author of `straight = true` (plan §12.3 B-4). Under state 3 the validation bake may not
 * silently realize the same frozen responsibility set that [com.monkfitness.app.animation.IkStage]
 * owns; the probes' readings must therefore come from the ACTIVE implementation in each
 * configuration, and the instruments must stay faithful (docs/VALIDATION.md §2 — the probe says
 * "want a straight limb here, show me what the runtime does").
 *
 * Contract properties tested (RED baseline: property 1 fails on the pre-WP-F tree):
 *
 *  1. [freshBuildWindowCarriesNoPreviousRealization] — the carrier's build-window bookkeeping
 *     (F2 re-arm) is REGISTRATION-window state, not realization, so it must run in BOTH
 *     configurations exactly like the member/package bakes: a second build() of the reused
 *     instrument carrier under the ACTIVE stage must not carry the previous frame's solver
 *     readings (stale `straightIntentDropped` would be a fabricated probe result — the reading
 *     would name a solve that never happened this cycle).
 *  2. [validationBakeRegistersIntentUnderActiveStage] — under the active stage the validation
 *     bake still registers its declared intent: every limb reaches the stage as a lossless
 *     [com.monkfitness.app.animation.WorldTarget] (declared lengths + constraint — the straight
 *     instruments' `fullyExtended` constraints ride the declaration, no definition recovery),
 *     and every fixed support still registers its ContactSpec for Phase-2 settlement.
 *  3. [straightProbeReadingComesFromActiveImplementation] — MiddleSplit (bent-fallback probe)
 *     and DeadHang/PikeSit/DOH (settled-contact instruments) produce IDENTICAL published
 *     semantics through the pipeline under both flags; under flag-ON the build window alone
 *     carries no realization reading — the stage is the producer (ownership, not coincidence).
 *  4. [validatorNeverSolvesOrWritesGeometry] — R9 static audit: the production validator path
 *     contains no limb-solver invocation and no carrier geometry writes.
 */
class ValidationOwnershipReCertificationTest {

    private val def = SkeletonDefinition.DEFAULT_ADULT
    private val originalStage = IK_STAGE_ACTIVE

    @After
    fun restoreFlag() {
        IK_STAGE_ACTIVE = originalStage
    }

    private fun ctx() = PoseContext(0.5f, Side.LEFT, def)

    // ------------------------------------- 1 — fresh-window bookkeeping is configuration-neutral

    @Test
    fun freshBuildWindowCarriesNoPreviousRealization() {
        val original = IK_STAGE_ACTIVE
        try {
            IK_STAGE_ACTIVE = true // the activated configuration: stage owns realization
            val instrument = MiddleSplitPose()
            // Frame 1: realized by the engine stage through the pipeline. The straight legs
            // (targets inside L1) must drop the straight intent — the probe's honest reading.
            val first = instrument.build(ctx())
            SkeletonPipeline(def).produceFrame(first)
            assertTrue("frame 1 must carry the stage's dropped reading", first.straightIntentDropped)

            // Frame 2 authoring (build only, before any stage window): the reused carrier must
            // be RE-ARMED to the fresh-window defaults — no reading may survive from frame 1.
            instrument.build(ctx())
            assertFalse(
                "a second build() under the active stage must re-arm the straight-dropped flag " +
                    "(the §12.5 'stamp producers behave identically' acceptance): a stale `true` " +
                    "would let the probe report a drop no implementation executed this cycle — " +
                    "the validation bake must run its F2 window block regardless of the gate",
                first.straightIntentDropped
            )
            assertTrue("fresh window re-opens optimistically", first.boneLengthsVerified)
            assertEquals("no realization happened in the build window", 0, first.limbSolverExecutions)
        } finally {
            IK_STAGE_ACTIVE = original
        }
    }

    // ------------------------------------------- 2 — registration completeness under flag-ON

    @Test
    fun validationBakeRegistersIntentUnderActiveStage() {
        val original = IK_STAGE_ACTIVE
        try {
            IK_STAGE_ACTIVE = true
            val split = MiddleSplitPose().build(ctx())
            assertEquals("all four limbs declared", 4, split.limbTargets.size)
            for (t in split.limbTargets) {
                assertFalse("${t.joint}: declared lengths required", t.length1.isNaN() || t.length2.isNaN())
                assertTrue("${t.joint}: declared constraint required", t.constraint != null)
                assertTrue("${t.joint}: straight probe intent carried", t.straight)
            }
            // The straight instruments opt into full-extension constraints — the declaration
            // must carry them (B-3 losslessness applies to the validation family exactly like
            // the production one; the stage may not recover definition defaults here).
            assertTrue(
                "MiddleSplit's authored straight constraints (fullyExtended) must survive on " +
                    "the declared targets — the validation family is the ONLY production " +
                    "author of per-bake extended constraints (§12.3 B-3)",
                split.limbTargets.all { it.constraint!!.allowFullExtension }
            )
            // Contact registration is unconditional: legs plant on the ground.
            assertEquals("both planted legs register ContactSpecs", 2, split.contacts.size)

            val hang = DeadHangPose().build(ctx())
            assertEquals("both planted arms register ContactSpecs", 2, hang.contacts.size)
            assertEquals("four limbs declared", 4, hang.limbTargets.size)

            // Realization under the gate must have left the node geometry UN-written by the
            // bake — the authored pre-solve locals are intact until the stage runs (anti-
            // "hidden second solve": the bake returns after registration, before realization).
            val kneeF = split.roots.first { it.joint == com.monkfitness.app.animation.Joint.PELVIS }
                .let { findNode(it, com.monkfitness.app.animation.Joint.KNEE_F) }!!
            // MiddleSplit authors NO knee local (it is pure limb-target intent): still the
            // factory default when the bake is realization-gated.
            assertEquals(
                "under the active stage the validation bake must not write limb nodes",
                0f, kneeF.localPosition.mag(), 0f
            )
        } finally {
            IK_STAGE_ACTIVE = original
        }
    }

    // ------------------------------------ 3 — probe semantics across configurations

    @Test
    fun straightProbeReadingComesFromActiveImplementation() {
        val original = IK_STAGE_ACTIVE
        try {
            // (a) MiddleSplit: the bent-fallback drop is published identically through the
            // pipeline in BOTH configurations — the reading is the same because both
            // implementations execute the same frozen responsibility set with the same
            // declared inputs, not because either path is skipped.
            IK_STAGE_ACTIVE = false
            val legacy = SkeletonPipeline(def).produceFrame(MiddleSplitPose(), ctx()).pose
            IK_STAGE_ACTIVE = true
            val activated = SkeletonPipeline(def).produceFrame(MiddleSplitPose(), ctx()).pose
            assertTrue("legacy config: the authoring solver produces the drop", legacy.straightIntentDropped)
            assertTrue("activated config: the engine stage produces the drop", activated.straightIntentDropped)
            // Published transforms + stamps byte-identical across the ownership swap
            // (the §12.9 property, restricted here to the validation instruments).
            for (j in com.monkfitness.app.animation.Joint.entries) {
                val a = legacy.getJoint(j); val b = activated.getJoint(j)
                assertEquals(
                    "$j leg geometry must survive the ownership transition bit-exactly",
                    a.x.toRawBits(), b.x.toRawBits()
                )
                assertEquals("$j", a.y.toRawBits(), b.y.toRawBits())
                assertEquals("$j", a.z.toRawBits(), b.z.toRawBits())
            }
            assertEquals(legacy.maxIkClampAmount.toRawBits(), activated.maxIkClampAmount.toRawBits())
            assertEquals(legacy.boneLengthsVerified, activated.boneLengthsVerified)

            // (b) All four instruments: published semantics equal across configurations.
            for (factory in listOf<Pair<String, () -> com.monkfitness.app.animation.PoseBuilder>>(
                "DeadHang" to { DeadHangPose() },
                "PikeSit" to { PikeSitPose() },
                "DeepOverheadSquat" to { DeepOverheadSquatPose() }
            )) {
                IK_STAGE_ACTIVE = false
                val l = SkeletonPipeline(def).produceFrame(factory.second(), ctx()).pose
                IK_STAGE_ACTIVE = true
                val a2 = SkeletonPipeline(def).produceFrame(factory.second(), ctx()).pose
                for (j in com.monkfitness.app.animation.Joint.entries) {
                    val x = l.getJoint(j); val y = a2.getJoint(j)
                    assertEquals("${factory.first} $j x", x.x.toRawBits(), y.x.toRawBits())
                    assertEquals("${factory.first} $j y", x.y.toRawBits(), y.y.toRawBits())
                    assertEquals("${factory.first} $j z", x.z.toRawBits(), y.z.toRawBits())
                }
                assertEquals(factory.first, l.maxIkClampAmount.toRawBits(), a2.maxIkClampAmount.toRawBits())
                assertEquals(factory.first, l.straightIntentDropped, a2.straightIntentDropped)
                assertEquals(factory.first, l.boneLengthsVerified, a2.boneLengthsVerified)
            }

            // (c) Under the activated configuration, the drop reading exists ONLY after a
            // stage window — never during the build window (producer identity, §12.8: probes
            // must not depend on authoring bake realization once ownership has moved).
            IK_STAGE_ACTIVE = true
            val midBuild = MiddleSplitPose().build(ctx())
            assertFalse("the activated build window carries no realization reading", midBuild.straightIntentDropped)
            SkeletonPipeline(def).produceFrame(midBuild)
            assertTrue("the activated stage window is the producer", midBuild.straightIntentDropped)
        } finally {
            IK_STAGE_ACTIVE = original
        }
    }

    // ------------------------------------------------------- 4 — R9 observational audit

    @Test
    fun validatorNeverSolvesOrWritesGeometry() {
        // R9: ExerciseValidator reads Published Pose State / intent ranges / Frame Context; it
        // never writes the carrier, never derives geometry, never drives execution. The
        // validation FAMILY (instruments) are PoseBuilder fixtures — authoring-side — but the
        // validator itself must stay solver-free and write-free after P12's gate changes.
        val root = productionJavaRoot()
        val validatorFiles = File(root, "validation")
            .walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { !it.path.contains("/poses/") } // instruments are authoring fixtures
            .toList()
        assertTrue("validator sources found", validatorFiles.isNotEmpty())
        val forbidden = listOf("solveIK(", "solveStraightLimb(", "IkStage.apply", "bakeIkLimb(")
        val offenders = mutableListOf<String>()
        for (f in validatorFiles) {
            f.readLines().forEachIndexed { i, raw ->
                val line = raw.trim()
                if (line.startsWith("//") || line.startsWith("*") || line.startsWith("/*")) return@forEachIndexed
                val code = line.substringBefore("//")
                for (p in forbidden) if (code.contains(p)) offenders.add("${f.name}:${i + 1} $p")
                if (code.contains(".setJoint(") ||
                    Regex("""localPosition\s*=\s*[^=]""").containsMatchIn(code) ||
                    code.contains(".localPosition.set(")
                ) offenders.add("${f.name}:${i + 1} carrier geometry write")
            }
        }
        assertEquals(
            "R9: the validation subsystem (non-instrument code) must never invoke a solver " +
                "or write carrier geometry:\n" + offenders.joinToString("\n"),
            emptyList<String>(), offenders
        )
    }

    // --------------------------------------------------------------- helpers

    private fun findNode(
        node: com.monkfitness.app.animation.SkeletonNode,
        joint: com.monkfitness.app.animation.Joint
    ): com.monkfitness.app.animation.SkeletonNode? {
        if (node.joint == joint) return node
        for (c in node.children) {
            val f = findNode(c, joint)
            if (f != null) return f
        }
        return null
    }

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
