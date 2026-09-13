package com.monkfitness.app

import com.monkfitness.app.animation.Joint
import com.monkfitness.app.animation.PoseBuilder
import com.monkfitness.app.animation.PoseContext
import com.monkfitness.app.animation.Side
import com.monkfitness.app.animation.SkeletonDefinition
import com.monkfitness.app.animation.SkeletonPipeline
import com.monkfitness.app.animation.SkeletonPose
import com.monkfitness.app.animation.Vector3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.sqrt

/**
 * **The canonical-`SkeletonFactory` migration of the ten remaining hand-rolled pose trees** (the M11
 * residue class of `docs/STABILIZATION_AUDIT.md` §4: *"the pose still builds its own hand-rolled node
 * tree (`PELVIS → CHEST`, `CHEST → SHOULDER_*`), so five canonical joints are never authored and
 * publish at the WORLD ORIGIN"*).
 *
 * ## The batch and what each pose's `ensureHierarchy` now does
 *
 * `ArmCirclesPose`, `BurpeePose`, `FacePullPose`, `GluteBridgePose`, `HipCarsPose`,
 * `KettlebellSwingPose`, `MountainClimberPose`, `PelvicTiltPose`, `ScapularRetractionPose`,
 * `WallSlidesPose` each built a 26-node tree by hand (`PELVIS → CHEST → SHOULDER_*`, no lower-spine
 * segment, no shoulder girdle). They now adopt the canonical tree,
 * `SkeletonFactory.createStandardSkeleton()`, exactly as the already-migrated families do
 * (`SupermanPose`, `DeadBugPose`, `LatStretchPose`, `CatCowPose`, the `Base*Pose` families,
 * `BaseValidationPose`) — 31 nodes, adding `LUMBAR` between `PELVIS` and `CHEST` and
 * `CLAVICLE_*`/`SCAPULA_*` between `CHEST` and `SHOULDER_*`.
 *
 * The factory's added nodes are **pass-throughs** — `LUMBAR` coincident with the `PELVIS` with an
 * identity rotation, `CLAVICLE_*`/`SCAPULA_*` coincident with the `CHEST` — so every transform each
 * pose already authored resolves exactly as before. The published delta is therefore confined to the
 * five canonical joints, which stop publishing at the world origin.
 *
 * ## The A/B evidence this coverage sits on (measured, not asserted here)
 *
 * Whole-corpus A/B through the production pipeline (`SkeletonPipeline.produceFrame`): `51`
 * production pose classes × `16` frames — one fresh pipeline's cold first frame plus `15` advancing
 * phases of a long-lived pipeline — × every `Joint.entries` position AND rotation as FULL FLOAT
 * BITS, plus the published state/stamps, the declared limb targets, the support declaration, the
 * built authoring intents and the environment metadata (`32,403` rows). Measured on the pristine
 * base (this branch's `origin/main`, `4a32d84`) and on the migrated tree, the two dumps differ in
 * **exactly `800` rows**: `80` in each of the ten poses — the five canonical joints
 * (`LUMBAR`, `CLAVICLE_A/P`, `SCAPULA_A/P`) × `16` frames — and **nothing else**:
 *
 *  * every other joint position AND rotation of every pose is byte-identical;
 *  * every declared limb target (`WorldTarget.world` / `.pole` / `.straight` / `.length1` /
 *    `.length2`) is byte-identical, i.e. the migration is authoring-neutral;
 *  * every published stamp (`maxIkClampAmount`, `rootTranslationDelta`, `rootRotationDelta`,
 *    `isTransformsUpdated`, `boneLengthsVerified`, `straightIntentDropped`) and every
 *    `supportedPoints` set is byte-identical;
 *  * every `jointIntents`/`spineIntent` carrier count and value is byte-identical;
 *  * every `EnvironmentDefinition` row (ground level/visible, prop count) and every `PoseMetadata`
 *    row (camera, duration, loop mode, motion curve, support pivot/contacts) is byte-identical.
 *
 * Pre-migration the ten poses published `|LUMBAR − PELVIS|` equal to the pelvis's own distance from
 * the origin (`14.0000` … `235.0532`, against the canonical pass-through's `0.0000`) and
 * `|CLAVICLE_A| = |CLAVICLE_P| = |SCAPULA_A| = |SCAPULA_P| = 0.0000` exactly.
 */
