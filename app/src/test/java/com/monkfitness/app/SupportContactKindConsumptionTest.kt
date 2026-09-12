package com.monkfitness.app

import com.monkfitness.app.animation.*
import com.monkfitness.app.poses.IsometricSidePlankPose
import com.monkfitness.app.poses.PikePushUpPose
import com.monkfitness.app.poses.StandardPushUpPose
import com.monkfitness.app.poses.StaticForearmPlankPose
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max

/**
 * **B-3 — the declared contact KIND is consumed by the extremity derivation.**
 *
 * The Finalizer orients each extremity against the surface of the support contact the pose declared
 * (`SkeletonPoseFinalizer` → `isSupported` + `supportPlaneNormalFor`). That resolution used to ask
 * ONLY for the `*_FOOT` / `*_HAND` family member, so a pose that declared its planted contact as
 * `*_TOES` (the whole plank/push-up family) or `*_FOREARM` (a forearm plank) resolved to `null`
 * support, produced no support plane, and the extremity was never oriented against its surface
 * (`adjustFootOrientation` / `adjustHandOrientation` both returned with the neutral derivation).
 *
 * The fix resolves the extremity's declared support over its **declaration family** — the declared
 * [SupportPoint] itself is used verbatim, never aliased to another kind: `*_TOES` and `*_FOOT` name
 * the same physical support in the engine's own contact→joint map (`contactJointsFor` maps both to
 * the identical `{ankle, heel, toe}` triple), exactly as `*_FOREARM` does for a forearm-planted
 * hand. No side convention is touched (that is the separate, still-open B-4 finding).
 *
 * ## RED evidence (measured on `origin/main` `dc4cc27`, published frames)
 *
 * * `StandardPushUpPose` (declares `LEFT_TOES`/`RIGHT_TOES`): `TOE_F − ANKLE_F = +17.57`,
 *   `HEEL_F − ANKLE_F = −7.18` at every sampled progress — the declared floor contact floated
 *   above its surface and pointed upward, while only the `*_HAND`-declared hands reached the plane.
 *   Post-fix: `0.000` for both feet at every sampled progress.
 * * The same shape for `Wide`/`Military`/`Diamond`/`Decline` push-ups.
 * * A `*_FOREARM`-only declaration left the hand unoriented (`FINGERTIPS − HAND` off-plane) because
 *   the hand path asked only for `*_HAND`; post-fix the hand is laid in its support plane.
 *
 * The kind-sensitivity controls at the bottom are what makes these assertions meaningful: the same
 * pose with the declaration REMOVED must keep its off-plane geometry (pre-fix behaviour), so a fix
 * that simply flattened every extremity unconditionally — or a regression that stops consuming the
 * new kinds again — fails here.
 */
class SupportContactKindConsumptionTest {

    private val def = SkeletonDefinition.DEFAULT_ADULT
    private val progressValues = listOf(0.0f, 0.25f, 0.5f, 0.75f, 1.0f)

    /** Tolerance for "in the support plane": the derivation projects the extremity onto the plane. */
    private val inPlane = 0.5f
    /** Threshold for "still off-plane" — an order of magnitude above the projected residual. */
    private val offPlane = 5f

    private fun context(progress: Float) = PoseContext(
        progress = progress, side = Side.RIGHT, definition = def,
        deltaTime = 0.0166f, cycleDuration = 2500f
    )

    /** Published frame, snapshotted by value (the pipeline's frame buffer is reused). */
    private fun frame(pose: PoseBuilder, p: Float, pipeline: SkeletonPipeline = SkeletonPipeline(def)): SkeletonPose =
        SkeletonPose().apply { copyFrom(pipeline.produceFrame(pose, context(p)).pose) }

    private fun declaredKinds(builder: PoseBuilder): List<SupportPoint> =
        builder.metadata.support.contacts.map { it.point }.sortedBy { it.name }

    /** Max |heel/toe − ankle| over both feet: 0 means the foot lies in its support plane. */
    private fun footOffPlane(pose: SkeletonPose): Float = max(
        max(
            abs(pose.getJoint(Joint.TOE_F).y - pose.getJoint(Joint.ANKLE_F).y),
            abs(pose.getJoint(Joint.HEEL_F).y - pose.getJoint(Joint.ANKLE_F).y)
        ),
        max(
            abs(pose.getJoint(Joint.TOE_B).y - pose.getJoint(Joint.ANKLE_B).y),
            abs(pose.getJoint(Joint.HEEL_B).y - pose.getJoint(Joint.ANKLE_B).y)
        )
    )

