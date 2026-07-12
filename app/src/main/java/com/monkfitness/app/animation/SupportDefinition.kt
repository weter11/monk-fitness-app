package com.monkfitness.app.animation

/**
 * SupportDefinition completely describes how the body is supported in an exercise.
 * Examples:
 * - Standard Push-Up: PivotType.FEET, contacts = LEFT_HAND, RIGHT_HAND, LEFT_TOES, RIGHT_TOES
 * - Knee Push-Up: PivotType.KNEES, contacts = LEFT_HAND, RIGHT_HAND, LEFT_KNEE, RIGHT_KNEE
 * - Decline Push-Up: PivotType.FEET, contacts = LEFT_HAND, RIGHT_HAND, LEFT_TOES, RIGHT_TOES, supportHeight = benchHeight
 * - Plank: PivotType.FEET, contacts = LEFT_FOREARM, RIGHT_FOREARM, LEFT_TOES, RIGHT_TOES
 * - Lunge: PivotType.FEET, contacts = LEFT_FOOT, RIGHT_FOOT
 * - Bridge: PivotType.FEET, contacts = LEFT_FOOT, RIGHT_FOOT, HIPS
 */
data class SupportDefinition(
    val pivot: PivotType,
    val contacts: Set<SupportContact>,
    val supportHeight: Float = 0f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val offsetZ: Float = 0f
)
