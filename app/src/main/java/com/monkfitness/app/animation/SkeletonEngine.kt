package com.monkfitness.app.animation

object SkeletonEngine {
    // Immutable bone lengths from p5.js
    const val THIGH = 96f
    const val SHIN = 92f
    const val TORSO = 104f
    const val UPARM = 72f
    const val FOREARM = 68f
    val ARMLEN = UPARM + FOREARM
    const val HIPW = 20f
    const val SHW = 34f
    const val NECK = 14f
    const val HEADR = 19f

    // Bone hierarchy for rendering
    val bones = listOf(
        // Back leg
        Bone(Joint.HIP_B, Joint.KNEE_B, 9f, 0.62f),
        Bone(Joint.KNEE_B, Joint.ANKLE_B, 8f, 0.62f),
        Bone(Joint.ANKLE_B, Joint.TOE_B, 6f, 0.62f),

        // Pelvis + torso + shoulder girdle
        Bone(Joint.HIP_F, Joint.HIP_B, 13f, 0.9f),
        Bone(Joint.PELVIS, Joint.CHEST, 17f, 0.95f),
        Bone(Joint.SHOULDER_P, Joint.SHOULDER_A, 12f, 0.95f),
        Bone(Joint.CHEST, Joint.NECK_END, 8f, 0.95f),

        // Planted support arm
        Bone(Joint.SHOULDER_P, Joint.ELBOW_P, 8f, 0.8f),
        Bone(Joint.ELBOW_P, Joint.HAND_P, 7f, 0.8f),

        // Front leg
        Bone(Joint.HIP_F, Joint.KNEE_F, 15f, 1.0f),
        Bone(Joint.KNEE_F, Joint.ANKLE_F, 13f, 1.0f),
        Bone(Joint.ANKLE_F, Joint.TOE_F, 10f, 1.0f),

        // Active arm
        Bone(Joint.SHOULDER_A, Joint.ELBOW_A, 12f, 1.05f),
        Bone(Joint.ELBOW_A, Joint.HAND_A, 10f, 1.05f)
    )
}
