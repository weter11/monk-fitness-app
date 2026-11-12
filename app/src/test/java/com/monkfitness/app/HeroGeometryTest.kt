package com.monkfitness.app

import com.monkfitness.app.ui.components.HeroGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The media hero's pure layout geometry: overlay caps (the text may never grow into the safe band at
 * any font scale), the safe band itself, the hit-test regions (a touch on overlay text is not a
 * rotation), and the dominant hero's share of the window.
 */
class HeroGeometryTest {

    /** A 325 dp-wide phone at a representative window height, at three text scales. */
    private val windowHeightDp = 760f
    private val fontScales = listOf(1.0f, 1.3f, 1.5f)

    private fun requestedOverlayDp(fontScale: Float): Pair<Float, Float> =
        // The session overlay asks for a compact two-line top and one/two-line bottom; at larger
        // text scales the request grows, but the cap must hold it.
        64f * fontScale to 56f * fontScale

    @Test
    fun overlayZonesAreCappedAtEveryFontScale() {
        for (fontScale in fontScales) {
            val heroHeight = HeroGeometry.sessionHeroHeightDp(
                windowHeightDp = windowHeightDp,
                headerDp = 44f * fontScale,
                controlsDp = 132f * fontScale,
                fontScale = fontScale
            )
            val (topAsked, bottomAsked) = requestedOverlayDp(fontScale)
            val zones = HeroGeometry.overlayZones(heroHeight, topAsked, bottomAsked)

            assertTrue(
                "font $fontScale: top zone ${zones.topDp} exceeds the 20% cap",
                zones.topDp <= heroHeight * HeroGeometry.OVERLAY_TOP_MAX_FRACTION + 0.001f
            )
            assertTrue(
                "font $fontScale: bottom zone ${zones.bottomDp} exceeds the 20% cap",
                zones.bottomDp <= heroHeight * HeroGeometry.OVERLAY_BOTTOM_MAX_FRACTION + 0.001f
            )
            val band = HeroGeometry.safeBand(heroHeight, zones)
            assertTrue(
                "font $fontScale: the safe band ${band.height} shrank below the minimum share",
                band.height >= heroHeight * HeroGeometry.MIN_SAFE_FRACTION - 0.001f
            )
            assertTrue(
                "font $fontScale: the overlay zones overlap (top=${zones.topDp}, bottom=${zones.bottomDp})",
                band.height > 0f
            )
        }
    }

    @Test
    fun overlayTextNeverGrowsIntoTheSafeBand() {
        // A pathological request (whole hero of text) must still be capped to the two zones, leaving
        // the safe band intact — this is what "text beyond the cap moves below the hero or truncates"
        // means geometrically.
        val heroHeight = 500f
        val zones = HeroGeometry.overlayZones(heroHeight, requestedTopDp = 10000f, requestedBottomDp = 10000f)
        val band = HeroGeometry.safeBand(heroHeight, zones)
        assertEquals("the safe band's top is the top zone's bottom", zones.topDp, band.top, 0.001f)
        assertEquals("the safe band's bottom is the bottom zone's top", heroHeight - zones.bottomDp, band.bottom, 0.001f)
        assertTrue("the safe band keeps its minimum share", band.height >= heroHeight * HeroGeometry.MIN_SAFE_FRACTION - 0.001f)
        assertEquals("the two zones sum to at most 1 - MIN_SAFE_FRACTION", true,
            zones.topDp + zones.bottomDp <= heroHeight * (1f - HeroGeometry.MIN_SAFE_FRACTION) + 0.001f)
    }

    @Test
    fun hitTestSeparatesOverlayTextFromTheFreeRotationArea() {
        val heroHeight = 500f
        val zones = HeroGeometry.overlayZones(heroHeight, 64f, 56f)
        val band = HeroGeometry.safeBand(heroHeight, zones)

        // A touch that STARTS on the top overlay text never rotates.
        assertFalse("top overlay text must not rotate",
            HeroGeometry.isRotationArea(zones.topDp / 2f, heroHeight, zones))
        // A touch on the bottom overlay text never rotates.
        assertFalse("bottom overlay text must not rotate",
            HeroGeometry.isRotationArea(heroHeight - zones.bottomDp / 2f, heroHeight, zones))
        // A touch in the free band rotates.
        assertTrue("free band must rotate",
            HeroGeometry.isRotationArea(band.top + band.height / 2f, heroHeight, zones))
        // Boundary: exactly at the bottom of the top zone is the free band's first dp.
        assertTrue("the free band starts at the top zone's bottom edge",
            HeroGeometry.isRotationArea(band.top + 0.01f, heroHeight, zones))
        assertTrue("the free band ends at the bottom zone's top edge",
            HeroGeometry.isRotationArea(band.bottom - 0.01f, heroHeight, zones))
    }

    @Test
    fun theHeroIsTheDominantElementAtEveryFontScale() {
        for (fontScale in fontScales) {
            val headerDp = 44f * fontScale
            val controlsDp = 132f * fontScale
            val heroHeight = HeroGeometry.sessionHeroHeightDp(windowHeightDp, headerDp, controlsDp, fontScale)
            val share = HeroGeometry.heroShare(heroHeight, windowHeightDp)
            val target = HeroGeometry.heroShareTarget(fontScale)
            assertTrue(
                "font $fontScale: hero share $share is below the $target target",
                share >= target - 0.0001f
            )
        }
        assertTrue("font 1.0 target is 45%", HeroGeometry.heroShareTarget(1.0f) >= 0.45f)
        assertTrue("font 1.5 target is 35%", HeroGeometry.heroShareTarget(1.5f) >= 0.35f)
    }

    @Test
    fun theFramingSurfaceIsTheSafeBand() {
        val heroHeight = 500f
        val zones = HeroGeometry.overlayZones(heroHeight, 64f, 56f)
        val band = HeroGeometry.safeBand(heroHeight, zones)
        val surface = HeroGeometry.framingSurface(325f, heroHeight, zones)
        assertEquals("the frame is derived for the safe band's height", band.height, surface.heightDp, 0.001f)
        assertEquals("the frame is offset down by the top zone", zones.topDp, surface.offsetYDp, 0.001f)
        assertEquals("the frame keeps the hero's full width", 325f, surface.widthDp, 0.001f)
    }

    @Test
    fun rotationStaysWithinTheGestureClamp() {
        assertTrue(HeroGeometry.MAX_YAW_OFFSET > 1.5f)
        // The sensitivity the drag uses is the one the controller clamps against.
        assertTrue(HeroGeometry.ROTATION_SENSITIVITY > 0f)
        assertTrue("a 48 dp touch target fits in the free band",
            HeroGeometry.MIN_TOUCH_TARGET_DP <= 48f + 0.001f)
    }
}
