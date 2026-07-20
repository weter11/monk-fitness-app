package com.monkfitness.app

import org.junit.Assert.*
import org.junit.Test

/**
 * Motion-range contract for the LUNGE family (PDP §5 [7], PAC §3/§4).
 * Each lunge steps/drops through a real range of motion; a static lunge would pass the validator
 * yet animate nothing.
 */
class LungeMotionTest {

    @Test
    fun lungesTravelThroughTheRep() {
        val cases = mapOf(
            "AlternatingForwardLungesPose" to 45f,
            "AlternatingSideLungesPose" to 45f,
            "AlternatingReverseLungesPose" to 45f
        )
        val failures = mutableListOf<String>()
        for ((name, floor) in cases) {
            val travel = MotionProbe.maxVerticalTravel(MotionProbe.build(name))
            if (travel < floor) failures.add("$name travel=%.1f (need >= %.1f)".format(travel, floor))
        }
        assertTrue("Lunge motion contract violated:\n" + failures.joinToString("\n"), failures.isEmpty())
    }
}
