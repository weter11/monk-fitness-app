package com.monkfitness.app.animation

/**
 * SupportContact represents specific body parts that support the body in an exercise.
 * Unlike PivotType, multiple contacts can exist simultaneously.
 * Examples:
 * - Standard Push-Up: LEFT_HAND, RIGHT_HAND, LEFT_TOES, RIGHT_TOES
 * - Knee Push-Up: LEFT_HAND, RIGHT_HAND, LEFT_KNEE, RIGHT_KNEE
 * - Plank: LEFT_FOREARM, RIGHT_FOREARM, LEFT_TOES, RIGHT_TOES
 * - Bridge: LEFT_FOOT, RIGHT_FOOT, HIPS
 * - Side Plank: LEFT_FOREARM, LEFT_FOOT
 */
enum class SupportContact {
    LEFT_FOOT,
    RIGHT_FOOT,
    LEFT_TOES,
    RIGHT_TOES,
    LEFT_KNEE,
    RIGHT_KNEE,
    LEFT_HAND,
    RIGHT_HAND,
    LEFT_FOREARM,
    RIGHT_FOREARM,
    HIPS,
    CUSTOM
}
