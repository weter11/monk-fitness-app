package com.monkfitness.app

import com.monkfitness.app.animation.*
import com.monkfitness.app.poses.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Motion-range contract for the push-up family.
 *
 * The ExerciseValidator only checks per-frame biomechanical *validity* (bone lengths,
 * joint ROM, contacts, alignment) — a perfectly valid STATIC pose passes it. A push-up
 * that never moves would therefore pass the validator with 0 errors yet animate nothing.
 * This test asserts the behavioural contract the validator cannot see: the body must
 * actually travel through the rep. It would have caught the static-body regression
 * (feet-pivot chest delta 0.00, knee-pivot chest delta ~4u) directly.
 */
class PushUpMotionTest {

    private fun make(name: String): BasePose = when (name) {
        "Standard" -> StandardPushUpPose()
        "Wide" -> WidePushUpPose()
        "Decline" -> DeclinePushUpPose()
        "Diamond" -> DiamondPushUpPose()
        "Military" -> MilitaryPushUpPose()
        "Knee" -> KneePushUpPose()
        "Pike" -> PikePushUpPose()
        else -> throw IllegalArgumentException(name)
    }

    @Test
    fun allVariantsTravelThroughTheRep() {
        val def = SkeletonDefinition.DEFAULT_ADULT
        val failures = mutableListOf<String>()
        for (name in listOf("Standard", "Wide", "Decline", "Diamond", "Military", "Knee", "Pike")) {
            val poseBuilder = make(name)
            val pipeline = SkeletonPipeline(def)
            // warm the pipeline (matches SkeletonPipeline's documented warmup expectation)
            for (k in 0..10) {
                val c = PoseContext(progress = 0.3f, side = Side.RIGHT, definition = def,
                    deltaTime = 0.0166f, cycleDuration = 2500f)
                pipeline.produceFrame(poseBuilder.build(c))
            }
            var chestMin = Float.MAX_VALUE
            var chestMax = -Float.MAX_VALUE
            for (p in arrayOf(0.0f, 0.25f, 0.5f, 0.75f, 1.0f)) {
                val ctx = PoseContext(progress = p, side = Side.RIGHT, definition = def,
                    deltaTime = 0.0166f, cycleDuration = 2500f)
                val fin = pipeline.produceFrame(poseBuilder.build(ctx)).pose
                val cy = fin.getJoint(Joint.CHEST).y
                chestMin = minOf(chestMin, cy)
                chestMax = maxOf(chestMax, cy)
            }
            val travel = chestMax - chestMin
            // A real push-up descends the chest by a large fraction of arm length; require a
            // meaningful floor-to-top travel so a static plank cannot pass.
            if (travel < 40f) {
                failures.add("$name chest travel only $travel (need >= 40)")
            }
        }
        assertTrue(
            "Push-up motion contract violated:\n" + failures.joinToString("\n"),
            failures.isEmpty()
        )
    }
}
