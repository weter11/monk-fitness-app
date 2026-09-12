package com.monkfitness.app

import com.monkfitness.app.animation.PivotType
import com.monkfitness.app.animation.PoseBuilder
import com.monkfitness.app.animation.PoseContext
import com.monkfitness.app.animation.Side
import com.monkfitness.app.animation.SkeletonDefinition
import com.monkfitness.app.animation.SkeletonPipeline
import com.monkfitness.app.animation.SkeletonPose
import com.monkfitness.app.animation.SupportContact
import com.monkfitness.app.animation.SupportDefinition
import com.monkfitness.app.animation.SupportMath
import com.monkfitness.app.animation.SupportPoint
import com.monkfitness.app.animation.Joint
import com.monkfitness.app.poses.AlternatingBirdDogPose
import com.monkfitness.app.poses.BirdDogPose
import com.monkfitness.app.poses.IsometricSidePlankPose
import com.monkfitness.app.poses.PoseRegistry
import com.monkfitness.app.poses.StandardPushUpPose
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max

/**
 * **B-4 — the declared support contact is consumed on the side it names, through the production
 * published path.**
 *
 * Every assertion here is measured on the frame the production pipeline publishes (`SkeletonPose`
 * snapshotted by value), never on a helper production does not call. The observable is the support
 * plane's effect on the extremity: when an extremity's declared support resolves, its heel/toe (or
 * palm/fingertips) are projected into that plane; when it does not, the extremity keeps the neutral
 * derivation and the heel/toe deviate from the ankle (`17.572` units on this geometry — the same
 * value B-3 measured for an undeclared foot).
 *
 * ## RED evidence (measured on `origin/main` `fa81cb6`, published frames)
 *
 * | case | pre-fix | post-fix |
 * | --- | --- | --- |
 * | push-up, declares `LEFT_TOES` only | `devF = 17.572` (LEFT foot **not** flattened) · `devB = 0.000` (**RIGHT** foot flattened) | `devF = 0.000` · `devB = 17.572` |
 * | push-up, declares `RIGHT_TOES` only | `devF = 0.000` · `devB = 17.572` | `devF = 17.572` · `devB = 0.000` |
 * | `side_plank_standard` (declares `RIGHT_FOOT`, planted foot = B) | `HEEL_B y = 7.82`, `TOE_B y = 32.57`, `devB = 17.572` — the planted foot was left un-flattened while the TOP foot claimed the declaration | `HEEL_B = TOE_B = ANKLE_B = 15.00`, `devB = 0.000` |
 * | whole corpus (49 pose classes × 5 progress × every joint) | — | **only `side_plank_standard` changed, and only its `HEEL_B`/`TOE_B`** |
 *
 * The `side_plank_standard` row's `17.572` is that measurement on the pose **as it then was**: B3
 * (`fix/b3-sideplank-knee-plane`, off `2fb6079`) re-authors that pose's support leg so its residual
 * knee bend leaves the mat, and the engine's neutral derivation for the re-authored leg already lies
 * in the plane (`devB = 0.0000` for the declaration-free twin) — the row's post-fix outcome, the
 * planted foot in its own plane, is unchanged (the production pose's `HEEL_B`/`TOE_B` move by `< 3e-5`
 * through the correction).
 *
 * The hand path is the internal cross-check: it already followed the canonical convention
 * (`LEFT_HAND` flattened the A hand pre-fix), so the pre-fix tree flattened the LEFT hand and the
 * RIGHT foot for the same "LEFT…" declaration — the contradiction these tests now forbid.
 */
class SupportPointSideConsumptionTest {

    private val def = SkeletonDefinition.DEFAULT_ADULT
    private val progressValues = listOf(0.0f, 0.25f, 0.5f, 0.75f, 1.0f)

    /** Tolerance for "lies in its declared support plane" (the derivation projects the extremity). */
    private val inPlane = 0.5f
    /** Threshold for "still off-plane" — an order of magnitude above the projected residual. */
    private val offPlane = 5f

    private fun context(progress: Float, side: Side = Side.RIGHT) = PoseContext(
        progress = progress, side = side, definition = def,
        deltaTime = 0.0166f, cycleDuration = 2500f
    )

    /** Published frame, snapshotted by value (the pipeline reuses its frame buffer). */
    private fun frame(
        pose: PoseBuilder,
        p: Float,
        side: Side = Side.RIGHT,
        pipeline: SkeletonPipeline = SkeletonPipeline(def)
    ): SkeletonPose = SkeletonPose().apply { copyFrom(pipeline.produceFrame(pose, context(p, side)).pose) }

