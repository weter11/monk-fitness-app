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
         * **Re-baselined by the B2 runner's-lunge back-knee correction**
         * (`fix/b2-wgs-back-knee-plane`, off `2fb6079`): this corpus contains
         * `DynamicWorldsGreatestStretchPose`, the pose B2 corrects. Its back leg's stance is now the
         * extension the pose's own KDoc declares — the ankle authored one full chain reach behind the
         * hip, at the definition's own floor-contact height, with the knee's bend side derived from the
         * hip→ankle chord — so the realized `KNEE_B` sits `+13.162892` ABOVE the mat instead of the
         * `−46.105583` BELOW it that T2 pinned.
         * Observed RED on the previous value `-8128235422251913276` before the re-baseline (this live run measured
         * `-7273487059142510759`). Attribution is direct, not inferred: the whole-corpus dump (`51` classes × `5`
         * samples × every joint XYZ = `8415` rows, taken in a pristine `origin/main` @ `2fb6079`
         * worktree and on this tree, then diffed) differs in exactly `20` rows, ALL of them inside
         * `DynamicWorldsGreatestStretchPose` (`KNEE_B`/`ANKLE_B`/`HEEL_B`/`TOE_B` × the `5` samples,
         * max `82.2520` u at `HEEL_B`), with the other `50` classes byte-identical and the pose's
         * reachability stamp unchanged (`maxIkClampAmount` `21.640945` / `13.396454` / `7.5872955` at
         * p = 0 / 0.25 / 0.5 on BOTH trees — that clamp is the pose's support arm, and B2 does not
         * touch the arms). B2's own blast-radius guard is `WorldsGreatestStretchBackKneePlaneTest`.
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
        * the re-baseline (this live run measured `-4120795854071733418`). Attribution is direct, not
        * inferred
        * whole-corpus dump (`51` classes x `5` samples x every joint XYZ, `8415` rows, a `git stash`
        * round-trip on the corrected pose file with `md5sum -c` on restore) differs in exactly `60` rows,
        * ALL of them inside `DiamondPushUpPose` — `ELBOW_A`/`ELBOW_P` at all five samples
        * (`19.96 ... 46.79` u) plus the derived `HAND`/`WRIST`/`PALM`/`KNUCKLES`/`FINGERTIPS` pair
        * (<= `8e-6` u float drift at four samples, and `5.29` / `10.57` / `19.38` u at `p = 0.5`, where
        * the engine's planted-hand flattening now fires because the elbow is above the hand) — with the
        * other `50` classes byte-identical. B1's own regression is `DiamondPushUpElbowClearanceTest`.
         *
         * **Re-measured by the B1 integration** (this branch merges `fix/b1-diamond-pushup-elbow-plane`
         * @ `29ee54b`, off `2fb6079`, onto the B2 merge `f8f8b24`): this corpus contains BOTH corrected
         * poses, so neither pass's committed value was valid on the merged tree. Observed RED on the
         * B2-merged value `-7273487059142510759` before this re-baseline (this live run, with B1 integrated,
         * measured `-3266047490962330901`). The digest's move from the B2-merged value is B1's own delta
         * (`DiamondPushUpPose`'s `ELBOW_A`/`ELBOW_P` plus the derived hand chain — `60` rows of the
         * whole-corpus dump, per B1's own record); from the pre-B2 value it is the union of B2's `20`
         * rows and B1's `60`.
         *
         * **Re-baselined by the B3 side-plank support-leg correction** (`fix/b3-sideplank-knee-plane`,
         * rebased onto the B2 merge `f8f8b24`, with B1 integrated): this corpus contains
         * `IsometricSidePlankPose`, the pose B3 corrects — the support leg's residual knee bend is
         * authored out of the mat now (the bend plane's pole `(0, -1, 0)` → `(0, 1, 0)`), so the pose
         * publishes `KNEE_B` above its own declared plane instead of `25.8897` below it. Observed RED on
         * the B1-integrated value `-3266047490962330901` before this re-baseline (this live run measured `3910385706448508459`).
         * Attribution is direct, not inferred: the whole-corpus dump (`51` classes × `5` samples × every
         * joint XYZ, `8415` rows, over a `git stash` round-trip on the corrected pose file with
         * `md5sum -c` on restore) differs from the pre-B3 tree in exactly `5` xyz rows — all `5` samples
         * of `IsometricSidePlankPose`'s `KNEE_B` — while B1's `60` `DiamondPushUpPose` rows and B2's `20`
         * `DynamicWorldsGreatestStretchPose` rows are untouched by this pass. B3's own gate is
         * `IsometricSidePlankKneePlaneTest`.
         * **Re-baselined by the B4 supine elbow-plane correction**
         * (`fix/b4-glute-bridge-pelvic-tilt-elbow-plane`, off `ba3928b` — the B1+B2+B3 tree): this corpus
         * contains `GluteBridgePose` and `PelvicTiltPose`, the two supine poses B4 corrects. Their arms' bend
         * side is re-authored onto the poses' own lateral axis: the family's `(0, -1, ∓1)` pole spent its `-Y`
         * component on the horizontal shoulder→hand chord's DOWNWARD basis vector (measured
         * `phat_y = -0.6995 … -0.7079` against `h = 58.48 … 60.74`), realizing `ELBOW_A/P` `28.6 … 33.4` u
         * below each pose's own declared mat at EVERY phase. Both poses' elbows now lie in the floor plane
         * (`+7.1 … +12.8` u) and the engine's planted-hand flattening fires with them. Observed RED on the
         * previous value `3910385706448508459` before the re-baseline (this live run measured `-103273405706572933`). Attribution is direct,
         * not inferred: the whole-corpus dump (`51` classes × `5` samples × every joint XYZ, `8415` rows, over a
         * `git stash` round-trip on the two corrected pose files with `md5sum -c` on restore) differs in
         * exactly `116` rows, ALL of them inside those two poses (`GluteBridgePose` `56`, `PelvicTiltPose` `60`
         * — the two `ELBOW_*` plus each arm's derived `HAND`/`WRIST`/`PALM`/`KNUCKLES`/`FINGERTIPS` chain, max
         * `43.0165` u at `GluteBridgePose` `ELBOW_A` `p = 1.0`), with the other `49` classes byte-identical and
         * every pose's stamps
         * unchanged (`maxIkClampAmount` reads identically on both trees — it folds the LEGS' own
         * min-reach clamp — with `boneLengthsVerified = true` and `straightIntentDropped = false`
         * everywhere). B4's own gate is `SupineArmElbowPlaneTest`.
         *
         * **Re-baselined by the B4 trunk correction** (`fix/b4-pelvic-tilt-trunk-ground-plane`, off the
         * B4-elbow merge `7012ccb`): this corpus contains `PelvicTiltPose`, whose rigid trunk is
         * re-authored out of the mat (the tilt's DIRECTION — the pose was carrying
         * `CHEST`/`SHOULDER_A/P`/`NECK_END`/`HEAD_POS` `120/138·sin(0.12)` below a pelvis whose resting
         * layer is `14`). Observed RED on the previous value `-103273405706572933` before the
         * re-baseline (this live run measured `-3023759717240069199`). Attribution is direct, not
         * inferred: the whole-corpus dump (`51` classes × `5` samples × every joint XYZ, `8415` rows,
         * over a `git stash` round-trip on the corrected pose file with `md5sum -c` on restore) differs
         * in exactly `56` rows, ALL of them inside `PelvicTiltPose` (its trunk chain plus the arm joints
         * that hang off the moved shoulder, max `33.0581` u at `HEAD_POS` `p = 1.0`; the pose's legs,
         * pelvis and the world-origin joints are unchanged, and the whole `p = 0.0` sample is
         * byte-identical because the authored tilt is zero at rest), with the other `50` classes
         * byte-identical. The trunk's own gate is `PelvicTiltTrunkPlaneTest`.
         *
         *
         * **Re-baselined by the first reach-band cleanup batch** (`fix/reach-band-batch1-airsquat-squat`,
         * off the C2 merge `d6f4f7f`): this corpus contains `AirSquatPose` and `SquatPose`, whose
         * authored limb targets are now projected onto their own chains' reachable annulus (R2/R4) — the
         * standing-phase ankle was authored as a locked-out leg (`210.288` against the engine's `0.98`
         * extension cap at `205.800`) and the counterbalance reach never left a `9.2 … 15.9` unit radius
         * against the arm chain's `minReach = 40.134`, so the solver relocated the realized
         * end-effector along the authored ray (`4.488 … 30.934` u) and the publishable geometry WAS the
         * projection. Observed RED on the previous value `-3023759717240069199` before the re-baseline (this live run
         * measured `-2178969791688578353`). Attribution is direct, not inferred: a whole-corpus dump (`51` classes ×
         * `5` samples × every joint XYZ plus every stamp, the declared limb targets, the supported
         * points and the environment, `255` rows) diffed between this branch and a `git worktree` of
         * `origin/main` @ `d6f4f7f` differs in exactly `152` rows — `76` in each of the two poses, ALL
         * of them limb-chain joints (`KNEE_*` max `0.0495` u, `ANKLE_*`/`HEEL_*`/`TOE_*` `0.0205` u, the
         * derived `HAND`/`WRIST`/`PALM`/`KNUCKLES`/`FINGERTIPS` chain `<= 0.0056` u, `ELBOW_*`
         * `<= 0.0006` u) — with the other `49` classes byte-identical, every `support` and `env` row
         * byte-identical, and the two poses' reachability stamp dropping from `30.93442 / 30.00000 /
         * 24.24812` to exactly `0.000000` (the relocation this batch removes). The batch's own gate is
         * `SquatReachBandAuthoringTest`; the three pose files stashed on the base tree re-run this guard
         * GREEN on the pre-baseline value (measured in the same session).
          *
          * **Re-baselined by the second reach-band cleanup batch** (`fix/reach-band-batch2-deepsquat-jump-cossack`,
          * off the first reach-band batch's merge `ef9400f`): this corpus is "every production pose class
          * except the classes this correction owns", so it includes the batch's two corrected poses
          * (`DeepSquatHoldPose`, `JumpSquatPose`). Observed RED on the pre-baseline value `-2178969791688578353`
          * before the re-baseline (this live run measured `5471688281724201329` below). Attribution is direct, not
          * inferred
          * from this digest: the whole-corpus dump (`51` classes × `14` phases × every `Joint.entries` XYZ ×
          * both frame conditions — `47,124` rows, full float bits) measured on a worktree of `origin/main` @
          * `ef9400f` and on this branch differs in exactly `584` rows — `224` in `DeepSquatHoldPose`, `360` in
          * `JumpSquatPose` — ALL of them limb-chain joints (`KNEE_*` max `0.0484` u, `ANKLE_*`/`HEEL_*`/`TOE_*`
          * `0.0206` u, the derived `HAND`/`WRIST`/`PALM`/`KNUCKLES`/`FINGERTIPS` chain `0.0053` u, `ELBOW_*`
          * `0.0006` u), with the other `49` classes byte-identical — `CossackSquatPose` included, which this
          * batch deliberately does NOT change — and every pelvis/spine/girdle/head joint byte-identical, i.e.
          * the batch is target-side only (a root-side "fix" would have moved the holds' depths). The batch's
          * own gate is `ReachBandBatch2AuthoringTest`.
          *
          * **Re-baselined by the third reach-band cleanup batch**
          * (`fix/reach-band-batch3-halfkneel-hamstring-cobra`, off the #254 merge `cf8a14f`): this corpus is
          * "every production pose class except the classes this correction owns", so it contains
          * `HalfKneelingStretchPose`, `HamstringStretchPose` and `ProneCobraStretchPose` — the batch's poses. Their authored limb targets sat outside their own
          * chains' reachable annulus and are now projected onto it (R2/R4). Observed RED on the pre-baseline value `5471688281724201329` before the re-baseline
          * (this live run measured `6604227265853342977`). Attribution is direct, not inferred: the whole-corpus A/B (`51`
          * classes × `15` phases × every `Joint.entries` XYZ × both frame conditions — `60,660` rows, full
          * float bits) measured on a worktree of `origin/main` @ `cf8a14f` and on this branch differs in
          * exactly `828` rows — `450` in `HalfKneelingStretchPose`, `180` in `HamstringStretchPose`, `198` in
          * `ProneCobraStretchPose`, ALL of them limb-chain joints (the arm chain's `ELBOW_*` max `0.0356` u,
          * the derived `HAND`/`WRIST`/`PALM`/`KNUCKLES`/`FINGERTIPS` chain `0.0206` u, the tucked leg's
          * `TOE_B`/`HEEL_B`/`ANKLE_B` `0.0059` u and `KNEE_B` `0.0002` u), with the other `48` production
          * classes byte-identical — `CouchStretchPose` included, which shares this site's authoring helper
          * and is deliberately NOT re-scoped — and every pelvis/spine/girdle/head joint of the three
          * byte-identical too, i.e. the batch is target-side only (a root-side "fix" would have moved their
          * stances). The batch's own gate is `ReachBandBatch3AuthoringTest`.
          *
          * **Re-baselined by the fourth (final) reach-band cleanup batch**
          * (`fix/reach-band-batch4-remaining-candidates`, off the #255 merge `ca011ad`): this corpus is "every
          * production pose class except the classes each correction owns", so it contains the batch's five poses
          * (`CouchStretchPose`, `QuadrupedThoracicRotationsPose`, `GluteBridgePose`, `PelvicTiltPose`,
          * `ThoracicExtensionPose`). Their authored limb targets sat outside their own chains' reachable annulus and
          * are now projected onto it (R2/R4). Observed RED on the pre-baseline value `6604227265853342977` before this re-baseline
          * (the live run measured `851867046944843566`). Attribution is direct, not inferred: the whole-corpus A/B (`51` classes ×
          * `16` frames — a fresh pipeline's cold first frame plus `15` phases of an advancing pipeline — × every
          * `Joint.entries` XYZ AND every joint rotation, full float bits, `58,464` rows) measured on a worktree of
          * `origin/main` @ `ca011ad` and on this branch differs in exactly `852` rows — `240` in
          * `ThoracicExtensionPose`, `197` in `CouchStretchPose`, `176` in `PelvicTiltPose`, `146` in
          * `GluteBridgePose`, `93` in `QuadrupedThoracicRotationsPose` — i.e. `652` published joint rows, `128`
          * declared-target rows and `72` state rows whose ONLY moving field is the reachability stamp. Every differing
          * joint belongs to the pose's own corrected limb chain (arms: `ELBOW_*`/`HAND_*`/`WRIST_*`/`PALM_*`/
          * `KNUCKLES_*`/`FINGERTIPS_*`; legs: `KNEE_*`/`ANKLE_*`/`HEEL_*`/`TOE_*`), max published move `0.0324` u
          * (`QuadrupedThoracicRotationsPose`), and the other `46` production classes are byte-identical — every joint
          * ROTATION byte-identical everywhere, every pelvis/spine/girdle/head joint of the five unchanged, and every
          * support set, ground level, environment prop and state flag identical, i.e. the batch is target-side only.
          * The batch's own gate is `ReachBandBatch4AuthoringTest`.
          * **Re-baselined by the canonical-`SkeletonFactory` pose batch**
          * (`fix/canonical-skeletonfactory-pose-batch`, off the #256 merge `4a32d84`): the ten remaining
          * hand-rolled pose trees (`ArmCirclesPose`, `BurpeePose`, `FacePullPose`, `GluteBridgePose`,
          * `HipCarsPose`, `KettlebellSwingPose`, `MountainClimberPose`, `PelvicTiltPose`,
          * `ScapularRetractionPose`, `WallSlidesPose`) adopt `SkeletonFactory.createStandardSkeleton()` (the
          * M11 residue class — the last poses whose five canonical joints published at the world origin), and
          * nine of them are inside this corpus (`WallSlidesPose` is the pose this pass owns and excludes). Observed RED on the previous value `<851867046944843566>` before
          * this re-baseline (the live run measured `6564548536685897903`). Attribution is direct, not inferred: the
          * whole-corpus A/B (`51` classes × `16` frames — a fresh pipeline's cold first frame plus `15` advancing
          * phases of a long-lived pipeline — × every `Joint.entries` position AND rotation as FULL FLOAT BITS,
          * plus the published state/stamps, the declared limb targets, the built authoring intents, the support
          * declarations and the environment metadata — `32,403` rows) measured on the pristine base tree
          * (`4a32d84`) and on this tree differs in exactly `800` rows — `80` in each of the ten poses, ALL of
          * them the five canonical joints (`LUMBAR`, `CLAVICLE_A/P`, `SCAPULA_A/P`) × `16` frames, each moving
          * from the world origin to its authored pass-through transform — with every other joint position and
          * rotation, every declared limb target, every stamp, every support set and every environment row
          * byte-identical. The batch's own gate is `CanonicalSkeletonFactoryPoseBatchTest`.
         *
          * **Re-baselined by the animation-coverage phase, batch 1** (`feat/animation-coverage-01`, based on
          * `7df32c0`): this corpus is "every production pose class except its own corrected pose", so the four pose
          * classes the batch ADDED (`HorseStancePose`, `WallSitPose`, `AnkleMobilityPose`, `CalfStretchPose`) entered
          * it. Attribution measured, not inferred: the per-pose digest probe (this guard's own hashing recipe, a fresh
          * pipeline per pose) run on the pristine `origin/main` worktree and on this branch reported **all 51
          * pre-existing pose classes byte-identical** — `0` differing digests, `4` added, `0` removed — on both the
          * `4a32d84` and the rebased `7df32c0` base, so the observed RED was corpus membership, not geometry drift.
          * Pre-rebaseline measurement: `6564548536685897903`.
         *
         * **Re-baselined by the animation-coverage phase, batch 2** (`feat/animation-coverage-02`, based on the
          * #258 merge `6887738`): this corpus is "every production pose class except its own corrected pose", so the
          * four pose classes the batch ADDED (`RowsPose`, `DipsPose`, `BandPullApartPose`, `YTRaisesPose`) entered it
          * (the family base `BaseBarSupportPose` is filtered out of every corpus by the `Base*` rule). Attribution
          * measured, not inferred: the per-pose digest probe (this guard's own hashing recipe, a fresh pipeline per
          * pose) run on the pristine `origin/main` worktree (`6887738`) and on this branch reported **all `55`
          * pre-existing pose classes byte-identical** — `0` differing digests, `4` added, `0` removed — so the
          * observed RED was corpus membership, not geometry drift. Pre-rebaseline measurement: `-4681652850365577567`.
         *
         * **Re-baselined by the animation-coverage phase, batch 3** (`feat/animation-coverage-03`, based on the
         * #259 merge `139daf9`): this corpus is "every production pose class except its own corrected pose", so
         * the four pose classes the batch ADDED (`HipCirclesPose`, `LegSwingsPose`, `NinetyNinetyHipsPose`,
         * `PiriformisStretchPose`) entered it. Attribution measured, not inferred: the per-pose digest probe
         * (this guard's own hashing recipe, a fresh pipeline per pose) run on the pristine `origin/main` worktree
         * (`139daf9`) and on this branch reported **all `59` pre-existing pose classes byte-identical** — `0`
         * differing digests, `4` added, `0` removed — so the observed RED was corpus membership, not geometry
         * drift. Pre-rebaseline measurement: `-3809921464030433703`.
                  *
         * **Re-baselined by the animation-coverage phase, batch 4** (`feat/animation-coverage-04`, based on the
         * #260 merge `7fed307`): this corpus is "every production pose class except its own corrected pose", so
         * the two pose classes the batch ADDED (`ChinTuckPose`, `NeckCirclesPose` — the cervical-mobility pair)
         * entered it. Attribution measured, not inferred: the per-pose digest probe (this guard's own hashing
         * recipe, a fresh pipeline per pose) run on the pristine `origin/main` worktree (`7fed307`) and on this
         * branch reported **all `63` pre-existing pose classes byte-identical** — `0` differing digests, `2`
         * added, `0` removed — so the observed RED was corpus membership, not geometry drift. Pre-rebaseline
         * measurement: `559777758847204373`.
*/
        const val UNAFFECTED_CORPUS_DIGEST = 691826339046170470L
    }
}
