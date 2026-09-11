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
            // P12 (§12.6): 3D axis span, not a Y-only assumption — see MotionProbe.maxTravel3D
            // for why the mobility family's authored choreography (prone sagittal sweeps,
            // quadruped arching) requires the non-vertical axis after the bypass migration.
            val travel = MotionProbe.maxTravel3D(MotionProbe.build(name))
            if (travel < floor) failures.add("$name travel=%.1f (need >= %.1f)".format(travel, floor))
        }
        assertTrue("Mobility motion contract violated:\n" + failures.joinToString("\n"), failures.isEmpty())
    }
}
