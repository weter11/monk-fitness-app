package com.monkfitness.app.animation

class SkeletonEngine(val definition: SkeletonDefinition) {
    // Bone hierarchy for rendering
    val bones = listOf(
        // Background limbs (Right side) - using reduced thickness for depth perception
        Bone(Joint.HIP_B, Joint.KNEE_B, definition.thighThickness * 0.8f, 0.62f),
        Bone(Joint.KNEE_B, Joint.ANKLE_B, definition.shinThickness * 0.8f, 0.62f),
        Bone(Joint.ANKLE_B, Joint.TOE_B, 10f, 0.62f),

        Bone(Joint.SHOULDER_P, Joint.ELBOW_P, definition.upperArmThickness * 0.85f, 0.8f),
        Bone(Joint.ELBOW_P, Joint.HAND_P, definition.forearmThickness * 0.85f, 0.8f),

        // Foreground limbs (Left side)
        Bone(Joint.HIP_F, Joint.KNEE_F, definition.thighThickness, 1.0f),
        Bone(Joint.KNEE_F, Joint.ANKLE_F, definition.shinThickness, 1.0f),
        Bone(Joint.ANKLE_F, Joint.TOE_F, 12f, 1.0f),

        Bone(Joint.SHOULDER_A, Joint.ELBOW_A, definition.upperArmThickness, 1.05f),
        Bone(Joint.ELBOW_A, Joint.HAND_A, definition.forearmThickness, 1.05f),

        // Spine/Neck
        Bone(Joint.PELVIS, Joint.CHEST, 17f, 0.95f),
        Bone(Joint.CHEST, Joint.NECK_END, definition.neckThickness, 0.95f)
    )
}
