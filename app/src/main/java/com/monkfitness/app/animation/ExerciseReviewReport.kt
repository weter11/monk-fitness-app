package com.monkfitness.app.animation

/**
 * Report containing automated biomechanical evaluation, ratings,
 * and actionable recommendations for an exercise sequence.
 */
data class ExerciseReviewReport(
    val score: Int, // Overall rating, e.g. 0 to 100
    val cameraProblems: String,
    val groundPenetration: String,
    val limbSymmetry: String,
    val bodyBalance: String,
    val handSliding: String,
    val footSliding: String,
    val viewportClipping: String,
    val jointDiscontinuities: String,
    val boneStretching: String,
    val recommendations: List<String>
)
