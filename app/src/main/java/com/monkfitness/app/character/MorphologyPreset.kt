package com.monkfitness.app.character

/**
 * Vestigial body-shape parameters carried by the test asset. One morph target per region, so a
 * preset is a vector and changing it is a renderable update, never an engine rebuild.
 */
enum class MorphologyPreset(val id: String, val weights: FloatArray) {
    NEUTRAL("neutral", floatArrayOf(0f, 0f, 0f, 0f, 0f)),
    SLIM("slim", floatArrayOf(0.35f, -0.25f, -0.55f, -0.45f, 0f)),
    HEAVY("heavy", floatArrayOf(0.15f, 0.85f, 1f, 0.8f, 0f)),
    MUSCULAR("muscular", floatArrayOf(1f, 0.7f, -0.35f, 0.9f, 0f));

    fun copyWeights(): FloatArray = weights.copyOf()

    companion object {
        fun byId(id: String): MorphologyPreset = entries.firstOrNull { it.id == id } ?: NEUTRAL
    }
}
