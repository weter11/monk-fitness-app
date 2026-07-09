package com.monkfitness.app.animation

import kotlin.math.PI

data class FootDefinition(
    val footLength: Float,
    val heelRatio: Float = 0.29f,
    val toeRatio: Float = 0.71f,
    val ankleHeight: Float = 15f,
    val minPitch: Float = -45f * PI.toFloat() / 180f,
    val maxPitch: Float = 45f * PI.toFloat() / 180f
) {
    /**
     * Procedurally computes Heel and Toe positions from Ankle and a forward direction.
     * The ankle remains the rotational pivot (origin in this context).
     */
    fun computeHeelToe(ankle: Vector3, forward: Vector3, outHeel: Vector3, outToe: Vector3) {
        val dir = forward.normalizedCopy()
        outHeel.set(dir).multiply(-(footLength * heelRatio)).add(ankle)
        outToe.set(dir).multiply(footLength * toeRatio).add(ankle)
    }
}
