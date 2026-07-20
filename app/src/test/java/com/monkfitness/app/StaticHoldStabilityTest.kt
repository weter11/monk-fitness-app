package com.monkfitness.app

import org.junit.Assert.*
import org.junit.Test

/**
 * Stability contract for STATIC-BY-DESIGN poses (PDP §5 [7], PAC §3/§4).
 *
 * These poses are intentionally holds (deep squat hold, hang, wall slide, lat/scarular stretches,
 * face pull). They must NOT drift through a large range — if one starts travelling like a dynamic
 * exercise, that is a regression of intent. This asserts the body stays essentially still, which is
 * the inverse of the motion contract and documents that these were reviewed as static.
 *
 * NOTE: WallSlidesPose currently reads ~0.3u travel; if the BPS specifies shoulder sliding, that is
 * a separate latent bug to fix — flagged here so the hold contract does not mask it.
 */
class StaticHoldStabilityTest {

    @Test
    fun holdsStayStatic() {
        val cases = mapOf(
            "DeepSquatHoldPose" to 15f,
            "WallSlidesPose" to 15f,
            "HangPose" to 15f,
            "LatStretchPose" to 15f,
            "ScapularRetractionPose" to 15f,
            "ScapularPullUpPose" to 15f,
            "FacePullPose" to 15f,
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
