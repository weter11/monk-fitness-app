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
    val error: String?,
    /** Anatomy heads in the definition; zero until a flagged session has built its solve. */
    val muscleHeads: Int = 0,
    val muscleHeadsRefused: Int = 0,
    val muscleInstancesDrawn: Int = 0,
    val muscleTriangles: Int = 0,
    val muscleReadings: List<MuscleHeadReading> = emptyList(),
    /** The vertex attributes the tube material required, as planned; empty until a flagged session ran. */
    val muscleAttributes: String = "",
    /** Why the tube layer refused to build, or null when it drew. */
    val muscleMaterialError: String? = null,
    /** Which material candidate drew: 0 is the provider's permutation, n>0 a donor instance. */
    val muscleMaterialCandidate: Int = -1,
    /** How many GPU primitives the tube layer has built: one per session while the topology is fixed. */
    val muscleTubeBuilds: Int = 0,
    /** False when no muscle layer is drawn: the solve is skipped and the carrier is cleared. */
    val musclesSolved: Boolean = false,
    /** Head-solves by the pass the solver reported; index 0 is a refusal before the loop ran. */
    val anatomyPassCounts: List<Int> = emptyList(),
    /** The route search's pass budget, so a histogram row can be read against it. */
    val anatomyPassCap: Int = 0,
    /** Per-stage p50/p95 over the same window as [frameTimeP95Ms]; empty until a frame ran. */
    val frameStages: List<FrameStageReading> = emptyList()
)

/**
 * One head's modelled reading. [relativePathLengthChange] is a ratio against the rest stance, so it is
 * dimensionless: a percentage of the reference length, never a length in any unit.
 */
data class MuscleHeadReading(
    val head: String,
    val relativePathLengthChange: Float,
    val drawn: Boolean
)
