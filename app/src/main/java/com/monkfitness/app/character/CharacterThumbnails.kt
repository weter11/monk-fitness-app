package com.monkfitness.app.character

/**
 * Identity of a static character thumbnail. Every dimension that can change the pixels is in the
 * key, so a preset or asset change is a miss rather than a stale image.
 */
data class CharacterThumbnailKey(
    val characterId: String,
    val morphology: String,
    val animationId: String,
    val qualityTier: String,
    val poseProgressPercent: Int,
    val assetVersion: String
)

/**
 * Cache identity and budget for static thumbnails. Lists render the 2D fallback today; the
 * offscreen single-frame renderer is proposed in docs/CHARACTER_RENDERING.md §7.
 */
object CharacterThumbnails {

    const val DEFAULT_TIER = "standard"
    const val THUMBNAIL_PX = 192
    const val MAX_CACHE_BYTES = 4 * 1024 * 1024
    const val MAX_ENTRIES = 24

    /** Bumped whenever the packaged character asset changes; part of every cache key. */
    const val ASSET_VERSION = "humanoid_v1/1"

    fun key(
        animationId: String,
        morphology: MorphologyPreset,
        progress: Float = 0.5f,
        tier: String = DEFAULT_TIER
    ): CharacterThumbnailKey = CharacterThumbnailKey(
        characterId = CharacterFeature.CHARACTER_ID,
        morphology = morphology.id,
        animationId = animationId,
        qualityTier = tier,
        poseProgressPercent = (progress.coerceIn(0f, 1f) * 100).toInt(),
        assetVersion = ASSET_VERSION
    )

    fun bitmapBytes(px: Int = THUMBNAIL_PX): Int = px * px * 4

    /** Whether [entries] thumbnails still fit both the byte budget and the entry cap. */
    fun fitsBudget(entries: Int, px: Int = THUMBNAIL_PX): Boolean =
        entries in 0..MAX_ENTRIES && entries.toLong() * bitmapBytes(px) <= MAX_CACHE_BYTES
}