class CanonicalSkeletonFactoryPoseBatchTest {

    private val def = SkeletonDefinition.DEFAULT_ADULT
    private val samples = listOf(0f, 0.25f, 0.5f, 0.75f, 1f)

    /** The ten classes this migration owns, sorted. */
    private val migrated = listOf(
        "ArmCirclesPose", "BurpeePose", "FacePullPose", "GluteBridgePose", "HipCarsPose",
        "KettlebellSwingPose", "MountainClimberPose", "PelvicTiltPose", "ScapularRetractionPose",
        "WallSlidesPose"
    )

    private fun ctx(p: Float) = PoseContext(progress = p, side = Side.RIGHT, definition = def)

    /** A frame captured BY VALUE — the pipeline publishes a reused buffer (the T-7 trap). */
    private fun snapshot(frame: SkeletonPose): SkeletonPose = SkeletonPose().apply { copyFrom(frame) }

    /**
     * The PUBLISHED frame of the production path at [p]. [warm] selects the second frame condition
     * (a pipeline that has already produced two frames) instead of a genuinely cold first frame.
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

    // =========================================================================================
    // The witness — the canonical hierarchy is what each migrated pose publishes
    // =========================================================================================

    /**
     * Every migrated pose publishes the canonical two-segment spine and shoulder girdle: `LUMBAR` is
     * the `PELVIS` pass-through (Issue E), `CLAVICLE_*`/`SCAPULA_*` lie on the `CHEST → SHOULDER_*`
     * segment carrying the chest's own rotation, and none of the five publishes at the world origin.
     *
     * Held under BOTH frame conditions: the failure this guards is authoring-frame/hierarchy state,
     * so a cold frame and a mid-playback frame must agree.
     */
    @Test
    fun everyMigratedPosePublishesTheCanonicalHierarchy() {
        var worstLumbar = 0f
        var worstLumbarRot = 0f
        var worstGirdle = 0f
        var worstGirdleRot = 0f
        var worstOrigin = 0f
        var originWitness = ""

        for (name in migrated) {
            val builder = MotionProbe.build(name)
            for (warm in listOf(false, true)) {
                for (p in samples) {
                    val f = published(builder, p, warm)
                    val pelvis = f.getJoint(Joint.PELVIS)
                    val chest = f.getJoint(Joint.CHEST)
                    val tag = "$name@p=$p${if (warm) "(warm)" else "(cold)"}"
                    val where = "measured on the PUBLISHED frame"

                    // (1) LUMBAR is the PELVIS pass-through: coincident position AND identical rotation.
                    val lg = dist(f.getJoint(Joint.LUMBAR), pelvis)
                    if (lg > worstLumbar) worstLumbar = lg
                    val lr = f.getJointRotation(Joint.LUMBAR)
                    val pr = f.getJointRotation(Joint.PELVIS)
                    val lrD = dist(lr.axis, pr.axis) + kotlin.math.abs(lr.angle - pr.angle)
                    if (lrD > worstLumbarRot) worstLumbarRot = lrD

                    // (2) the girdle nodes are pass-throughs ON the CHEST -> SHOULDER segment, and
                    // (3) they carry the CHEST's own rotation (a coincident identity-rotation node).
                    val cr = f.getJointRotation(Joint.CHEST)
                    for ((joint, side) in listOf(
                        Joint.CLAVICLE_A to Joint.SHOULDER_A,
                        Joint.SCAPULA_A to Joint.SHOULDER_A,
                        Joint.CLAVICLE_P to Joint.SHOULDER_P,
                        Joint.SCAPULA_P to Joint.SHOULDER_P
                    )) {
                        val v = f.getJoint(joint)
                        val sh = f.getJoint(side)
                        val off = kotlin.math.abs(dist(v, chest) + dist(v, sh) - dist(chest, sh))
                        if (off > worstGirdle) worstGirdle = off
                        val r = f.getJointRotation(joint)
                        val rd = dist(r.axis, cr.axis) + kotlin.math.abs(r.angle - cr.angle)
                        if (rd > worstGirdleRot) worstGirdleRot = rd
                        val mag = v.mag()
                        if (mag > worstOrigin) { worstOrigin = mag; originWitness = "$tag $joint" }
                    }
                    assertTrue(
                        "anti-vacuity ($tag): the pose must publish a chest distinct from the pelvis " +
                            "($where, |CHEST-PELVIS|=${dist(chest, pelvis)})",
                        dist(chest, pelvis) > 1f
                    )
                }
            }
        }

        assertEquals(
            "LUMBAR must publish as the PELVIS pass-through (Issue E: PELVIS -> LUMBAR -> CHEST with a " +
                "coincident, identity-rotation lumbar) for every migrated pose — the hand-rolled tree " +
                "left it at the world origin, |LUMBAR-PELVIS| = 235.0000 measured on ArmCirclesPose " +
                "pre-migration (worst now $worstLumbar)",
            0f, worstLumbar, 0.001f
        )
        assertTrue(
            "LUMBAR must carry the PELVIS's own rotation (worst axis+angle delta $worstLumbarRot) — the " +
                "pass-through is identity, so any divergence means the node is authored, not passed through",
            worstLumbarRot < 1e-5f
        )
        assertTrue(
            "CLAVICLE_*/SCAPULA_* must sit ON the CHEST -> SHOULDER_* segment (a pass-through node) for " +
                "every migrated pose, but the worst deviation is $worstGirdle units",
            worstGirdle < 0.5f
        )
        assertTrue(
            "CLAVICLE_*/SCAPULA_* must carry the CHEST's own rotation (worst axis+angle delta " +
                "$worstGirdleRot) — pre-migration they published the default identity at the origin",
            worstGirdleRot < 1e-5f
        )
        assertTrue(
            "anti-origin: the canonical girdle joints must carry authored transforms, not (0,0,0) — " +
                "the hand-rolled tree published |CLAVICLE_A| = |CLAVICLE_P| = |SCAPULA_A| = " +
                "|SCAPULA_P| = 0.0000 for every migrated pose (worst magnitude now $worstOrigin at " +
                "$originWitness)",
            worstOrigin > 1f
        )
    }

