package com.monkfitness.app

import com.monkfitness.app.animation.*
import com.monkfitness.app.poses.PoseRegistry
import com.monkfitness.app.validation.poses.DeadHangPose
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Branch C (RFC_BRANCH_C_EXTREMITY_ARTICULATION) — §1.3 Interaction / Articulation Intent.
 *
 * This pins the RFC §11 acceptance tests:
 *  - (a) carrier -> derived geometry is byte-identical to the legacy node-read path: every
 *    migrated pose records `extremityArticulations`; clearing the carrier (leaving the node
 *    rotation, which the Finalizer falls back to) renders identically.
 *  - (b) the MANUAL_OVERRIDE opt-out is real: an extremity opted out preserves its authored
 *    endpoint geometry instead of being engine-derived.
 *  - (c) the 2-DOF wrist composer combines flexion + deviation exactly (the composed rotation is
 *    not a single dropped axis).
 *
 * ## B-5 — why this suite compares two runs of the SAME production entry point
 *
 * The carrier/legacy-node equivalence is only a freeze guard if the two legs differ in EXACTLY ONE
 * thing: the carrier. The previous form built the reference leg on a different entry point
 * (`produceFrame(built, environment, supportedPoints)`) and reconstructed the support declaration
 * in test code, because that overload's Frame Context (§5 R8) was caller-supplied and defaulted to
 * empty (B-5). Three consequences made it a false freeze guard:
 *  1. the reference leg was a hand-assembled second path, not the production path;
 *  2. its runtime context was a test-local COPY of the pipeline's derivation — a parallel source of
 *     truth that a production change could silently desync;
 *  3. an omitted default silently rendered pre-B-3 geometry (no support plane ⇒ no extremity
 *     orientation) — the exact "freeze guard whose reference leg never receives the new input" trap.
 *
 * Both legs now run the production PoseBuilder entry point ([SkeletonPipeline.produceFrame]), on
 * independently constructed instances of the same pose, so the pipeline resolves the Frame Context
 * from the SAME declaration for both legs — that equality is ASSERTED below, not assumed. The only
 * difference is the carrier: the reference leg's builder clears it inside `build`, exactly where a
 * pose authors it.
 */
class ExtremityArticulationTest {

    private val def = SkeletonDefinition.DEFAULT_ADULT

    private val progresses = listOf(0f, 0.5f, 1f)

    /**
     * The carrier-cleared reference leg. Delegating [PoseBuilder] that clears the Branch-C carrier
     * immediately after the pose authored it, so the Finalizer must take its documented
     * compatibility path — the legacy wrist/ankle node read ([SkeletonPoseFinalizer.articulationFor]
     * fallback) — on a pose that is otherwise bit-for-bit the production authoring.
     */
    private class CarrierCleared(private val inner: PoseBuilder) : PoseBuilder by inner {
        override fun build(context: PoseContext): SkeletonPose =
            inner.build(context).also { it.extremityArticulations.clear() }
    }

    /**
     * Every production pose that authors the Branch-C carrier, derived from the production registry
     * (never a hand-list): an id whose registered builder populates `extremityArticulations`.
     * [migratedCorpusIsExactlyTheCarrierPopulatedProductionPoses] pins the membership so a newly
     * migrated pose cannot silently escape the equivalence guard below.
     */
    private fun carrierPopulatedIds(): List<String> =
        PoseRegistry.getDedicatedAnimationIds().sorted().filter { id ->
            val builder = PoseRegistry.getPoseConfig(id)?.builder ?: return@filter false
            builder.build(PoseContext(0.5f, Side.LEFT, def)).extremityArticulations.isNotEmpty()
        }

    /** A FRESH instance per leg (registry builders are shared, reused singletons). */
    private fun freshBuilder(id: String): PoseBuilder =
        PoseRegistry.getPoseConfig(id)!!.builder.javaClass.getDeclaredConstructor().newInstance()

    private fun migratedCorpus(): List<Pair<String, () -> PoseBuilder>> =
        carrierPopulatedIds().map { id -> id to { freshBuilder(id) } }

    private fun maxDeviation(a: SkeletonPose, b: SkeletonPose): Float = worstJoint(a, b).first

