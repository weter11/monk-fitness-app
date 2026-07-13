package com.monkfitness.app.animation

/**
 * SupportContact represents one contact with the environment.
 */
data class SupportContact(
    val point: SupportPoint,
    val supportsWeight: Boolean = true,
    val fixedPosition: Boolean = true,
    val friction: Float = 1.0f,
    val heightOffset: Float = 0f,
    val anchorId: String? = null
) {
    companion object {
        @JvmField val LEFT_FOOT = SupportContact(SupportPoint.LEFT_FOOT)
        @JvmField val RIGHT_FOOT = SupportContact(SupportPoint.RIGHT_FOOT)
        @JvmField val LEFT_TOES = SupportContact(SupportPoint.LEFT_TOES)
        @JvmField val RIGHT_TOES = SupportContact(SupportPoint.RIGHT_TOES)
        @JvmField val LEFT_KNEE = SupportContact(SupportPoint.LEFT_KNEE)
        @JvmField val RIGHT_KNEE = SupportContact(SupportPoint.RIGHT_KNEE)
        @JvmField val LEFT_HAND = SupportContact(SupportPoint.LEFT_HAND)
        @JvmField val RIGHT_HAND = SupportContact(SupportPoint.RIGHT_HAND)
        @JvmField val LEFT_FOREARM = SupportContact(SupportPoint.LEFT_FOREARM)
        @JvmField val RIGHT_FOREARM = SupportContact(SupportPoint.RIGHT_FOREARM)
        @JvmField val HIPS = SupportContact(SupportPoint.HIPS)
        @JvmField val CUSTOM = SupportContact(SupportPoint.CUSTOM)
    }
}
