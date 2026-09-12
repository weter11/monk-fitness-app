package com.monkfitness.app

import com.monkfitness.app.animation.*
import com.monkfitness.app.poses.DiamondPushUpPose
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **B1 — the diamond push-up's published frames must not put a joint under the pose's own declared
 * ground plane, and the arm chain must realize the bend side the exercise declares.**
 *
 * ## The defect (measured on `origin/main` @ `2fb6079`, published path)
 *
 * `DiamondPushUpPose` authors the family's default *Z-dominant* elbow pole shape
 * (`poleA = (0.5, 0.5, -2.0)`, the `Wide` variant's bend statement). The family's convention is
 * sound for every grip whose hands sit **at or outside** the shoulder line — `Standard` (grip 1.5),
 * `Wide` (1.9), `Military` (1.0), `Knee` (1.8), `Decline` (1.5) — because there the shoulder→hand
 * chord is a lateral line and the pole's Z component selects the elbow's lateral side.
 *
 * The diamond grip is `0.1`: the hands come to the midline, **41.4 u inboard** of the shoulder
 * joint (`z = ±4.6` against the shoulder's `±46`). The chord therefore already *is* the lateral
 * direction, the pole's `Z` no longer selects a lateral side, and the chain's perpendicular
 * offset collapses onto the chord's other basis vector. At the bottom of the rep (the phase with
 * the largest triangle height) that vector points **downward and outward**:
 *
 * ```
 * p = 0.5: shoulder (-59.99, 53.97, -46.00) -> hand (-58.32, 0.00, -4.60)
 *          d = 68.04  h = 63.21  u = (0.025, -0.793, 0.608)  phat = (0.388, -0.553, -0.737)
 *          ELBOW_A = shoulder + u*a + phat*h = (-34.29, -19.91, -62.75)
 * ```
 *
 * i.e. the elbow is realized **19.91 u BELOW the mat the pose itself declares**
 * (`metadata.environment.ground.level = 0`), and the geometric predicate
 * `SkeletonPoseFinalizer.adjustHandOrientation` uses to decide whether a hand is *planted*
 * (`hand.y <= elbow.y + 1`) flips FALSE over the whole bottom band: the derived
 * palm/knuckles/fingertips are **not** flattened onto the declared plane on `67` of the `201`
 * frames of the dense sweep, while every frame outside that band is flat (measured `PALM_A y =
 * 1.8103`, `KNUCKLES_A 3.6205`, `FINGERTIPS_A 6.6377` at p = 0.5, against `0.0000` at
 * p = 0.25/0.75) — a frame-to-frame discontinuity in the same defect.
 *
 * ## Why the existing instruments were blind (the T2 lesson)
 *
 * `ELBOW_A`/`ELBOW_P` are **not** in the pose's declared support family (`LEFT_HAND`, `RIGHT_HAND`,
 * `LEFT_TOES`, `RIGHT_TOES` → hand/palm/knuckles/fingertips + ankle/heel/toe), so the
 * declaration-keyed `EnvironmentPenetrationTest` cannot see them; `ExerciseValidator`'s ground rule
 * is foot-only; and the pose's elbow is an **IK output** — `DiamondPushUpPose` owns only the chain
 * root (the shoulder), the end target (the hand) and the pole. T2 pins the pair
 * (`PublishedBelowGroundInvariantTest.knownBelowGround`) as an attributed open item; this file is
 * the focused regression that item's exit criterion asks for.
 *
 * ## What this test asserts (all on the PUBLISHED frame, never `build()`)
 *
 *  1. No published joint of any frame passes below the pose's own declared plane — over a **dense**
 *     progress sweep (51 samples, not the corpus invariants' 5), under **both** frame conditions
 *     (the genuinely cold first frame of a fresh pose on a fresh pipeline, and the frame an
 *     advancing pipeline publishes), every frame captured **by value** (the T-7 trap: `produceFrame`
 *     returns the Finalizer's reused buffer).
 *  2. The elbow chain has a real clearance — the defect is not merely nudged to `-0.04`.
 *  3. The arm chain reads the exercise's declared bend side (BPS §6/§11: *"the upper arms are
 *     near-parallel to the trunk"*, *"elbows tucked tightly to ribs ... must not flare outward"*,
 *     *"forearms ... close together"*): the elbow stays **outside the fused hand base** laterally
 *     (so the two arms cannot collide in the mid-sagittal plane) and **inside the shoulder line**
 *     (adducted, not flared).
 *  4. The derived hand chain is flattened onto the declared plane on **every** frame — the
 *     engine's own `planted` precondition (`hand.y <= elbow.y + 1`) holds throughout, which is the
 *     second observable of the same root cause (it is the elbow's height that decides it).
 *  5. Anti-vacuity: the sweep really publishes distinct frames, and the elbow really travels.
 *  6. Anti-regression: the family motion contract is untouched (`PushUpMotionTest`'s ≥ 40 u chest
 *     travel) — the fix may not "pass" by stilling the rep.
 *  7. The mirror symmetry is exact (the corrected pole has no lateral component, so A and P must
 *     realize the mirrored chain bit-for-bit).
 */
class DiamondPushUpElbowClearanceTest {

    private val def = SkeletonDefinition.DEFAULT_ADULT

    /** The plane the pose itself declares — never a hardcoded zero (`ENGINE.md` §4). */
    private val ground: Float = DiamondPushUpPose().metadata.environment.ground.level

    /** Dense sweep: the corpus invariants' 5 samples can miss a single-frame dip. */
    private val samples: List<Float> = (0..50).map { it / 50f }

    /** The T2 invariant's band (`PublishedBelowGroundInvariantTest.groundBand`), same value. */
    private val band = 0.05f

    /**
     * The elbow clearance the corrected chain must keep over the whole sweep.
     *
     * Measured on the fixed tree: the elbow's worst published height over the dense sweep is
     * `16.3014` (p = 0.505, the chain's maximum triangle height phase) against a pre-fix `-19.9130`.
     * The floor is set well below the measurement (and three orders of magnitude above the T2
     * band), so it pins the DEFECT, not the exact number: any realization that puts the elbow back
     * under the mat fails by ~20 u, and the thin-clearance "nudge it above the band" shape fails
     * too.
     */
    private val elbowClearanceFloor = 10f

    /** The family motion contract this change must not quietly delete (`PushUpMotionTest`). */
    private val chestTravelFloor = 40f

    private enum class Condition { COLD, PLAYING }

    /**
     * One measured frame, primitives only — never a [SkeletonPose] reference (the pipeline
     * publishes the Finalizer's reused buffer, so holding references aliases every sample to the
     * last frame and silently reports one frame N times: the T-7 trap).
     */
    private class Frame(
        val condition: Condition,
        val progress: Float,
        val identity: Int,
        val y: Map<Joint, Float>,
        val x: Map<Joint, Float>,
        val z: Map<Joint, Float>
    ) {
        fun y(j: Joint): Float = y.getValue(j)
        fun z(j: Joint): Float = z.getValue(j)
    }

    private fun ctx(p: Float) = PoseContext(
        progress = p, side = Side.RIGHT, definition = def,
        deltaTime = 0.0166f, cycleDuration = 2500f
    )

    private fun measure(frame: SkeletonPose, condition: Condition, p: Float) = Frame(
        condition, p, System.identityHashCode(frame),
        Joint.entries.associateWith { frame.getJoint(it).y },
        Joint.entries.associateWith { frame.getJoint(it).x },
        Joint.entries.associateWith { frame.getJoint(it).z }
    )

    /**
     * The sweep under both frame conditions.
     *  * COLD — a fresh pose instance on a fresh pipeline for every sample: the genuinely cold
     *    first frame of a build that reads no reused node state.
     *  * PLAYING — one builder advancing 0 → 1 on one pipeline: the frame the engine publishes in
     *    the rep (mirrors the sibling corpus invariants' second leg).
     */
    private fun sweep(): List<Frame> {
        val out = ArrayList<Frame>(samples.size * 2)
        for (p in samples) {
            val cold = SkeletonPipeline(def).produceFrame(DiamondPushUpPose(), ctx(p)).pose
            out.add(measure(SkeletonPose().apply { copyFrom(cold) }, Condition.COLD, p))
        }
        val builder = DiamondPushUpPose()
        val pipeline = SkeletonPipeline(def)
        for (p in samples) {
            val playing = pipeline.produceFrame(builder, ctx(p)).pose
            out.add(measure(SkeletonPose().apply { copyFrom(playing) }, Condition.PLAYING, p))
        }
        return out
    }

    private fun fmt(v: Float) = String.format(java.util.Locale.ROOT, "%.4f", v)

    // ------------------------------------------------------------------------------------------
    // 1 + 2. the defect: no joint under the declared plane, and the elbow clears it for real
    // ------------------------------------------------------------------------------------------

    @Test
    fun noPublishedJointOfTheDiamondPushUpPassesBelowItsDeclaredPlane() {
        val frames = sweep()
        val worst = frames.flatMap { f -> Joint.entries.map { it to f } }
            .minByOrNull { (j, f) -> f.y(j) }!!

        val offenders = frames.flatMap { f ->
            Joint.entries.filter { f.y(it) < ground - band }
                .map { "${f.condition} p=${fmt(f.progress)} $it y=${fmt(f.y(it))}" }
        }
        assertTrue(
            "published joints below the pose's own declared plane (level=$ground, band=$band); " +
                "worst = ${worst.first} ${fmt(worst.second.y(worst.first))} at " +
                "${worst.second.condition} p=${fmt(worst.second.progress)}:\n" +
                offenders.distinct().joinToString("\n") { "  $it" } +
                "\n(pre-fix: ELBOW_A/P reach -19.9130 at p=0.5 — the chain's perpendicular offset " +
                "collapses downward when the hands are pulled 41.4 u inboard of the shoulder line)",
            offenders.isEmpty()
        )
    }

    @Test
    fun theElbowChainClearsThePlaneOverTheWholeRep() {
        val frames = sweep()
        for (j in listOf(Joint.ELBOW_A, Joint.ELBOW_P)) {
            val lowest = frames.minByOrNull { it.y(j) }!!
            assertTrue(
                "$j: the realized elbow must stay clear of the declared plane over the whole " +
                    "sweep (worst ${fmt(lowest.y(j))} at ${lowest.condition} p=${fmt(lowest.progress)}); " +
                    "pre-fix the chain's own IK placed it at -19.9130 (p=0.5)",
                lowest.y(j) >= ground + elbowClearanceFloor
            )
        }
    }

    // ------------------------------------------------------------------------------------------
    // 3. the realized chain reads the diamond's declared bend side
    // ------------------------------------------------------------------------------------------

    @Test
    fun theElbowStaysOutsideTheFusedHandBaseAndInsideTheShoulderLine() {
        val frames = sweep()
        for (f in frames) {
            for ((elbow, hand, shoulder) in listOf(
                Triple(Joint.ELBOW_A, Joint.HAND_A, Joint.SHOULDER_A),
                Triple(Joint.ELBOW_P, Joint.HAND_P, Joint.SHOULDER_P)
            )) {
                val e = kotlin.math.abs(f.z(elbow))
                val h = kotlin.math.abs(f.z(hand))
                val s = kotlin.math.abs(f.z(shoulder))
                assertTrue(
                    "$elbow at ${f.condition} p=${fmt(f.progress)}: the elbow must stay laterally " +
                        "OUTSIDE the fused hand base (|elbow.z|=${fmt(e)} vs |hand.z|=${fmt(h)}) — " +
                        "both forearms in the mid-sagittal plane would collide; pre-fix the elbow " +
                        "was realized below the mat instead (ELBOW_A y=-19.9130 at p=0.5)",
                    e > h
                )
                assertTrue(
                    "$elbow at ${f.condition} p=${fmt(f.progress)}: the elbow must stay INSIDE the " +
                        "shoulder line (|elbow.z|=${fmt(e)} vs |shoulder.z|=${fmt(s)}) — BPS §6/§11 " +
                        "'elbows tucked tightly to ribs (0-20 deg from torso), not flared'",
                    e < s
                )
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // 4. the same root cause seen from the engine's own predicate: the planted hand chain
    // ------------------------------------------------------------------------------------------

    @Test
    fun theDerivedHandChainIsFlattenedOnEveryFrameOfTheRep() {
        val derived = listOf(
            Joint.WRIST_A, Joint.PALM_A, Joint.KNUCKLES_A, Joint.FINGERTIPS_A,
            Joint.WRIST_P, Joint.PALM_P, Joint.KNUCKLES_P, Joint.FINGERTIPS_P
        )
        val frames = sweep()
        for (f in frames) {
            val planted = f.y(Joint.HAND_A) <= f.y(Joint.ELBOW_A) + 1.0f &&
                f.y(Joint.HAND_P) <= f.y(Joint.ELBOW_P) + 1.0f
            assertTrue(
                "the engine's own 'hand is planted' precondition (hand.y <= elbow.y + 1) must hold " +
                    "on every frame of a floor-planted push-up: ${f.condition} p=${fmt(f.progress)} " +
                    "hand=${fmt(f.y(Joint.HAND_A))} elbow=${fmt(f.y(Joint.ELBOW_A))}; pre-fix it " +
                    "flipped false exactly at the bottom of the rep (p=0.5), where the derived " +
                    "FINGERTIPS_A sat at y=6.64 instead of on the plane",
                planted
            )
            for (j in derived) {
                val h = if (j.name.endsWith("_A")) Joint.HAND_A else Joint.HAND_P
                assertEquals(
                    "$j at ${f.condition} p=${fmt(f.progress)}: a planted hand's derived chain must " +
                        "lie in its support plane (hand y=${fmt(f.y(h))}); the frame-to-frame " +
                        "discontinuity this catches is the elbow's height deciding the derivation",
                    f.y(h), f.y(j), 1e-3f
                )
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // 5. anti-vacuity
    // ------------------------------------------------------------------------------------------

    @Test
    fun theSweepPublishesDistinctFramesAndTheElbowReallyTravels() {
        val frames = sweep()
        assertEquals(
            "every sample must publish its own frame object (a reused buffer would alias them all " +
                "to the last sample — the T-7 trap this file must not inherit)",
            frames.size, frames.map { it.identity }.distinct().size
        )
        val elbowSpan = frames.maxOf { it.y(Joint.ELBOW_A) } - frames.minOf { it.y(Joint.ELBOW_A) }
        assertTrue(
            "the elbow must realize the rep's travel (measured span ${fmt(elbowSpan)}); a chain " +
                "frozen at one height would satisfy a clearance assertion vacuously",
            elbowSpan > 20f
        )
    }

    // ------------------------------------------------------------------------------------------
    // 6 + 7. the motion contract survives, and the correction is mirror-exact
    // ------------------------------------------------------------------------------------------

    @Test
    fun theFamilyMotionContractIsPreserved() {
        val frames = sweep()
        val chest = frames.map { it.y(Joint.CHEST) }
        val travel = chest.max() - chest.min()
        assertTrue(
            "the diamond push-up's rep must still travel (chest span ${fmt(travel)}, floor " +
                "$chestTravelFloor u — the same contract PushUpMotionTest asserts): the elbow fix " +
                "must not be bought by stilling the rep",
            travel >= chestTravelFloor
        )
    }

    @Test
    fun theCorrectedChainIsMirrorExact() {
        for (f in sweep()) {
            for ((a, p) in listOf(
                Joint.ELBOW_A to Joint.ELBOW_P, Joint.HAND_A to Joint.HAND_P,
                Joint.SHOULDER_A to Joint.SHOULDER_P
            )) {
                assertEquals(
                    "$a/$p at ${f.condition} p=${fmt(f.progress)}: the corrected pole has no " +
                        "lateral component, so the two sides must realize the mirrored chain",
                    f.y(a), f.y(p), 1e-4f
                )
                assertEquals(
                    "$a/$p z at ${f.condition} p=${fmt(f.progress)}: mirror symmetry",
                    -f.z(a), f.z(p), 1e-4f
                )
            }
        }
    }
}