    /**
     * The Frame Context (§5 R8) of a published frame, in a value-comparable form.
     * `EnvironmentDefinition` embeds `Vector3` instances that carry no value equality, so a plain
     * data-class compare would call two structurally identical environments different; the rendered
     * form is the value projection of every field the Finalizer reads.
     */
    private fun frameContextOf(pose: SkeletonPose): Pair<String, Set<SupportPoint>> =
        pose.environment.toString() to pose.supportedPoints.toSet()

    private fun worstJoint(a: SkeletonPose, b: SkeletonPose): Pair<Float, String> {
        var max = 0f; var worst = "-"
        for (j in Joint.entries) {
            val pa = a.getJoint(j); val pb = b.getJoint(j)
            val d = maxOf(abs(pa.x - pb.x), abs(pa.y - pb.y), abs(pa.z - pb.z))
            if (d > max) { max = d; worst = j.name }
        }
        return max to worst
    }

    @Test
    fun migratedCorpusIsExactlyTheCarrierPopulatedProductionPoses() {
        assertEquals(
            "the Branch-C equivalence guard must cover EVERY migrated production pose " +
                "(carrier-authoring pose), derived from the production registry",
            listOf(
                "chinup_standard", "dead_hang", "hamstring_stretch_hold", "pike_pushup_standard",
                "pullup_neutral", "pullup_standard", "pullup_wide", "scapular_pullup_deadhang",
                "squat_jump", "thoracic_extension_reps", "world_greatest_stretch"
            ),
            carrierPopulatedIds()
        )
    }

    @Test
    fun carrierIsPopulatedByArticulatingPoses() {
        for ((name, factory) in migratedCorpus()) {
            val pose = factory().build(PoseContext(0.5f, Side.LEFT, def))
            assertTrue(
                "$name must populate extremityArticulations (Branch C carrier live) got=${pose.extremityArticulations.size}",
                pose.extremityArticulations.isNotEmpty()
            )
        }
    }

    /**
     * (a) For every migrated production pose: same progress, same [PoseContext], same authored pose
     * state, carrier enabled vs carrier cleared ⇒ equivalent published geometry.
     */
    @Test
    fun carrierReproducesTheLegacyNodePathForEveryMigratedPose() {
        val corpus = migratedCorpus()
        assertEquals("corpus must not shrink silently", 11, corpus.size)
        for ((name, factory) in corpus) {
            for (p in progresses) {
                val ctx = PoseContext(p, Side.LEFT, def)

                val carrierLeg = factory()
                val withCarrier = SkeletonPipeline(def).produceFrame(carrierLeg, ctx).pose

                val nodeLeg = CarrierCleared(factory())
                val withoutCarrier = SkeletonPipeline(def).produceFrame(nodeLeg, ctx).pose

                // The ONLY intended difference between the legs is the carrier: both ran the same
                // production entry point over the same declaration, so §5 R8 resolved the same
                // Frame Context for both. Asserted, not assumed.
                assertEquals(
                    "$name @$p Frame Context (environment + support model)",
                    frameContextOf(withCarrier), frameContextOf(withoutCarrier)
                )
                // Non-vacuity: the legs really are "carrier on" vs "carrier off", and they are
                // distinct frames published from independently authored, independently finalized
                // carriers — not one reused mutable object compared with itself.
                assertTrue(
                    "$name @$p: the carrier leg must publish the authored carrier",
                    withCarrier.extremityArticulations.isNotEmpty()
                )
                assertTrue(
                    "$name @$p: the reference leg must publish an EMPTY carrier (node-read path)",
                    withoutCarrier.extremityArticulations.isEmpty()
                )
                assertNotSame("$name @$p: the two legs must be distinct published frames", withCarrier, withoutCarrier)
                assertTrue(
                    "$name @$p: the two legs must not share a reused node tree",
                    withCarrier.roots !== withoutCarrier.roots
                )

                val (dev, joint) = worstJoint(withCarrier, withoutCarrier)
                assertEquals(
                    "$name @$p carrier must reproduce the node-read path " +
                        "(maxDev=$dev at $joint, tolerance 1e-4)",
                    0f, dev, 1e-4f
                )
            }
        }
    }