    private fun footDeviationF(pose: SkeletonPose): Float = deviation(pose, Joint.ANKLE_F, Joint.HEEL_F, Joint.TOE_F)
    private fun footDeviationB(pose: SkeletonPose): Float = deviation(pose, Joint.ANKLE_B, Joint.HEEL_B, Joint.TOE_B)
    private fun handDeviationA(pose: SkeletonPose): Float = deviation(pose, Joint.HAND_A, Joint.PALM_A, Joint.FINGERTIPS_A)
    private fun handDeviationP(pose: SkeletonPose): Float = deviation(pose, Joint.HAND_P, Joint.PALM_P, Joint.FINGERTIPS_P)

    /** Max |contact-layer joint − anchor| on Y: 0 means the extremity lies in its support plane. */
    private fun deviation(pose: SkeletonPose, anchor: Joint, vararg layer: Joint): Float {
        val y = pose.getJoint(anchor).y
        var worst = 0f
        for (joint in layer) worst = max(worst, abs(pose.getJoint(joint).y - y))
        return worst
    }

    // ------------------------------------------------------------------
    // B.1 — the side decision, on a production pose whose declaration varies per side
    // ------------------------------------------------------------------

    /**
     * The decisive case: a real production push-up whose declared foot contact is narrowed to ONE
     * side. The declared side's foot must be the one flattened, for BOTH kinds (`*_TOES` and
     * `*_FOOT`) — and the arm path must agree with it in the same frame, so "LEFT" cannot mean one
     * limb family for a hand and the opposite for a foot.
     *
     * This single test fails on the pre-fix tree on all four foot scenarios (the declared side was
     * flattened on the opposite foot) while the two hand scenarios pass there — which is exactly the
     * contradiction B-4 removed.
     */
    @Test
    fun aOneSidedDeclarationFlattensThatSideForBothArmsAndLegs() {
        val footCases = listOf(
            // declared contacts                    declared side is F (leg LEFT / leg B RIGHT)
            Triple(setOf(SupportContact.LEFT_TOES), true, "LEFT_TOES"),
            Triple(setOf(SupportContact.RIGHT_TOES), false, "RIGHT_TOES"),
            Triple(setOf(SupportContact.LEFT_FOOT), true, "LEFT_FOOT"),
            Triple(setOf(SupportContact.RIGHT_FOOT), false, "RIGHT_FOOT")
        )
        for ((contacts, declaredIsF, label) in footCases) {
            val builder = PushUpWithDeclaredSupport(contacts)
            val pipeline = SkeletonPipeline(def)
            for (p in progressValues) {
                val published = frame(builder, p, pipeline = pipeline)
                val declared = if (declaredIsF) footDeviationF(published) else footDeviationB(published)
                val other = if (declaredIsF) footDeviationB(published) else footDeviationF(published)
                val side = if (declaredIsF) "LEFT (F)" else "RIGHT (B)"
                assertTrue(
                    "$label p=$p: the declared $side foot must lie in its support plane, but its " +
                        "heel/toe deviate ${declared}u from its ankle (pre-fix this foot measured " +
                        "17.572u — the OTHER foot was flattened)",
                    declared <= inPlane
                )
                assertTrue(
                    "$label p=$p: the undeclared side's foot must keep the neutral derivation, but it " +
                        "measures ${other}u",
                    other > offPlane
                )
            }
        }

        // The arm path, in the same frames: the side must mean the same thing for hand and foot.
        val leftHand = PushUpWithDeclaredSupport(setOf(SupportContact.LEFT_HAND))
        val rightHand = PushUpWithDeclaredSupport(setOf(SupportContact.RIGHT_HAND))
        for (p in progressValues) {
            val l = frame(leftHand, p)
            assertTrue(
                "p=$p: declaring LEFT_HAND must flatten the LEFT (A) hand and leave the P hand " +
                    "(devHA=${handDeviationA(l)}, devHP=${handDeviationP(l)})",
                handDeviationA(l) <= inPlane && handDeviationP(l) > offPlane
            )
            val r = frame(rightHand, p)
            assertTrue(
                "p=$p: declaring RIGHT_HAND must flatten the RIGHT (P) hand and leave the A hand " +
                    "(devHA=${handDeviationA(r)}, devHP=${handDeviationP(r)})",
                handDeviationP(r) <= inPlane && handDeviationA(r) > offPlane
            )
        }
    }

    // ------------------------------------------------------------------
    // B.2 — the B-4 residual case: IsometricSidePlankPose
    // ------------------------------------------------------------------

