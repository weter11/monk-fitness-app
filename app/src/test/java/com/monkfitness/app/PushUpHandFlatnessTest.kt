package com.monkfitness.app

import com.monkfitness.app.animation.*
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Engine contract: a push-up hand rests flat on its declared support plane (the floor).
 *
 * Root cause this guards (fix/pushup-contact-orientation): push-up poses author the plank by FK
 * and bake the arms with no ContactConstraint, so pose.contacts is empty and the Finalizer's
 * extremity-orientation pass had no support plane for the hands. The completed hand was therefore a
 * rigid extension of the forearm, which points down-and-forward to a planted hand, so the
 * palm/knuckles/fingertips sloped INTO the floor (fingertip 8-20u below the wrist). The engine now
 * reads BasePushUpPose's declared extremitySupportPlanes and projects the hand direction onto that
 * plane, so the hand lies flat.
 *
 * This is a behavioral invariant (the hand's long axis lies in the support plane), NOT a snapshot:
 * it asserts a geometric relationship that must hold for every push-up variant at every depth.
 */
class PushUpHandFlatnessTest {

    // The six variants that share BasePushUpPose.build() (and therefore its declared hand support
    // plane). PikePushUpPose is intentionally excluded: it overrides build() entirely and authors
    // its own wrist grip (buildWristArticulation tracking torso pitch), so its palm orientation is
    // deliberate intent, not the engine-derived flat-hand this fix restores.
    private val variants = listOf(
        "StandardPushUpPose", "WidePushUpPose", "DeclinePushUpPose", "DiamondPushUpPose",
        "MilitaryPushUpPose", "KneePushUpPose"
    )

    @Test
    fun everyPushUpHandLiesFlatOnTheFloor() {
        val def = SkeletonDefinition.DEFAULT_ADULT
        val failures = mutableListOf<String>()
        for (name in variants) {
            val pb = MotionProbe.build(name)
            val pipe = SkeletonPipeline(def)
            for (p in listOf(0.0f, 0.25f, 0.5f, 0.75f, 1.0f)) {
                val ctx = PoseContext(progress = p, side = Side.RIGHT, definition = def,
                    deltaTime = 0.0166f, cycleDuration = 2500f)
                val pose = pipe.produceFrame(pb.build(ctx)).pose
                // For each hand, the wrist->fingertip vector must be horizontal (in the floor plane,
                // normal = +Y): its vertical component must be ~0. A downward-sloping hand (the bug)
                // has a large negative dY.
                for (pair in listOf(
                    Triple("A", Joint.HAND_A, Joint.FINGERTIPS_A),
                    Triple("P", Joint.HAND_P, Joint.FINGERTIPS_P)
                )) {
                    val hand = pose.getJoint(pair.second)
                    val tip = pose.getJoint(pair.third)
                    val dY = tip.y - hand.y
                    if (kotlin.math.abs(dY) > 1.0f) {
                        failures.add("$name hand ${pair.first} p=$p: wrist->fingertip dY=%.2f (must lie flat, ~0)".format(dY))
                    }
                }
            }
        }
        assertTrue(
            "Push-up hands slope off the floor plane (palm-into-floor regression):\n" + failures.joinToString("\n"),
            failures.isEmpty()
        )
    }
}
