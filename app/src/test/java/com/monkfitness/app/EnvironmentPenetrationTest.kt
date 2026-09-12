package com.monkfitness.app

import com.monkfitness.app.animation.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * **B-6 — the universal support-contact surface invariant, evaluated for real.**
 *
 * Engine contract (`docs/ARCHITECTURE_V2.md` §4.1 support declaration, `docs/BIOMECHANICS.md`):
 * every body point a pose declares as resting on the environment must actually lie ON the surface
 * that declaration names — it must not pass THROUGH it. The Finalizer orients a declared
 * extremity against the plane derived from `metadata.environment` + `metadata.support.contacts`
 * (`SkeletonPoseFinalizer.supportPlaneNormalFor`), so the declaration is the engine's own statement
 * of where the body touches the world, and the published frame is where that statement must hold.
 *
 * ## The B-6 defect this file replaces (active stabilization audit: "B-6 — `EnvironmentPenetrationTest`
 * vacuity"; P11 whole-system audit §4 T-1/T-2/T-6)
 *
 * The previous file *claimed* this invariant ("every body point a pose declares as resting on the
 * environment actually lies ON its support surface", "applied to EVERY pose and EVERY surface kind")
 * and simultaneously asserted nothing about most of it. Measured on the unmodified baseline
 * (`origin/main` @ `6e96275`, the B-5 merge), the old test evaluated **790** joint-observations over
 * the hand-picked 25-name `variants` list, and:
 *
 *  1. **Silent skip (`if (contacts.isEmpty()) continue`).** It enumerated 25 of the **51** concrete
 *     production pose classes; 7 of its own 25 variants declare no support at all
 *     (`GluteBridgePose`, `BirdDogPose`, `CatCowPose`, `DeadBugPose`, `SupermanPose`, `LegRaisePose`,
 *     `HipCarsPose`) and were skipped silently — and the remaining **26** production classes were
 *     never named at all. A pose that silently loses its declaration (the B-2 defect class) could
 *     therefore only *reduce* what the file asserted, never fail it.
 *  2. **A private copy of the canonical mapping that returns `emptyList()` for everything it did not
 *     enumerate.** `supportJoints()` re-derived `SupportPoint → Joint` for the third time (P11 §4
 *     T-6, the fourth copy of the side mapping B-4 established authority for) and fell through to
 *     `else -> emptyList()` for `*_FOREARM`, `*_ELBOW`, `HIPS`/`BACK`/`PELVIS` and `CUSTOM`. So every
 *     declared `*_FOREARM` contact the file DID name (`StaticForearmPlankPose`'s two, 20
 *     joint-observations) was resolved to ∅ and compared against nothing — and the side plank, which
 *     declares the same kind, was not enumerated at all.
 *  3. **A per-joint surface re-derivation** (`resolveSurfaceY(v.x, v.z, …)` inside the joint loop)
 *     instead of the engine's own rule (ONE plane per contact, from the contact's canonical joint
 *     centroid). One declared contact could therefore be split across two unrelated surfaces:
 *     measured `HangPose` at progress 0.5, the declared `LEFT_HAND` contact of a bar hang had
 *     `HAND_A` compared against the bar top (`y = 500.000`) while `PALM_A`/`KNUCKLES_A`/`FINGERTIPS_A`
 *     of the *same* contact were compared against the ground (`y = 0.000`, deltas +499.703/+499.406/+498.912).
 *  4. **Both halves of its own KDoc disclaimed or unimplemented.** The penetration check was
 *     one-sided, so the float half was invisible (that is how B-1's 69-unit floating knee passed it),
 *     and no assertion counted what was evaluated, so the ∅/skip paths above cost nothing.
 *
 * **What that vacuity hid (measured, and the reason this file exists):** with the canonical mapping
 * (`SupportMath.jointsFor`), the production pipeline's own surface rule and the whole corpus, the
 * unmodified baseline reports the two forearm planks' declared `*_FOREARM` contacts penetrating the
 * mat — `StaticForearmPlankPose` `ELBOW_A`/`ELBOW_P` down to **−44.752**, `IsometricSidePlankPose`
 * `ELBOW_P` down to **−37.863** (both poses name the mat as their support and author their forearm
 * below their own floor; every other declared-contact joint of every other production pose is inside
 * the engine's band). Exactly the class of defect the old file's ∅ mapping could not see — and the
 * same "author support elbow below their own floor" residual the B-3 record in
 * `docs/STABILIZATION_AUDIT.md` listed as the poses' own §7 debt. (B-7 has since re-authored that
 * plant, so the invariant below runs with **no attribution table at all**.)
 *
 * ## Fix shape (test-only — no production file is touched by B-6)
 *
 *  * The corpus is **every** concrete production pose class in `poses/`, and the classes that
 *    declare no support are a **pinned, exact census** ([undeclaringPoses]) instead of a `continue`:
 *    a pose that drops its declaration becomes a new census member and fails
 *    ([theDeclarationCensusDetectsASilentlyDroppedDeclaration] proves that sensitivity).
 *  * The contact's joint family comes from the ONE canonical mapping (`SupportMath.jointsFor`) and is
 *    asserted non-empty for every declared point
 *    ([everyDeclaredSupportContactResolvesThroughTheOneCanonicalMap]).
 *  * The surface is resolved ONCE per contact, by the engine's own rule
 *    ([contactSurfaceY]), and every joint of that contact is compared against that one surface
 *    ([everyJointsOfAContactIsComparedAgainstThatContactsOwnSurface] pins it).
 *  * Every observation is counted and reconciled against the count the declarations imply
 *    ([everyDeclaredContactJointIsActuallyEvaluated]) — a silent ∅/skip cannot cost nothing.
 *  * The invariant itself ([noDeclaredSupportContactPenetratesItsSupportSurface]) is the engine's
 *    unchanged 2-unit band applied to every observation. It carries **no attribution table** (B-6
 *    shipped the two plank forearms pinned there; B-7 fixed the geometry and the pins are gone).
 *
 * ## What is deliberately NOT asserted, and why (measured, not assumed)
 *
 *  * **An absolute rest HEIGHT ("no float").** The engine declares no per-contact rest height: the
 *    height a contact sits at is authored by each pose against the definition's contact-radius
 *    convention (`FootDefinition.ankleHeight = 15`, `PushUpPlank.BASE_KNEE_HEIGHT = 15`), which is
 *    why a planted push-up toe is legitimately at `y = 25.000` and a planted knee at `y = 15.000`
 *    while the contact is on the floor. Asserting a height band would mean inventing a threshold the
 *    architecture does not own; the audit's own note defers that half to its geometry follow-up.
 *  * **In-plane coplanarity of a contact's joints.** Measured per frame over the same corpus: 18
 *    pose/contact pairs are not coplanar within the band (spreads 2.1–59.8), most of them for
 *    legitimate reasons the engine models deliberately (a jump-squat foot rotating through toe-off:
 *    19.76; a pull-up hand rotating about the bar: 2.3–20.7; `PikePushUpPose`'s planted toe: 24.75).
 *    That is articulation, not a violated support; it is recorded here so a future reader does not
 *    re-derive it as a missing assertion.
 */
class EnvironmentPenetrationTest {

    private val def = SkeletonDefinition.DEFAULT_ADULT

    /** Rep samples, unchanged from the file this replaces. */
    private val samples = listOf(0.0f, 0.25f, 0.5f, 0.75f, 1.0f)

    /** The engine's penetration band (unchanged): a declared contact joint must not sit more than
     *  this far below the surface its own declaration rests on. */
    private val penetrationBand = 2.0f

    /** The two frame conditions the invariant must hold under: the genuinely cold first frame of a
     *  fresh pose on a fresh pipeline, and a frame produced mid-playback by an advancing pipeline
     *  (the exact shape the file this replaces used). */
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
        val contact: SupportPoint,
        val joint: Joint,
        val y: Float,
        val surfaceY: Float
    ) {
        val delta: Float get() = y - surfaceY
        val key: Pair<String, SupportPoint> get() = pose to contact
    }

    // ------------------------------------------------------------------------------------------
    // Corpus + scan
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

    /** The production corpus as (simple class name → a fresh builder); mirrors the sibling
     *  corpus tests' enumeration (`SupportDeclarationChannelTest`, `SupportPointSideConsumptionTest`). */
    private fun productionCorpus(): List<Pair<String, PoseBuilder>> =
        productionPoseClasses().map { it to MotionProbe.build(it) }

    /** A frame captured BY VALUE (the pipeline reuses its output buffer). */
    private fun snapshot(frame: SkeletonPose): SkeletonPose =
        SkeletonPose().apply { copyFrom(frame) }

    /**
     * The surface a declared contact rests on, resolved ONCE per contact from the contact's own
     * canonical joint centroid — the rule the engine itself uses
     * (`SkeletonPoseFinalizer.supportPlaneNormalFor`: a box/step/bench prop top when the contact's
     * centroid lies inside its footprint, otherwise the ground plane). Resolving per joint instead
     * splits one contact across unrelated surfaces (see the class KDoc, defect 3).
     */
    private fun contactSurfaceY(frame: SkeletonPose, point: SupportPoint): Float {
        val env = frame.environment
        val joints = SupportMath.jointsFor(point).map { frame.getJoint(it) }
        check(joints.isNotEmpty()) { "declared support point $point has no canonical joint family" }
        val cx = joints.sumOf { it.x.toDouble() }.toFloat() / joints.size
        val cz = joints.sumOf { it.z.toDouble() }.toFloat() / joints.size
        var surface = env.ground.level
        for (prop in env.props) {
            val fp = footprint(prop) ?: continue
            if (cx in (fp[0] - fp[3])..(fp[0] + fp[3]) && cz in (fp[2] - fp[5])..(fp[2] + fp[5])) {
                when (prop) {
                    is BoxProp, is StepProp, is BenchProp -> surface = fp[1] + fp[4]
                    is WallProp -> { /* a wall supports on its face; Y stays the ground reference */ }
                }
            }
        }
        return surface
    }

    private fun footprint(prop: EnvironmentProp): FloatArray? = when (prop) {
        is BoxProp -> floatArrayOf(prop.center.x, prop.center.y, prop.center.z, prop.width * 0.5f, prop.height * 0.5f, prop.depth * 0.5f)
        is StepProp -> floatArrayOf(prop.center.x, prop.center.y, prop.center.z, prop.width * 0.5f, prop.height * 0.5f, prop.depth * 0.5f)
        is BenchProp -> floatArrayOf(prop.center.x, prop.center.y, prop.center.z, prop.width * 0.5f, prop.height * 0.5f, prop.depth * 0.5f)
        is WallProp -> floatArrayOf(prop.center.x, prop.center.y, prop.center.z, prop.width * 0.5f, prop.height * 0.5f, prop.depth * 0.5f)
    }

    /** Measures every declared contact joint of one captured frame. */
    private fun measure(frame: SkeletonPose, name: String, condition: FrameCondition, progress: Float): List<Observation> {
        val out = mutableListOf<Observation>()
        for (point in frame.supportedPoints.toList().sortedBy { it.ordinal }) {
            val surface = contactSurfaceY(frame, point)
            for (joint in SupportMath.jointsFor(point)) {
                out.add(Observation(name, condition, progress, point, joint, frame.getJoint(joint).y, surface))
            }
        }
        return out
    }

    /** All observations of one pose under both frame conditions, through the production pipeline. */
    private fun scan(builder: PoseBuilder, name: String): List<Observation> {
        if (builder.metadata.support.contacts.isEmpty()) return emptyList()
        val out = mutableListOf<Observation>()

        // COLD — the genuinely cold first frame: a fresh pose instance on a fresh pipeline.
        for (p in samples) {
            val cold = SkeletonPipeline(def).produceFrame(MotionProbe.build(name), context(p)).pose
            out.addAll(measure(snapshot(cold), name, FrameCondition.COLD, p))
        }

        // PLAYING — one builder + one pipeline advancing 0 -> 1, frames captured by value.
        val pipeline = SkeletonPipeline(def)
        val builder2 = MotionProbe.build(name)
        for (p in samples) {
            val frame = pipeline.produceFrame(builder2, context(p)).pose
            out.addAll(measure(snapshot(frame), name, FrameCondition.PLAYING, p))
        }
        return out
    }

    private fun scanCorpus(corpus: List<Pair<String, PoseBuilder>> = productionCorpus()): List<Observation> =
        corpus.flatMap { (name, builder) -> scan(builder, name) }

    /** The observation count the declarations imply: contacts × canonical family size × samples × conditions. */
    private fun expectedObservationCount(corpus: List<Pair<String, PoseBuilder>>): Int =
        corpus.sumOf { (_, builder) ->
            builder.metadata.support.contacts.sumOf { supportContact ->
                SupportMath.jointsFor(supportContact.point).size
            } * samples.size * FrameCondition.entries.size
        }

    private fun undeclaringNames(corpus: List<Pair<String, PoseBuilder>>): List<String> =
        corpus.filter { (_, b) -> b.metadata.support.contacts.isEmpty() }.map { it.first }.sorted()

    // ------------------------------------------------------------------------------------------
    // Pinned declaration census — the classes that declare NO support on the one canonical channel
    // ------------------------------------------------------------------------------------------

    /**
     * The 26 production pose classes that declare no `metadata.support.contacts` (measured on
     * `origin/main` @ `6e96275`). This list is DATA, not a tolerance: it is the set the corpus
     * census is asserted against, so a pose that silently loses its declaration becomes a new member
     * and fails. Declaring support for the stretch family (M9) and the core/hip poses (M10) is
     * pending work tracked in `docs/STABILIZATION_AUDIT.md`; landing it moves names out of this list
     * and the census must be updated in that change.
     */
    private val undeclaringPoses = setOf(
        "AlternatingBirdDogPose", "ArmCirclesPose", "BirdDogPose", "BurpeePose", "CatCowPose",
        "CouchStretchPose", "DeadBugPose", "DynamicWorldsGreatestStretchPose", "FacePullPose",
        "GluteBridgePose", "HalfKneelingStretchPose", "HamstringStretchPose", "HipCarsPose",
        "KettlebellSwingPose", "LatStretchPose", "LegRaisePose", "MountainClimberPose",
        "PelvicTiltPose", "ProneCobraStretchPose", "QuadrupedThoracicRotationsPose",
        "ReverseSnowAngelPose", "ScapularRetractionPose", "StaticBirdDogHoldPose", "SupermanPose",
        "ThoracicExtensionPose", "WallSlidesPose"
    )

    // ------------------------------------------------------------------------------------------
    // The invariant — no attribution table: a declared contact either holds or it fails
    // ------------------------------------------------------------------------------------------

    /** The worst (most negative) delta of every declared pose/contact pair. */
    private fun worstByContact(observations: List<Observation>): Map<Pair<String, SupportPoint>, Float> =
        observations.groupBy { it.key }.mapValues { (_, v) -> v.minOf { it.delta } }

    /** Every declared pose/contact pair whose worst joint is below its own surface by the band. */
    private fun violationsOf(observations: List<Observation>): Map<Pair<String, SupportPoint>, Float> =
        worstByContact(observations).filterValues { it < -penetrationBand }

    /** Comparison tolerance for the control assertions' measured magnitudes. */
    private val measuredTolerance = 0.01f

    /**
     * The invariant itself: no declared support contact may pass through the surface its own
     * declaration names.
     *
     * This assertion is the whole check — there is **no attribution table**. Until B-7 the two
     * forearm planks were pinned here as known, measured, *unfixed* violations (the poses declared
     * the mat and authored their support elbow 38–45 units below it); B-7 re-authored that plant, so
     * the pins are gone and a new forearm penetration fails the suite instead of being absorbed.
     */
    @Test
    fun noDeclaredSupportContactPenetratesItsSupportSurface() {
        val observations = scanCorpus()
        val violations = violationsOf(observations)

        assertTrue(
            "declared support contacts below their support surface (band = $penetrationBand):\n" +
                violations.entries.joinToString("\n") { (k, v) ->
                    "  ${k.first} ${k.second} worst=$v joints=" +
                        observations.filter { it.key == k }.map { it.joint }.distinct()
                },
            violations.isEmpty()
        )
    }

    // ------------------------------------------------------------------------------------------
    // 1. Declaration census (replaces the silent `continue`)
    // ------------------------------------------------------------------------------------------

    @Test
    fun everyProductionPoseClassIsAccountedForByTheDeclarationCensus() {
        val corpus = productionCorpus()
        assertTrue(
            "anti-vacuity: the production pose corpus must be enumerated in full (found ${corpus.size})",
            corpus.size >= 45
        )
        val declaring = corpus.filter { (_, b) -> b.metadata.support.contacts.isNotEmpty() }
        assertTrue(
            "anti-vacuity: the corpus must contain declaring poses (found ${declaring.size})",
            declaring.size >= 20
        )

        val actual = undeclaringNames(corpus)
        val pinned = undeclaringPoses.sorted()
        assertEquals(
            "the set of production poses that declare NO support is pinned data, not a silent skip. " +
                "A new member means a pose lost (or never gained) its declaration — the B-2 defect class. " +
                "A missing member means a pose now declares support and this census must be updated.",
            pinned, actual
        )
        // The two B-2 victims must be on the declaring side: their declaration reaching the runtime is
        // exactly what the old file's skip could not require.
        assertTrue(
            "the two poses whose declaration used to live on a dead channel must declare support",
            declaring.map { it.first }.containsAll(listOf("StaticForearmPlankPose", "IsometricSidePlankPose"))
        )
    }

    /** Sensitivity control: the census is driven by the declaration, so dropping one must be visible. */
    @Test
    fun theDeclarationCensusDetectsASilentlyDroppedDeclaration() {
        val corpus = productionCorpus()
        val twin = DroppedDeclarationPlank()
        assertEquals(
            "the twin declares no support",
            emptySet<SupportPoint>(), twin.metadata.support.contacts.map { it.point }.toSet()
        )
        val perturbed = corpus + ("DroppedDeclarationPlank" to twin)
        val actual = undeclaringNames(perturbed)
        assertEquals(
            "the perturbed corpus must gain exactly one census member — this is what makes the pinned " +
                "census a live guard rather than a list nobody compares",
            (undeclaringPoses + "DroppedDeclarationPlank").sorted(), actual
        )
        // …and that is not what production does: the real plank declares its mat support.
        assertTrue(
            "the production forearm plank must declare support (contrast with the twin)",
            StaticForearmPlankTwin().metadata.support.contacts.isNotEmpty()
        )
    }

    // ------------------------------------------------------------------------------------------
    // 2. Resolution census (replaces the private mapping that returned emptyList())
    // ------------------------------------------------------------------------------------------

    @Test
    fun everyDeclaredSupportContactResolvesThroughTheOneCanonicalMap() {
        val corpus = productionCorpus()
        val unresolved = mutableListOf<String>()
        val publishedMismatch = mutableListOf<String>()
        for ((name, builder) in corpus) {
            val declared = builder.metadata.support.contacts.map { it.point }.toSet()
            if (declared.isEmpty()) continue
            for (point in declared) {
                // ONE canonical authority (B-4); an empty family means a declared contact that no
                // consumer can resolve — the exact hole the private `supportJoints` copy created.
                if (SupportMath.jointsFor(point).isEmpty()) {
                    unresolved.add("$name declares $point but the canonical map resolves no joint")
                }
            }
            // The declaration must reach the published frame's carrier (B-2's contract).
            for (p in listOf(0.0f, 0.5f, 1.0f)) {
                val published = SkeletonPipeline(def).produceFrame(builder, context(p)).pose.supportedPoints.toSet()
                if (published != declared) {
                    publishedMismatch.add("$name p=$p declared=$declared published=$published")
                }
            }
        }
        assertTrue("every declared support point must resolve to a joint family:\n" + unresolved.joinToString("\n"), unresolved.isEmpty())
        assertTrue("the declared support model must be the published one:\n" + publishedMismatch.joinToString("\n"), publishedMismatch.isEmpty())
    }

    // ------------------------------------------------------------------------------------------
    // 3. Observation census — a silent skip or a ∅ resolution can no longer cost nothing
    // ------------------------------------------------------------------------------------------

    @Test
    fun everyDeclaredContactJointIsActuallyEvaluated() {
        val corpus = productionCorpus()
        val observations = scanCorpus(corpus)
        val expected = expectedObservationCount(corpus)

        assertEquals(
            "every declared contact joint must be evaluated on every sampled progress under both frame " +
                "conditions — the count is implied by the declarations, so an empty resolution or a " +
                "skipped pose cannot hide in it",
            expected, observations.size
        )
        assertTrue("anti-vacuity: the scan must cover the whole corpus (evaluated ${observations.size})", observations.size >= 2000)

        val declaringPoses = corpus.filter { (_, b) -> b.metadata.support.contacts.isNotEmpty() }.map { it.first }
        val evaluatedPoses = observations.map { it.pose }.toSet()
        assertEquals(
            "every declaring pose must contribute observations",
            declaringPoses.sorted(), evaluatedPoses.sorted()
        )
        for ((name, builder) in corpus) {
            for (point in builder.metadata.support.contacts.map { it.point }) {
                val n = observations.count { it.pose == name && it.contact == point }
                assertTrue(
                    "$name/$point: declared contact must be evaluated (the old private mapping returned " +
                        "emptyList() for whole contact kinds, which is how the plank forearms escaped)",
                    n >= SupportMath.jointsFor(point).size * samples.size * FrameCondition.entries.size
                )
            }
        }
        // The forearm contacts specifically: the exact kind the private copy dropped.
        assertTrue(
            "forearm-declared contacts must be evaluated (both planks, every progress, both conditions)",
            observations.count { it.contact == SupportPoint.LEFT_FOREARM || it.contact == SupportPoint.RIGHT_FOREARM } >= 40
        )
    }

    /** Sensitivity control: the check reports a contact sunk below its own surface. */
    @Test
    fun aContactSunkBelowItsDeclaredSurfaceIsReported() {
        val name = "StandardPushUpPose"
        val pipeline = SkeletonPipeline(def)
        val builder = MotionProbe.build(name)
        val frame = snapshot(pipeline.produceFrame(builder, context(0.5f)).pose)

        val baseline = violationsOf(measure(frame, name, FrameCondition.COLD, 0.5f))
        assertTrue(
            "control precondition: the unperturbed push-up hand is not in violation (was $baseline)",
            baseline.isEmpty()
        )

        val sunk = SkeletonPose().apply {
            copyFrom(frame)
            getJoint(Joint.HAND_A).y -= 60f
        }
        val perturbed = violationsOf(measure(sunk, name, FrameCondition.COLD, 0.5f))
        assertEquals(
            "a hand 60 units below the floor must be reported — this is what makes the invariant live",
            setOf(name to SupportPoint.LEFT_HAND), perturbed.keys
        )
        assertEquals(
            "the reported penetration must be the perturbed depth",
            -60f, perturbed[name to SupportPoint.LEFT_HAND]!!, measuredTolerance
        )
    }

    // ------------------------------------------------------------------------------------------
    // 5. Frame-condition integrity — immutable snapshots, one surface per contact
    // ------------------------------------------------------------------------------------------

    /**
     * One surface per declared contact, and it is the contact's own. Measured on the baseline, the
     * replaced file's per-joint rule split single contacts across unrelated surfaces: the hang's
     * declared `LEFT_HAND` compared `HAND_A` against the bar top (`500.000`) while
     * `PALM_A`/`KNUCKLES_A`/`FINGERTIPS_A` — same declaration — were compared against the ground
     * (`0.000`). Resolving once per contact from the contact's own centroid is the engine's rule
     * (`supportPlaneNormalFor`), and it is what makes a contact's verdict a single statement.
     *
     * (Measured, for the record: under the engine's centroid rule a thin-bar grip's centroid falls
     * outside the bar footprint, so the hand contact resolves to the ground reference — the bar's
     * plane normal is +Y either way, so this is inert for the derivation, as the P11 audit notes.
     * The rule's job here is ONE surface per contact; it is not asserted to be the bar top.)
     */
    @Test
    fun everyJointsOfAContactIsComparedAgainstThatContactsOwnSurface() {
        val observations = scanCorpus()
        val split = observations.groupBy { Triple(it.pose, it.condition, it.progress) to it.contact }
            .filter { (_, v) -> v.map { it.surfaceY }.distinct().size != 1 }
        assertTrue(
            "one declared contact must be resolved against ONE surface:\n" +
                split.keys.take(10).joinToString("\n") { "${it.first} ${it.second}" },
            split.isEmpty()
        )

        // The bar family still exercises the rule (its joints straddle the bar footprint).
        val hang = observations.filter { it.pose == "HangPose" && it.contact == SupportPoint.LEFT_HAND }
        assertTrue("the bar hang must be evaluated (found ${hang.size})", hang.isNotEmpty())
        assertEquals(
            "the bar hang's declared hand contact is one surface for all four of its joints",
            1, hang.map { it.surfaceY }.distinct().size
        )
    }

    /**
     * The same resolution change also removes a false-positive class the replaced rule produced:
     * measured with the per-joint/ground rule, `UnderhandChinUpPose`'s hand joints read as
     * penetrating by up to `-5.109` — a grip on a bar being compared against the floor. Under the
     * production rule (one surface per contact, from its centroid) the chin-up's hands are not
     * violations at all.
     */
    @Test
    fun aBarGripIsNotReportedAsPenetratingTheFloor() {
        val name = "UnderhandChinUpPose"
        val builder = MotionProbe.build(name)
        val contactViolations = scan(builder, name).filter {
            it.contact == SupportPoint.LEFT_HAND || it.contact == SupportPoint.RIGHT_HAND
        }
        assertTrue(
            "the chin-up's hand contacts must be evaluated (found ${contactViolations.size})",
            contactViolations.size >= 40
        )
        assertTrue(
            "a bar grip must not be judged against the floor (worst delta " +
                f(contactViolations.minOf { it.delta }) + ")",
            contactViolations.minOf { it.delta } >= -penetrationBand
        )
    }

    /** The frames the invariant samples are distinct, immutable observations — not one reused buffer. */
    @Test
    fun sampledFramesAreDistinctObservationsNotTheReusedOutputBuffer() {
        // A pose whose rep is monotone in trunk height, so distinctness of the samples is a real
        // signal (a push-up returns to its start height at both endpoints).
        val name = "StaticForearmPlankPose"
        val pipeline = SkeletonPipeline(def)
        val builder = MotionProbe.build(name)

        val first = snapshot(pipeline.produceFrame(builder, context(samples.first())).pose)
        val firstChestBefore = first.getJoint(Joint.CHEST).y
        val rest = samples.drop(1).map { p -> snapshot(pipeline.produceFrame(builder, context(p)).pose) }
        val frames = listOf(first) + rest

        assertEquals(
            "every sampled frame must be a distinct object (the pipeline publishes a reused buffer)",
            samples.size, frames.map { System.identityHashCode(it) }.distinct().size
        )
        // Frame N must not change when frame N+1 is produced: the value read BEFORE the later frames
        // were produced must still be there afterwards.
        assertEquals(
            "the first captured frame must be unaffected by the later frames' production",
            firstChestBefore, first.getJoint(Joint.CHEST).y, 0f
        )

        val heights = frames.map { it.getJoint(Joint.CHEST).y }
        assertTrue(
            "the samples must be genuinely different observations, not one frame measured five times " +
                "(CHEST heights: $heights)",
            heights.distinct().size >= 2 && heights.max() - heights.min() > 1f
        )
    }

    // ------------------------------------------------------------------------------------------
    // Sensitivity / control twins
    // ------------------------------------------------------------------------------------------

    /** The production forearm plank, delegated so only the declaration varies. */
    private class StaticForearmPlankTwin : PoseBuilder by com.monkfitness.app.poses.StaticForearmPlankPose()

    /** The same pose with its support declaration deliberately removed (the B-2 defect class). */
    private class DroppedDeclarationPlank : PoseBuilder by com.monkfitness.app.poses.StaticForearmPlankPose() {
        override val metadata = com.monkfitness.app.poses.StaticForearmPlankPose().metadata.copy(
            support = SupportDefinition(pivot = PivotType.ELBOWS, contacts = emptySet())
        )
    }

    private fun f(v: Float) = String.format(Locale.US, "%.4f", v)
}
