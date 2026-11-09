package com.monkfitness.app.character

import com.monkfitness.app.animation.SkeletonPose

/** Supplies the engine frame to show, before any 2D projection; the pose carrier is reused. */
fun interface CharacterPoseSource {
    fun pose(): SkeletonPose
}

/** Snapshot for the diagnostics screen; never read by the production UI. */
data class CharacterDiagnostics(
    val requested: BackendRequest,
    val activeBackend: String,
    val attempts: List<BackendAttempt>,
    val capabilities: GraphicsCapabilities,
    val fallbackReason: String?,
    val joints: Int,
    val morphTargets: Int,
    val morphology: String,
    val frames: Long,
    val fps: Float,
    val frameTimeP95Ms: Float,
    val hasSurface: Boolean,
    val running: Boolean,
    val error: String?
)
