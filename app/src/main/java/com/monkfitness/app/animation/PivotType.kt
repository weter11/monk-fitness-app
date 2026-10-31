package com.monkfitness.app.animation

/**
 * Which body segments contribute to the primary lever arm: FEET for push-ups, squats and lunges,
 * KNEES for knee push-ups, HANDS for handstands, HIPS for glute bridges, ELBOWS for planks,
 * PELVIS for a rigid lever defined at the pelvis, CUSTOM for a user-defined one.
 */
enum class PivotType {
    FEET,
    KNEES,
    HANDS,
    HIPS,
    ELBOWS,
    PELVIS,
    CUSTOM
}
