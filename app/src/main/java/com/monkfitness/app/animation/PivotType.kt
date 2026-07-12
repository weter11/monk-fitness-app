package com.monkfitness.app.animation

/**
 * The pivot type determines which body segments contribute to the primary lever arm.
 * Examples:
 * - FEET: Standard push-ups, squats, lunges, decline push-ups
 * - KNEES: Knee push-ups
 * - HANDS: Handstands
 * - HIPS: Glute bridges
 * - ELBOWS: Planks
 * - CUSTOM: User-defined
 */
enum class PivotType {
    FEET,
    KNEES,
    HANDS,
    HIPS,
    ELBOWS,
    CUSTOM
}
