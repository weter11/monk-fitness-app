package com.monkfitness.app.ui.components

/**
 * The pure geometry of the media hero: where the overlay text may sit, the safe band the rig is
 * framed into, and which part of a touch belongs to the camera. No Android or Compose dependency —
 * layout reads these numbers and the JVM tests assert them.
 *
 * The rig is fitted by `CameraFraming.exerciseFrame` into a rectangle of [safeBand] height: the
 * framing is derived for that rectangle instead of the whole hero, so the athlete (with its ground
 * contact) can never grow into the overlay zones. The overlay text is capped to a fraction of the
 * hero height at every text scale; beyond the cap it is truncated or moved below the hero.
 */
object HeroGeometry {

    /** The fraction of the hero height each overlay zone may occupy at most. */
    const val OVERLAY_TOP_MAX_FRACTION = 0.20f
    const val OVERLAY_BOTTOM_MAX_FRACTION = 0.20f

    /** The reach of the manual rotation gesture, mirrored from `AnimationController.onRotate`. */
    const val MAX_YAW_OFFSET = 1.5708f
    const val ROTATION_SENSITIVITY = 3.14159f / 600f

    /** A touch target is at least this tall, in dp, so the free area stays reachable. */
    const val MIN_TOUCH_TARGET_DP = 48f

    /**
     * The whole hero is [heroHeightDp] tall. The overlay zones are capped fractions of it, grown by
     * the font scale only up to the cap: at 1.0/1.3/1.5 the requested text height grows, but the
     * zone never exceeds the cap, so it never eats the safe band.
     */
    data class OverlayZones(val topDp: Float, val bottomDp: Float)

    fun overlayZones(heroHeightDp: Float, requestedTopDp: Float, requestedBottomDp: Float): OverlayZones {
        val topCap = heroHeightDp * OVERLAY_TOP_MAX_FRACTION
        val bottomCap = heroHeightDp * OVERLAY_BOTTOM_MAX_FRACTION
        val top = requestedTopDp.coerceAtMost(topCap)
        val bottom = requestedBottomDp.coerceAtMost(bottomCap)
        // The two zones may never meet: the safe band keeps a minimum share of the hero.
        val minSafe = heroHeightDp * MIN_SAFE_FRACTION
        val combinedCap = (heroHeightDp - minSafe).coerceAtLeast(0f)
        return if (top + bottom > combinedCap) {
            val scale = combinedCap / (top + bottom)
            OverlayZones(top * scale, bottom * scale)
        } else {
            OverlayZones(top, bottom)
        }
    }

    /** The fraction of the hero the rig's frame is allowed to use, at least. */
    const val MIN_SAFE_FRACTION = 0.55f

    /** The rig's safe band: [top]..[bottom] in dp from the top of the hero. */
    data class SafeBand(val top: Float, val bottom: Float) {
        val height: Float get() = bottom - top
    }

    fun safeBand(heroHeightDp: Float, zones: OverlayZones): SafeBand =
        SafeBand(zones.topDp, heroHeightDp - zones.bottomDp)

    /**
     * The rectangle (in dp) `CameraFraming.exerciseFrame` must derive the frame for, so the rig is
     * drawn only inside the safe band. Width is the full hero; height is the safe band's height.
     */
    data class FramingSurface(val widthDp: Float, val heightDp: Float, val offsetYDp: Float)

    fun framingSurface(heroWidthDp: Float, heroHeightDp: Float, zones: OverlayZones): FramingSurface =
        FramingSurface(heroWidthDp, safeBand(heroHeightDp, zones).height, zones.topDp)

    /**
     * Whether a touch at [yDp] (from the top of the hero) starts in the free rotation area. A touch
     * that starts on either overlay text zone never rotates the camera.
     */
    fun isRotationArea(yDp: Float, heroHeightDp: Float, zones: OverlayZones): Boolean =
        yDp >= zones.topDp && yDp <= heroHeightDp - zones.bottomDp

    /** The hero's share of the window height, from measured dp values. */
    fun heroShare(heroHeightDp: Float, windowHeightDp: Float): Float =
        if (windowHeightDp <= 0f) 0f else heroHeightDp / windowHeightDp

    /** The share of the window a details/notes column may take below the hero (conservative model). */
    const val DETAILS_FRACTION = 0.24f

    /** The hero's guaranteed minimum share at a text scale (the smaller target at large fonts). */
    fun heroShareTarget(fontScale: Float): Float = if (fontScale >= 1.45f) 0.35f else 0.45f

    /**
     * A conservative lower bound on the hero height: the window minus a compact header, a details
     * column fixed at [DETAILS_FRACTION] of the window, and the pinned controls. The production
     * layout actually gives the hero a 0.66 weight against the details column's 0.24 weight over the
     * *flexible* space (after the wrap-content header and controls), which is always MORE than this
     * fixed-fraction model — so a share that passes this model also passes on screen. The tests assert
     * the resulting share against [heroShareTarget] at font 1.0 / 1.3 / 1.5.
     */
    fun sessionHeroHeightDp(
        windowHeightDp: Float,
        headerDp: Float,
        controlsDp: Float,
        fontScale: Float
    ): Float {
        val details = windowHeightDp * DETAILS_FRACTION
        val raw = windowHeightDp - headerDp - details - controlsDp
        return raw.coerceAtLeast(windowHeightDp * heroShareTarget(fontScale))
            .coerceAtMost(windowHeightDp)
    }
}