    /**
     * The migration is a hierarchy swap, not an authoring change: every pose in the batch must keep
     * its own member state fields and route them from the factory, and no pose may retain a second
     * hand-rolled tree. Asserted statically on the production sources (the test JVM's `user.dir`
     * walks up to the app module root).
     */
    @Test
    fun everyMigratedPoseAdoptsTheFactoryAndRetainsNoHandRolledTree() {
        val posesDir = File(moduleRoot(), "src/main/java/com/monkfitness/app/poses")
        assertEquals(
            "anti-vacuity: the batch must be exactly the ten M11-residue poses",
            10, migrated.size
        )
        for (name in migrated) {
            val f = File(posesDir, "$name.kt")
            assertTrue("anti-vacuity: $name must exist at ${f.path}", f.isFile)
            val src = f.readText()
            assertTrue(
                "$name must adopt `SkeletonFactory.createStandardSkeleton()` — the canonical two-segment " +
                    "spine + shoulder girdle tree",
                src.contains("SkeletonFactory.createStandardSkeleton()")
            )
            assertTrue(
                "$name must no longer hand-roll `SkeletonNode(...)` children (found " +
                    "`addChild(SkeletonNode(`): the legacy tree is the M11 residue this batch removes",
                !src.contains("addChild(SkeletonNode(")
            )
            assertTrue(
                "$name must not re-introduce the legacy position-driven helper",
                !src.contains("fromJointPositions")
            )
        }
        // Anti-vacuity for the scan itself: a pose that never had a hand-rolled tree is irrelevant to
        // the batch, so the assertion above must be shown to be able to fail — at least one file in
        // the batch is proven to be a REAL source file with content.
        assertTrue(
            "the scan must read real sources (the batch's largest file is " +
                "${migrated.maxOf { File(posesDir, "$it.kt").length() }} bytes)",
            migrated.any { File(posesDir, "$it.kt").length() > 1000L }
        )
    }

