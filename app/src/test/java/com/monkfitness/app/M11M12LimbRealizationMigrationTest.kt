package com.monkfitness.app

import com.monkfitness.app.animation.*
import com.monkfitness.app.poses.CatCowPose
import com.monkfitness.app.poses.LatStretchPose
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The published-frame residue of the M11/M12 migration for `LatStretchPose` and `CatCowPose`, measured
 * on the path the renderer and the validators read (`SkeletonPipeline.produceFrame` in the deployed
 * `IK_STAGE_ACTIVE = true` configuration). Neither literal `solveIK` bypass still exists; what is
 * asserted is the residue the two findings name:
 *
 *  - **M11-a (legacy authored hierarchy).** `LatStretchPose` still builds its OWN hand-rolled node
 *    tree, whose node set stops at `PELVIS → CHEST` and `CHEST → SHOULDER_*`. Five canonical joints are
 *    never authored, so they publish at the WORLD ORIGIN: measured `|LUMBAR − PELVIS| = 144.4507` (the
 *    pass-through is `0.0000` on every pose built from `SkeletonFactory.createStandardSkeleton()`),
 *    `|CLAVICLE_A| = |SCAPULA_A| = 0.0000`. The same class the M3/M5 pass corrected for
 *    `ReverseSnowAngelPose` ("its hand-rolled tree has no lower-spine segment, so `Joint.LUMBAR`
 *    publishes at the world origin").
 *  - **M11-b (gaze — RECORDED, deliberately NOT migrated).** The pose declares no `headTarget`, and a
 *    world-space gaze target is NOT expressible for this body: `resolveHeadTarget` writes the world
 *    delta as the neck/head LOCAL offset, which this pose's `0.95` rad trunk pitch re-applies, so the
 *    resolved head lands `0.9147` rad off the authored axis (the constraint the M3/M5 pass recorded for
 *    the prone family; `MIGRATION_RULES` A8 prohibits pose-side compensation). The authored head
 *    already lives in the chain's own frame, and [latStretchAuthoredHeadStaysOnThePosesOwnTrunkAxis]
 *    pins that resolved behaviour — the trap that turns RED the moment someone declares the naive world
 *    target.
 *  - **M12-a (raw floor-frame limb targets outside their own chain's band).** `CatCowPose` authors its
 *    leg end-effector as an absolute world literal `(50, ankleHeight, ±hipWidth)`, which its own leg
 *    chain cannot fold to at ANY phase: `42.5 … 45.0` against
 *    `SkeletonMath.minReach(112, 98, 30°) = 56.0090`. The engine answers by relocating the realized
 *    foot (`maxIkClampAmount = 11.0090 → 16.0090`, realised `ANKLE_F` `13.5090` units off the declared
 *    point at `p = 0.5`) — the unreachable-authoring-silently-solver-clamped defect class.
 *  - **M12-b (no support model on the canonical channel).** The quadruped declares NO
 *    `metadata.support.contacts`, so `SkeletonPose.supportedPoints` publishes EMPTY for a pose whose
 *    BPS §8 base is a four-point contact (`Cat-Cow (Reps).md`: "both hands … and both knees … remain in
 *    contact with the floor"). `EnvironmentPenetrationTest`'s pinned census names this pose.
 *
 * Deliberately NOT asserted: no tolerance is added to any pre-existing assertion, nothing is
 * `@Ignore`d or `assume`d, and the pose's AUTHORED choreography is not re-litigated — the leg pole's
 * lateral component (which splays the knee ≈`69` units outside the hip line) and the spine articulation
 * authored as the pelvis tilt are reported as open items rather than fixed.
 */
class M11M12LimbRealizationMigrationTest {

    private val def = SkeletonDefinition.DEFAULT_ADULT
    private val samples = listOf(0.0f, 0.25f, 0.5f, 0.75f, 1.0f)

    /** The leg chain's declared realization context (`CatCowPose`'s two leg bakes). */
    private val legConstraint = def.legIKConstraint
    private val legMinReach = SkeletonMath.minReach(def.thighLength, def.shinLength, legConstraint)
    private val legMaxReach = SkeletonMath.maxReach(def.thighLength, def.shinLength, legConstraint)

    /** The engine's own reachability flag band (`M8M9M10SupportDeclarationTest`'s convention). */
    private val reachabilityFlagBand = 0.1f

    private fun ctx(p: Float) = PoseContext(p, Side.RIGHT, def, 0.0166f, 2500f)

    /** A frame captured BY VALUE — the pipeline publishes a reused buffer (the T-7 trap). */
    private fun snapshot(frame: SkeletonPose): SkeletonPose = SkeletonPose().apply { copyFrom(frame) }

    /**
     * The PUBLISHED frame of the production path at [p], in the deployed configuration.
     *
     * [warm] selects the second frame condition: a pipeline that has already produced two frames
     * (mid-playback) instead of a genuinely cold first frame on a fresh pipeline.
     */
    private fun published(builder: PoseBuilder, p: Float, warm: Boolean = false): SkeletonPose {
        val pipeline = SkeletonPipeline(def)
        if (warm) {
            pipeline.produceFrame(builder, ctx(0.3f))
            pipeline.produceFrame(builder, ctx(0.4f))
        }
        return snapshot(pipeline.produceFrame(builder, ctx(p)).pose)
    }

    private fun dist(a: Vector3, b: Vector3): Float {
        val dx = a.x - b.x; val dy = a.y - b.y; val dz = a.z - b.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }



    /**
     * The pose's tree must cover the canonical joint set, so the two-segment spine and the shoulder
     * girdle publish real transforms instead of the world origin (the legacy-tree signature the
     * M3/M5 pass measured on `ReverseSnowAngelPose`).
     */
    @Test
    fun latStretchPublishesTheCanonicalAuthoredHierarchy() {
        val pose = LatStretchPose()
        var worstLumbar = 0f
        var worstStray = 0f
        var stray = ""

        for (p in samples) {
            val f = published(pose, p)
            val pelvis = f.getJoint(Joint.PELVIS)
            val chest = f.getJoint(Joint.CHEST)

            // (1) LUMBAR is the PELVIS pass-through: the chest resolves to the same world transform as
            // the old single PELVIS->CHEST link, so a non-zero gap is the unpublished segment the
            // hand-rolled tree leaves behind.
            val lumbarGap = dist(f.getJoint(Joint.LUMBAR), pelvis)
            if (lumbarGap > worstLumbar) worstLumbar = lumbarGap

            // (2) CLAVICLE/SCAPULA are pass-throughs on the CHEST->SHOULDER segment; (3) no canonical
            // joint may publish at the world origin.
            for ((joint, side) in listOf(
                Joint.CLAVICLE_A to Joint.SHOULDER_A,
                Joint.SCAPULA_A to Joint.SHOULDER_A,
                Joint.CLAVICLE_P to Joint.SHOULDER_P,
                Joint.SCAPULA_P to Joint.SHOULDER_P
            )) {
                val v = f.getJoint(joint)
                val strayMag = v.mag()
                if (strayMag > worstStray) { worstStray = strayMag; stray = "$joint@p=$p" }
                val onSegment = dist(v, chest) + dist(v, f.getJoint(side)) - dist(chest, f.getJoint(side))
                assertTrue(
                    "LatStretchPose $joint@p=$p must sit on the CHEST->$side segment (a pass-through " +
                        "node), but it is $onSegment units off it — v=$v chest=$chest " +
                        "shoulder=${f.getJoint(side)}",
                    onSegment < 0.5f
                )
            }
        }
        assertEquals(
            "LatStretchPose must publish LUMBAR as the PELVIS pass-through (Issue E: PELVIS -> LUMBAR " +
                "-> CHEST with a coincident, identity-rotation lumbar) — the hand-rolled tree leaves " +
                "it at the world origin, |LUMBAR - PELVIS| = $worstLumbar",
            0f, worstLumbar, 0.001f
        )
        assertTrue(
            "anti-origin: the canonical girdle joints must carry authored transforms, not (0,0,0) " +
                "(worst |joint| = $worstStray at $stray)",
            worstStray > 1f
        )
    }



    /**
     * M11-b — the head is authored in the CHAIN'S OWN FRAME, the only representation that can express
     * this body orientation; the published frame must realize exactly it.
     *
     * `resolveHeadTarget` (the sole head writer) writes a world delta verbatim as the neck/head LOCAL
     * offset, which the neck's parent rotation re-applies — so a world `headTarget` on a trunk pitched
     * `0.95` rad lands the head `0.9147` rad off the authored axis (measured; the M3/M5 `SupermanPose`
     * precedent). The pose declares NO gaze target, and this guard pins what must survive: the published
     * neck/head chain lies on the authored trunk axis at the authored bone lengths.
     *
     * GREEN by construction on the untouched tree, and a trap for the naive "declare the gaze"
     * migration — that RED is how the `0.9147` rad figure was measured.
     */
    @Test
    fun latStretchAuthoredHeadStaysOnThePosesOwnTrunkAxis() {
        val pose = LatStretchPose()
        val leanAngle = 0.95f // the pose's authored trunk lean (about +Z), unchanged by this migration
        val authored = Vector3(sin(leanAngle), cos(leanAngle), 0f).normalize()
        var worstDir = 0f
        var worstBone = 0f

        for (p in samples) {
            val f = published(pose, p)
            val n = f.getJoint(Joint.NECK_END)
            val h = f.getJoint(Joint.HEAD_POS)
            val bone = dist(n, h)
            worstBone = maxOf(worstBone, abs(bone - 18f))
            val dir = Vector3(h.x - n.x, h.y - n.y, h.z - n.z).normalize()
            worstDir = maxOf(worstDir, dist(dir, authored))
        }
        assertTrue(
            "the published NECK_END->HEAD_POS bone must stay at the authored ~18 units " +
                "(worst error $worstBone)",
            worstBone < 0.18f
        )
        assertTrue(
            "LatStretchPose's published head must lie on the pose's own authored trunk axis " +
                "(worst direction error $worstDir). A world-space `headTarget` here would resolve " +
                "the head 0.9147 rad off it — `resolveHeadTarget` writes the world delta as a LOCAL " +
                "offset and the pitched trunk re-applies it (the recorded M11-b finding; the " +
                "M3/M5 SupermanPose precedent authors the head in the chain's own frame for exactly " +
                "this reason)",
            worstDir < 1e-3f
        )
    }



    /**
     * Reachable-by-construction: a pose authors the DIRECTION and stance it wants, and the declared
     * target must lie inside its own chain's band, so the realized limb is exactly what the pose
     * declared and the reachability signal stays honest.
     */
    @Test
    fun catCowLegTargetsAreReachableAsAuthored() {
        val pose = CatCowPose()
        val witnesses = mutableListOf<String>()

        for (p in samples) {
            val built = pose.build(ctx(p))
            val f = published(pose, p)
            assertEquals(
                "anti-vacuity: CatCowPose declares its four limbs (found ${built.limbTargets.size})",
                4, built.limbTargets.size
            )
            for ((end, hip) in listOf(Joint.ANKLE_F to Joint.HIP_F, Joint.ANKLE_B to Joint.HIP_B)) {
                val target = built.limbTargets.first { it.joint == end }
                val d = dist(target.world, f.getJoint(hip))
                witnesses.add("$end@p=$p d=$d band=[$legMinReach,$legMaxReach] clamp=${f.maxIkClampAmount}")
                assertTrue(
                    "CatCowPose $end@p=$p is authored $d from its chain root, OUTSIDE the leg chain's " +
                        "reachable band [$legMinReach, $legMaxReach] — the solver relocates the " +
                        "realized foot instead of realizing the declared target",
                    d >= legMinReach - 0.5f && d <= legMaxReach + 0.5f
                )
                val realised = dist(f.getJoint(end), target.world)
                assertTrue(
                    "the published $end@p=$p must BE the declared target (relocation $realised)",
                    realised <= 0.5f
                )
            }
            assertTrue(
                "the engine's own reachability flag must not fire for CatCowPose at p=$p " +
                    "(maxIkClampAmount=${f.maxIkClampAmount}, band=$reachabilityFlagBand) — the " +
                    "declared leg targets are unreachable authoring",
                f.maxIkClampAmount <= reachabilityFlagBand
            )
        }
        assertTrue("witnesses: $witnesses", witnesses.size == samples.size * 2)
    }



    /**
     * BPS §8 (`Cat-Cow (Reps).md`): "Four-point base throughout: both hands (palm/carpal arch) and both
     * knees (patella/shin on a padded surface) remain in contact with the floor." The declaration
     * belongs on the ONE canonical channel (`metadata.support.contacts`) and must reach the published
     * carrier under BOTH frame conditions — a declaration the carrier never sees is inert.
     */
    @Test
    fun catCowDeclaresItsFourPointBaseOnTheCanonicalChannel() {
        val pose = CatCowPose()
        val declared = pose.metadata.support.contacts.map { it.point }.toSet()
        assertEquals(
            "CatCowPose's BPS §8 base is a four-point contact (both hands + both knees); the " +
                "declaration must name exactly that on `metadata.support.contacts`",
            setOf(SupportPoint.LEFT_HAND, SupportPoint.RIGHT_HAND, SupportPoint.LEFT_KNEE, SupportPoint.RIGHT_KNEE),
            declared
        )
        for (point in declared) {
            assertTrue(
                "anti-vacuity: every declared contact must resolve through the ONE canonical " +
                    "SupportPoint->Joint map (found none for $point)",
                SupportMath.jointsFor(point).isNotEmpty()
            )
        }
        assertEquals(
            "the declared pivot must be the quadruped's own support base (the KneePushUpPose " +
                "precedent: hands + knees, pivot KNEES)",
            PivotType.KNEES, pose.metadata.support.pivot
        )

        for (warm in listOf(false, true)) {
            for (p in samples) {
                val f = published(pose, p, warm)
                assertEquals(
                    "the declaration must REACH the published carrier (${if (warm) "mid-playback" else "cold"} " +
                        "frame, p=$p): published supportedPoints=${f.supportedPoints}",
                    declared, f.supportedPoints.toSet()
                )
            }
        }
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

    private fun corpusDigest(): Long {
        val names = File(moduleRoot(), "src/main/java/com/monkfitness/app/poses")
            .listFiles { file -> file.isFile && file.name.endsWith("Pose.kt") }!!
            .map { it.name.removeSuffix(".kt") }
            .filterNot { it.startsWith("Base") || it == "PoseRegistry" || it in corrected }
            .sorted()
        assertTrue(
            "anti-vacuity: the digest corpus must contain the untouched poses (found ${names.size})",
            names.size >= 45
        )
        assertTrue(
            "anti-vacuity: the correction scope must be exactly the two classes this pass names",
            corrected.size == 2
        )
        var hash = 1125899906842597L
        for (name in names) {
            val pipeline = SkeletonPipeline(def)
            val builder = MotionProbe.build(name)
            hash = hash * 31 + name.hashCode()
            for (p in samples) {
                val frame = published(builder, p)
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

    @Test
    fun unaffectedPosesPublishByteIdenticalGeometry() {
        val digest = corpusDigest()
        assertEquals(
            "geometry of the production poses outside {LatStretchPose, CatCowPose} must be " +
                "byte-identical to the pre-fix tree (every joint of every sampled frame of every " +
                "other production pose class); a change here means the migration leaked outside its " +
                "scope. measured=$digest pinned=$UNAFFECTED_CORPUS_DIGEST",
            UNAFFECTED_CORPUS_DIGEST, digest
        )
    }

    companion object {
        /** The exact classes this pass owns. */
        private val corrected = setOf("LatStretchPose", "CatCowPose")

        /**
         * Blast-radius guard: the production corpus MINUS the two corrected classes.
         *
         * Measured equal on the pre-fix tree (`origin/main`) and on the corrected tree — the pass is
         * confined to its own two poses. Same digest definition as the sibling scope guards
         * (`M8M9M10SupportDeclarationTest`, `HamstringForwardReachTest`): every other production pose
         * class × 5 progress samples × every joint XYZ, full float bits.
         *
         * Corpus-sensitive by construction: adding a pose class, or moving any pose inside the corpus,
         * moves the digest. A RED here is either membership or a leak outside the two corrected poses —
         * attribute it from a whole-corpus dump diffed against a pristine `origin/main` worktree, never
         * by pasting the new number in.
         */
        const val UNAFFECTED_CORPUS_DIGEST = -4784460043153301629L
    }
}