    /**
     * `IsometricSidePlankPose` declares `RIGHT_FOOT` + `RIGHT_FOREARM` — the down-side limbs. Its own
     * authoring comments name them (`SHOULDER_P / HIP_B` drop to the support side; `HIP_B` rests on
     * the mat), and the declaration survives verbatim (B-2/B-3). The declared `RIGHT_FOOT` must
     * therefore resolve to the **B** foot — the bottom, load-bearing one — and that foot must be
     * flattened into its declared plane. Pre-fix the declaration resolved to the F foot (the stacked
     * TOP foot), so the top foot got the plane and the planted foot was left at `17.572` units of
     * deviation while its heel/toe sat at `y = 7.82 / 32.57` instead of the support surface at
     * `y = 15.00`.
     */
    @Test
    fun theIsometricSidePlankPlantsTheBottomFootItDeclares() {
        val pose = IsometricSidePlankPose()
        val declared = pose.metadata.support.contacts.map { it.point }.sortedBy { it.name }
        assertEquals(
            "the side plank declares its down-side (RIGHT) forearm + foot",
            listOf(SupportPoint.RIGHT_FOOT, SupportPoint.RIGHT_FOREARM),
            declared
        )
        assertEquals(
            "the declared RIGHT_FOOT is the B foot (the canonical mapping)",
            Joint.ANKLE_B, SupportMath.anchorJointFor(SupportPoint.RIGHT_FOOT)
        )

        val pipeline = SkeletonPipeline(def)
        for (p in progressValues) {
            val published = frame(pose, p, pipeline = pipeline)
            assertEquals(
                "the published support model stays the declared one (B-2/B-3 untouched)",
                setOf(SupportPoint.RIGHT_FOOT, SupportPoint.RIGHT_FOREARM),
                published.supportedPoints.toSet()
            )
            val ankleB = published.getJoint(Joint.ANKLE_B).y
            val ankleF = published.getJoint(Joint.ANKLE_F).y
            assertTrue(
                "p=$p: B is the DOWN-side leg — it never stacks above the F leg " +
                    "(ANKLE_B=$ankleB, ANKLE_F=$ankleF)",
                ankleB <= ankleF + 0.05f
            )
            if (p > 0f) {
                assertTrue(
                    "p=$p: the top (F) leg is stacked above the planted B leg " +
                        "(ANKLE_B=$ankleB, ANKLE_F=$ankleF)",
                    ankleF - ankleB > 1f
                )
            }
            assertTrue(
                "p=$p: the DECLARED RIGHT_FOOT is the planted B foot, so it must lie in its support " +
                    "plane; it deviates ${footDeviationB(published)}u (pre-fix: 17.572u " +
                    "— the declaration was applied to the TOP foot instead)",
                footDeviationB(published) <= inPlane
            )
            assertTrue(
                "p=$p: the heel/toe of the planted foot must sit on the declared surface " +
                    "(ANKLE_B=$ankleB, HEEL_B=${published.getJoint(Joint.HEEL_B).y}, " +
                    "TOE_B=${published.getJoint(Joint.TOE_B).y})",
                abs(published.getJoint(Joint.HEEL_B).y - ankleB) <= inPlane &&
                    abs(published.getJoint(Joint.TOE_B).y - ankleB) <= inPlane
            )
        }
    }

    // The former `theSidePlanksDeclaredSideIsWhatSelectsTheFoot` twin (this pose with `LEFT_FOOT`
    // declared, asserting the OTHER foot keeps the neutral derivation) was RETIRED by the B3
    // correction: it witnessed the side decision through the side plank's undeclared B foot, and that
    // foot no longer carries an off-plane derivation to witness. B3 re-authors the pose's support leg
    // (the residual knee bend leaves the mat instead of passing through it), and the engine's derived
    // foot for that leg follows the shank: measured `devB = 0.0000` for a twin that declares NOTHING,
    // where the same twin measured `devB = 17.5716` (`HEEL_B y = 7.82`, `TOE_B y = 32.57`) before the
    // correction. Both feet of this pose now lie in the support plane whatever is declared, so no
    // assertion on this vehicle can distinguish the declared side from the other one — a test there
    // would be green by geometry, not by the side decision.
    //
    // The rule it witnessed is not left unwatched: the `*_FOOT` kind is witnessed on BOTH sides by
    // [aOneSidedDeclarationFlattensThatSideForBothArmsAndLegs]'s push-up twins (`LEFT_FOOT`/`RIGHT_FOOT`
    // with `other > offPlane`, that pose's undeclared foot still measuring `17.572`), the corpus-wide
    // form by [everyOneSidedFootDeclarationInTheCorpusFlattensTheDeclaredSide], and this pose's own
    // `RIGHT_FOOT` → B-foot resolution by [theIsometricSidePlankPlantsTheBottomFootItDeclares].

