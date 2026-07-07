package com.monkfitness.app.animation

object SkeletonEngine {
    // Reference Proportions (Pixel scale)
    const val TORSO = 120f
    const val THIGH = 112f
    const val SHIN = 98f

    const val UPARM = 64f
    const val FOREARM = 82f
    val ARMLEN = UPARM + FOREARM

    const val NECK = 18f
    const val SHW = 42f
    const val HIPW = 22f
    const val HEADR = 18f

    // Bone hierarchy for rendering
    val bones = listOf(
        // RIGHT leg (A for Active/Front, P for Planted/Back mapping depending on side)
        // In the new ref implementation, it uses hipR/hipL
        // We will keep our Joint mapping but update thicknesses to match

        // We will define bones that match the ref implementation's render loop

        // Background limbs (Right side in WGS ref)
        Bone(Joint.HIP_B, Joint.KNEE_B, 17f, 0.62f),
        Bone(Joint.KNEE_B, Joint.ANKLE_B, 13f, 0.62f),
        Bone(Joint.ANKLE_B, Joint.TOE_B, 10f, 0.62f),

        Bone(Joint.SHOULDER_P, Joint.ELBOW_P, 14f, 0.8f),
        Bone(Joint.ELBOW_P, Joint.HAND_P, 11f, 0.8f),

        // Foreground limbs (Left side in WGS ref)
        Bone(Joint.HIP_F, Joint.KNEE_F, 21f, 1.0f),
        Bone(Joint.KNEE_F, Joint.ANKLE_F, 16f, 1.0f),
        Bone(Joint.ANKLE_F, Joint.TOE_F, 12f, 1.0f),

        Bone(Joint.SHOULDER_A, Joint.ELBOW_A, 16f, 1.05f),
        Bone(Joint.ELBOW_A, Joint.HAND_A, 13f, 1.05f),

        // Spine/Neck
        Bone(Joint.PELVIS, Joint.CHEST, 17f, 0.95f), // Handled by box mainly, but kept for legacy
        Bone(Joint.CHEST, Joint.NECK_END, 12f, 0.95f)
    )
}