    /**
     * (B) Sensitivity/NON-VACUITY control for the equivalence above: the SAME comparison, run
     * against a reference leg that receives a DIFFERENT runtime context (the pre-B-5 renderer shape:
     * the declaration is not supplied, so the pose finalizes against an empty support model),
     * reports a real difference for every pose whose extremity derivation consumes its declaration.
     * That proves the assertion above is capable of failing — it is not blind to a changed input.
     */
    @Test
    fun carrierComparisonDetectsADroppedSupportDeclaration() {
        val detected = ArrayList<String>()
        for ((name, factory) in migratedCorpus()) {
            for (p in progresses) {
                val ctx = PoseContext(p, Side.LEFT, def)
                val withCarrier = SkeletonPipeline(def).produceFrame(factory(), ctx).pose

                val declaration = factory().metadata.support
                if (declaration.supportPoints.isEmpty()) continue

                // Reference leg WITHOUT the declaration (the defect's shape): carrier cleared AND
                // no support model supplied. A freshly authored pose carries none, so the pipeline
                // injects the empty model — exactly what B-5 produced on the renderer path.
                val builder = CarrierCleared(factory())
                val built = builder.build(ctx)
                val withoutDeclaration = SkeletonPipeline(def)
                    .produceFrame(built, builder.metadata.environment).pose

                val (dev, joint) = worstJoint(withCarrier, withoutDeclaration)
                if (dev > 1e-4f) detected.add("$name@$p:$joint=$dev")
            }
        }
        assertTrue(
            "the equivalence assertion must be able to detect a runtime-context difference — " +
                "a declaration-consuming pose published identical geometry with and without its " +
                "Support Declaration, so the comparison is blind (detected=$detected)",
            detected.isNotEmpty()
        )
    }

    @Test
    fun manualOverridePreservesAuthoredEndpoints() {
        // DeadHang authors an overhand grip via the carrier; opting HAND_A into MANUAL_OVERRIDE
        // must leave the authored palm/fingertips geometry untouched (the derivation is skipped).
        //
        // P12 WP-I: the "authored endpoint" reference is the FK value on the produced frame's node
        // tree — `PALM_A` is a carrier-only extremity-derived joint, so the derivation writes the
        // FLAT CARRIER and never the node. Reading it from the half-built carrier instead (as the
        // pre-activation form did) is no longer the frame: under state 3 `build()` registers limb
        // intent and the engine-owned stage realizes the limb (§12.7a). The claim — the opt-out
        // preserves the authored endpoint verbatim — is unchanged and is now asserted on the
        // published frame of the production path.
        val ctx = PoseContext(0.5f, Side.LEFT, def)
        val built = DeadHangPose().build(ctx)
        built.overrideExtremityOrientation(Extremity.HAND_A)
        val out = SkeletonPipeline(def).produceFrame(built).pose

        assertTrue(
            "the opt-out must be recorded on the frame the engine reads",
            out.extremityOverrides.contains(Extremity.HAND_A)
        )
        val authored = palmNodeWorld(out)
        val published = out.getJoint(Joint.PALM_A)
        assertEquals("opt-out must preserve authored PALM_A.x", authored.x, published.x, 1e-3f)
        assertEquals("opt-out must preserve authored PALM_A.y", authored.y, published.y, 1e-3f)
        assertEquals("opt-out must preserve authored PALM_A.z", authored.z, published.z, 1e-3f)
        // Every carrier-only endpoint of the opted-out hand is preserved, not just the palm.
        assertAuthoredEndpointsPreserved(out, listOf(Joint.PALM_A, Joint.KNUCKLES_A, Joint.FINGERTIPS_A))

        // Control (non-vacuity): the SAME frame with the hand auto-owned IS derived — the carrier
        // value departs from the authored FK value — so the preservation above is a real opt-out
        // effect and not a derivation that happens to be a no-op.
        val derivedFrame = SkeletonPipeline(def).produceFrame(DeadHangPose(), ctx).pose
        val derivedAuthored = palmNodeWorld(derivedFrame)
        val derivedPublished = derivedFrame.getJoint(Joint.PALM_A)
        val shift = abs(derivedPublished.x - derivedAuthored.x) +
            abs(derivedPublished.y - derivedAuthored.y) +
            abs(derivedPublished.z - derivedAuthored.z)
        assertTrue(
            "control: an auto-owned hand must be engine-derived (observed authored->carrier shift=$shift)",
            shift > 1e-3f
        )
        // ... and the derived carrier of that same hand is NOT the authored endpoint set, so the
        // opted-out frame above is a genuine opt-out, not an identity-preserving derivation.
        assertEquals(
            "control: the opted-out frame must differ from the auto-derived frame",
            false,
            sameEndpoints(out, derivedFrame, listOf(Joint.PALM_A, Joint.KNUCKLES_A, Joint.FINGERTIPS_A))
        )
    }

