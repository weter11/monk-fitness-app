package com.monkfitness.app

import org.junit.Assert.*
import org.junit.Test

/**
 * Motion-range contract for the MOBILITY / dynamic-stretch family (PDP §5 [7], PAC §3/§4).
 * These are movement drills (hip cars, cat-cow, thoracic rotations, dynamic world's greatest
 * stretch, etc.), not static holds — they must travel through their range. A frozen version would
 * pass the validator yet animate nothing.
 */
class MobilityMotionTest {

    @Test
    fun mobilityTravelThroughTheRep() {
        val cases = mapOf(
            "HipCarsPose" to 35f,
            "CatCowPose" to 25f,
            "ThoracicExtensionPose" to 25f,
            "ArmCirclesPose" to 25f,
            "PelvicTiltPose" to 12f,
            "HamstringStretchPose" to 45f,
            "CouchStretchPose" to 15f,
            "ProneCobraStretchPose" to 35f,
            "StaticBirdDogHoldPose" to 45f,
            "QuadrupedThoracicRotationsPose" to 60f,
            "DynamicWorldsGreatestStretchPose" to 70f,
            "ReverseSnowAngelPose" to 80f
        )
        val failures = mutableListOf<String>()
        for ((name, floor) in cases) {
            val travel = MotionProbe.maxVerticalTravel(MotionProbe.build(name))
            if (travel < floor) failures.add("$name travel=%.1f (need >= %.1f)".format(travel, floor))
        }
        assertTrue("Mobility motion contract violated:\n" + failures.joinToString("\n"), failures.isEmpty())
    }
}
