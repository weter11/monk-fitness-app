package com.monkfitness.app

import com.monkfitness.app.animation.Joint
import com.monkfitness.app.animation.PoseContext
import com.monkfitness.app.animation.Side
import com.monkfitness.app.animation.SkeletonDefinition
import com.monkfitness.app.animation.SkeletonPipeline
import com.monkfitness.app.animation.SkeletonPose
import com.monkfitness.app.animation.Vector3
import com.monkfitness.app.poses.PoseRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import kotlin.math.acos
import kotlin.math.max
import kotlin.math.min

/**
 * The press and the horizontal pull articulate the shoulder girdle, not only the elbow.
 *
 * `SCAPULA -> SHOULDER` is the bone the rig actually drives (`CLAVICLE` and `SCAPULA` share a bind
 * origin, so the scapula is the girdle's moving member): the angle that bone sweeps across the rep
 * is the girdle's contribution, measured on the published frame. A press whose scapula is left at
 * identity publishes `0.00°` for the whole cycle and the whole movement is the glenohumeral hinge;
 * a pull whose blades never move leaves the bar row reading as a curl.
 *
 * Everything here is a measurement of the published frame, copied by value because the pipeline
 * hands back a reused buffer.
 */
class PushUpRowGirdleArticulationTest {

    private val definition = SkeletonDefinition.DEFAULT_ADULT

    private fun context(progress: Float) = PoseContext(
        progress = progress, side = Side.RIGHT, definition = definition, deltaTime = 0.0166f, cycleDuration = 2500f
    )

    /** One published rep, warmed exactly as the renderer warms it, captured by value. */
    private fun sweep(id: String): List<SkeletonPose> {
        val builder = PoseRegistry.getPoseConfig(id)!!.builder
        val environment = builder.metadata.environment
        val supported = builder.metadata.support.supportPoints
        val pipeline = SkeletonPipeline(definition)
        repeat(WARM_UP_FRAMES) { pipeline.produceFrame(builder.build(context(WARM_UP_PROGRESS)), environment, supported) }
        val out = ArrayList<SkeletonPose>(STEPS + 1)
        for (i in 0..STEPS) {
            val published = pipeline.produceFrame(builder.build(context(i.toFloat() / STEPS)), environment, supported).pose
            out.add(SkeletonPose().apply { copyFrom(published) })
        }
        return out
    }

    private fun bladeDirection(frame: SkeletonPose, scapula: Joint, shoulder: Joint): Vector3 {
        val a = frame.getJoint(scapula)
        val b = frame.getJoint(shoulder)
        val v = Vector3(b.x - a.x, b.y - a.y, b.z - a.z)
        return if (v.mag() < 1e-5f) v else v.normalize()
    }

    /** The girdle's sweep: the bone's worst angle off its start, and the worst step between frames, in degrees. */
    private class Sweep(val rangeDegrees: Float, val maxStepDegrees: Float)

    private fun angleDegrees(a: Vector3, b: Vector3): Float =
        Math.toDegrees(acos(a.dot(b).coerceIn(-1f, 1f).toDouble())).toFloat()

    private fun bladeSweep(frames: List<SkeletonPose>, scapula: Joint, shoulder: Joint): Sweep {
        val start = bladeDirection(frames.first(), scapula, shoulder)
        var range = 0f
        var step = 0f
        var previous = start
        for (frame in frames) {
            val direction = bladeDirection(frame, scapula, shoulder)
            range = max(range, angleDegrees(direction, start))
            step = max(step, angleDegrees(direction, previous))
            previous = direction
        }
        return Sweep(range, step)
    }

    private fun handSlipUnits(frames: List<SkeletonPose>, hand: Joint): Float {
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
        var minZ = Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
        for (frame in frames) {
            val v = frame.getJoint(hand)
            minX = min(minX, v.x); maxX = max(maxX, v.x)
            minZ = min(minZ, v.z); maxZ = max(maxZ, v.z)
        }
        val dx = maxX - minX
        val dz = maxZ - minZ
        return kotlin.math.sqrt(dx * dx + dz * dz)
    }

    @Test
    fun thePressAndTheRowSweepTheGirdleThroughTheRep() {
        for (id in ARTICULATED) {
            val frames = sweep(id)
            val active = bladeSweep(frames, Joint.SCAPULA_A, Joint.SHOULDER_A)
            val passive = bladeSweep(frames, Joint.SCAPULA_P, Joint.SHOULDER_P)

            assertTrue(
                "$id: the scapula must sweep a non-zero range across the rep (measured " +
                    "${f(active.rangeDegrees)}°); an identity girdle leaves the press to the elbow alone",
                active.rangeDegrees >= MIN_RANGE_DEGREES
            )
            assertTrue(
                "$id: the girdle range must stay inside the joint's own excursion (measured " +
                    "${f(active.rangeDegrees)}° > ${MAX_RANGE_DEGREES}°)",
                active.rangeDegrees <= MAX_RANGE_DEGREES
            )
            assertTrue(
                "$id: the girdle must move smoothly, not step (worst step ${f(active.maxStepDegrees)}° per " +
                    "1/$STEPS of the rep)",
                active.maxStepDegrees <= MAX_STEP_DEGREES
            )
            assertEquals(
                "$id: both blades must mirror the same girdle motion",
                active.rangeDegrees, passive.rangeDegrees, MIRROR_TOLERANCE_DEGREES
            )

            // The girdle moves the glenoid and the arm, never the plant: both hands stay at the one
            // authored world point for the whole rep.
            for (hand in listOf(Joint.HAND_A, Joint.HAND_P)) {
                assertEquals(
                    "$id ${hand.name}: the girdle drive must not move a planted hand",
                    0f, handSlipUnits(frames, hand), PLANTED_TOLERANCE_UNITS
                )
            }
        }
    }

