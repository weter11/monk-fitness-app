package com.monkfitness.app

import com.monkfitness.app.animation.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * `WallSlidesPose`'s wall/forearm contact plane, asserted on the PUBLISHED frame.
 *
 * BPS (`Wall Slide (Standard).md` §8, §6/§11) and the Movement Ownership Matrix define the exercise by
 * one contact: "Elbows and the backs of the wrists/hands contact the wall" — the guiding constraint that
 * keeps the pattern honest ("letting the elbows or wrists peel off the wall (cheating the path)").
 *
 * The pose declares a `WallProp`, which in this engine is a plane of constant X (`width` is the X
 * extent). The arm chain was authored nowhere near that plane, and the wall did not span the arms at
 * all:
 *
 *  * `ELBOW_A.x` travels `-62.58 … -51.90` while the wall's own +X face is at `-11.000` — the elbow
 *    sits `40.9 … 51.6` u BEHIND the contact face, and the forearm's X span measures `46.9 … 65.7` of
 *    its `66` u length, i.e. the forearm runs roughly along the wall's NORMAL (through the wall)
 *    instead of lying in its plane.
 *  * The wall spans `y ∈ [0, 180]`, topping out below the athlete's own pelvis (`y = 235`), while the
 *    declared contacts sit at `y = 332.29 … 415.00` (wrist) and `y = 356.37 … 382.86` (elbow): every
 *    contact floats `152 … 235` u above the top edge, and the elbow is `21.5` u outside its
 *    `z ∈ [-80, 80]` extent.
 *  * The wall's +X face is `6` u behind the plane the spine is authored in
 *    (`PELVIS/CHEST/HEAD_POS.x = -5.000`), so the wall the athlete leans against is not the plane the
 *    arms slide on.
 *
 * The arm chain's realisation causes the first two readings: the authored elbow pole `(-1, 0, ∓1)` has
 * a negative X component, and the elbow is placed on the arc of radius `h = 65.88` perpendicular to the
 * shoulder→hand chord, so a pole whose perpendicular component points along `-X` throws the elbow ~`58`
 * u backwards out of the plane. The pose's own comment says the pole exists to "keep contact with the
 * wall plane" — it does the opposite.
 *
 * Asserted, on both frame conditions (a fresh pipeline's genuinely cold first frame, and the frames an
 * advancing pipeline publishes), plus the anti-collateral foot guard and this finding's blast-radius
 * digest:
 *
 *  1. [theForearmLiesInTheWallsOwnContactPlane] — both endpoints of each forearm (elbow + wrist) sit on
 *     the wall's own declared contact plane (band `0.5` u, the sibling guards' precision).
 *  2. [theWallContactJointsLieOnTheWallsOwnSurface] — those contacts lie inside the prop's own Y/Z
 *     extent: on the wall, not floating past its top edge or its side edge.
 *  3. [theWholeHandLiesOnTheWall] — BPS names the "backs of the wrists/hands", so the DERIVED hand chain
 *     (palm/knuckles/fingertips) must lie on the face plane too, not only the wrist joint.
 *  4. [theSlideStaysCoherentThroughTheWholeRep] — the slide itself is unchanged and coherent (the hand
 *     travels the authored `-10 → +60` slide, each arm keeps its definition bone lengths, and the wrist
 *     stays above the elbow, which is what "sliding UP the wall" means).
 *
 * [theDeclaredFootContactsStillResolveToTheGround] guards that the wall's footprint must not capture the
 * declared foot contacts: the engine resolves a contact's surface from its own centroid, so a footprint
 * swallowing the feet would orient them against the wall instead of the floor. Its green pre-fix reading
 * is also what REFUTES moving the wall's face forward onto the athlete's spine plane.
 *
 * Deliberately NOT asserted: the wall's standoff from the athlete's back. BPS also puts the head, upper
 * back and pelvis ON the wall, but the face has to stay clear of the declared foot contact's centroid,
 * so it sits `6` u behind the spine plane and that half of the contract is not modelled; asserting
 * "face == spine plane" would demand a change the engine coupling forbids.
 *
 * Measured RED on the untouched base tree (`origin/main` @ `4203fff`, `--rerun-tasks`), 5 of 6 FAILED:
 * `ELBOW_A` `51.58 … 59.65` u off the face plane, `HAND_A` `6.00` off it, the forearm's X span
 * `57.58 … 65.65` of its `66` u length; every contact `152 … 235` u above the top edge `y = 180`, the
 * elbows `21.5 … 24.5` u outside `z ∈ [-80, 80]`; `PALM_A` `+5.23 …`, `FINGERTIPS_A` `+16.89` — the
 * hand chain in FRONT of the wall; the wrist below the elbow at `p ≤ 0.125`.
 */
class M15WallSlidesWallGeometryTest {

    private val def = SkeletonDefinition.DEFAULT_ADULT

    /** A dense sweep: the contact must hold through the WHOLE motion, not at the sample points. */
    private val samples = listOf(0f, 0.125f, 0.25f, 0.375f, 0.5f, 0.625f, 0.75f, 0.875f, 1f)

    /** The engine's own contact precision: a wall contact joint must sit on the wall's contact
     *  plane within this many skeleton units. */
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
         * Measured equal on the pre-fix tree (`origin/main`) and on the corrected tree — the M15
         * correction is confined to `WallSlidesPose`, which is where "no leak outside M15" is actually
         * gated. Same digest definition as the sibling scope guards (`M8M9M10SupportDeclarationTest`,
         * `HamstringForwardReachTest`): every other production pose class × 5 progress samples × every
         * joint XYZ, full float bits.
         *
         * Corpus-sensitive by construction: adding a pose class, or moving any pose inside the corpus,
         * moves the digest. A RED here is either membership or a leak outside `WallSlidesPose` — attribute
         * it from a whole-corpus dump diffed against a pristine `origin/main` worktree, never by pasting
         * the new number in.
         */
        const val UNAFFECTED_CORPUS_DIGEST = 7262105918843113657L
    }
}
