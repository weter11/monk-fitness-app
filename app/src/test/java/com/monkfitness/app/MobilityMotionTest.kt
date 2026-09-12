package com.monkfitness.app

import org.junit.Assert.*
import org.junit.Test

/**
 * Motion-range contract for the MOBILITY / dynamic-stretch family (PDP §5 [7], PAC §3/§4).
 * These are movement drills (hip cars, cat-cow, thoracic rotations, dynamic world's greatest
 * stretch, etc.), not static holds — they must travel through their range. A frozen version would
 * pass the validator yet animate nothing.
 *
 * NOTE (M8): `HipCarsPose`'s floor is re-pointed at the AUTHORED span. Its pre-fix reading passed
 * this contract through a clamp artifact, not through choreography: the working leg's ankle target
 * was authored a whole standing root height above its hip, so the solver pinned the effector at
 * maximum reach and the knee swung 74.73 units (measured) instead of the authored circle. With the
 * M8 correction the working leg realizes its authored circle (`circleRadiusX = 15` → a 30.0-unit
 * span on the ankle, `circleRadiusY = 12`), which is what this floor now pins — a knife-edge pin,
 * deliberately, so any loss of travel fails. Whether the authored circle should be WIDER than
 * radiusX 15 is a tuning question for the user, not something this test may mask.
 */
class MobilityMotionTest {

    @Test
    fun mobilityTravelThroughTheRep() {
        val cases = mapOf(
            "HipCarsPose" to 30f,
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
