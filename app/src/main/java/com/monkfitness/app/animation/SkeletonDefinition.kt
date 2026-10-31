package com.monkfitness.app.animation

/** Skeleton body proportions and anatomical metadata. */
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

    val armIKConstraint: IKConstraint
    val legIKConstraint: IKConstraint

    // Angular joint-limit vocabulary: shared and general, never per-exercise magic numbers, and
    // carried by the definition so the solver and validator read a single source of truth.
    val armAngularLimits: AngularJointLimits
        get() = armIKConstraint.angularLimits
    val legAngularLimits: AngularJointLimits
        get() = legIKConstraint.angularLimits

    // Hip (acetabular ball-and-socket) range of motion: named, shared human-range caps, the single
    // source of truth for over-range-hip detection (validator + optional clamp).
    val hipRomLimits: HipRomLimits
        get() = HipRomLimits.DEFAULT

    // The definition's anatomical forward axis: the default axis of spine intent comes from the
    // skeleton's own anatomical axes, never from a call-site constant. Deliberately abstract, with
    // no inherited fallback, so every definition must state its own forward.
    val anatomicalForward: Vector3

    companion object {
        val DEFAULT_ADULT: SkeletonDefinition = HumanSkeletonDefinition()
    }
}

/** Default implementation for the Monk Fitness human model. */
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
    override val legIKConstraint: IKConstraint = IKConstraint.LegConstraint,
    override val hipRomLimits: HipRomLimits = HipRomLimits.DEFAULT
) : SkeletonDefinition {
    // +Z keeps the historical call-site default, so axis-less spine authoring is bit-exact. A
    // class-body property, not a constructor parameter: adding one would silently widen
    // equals/hashCode/copy.
    override val anatomicalForward: Vector3 = Vector3(0f, 0f, 1f)
}