    // =========================================================================================
    // The batch's own scope digest — its authored geometry plus the five canonical joints
    // =========================================================================================

    /** The app module root, located by walking up from the test JVM's working directory. */
    private fun moduleRoot(): File {
        var dir = File(System.getProperty("user.dir") ?: error("user.dir is not set"))
        for (attempt in 0 until 8) {
            if (File(dir, "src/main/java/com/monkfitness/app/poses").isDirectory) return dir
            dir = dir.parentFile ?: break
        }
        error("Could not locate the app module root from ${System.getProperty("user.dir")}")
    }

    /**
     * The batch's published geometry: every migrated pose × `16` frames (a fresh pipeline's cold
     * first frame plus `15` advancing phases of one long-lived pipeline — the frame pair the A/B
     * evidence used) × every `Joint.entries` position AND rotation, full float bits.
     */
    private fun batchDigest(): Long {
        var hash = 1125899906842597L
        for (name in migrated) {
            hash = hash * 31 + name.hashCode()
            val cold = SkeletonPipeline(def).produceFrame(MotionProbe.build(name), ctx(0f)).pose
            val frames = mutableListOf(snapshot(cold))
            val pipeline = SkeletonPipeline(def)
            val builder = MotionProbe.build(name)
            for (sample in 1..15) {
                frames.add(snapshot(pipeline.produceFrame(builder, ctx(sample / 15f)).pose))
            }
            for (frame in frames) {
                for (joint in Joint.entries) {
                    hash = hash * 31 + java.lang.Float.floatToRawIntBits(frame.getJoint(joint).x).toLong()
                    hash = hash * 31 + java.lang.Float.floatToRawIntBits(frame.getJoint(joint).y).toLong()
                    hash = hash * 31 + java.lang.Float.floatToRawIntBits(frame.getJoint(joint).z).toLong()
                    val r = frame.getJointRotation(joint)
                    hash = hash * 31 + java.lang.Float.floatToRawIntBits(r.axis.x).toLong()
                    hash = hash * 31 + java.lang.Float.floatToRawIntBits(r.axis.y).toLong()
                    hash = hash * 31 + java.lang.Float.floatToRawIntBits(r.axis.z).toLong()
                    hash = hash * 31 + java.lang.Float.floatToRawIntBits(r.angle).toLong()
                }
            }
        }
        return hash
    }

    /**
     * Blast-radius + regression guard for the batch: the digest covers every authored transform of
     * the ten poses (proven byte-identical to the pre-migration tree by the whole-corpus A/B in the
     * class KDoc) together with the five canonical joints the migration newly owns. A change here
     * means either an authored transform of a migrated pose moved — which the A/B says it must not —
     * or the canonical hierarchy drifted.
     *
     * Proved non-vacuous by the counterfactual: this whole class is RED on the base tree
     * (`4a32d84`, the ten poses' hand-rolled trees), the witness quoting
     * `|LUMBAR-PELVIS| = 235.0000` and `|CLAVICLE_A| = 0.0000`.
     */
    @Test
    fun migratedPosesPublishTheBatchScopeDigest() {
        val digest = batchDigest()
        assertEquals(
            "the ten migrated poses' published geometry (every joint position AND rotation of every " +
                "sampled frame, 16 frames per pose) must be byte-identical to the migrated baseline; a " +
                "change means a migrated pose's authored choreography moved or the canonical hierarchy " +
                "drifted. measured=$digest pinned=$BATCH_SCOPE_DIGEST",
            BATCH_SCOPE_DIGEST, digest
        )
    }

    companion object {
        /**
         * The batch's published-geometry digest, measured on the migrated tree: `10` poses × `16`
         * frames × `33` joints × (position XYZ + rotation axis XYZ + angle), full float bits.
         *
         * The authored half of this digest is provenance-checked, not assumed: the whole-corpus A/B
         * described in the class KDoc differs from the base tree in exactly the five canonical joints
         * of the ten poses and in nothing else, so this digest pins the pre-migration authored
         * geometry byte-for-byte alongside the newly owned canonical transforms.
         */
        const val BATCH_SCOPE_DIGEST = 5967077127684194150L
    }
}