    /** Max |palm/fingertips − hand| over both hands: 0 means the hand lies in its support plane. */
    private fun handOffPlane(pose: SkeletonPose): Float = max(
        max(
            abs(pose.getJoint(Joint.PALM_A).y - pose.getJoint(Joint.HAND_A).y),
            abs(pose.getJoint(Joint.FINGERTIPS_A).y - pose.getJoint(Joint.HAND_A).y)
        ),
        max(
            abs(pose.getJoint(Joint.PALM_P).y - pose.getJoint(Joint.HAND_P).y),
            abs(pose.getJoint(Joint.FINGERTIPS_P).y - pose.getJoint(Joint.HAND_P).y)
        )
    )

    // ------------------------------------------------------------------
    // B-3 #5 — a *_TOES declaration is consumed by the foot derivation.
    // ------------------------------------------------------------------

    /**
     * The push-up family declares its planted foot as TOES and authors no ankle articulation, so the
     * derivation's own result (the foot lying in the declared support plane) is directly observable.
     * `PikePushUpPose` is deliberately excluded: it authors a plantar-flexion articulation, which is
     * composed AFTER the projection by design (see [authoredAnkleArticulationIsNotOverridden]).
     */
    @Test
    fun toesDeclaredFeetAreOrientedAgainstTheirSupportPlane() {
        val toesDeclared = listOf(
            StandardPushUpPose(),
            MotionProbe.build("WidePushUpPose"),
            MotionProbe.build("MilitaryPushUpPose"),
            MotionProbe.build("DiamondPushUpPose"),
            MotionProbe.build("DeclinePushUpPose")
        )
        for (builder in toesDeclared) {
            val kinds = declaredKinds(builder)
            assertTrue(
                "${builder.javaClass.simpleName} must declare its feet as TOES (the kind under test): $kinds",
                kinds.contains(SupportPoint.LEFT_TOES) && kinds.contains(SupportPoint.RIGHT_TOES)
            )
            val pipeline = SkeletonPipeline(def)
            for (p in progressValues) {
                val off = footOffPlane(frame(builder, p, pipeline))
                assertTrue(
                    "${builder.javaClass.simpleName} p=$p: a *_TOES-declared foot must lie in its " +
                        "declared support plane, but heel/toe deviate ${off}u from the ankle " +
                        "(pre-fix: 17.57u — the TOES declaration was never consulted)",
                    off <= inPlane
                )
            }
        }
    }

    /** A `*_FOOT` declaration resolves through the same family, i.e. the kind is a family, not a branch. */
    @Test
    fun footDeclaredFeetResolveThroughTheSameFamily() {
        val builder: PoseBuilder = PushUpWithDeclaredSupport(
            setOf(SupportContact.LEFT_FOOT, SupportContact.RIGHT_FOOT)
        )
        val pipeline = SkeletonPipeline(def)
        for (p in progressValues) {
            val off = footOffPlane(frame(builder, p, pipeline))
            assertTrue("*_FOOT-declared feet must be oriented against their plane ($off)", off <= inPlane)
        }
    }

    // ------------------------------------------------------------------
    // B-3 #6 — a *_FOREARM declaration is consumed by the hand derivation.
    // ------------------------------------------------------------------

    /**
     * A forearm-planted pose declares `*_FOREARM` (the hand at the end of the planted forearm is the
     * extremity this derivation orients). The fixture is the production push-up with the declaration
     * swapped to the forearm kind and the hand/palm chain in an out-of-plane state, so the
     * consumption is observable as geometry: pre-fix the hand path found no `*_HAND` declaration and
     * left the hand following the forearm (fingertips off-plane); post-fix the hand lies in its
     * declared support plane.
     */
    @Test
    fun forearmDeclaredHandsAreOrientedAgainstTheirSupportPlane() {
        val builder: PoseBuilder = PushUpWithDeclaredSupport(
            setOf(SupportContact.LEFT_FOREARM, SupportContact.RIGHT_FOREARM)
        )
        assertTrue(
            "the fixture declares the forearm kind (not the hand kind)",
            declaredKinds(builder) == listOf(SupportPoint.LEFT_FOREARM, SupportPoint.RIGHT_FOREARM)
        )
        val pipeline = SkeletonPipeline(def)
        for (p in progressValues) {
            val off = handOffPlane(frame(builder, p, pipeline))
            assertTrue(
                "p=$p: a *_FOREARM-declared hand must lie in its declared support plane, but " +
                    "palm/fingertips deviate ${off}u from the hand (pre-fix: the FOREARM kind was " +
                    "never consulted, so the hand followed the forearm direction)",
                off <= inPlane
            )
        }
    }

    // ------------------------------------------------------------------
    // The controls: the orientation is DRIVEN by the declaration.
    // ------------------------------------------------------------------

