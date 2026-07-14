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
    val defaultCamera: CameraDefinition

    // Biomechanical constraints
    val armIKConstraint: IKConstraint
    val legIKConstraint: IKConstraint

    // Angular joint-limit vocabulary (shared, general — never per-exercise magic numbers).
    // Carried by the definition so the solver and validator read a single source of truth.
    val armAngularLimits: AngularJointLimits
        get() = armIKConstraint.angularLimits
    val legAngularLimits: AngularJointLimits
        get() = legIKConstraint.angularLimits

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
    override val upperArmLength: Float = 80f,
    override val forearmLength: Float = 66f,
    override val hand: HandDefinition = HandDefinition(),
    override val shoulderWidth: Float = 46f,
    override val hipWidth: Float = 22f,
    override val defaultCamera: CameraDefinition = CameraDefinition.DEFAULT,

    override val armIKConstraint: IKConstraint = IKConstraint.ArmConstraint,
    override val legIKConstraint: IKConstraint = IKConstraint.LegConstraint
) : SkeletonDefinition

// For backward compatibility during migration
typealias LegacySkeletonDefinition = HumanSkeletonDefinition
