package com.monkfitness.app.animation

/** Target requirements, constraints and metadata for one automated generation run. */
data class ExerciseGenerationRequest(
    val exerciseName: String,
    val definition: SkeletonDefinition,
    val environment: EnvironmentDefinition,
    val targetDurationSeconds: Float = 3.0f,
    val targetFrameCount: Int = 10,
    val targetMuscleGroups: List<String> = emptyList(),
    val customConstraints: Map<String, Any> = emptyMap()
)

/** Output of a single pose/sequence generation run. */
data class ExerciseGenerationResult(
    val poses: List<SkeletonPose>,
    val sequence: ExerciseSnapshotSequence
)

/**
 * All biomechanical errors plus specific retry correction guides, to steer the next generation
 * iteration.
 */
data class ExerciseGenerationFeedback(
    val validationReport: ValidationReport,
    val suggestedCorrections: List<String> = emptyList()
)

/** Biomechanical and visual score of an iteration, with per-area sub-scores. */
data class GenerationScore(
    val overallScore: Int, // 0 to 100
    val isValid: Boolean,  // Whether the generated sequence meets all ERROR-free criteria
    val categoryScores: Map<String, Int> = emptyMap()
)

/** One step of the feedback retry loop: request, outcome, feedback and score. */
data class GenerationIteration(
    val iterationIndex: Int,
    val request: ExerciseGenerationRequest,
    val result: ExerciseGenerationResult?,
    val feedback: ExerciseGenerationFeedback?,
    val score: GenerationScore?
)

/** Automated exercise generation engine: an iterative, feedback-driven loop. */
interface ExerciseGeneratorService {
    /**
     * Generates a new exercise sequence or poses. `history` carries previous iterations, whose
     * feedback steers the corrections.
     */
    fun generate(
        request: ExerciseGenerationRequest,
        history: List<GenerationIteration> = emptyList()
    ): ExerciseGenerationResult
}
