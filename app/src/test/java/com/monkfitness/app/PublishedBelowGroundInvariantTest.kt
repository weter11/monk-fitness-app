package com.monkfitness.app

import com.monkfitness.app.animation.*
import com.monkfitness.app.poses.BirdDogPose
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **T2 — the published below-ground invariant: no joint of a published frame may pass through the
 * ground plane its own pose declares.**
 *
 * Engine contract (`docs/ENGINE.md` §4 Coordinate Systems): *"Y is up. Ground level is a Y value
 * (`GroundDefinition.level`, default 0). **'Below ground' means `y < level`**"* — a statement about
 * the whole body, not about the multi-joint subset a pose happens to declare. Every entry of
 * [Joint] is a physical body point (the spine model's `LUMBAR` segment, the `CLAVICLE_*`/`SCAPULA_*`
 * girdle bones, the derived `PALM_*`/`KNUCKLES_*`/`FINGERTIPS_*`/`HEEL_*`/`TOE_*` chains), so the
 * contract applies to **all 33 published joints of every production pose**.
 *
 * ## Why this file exists — the two existing floor instruments and what they cannot see (measured)
 *
 * This is the P11 whole-system audit's §4 class, taken to the whole body: the audit's floor findings
 * all describe *"no invariant covers them"* — the M8/M9/M10 record closes with *"(d)
 * `GluteBridgePose`/`PelvicTiltPose` publish their elbows `−30.69`/`−33.35` BELOW their own mat
 * (measured … no declared contact, so no invariant covers them) — the supine arm authoring, recorded
 * as open"*, and the M15 record names the same blindness ("the corpus invariant it owns is a Y-band
 * on **declared contacts**").
 *
 * Measured over the whole production corpus (51 classes × 5 progress × 2 frame conditions, on the
 * published pipeline path `SkeletonPipeline.produceFrame(pose, ctx)`, `origin/main` @ `07dfe38`):
 *
 * | instrument | what it keys on | below-ground pose/joint pairs it reports |
 * |---|---|---|
 * | `ExerciseValidator.validateFeetGroundPenetration` (default config) | the **6 foot joints only** (`FEET_JOINTS`), and only when `allowFootGroundPenetration = false` | **12 issues in 1 of 10 poses** — `CatCowPose`'s feet (`HEEL_*` worst `−2.422398`). The other 9 poses report **0** |
 * | `EnvironmentPenetrationTest` (B-6) | the joints of a pose's **declared support contacts** | **0 of 41** — for every one of the 10 offending poses the offending joint is OUTSIDE its declared support family ([theBelowGroundClassIsInvisibleToTheDeclarationKeyedInstrument] pins that fact) |
 * | this file | **every joint of every published frame** | **41 pose/joint pairs over 10 poses** (`DiamondPushUpPose`'s pair was corrected by B1 and its entry removed in that change — the pin table's own guard) |
 *
 * The 4 poses whose below-ground hand chains are invisible to the declared-contact instrument are
 * exactly the classes that declare NO support at all (`BirdDogPose`, `AlternatingBirdDogPose`,
 * `StaticBirdDogHoldPose`, `QuadrupedThoracicRotationsPose`) — the "flag for assignment" group in
 * `docs/STABILIZATION_AUDIT.md` §4. A declaration-keyed gate can only ever be blind to them.
 *
 * ## What this invariant distinguishes (the three categories the mission names)
 *
 *  1. **Legitimate support / contact geometry.** The invariant is a **lower bound only**:
 *     `y >= declaredGroundLevel − [groundBand]`. A planted contact is authored and derived *onto*
 *     the plane, and the Finalizer's extremity derivation flattens it there **exactly** — measured
 *     `HAND_A = 0.000000` for the whole floor-planted push-up family and a planted toe at
 *     `25.000000`/knee at `15.000000` (the definition's own contact radii, which is why the engine
 *     declares no per-contact rest height and this file asserts **no upper bound**. See
 *     [aPlantedSupportJointRestingOnTheDeclaredPlaneIsNotReported], which witnesses the exact
 *     flattening and that nothing passes under it).
 *  2. **Geometry a pose is *allowed* to publish below `y = 0`.** The repository HAS a channel for
 *     this, and it is the pose's own declaration: the plane every consumer reads is
 *     `metadata.environment.ground.level` (`SkeletonPoseFinalizer`, `SkeletonRenderer`,
 *     `SkeletonSnapshotRenderer`, `ExerciseValidator` all resolve it from the pose's environment).
 *     This invariant is measured against **that declared plane**, so a pose that legitimately works
 *     below `y = 0` declares its plane and is judged against it — no new metadata, no per-joint
 *     exemption vocabulary, and nothing hardcoded to zero ([theInvariantJudgesAgainstThePosesOwnDeclaredPlane];
 *     all 51 production poses declare `level = 0.0` today, measured). `PoseMetadata.groundHeight`
 *     exists as a second, **unread** field (grep: zero production readers) and is deliberately not
 *     resurrected here.
 *  3. **Genuine ground penetration.** A published joint below the pose's own plane by more than
 *     [groundBand] — 41 pose/joint pairs today, attributed in [knownBelowGround].
 *
 * ## Non-vacuity (the B-6 lesson, applied to the whole body)
 *
 *  * The corpus is **every** concrete production pose class in `poses/` (base classes and the
 *    registry excluded), discovered from the source tree, so a new pose cannot escape the gate.
 *  * The evaluation count is reconciled: `corpus × Joint.entries × samples × frame conditions`, and
 *    every pose must contribute — **including the poses that declare no support**, which is the
 *    silent-`continue` hole this invariant cannot have by construction (it reads no declaration;
 *    [everyPublishedJointOfEveryProductionPoseIsEvaluated] asserts it explicitly).
 *  * The frames are captured **by value**: `produceFrame(...).pose` is the Finalizer's reused output
 *    buffer, so holding pose references aliases every sample to the last one (the T-7 trap).
 *  * Both frame conditions are sampled: the genuinely cold first frame of a fresh pose on a fresh
 *    pipeline, and the frame an advancing pipeline publishes — the same shape the sibling B-6
 *    invariant uses.
 *  * The attribution table is exact and self-guarding: an unattributed violation fails, a pinned
 *    entry that no longer violates fails (so fixing a pose **forces** its entry out in the same
 *    change), a magnitude that moves fails, and every pinned pose must carry an attribution entry.
 *
 * ## Scope — this is an instrument, not a fix (deliberate)
 *
 * The below-ground geometry measured here is **not** fixed by this change; none of it is caused by
 * this file (no production file is touched: whole-tree diff = this test file + the audit record).
 * The pinned entries are the *open* items the audit already names, each with its owning pass, and
 * each entry is an exit criterion for that pass — not a tolerance, and not a license:
 * [groundBand] is unchanged and applies to every observation, and the table can only shrink by
 * fixing the pose (a stale entry fails). [attribution] documents, per pose, why the entry is open
 * and which record already owns it.
 */
class PublishedBelowGroundInvariantTest {

    private val def = SkeletonDefinition.DEFAULT_ADULT

    /** Rep samples, the same five the sibling corpus invariants use. */
    private val samples = listOf(0.0f, 0.25f, 0.5f, 0.75f, 1.0f)

    /**
     * The clearance a joint must keep below its pose's declared ground plane.
     *
     * Not a tuned threshold: the engine places a derived contact ON the plane **exactly** (measured
     * `0.000000` for the plant's planted hand across the push-up family at every sampled progress
     * and both frame conditions), and `ExerciseValidator`'s own foot rule uses `0.01`, so this band
     * only has to absorb float noise on a joint authored *at* the plane. Measured separation over
     * the whole corpus: the closest legitimate reading is `−1e-4`-scale or `0.000000`, the smallest
     * genuine violation is `−0.8409` (`QuadrupedThoracicRotationsPose` `FINGERTIPS_A`) — i.e. the
     * band sits three orders of magnitude below the smallest defect it must catch.
     */
    private val groundBand = 0.05f

    /** How close a pinned magnitude must reproduce (mirrors the sibling B-6 attribution table). */
    private val attributionTolerance = 0.01f

    /** The two frame conditions the invariant must hold under. */
    private enum class FrameCondition { COLD, PLAYING }

    /**
     * One immutable measurement. Primitives only — never a [SkeletonPose] reference: the pipeline
     * publishes the Finalizer's **reused** output buffer, so holding frames by reference aliases
     * every sample to the last one (the T-7 trap).
     */
    private data class Observation(
        val pose: String,
        val condition: FrameCondition,
        val progress: Float,
        val joint: Joint,
        val y: Float,
        val ground: Float
    ) {
        val delta: Float get() = y - ground
        val key: Pair<String, Joint> get() = pose to joint
    }

    // ------------------------------------------------------------------------------------------
    // Corpus + scan (the sibling corpus invariants' enumeration, unchanged in shape)
    // ------------------------------------------------------------------------------------------

    private fun context(p: Float) = PoseContext(
        progress = p, side = Side.RIGHT, definition = def,
        deltaTime = 0.0166f, cycleDuration = 2500f
    )

    /** Every concrete production pose class in `poses/` (base classes and the registry excluded). */
    private fun productionPoseClasses(): List<String> {
        val start = System.getProperty("user.dir")
            ?: error("user.dir is not set — the corpus enumeration needs the module working directory")
        var dir = File(start)
        var moduleRoot: File? = null
        for (attempt in 0 until 8) {
            if (File(dir, "src/main/java/com/monkfitness/app/poses").isDirectory) {
                moduleRoot = dir
                break
            }
            val parent = dir.parentFile ?: break
            dir = parent
        }
        val root = moduleRoot
            ?: error("Could not locate app module root from ${System.getProperty("user.dir")}")
        return File(root, "src/main/java/com/monkfitness/app/poses")
            .listFiles { f -> f.isFile && f.name.endsWith("Pose.kt") }!!
            .map { it.name.removeSuffix(".kt") }
            .filterNot { it.startsWith("Base") || it == "PoseRegistry" }
            .sorted()
    }

    private fun productionCorpus(): List<Pair<String, PoseBuilder>> =
        productionPoseClasses().map { it to MotionProbe.build(it) }

    /** A frame captured BY VALUE (the pipeline reuses its output buffer). */
    private fun snapshot(frame: SkeletonPose): SkeletonPose =
        SkeletonPose().apply { copyFrom(frame) }

    /** Every published joint of one captured frame, measured against that pose's declared plane. */
    private fun measure(
        frame: SkeletonPose, name: String, condition: FrameCondition, progress: Float, ground: Float
    ): List<Observation> =
        Joint.entries.map { Observation(name, condition, progress, it, frame.getJoint(it).y, ground) }

    /**
     * All observations of one pose under both frame conditions, through the production pipeline.
     * Note the pose's DECLARED plane is read from its metadata — never assumed to be zero.
     *
     * @param fresh builds a fresh, independent instance (the cold leg needs a genuinely cold pose;
     *   the twin's instance cannot be produced by name reflection).
     */
    private fun scan(fresh: () -> PoseBuilder, name: String): List<Observation> {
        val ground = fresh().metadata.environment.ground.level
        val out = mutableListOf<Observation>()

        // COLD — the genuinely cold first frame: a fresh pose instance on a fresh pipeline.
        for (p in samples) {
            val cold = SkeletonPipeline(def).produceFrame(fresh(), context(p)).pose
            out.addAll(measure(snapshot(cold), name, FrameCondition.COLD, p, ground))
        }

        // PLAYING — one builder + one pipeline advancing 0 -> 1, frames captured by value.
        val pipeline = SkeletonPipeline(def)
        val builder2 = fresh()
        for (p in samples) {
            val frame = pipeline.produceFrame(builder2, context(p)).pose
            out.addAll(measure(snapshot(frame), name, FrameCondition.PLAYING, p, ground))
        }
        return out
    }

    private fun scanCorpus(corpus: List<Pair<String, PoseBuilder>> = productionCorpus()): List<Observation> =
        corpus.flatMap { (name, _) -> scan({ MotionProbe.build(name) }, name) }

    /** The observation count the corpus implies: every joint of every pose, every sample, both conditions. */
    private fun expectedObservationCount(corpus: List<Pair<String, PoseBuilder>>): Int =
        corpus.size * Joint.entries.size * samples.size * FrameCondition.entries.size

    // ------------------------------------------------------------------------------------------
    // The rule
    // ------------------------------------------------------------------------------------------

    /** The worst (most negative) delta of every pose/joint pair. */
    private fun worstByPoseJoint(observations: List<Observation>): Map<Pair<String, Joint>, Float> =
        observations.groupBy { it.key }.mapValues { (_, v) -> v.minOf { it.delta } }

    /** Every pose/joint pair whose worst published height is below its own declared plane by the band. */
    private fun violationsOf(observations: List<Observation>): Map<Pair<String, Joint>, Float> =
        worstByPoseJoint(observations).filterValues { it < -groundBand }

    /** The pinned table's flattened form, for set algebra against the measurement. */
    private fun pinnedPairs(): Map<Pair<String, Joint>, Float> =
        knownBelowGround.entries.flatMap { (pose, joints) ->
            joints.entries.map { (joint, depth) -> (pose to joint) to depth }
        }.toMap()

    /** Violations the pin table does not name — a NEW below-ground case. */
    private fun unattributedOf(violations: Map<Pair<String, Joint>, Float>): Map<Pair<String, Joint>, Float> =
        violations.filterKeys { it !in pinnedPairs() }

    /** Pinned pairs the measurement no longer violates — a pose that was FIXED (the entry must go). */
    private fun stalePins(violations: Map<Pair<String, Joint>, Float>): Set<Pair<String, Joint>> =
        pinnedPairs().keys - violations.keys

    /** The joints a pose's own support declaration resolves to (the B-4 canonical mapping). */
    private fun declaredJointFamily(builder: PoseBuilder): Set<Joint> =
        builder.metadata.support.contacts.flatMap { SupportMath.jointsFor(it.point) }.toSet()

    // ------------------------------------------------------------------------------------------
    // The invariant — every joint of every published frame, against its pose's own declared plane
    // ------------------------------------------------------------------------------------------

    /**
     * Pre-existing, measured, **NOT fixed by T2**: the production poses that publish joints below
     * their own declared ground plane on the published pipeline path, with the worst measured depth
     * per joint (worst over both frame conditions and all five samples).
     *
     * An entry is an **open item with an owning pass** ([attribution]), not an exemption from the
     * rule: [groundBand] is unchanged and applies to every observation.
     *  * an entry that is no longer violating (a pose got fixed) makes the suite FAIL, forcing the
     *    entry's removal in that same change;
     *  * an entry whose magnitude moves fails;
     *  * a below-ground pose/joint pair that is not listed fails.
     *
     * Measured on `origin/main` @ `07dfe38`, 5 progress samples × 2 frame conditions; the depths are
     * identical under both conditions for every entry (the geometry is not frame-condition
     * sensitive here).
     */
    private val knownBelowGround: Map<String, Map<Joint, Float>> = mapOf(
        "AlternatingBirdDogPose" to mapOf(
            Joint.PALM_A to -5.0411f, Joint.PALM_P to -5.0411f,
            Joint.KNUCKLES_A to -10.0823f, Joint.KNUCKLES_P to -10.0823f,
            Joint.FINGERTIPS_A to -18.4842f, Joint.FINGERTIPS_P to -18.4842f
        ),
        "BirdDogPose" to mapOf(
            Joint.PALM_A to -5.0411f, Joint.PALM_P to -5.0411f,
            Joint.KNUCKLES_A to -10.0823f, Joint.KNUCKLES_P to -10.0823f,
            Joint.FINGERTIPS_A to -18.4842f, Joint.FINGERTIPS_P to -18.4842f
        ),
        "StaticBirdDogHoldPose" to mapOf(
            Joint.PALM_A to -5.0411f, Joint.PALM_P to -5.0411f,
            Joint.KNUCKLES_A to -10.0823f, Joint.KNUCKLES_P to -10.0823f,
            Joint.FINGERTIPS_A to -18.4842f, Joint.FINGERTIPS_P to -18.4842f
        ),
        "BurpeePose" to mapOf(
            Joint.KNEE_F to -2.3537f, Joint.KNEE_B to -2.3537f
        ),
        "CatCowPose" to mapOf(
            Joint.HEEL_F to -2.4224f, Joint.HEEL_B to -2.4224f,
            Joint.ANKLE_F to -2.1292f, Joint.ANKLE_B to -2.1292f,
            Joint.TOE_F to -1.4113f, Joint.TOE_B to -1.4113f
        ),
        "DynamicWorldsGreatestStretchPose" to mapOf(
            Joint.KNEE_B to -46.1056f
        ),
        "GluteBridgePose" to mapOf(
            Joint.ELBOW_A to -30.6914f, Joint.ELBOW_P to -30.6914f
        ),
        "IsometricSidePlankPose" to mapOf(
            Joint.KNEE_B to -25.8897f
        ),
        "PelvicTiltPose" to mapOf(
            Joint.ELBOW_A to -33.3501f, Joint.ELBOW_P to -33.3501f,
            Joint.HEAD_POS to -2.5384f, Joint.NECK_END to -2.5295f,
            Joint.CHEST to -0.3659f, Joint.SHOULDER_A to -0.3659f, Joint.SHOULDER_P to -0.3659f
        ),
        "QuadrupedThoracicRotationsPose" to mapOf(
            Joint.PALM_P to -5.2192f, Joint.KNUCKLES_P to -10.4384f,
            Joint.FINGERTIPS_P to -19.1372f, Joint.FINGERTIPS_A to -0.8409f
        )
    )

    /**
     * Why each pinned entry is open, and which record already owns it. Data, not prose decoration:
     * [everyPinnedEntryCarriesItsAttribution] asserts this table and [knownBelowGround] cover exactly
     * the same poses, so a new entry cannot be added without stating what owns it.
     */
    private val attribution: Map<String, String> = mapOf(
        "AlternatingBirdDogPose" to
            "undeclared class (docs/STABILIZATION_AUDIT.md §4 item 2's seven 'flagged for assignment' " +
            "poses: no M-number assigns them a support declaration); the derived hand chain " +
            "(PALM/KNUCKLES/FINGERTIPS, both hands) hangs under the mat at EVERY phase",
        "BirdDogPose" to
            "same undeclared class as AlternatingBirdDogPose; the derived hand chain is the same geometry " +
            "and is frame-independent (measured identical at all five samples, both conditions)",
        "StaticBirdDogHoldPose" to
            "same undeclared class; the hold's derived hand chain (both hands)",
        "QuadrupedThoracicRotationsPose" to
            "same undeclared class; the P hand's derived chain (the A hand's FINGERTIPS_A only dips " +
            "-0.8409 at p=0.0, the pose's own rotation phase)",
        "BurpeePose" to
            "M7's rep geometry (the plant/pivot schedule) — the pose declares its two HANDS (M8/M9/M10 " +
            "pass), and the M8/M9/M10 record's open item (b) already names the FEET as the remaining " +
            "one-line follow-up; the knees are the joints that pass under during the plant phase",
        "CatCowPose" to
            "recorded OPEN in the M11/M12 record clause (c): 'the realized ankle now sits 2.1292 u below " +
            "the pose's own mat at p = 1.0'; the pose's declaration is its four-point base (hands + " +
            "knees), so its feet are outside the declared family",
        "DynamicWorldsGreatestStretchPose" to
            "the lunge's back knee never reaches the mat; the pose declares LEFT_FOOT/RIGHT_FOOT/" +
            "RIGHT_HAND (M8/M9/M10), so its knee is outside the declared family; not named by any " +
            "M-number — flagged for assignment",
        "GluteBridgePose" to
            "M10 owns its declaration; the supine arm authoring is recorded OPEN in the M8/M9/M10 " +
            "record clause (d): 'publish their elbows -30.69/-33.35 BELOW their own mat (measured, " +
            "unchanged by this pass: no declared contact, so no invariant covers them)'",
        "IsometricSidePlankPose" to
            "M2's pose (the declaration side resolved by B-4, the forearm plant by B-7); the B leg is " +
            "hip-abducted and the knee is outside the declared RIGHT_FOREARM/RIGHT_FOOT family; not " +
            "named by any M-number — flagged for assignment",
        "PelvicTiltPose" to
            "M10 owns its declaration; same clause (d) as GluteBridgePose for the elbows, and the " +
            "supine layout puts CHEST/SHOULDER_*/NECK_END/HEAD_POS just under the mat at the top of " +
            "the rep (p = 1.0)"
    )

    @Test
    fun noPublishedJointPassesBelowItsPosesOwnDeclaredGroundPlane() {
        val observations = scanCorpus()
        val violations = violationsOf(observations)
        val pinned = pinnedPairs()

        val unattributed = unattributedOf(violations)
        assertTrue(
            "published joints below their pose's own declared ground plane (band = $groundBand):\n" +
                unattributed.entries.joinToString("\n") { (k, v) ->
                    "  ${k.first} ${k.second} worst=$v"
                } + "\n(attributed pre-existing items: " +
                pinned.keys.joinToString { "${it.first} ${it.second}" } + ")",
            unattributed.isEmpty()
        )

        val stale = stalePins(violations)
        assertTrue(
            "these pinned entries no longer pass below their pose's declared plane — the pose was " +
                "fixed, so the [knownBelowGround] entry (and its [attribution]) must be removed in " +
                "the same change:\n" + stale.joinToString("\n") { "  ${it.first} ${it.second}" },
            stale.isEmpty()
        )

        for ((key, pinnedDepth) in pinned) {
            val measured = violations[key]
            assertTrue(
                "${key.first} ${key.second}: a pinned entry may only contain a pair that is STILL " +
                    "violating (measured $measured, pinned $pinnedDepth)",
                measured != null && measured < -groundBand
            )
            assertEquals(
                "${key.first} ${key.second}: the pinned depth is a measurement, not a tolerance — a " +
                    "pose change that moves it must update the entry",
                pinnedDepth, measured!!, attributionTolerance
            )
        }
    }

    // ------------------------------------------------------------------------------------------
    // Non-vacuity — the whole corpus, every joint, and the poses a declaration-keyed gate skips
    // ------------------------------------------------------------------------------------------

    /**
     * The corpus is every concrete production pose class, every one of its 33 published joints is
     * measured at every sampled progress under both frame conditions, and **the poses that declare
     * no support are measured too** — the invariant reads no declaration, so it cannot inherit the
     * silent `continue` a declaration-keyed corpus has.
     */
    @Test
    fun everyPublishedJointOfEveryProductionPoseIsEvaluated() {
        val corpus = productionCorpus()
        val observations = scanCorpus(corpus)

        assertTrue(
            "anti-vacuity: the production pose corpus must be enumerated in full (found ${corpus.size})",
            corpus.size >= 45
        )
        assertEquals(
            "every joint of every pose must be evaluated on every sampled progress under both frame " +
                "conditions — the count is implied by the corpus alone, so no declaration and no " +
                "skip can hide in it",
            expectedObservationCount(corpus), observations.size
        )
        assertTrue(
            "anti-vacuity: the scan must cover the whole body of the whole corpus (evaluated ${observations.size})",
            observations.size >= 16000
        )

        val evaluatedPoses = observations.map { it.pose }.toSet()
        assertEquals(
            "every production pose must contribute observations (this invariant has NO declaration census)",
            corpus.map { it.first }.sorted(), evaluatedPoses.sorted()
        )
        for ((name, _) in corpus) {
            assertEquals(
                "$name: every published joint must be evaluated (found " +
                    observations.count { it.pose == name } + ")",
                Joint.entries.size * samples.size * FrameCondition.entries.size,
                observations.count { it.pose == name }
            )
        }

        // The hole this invariant exists to close, stated as data: the offending poses that a
        // declaration-keyed corpus skips entirely.
        val undeclaringViolating = corpus
            .filter { (_, b) -> b.metadata.support.contacts.isEmpty() }
            .map { it.first }
            .filter { it in knownBelowGround.keys }
        assertEquals(
            "the poses with no support declaration at all must be among the measured ones — this is " +
                "the requirement that the invariant is non-vacuous for an undeclared pose",
            listOf(
                "AlternatingBirdDogPose", "BirdDogPose", "QuadrupedThoracicRotationsPose",
                "StaticBirdDogHoldPose"
            ),
            undeclaringViolating.sorted()
        )
    }

    /** Every pinned pose states what owns it, and no pose is pinned without a justification. */
    @Test
    fun everyPinnedEntryCarriesItsAttribution() {
        assertEquals(
            "the pinned table and the attribution table must cover exactly the same poses — a new " +
                "below-ground entry cannot be added without naming what owns it",
            knownBelowGround.keys.sorted(), attribution.keys.sorted()
        )
        for ((pose, reason) in attribution) {
            assertTrue("$pose: the attribution must name an owner/record", reason.length > 40)
        }
    }

    // ------------------------------------------------------------------------------------------
    // The three categories, each witnessed
    // ------------------------------------------------------------------------------------------

    /**
     * **Category (a) — legitimate support geometry is not a violation.** A planted contact is
     * derived ONTO the plane: the floor-planted push-up family's lowest published joint is exactly
     * `0.000000` at every sample and under both conditions, i.e. the body touches the plane and
     * (`DiamondPushUpPose` joined this list when B1 corrected its elbow pole: it was the one
     * floor-planted push-up whose realized elbow used to pass below the same plane, and with the
     * elbow above the hand its derived hand chain flattens onto the plane like its siblings'.)
     *
     * This is also why the invariant asserts **no upper bound**: a planted toe at `25.000000` and a
     * planted knee at `15.000000` are the definition's own contact radii, not defects.
     */
    @Test
    fun aPlantedSupportJointRestingOnTheDeclaredPlaneIsNotReported() {
        val plantedPoses = listOf(
            "StandardPushUpPose", "WidePushUpPose", "MilitaryPushUpPose", "PikePushUpPose", "KneePushUpPose",
            "DiamondPushUpPose"
        )
        for (name in plantedPoses) {
            val observations = scan({ MotionProbe.build(name) }, name)
            assertTrue(
                "$name: a floor-planted push-up must publish no joint below its plane (worst " +
                    observations.minOf { it.delta } + ")",
                observations.none { it.delta < -groundBand }
            )
            val lowest = observations.minOf { it.y }
            assertEquals(
                "$name: the lowest published joint of a floor-planted pose is exactly the plane — the " +
                    "derivation flattens the contact onto it",
                0f, lowest, 1e-3f
            )
            assertTrue(
                "$name: the resting hand must be one of the joints ON the plane",
                observations.any { it.joint == Joint.HAND_A && kotlin.math.abs(it.y) <= 1e-3f }
            )
        }
    }

    /**
     * **Category (c) — a joint sunk under the plane is reported**, whatever kind of joint it is: a
     * declared contact joint, an IK-realised joint, and a trunk joint are all covered, because the
     * invariant iterates [Joint.entries] and not a contact family.
     */
    @Test
    fun aJointSunkBelowTheDeclaredPlaneIsReported() {
        val name = "StandardPushUpPose"
        val builder = MotionProbe.build(name)
        val ground = builder.metadata.environment.ground.level
        val frame = snapshot(SkeletonPipeline(def).produceFrame(builder, context(0.5f)).pose)

        val baseline = violationsOf(measure(frame, name, FrameCondition.COLD, 0.5f, ground))
        assertTrue(
            "control precondition: the unperturbed push-up must have no below-ground joint (was $baseline)",
            baseline.isEmpty()
        )

        val sinks = mapOf(Joint.HAND_A to 60f, Joint.ELBOW_P to 80f, Joint.HEAD_POS to 80f)
        val sunk = SkeletonPose().apply {
            copyFrom(frame)
            for ((joint, depth) in sinks) getJoint(joint).y -= depth
        }
        val reported = violationsOf(measure(sunk, name, FrameCondition.COLD, 0.5f, ground))
        val expected = sinks.keys.filter { frame.getJoint(it).y - sinks[it]!! < ground - groundBand }.toSet()
        assertEquals(
            "every sunk joint must be reported (the perturbation must not be vacuous)",
            sinks.keys, expected
        )
        assertEquals(
            "a contact joint, an IK-realised joint and a trunk joint must all be reported",
            expected.map { name to it }.toSet(), reported.keys
        )
        for (joint in expected) {
            assertEquals(
                "$joint: the reported depth must be the perturbed depth",
                frame.getJoint(joint).y - sinks[joint]!!, reported[name to joint]!!, 1e-3f
            )
        }
    }

    /**
     * **Category (b) — geometry below `y = 0` is allowed if the pose declares its plane.** The
     * repository's existing channel is the pose's own `metadata.environment.ground.level`; every
     * consumer resolves the plane from there. This twin is the production bird dog (which declares
     * no support, so nothing in its geometry is derived from the plane) with the plane lowered: its
     * published geometry is byte-identical (measured), it is a pinned violation at level 0, and it
     * is not a violation at `-25` or `-50`.
     */
    @Test
    fun theInvariantJudgesAgainstThePosesOwnDeclaredPlane() {
        val production = scan({ BirdDogPose() }, "BirdDogPose")
        val productionWorst = production.minOf { it.delta }
        assertTrue(
            "precondition: the production bird dog publishes below its plane at level 0 (worst $productionWorst)",
            productionWorst < -groundBand
        )

        for (level in listOf(-25f, -50f)) {
            val twin = LoweredPlaneBirdDog(level)
            val observations = scan({ LoweredPlaneBirdDog(level) }, "LoweredPlaneBirdDog")
            assertEquals(
                "the twin must declare the plane it is judged against",
                level, twin.metadata.environment.ground.level, 0f
            )
            assertEquals(
                "lowering the DECLARED plane must not move the published geometry (the twin declares " +
                    "no support, so no derivation consumes the plane)",
                production.minOf { it.y }, observations.minOf { it.y }, 1e-4f
            )
            assertTrue(
                "a pose that declares its plane below its geometry is not penetrating it (worst " +
                    observations.minOf { it.delta } + " at level $level)",
                observations.none { it.delta < -groundBand }
            )
        }
    }

    /**
     * **The counterfactual the mission asks for — a declaration-keyed gate sees NONE of these.**
     * For every pinned violation, the offending joint is outside the pose's own declared support
     * family (`metadata.support.contacts` → the B-4 canonical mapping, the exact channel
     * `EnvironmentPenetrationTest` keys on). Measured on the pinned table: 41 of 41 — which is why
     * the B-6 invariant, the sole owner of the floor on the declaration channel, is green today
     * with all 41 pairs present. (`DiamondPushUpPose`'s pair left the table with the B1 correction:
     * its elbows were never inside its declared family either, and the pose is now a planted
     * control in [aPlantedSupportJointRestingOnTheDeclaredPlaneIsNotReported].)
     */
    @Test
    fun theBelowGroundClassIsInvisibleToTheDeclarationKeyedInstrument() {
        val corpus = productionCorpus().toMap()
        val covered = mutableListOf<String>()
        for ((pose, joints) in knownBelowGround) {
            val declared = declaredJointFamily(
                corpus[pose] ?: error("pinned pose $pose is not in the production corpus")
            )
            for (joint in joints.keys) {
                if (joint in declared) covered.add("$pose $joint")
            }
        }
        assertEquals(
            "every pinned below-ground joint must be OUTSIDE its pose's declared support family — " +
                "that is what makes this invariant the only instrument that can see them (a joint " +
                "that becomes declared is a corpus change this table must be re-measured against)",
            emptyList<String>(), covered
        )

        // And the identical statement about the INSTRUMENTS: the validator's floor rule covers the
        // 6 foot joints only, so 9 of the 10 poses cannot be seen by it at all.
        val footJoints = setOf(Joint.ANKLE_F, Joint.HEEL_F, Joint.TOE_F, Joint.ANKLE_B, Joint.HEEL_B, Joint.TOE_B)
        val validatorVisible = knownBelowGround.filterValues { joints -> joints.keys.any { it in footJoints } }
        assertEquals(
            "the validator's ground rule is a FOOT rule: exactly one of the 10 offending poses " +
                "(CatCowPose's feet) is reachable by it",
            listOf("CatCowPose"), validatorVisible.keys.toList()
        )
    }

    // ------------------------------------------------------------------------------------------
    // Sensitivity controls for the table itself
    // ------------------------------------------------------------------------------------------

    /** A pair the table does not name is reported as unattributed (the table cannot absorb it). */
    @Test
    fun anUnpinnedBelowGroundJointIsReportedAsUnattributed() {
        val name = "StandardPushUpPose"
        val builder = MotionProbe.build(name)
        val ground = builder.metadata.environment.ground.level
        val frame = snapshot(SkeletonPipeline(def).produceFrame(builder, context(0.5f)).pose)
        val sunk = SkeletonPose().apply {
            copyFrom(frame)
            // placed at an absolute depth below the plane, so the expectation is not geometry-dependent
            getJoint(Joint.CHEST).y = ground - 60f
        }
        val reported = unattributedOf(violationsOf(measure(sunk, name, FrameCondition.COLD, 0.5f, ground)))
        assertEquals(
            "a new below-ground joint of a pose that is NOT pinned must be reported unattributed",
            setOf(name to Joint.CHEST), reported.keys
        )
        assertEquals(ground - 60f, reported[name to Joint.CHEST]!!, 1e-3f)
    }

    /** A pinned pair that no longer violates is stale — that is how a fix forces its entry out. */
    @Test
    fun aPinnedPairThatNoLongerPenetratesIsReportedAsStale() {
        assertEquals(
            "with no violations at all, every pinned pair is stale",
            pinnedPairs().keys, stalePins(emptyMap())
        )
        val allViolating = pinnedPairs().mapValues { it.value - 1f }
        assertTrue(
            "with every pinned pair still violating, nothing is stale",
            stalePins(allViolating).isEmpty()
        )
        val oneFixed = knownBelowGround.keys.first() to Joint.PALM_A
        val partially = pinnedPairs().filterKeys { it != oneFixed }.mapValues { it.value - 1f }
        assertEquals(
            "fixing one pinned pair must leave exactly that pair stale",
            setOf(oneFixed), stalePins(partially)
        )
    }

    // ------------------------------------------------------------------------------------------
    // Twin
    // ------------------------------------------------------------------------------------------

    /** The production bird dog (which declares NO support) with its declared ground plane lowered. */
    private class LoweredPlaneBirdDog(level: Float) : PoseBuilder by BirdDogPose() {
        override val metadata = BirdDogPose().metadata.copy(
            environment = BirdDogPose().metadata.environment.copy(
                ground = GroundDefinition(visible = true, level = level)
            )
        )
    }
}
