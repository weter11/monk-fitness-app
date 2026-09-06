package com.monkfitness.app.arch

import com.monkfitness.app.animation.ContactConstraint
import com.monkfitness.app.animation.ContactSpec
import com.monkfitness.app.animation.ConstraintSolver
import com.monkfitness.app.animation.IKConstraint
import com.monkfitness.app.animation.Joint
import com.monkfitness.app.animation.JointRotation
import com.monkfitness.app.animation.PoseContext
import com.monkfitness.app.animation.Side
import com.monkfitness.app.animation.SkeletonDefinition
import com.monkfitness.app.animation.SkeletonFactory
import com.monkfitness.app.animation.SkeletonPipeline
import com.monkfitness.app.animation.SkeletonPose
import com.monkfitness.app.animation.Vector3
import com.monkfitness.app.poses.ArmCirclesPose
import com.monkfitness.app.validation.poses.MiddleSplitPose
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * Phase 7 (IMPLEMENTATION_PLAN_RUNTIME_SKELETON.md §P7) — R3 Settled-Contact Guarantee
 * enforcement (RFC_RUNTIME_SKELETON_ARCHITECTURE §5 R3/R7, §3.2 Settlement Result, §6
 * re-entry rule).
 *
 * The guarantee: once the ConstraintSolver settles a contact end-effector, no later
 * subsystem — including the SkeletonPoseFinalizer — may move it until the frame is
 * published. P7 instruments the PROTECTED WINDOW between solver completion (the Phase 2
 * exit where the Settlement Result becomes final) and the completion of Phase 3
 * finalization: the pipeline snapshots the WORLD POSITIONS of the settled-contact
 * end-effectors immediately after `ConstraintSolver.solve` and re-compares them exactly
 * after `finalizer.finalize` returns (debug builds; release builds compile the mechanism
 * out, same gating as the P6 R2 boundary assertions).
 *
 * The reference set is the Settlement Result (`pose.settlementResult.declaredContactJoints`
 * — the canonical Phase-3 producer output; per audit B2 the field name states exactly what
 * the solver records) — never reconstructed from `pose.contacts`, retained chains, solver
 * heuristics, Frame History, or pelvis/root state.
 *
 * Fixture facts (probe-verified against the merged tree):
 *  - A planted hand/ankle contact end-effector is bit-stable through finalization: the
 *    Finalizer's extremity derivation writes only the CHILD endpoints (wrist/palm/knuckles/
 *    fingertips, heel/toe), never the settled HAND/ANKLE array entries themselves, and
 *    the chest-frame reconstruction is R3-bound (F1 guard). All four contact-bearing
 *    validation poses show EXACTLY zero settled-contact drift across the progress sweep.
 *  - A planted TOE_F contact is a REAL violation route: the Finalizer's automatic foot
 *    derivation recomputes the toe entry from the settled ankle + shank direction, silently
 *    displacing the settled contact inside the protected window (exactly what R3 forbids) —
 *    this powers the primary behavioral regression with production code on both sides of
 *    the mutation, no test-only injection seam needed.
 *  - `resolveHeadTarget` writes only NECK_END/HEAD_POS local offsets (R7's neck/head scope).
 *    On a hand-planted pose the declared gaze intent therefore moves the head while the
 *    settled hand stays put — the head-target / sanctioned-skip shape: SupportPoint has no
 *    head/neck member and the settled chain is outside the neck/head scope, so the intent
 *    is bounded away from the contact instead of displacing it (RFC §5 R3: where declared
 *    intent would move a settled contact, the contact wins; the skip is legitimate
 *    architecture, not data loss).
 *  - `applyIntentCarriers` skips carrier consumption entirely for contact poses (the
 *    pre-existing sanctioned skip) — a declared joint intent on a planted pose must neither
 *    re-FK the tree nor trip the new assertion.
 *
 * This file compiles on the pre-P7 tree: the new utility surface is looked up reflectively
 * and the wiring assertions scan source text, so the same bytes run as the counterfactual
 * RED gate on `origin/main` without P7 instrumentation (the P6 pattern).
 */
class SettledContactGuaranteeTest {

    private val definition = SkeletonDefinition.DEFAULT_ADULT

    /** One authored frame on a fresh standard node tree, re-authorable per call. */
    private class FrameFixture {
        val nodes = SkeletonFactory.createStandardSkeleton()
        val pose: SkeletonPose = SkeletonPose().apply { roots = nodes.roots }
    }

    private fun ctx(progress: Float = 0.5f) = PoseContext(
        progress = progress, side = Side.RIGHT, definition = definition,
        deltaTime = 1f / 60f, cycleDuration = 2500f
    )

    private fun contact(
        end: Joint, root: Joint, parent: Joint, middle: Joint,
        target: Vector3, l1: Float, l2: Float,
        constraint: IKConstraint = IKConstraint(30f, 0.98f)
    ): ContactSpec = ContactSpec(
        endJoint = end, rootJoint = root, parentRotationJoint = parent, middleJoint = middle,
        targetWorld = Vector3(target.x, target.y, target.z), pole = Vector3(0.2f, 1f, 0f),
        length1 = l1, length2 = l2, constraint = constraint, straight = false,
        contact = ContactConstraint.ground(target.y)
    )

    /** A planted-hand frame: HAND_A settled at its ground target, solver re-bakes the arm. */
    private fun handPlant(fixture: FrameFixture): FrameFixture {
        fixture.nodes.pelvis.localPosition = Vector3(0f, 120f, 0f)
        fixture.pose.contacts.add(
            contact(Joint.HAND_A, Joint.SHOULDER_A, Joint.SCAPULA_A, Joint.ELBOW_A,
                Vector3(0f, 60f, -25f), definition.upperArmLength, definition.forearmLength)
        )
        return fixture
    }

    /** A planted-toe frame (toe-stand shape): TOE_F settled on the ground plane. The 1-bone
     *  chain uses a full-extension constraint (band degenerates to the bone length). */
    private fun toePlant(fixture: FrameFixture): FrameFixture {
        fixture.nodes.pelvis.localPosition = Vector3(0f, 120f, 0f)
        fixture.pose.contacts.add(
            contact(Joint.TOE_F, Joint.ANKLE_F, Joint.ANKLE_F, Joint.TOE_F,
                Vector3(15f, 0f, 8f), 20f, 0.001f, IKConstraint(30f, 1.0f))
        )
        return fixture
    }

    // ---------------------------------------------------------------------------------
    // 1 — Clean settled-contact frame passes (the whole-phase check is not over-broad).
    // ---------------------------------------------------------------------------------
    @Test
    fun cleanSettledHandPlantSurvivesFinalizationBitIdentical() {
        val fixture = handPlant(FrameFixture())
        val published = SkeletonPipeline(definition).produceFrame(fixture.pose).pose

        val sr = fixture.pose.settlementResult
        assertNotNull("fixture guard: the solve ran and produced a Settlement Result", sr)
        assertEquals("fixture guard: the Settlement Result references exactly the planted hand",
            listOf(Joint.HAND_A), sr!!.declaredContactJoints)
        // The settled world position (input-carrier arrays written by the solve's final
        // FK+flatten) survives into the published frame bit-for-bit: the Finalizer did not
        // move the settled end-effector.
        assertEquals("settled hand must be bit-identical across the protected window",
            0f, manhattan(fixture.pose.getJoint(Joint.HAND_A), published.getJoint(Joint.HAND_A)), 0f)
        assertEquals("anti-vacuity: the contact settled at its planted target",
            0f, fixture.pose.getJoint(Joint.HAND_A).x, 0f)
        assertEquals(60f, published.getJoint(Joint.HAND_A).y, 0f)
        // Replays through fresh pipelines must not throw — a lawful finalization passes the
        // R3 boundary assertion end-to-end.
        SkeletonPipeline(definition).produceFrame(handPlant(FrameFixture()).pose)
        SkeletonPipeline(definition).produceFrame(handPlant(FrameFixture()).pose)
    }

    // ---------------------------------------------------------------------------------
    // 2 — THE primary behavioral regression: a real settled-contact mutation executed
    // by production Finalizer code inside the protected window must throw R3. Pre-P7 the
    // displacement reaches the published frame silently (RED for exactly that reason).
    // ---------------------------------------------------------------------------------
    @Test
    fun settledContactDisplacedDuringFinalizationThrowsR3Violation() {
        val pipeline = SkeletonPipeline(definition)
        val fixture = toePlant(FrameFixture())
        val result = runCatching { pipeline.produceFrame(fixture.pose) }

        if (result.isSuccess) {
            // PRE-P7 COUNTERFACTUAL BRANCH: no assertion exists, so the displacement is
            // silent. Prove the violation is REAL (a settled contact actually moved into
            // the published frame), then fail for the intended reason: the guarantee is
            // not detected.
            val published = result.getOrThrow().pose
            val settledToe = Vector3().also { it.set(fixture.pose.getJoint(Joint.TOE_F)) }
            assertNotEquals(
                "fixture guard: production finalization must actually displace the settled " +
                    "toe (foot-derivation write), settled=$settledToe " +
                    "published=${published.getJoint(Joint.TOE_F)}",
                0f, manhattan(settledToe, published.getJoint(Joint.TOE_F)), 1e-3f
            )
            throw AssertionError(
                "expected IllegalStateException('R3 violation: …') for a settled contact moved " +
                    "inside the finalization window, but the pipeline published the displaced " +
                    "contact silently — the R3 Settled-Contact Guarantee is not detected (pre-P7)"
            )
        }

        val thrown = result.exceptionOrNull()!!
        assertTrue(
            "expected IllegalStateException('R3 violation …'), got: $thrown",
            thrown is IllegalStateException && thrown.message!!.contains("R3 violation")
        )
        val message = thrown!!.message!!
        // The error text identifies the rule AND names the operation window (plan §P7) and
        // the violated contact.
        assertTrue("error text must name the protected window (solve→finalize), got: $message",
            message.contains("finalize"))
        assertTrue("error text must identify the displaced joint, got: $message",
            message.contains("TOE_F"))
    }

    // ---------------------------------------------------------------------------------
    // 3 — Non-settled joints may still move: the Finalizer's R7-scoped Head-Target
    // Resolution rewrites the neck/head offsets inside the window; the assertion must
    // NOT treat non-contact motion as a violation (over-breadth guard).
    // ---------------------------------------------------------------------------------
    @Test
    fun nonContactHeadMotionDuringFinalizationDoesNotThrowR3() {
        val pipeline = SkeletonPipeline(definition)
        val fixture = handPlant(FrameFixture())
        SkeletonPose.IntentBuilder(fixture.pose).headTarget(Vector3(0f, 200f, 200f))
        val published = pipeline.produceFrame(fixture.pose).pose

        // Anti-vacuity: the head really moved inside the window (gaze resolution is the
        // production non-contact mutation), while the settled hand stayed bit-identical —
        // so this frame genuinely exercised the check against a live non-contact write.
        val headDrift = manhattan(
            fixture.pose.getJoint(Joint.HEAD_POS), published.getJoint(Joint.HEAD_POS)
        )
        assertNotEquals("fixture guard: Head-Target Resolution must have moved HEAD_POS",
            0f, headDrift, 1e-3f)
        assertEquals("settled hand untouched by the sanctioned neck/head writes",
            0f, manhattan(fixture.pose.getJoint(Joint.HAND_A), published.getJoint(Joint.HAND_A)), 0f)
    }

    // ---------------------------------------------------------------------------------
    // 4 — Multiple settled contacts: ALL are checked. The planted-hand + toe fixture
    // carries TWO settled contacts; only the SECOND (TOE_F, declared after HAND_A) is
    // displaced by production code — an implementation that checked only the head of the
    // reference set would miss the throw entirely. The clean MiddleSplit frame proves the
    // multi-contact walk passes when lawful (both settled ankles bit-identical).
    // ---------------------------------------------------------------------------------
    @Test
    fun multipleSettledContactsAreAllChecked() {
        // Clean multi-contact pass: MiddleSplit settles ANKLE_F + ANKLE_B; both survive
        // finalization bit-identical (probe-verified across the progress sweep).
        val splitFixture = MiddleSplitPose().build(ctx())
        val published = SkeletonPipeline(definition).produceFrame(splitFixture).pose
        val splitSr = splitFixture.settlementResult
        assertNotNull("fixture guard: MiddleSplit enters the solver with two contacts", splitSr)
        assertEquals("both feet are in the Settlement Result reference set",
            listOf(Joint.ANKLE_F, Joint.ANKLE_B), splitSr!!.declaredContactJoints)
        for (j in splitSr.declaredContactJoints) {
            assertEquals("$j must be bit-identical across a lawful finalization",
                0f, manhattan(splitFixture.getJoint(j), published.getJoint(j)), 0f)
        }

        // Displacement of the SECOND declared contact must be detected: hand declared first
        // (stays settled), toe declared second (displaced by foot derivation).
        val pipeline = SkeletonPipeline(definition)
        val fixture = toePlant(handPlant(FrameFixture()))
        val result = runCatching { pipeline.produceFrame(fixture.pose) }
        val sr = fixture.pose.settlementResult
        assertEquals("reference set must carry both settled contacts in declaration order",
            listOf(Joint.HAND_A, Joint.TOE_F), sr!!.declaredContactJoints)
        if (result.isSuccess) {
            val pub = result.getOrThrow().pose
            assertEquals("the first settled contact stayed put", 0f,
                manhattan(fixture.pose.getJoint(Joint.HAND_A), pub.getJoint(Joint.HAND_A)), 0f)
            assertNotEquals("fixture guard: the second settled contact was displaced", 0f,
                manhattan(fixture.pose.getJoint(Joint.TOE_F), pub.getJoint(Joint.TOE_F)), 1e-3f)
            throw AssertionError(
                "expected R3 violation detecting the displaced SECOND settled contact (TOE_F), " +
                    "but nothing threw — pre-P7 the multi-contact walk does not exist"
            )
        }
        val thrown = result.exceptionOrNull()!!
        assertTrue("expected an R3 violation naming the displaced second contact, got: $thrown",
            thrown is IllegalStateException && thrown.message!!.contains("R3 violation") &&
                thrown.message!!.contains("TOE_F"))
    }

    // ---------------------------------------------------------------------------------
    // 5 — No settled contacts ⇒ the assertion is a no-op. Three shapes: a posture-driven
    // solve with ZERO contacts (Settlement Result present, empty reference set), a
    // contact-less CUSTOM frame that skips the solve entirely, and the STALE-RESULT trap:
    // a reused carrier whose previous-frame Settlement Result survives into a later frame
    // that does not solve (or whose solve early-returns). The reference arms only from
    // THIS frame's producing solve — never from a leftover carrier slot.
    // ---------------------------------------------------------------------------------
    @Test
    fun noSettledContactsMakesTheAssertionANoOp() {
        val pipeline = SkeletonPipeline(definition)

        // (a) posture-driven, zero contacts: sr present with an empty declared set.
        val armCircles = ArmCirclesPose().build(ctx())
        ConstraintSolver.solve(armCircles, definition)
        val sr = armCircles.settlementResult
        assertNotNull("fixture guard: ARMCIRCLES enters the solver (posture-driven)", sr)
        assertTrue("fixture guard: no settled contacts declared", sr!!.declaredContactJoints.isEmpty())
        SkeletonPipeline(definition).produceFrame(ArmCirclesPose().build(ctx(0.6f)))

        // (b) contact-less CUSTOM: the solve is skipped; nothing to protect.
        val skip = FrameFixture()
        skip.nodes.pelvis.localPosition = Vector3(10f, 50f, 0f)
        pipeline.produceFrame(skip.pose)

        // (c) stale-reference trap: solve a planted frame, then CLEAR the contacts on the
        // SAME reused carrier and produce a CUSTOM contact-less frame. The previous frame's
        // Settlement Result object is still sitting in the carrier slot; the skipped frame
        // must not arm from it — and the finalizer's legitimate non-settled rewrites on the
        // skip path must not false-fire an R3 throw.
        val stale = handPlant(FrameFixture())
        SkeletonPipeline(definition).produceFrame(stale.pose)
        stale.pose.contacts.clear()
        stale.nodes.pelvis.localPosition = Vector3(0f, 150f, 0f) // the planted hand's world
                                                                  // position now differs from
                                                                  // the stale settled value
        SkeletonPipeline(definition).produceFrame(stale.pose)
    }

    // ---------------------------------------------------------------------------------
    // 6 — Existing sanctioned-skip behavior remains intact at the new boundary: a contact
    // pose declaring joint-intent carriers takes the Finalizer's `applyIntentCarriers`
    // early-out (the carriers stay live but are NOT consumed — re-FK would displace the
    // settled chain). The planted hand must stay bit-identical and no violation may fire.
    // ---------------------------------------------------------------------------------
    @Test
    fun sanctionedIntentCarrierSkipKeepsSettledContacts() {
        val pipeline = SkeletonPipeline(definition)
        val fixture = handPlant(FrameFixture())
        // Declare an intent that, if consumed, would rotate a settled-chain joint (chest →
        // shoulder → elbow → hand). applyIntentCarriers skips it for contact poses.
        SkeletonPose.IntentBuilder(fixture.pose)
            .joint(Joint.CHEST, JointRotation(Vector3(0f, 1f, 0f), 0.4f))
        val published = pipeline.produceFrame(fixture.pose).pose

        assertTrue("fixture guard: the intent carrier is populated (live, skipped by contract)",
            fixture.pose.jointIntents.isNotEmpty())
        assertEquals("settled chain untouched — the sanctioned skip preserved the contact",
            0f, manhattan(fixture.pose.getJoint(Joint.HAND_A), published.getJoint(Joint.HAND_A)), 0f)
    }

    // ---------------------------------------------------------------------------------
    // 7 — The Settlement Result is the SOLE reference source (structural + wiring audit):
    // the capture reads it — not the Contact Declarations — and the pipeline arms the
    // protected reference at exactly one site (inside the solve branch) with no Frame
    // History involvement.
    // ---------------------------------------------------------------------------------
    @Test
    fun settlementResultIsTheSoleReferenceSource() {
        // (a) The capture region reads pose.settlementResult + its contact-joint list and
        // never touches pose.contacts.
        val util = productionSource("PhaseBoundaryAsserts.kt")
        val captureRegion = funRegion(util, "captureSettledContacts")
        assertTrue("the capture must exist and read pose.settlementResult (the canonical " +
            "Phase-3 producer output); region: $captureRegion",
            captureRegion.any { it.contains("settlementResult") })
        assertTrue("the reference set is the Settlement Result's contact-joint list",
            captureRegion.any { it.contains("declaredContactJoints") })
        assertTrue("the reference set must NOT be reconstructed from the declaration list",
            captureRegion.none { Regex("""\.contacts\b""").containsMatchIn(it) })

        // (b) The pipeline arms the reference set only from the solve site and never
        // consults Frame History for it.
        val pipeline = productionSource("SkeletonPipeline.kt")
        val armSites = pipeline.withIndex()
            .filter { (_, line) -> Regex("""^r3SettledContacts\s*=(?!=)""").containsMatchIn(line.trim()) }
            .map { it.index }
        assertEquals("the R3 protected reference may switch at exactly one site (post-solve)",
            1, armSites.size)
        val solveAt = pipeline.indexOfFirst { it.contains("ConstraintSolver.solve(") }
        val finalizeAt = pipeline.indexOfFirst { it.trimStart().startsWith("val finalized = finalizer.finalize(") }
        assertTrue("the arm site must lie between the solve and finalization " +
                "(solveAt=$solveAt, arm=${armSites[0]}, finalizeAt=$finalizeAt)",
            solveAt in 0..<armSites[0] && armSites[0] < finalizeAt)
        val historyInference = pipeline.filter { line ->
            line.contains("r3SettledContacts") &&
                (line.contains("previous") || line.contains("prePrevious") ||
                    line.contains("previousSmoothingRoot"))
        }
        assertTrue("R3 arming must never consult Frame History: $historyInference",
            historyInference.isEmpty())
    }

    // ---------------------------------------------------------------------------------
    // 8 — Boundary-attachment audit: the assertion is wired into the REAL pipeline
    // boundary (post-solve capture → post-finalize check on the FINALIZED pose) rather
    // than living as an unused helper, and the R3 enforcement text stays centralized in
    // the shared P6 utility.
    // ---------------------------------------------------------------------------------
    @Test
    fun assertionIsAttachedToThePipelineBoundary() {
        val util = try {
            Class.forName("com.monkfitness.app.animation.PhaseBoundaryAsserts")
        } catch (e: ClassNotFoundException) {
            null
        }
        assertNotNull("the shared PhaseBoundaryAsserts utility must exist (P6)", util)
        // (internal member functions carry module-suffix mangling — match by prefix)
        val methods = util!!.declaredMethods.map { it.name }
        assertTrue("PhaseBoundaryAsserts must expose the multi-joint settled-contact capture " +
                "reused from P6 (plan §P7 instrumentation strategy); found: $methods",
            methods.any { it.startsWith("captureSettledContacts") })

        val pipeline = productionSource("SkeletonPipeline.kt")
        val captureCall = pipeline.indexOfFirst { it.contains("captureSettledContacts") }
        assertTrue("SkeletonPipeline.runStages must call captureSettledContacts (found: $captureCall)",
            captureCall >= 0)
        val finalizeAt = pipeline.indexOfFirst { it.trimStart().startsWith("val finalized = finalizer.finalize(") }
        val checkSites = pipeline.withIndex()
            .filter { (_, line) -> line.contains("r3SettledContacts?.assertUnchanged(") }
            .map { it.index }
        assertTrue("runStages must verify the settled contacts AFTER finalization " +
                "(window end = completion of Phase 3 finalization); found: $checkSites",
            checkSites.isNotEmpty() && checkSites.all { it > finalizeAt })
        val checkedOnFinalized = checkSites.any { site ->
            pipeline.drop(site).take(4).joinToString(" ").contains("finalized")
        }
        assertTrue("the post-window check must compare the FINALIZED pose", checkedOnFinalized)

        // Centralization: the R3 enforcement text lives in the shared utility only.
        val sources = productionAnimationSources()
        val r3Sites = sources.filter { (_, lines) ->
            lines.any { it.contains("\"R3 violation") }
        }.keys
        assertEquals("\"R3 violation\" text must be centralized in PhaseBoundaryAsserts.kt " +
            "only, found: $r3Sites", setOf("PhaseBoundaryAsserts.kt"), r3Sites)
    }

    // ---------------------------------------------------------------------------------
    // 9 — Debug-only by construction: both new boundary points sit inside
    // `if (BuildConfig.DEBUG)` blocks (release builds pay nothing — plan §P7 "free in
    // release"), mirroring the P6 gating.
    // ---------------------------------------------------------------------------------
    @Test
    fun r3InstrumentationIsDebugGated() {
        val pipeline = productionSource("SkeletonPipeline.kt")
        val finalizeAt = pipeline.indexOfFirst { it.trimStart().startsWith("val finalized = finalizer.finalize(") }
        val checkLine = pipeline.indexOfFirst { it.contains("r3SettledContacts?.assertUnchanged(") }
        assertTrue("the R3 check must exist after finalize (found: $checkLine)", checkLine > finalizeAt)
        assertTrue("the R3 check must sit inside a BuildConfig.DEBUG-guarded block",
            enclosedByDebugGuard(pipeline, checkLine))
        val armLine = pipeline.indexOfFirst {
            Regex("""^r3SettledContacts\s*=(?!=)""").containsMatchIn(it.trim())
        }
        assertTrue("the R3 arm site must exist (found: $armLine)", armLine >= 0)
        assertTrue("the R3 capture must sit inside a BuildConfig.DEBUG-guarded block",
            enclosedByDebugGuard(pipeline, armLine))
    }

    // ---------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------

    private fun manhattan(a: Vector3, b: Vector3): Float =
        abs(a.x - b.x) + abs(a.y - b.y) + abs(a.z - b.z)

    /** Production animation sources keyed by file name (walk-up per the Gradle user.dir pitfall). */
    private fun productionAnimationSources(): Map<String, List<String>> {
        var dir = File(System.getProperty("user.dir"))
        var moduleRoot: File? = null
        for (attempt in 0 until 8) {
            if (File(dir, "src/main/java/com/monkfitness/app/animation").isDirectory) {
                moduleRoot = dir
                break
            }
            dir = dir.parentFile ?: break
        }
        val root = moduleRoot ?: error(
            "Could not locate app module root from ${System.getProperty("user.dir")}"
        )
        val srcDir = File(root, "src/main/java/com/monkfitness/app/animation")
        return srcDir.listFiles { f -> f.isFile && f.name.endsWith(".kt") }!!
            .associate { it.name to it.readLines() }
    }

    private fun productionSource(name: String): List<String> =
        productionAnimationSources()[name]
            ?: error("$name not found in the production animation package")

    /** Lines of a function region starting at the header line and ending at the next
     *  top-level-or-member declaration (a line whose trimmed content starts a fun/class). */
    private fun funRegion(lines: List<String>, funName: String): List<String> {
        val start = lines.indexOfFirst { it.contains("fun $funName") }
        if (start < 0) return emptyList()
        val rest = lines.drop(start + 1)
        val end = rest.indexOfFirst { it.trimStart().startsWith("fun ") ||
            it.trimStart().startsWith("class ") || it.trimStart().startsWith("internal ") ||
            it.trimStart().startsWith("private ") }
        return rest.take(if (end < 0) rest.size else end)
    }

    /**
     * Upward brace walk: does [idx] sit inside an `if (BuildConfig.DEBUG) { … }` block
     * (directly or nested through other control-flow blocks)? Stops at a brace opener that
     * is not a condition (class/object member bodies).
     */
    private fun enclosedByDebugGuard(lines: List<String>, idx: Int): Boolean {
        var depth = 0
        for (i in (idx - 1) downTo 0) {
            val line = lines[i]
            var j = line.length - 1
            while (j >= 0) {
                when (line[j]) {
                    '}' -> depth++
                    '{' -> {
                        if (depth == 0) {
                            val t = line.trim()
                            if (t.contains("BuildConfig.DEBUG")) return true
                            if (!(t.startsWith("if ") || t.startsWith("} else if") ||
                                    t.startsWith("else") || t.contains("if ("))) return false
                            // nested through a non-DEBUG condition: keep walking up
                        } else depth--
                    }
                }
                j--
            }
        }
        return false
    }
}
