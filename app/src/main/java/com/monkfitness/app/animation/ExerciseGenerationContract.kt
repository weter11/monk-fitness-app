package com.monkfitness.app.animation

/**
 * ExerciseGenerationRequest encapsulates all target requirements, constraints,
 * and metadata requested for an automated exercise generation run.
 */
data class ExerciseGenerationRequest(
    val exerciseName: String,
    val definition: SkeletonDefinition,
    val environment: EnvironmentDefinition,
    val targetDurationSeconds: Float = 3.0f,
    val targetFrameCount: Int = 10,
    val targetMuscleGroups: List<String> = emptyList(),
    val customConstraints: Map<String, Any> = emptyMap()
)

/**
 * ExerciseGenerationResult represents the output of a single pose/sequence generation run.
 */
data class ExerciseGenerationResult(
    val poses: List<SkeletonPose>,
    val sequence: ExerciseSnapshotSequence
)

/**
 * ExerciseGenerationFeedback compiles all biomechanical errors and specific retry
 * correction guides to help steer the next generation iteration.
 */
data class ExerciseGenerationFeedback(
    val validationReport: ValidationReport,
    val suggestedCorrections: List<String> = emptyList()
)

/**
 * GenerationScore represents the detailed biomechanical and visual score of an iteration,
 * including sub-scores for specific areas (e.g. balance, symmetry, continuity).
 */
data class GenerationScore(
    val overallScore: Int, // 0 to 100
    val isValid: Boolean,  // Whether the generated sequence meets all ERROR-free criteria
    val categoryScores: Map<String, Int> = emptyMap()
)

/**
 * GenerationIteration couples all request, outcome, feedback, and scoring parameters
 * for a single step in the feedback retry loop.
 */
data class GenerationIteration(
    val iterationIndex: Int,
    val request: ExerciseGenerationRequest,
    val result: ExerciseGenerationResult?,
    val feedback: ExerciseGenerationFeedback?,
    val score: GenerationScore?
)

/**
 * ExerciseGeneratorService defines the interface for an automated exercise generation engine,
 * enabling an iterative, feedback-driven generation loop.
 */
interface ExerciseGeneratorService {
    /**
     * Generates a new exercise sequence or poses.
     * @param request The current target requirements.
     * @param history Previous iterations containing results and feedback to steer corrections.
     */
    fun generate(
        request: ExerciseGenerationRequest,
        history: List<GenerationIteration> = emptyList()
    ): ExerciseGenerationResult
}