    // ------------------------------------------------------------------
    // B.3 — the production corpus follows the declared side
    // ------------------------------------------------------------------

    /**
     * Every production pose that declares foot support on exactly ONE side must flatten that side's
     * foot. This is the corpus-wide form of the rule, run over the production registry
     * (`PoseRegistry.getDedicatedAnimationIds()`), so a future single-sided declaration cannot pick
     * up the opposite foot again.
     *
     * Poses that declare BOTH feet are not asserted here: with both sides declared, both feet are
     * flattened for the same reason, so they cannot witness the side decision (and `PikePushUpPose`
     * authors a plantar-flexion ankle articulation that is deliberately composed *after* the
     * projection — see `SupportContactKindConsumptionTest.authoredAnkleArticulationIsNotOverridden`).
     */
    @Test
    fun everyOneSidedFootDeclarationInTheCorpusFlattensTheDeclaredSide() {
        val examined = mutableListOf<String>()
        for (id in PoseRegistry.getDedicatedAnimationIds().sorted()) {
            val builder = PoseRegistry.getPoseConfig(id)?.builder ?: continue
            val supported = builder.metadata.support.contacts.map { it.point }.toSet()
            if (supported.isEmpty()) continue
            val declaredLeft = supported.any { it in LEFT_FOOT_POINTS }
            val declaredRight = supported.any { it in RIGHT_FOOT_POINTS }
            if (declaredLeft == declaredRight) continue // none, or both sides → cannot witness the side
            examined.add(id)
            val pipeline = SkeletonPipeline(def)
            for (p in progressValues) {
                val published = frame(builder, p, pipeline = pipeline)
                val deviation = if (declaredLeft) footDeviationF(published) else footDeviationB(published)
                val side = if (declaredLeft) "LEFT" else "RIGHT"
                assertTrue(
                    "$id p=$p declares one-sided $side foot support ($supported), so the $side foot must " +
                        "be flattened into its declared plane, but it deviates ${deviation}u",
                    deviation <= inPlane
                )
            }
        }
        assertTrue(
            "the corpus must contain the one-sided declaration this guard exists for " +
                "(IsometricSidePlankPose) — if the corpus changes, this guard's premise must be re-read",
            examined.contains("side_plank_standard")
        )
    }

    // ------------------------------------------------------------------
    // B.4 — the explicit convention witness: BirdDogPose
    // ------------------------------------------------------------------

    /**
     * `BirdDogPose` is the clearest authored statement of the side convention: *"RIGHT side -> left
     * arm (A) + right leg (B) extend; LEFT side -> right arm (P) + left leg (F)"*. Its published
     * frames must show exactly that (the named limb travels along +X, its diagonal partner stays at
     * the tabletop base), and the extended arm/leg pair must sit on opposite lateral sides — the
     * `A ≡ F side`, `P ≡ B side` pairing the canonical mapping encodes.
     */
    @Test
    fun birdDogPublishesTheAuthoredSideConvention() {
        val pose = BirdDogPose()
        for (side in listOf(Side.RIGHT, Side.LEFT)) {
            val pipeline = SkeletonPipeline(def)
            val start = frame(pose, 0.0f, side, pipeline)
            val end = frame(pose, 1.0f, side, pipeline)
            val armA = end.getJoint(Joint.HAND_A).x - start.getJoint(Joint.HAND_A).x
            val armP = end.getJoint(Joint.HAND_P).x - start.getJoint(Joint.HAND_P).x
            val legF = end.getJoint(Joint.ANKLE_F).x - start.getJoint(Joint.ANKLE_F).x
            val legB = end.getJoint(Joint.ANKLE_B).x - start.getJoint(Joint.ANKLE_B).x

            if (side == Side.RIGHT) {
                assertTrue(
                    "side=RIGHT must extend the LEFT arm (A, +140 along X) and the RIGHT leg (B, " +
                        "backwards): armA=$armA legB=$legB",
                    armA > 100f && legB < -50f
                )
                assertTrue("side=RIGHT must leave the diagonal pair at the base: armP=$armP legF=$legF", abs(armP) < 5f && abs(legF) < 5f)
                assertTrue(
                    "the extended limbs are diagonal and must sit on opposite sides " +
                        "(HAND_A z=${end.getJoint(Joint.HAND_A).z}, ANKLE_B z=${end.getJoint(Joint.ANKLE_B).z})",
                    end.getJoint(Joint.HAND_A).z * end.getJoint(Joint.ANKLE_B).z < 0f
                )
            } else {
                assertTrue(
                    "side=LEFT must extend the RIGHT arm (P) and the LEFT leg (F): armP=$armP legF=$legF",
                    armP > 100f && legF < -50f
                )
                assertTrue("side=LEFT must leave the diagonal pair at the base: armA=$armA legB=$legB", abs(armA) < 5f && abs(legB) < 5f)
                assertTrue(
                    "the extended limbs are diagonal and must sit on opposite sides " +
                        "(HAND_P z=${end.getJoint(Joint.HAND_P).z}, ANKLE_F z=${end.getJoint(Joint.ANKLE_F).z})",
                    end.getJoint(Joint.HAND_P).z * end.getJoint(Joint.ANKLE_F).z < 0f
                )
            }
        }
    }