    private fun assertAuthoredEndpointsPreserved(pose: SkeletonPose, joints: List<Joint>) {
        for (j in joints) {
            val node = nodeWorld(pose, j)
            val published = pose.getJoint(j)
            assertEquals("opt-out must preserve authored ${j.name}.x", node.x, published.x, 1e-3f)
            assertEquals("opt-out must preserve authored ${j.name}.y", node.y, published.y, 1e-3f)
            assertEquals("opt-out must preserve authored ${j.name}.z", node.z, published.z, 1e-3f)
        }
    }

    private fun sameEndpoints(a: SkeletonPose, b: SkeletonPose, joints: List<Joint>): Boolean =
        joints.all { j ->
            val pa = a.getJoint(j); val pb = b.getJoint(j)
            abs(pa.x - pb.x) <= 1e-3f && abs(pa.y - pb.y) <= 1e-3f && abs(pa.z - pb.z) <= 1e-3f
        }

    /** World position of the `PALM_A` node on a produced frame's tree (the authored FK value). */
    private fun palmNodeWorld(pose: SkeletonPose): Vector3 = nodeWorld(pose, Joint.PALM_A)

    /** World position of [joint]'s node on a produced frame's tree (the authored FK value). */
    private fun nodeWorld(pose: SkeletonPose, joint: Joint): Vector3 {
        fun find(node: SkeletonNode): SkeletonNode? {
            if (node.joint == joint) return node
            for (c in node.children) {
                find(c)?.let { return it }
            }
            return null
        }
        for (root in pose.roots) {
            find(root)?.let { return it.worldPosition }
        }
        error("the produced frame must carry a ${joint.name} node")
    }

    @Test
    fun wristComposerCombinesTwoDofExactly() {
        // buildWristRotation(flexion, deviation) must equal Rz(flexion) then Ry(deviation), not a
        // single dropped axis. Compose and compare against the explicit two-step rotation.
        val r = JointRotation()
        SkeletonMath.buildWristRotation(0.4f, 0.25f, r)
        val expected = JointRotation()
        SkeletonMath.buildWristRotation(0f, 0.25f, expected)
        val flex = JointRotation(Vector3(0f, 0f, 1f), 0.4f)
        SkeletonMath.composeRotations(flex, expected, expected)
        assertEquals(expected.axis.x, r.axis.x, 1e-4f)
        assertEquals(expected.axis.y, r.axis.y, 1e-4f)
        assertEquals(expected.axis.z, r.axis.z, 1e-4f)
        assertEquals(expected.angle, r.angle, 1e-4f)

        // Non-vacuity: the composed 2-DOF rotation is a genuinely combined result — it equals
        // neither of its single-DOF components, so a dropped axis could not pass the pin above.
        val flexionOnly = JointRotation()
        SkeletonMath.buildWristRotation(0.4f, 0f, flexionOnly)
        val deviationOnly = JointRotation()
        SkeletonMath.buildWristRotation(0f, 0.25f, deviationOnly)
        assertTrue(
            "composed 2-DOF wrist rotation must differ from the flexion-only rotation",
            !sameRotation(r, flexionOnly)
        )
        assertTrue(
            "composed 2-DOF wrist rotation must differ from the deviation-only rotation",
            !sameRotation(r, deviationOnly)
        )
    }

    private fun sameRotation(a: JointRotation, b: JointRotation): Boolean =
        abs(a.axis.x - b.axis.x) <= 1e-4f && abs(a.axis.y - b.axis.y) <= 1e-4f &&
            abs(a.axis.z - b.axis.z) <= 1e-4f && abs(a.angle - b.angle) <= 1e-4f
}
