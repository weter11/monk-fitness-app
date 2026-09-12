package com.monkfitness.app.arch

import com.monkfitness.app.animation.BasePose
import com.monkfitness.app.animation.ContactConstraint
import com.monkfitness.app.animation.IK_STAGE_ACTIVE
import com.monkfitness.app.animation.IkStage
import com.monkfitness.app.animation.Joint
import com.monkfitness.app.animation.JointRotation
import com.monkfitness.app.animation.PoseContext
import com.monkfitness.app.animation.Side
import com.monkfitness.app.animation.SkeletonDefinition
import com.monkfitness.app.animation.SkeletonFactory
import com.monkfitness.app.animation.SkeletonMath
import com.monkfitness.app.animation.SkeletonNode
import com.monkfitness.app.animation.SkeletonPipeline
import com.monkfitness.app.animation.SkeletonPose
import com.monkfitness.app.animation.Vector3
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P12 §12.7 — **flag lifecycle** and the **activated single-active-solver ownership** invariant:
 *
 * > For one build cycle and one configuration, exactly one Active Limb Solver may perform limb
 * > realization.
 *
 * The sibling suites cover the pieces of this contract that already had authoritative tests:
 * `SingleActiveSolverEnforcementTest` (per-implementation realization evidence, counterfactual
 * double execution within ONE implementation), `RuntimeSolverOwnershipAuditTest` (§12.7
 * configuration surface + static write confinement), `ActivationEquivalenceTest` (§12.9
 * cross-configuration corpus), `ValidationOwnershipReCertificationTest` (§12.5/§12.6 registration
 * under the active stage), `StraightIntentFallbackTest` (the flag's per-solve producers). This class
 * adds ONLY the four contract properties those suites cannot express, and it deliberately re-asserts
 * no claim they already own:
 *
 *  1. [declaredLimbsAreRealizedExactlyOncePerConfiguration] — the invariant stated on the DECLARED
 *     limb set instead of on one probe limb: for a multi-limb frame, the set of realized limbs must
 *     equal the declared [SkeletonPose.limbTargets] set in BOTH configurations, with exactly one
 *     solver window and zero duplicate executions. This is the "exactly one realization per declared
 *     limb" form of §12.7 that neither the 1-limb owner suites nor the corpus express.
 *  2. [bakeRealizationAndStageRealizationInOneCycleIsRejected] — the §12.7a **double-realization
 *     trap**: the authoring bake and the engine stage MUST NOT both realize the same build cycle.
 *     The trap is run through the registered production realization path (never a fabricated
 *     counter write) and it is proved that a geometry-only test could not detect it: the two
 *     single-realization frames are raw-bit identical.
 *  3. [registrationSurvivesTheGateWhileRealizationIsGated] — §12.5 acceptance at FIELD level: under
 *     the active stage every registration effect still runs (Limb Target with its declared target,
 *     pole, lengths, constraint and straight intent; the Contact Declaration), the realization
 *     effects do not (no window, no realized limb, no stamp folds, no node writes).
 *  4. The flag-lifecycle group — [freshAndReusedBuildersReportTheCurrentBuildsReading],
 *     [multipleLimbsInOneBuildMergeTheReading], [droppedReadingIsReArmedByTheNextBuild] and
 *     [bentOnlyBuildDoesNotInheritAPreviousDrop] prove the flag is *current-build state*: it is
 *     re-armed by the build window in BOTH configurations, it merges across limbs of one build, and
 *     it never survives into a later build that did not drop a straight intent.
 *
 * Correction of the record: the counterfactual traps below were executed against the defective
 * shapes before being accepted as green — (a) with the §12.7a gate removed from the authoring bakes,
 * [bakeRealizationAndStageRealizationInOneCycleIsRejected] observes the double realization and the
 * pipeline rejects the frame; (b) with the F2 build-window re-arm moved below the gate (the WP-F
 * defect shape) the lifecycle group goes red — see the P12 §12.7 landing record.
 */
class SingleActiveSolverLifecycleTest {

    private val def = SkeletonDefinition.DEFAULT_ADULT
    private val ctx = PoseContext(0.5f, Side.LEFT, def)
    private val originalFlag = IK_STAGE_ACTIVE

    @After
    fun restoreFlag() {
        IK_STAGE_ACTIVE = originalFlag
    }

    /** Runs [block] in an explicitly named ownership configuration, restoring the previous one. */
    private fun <T> inConfiguration(active: Boolean, block: () -> T): T {
        val original = IK_STAGE_ACTIVE
        return try {
            IK_STAGE_ACTIVE = active
            block()
        } finally {
            IK_STAGE_ACTIVE = original
        }
    }

    // ------------------------------------------------------------------------------ fixtures

    /** One declared limb on a leg chain: the joint, its world target and the authored intent. */
    private data class Declaration(
        val joint: Joint,
        val target: Vector3,
        val straight: Boolean,
        val contact: ContactConstraint? = null
    )

    /**
     * Test-only authoring probe: a [BasePose] that declares [declarations] on the front (`*_F`) /
     * back (`*_B`) leg chains of the standard skeleton and then finalizes through the same
     * `SkeletonPose.fromHierarchy` template every production pose uses. The declaration list is
     * mutable so a REBUILT carrier (the reused-builder lifecycle case) can change its intent.
     */
    private class StraightLimbProbe(var declarations: List<Declaration>) : BasePose() {
        override fun onBuild(context: PoseContext): SkeletonPose {
            val definition = context.definition
            val nodes = SkeletonFactory.createStandardSkeleton()
            nodes.pelvis.localPosition.set(ROOT.x, ROOT.y, ROOT.z)
            // Hip world == pelvis (no hip offset), so both chains share the fixture root and a
            // declared target is interpretable as a distance from it.
            nodes.hipF.localPosition.set(0f, 0f, 0f)
            nodes.kneeF.localPosition.set(definition.thighLength, 0f, 0f)
            nodes.ankleF.localPosition.set(definition.shinLength, 0f, 0f)
            nodes.hipB.localPosition.set(0f, 0f, 0f)
            nodes.kneeB.localPosition.set(definition.thighLength, 0f, 0f)
            nodes.ankleB.localPosition.set(definition.shinLength, 0f, 0f)
            nodes.roots.forEach { it.updateWorldTransforms(Vector3(), JointRotation()) }

            for (declaration in declarations) {
                val chain = when (declaration.joint) {
                    Joint.ANKLE_F -> Triple(nodes.hipF, nodes.kneeF, nodes.ankleF)
                    Joint.ANKLE_B -> Triple(nodes.hipB, nodes.kneeB, nodes.ankleB)
                    else -> error("fixture supports the leg chains only (got ${declaration.joint})")
                }
                bakeIkLimb(
                    chain.first.worldPosition,
                    declaration.target,
                    definition.thighLength,
                    definition.shinLength,
                    POLE,
                    definition.legIKConstraint,
                    nodes.pelvis.worldRotation,
                    chain.second,
                    chain.third,
                    SkeletonMath.IKResult(),
                    straight = declaration.straight,
                    contact = declaration.contact
                )
            }
            return SkeletonPose.fromHierarchy(nodes.roots, jointsBuffer)
        }
    }

    private fun probe(vararg declarations: Declaration) = StraightLimbProbe(declarations.toList())

    /** A straight request whose reach is inside the proximal bone ⇒ the bent fallback runs. */
    private fun droppedStraight(joint: Joint, contact: ContactConstraint? = null) =
        Declaration(joint, ROOT_PLUS_DROP, straight = true, contact = contact)

    /** A straight request that is genuinely deliverable ⇒ no fallback, no drop. */
    private fun honoredStraight(joint: Joint, contact: ContactConstraint? = null) =
        Declaration(joint, ROOT_PLUS_HONOR, straight = true, contact = contact)

    /** A bent request: the limb never asked for straight, so it can never drop that intent. */
    private fun bent(joint: Joint, contact: ContactConstraint? = null) =
        Declaration(joint, ROOT_PLUS_HONOR, straight = false, contact = contact)

    // ------------------------------------------------------------------------------ helpers

    private fun realizedJoints(pose: SkeletonPose): Set<Joint> =
        Joint.entries.filterTo(mutableSetOf()) { pose.limbRealizedLimbs and (1L shl it.index) != 0L }

    private fun declaredJoints(pose: SkeletonPose): Set<Joint> = pose.limbTargets.map { it.joint }.toSet()

    private fun jointBits(pose: SkeletonPose): List<Int> =
        Joint.entries.flatMap { j ->
            val v = pose.getJoint(j)
            listOf(v.x.toRawBits(), v.y.toRawBits(), v.z.toRawBits())
        }

    private fun bits(v: Vector3): List<Int> = listOf(v.x.toRawBits(), v.y.toRawBits(), v.z.toRawBits())

    private fun findNode(node: SkeletonNode, joint: Joint): SkeletonNode? {
        if (node.joint == joint) return node
        for (child in node.children) {
            val found = findNode(child, joint)
            if (found != null) return found
        }
        return null
    }

    private fun assertR5Violation(violation: Throwable?, context: String) {
        assertTrue(
            "$context — expected the single-active-solver enforcement to reject the frame with an " +
                "R5 violation. Observed: " +
                (violation?.let { "${it::class.simpleName}: ${it.message}" } ?: "no violation raised"),
            violation is IllegalStateException && violation.message.orEmpty().contains("R5 violation")
        )
    }

    // ------------------------------------------- 1 — one realization per DECLARED limb (§12.7)

    @Test
    fun declaredLimbsAreRealizedExactlyOncePerConfiguration() {
        val declarations = listOf(droppedStraight(Joint.ANKLE_F), honoredStraight(Joint.ANKLE_B))
        val declared = setOf(Joint.ANKLE_F, Joint.ANKLE_B)

        // (a) Authoring configuration: the bake is the Active Limb Solver for the whole build.
        inConfiguration(false) {
            val pose = probe(*declarations.toTypedArray()).build(ctx)
            assertEquals("exactly one authoring realization window", 1, pose.limbSolverExecutions)
            assertEquals("no limb realized twice", 0, pose.limbDuplicateRealizations)
            assertEquals(
                "every declared limb is realized, and nothing else",
                declared, realizedJoints(pose)
            )
            // The engine-side implementation is configuration-disabled: it must not add a window.
            IkStage.apply(pose, def)
            assertEquals("the gated stage contributes no window", 1, pose.limbSolverExecutions)
            assertEquals("the gated stage realizes nothing", 0, pose.limbDuplicateRealizations)
            assertEquals(declared, realizedJoints(pose))
        }

        // (b) Activated configuration: registration happens in the build, realization in the stage.
        inConfiguration(true) {
            val pose = probe(*declarations.toTypedArray()).build(ctx)
            assertEquals("the gated bake opens no window", 0, pose.limbSolverExecutions)
            assertEquals("the gated bake realizes no limb", emptySet<Joint>(), realizedJoints(pose))
            assertEquals("every declared limb still reaches the stage", declared, declaredJoints(pose))

            IkStage.apply(pose, def)
            assertEquals("exactly one engine realization window", 1, pose.limbSolverExecutions)
            assertEquals("no limb realized twice", 0, pose.limbDuplicateRealizations)
            assertEquals(
                "the engine realizes exactly the declared limb set",
                declared, realizedJoints(pose)
            )
            assertEquals(
                "realized set and declared set coincide — no limb realized that was not declared, " +
                    "none declared that was not realized",
                declaredJoints(pose), realizedJoints(pose)
            )
        }

        // Both configurations are accepted by the pipeline's enforcement on the production path.
        inConfiguration(false) { SkeletonPipeline(def).produceFrame(probe(*declarations.toTypedArray()), ctx) }
        inConfiguration(true) { SkeletonPipeline(def).produceFrame(probe(*declarations.toTypedArray()), ctx) }
    }

    // ------------------------------------ 2 — the double-realization trap (§12.7a violation mode)

    @Test
    fun bakeRealizationAndStageRealizationInOneCycleIsRejected() {
        val declarations = listOf(droppedStraight(Joint.ANKLE_F), honoredStraight(Joint.ANKLE_B))

        // PREMISE 1 — output equivalence: a frame realized by the bake and a frame realized by the
        // stage publish raw-bit identical geometry and identical solver stamps. A geometry-only or
        // stamp-only test therefore CANNOT distinguish one realization from two; only execution
        // evidence can.
        val authoringFrame = inConfiguration(false) {
            SkeletonPipeline(def).produceFrame(probe(*declarations.toTypedArray()), ctx).pose
        }
        val engineFrame = inConfiguration(true) {
            SkeletonPipeline(def).produceFrame(probe(*declarations.toTypedArray()), ctx).pose
        }
        assertEquals(
            "premise: the two single-realization configurations publish identical geometry",
            jointBits(authoringFrame), jointBits(engineFrame)
        )
        assertEquals(
            "premise: identical clamp stamp",
            authoringFrame.maxIkClampAmount.toRawBits(), engineFrame.maxIkClampAmount.toRawBits()
        )
        assertEquals(
            "premise: identical straight-intent reading",
            authoringFrame.straightIntentDropped, engineFrame.straightIntentDropped
        )
        assertTrue(
            "anti-vacuity: the declaration genuinely drops a straight intent (the frames are not " +
                "two untouched trees)",
            authoringFrame.straightIntentDropped
        )

        // PREMISE 2 — each configuration alone opens exactly one solver window.
        inConfiguration(false) {
            assertEquals(1, probe(*declarations.toTypedArray()).build(ctx).limbSolverExecutions)
        }
        inConfiguration(true) {
            val pose = probe(*declarations.toTypedArray()).build(ctx)
            assertEquals("the build window contributes no realization", 0, pose.limbSolverExecutions)
            IkStage.apply(pose, def)
            assertEquals("the stage window is the frame's one realization", 1, pose.limbSolverExecutions)
        }

        // THE TRAP — both registered implementations realize the SAME build cycle. This is the
        // pre-§12.7a shape (an ungated authoring bake co-executing with the engine stage) driven
        // through the registered production path: the carrier is authored with the bake realizing,
        // then handed to the pipeline, whose stage realizes the same declared limbs again.
        var carrier: SkeletonPose? = null
        val violation = try {
            IK_STAGE_ACTIVE = false
            val authored = probe(*declarations.toTypedArray()).build(ctx) // realization #1 (bake window)
            carrier = authored
            assertEquals(1, authored.limbSolverExecutions)
            IK_STAGE_ACTIVE = true
            runCatching { SkeletonPipeline(def).produceFrame(authored) }.exceptionOrNull() // #2 (stage)
        } finally {
            IK_STAGE_ACTIVE = originalFlag
        }

        assertR5Violation(violation, "flag-ON: bake realization + stage realization in one build cycle")
        assertTrue(
            "the rejection must name the observed window count (2), i.e. the EVIDENCE failed the " +
                "frame: ${violation?.message}",
            violation?.message.orEmpty().contains("windows executed this frame = 2")
        )
        assertEquals(
            "the trap's evidence is per-execution: both declared limbs were realized twice in one " +
                "cycle (the rejected frame's counters are not consumed by the throwing check)",
            2, carrier!!.limbDuplicateRealizations
        )
        assertEquals(
            "…inside one cycle whose realized-limb set is still the declared set",
            setOf(Joint.ANKLE_F, Joint.ANKLE_B), realizedJoints(carrier!!)
        )

        // ANTI-VACUITY ANCHOR — the same declaration and the same code path is ACCEPTED when only
        // one implementation realizes it (both ways). The trap above rejects a co-execution, not
        // the declaration.
        inConfiguration(false) { SkeletonPipeline(def).produceFrame(probe(*declarations.toTypedArray()), ctx) }
        inConfiguration(true) { SkeletonPipeline(def).produceFrame(probe(*declarations.toTypedArray()), ctx) }
    }

    // ------------------------------------------- 3 — registration preserved, realization gated

    @Test
    fun registrationSurvivesTheGateWhileRealizationIsGated() {
        val declaration = honoredStraight(Joint.ANKLE_F, contact = ContactConstraint.ground(0f))

        inConfiguration(true) {
            val activated = probe(declaration).build(ctx)

            // (a) The declared intent reached the carrier intact — every realization input.
            assertEquals("the Limb Target is registered", 1, activated.limbTargets.size)
            val target = activated.limbTargets[0]
            assertEquals("declared limb", Joint.ANKLE_F, target.joint)
            assertEquals("declared target", bits(ROOT_PLUS_HONOR), bits(target.world))
            assertEquals("declared pole", bits(POLE), bits(target.pole))
            assertTrue("declared straight intent", target.straight)
            assertEquals("declared length1", def.thighLength.toRawBits(), target.length1.toRawBits())
            assertEquals("declared length2", def.shinLength.toRawBits(), target.length2.toRawBits())
            assertSame("declared constraint", def.legIKConstraint, target.constraint)
            assertEquals(
                "the Contact Declaration is registered in BOTH configurations (§12.5 acceptance)",
                1, activated.contacts.size
            )
            assertEquals(Joint.ANKLE_F, activated.contacts[0].endJoint)
            assertSame(def.legIKConstraint, activated.contacts[0].constraint)

            // (b) Realization effects did NOT run: no execution evidence, no stamp folds.
            assertEquals("no realization window", 0, activated.limbSolverExecutions)
            assertEquals("no realized limb", 0L, activated.limbRealizedLimbs)
            assertEquals("no duplicate execution", 0, activated.limbDuplicateRealizations)
            assertEquals("no clamp fold", 0f, activated.maxIkClampAmount, 0f)
            assertFalse("no straight-intent fold", activated.straightIntentDropped)

            // (c) No limb node geometry was written by the gated bake: the authored pre-solve
            // locals survive verbatim (raw bits).
            val pelvis = activated.roots.first { it.joint == Joint.PELVIS }
            assertEquals(
                "the gated bake must write no middle-joint geometry",
                bits(Vector3(def.thighLength, 0f, 0f)), bits(findNode(pelvis, Joint.KNEE_F)!!.localPosition)
            )
            assertEquals(
                "the gated bake must write no end-joint geometry",
                bits(Vector3(def.shinLength, 0f, 0f)), bits(findNode(pelvis, Joint.ANKLE_F)!!.localPosition)
            )

            // (d) Anti-vacuity: the SAME declaration in the authoring configuration DOES realize —
            // the locals above are the untouched authored values, not a value the solver would
            // have produced anyway.
            inConfiguration(false) {
                val authoring = probe(declaration).build(ctx)
                assertEquals(1, authoring.limbSolverExecutions)
                val authoringPelvis = authoring.roots.first { it.joint == Joint.PELVIS }
                assertNotEquals(
                    "the authoring bake replaces the authored limb locals (the gate is what " +
                        "withholds that write, not the fixture)",
                    bits(Vector3(def.thighLength, 0f, 0f)),
                    bits(findNode(authoringPelvis, Joint.KNEE_F)!!.localPosition)
                )
            }

            // (e) The activated frame still passes the pipeline's enforcement with the declared
            // contact settled, and the pipeline consumes exactly the per-frame execution evidence
            // (never the declared intent).
            val frame = probe(declaration).build(ctx)
            SkeletonPipeline(def).produceFrame(frame)
            assertEquals(
                "the pipeline consumes the frame's realization evidence (per-frame instrumentation)",
                0, frame.limbSolverExecutions
            )
            assertEquals(0, frame.limbDuplicateRealizations)
            assertEquals("the declared intent is not consumed by the pipeline", 1, frame.limbTargets.size)
        }
    }

    // ----------------------------------------------------------------- 4 — flag lifecycle (§12.7)

    @Test
    fun freshAndReusedBuildersReportTheCurrentBuildsReading() {
        inConfiguration(true) {
            // Fresh builder: build window registers, the stage produces THIS build's reading.
            val first = probe(droppedStraight(Joint.ANKLE_F))
            val built = first.build(ctx)
            assertFalse(
                "the activated build window carries no realization reading (registration only)",
                built.straightIntentDropped
            )
            SkeletonPipeline(def).produceFrame(built)
            assertTrue("the activated stage window produces the reading", built.straightIntentDropped)

            // A fresh builder never inherits anything.
            val fresh = probe(honoredStraight(Joint.ANKLE_F))
            val freshBuilt = fresh.build(ctx)
            assertFalse("a fresh builder opens with no dropped reading", freshBuilt.straightIntentDropped)
            SkeletonPipeline(def).produceFrame(freshBuilt)
            assertFalse("a fresh builder's honored straight limb publishes no drop", freshBuilt.straightIntentDropped)
        }

        inConfiguration(false) {
            val built = probe(droppedStraight(Joint.ANKLE_F)).build(ctx)
            assertTrue(
                "the authoring configuration produces the reading inside its own build window",
                built.straightIntentDropped
            )
            val fresh = probe(honoredStraight(Joint.ANKLE_F)).build(ctx)
            assertFalse("a fresh builder opens with no dropped reading", fresh.straightIntentDropped)
        }
    }

    @Test
    fun multipleLimbsInOneBuildMergeTheReading() {
        inConfiguration(true) {
            for ((label, declarations, expected) in listOf(
                Triple("one dropped of two", listOf(droppedStraight(Joint.ANKLE_F), honoredStraight(Joint.ANKLE_B)), true),
                Triple("order independent", listOf(honoredStraight(Joint.ANKLE_B), droppedStraight(Joint.ANKLE_F)), true),
                Triple("none dropped", listOf(honoredStraight(Joint.ANKLE_F), honoredStraight(Joint.ANKLE_B)), false),
                Triple("bent limbs cannot drop a straight intent", listOf(bent(Joint.ANKLE_F), bent(Joint.ANKLE_B)), false)
            )) {
                val pose = probe(*declarations.toTypedArray()).build(ctx)
                assertEquals("$label: all limbs declared", 2, pose.limbTargets.size)
                SkeletonPipeline(def).produceFrame(pose)
                assertEquals("$label: the build's reading is the OR across its limbs", expected, pose.straightIntentDropped)
            }
        }
    }

    @Test
    fun droppedReadingIsReArmedByTheNextBuild() {
        // The flag is current-build state, never historical state: a later build of the SAME
        // carrier that honours its straight intent must publish `false`.
        inConfiguration(true) {
            val builder = probe(droppedStraight(Joint.ANKLE_F))
            val dropped = builder.build(ctx)
            SkeletonPipeline(def).produceFrame(dropped)
            assertTrue("frame N drops the straight intent", dropped.straightIntentDropped)

            builder.declarations = listOf(honoredStraight(Joint.ANKLE_F))
            val honored = builder.build(ctx)
            assertFalse(
                "the rebuilt carrier must not open frame N+1 carrying frame N's dropped reading " +
                    "(the build-window re-arm is bookkeeping, not realization — it runs in BOTH " +
                    "configurations, §12.5 acceptance)",
                honored.straightIntentDropped
            )
            SkeletonPipeline(def).produceFrame(honored)
            assertFalse("the later successful build must not remain flagged", honored.straightIntentDropped)
        }

        inConfiguration(false) {
            val builder = probe(droppedStraight(Joint.ANKLE_F))
            assertTrue(builder.build(ctx).straightIntentDropped)
            builder.declarations = listOf(honoredStraight(Joint.ANKLE_F))
            assertFalse(
                "the authoring configuration re-arms the same way",
                builder.build(ctx).straightIntentDropped
            )
        }
    }

    @Test
    fun bentOnlyBuildDoesNotInheritAPreviousDrop() {
        inConfiguration(true) {
            val builder = probe(droppedStraight(Joint.ANKLE_F))
            SkeletonPipeline(def).produceFrame(builder.build(ctx))

            // A later build that authors no straight intent at all: there is nothing that could
            // have been dropped, so the published reading must be `false`.
            builder.declarations = listOf(bent(Joint.ANKLE_F), bent(Joint.ANKLE_B))
            val bentOnly = builder.build(ctx)
            assertFalse(
                "a build with no straight intent must not inherit the previous build's drop",
                bentOnly.straightIntentDropped
            )
            SkeletonPipeline(def).produceFrame(bentOnly)
            assertFalse("published state must be truthful for this build", bentOnly.straightIntentDropped)
        }
    }

    // --------------------------------------------------------------------------------- constants

    private companion object {
        /** Fixture root: the pelvis sits away from the origin so a target distance is meaningful. */
        val ROOT = Vector3(0f, 220f, 0f)

        /**
         * Straight requests inside the proximal bone (`dist < L1`): the engine's only honest
         * outcome is the bent fallback, which sets the Straight-Intent-Dropped reading.
         */
        val ROOT_PLUS_DROP = Vector3(0f, 220f - 56f, 0f)

        /**
         * Straight requests between the proximal bone and the constraint's extended reach
         * (`L1 < dist <= 0.9 * (L1 + L2)` on `DEFAULT_ADULT`): deliverable, so no drop.
         */
        val ROOT_PLUS_HONOR = Vector3(0f, 220f - 170f, 0f)

        val POLE = Vector3(0f, 0f, 1f)
    }
}
