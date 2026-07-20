package com.monkfitness.app

import org.junit.Assert.*
import org.junit.Test

/**
 * Motion-range contract for the CORE / dynamic-body family (PDP §5 [7], PAC §3/§4).
 * These poses drive a real rep (curl-up, hip raise, mountain climber, burpee, swing); a static
 * version would pass the validator yet animate nothing.
 */
class CoreMotionTest {

    @Test
    fun coreTravelThroughTheRep() {
        val cases = mapOf(
            "DeadBugPose" to 50f,
            "BirdDogPose" to 45f,
            "AlternatingBirdDogPose" to 45f,
            "GluteBridgePose" to 35f,
            "LegRaisePose" to 60f,
            "SupermanPose" to 40f,
            "MountainClimberPose" to 40f,
            "BurpeePose" to 70f,
            "KettlebellSwingPose" to 60f
        )
        val failures = mutableListOf<String>()
        for ((name, floor) in cases) {
            val travel = MotionProbe.maxVerticalTravel(MotionProbe.build(name))
            if (travel < floor) failures.add("$name travel=%.1f (need >= %.1f)".format(travel, floor))
        }
        assertTrue("Core motion contract violated:\n" + failures.joinToString("\n"), failures.isEmpty())
    }
}
