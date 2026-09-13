package com.monkfitness.app

import com.monkfitness.app.animation.*
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * Shared sweep harness for the animation-coverage poses (`PoseBuilder`-authored exercises driven by
 * `PoseRegistry`).
 *
 * Why this exists next to [MotionProbe]: the coverage tests need more than one travel number. They
 * assert the exercise's own biomechanics — which joints must NOT move (planted contacts, stance
 * width), which must, the interior angles of the loaded limbs, and the ground invariant — and all of
 * those must be read off the **published frame** (the engine's own output), never the raw `build()`
 * result (a posed limb that is IK-baked late carries the previous frame's world until the Finalizer
 * re-runs FK; see the reasoning in `monk-pose-qa`).
 *
 * `SkeletonPipeline.produceFrame(...)` returns a **reused mutable buffer**, so every sample is copied
 * BY VALUE ([SkeletonPose.copyFrom] + a [Vector3] copy per joint) inside the single pass. Storing the
 * returned references would alias every sample to the last frame and silently report zero travel —
 * the T-7 defect class. [distinctFrameCount] exists so a test can prove the samples are distinct.
 */
object PoseFrameSweep {

    /** One published frame, copied out of the engine's reused buffer. */
    class Frame(val progress: Float, val joints: Map<Joint, Vector3>, val maxIkClampAmount: Float, val identity: Int) {
        operator fun get(joint: Joint): Vector3 = joints.getValue(joint)
        fun y(joint: Joint): Float = joints.getValue(joint).y
        fun pos(joint: Joint): Vector3 = joints.getValue(joint)
    }

    /** The default `progress` sample set: the ends, the middle and the quarter points. */
    val DEFAULT_PROGRESS = floatArrayOf(0f, 0.125f, 0.25f, 0.375f, 0.5f, 0.625f, 0.75f, 0.875f, 1f)

    /**
     * Play the pose through the production pipeline and return by-value snapshots of the published
     * frames.
     *
     * The pipeline is entered exactly the way `SkeletonRenderer` enters it in the app
     * (`pipeline.produceFrame(pose, environment, supportedPoints)` — the renderer's own call, with the
     * pose's declared environment and support), NOT through the bare-pose overload: since R8/B-5 a
     * bare pose carries no declaration source, so the declaration-driven derivations (the planted-foot
     * heading/flattening among them) silently do nothing and the test would measure geometry the
     * athlete never sees.
     *
     * Warms 11 frames first (the engine's cold-start fixed point — only warmed frames are comparable),
     * exactly as [MotionProbe] does.
     */
    fun sweep(
        pose: PoseBuilder,
        progress: FloatArray = DEFAULT_PROGRESS,
        def: SkeletonDefinition = SkeletonDefinition.DEFAULT_ADULT,
        alternating: Boolean = false
    ): List<Frame> {
        val pipeline = SkeletonPipeline(def)
        val environment = pose.metadata.environment
        val supportedPoints = pose.metadata.support.supportPoints
        for (k in 0..10) {
            pipeline.produceFrame(
                pose.build(PoseContext(progress = 0.3f, side = Side.RIGHT, definition = def, deltaTime = 0.0166f, cycleDuration = 2500f)),
                environment,
                supportedPoints
            )
        }
        val out = ArrayList<Frame>(progress.size)
        for (p in progress) {
            val published = pipeline.produceFrame(
                pose.build(PoseContext(progress = p, side = Side.RIGHT, definition = def, deltaTime = 0.0166f, cycleDuration = 2500f)),
                environment,
                supportedPoints
            ).pose
            val copy = HashMap<Joint, Vector3>(Joint.entries.size)
            for (j in Joint.entries) copy[j] = Vector3().set(published.getJoint(j))
            out.add(Frame(p, copy, published.maxIkClampAmount, System.identityHashCode(published)))
        }
        return out
    }

    /**
     * How many DISTINCT published frames the sweep observed, by CONTENT (the pipeline hands back one
     * reused buffer, so object identity is constant by design — the T-7 aliasing signature is "N
     * samples that are byte-identical", not "N samples of the same object"). Must equal the sample
     * count: a sweep whose samples repeat one frame cannot witness travel, and every motion
     * assertion built on it would be vacuous.
     */
    fun distinctFrameCount(frames: List<Frame>): Int =
        frames.map { f -> Joint.entries.joinToString(",") { "%.6f/%.6f/%.6f".format(java.util.Locale.ROOT, f[it].x, f[it].y, f[it].z) } }
            .distinct().size

    /** Max axis travel of [joint] across the sweep. */
    fun travel(frames: List<Frame>, joint: Joint, axis: Int): Float {
        val values = frames.map { f ->
            when (axis) { 0 -> f[joint].x; 1 -> f[joint].y; else -> f[joint].z }
        }
        return values.max() - values.min()
    }

    fun travelY(frames: List<Frame>, joint: Joint): Float = travel(frames, joint, 1)

    /** Max 3-D travel (any axis) of [joint]. */
    fun travel3D(frames: List<Frame>, joint: Joint): Float =
        maxOf(travel(frames, joint, 0), travel(frames, joint, 1), travel(frames, joint, 2))

    /** Interior joint angle (degrees) at [vertex] between [a] and [c] on a published frame. */
    fun interiorAngle(frame: Frame, a: Joint, vertex: Joint, c: Joint): Float {
        val u = Vector3().set(frame[a]).subtract(frame[vertex])
        val w = Vector3().set(frame[c]).subtract(frame[vertex])
        val lu = sqrt(u.x * u.x + u.y * u.y + u.z * u.z)
        val lw = sqrt(w.x * w.x + w.y * w.y + w.z * w.z)
        if (lu < 1e-4f || lw < 1e-4f) return Float.NaN
        val d = ((u.x * w.x + u.y * w.y + u.z * w.z) / (lu * lw)).coerceIn(-1f, 1f)
        return Math.toDegrees(acos(d.toDouble())).toFloat()
    }

    /** The worst (most negative) `y - level` over every joint of the sweep — the ground invariant. */
    fun worstClearance(frames: List<Frame>, def: SkeletonDefinition, level: Float = 0f): Pair<Joint, Float> {
        var worstJoint = Joint.PELVIS
        var worst = Float.MAX_VALUE
        for (f in frames) for (j in Joint.entries) {
            val clearance = f.y(j) - level
            if (clearance < worst) { worst = clearance; worstJoint = j }
        }
        return worstJoint to worst
    }

    /** Worst deviation of a joint between `from` and `to` frames (max over the axis deltas). */
    fun deviation(frames: List<Frame>, joint: Joint, from: Int, to: Int): Float {
        val a = frames[from][joint]; val b = frames[to][joint]
        return maxOf(kotlin.math.abs(a.x - b.x), kotlin.math.abs(a.y - b.y), kotlin.math.abs(a.z - b.z))
    }

    /**
     * B-4 semantics: the joints a declared support point resolves to, on the canonical
     * `SupportPoint -> Joint` map. Used to assert a declared contact is really the contact.
     */
    fun jointsFor(point: SupportPoint): List<Joint> = SupportMath.jointsFor(point)
}
