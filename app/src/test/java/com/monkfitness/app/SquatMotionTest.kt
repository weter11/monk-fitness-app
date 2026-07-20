package com.monkfitness.app

import org.junit.Assert.*
import org.junit.Test

/**
 * Motion-range contract for the SQUAT family (PDP §5 [7], PAC §3/§4).
 * A squat must descend and rise through the rep; a static squat would pass the ExerciseValidator
 * yet animate nothing. Floors are set well below the measured travel so the test passes today and
 * catches any regression to a near-static pose.
 */
class SquatMotionTest {

    @Test
    fun squatsTravelThroughTheRep() {
        val cases = mapOf(
            "AirSquatPose" to 50f,
            "SquatPose" to 50f,
            "SumoSquatPose" to 50f,
            "CossackSquatPose" to 50f,
            "JumpSquatPose" to 35f
        )
        val failures = mutableListOf<String>()
        for ((name, floor) in cases) {
            val travel = MotionProbe.maxVerticalTravel(MotionProbe.build(name))
            if (travel < floor) failures.add("$name travel=%.1f (need >= %.1f)".format(travel, floor))
        }
        assertTrue("Squat motion contract violated:\n" + failures.joinToString("\n"), failures.isEmpty())
    }
}