    /** With the declaration removed the extremity keeps its off-plane geometry (pre-fix behaviour). */
    @Test
    fun removingTheFootDeclarationLeavesTheFootOffPlane() {
        val builder: PoseBuilder = PushUpWithDeclaredSupport(emptySet())
        val pipeline = SkeletonPipeline(def)
        var sawOffPlane = false
        for (p in progressValues) {
            val off = footOffPlane(frame(builder, p, pipeline))
            if (off > offPlane) sawOffPlane = true
        }
        assertTrue(
            "without a foot declaration the derivation has no support plane to use — the foot must " +
                "keep its neutral (off-plane) orientation, otherwise the *_TOES assertion above " +
                "would pass for a reason unrelated to the declaration",
            sawOffPlane
        )
    }

    @Test
    fun removingTheForearmDeclarationLeavesTheHandOffPlane() {
        val builder: PoseBuilder = PushUpWithDeclaredSupport(emptySet())
        val pipeline = SkeletonPipeline(def)
        var sawOffPlane = false
        for (p in progressValues) {
            val off = handOffPlane(frame(builder, p, pipeline))
            if (off > offPlane) sawOffPlane = true
        }
        assertTrue(
            "without a hand/forearm declaration the hand must keep following the forearm " +
                "(off-plane); pre-fix this was also the state of every *_FOREARM-declaring pose",
            sawOffPlane
        )
    }

    /**
     * The declared support plane is the BASE direction the derivation orients; a pose-authored ankle
     * articulation is composed on top of it (never overridden). `PikePushUpPose` authors plantar
     * flexion through the §1.3 extremity-articulation carrier, so its foot must stay pitched rather
     * than being flattened by the new TOES consumption.
     */
    @Test
    fun authoredAnkleArticulationIsNotOverridden() {
        val pike = PikePushUpPose()
        assertTrue(
            "PikePushUp must declare TOES (it is the case this guard protects)",
            declaredKinds(pike).contains(SupportPoint.LEFT_TOES)
        )
        val pipeline = SkeletonPipeline(def)
        var sawPitch = false
        for (p in progressValues) {
            if (footOffPlane(frame(pike, p, pipeline)) > offPlane) sawPitch = true
        }
        assertTrue(
            "an authored ankle articulation must survive the support-plane derivation",
            sawPitch
        )
    }

    // ------------------------------------------------------------------
    // B-3 #7 — the declared kinds stay semantically distinct (never aliased).
    // ------------------------------------------------------------------

    @Test
    fun declaredContactKindsSurviveVerbatimOnThePublishedFrame() {
        val pushUp = StandardPushUpPose()
        val pushUpPublished = frame(pushUp, 0.5f).supportedPoints.toSet()
        assertEquals(
            "the push-up's TOES declaration must be published verbatim (not rewritten to FOOT)",
            setOf(
                SupportPoint.LEFT_HAND, SupportPoint.RIGHT_HAND,
                SupportPoint.LEFT_TOES, SupportPoint.RIGHT_TOES
            ),
            pushUpPublished
        )
        assertTrue(
            "TOES and FOOT must remain distinct values in the model",
            SupportPoint.LEFT_TOES != SupportPoint.LEFT_FOOT
        )

        val forearmPlankPublished = frame(StaticForearmPlankPose(), 0.5f).supportedPoints.toSet()
        assertEquals(
            "the forearm plank's FOREARM declaration must be published verbatim (not rewritten to HAND)",
            setOf(
                SupportPoint.LEFT_FOREARM, SupportPoint.RIGHT_FOREARM,
                SupportPoint.LEFT_TOES, SupportPoint.RIGHT_TOES
            ),
            forearmPlankPublished
        )

        val sidePlankPublished = frame(IsometricSidePlankPose(), 0.5f).supportedPoints.toSet()
        assertEquals(
            "the side plank's forearm + foot declaration must be published verbatim",
            setOf(SupportPoint.RIGHT_FOREARM, SupportPoint.RIGHT_FOOT),
            sidePlankPublished
        )
    }

    /**
     * A production pose with its support DECLARATION replaced — the kind-sensitivity control rig.
     *
     * It delegates `build()` to the real production pose (production geometry, production authoring
     * path) and varies exactly one thing: `metadata.support.contacts`. So a difference in the
     * published frame can only come from the declaration, which is what these tests must isolate.
     */
    private class PushUpWithDeclaredSupport(
        contacts: Set<SupportContact>
    ) : PoseBuilder by StandardPushUpPose() {
        override val metadata = StandardPushUpPose().metadata.copy(
            support = SupportDefinition(pivot = PivotType.FEET, contacts = contacts)
        )
    }
}
