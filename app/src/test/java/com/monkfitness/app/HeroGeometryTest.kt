package com.monkfitness.app

import com.monkfitness.app.ui.components.HeroGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hero's sizing rule, as arithmetic: the rig's own need decides the height, the 45 % cap and the
 * room the rest of the screen needs bound it, and the rotation floor is never below 45 degrees.
 */
class HeroGeometryTest {

    private val density = 3.1375f
    private val contentHeightDp = 699f

    @Test
    fun aTallRigTakesTheRoomItNeedsUpToTheCap() {
        // A rig 900 px tall wants 900 / 0.70 = 1286 px = 410 dp of hero, which the cap must cut.
        val hero = HeroGeometry.heightDp(
            rigHeightPx = 900f,
            density = density,
            contentHeightDp = contentHeightDp,
            roomDp = contentHeightDp
        )
        assertEquals(contentHeightDp * HeroGeometry.MAX_CONTENT_FRACTION, hero, 0.01f)
        assertTrue(
            "the capped hero must still let a 900 px rig fill 70 % of it",
            900f / (hero * density) >= HeroGeometry.RIG_HEIGHT_FRACTION - 0.001f
        )
    }

    @Test
    fun aWideRigIsHeldToTheFloorAndTheRoomItIsGiven() {
        val need = 300f / density / HeroGeometry.RIG_HEIGHT_FRACTION
        assertEquals(
            "a rig that needs less than the floor still gets the floor",
            HeroGeometry.MIN_HEIGHT_DP,
            HeroGeometry.heightDp(300f, density, contentHeightDp, contentHeightDp),
            0.01f
        )
        assertTrue(need < HeroGeometry.MIN_HEIGHT_DP)

        // The room the info block and the controls leave wins over the rig's wish, and never wins over
        // the floor: a screen with no room left scrolls instead of shrinking the hero into a strip.
        assertEquals(
            200f,
            HeroGeometry.heightDp(900f, density, contentHeightDp, 200f),
            0.01f
        )
        assertEquals(
            HeroGeometry.MIN_HEIGHT_DP,
            HeroGeometry.heightDp(900f, density, contentHeightDp, 40f),
            0.01f
        )
    }

    @Test
    fun theRotationNeverDropsBelowFortyFiveDegrees() {
        assertEquals(
            "a hero that can hold a full turn keeps the gesture's reach",
            HeroGeometry.MIN_YAW_LIMIT,
            HeroGeometry.yawLimit(containedRadians = 0.2f, declaredReach = 1.5708f),
            0.0001f
        )
        assertEquals(
            "a hero that can hold more than the floor keeps what it can hold",
            0.9f,
            HeroGeometry.yawLimit(containedRadians = 0.9f, declaredReach = 1.5708f),
            0.0001f
        )
        assertEquals(
            "a pose that declares less than the floor can only turn as far as it declares",
            0.5f,
            HeroGeometry.yawLimit(containedRadians = 0.2f, declaredReach = 0.5f),
            0.0001f
        )
        assertEquals(
            "the gesture may never exceed its own reach",
            HeroGeometry.MAX_YAW_LIMIT,
            HeroGeometry.yawLimit(containedRadians = 3f, declaredReach = 3f),
            0.0001f
        )
    }
}
