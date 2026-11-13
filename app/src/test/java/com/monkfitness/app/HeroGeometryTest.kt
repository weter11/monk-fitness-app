package com.monkfitness.app

import com.monkfitness.app.animation.YawLimits
import com.monkfitness.app.ui.components.HeroGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hero's sizing rule, as arithmetic: the rig's own need decides the height, the 45 % cap and the
 * room the rest of the screen needs bound it, and the rotation the drag may reach is the rotation the
 * surface can draw — with no floor under it, because a floor is a crop.
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
    fun theClampIsWhatTheSurfaceCanDrawAndNothingMore() {
        val fiveDegrees = Math.toRadians(5.0).toFloat()
        val asymmetric = HeroGeometry.yawLimits(YawLimits(negative = 0.2f, positive = 0.9f), declaredReach = 1.5708f)
        assertEquals(
            "each side keeps exactly what the surface can draw, and the sides differ",
            0.2f,
            asymmetric.negative,
            1e-5f
        )
        assertEquals(0.9f, asymmetric.positive, 1e-5f)

        val declared = HeroGeometry.yawLimits(
            YawLimits(negative = fiveDegrees, positive = 0.9f),
            declaredReach = fiveDegrees
        )
        assertEquals(
            "a side may never exceed the pose's own declared reach",
            fiveDegrees,
            declared.positive,
            1e-5f
        )

        val full = HeroGeometry.yawLimits(YawLimits(negative = 3f, positive = 3f), declaredReach = 3f)
        assertEquals("the gesture may never exceed its own reach", HeroGeometry.MAX_YAW_LIMIT, full.positive, 1e-5f)

        val none = HeroGeometry.yawLimits(YawLimits(negative = 0f, positive = 0f), declaredReach = 1.5708f)
        assertEquals("a surface that can hold no rotation at all does not turn", 0f, none.positive, 1e-5f)
        assertEquals(0f, none.negative, 1e-5f)
    }

    @Test
    fun aClampTooTightForTheTouchSlopIsTurnedMoreSlowly() {
        val small = HeroGeometry.rotationSensitivity(Math.toRadians(5.0).toFloat(), density)
        val deliberateDragPx = HeroGeometry.MIN_YAW_DRAG_DP * density

        assertEquals(
            "reaching a five-degree clamp must take a deliberate drag, not the touch slop",
            Math.toRadians(5.0).toFloat(),
            small * deliberateDragPx,
            Math.toRadians(0.1).toFloat()
        )
        assertTrue(
            "the rate must be below the screen's own for a clamp this small",
            small < HeroGeometry.ROTATION_SENSITIVITY
        )
        assertTrue(
            "an even smaller clamp must turn more slowly still",
            HeroGeometry.rotationSensitivity(Math.toRadians(2.0).toFloat(), density) < small
        )
        assertEquals(
            "a pose with the gesture's full reach turns at the screen's own rate",
            HeroGeometry.ROTATION_SENSITIVITY,
            HeroGeometry.rotationSensitivity(HeroGeometry.MAX_YAW_LIMIT, density),
            1e-6f
        )
        assertEquals(
            "no allowance, no turn",
            0f,
            HeroGeometry.rotationSensitivity(0f, density),
            0f
        )
    }
}
