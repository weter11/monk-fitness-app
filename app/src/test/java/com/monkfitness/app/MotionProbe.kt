package com.monkfitness.app

import com.monkfitness.app.animation.*
import com.monkfitness.app.poses.*

/**
 * Shared probe for the motion-range contract (PDP §5 [7], PAC §3/§4).
 *
 * The ExerciseValidator only checks per-frame *validity*, so a perfectly valid but static pose
 * passes with 0 errors yet animates nothing. This probe measures the actual vertical travel of
 * the body through the rep, exactly the way the production SkeletonPipeline resolves motion.
 *
 * IMPORTANT: SkeletonPipeline.produceFrame() returns a *reused mutable buffer*. Joint Y values must
 * be read into primitives during the pass; never store .pose references (they all alias the last
 * frame, which silently reports 0 travel).
 */
object MotionProbe {

    private val VJ = listOf(
        Joint.PELVIS, Joint.CHEST, Joint.HIP_F, Joint.KNEE_F, Joint.HEAD_POS,
        Joint.HAND_A, Joint.ELBOW_A, Joint.SHOULDER_A, Joint.ANKLE_F
    )

    /** Max vertical (Y) travel of any tracked joint across progress 0 -> 1, in skeleton units. */
    fun maxVerticalTravel(pose: PoseBuilder, def: SkeletonDefinition = SkeletonDefinition.DEFAULT_ADULT): Float {
        val pipeline = SkeletonPipeline(def)
        for (k in 0..10) {
            val c = PoseContext(progress = 0.3f, side = Side.RIGHT, definition = def,
                deltaTime = 0.0166f, cycleDuration = 2500f)
            pipeline.produceFrame(pose.build(c))
        }
        val mn = VJ.associateWith { Float.MAX_VALUE }.toMutableMap()
        val mx = VJ.associateWith { -Float.MAX_VALUE }.toMutableMap()
        for (p in arrayOf(0.0f, 0.25f, 0.5f, 0.75f, 1.0f)) {
            val ctx = PoseContext(progress = p, side = Side.RIGHT, definition = def,
                deltaTime = 0.0166f, cycleDuration = 2500f)
            val fr = pipeline.produceFrame(pose.build(ctx)).pose
            for (j in VJ) {
                val y = fr.getJoint(j).y
                mn[j] = kotlin.math.min(mn[j]!!, y)
                mx[j] = kotlin.math.max(mx[j]!!, y)
            }
        }
        return VJ.maxOf { mx[it]!! - mn[it]!! }
    }

    /** Build a pose by simple class name via reflection (mirrors the production pose registry). */
    @Suppress("UNCHECKED_CAST")
    fun build(name: String): PoseBuilder =
        Class.forName("com.monkfitness.app.poses.$name").getDeclaredConstructor().newInstance() as PoseBuilder
}