    /**
     * `AlternatingBirdDogPose` is progress-driven instead of side-driven, and its own authoring
     * comments name the same convention: "Right arm (SHOULDER_P) + Left leg (HIP_F) extend with
     * rightExt" at progress 0.25, "Left arm (SHOULDER_A) + Right leg (HIP_B) extend with leftExt" at
     * 0.75. Both phases are asserted on the published frame — a second, independent witness that
     * `A`/`F` and `P`/`B` are the two physical sides.
     */
    @Test
    fun alternatingBirdDogPublishesTheAuthoredConvention() {
        val pose = AlternatingBirdDogPose()
        val pipeline = SkeletonPipeline(def)
        val base = frame(pose, 0.0f, Side.RIGHT, pipeline)
        val rightPhase = frame(pose, 0.25f, Side.RIGHT, pipeline)
        val leftPhase = frame(pose, 0.75f, Side.RIGHT, pipeline)

        assertTrue(
            "progress 0.25 extends the P arm + F leg (the authoring comment's rightExt diagonal): " +
                "armP=${rightPhase.getJoint(Joint.HAND_P).x - base.getJoint(Joint.HAND_P).x} " +
                "legF=${rightPhase.getJoint(Joint.ANKLE_F).x - base.getJoint(Joint.ANKLE_F).x}",
            rightPhase.getJoint(Joint.HAND_P).x - base.getJoint(Joint.HAND_P).x > 100f &&
                rightPhase.getJoint(Joint.ANKLE_F).x - base.getJoint(Joint.ANKLE_F).x < -50f
        )
        assertTrue(
            "progress 0.75 extends the A arm + B leg: " +
                "armA=${leftPhase.getJoint(Joint.HAND_A).x - base.getJoint(Joint.HAND_A).x} " +
                "legB=${leftPhase.getJoint(Joint.ANKLE_B).x - base.getJoint(Joint.ANKLE_B).x}",
            leftPhase.getJoint(Joint.HAND_A).x - base.getJoint(Joint.HAND_A).x > 100f &&
                leftPhase.getJoint(Joint.ANKLE_B).x - base.getJoint(Joint.ANKLE_B).x < -50f
        )
        for (phase in listOf(rightPhase, leftPhase)) {
            val arm = if (phase === rightPhase) Joint.HAND_P else Joint.HAND_A
            val leg = if (phase === rightPhase) Joint.ANKLE_F else Joint.ANKLE_B
            assertTrue(
                "$arm and $leg are diagonal limbs and must sit on opposite sides " +
                    "(z=${phase.getJoint(arm).z} / ${phase.getJoint(leg).z})",
                phase.getJoint(arm).z * phase.getJoint(leg).z < 0f
            )
        }
    }

    // ------------------------------------------------------------------

    private val LEFT_FOOT_POINTS = setOf(SupportPoint.LEFT_FOOT, SupportPoint.LEFT_TOES)
    private val RIGHT_FOOT_POINTS = setOf(SupportPoint.RIGHT_FOOT, SupportPoint.RIGHT_TOES)

    /**
     * A production pose with its support DECLARATION replaced — the same rig B-3 used: it delegates
     * `build()` to the real pose (production geometry, production authoring path) and varies exactly
     * one thing (`metadata.support.contacts`), so a published-frame difference can only come from
     * the declaration.
     */
    private class PushUpWithDeclaredSupport(
        contacts: Set<SupportContact>
    ) : PoseBuilder by StandardPushUpPose() {
        override val metadata = StandardPushUpPose().metadata.copy(
            support = SupportDefinition(pivot = PivotType.FEET, contacts = contacts)
        )
    }
}