    @Test
    fun theUnrelatedCorpusStaysBitIdentical() {
        val digest = corpusDigest()
        assertEquals(
            "every pose class outside the press and row families must publish the pre-change geometry — " +
                "a move here means the girdle drive leaked outside its scope (measured $digest)",
            UNAFFECTED_CORPUS_DIGEST, digest
        )
    }

    /** Every concrete pose class in `poses/` except the seven this pass authors, like the sibling guards. */
    private fun corpusClasses(): List<String> {
        var dir = java.io.File(System.getProperty("user.dir") ?: error("user.dir is not set"))
        var moduleRoot: java.io.File? = null
        for (attempt in 0 until 8) {
            if (java.io.File(dir, "src/main/java/com/monkfitness/app/poses").isDirectory) {
                moduleRoot = dir
                break
            }
            dir = dir.parentFile ?: break
        }
        val root = moduleRoot ?: error("Could not locate the app module root from ${System.getProperty("user.dir")}")
        return java.io.File(root, "src/main/java/com/monkfitness/app/poses")
            .listFiles { file -> file.isFile && file.name.endsWith("Pose.kt") }!!
            .map { it.name.removeSuffix(".kt") }
            .filterNot { it.startsWith("Base") || it == "PoseRegistry" || it in ARTICULATED_CLASSES }
            .sorted()
    }

    /**
     * Fresh instances, so the digest is a property of the pose and not of whichever test built it
     * before: the registry holds singletons whose node trees carry the previous frame's state.
     */
    private fun corpusDigest(): Long {
        val classes = corpusClasses()
        assertTrue("anti-vacuity: the digest corpus must contain the untouched poses", classes.size >= 45)
        var hash = 1125899906842597L
        for (name in classes) {
            val builder = MotionProbe.build(name)
            val pipeline = SkeletonPipeline(definition)
            hash = hash * 31 + name.hashCode()
            for (p in SAMPLES) {
                val frame = pipeline.produceFrame(
                    builder.build(context(p)), builder.metadata.environment, builder.metadata.support.supportPoints
                ).pose
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

    private fun f(v: Float) = String.format(Locale.US, "%.4f", v)

    private companion object {
        /** The registry ids this pass authors: the six feet/knee-pivot presses and the bar row. */
        val ARTICULATED = setOf(
            "pushup_standard", "pushup_wide", "pushup_military", "pushup_knee",
            "pushup_diamond", "pushup_decline", "row_standard"
        )

        /** The classes those ids resolve to; the digest corpus excludes them by class, as the siblings do. */
        val ARTICULATED_CLASSES = setOf(
            "StandardPushUpPose", "WidePushUpPose", "MilitaryPushUpPose", "KneePushUpPose",
            "DiamondPushUpPose", "DeclinePushUpPose", "RowsPose"
        )

        const val STEPS = 64
        const val WARM_UP_FRAMES = 11
        const val WARM_UP_PROGRESS = 0.3f

        val SAMPLES = listOf(0f, 0.25f, 0.5f, 0.75f, 1f)

        /** Below this the girdle is visually static (a 3-degree blade sweep is ~1.4 mm of travel). */
        const val MIN_RANGE_DEGREES = 3f

        /** The scapula's own protraction/retraction excursion across a press or pull. */
        const val MAX_RANGE_DEGREES = 20f

        /** A step this large per 1/64 rep is a jump, not a sweep. */
        const val MAX_STEP_DEGREES = 1f

        const val MIRROR_TOLERANCE_DEGREES = 0.01f

        /** Float noise on an authored world point: a planted hand is fixed, not nearly fixed. */
        const val PLANTED_TOLERANCE_UNITS = 1e-4f

        /**
         * Digest of every registered pose outside the press and row families (every joint, five
         * samples, full float bits), measured by THIS test on the pre-change tree: the value is
         * identical there and here, which is what makes "only the press and the row moved" a
         * measurement rather than a claim. The seven articulated ids are excluded because this pass
         * moves them on purpose; their own gates are the range and contact assertions above.
         */
        const val UNAFFECTED_CORPUS_DIGEST = -5414595749725570111L
    }
}
