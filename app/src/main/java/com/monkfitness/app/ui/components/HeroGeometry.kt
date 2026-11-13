package com.monkfitness.app.ui.components

import kotlin.math.min

/**
 * The pure geometry of the media hero: how tall a surface the rig needs, how much of the screen the
 * hero may take, and how far the rotation gesture may turn before the hero crops the figure. No
 * Android or Compose dependency — the layout reads these numbers and the JVM tests assert them.
 */
object HeroGeometry {

    /** The rig must fill at least this share of the hero's height for the fit to read as a demo. */
    const val RIG_HEIGHT_FRACTION = 0.70f

    /**
     * What the height is derived for, above [RIG_HEIGHT_FRACTION]: the rig's drawn height changes
     * through the rep (measured 461-517 px for a Cat-Cow on this handset), and the criterion is read
     * from one frame, so the surface is sized for the peak plus that swing rather than for 70 % exactly.
     */
    const val RIG_NEED_FRACTION = 0.78f

    /** The hero may take at most this share of the content area, whatever the rig would like. */
    const val MAX_CONTENT_FRACTION = 0.45f

    /**
     * A hero shorter than this stops reading as the movement: the wide poses are width-limited, so
     * their own rig need is small and this is the floor that keeps the three lying poses legible.
     */
    const val MIN_HEIGHT_DP = 191f

    /** The rotation gesture never gives the user less than this, even where the hero crops. */
    const val MIN_YAW_LIMIT = 0.7854f

    /** The gesture's own reach, mirrored from `AnimationController.onRotate`. */
    const val MAX_YAW_LIMIT = 1.5708f

    /** Roughly half a turn across 600 dp of drag, as the session screen has always rotated. */
    const val ROTATION_SENSITIVITY = 3.14159f / 600f

    /**
     * The hero's height for one exercise, in dp.
     *
     * [rigHeightPx] is the rig's drawn height when the surface is exactly as wide as the hero — the
     * size the frame will actually draw, before the hero's own height is known. Dividing it by
     * [RIG_NEED_FRACTION] is the height at which the rig fills that share; the result is then held
     * to [MAX_CONTENT_FRACTION] of [contentHeightDp] and to [roomDp] (what the rest of the screen —
     * the info block and the pinned controls — already needs), and never below [MIN_HEIGHT_DP]. The
     * room term is what keeps the info block and Confirm set visible without scrolling: a rig need
     * larger than the room is served by a shorter hero, which only makes the rig a larger share of it.
     */
    fun heightDp(
        rigHeightPx: Float,
        density: Float,
        contentHeightDp: Float,
        roomDp: Float
    ): Float {
        val need = if (density <= 0f) MIN_HEIGHT_DP else rigHeightPx / density / RIG_NEED_FRACTION
        val cap = min(contentHeightDp * MAX_CONTENT_FRACTION, roomDp).coerceAtLeast(MIN_HEIGHT_DP)
        return need.coerceIn(MIN_HEIGHT_DP, cap)
    }

    /**
     * The yaw the drag may reach: what the hero can draw without cropping, but never less than
     * [MIN_YAW_LIMIT] — a hero too tight to turn at all is worse than a cropped extreme view — and
     * never more than the pose declares.
     */
    fun yawLimit(containedRadians: Float, declaredReach: Float): Float =
        maxOf(containedRadians, MIN_YAW_LIMIT).coerceIn(0f, declaredReach.coerceAtMost(MAX_YAW_LIMIT))
}
