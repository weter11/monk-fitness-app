package com.monkfitness.app.animation

/**
 * Generic interface for skeleton body proportions and anatomical metadata.
 */
interface SkeletonDefinition {
    val torsoLength: Float
    val neckLength: Float
    val thighLength: Float
    val shinLength: Float
    val footLength: Float
    val foot: FootDefinition
    val upperArmLength: Float
    val forearmLength: Float
    val hand: HandDefinition
    val shoulderWidth: Float
    val hipWidth: Float

    // Biomechanical constraints
    val armIKConstraint: IKConstraint
    val legIKConstraint: IKConstraint

    companion object {
        val DEFAULT_ADULT: SkeletonDefinition = HumanSkeletonDefinition()
    }
}

/**
 * Default implementation for the Monk Fitness human model.
 */
data class HumanSkeletonDefinition(
    override val torsoLength: Float = 120f,
    override val neckLength: Float = 18f,
    override val thighLength: Float = 112f,
    override val shinLength: Float = 98f,
    override val footLength: Float = 35f,
    override val foot: FootDefinition = FootDefinition(footLength),
    override val upperArmLength: Float = 64f,
    override val forearmLength: Float = 82f,
    override val hand: HandDefinition = HandDefinition(),
    override val shoulderWidth: Float = 42f,
    override val hipWidth: Float = 22f,

    override val armIKConstraint: IKConstraint = IKConstraint.ArmConstraint,
    override val legIKConstraint: IKConstraint = IKConstraint.LegConstraint
) : SkeletonDefinition

// For backward compatibility during migration
typealias LegacySkeletonDefinition = HumanSkeletonDefinition
