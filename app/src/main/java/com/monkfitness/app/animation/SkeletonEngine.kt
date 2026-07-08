package com.monkfitness.app.animation

class SkeletonEngine(
    val definition: SkeletonDefinition,
    val style: SkeletonStyle
) {
    // Bone hierarchy for rendering
    val bones = listOf(
        // Background limbs (Right side)
        Bone(Joint.HIP_B, Joint.KNEE_B, style.thighThickness * 0.8f, 0.62f),
        Bone(Joint.KNEE_B, Joint.ANKLE_B, style.shinThickness * 0.8f, 0.62f),
        // Triangulated Foot (Right/Back)
        Bone(Joint.ANKLE_B, Joint.TOE_B, 10f, 0.62f), // Instep
        Bone(Joint.ANKLE_B, Joint.HEEL_B, 10f, 0.62f), // Calcaneus
        Bone(Joint.HEEL_B, Joint.TOE_B, 10f, 0.62f), // Sole

        Bone(Joint.SHOULDER_P, Joint.ELBOW_P, style.upperArmThickness * 0.85f, 0.8f),
        Bone(Joint.ELBOW_P, Joint.WRIST_P, style.forearmThickness * 0.85f, 0.8f),
        Bone(Joint.WRIST_P, Joint.PALM_P, style.handThickness * 0.85f, 0.8f),
        Bone(Joint.PALM_P, Joint.FINGERTIPS_P, style.handThickness * 0.8f * 0.85f, 0.8f),

        // Foreground limbs (Left side)
        Bone(Joint.HIP_F, Joint.KNEE_F, style.thighThickness, 1.0f),
        Bone(Joint.KNEE_F, Joint.ANKLE_F, style.shinThickness, 1.0f),
        // Triangulated Foot (Left/Fore)
        Bone(Joint.ANKLE_F, Joint.TOE_F, 12f, 1.0f), // Instep
        Bone(Joint.ANKLE_F, Joint.HEEL_F, 12f, 1.0f), // Calcaneus
        Bone(Joint.HEEL_F, Joint.TOE_F, 12f, 1.0f), // Sole

        Bone(Joint.SHOULDER_A, Joint.ELBOW_A, style.upperArmThickness, 1.05f),
        Bone(Joint.ELBOW_A, Joint.WRIST_A, style.forearmThickness, 1.05f),
        Bone(Joint.WRIST_A, Joint.PALM_A, style.handThickness, 1.05f),
        Bone(Joint.PALM_A, Joint.FINGERTIPS_A, style.handThickness * 0.8f, 1.05f),

        // Spine/Neck
        Bone(Joint.PELVIS, Joint.CHEST, 17f, 0.95f),
        Bone(Joint.CHEST, Joint.NECK_END, style.neckThickness, 0.95f)
    )
}
