package com.monkfitness.app.animation

/**
 * HandDefinition describes the anatomical structure of the hand.
 * All positions are offsets relative to the wrist (0,0,0).
 */
data class HandDefinition(
    val palmLength: Float = 12f,
    val fingerLength: Float = 10f,
    val handWidth: Float = 10f
) {
    // Relative to wrist (0,0,0) in hand-local space (X is forward)
    val wristLocal = Vector3(0f, 0f, 0f)
    val palmLocal = Vector3(palmLength * 0.5f, 0f, 0f)
    val knucklesLocal = Vector3(palmLength, 0f, 0f)
    val fingertipsLocal = Vector3(palmLength + fingerLength, 0f, 0f)

    /**
     * Computes the positions of the hand joints in world space.
     * @param wrist The world position of the wrist joint.
     * @param direction The forward direction of the hand.
     */
    fun computeHandJoints(wrist: Vector3, direction: Vector3): HandJoints {
        val dir = direction.normalize()
        return HandJoints(
            wrist = wrist,
            palm = wrist + dir * (palmLength * 0.5f),
            knuckles = wrist + dir * palmLength,
            fingertips = wrist + dir * (palmLength + fingerLength)
        )
    }
}

data class HandJoints(
    val wrist: Vector3,
    val palm: Vector3,
    val knuckles: Vector3,
    val fingertips: Vector3
)
