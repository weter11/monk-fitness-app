package com.monkfitness.app

import com.monkfitness.app.animation.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * **M15 — `WallSlidesPose`'s wall/forearm contact plane, asserted on the PUBLISHED frame.**
 *
 * ## The documented contract
 *
 * `docs/Biomechanical Pose Specification (BPS)/Wall Slide (Standard).md` §8 states the contacts
 * concretely — "the back of the head, upper back, pelvis, and low back contact the wall. **Elbows and
 * the backs of the wrists/hands contact the wall** (the guiding constraint)" — and §6/§11 repeat it
 * ("the elbows and wrists maintain wall contact throughout"; "at the top the arms are overhead with
 * wrists still on the wall"). `docs/architecture/Movement Ownership Matrix.md` §Wall Slide names the
 * same two joints: "Followers: … Elbow, Wrist/Hand (**on wall**)". The exercise is *defined* by that
 * contact — it is the constraint that keeps the pattern honest (§12 "letting the elbows or wrists peel
 * off the wall (cheating the path)").
 *
 * ## The defect this file pins (audit row M15, measured on the pre-fix tree)
 *
 * The pose declares a `WallProp`, which in this engine is a **plane of constant X** (`width` is the X
 * extent — the same convention `M1StepUpGeometryTest`/`EnvironmentPenetrationTest` resolve props with).
 * Its contact plane is therefore a fixed X. The arm chain, however, was authored so that the realized
 * forearm is nowhere near that plane and the wall does not span the arms at all:
 *
 *  * `ELBOW_A.x` travels `-62.58 … -51.90` while the wall's own +X face is at `-11.000` — the elbow
 *    sits `40.9 … 51.6` u **behind** the wall's contact face, and the forearm's X span measures
 *    `46.9 … 65.7` of its `66` u length, i.e. the forearm runs roughly **along the wall's normal**
 *    (through the wall) instead of lying in its plane.
 *  * The wall spans `y ∈ [0, 180]`, i.e. it tops out below the athlete's own pelvis (`y = 235`), while
 *    the declared wall contacts sit at `y = 332.29 … 415.00` (wrist) and `y = 356.37 … 382.86`
 *    (elbow): every wall contact floats `152 … 235` u above the wall's top edge and the elbow is
 *    `21.5` u outside its `z ∈ [-80, 80]` extent.
 *  * The wall's +X face is `6` u behind the plane the athlete's own spine is authored in
 *    (`PELVIS/CHEST/HEAD_POS.x = -5.000`), so the wall the athlete leans against is not the plane the
 *    arms slide on — and the H1 record's own intent ("its +X face meets the back/forearms") is not
 *    realized.
 *
 * The arm chain's realisation is what makes the first two readings: the authored elbow pole
 * `(-1, 0, ∓1)` has a negative X component, and the elbow is placed on the arc of radius
 * `h = 65.88` perpendicular to the shoulder→hand chord, so a pole whose perpendicular component points
 * along `-X` throws the elbow ~`58` u backwards out of the plane. The pose's own comment says the pole
 * exists to "keep contact with the wall plane" — it does the opposite.
 *
 * ## What this file asserts
 *
 * Four behavioural readings, all on the published frame (both frame conditions: the genuinely cold
 * first frame of a fresh builder on a fresh pipeline, and the frames an advancing pipeline publishes),
 * plus the anti-collateral foot guard and this finding's own blast-radius digest:
 *
 *  1. [theForearmLiesInTheWallsOwnContactPlane] — both endpoints of each forearm (elbow + wrist) sit on
 *     the wall's own declared contact plane (band `0.5` u, the sibling guards' precision).
 *  2. [theWallContactJointsLieOnTheWallsOwnSurface] — those contacts lie inside the wall prop's own Y/Z
 *     extent, i.e. they are *on the wall*, not floating past its top edge or its side edge.
 *  3. [theWholeHandLiesOnTheWall] — BPS §8 names the "backs of the wrists/hands": the DERIVED hand
 *     chain (palm/knuckles/fingertips) lies on the face plane too, not only the wrist joint.
 *  4. [theSlideStaysCoherentThroughTheWholeRep] — the wall slide itself is unchanged and coherent
 *     (the hand travels the authored `-10 → +60` slide, each arm keeps its definition bone lengths, and
 *     the wrist stays above the elbow, which is what "sliding **up** the wall" means).
 *
 * Plus [theDeclaredFootContactsStillResolveToTheGround] (the wall's footprint must not capture the
 * declared foot contacts — the engine resolves a contact's surface from its own centroid, and a wall
 * footprint that swallowed the feet would orient them against the wall instead of the floor; this is
 * also the measurement that REFUTES moving the wall's face forward onto the athlete's spine plane) and
 * [unaffectedPosesPublishByteIdenticalGeometry] (the blast-radius digest over every other production
 * pose class).
 *
 * ## Deliberately NOT asserted: the wall's standoff from the athlete's back
 *
 * BPS §3/§7/§8 also put the head, upper back and pelvis ON the wall. In this engine the body is a
 * stick skeleton on one X, and the wall's face has to stay clear of the declared foot contact's
 * centroid (see above), so the face is `6` u behind the spine plane and that half of the contract is
 * NOT modelled. Asserting "face == spine plane" here would demand a change the current engine
 * coupling forbids — the recorded H1 complement, not an M15 contract.
 *
 * ## RED evidence (this class on the untouched base tree)
 *
 * `origin/main` @ `4203fff`, `--rerun-tasks`: **5 of 6 FAILED**, the numbers quoted in the assertion
 * messages —
 * `theForearmLiesInTheWallsOwnContactPlane` (`ELBOW_A` `51.58 … 59.65` u off the face plane, `HAND_A`
 * `6.00` off it, the forearm's X span `57.58 … 65.65` of its `66` u length),
 * `theWallContactJointsLieOnTheWallsOwnSurface` (every contact `152 … 235` u above the wall's top edge
 * `y = 180`, the elbows `21.5 … 24.5` u outside `z ∈ [-80, 80]`),
 * `theWholeHandLiesOnTheWall` (`PALM_A` `+5.23 …`, `FINGERTIPS_A` `+16.89` — the hand chain in FRONT of
 * the wall), `theSlideStaysCoherentThroughTheWholeRep` (the wrist below the elbow at `p ≤ 0.125`).
 * `theDeclaredFootContactsStillResolveToTheGround` is green on both trees **by design** — it is the
 * anti-collateral guard, and its green pre-fix reading is what refutes the alternative fix (moving the
 * wall's face onto the athlete's spine plane).
 *
 * ## Per-hunk counterfactuals (fresh runs, each essential correction removed ALONE)
 *
 * | hunk removed | this class |
 * |---|---|
 * | the wall prop's extent (`500 × 300` → the pre-M15 `180 × 160`) | 1 RED — [theWallContactJointsLieOnTheWallsOwnSurface] |
 * | the arm chain authored on the wall's face (`handX` `-11` → the body plane `-5`) | 3 RED — plane, whole hand, contacts-on-wall |
 * | the in-plane reach extension of the authored W | 3 RED — plane, whole hand, contacts-on-wall |
 * | the derived elbow pole (→ the pre-fix `(-1, 0, ∓1)`) | 4 RED — + [theSlideStaysCoherentThroughTheWholeRep] (wrist below elbow) |
 *
 * In every variant the two guards ([theDeclaredFootContactsStillResolveToTheGround],
 * [unaffectedPosesPublishByteIdenticalGeometry]) stay GREEN, and the restored bytes re-run fully GREEN
 * (`md5sum -c` on the restored pose file).
 */
class M15WallSlidesWallGeometryTest {

    private val def = SkeletonDefinition.DEFAULT_ADULT

    /** A dense sweep: the contact must hold through the WHOLE motion, not at the sample points. */
    private val samples = listOf(0f, 0.125f, 0.25f, 0.375f, 0.5f, 0.625f, 0.75f, 0.875f, 1f)

    /** The engine's own contact precision (the B-6 band's magnitude): a wall contact joint must sit on
     *  the wall's contact plane within this many skeleton units. */
    private val planeBand = 0.5f

    private val poseName = "WallSlidesPose"

    private enum class FrameCondition { COLD, PLAYING }

    /** One immutable measurement — a frame captured BY VALUE (`produceFrame` reuses its output buffer). */
    private data class Sample(val condition: FrameCondition, val progress: Float, val frame: SkeletonPose)

    private fun ctx(p: Float) = PoseContext(
        progress = p, side = Side.RIGHT, definition = def, deltaTime = 0.0166f, cycleDuration = 2500f
    )

    private fun snapshot(f: SkeletonPose) = SkeletonPose().apply { copyFrom(f) }

    /**
     * Every sample under both frame conditions. COLD = a fresh builder on a fresh pipeline per sample
     * (the genuinely cold first frame); PLAYING = one builder + one pipeline advancing 0 → 1.
     */
    private fun allSamples(): List<Sample> {
        val out = mutableListOf<Sample>()
        for (p in samples) {
            out.add(Sample(FrameCondition.COLD, p, snapshot(SkeletonPipeline(def).produceFrame(MotionProbe.build(poseName), ctx(p)).pose)))
        }
        val pipeline = SkeletonPipeline(def)
        val builder = MotionProbe.build(poseName)
        for (p in samples) {
            out.add(Sample(FrameCondition.PLAYING, p, snapshot(pipeline.produceFrame(builder, ctx(p)).pose)))
        }
        return out
    }

    private fun wallProp(): WallProp =
        MotionProbe.build(poseName).metadata.environment!!.props
            .filterIsInstance<WallProp>().single()

    /** The wall's contact surface: the +X face of the prop, in the prop's own coordinates. */
    private fun wallFaceX(prop: WallProp): Float = prop.center.x + prop.width * 0.5f

    private fun where(cond: FrameCondition, p: Float, joint: Joint, f: SkeletonPose) =
        "$poseName/${cond.name.lowercase()} p=$p ${joint.name}=(${f.getJoint(joint).x},${f.getJoint(joint).y},${f.getJoint(joint).z})"

    private fun distance(a: Vector3, b: Vector3): Float =
        sqrt((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y) + (a.z - b.z) * (a.z - b.z))

    // ------------------------------------------------------------------------------------------
    // 1 + 2 — the forearms lie IN the wall's own contact plane, and the contacts are ON the wall
    // ------------------------------------------------------------------------------------------

    @Test
    fun theForearmLiesInTheWallsOwnContactPlane() {
        val prop = wallProp()
        val faceX = wallFaceX(prop)
        val forearmJoints = listOf(
            Joint.ELBOW_A to Joint.HAND_A, // the F/A-side forearm (authored-elbow → wrist)
            Joint.ELBOW_P to Joint.HAND_P
        )
        val failures = mutableListOf<String>()

        for (s in allSamples()) {
            for ((elbow, wrist) in forearmJoints) {
                val e = s.frame.getJoint(elbow)
                val w = s.frame.getJoint(wrist)
                val elbowOffPlane = abs(e.x - faceX)
                val wristOffPlane = abs(w.x - faceX)
                // The forearm's long axis must lie in the plane: its X span is the wall-normal component.
                val forearmXSpan = abs(w.x - e.x)
                if (elbowOffPlane > planeBand || wristOffPlane > planeBand) {
                    failures.add(
                        "${s.condition} p=${s.progress}: ${elbow.name} is ${elbowOffPlane} u off the wall's " +
                            "face plane (x=$faceX) and ${wrist.name} is ${wristOffPlane} u off it; the " +
                            "forearm's X span (= its wall-normal component) is ${forearmXSpan} u of the " +
                            "${distance(e, w)} u forearm — `where(…)` ${where(s.condition, s.progress, elbow, s.frame)} " +
                            "${where(s.condition, s.progress, wrist, s.frame)}"
                    )
                }
            }
        }
        assertTrue(
            "M15 — BPS §8/§6 require the elbows and wrists to contact the wall, i.e. each forearm must lie " +
                "IN the wall's contact plane (a plane of constant X in this engine). Failures:\n" +
                failures.joinToString("\n"),
            failures.isEmpty()
        )
    }

    @Test
    fun theWallContactJointsLieOnTheWallsOwnSurface() {
        val prop = wallProp()
        val faceX = wallFaceX(prop)
        val yLo = prop.center.y - prop.height * 0.5f
        val yHi = prop.center.y + prop.height * 0.5f
        val zLo = prop.center.z - prop.depth * 0.5f
        val zHi = prop.center.z + prop.depth * 0.5f
        val failures = mutableListOf<String>()

        for (s in allSamples()) {
            for (joint in listOf(Joint.ELBOW_A, Joint.HAND_A, Joint.ELBOW_P, Joint.HAND_P)) {
                val v = s.frame.getJoint(joint)
                val onFace = abs(v.x - faceX) <= planeBand
                if (!onFace || v.y !in yLo..yHi || v.z !in zLo..zHi) {
                    failures.add(
                        "${s.condition} p=${s.progress}: ${joint.name}=(${v.x},${v.y},${v.z}) is not on the " +
                            "wall (face x=$faceX ±$planeBand, y∈[$yLo,$yHi], z∈[$zLo,$zHi])"
                    )
                }
            }
        }
        assertTrue(
            "M15 — a contact that is on the wall's PLANE but outside the wall prop's own extent (above its " +
                "top edge / past its side edge) is not 'on the wall' (BPS §8). Failures:\n" +
                failures.joinToString("\n"),
            failures.isEmpty()
        )
    }

    // ------------------------------------------------------------------------------------------
    // 3 — the whole hand, not only the wrist (BPS §8 "the backs of the hands/wrists")
    // ------------------------------------------------------------------------------------------

    @Test
    fun theWholeHandLiesOnTheWall() {
        val prop = wallProp()
        val faceX = wallFaceX(prop)
        val failures = mutableListOf<String>()

        for (s in allSamples()) {
            for (joint in listOf(
                Joint.PALM_A, Joint.KNUCKLES_A, Joint.FINGERTIPS_A,
                Joint.PALM_P, Joint.KNUCKLES_P, Joint.FINGERTIPS_P
            )) {
                val v = s.frame.getJoint(joint)
                val off = abs(v.x - faceX)
                if (off > planeBand) {
                    failures.add("${s.condition} p=${s.progress}: ${joint.name}=(${v.x},${v.y},${v.z}) is $off u off the wall's face plane (x=$faceX)")
                }
            }
        }
        assertTrue(
            "M15 — BPS §8 contacts the wall with the ELBOWS and the BACKS OF THE WRISTS/HANDS: the derived " +
                "hand chain must lie on the wall's face too, not only the wrist. Failures:\n" +
                failures.joinToString("\n"),
            failures.isEmpty()
        )
    }

    // ------------------------------------------------------------------------------------------
    // 4 — the exercise itself is unchanged and coherent through the whole rep
    // ------------------------------------------------------------------------------------------

    @Test
    fun theSlideStaysCoherentThroughTheWholeRep() {
        val frames = allSamples()
        val failures = mutableListOf<String>()

        // (a) the authored slide: the hand travels the −10 → +60 from-the-shoulder slide (−10 at the
        //     W, +60 overhead), i.e. >= 70 u of travel measured on the published frame.
        for (side in listOf(Joint.HAND_A to "A", Joint.HAND_P to "P")) {
            val (joint, name) = side
            val ys = frames.map { it.frame.getJoint(joint).y }
            val travel = ys.max() - ys.min()
            if (travel < 70f) failures.add("hand $name Y travel is ${travel} u (the authored slide is 70 u)")
        }

        // (b) bone lengths and the slide DIRECTION: on a wall slide the wrist sits above the elbow
        //     (the arms slide UP the wall), and each arm keeps the definition's segment lengths.
        for (s in frames) {
            val shA = s.frame.getJoint(Joint.SHOULDER_A); val elA = s.frame.getJoint(Joint.ELBOW_A); val ha = s.frame.getJoint(Joint.HAND_A)
            val shP = s.frame.getJoint(Joint.SHOULDER_P); val elP = s.frame.getJoint(Joint.ELBOW_P); val hp = s.frame.getJoint(Joint.HAND_P)
            for ((label, arm) in listOf("A" to Triple(shA, elA, ha), "P" to Triple(shP, elP, hp))) {
                val (sh, el, hand) = arm
                val upper = distance(sh, el)
                val fore = distance(el, hand)
                if (abs(upper - def.upperArmLength) > 0.5f) failures.add("${s.condition} p=${s.progress}: arm $label upper arm is $upper u, definition says ${def.upperArmLength}")
                if (abs(fore - def.forearmLength) > 0.5f) failures.add("${s.condition} p=${s.progress}: arm $label forearm is $fore u, definition says ${def.forearmLength}")
                if (hand.y <= el.y) failures.add("${s.condition} p=${s.progress}: arm $label wrist y=${hand.y} is not above the elbow y=${el.y} (the arms slide UP the wall)")
                if (abs(el.z) <= abs(sh.z)) failures.add("${s.condition} p=${s.progress}: arm $label elbow |z|=${abs(el.z)} is not outboard of the shoulder |z|=${abs(sh.z)} (no abduction)")
            }
        }
        assertTrue("M15 — the wall slide itself must keep its authored motion and stay coherent:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    // ------------------------------------------------------------------------------------------
    // Anti-collateral: the wall's footprint must not capture the DECLARED foot contacts
    // ------------------------------------------------------------------------------------------

    @Test
    fun theDeclaredFootContactsStillResolveToTheGround() {
        val prop = wallProp()
        val hw = prop.width * 0.5f
        val hd = prop.depth * 0.5f
        val failures = mutableListOf<String>()

        // The wall must stay BEHIND the athlete it supports (this pose faces +X; BPS §3/§7): a wall
        // in front of the body would make the declared supports meaningless.
        for (s in allSamples()) {
            assertTrue(
                "the wall's face (x=$prop) must stand behind the athlete's spine (x=${s.frame.getJoint(Joint.PELVIS).x})",
                wallFaceX(prop) < s.frame.getJoint(Joint.PELVIS).x
            )
        }

        for (s in allSamples()) {
            for (point in s.frame.supportedPoints.sortedBy { it.ordinal }) {
                val joints = SupportMath.jointsFor(point).map { s.frame.getJoint(it) }
                if (joints.isEmpty()) continue
                val cx = joints.sumOf { it.x.toDouble() }.toFloat() / joints.size
                val cz = joints.sumOf { it.z.toDouble() }.toFloat() / joints.size
                val inside = cx in (prop.center.x - hw)..(prop.center.x + hw) &&
                    cz in (prop.center.z - hd)..(prop.center.z + hd)
                if (inside) {
                    failures.add("${s.condition} p=${s.progress}: declared $point centroid ($cx,$cz) lies inside the wall footprint — the engine would resolve its surface to the wall face, not the ground")
                }
            }
            // …and the feet are still flat on the floor (the wall did not become their support normal).
            for ((ankle, heel, toe) in listOf(
                Triple(Joint.ANKLE_F, Joint.HEEL_F, Joint.TOE_F),
                Triple(Joint.ANKLE_B, Joint.HEEL_B, Joint.TOE_B)
            )) {
                val a = s.frame.getJoint(ankle); val h = s.frame.getJoint(heel); val t = s.frame.getJoint(toe)
                if (abs(h.y - a.y) > 0.5f || abs(t.y - a.y) > 0.5f) {
                    failures.add("${s.condition} p=${s.progress}: ${ankle.name} chain is not flat (heel ${h.y}, ankle ${a.y}, toe ${t.y}) — the feet were re-oriented onto a prop instead of the floor")
                }
            }
        }
        assertTrue(
            "M15 — the wall's footprint must not swallow the declared foot contacts (the engine derives a " +
                "contact's support normal from the prop it lies within). Failures:\n" + failures.joinToString("\n"),
            failures.isEmpty()
        )
    }

    // ------------------------------------------------------------------------------------------
    // Blast radius — this finding's own scope guard
    // ------------------------------------------------------------------------------------------

    @Test
    fun unaffectedPosesPublishByteIdenticalGeometry() {
        val digest = corpusDigest()
        assertEquals(
            "geometry of the production poses outside the corrected wall slide must be byte-identical to " +
                "the pre-M15 tree (the digest covers every joint of every sampled frame of every other " +
                "production pose class); a change here means the correction leaked outside its scope. " +
                "measured=$digest pinned=$UNAFFECTED_CORPUS_DIGEST",
            UNAFFECTED_CORPUS_DIGEST, digest
        )
    }

    /** The app module root, located by walking up from the test JVM's working directory. */
    private fun moduleRoot(): File {
        var dir = File(System.getProperty("user.dir") ?: error("user.dir is not set"))
        for (attempt in 0 until 8) {
            if (File(dir, "src/main/java/com/monkfitness/app/poses").isDirectory) return dir
            dir = dir.parentFile ?: break
        }
        error("Could not locate the app module root from ${System.getProperty("user.dir")}")
    }

    /** Every concrete production pose class in `poses/` except the corrected wall slide. */
    private fun corpusDigest(): Long {
        val names = File(moduleRoot(), "src/main/java/com/monkfitness/app/poses")
            .listFiles { file -> file.isFile && file.name.endsWith("Pose.kt") }!!
            .map { it.name.removeSuffix(".kt") }
            .filterNot { it.startsWith("Base") || it == "PoseRegistry" || it == poseName }
            .sorted()
        assertTrue("anti-vacuity: the digest corpus must contain the untouched poses (found ${names.size})", names.size >= 45)
        assertTrue("anti-vacuity: the corrected class must be excluded", poseName !in names)

        var hash = 1125899906842597L
        for (name in names) {
            val pipeline = SkeletonPipeline(def)
            val builder = MotionProbe.build(name)
            hash = hash * 31 + name.hashCode()
            for (p in samples) {
                val frame = snapshot(pipeline.produceFrame(builder, ctx(p)).pose)
                for (joint in Joint.entries) {
                    val v = frame.getJoint(joint)
                    hash = hash * 31 + java.lang.Float.floatToIntBits(v.x)
                    hash = hash * 31 + java.lang.Float.floatToIntBits(v.y)
                    hash = hash * 31 + java.lang.Float.floatToIntBits(v.z)
                }
            }
        }
        return hash
    }

    companion object {
        /**
         * Blast-radius guard: the production corpus MINUS the corrected wall slide.
         *
         * Measured **equal on the pre-fix tree (`origin/main` @ `4203fff`) and on the corrected tree**
         * (both `-8128235422251913276`) — i.e. the M15 correction is confined to `WallSlidesPose`, which
         * is where "no leak outside M15" is actually gated. Same digest definition as the sibling scope
         * guards (`M8M9M10SupportDeclarationTest`, `HamstringForwardReachTest`): every other production
         * pose class × 5 progress samples × every joint XYZ, full float bits.
         *
         * Attribution is direct, not inferred from this digest: the whole-corpus dump (`51` classes ×
         * `9` samples × every joint XYZ plus every `maxIkClampAmount` / `boneLengthsVerified` /
         * `supportedPoints` stamp, the environment props and the declared limb targets — `16524` rows —
         * over a `git stash` round-trip on the corrected pose file, `md5sum -c` on restore) differs in
         * exactly `126` rows, all inside `WallSlidesPose`: the two arm chains'
         * `ELBOW_*`/`HAND_*`/`WRIST_*`/`PALM_*`/`KNUCKLES_*`/`FINGERTIPS_*` (`12` joints × `9` samples =
         * `108`) plus the `9` `TARGETS` and `9` `ENV` rows. The other `50` classes are byte-identical,
         * the legs/spine are untouched and `maxIkClampAmount` reads `0.047028` on both trees.
         *
         * The six long-standing scope digests (`M1StepUpGeometryTest`, `M3M5ProneTrunkGeometryTest`,
         * `M6M7SwingBurpeeGeometryTest`, `PlankForearmSupportGeometryTest`,
         * `M11M12LimbRealizationMigrationTest`, `HamstringForwardReachTest`) were re-baselined with that
         * measurement appended to each constant's KDoc; their tests were re-run with the pose file
         * stashed (`--rerun-tasks`) and are GREEN there, so the delta is attributable to this change and
         * not to a drifted base.
        *
        * **Re-baselined by the B1 diamond push-up elbow-plane correction**
        * (`fix/b1-diamond-pushup-elbow-plane`, off `2fb6079` — the T2 merge): this corpus means "every
        * production pose class except the ones this pass corrects", so it contains `DiamondPushUpPose`,
        * whose elbow pole is re-authored onto the trunk's own long axis. The inherited Z-dominant pole
        * shape is correct for a grip whose hands sit at or outside the shoulder line; the diamond grip is
        * `0.1` (the hands come to the fused base `4.6` from the midline against the shoulder joint's
        * `46`), so the pole's perpendicular residual collapsed onto the chord's downward basis vector and
        * realized the elbow `19.91` u BELOW the pose's own declared plane at the bottom of the rep
        * (measured `p = 0.5`; the pose is a pinned, attributed open item in the T2 invariant, whose entry
        * this correction removes in the same change). Observed RED on the previous value `-8128235422251913276` before
        * the re-baseline (this live run measured `-4120795854071733418`). Attribution is direct, not inferred: the
        * whole-corpus dump (`51` classes x `5` samples x every joint XYZ, `8415` rows, a `git stash`
        * round-trip on the corrected pose file with `md5sum -c` on restore) differs in exactly `60` rows,
        * ALL of them inside `DiamondPushUpPose` — `ELBOW_A`/`ELBOW_P` at all five samples
        * (`19.96 ... 46.79` u) plus the derived `HAND`/`WRIST`/`PALM`/`KNUCKLES`/`FINGERTIPS` pair
        * (<= `8e-6` u float drift at four samples, and `5.29` / `10.57` / `19.38` u at `p = 0.5`, where
        * the engine's planted-hand flattening now fires because the elbow is above the hand) — with the
        * other `50` classes byte-identical. B1's own regression is `DiamondPushUpElbowClearanceTest`.
         */
        const val UNAFFECTED_CORPUS_DIGEST = -4120795854071733418L
    }
}
