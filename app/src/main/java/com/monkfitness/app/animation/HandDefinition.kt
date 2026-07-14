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

    // Allocation-free scratch for the orientation-aware overload's working direction.
    private val scratchDir = Vector3()

    /**
     * Computes the positions of the hand joints in world space.
     * The hand is treated as a rigid extension of the forearm along [direction].
     * @param wrist The world position of the wrist joint.
     * @param direction The forward direction of the hand (forearm direction).
     */
    fun computeHandJoints(wrist: Vector3, direction: Vector3, result: HandJoints) {
        val dir = direction.normalizedCopy()
        writeJoints(wrist, dir, result)
    }

    /**
     * Orientation-aware overload. Promotes the wrist to a real joint: the authored
     * [wristRotation] is composed with the forearm [direction] to build the completed
     * hand basis, so grips (pronation / supination / wrist flexion) are honored instead
     * of being dropped. When [wristRotation] is the identity rotation the result is
     * identical to [computeHandJoints], preserving all non-grip rendering.
     *
     * Allocation-free: writes into [scratchDir] and [result].
     */
    fun computeHandJoints(
        wrist: Vector3,
        direction: Vector3,
        wristRotation: JointRotation,
        result: HandJoints
    ) {
        SkeletonMath.rotAround(direction, wristRotation.axis, wristRotation.angle, scratchDir)
        scratchDir.normalize()
        writeJoints(wrist, scratchDir, result)
    }

    private fun writeJoints(wrist: Vector3, dir: Vector3, result: HandJoints) {
        result.wrist.set(wrist)
        result.palm.set(dir).multiply(palmLength * 0.5f).add(wrist)
        result.knuckles.set(dir).multiply(palmLength).add(wrist)
        result.fingertips.set(dir).multiply(palmLength + fingerLength).add(wrist)
    }
}

class HandJoints(
    val wrist: Vector3 = Vector3(),
    val palm: Vector3 = Vector3(),
    val knuckles: Vector3 = Vector3(),
    val fingertips: Vector3 = Vector3()
)
