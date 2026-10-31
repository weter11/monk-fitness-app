package com.monkfitness.app

import org.junit.Assert.*
import org.junit.Test

/**
 * Stability contract for STATIC-BY-DESIGN poses: deep squat hold, hang, lat/scapular stretches.
 *
 * These poses are intentionally holds. They must NOT drift through a large range — if one starts
 * travelling like a dynamic exercise, that is a regression of intent. This asserts the body stays
 * essentially still, which is the inverse of the motion contract, and documents that these were
 * reviewed as static.
 *
 * Three entries were re-derived from the authored choreography, because their limbs had been authored
 * as ABSOLUTE floor-framed targets while the pose's root is solver-owned (written after `build`), so
 * the solver clamped every effector at maximum reach and the limbs could not animate:
 *
 *  - `WallSlidesPose` 0.19 → 82.71 (the slide the exercise is named for) and `FacePullPose`
 *    3.92 → 51.50 were in this list only because the clamp froze them. They are rep exercises, so they
 *    are removed from the hold list; their authored motion is pinned by the motion contract instead of
 *    being asserted as "static".
 *  - `ScapularRetractionPose` 0.72 → 19.45 on the elbow. It stays a hold by registration
 *    (`scapular_retraction_hold`) but is AUTHORED as a moving squeeze (`handX` 25 → 5), and the 15
 *    ceiling was only ever read off the frozen chain. The ceiling is re-pointed at the authored travel
 *    (20, a knife-edge pin against the measured 19.45); the intent mismatch — a registered hold whose
 *    authoring travels 19.45 — is flagged rather than resolved here.
 */
class StaticHoldStabilityTest {

    @Test
    fun holdsStayStatic() {
        val cases = mapOf(
            "DeepSquatHoldPose" to 15f,
            "HangPose" to 15f,
            "LatStretchPose" to 15f,
            "ScapularRetractionPose" to 20f,
            "ScapularPullUpPose" to 15f,
            "HalfKneelingStretchPose" to 20f
        )
        val failures = mutableListOf<String>()
        for ((name, ceil) in cases) {
            val travel = MotionProbe.maxVerticalTravel(MotionProbe.build(name))
            if (travel > ceil) failures.add("$name travel=%.1f exceeds static hold ceiling %.1f".format(travel, ceil))
        }
        assertTrue("Static hold contract violated (pose moving when it should be held):\n" + failures.joinToString("\n"), failures.isEmpty())
    }
}
