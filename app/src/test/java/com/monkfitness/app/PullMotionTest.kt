package com.monkfitness.app

import org.junit.Assert.*
import org.junit.Test

/**
 * Motion-range contract for the VERTICAL PULL family (PDP §5 [7], PAC §3/§4).
 * A pull-up/chin-up raises the body to the bar and lowers it; a static pull would pass the
 * validator yet animate nothing.
 */
class PullMotionTest {

    @Test
    fun pullsTravelThroughTheRep() {
        val cases = mapOf(
            "StandardPullUpPose" to 30f,
            "NeutralGripPullUpPose" to 30f,
            "WideGripPullUpPose" to 30f,
            "UnderhandChinUpPose" to 30f
        )
        val failures = mutableListOf<String>()
        for ((name, floor) in cases) {
            val travel = MotionProbe.maxVerticalTravel(MotionProbe.build(name))
            if (travel < floor) failures.add("$name travel=%.1f (need >= %.1f)".format(travel, floor))
        }
        assertTrue("Pull motion contract violated:\n" + failures.joinToString("\n"), failures.isEmpty())
    }
}
