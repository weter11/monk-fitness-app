package com.monkfitness.app

import com.monkfitness.app.animation.YawLimits
import com.monkfitness.app.animation.clampedYawOffset
import com.monkfitness.app.ui.components.HeroGeometry
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI

/**
 * The rotation gesture's arithmetic at the clamp: a pose the hero can only turn a few degrees must stay
 * aimed and must not fight the finger. The state is the offset the user sees, so nothing accumulates
 * past the limit, a drag past the clamp does not wrap or spring back, and the first drag back turns the
 * figure back on the same event.
 */
class YawDragClampTest {

    private val density = 3.1375f
    private val fiveDegrees = (PI / 36).toFloat()

    /** One drag event of [dp] dp on a hero clamped at [limit] both ways, starting from [current]. */
    private fun drag(current: Float, dp: Float, limit: Float): Float =
        clampedYawOffset(current, dp * density * HeroGeometry.rotationSensitivity(limit, density), YawLimits(limit, limit))

    @Test
    fun aDragPastTheClampStaysOnItAndDoesNotWrap() {
        var offset = 0f
        repeat(40) { offset = drag(offset, dp = 12f, limit = fiveDegrees) }
        assertEquals("a long drag must stop exactly on the clamp", fiveDegrees, offset, 1e-6f)

        repeat(20) { offset = drag(offset, dp = -12f, limit = fiveDegrees) }
        assertEquals("the opposite drag must stop on the clamp, not wrap round it", -fiveDegrees, offset, 1e-6f)
    }

    @Test
    fun theFirstDragBackTurnsTheFigureBackImmediately() {
        var offset = drag(0f, dp = 40f, limit = fiveDegrees)
        assertEquals("a long drag must land on the clamp", fiveDegrees, offset, 1e-6f)

        // No invisible debt beyond the limit: the very next event back moves the figure.
        val step = 12f * density * HeroGeometry.rotationSensitivity(fiveDegrees, density)
        offset = drag(offset, dp = -12f, limit = fiveDegrees)
        assertEquals("the drag must respond on the first event back", fiveDegrees - step, offset, 1e-6f)
    }

    @Test
    fun theClampIsStableUnderAFingerHeldAtIt() {
        var offset = drag(0f, dp = 30f, limit = fiveDegrees)
        val held = offset
        // A finger that keeps pushing at the clamp cannot move the figure, and cannot make it oscillate.
        repeat(25) {
            offset = drag(offset, dp = 3f, limit = fiveDegrees)
            assertEquals("held at the clamp the offset must not move", held, offset, 0f)
        }
        // Nor can a limit that the layout re-derives identically on every frame.
        var again = held
        repeat(25) { again = clampedYawOffset(again, 0f, YawLimits(fiveDegrees, fiveDegrees)) }
        assertEquals(held, again, 0f)
    }

    @Test
    fun eachSideStopsAtItsOwnAllowance() {
        val limits = YawLimits(negative = 0.02f, positive = 0.35f)
        var offset = 0f
        repeat(30) { offset = clampedYawOffset(offset, 0.05f, limits) }
        assertEquals("the roomy side stops at its own limit", limits.positive, offset, 1e-6f)

        repeat(30) { offset = clampedYawOffset(offset, -0.05f, limits) }
        assertEquals("the tight side stops at its own limit", -limits.negative, offset, 1e-6f)
    }

    @Test
    fun aSurfaceThatCannotTurnHoldsTheFigureStill() {
        var offset = 0f
        repeat(10) { offset = drag(offset, dp = 40f, limit = 0f) }
        assertEquals("a zero clamp must not be a divide-by-zero drag", 0f, offset, 0f)
    }
}
