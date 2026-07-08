package com.monkfitness.app.animation

import androidx.compose.ui.graphics.Color

data class SkeletonStyle(
    val headRadius: Float,
    val jointRadius: Float,
    val upperArmThickness: Float,
    val forearmThickness: Float,
    val thighThickness: Float,
    val shinThickness: Float,
    val neckThickness: Float,
    val torsoChestDepth: Float,
    val torsoHipDepth: Float,
    val outlineWidth: Float,
    val shadowRadiusX: Float,
    val shadowRadiusY: Float,
    val primaryColor: Color = Color(0xFF64F0DC),
    val secondaryColor: Color = Color(0xFFB4C8DC),
    val farColor: Color = Color(0xFF192337)
) {
    companion object {
        val DEFAULT = SkeletonStyle(
            headRadius = 18f,
            jointRadius = 9f,
            upperArmThickness = 16f,
            forearmThickness = 13f,
            thighThickness = 21f,
            shinThickness = 16f,
            neckThickness = 12f,
            torsoChestDepth = 22f,
            torsoHipDepth = 12f,
            outlineWidth = 2f,
            shadowRadiusX = 30f,
            shadowRadiusY = 9f
        )
    }
}
