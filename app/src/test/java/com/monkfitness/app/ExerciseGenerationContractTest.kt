package com.monkfitness.app

import com.monkfitness.app.animation.*
import org.junit.Assert.*
import org.junit.Test

class ExerciseGenerationContractTest {

    @Test
    fun testGenerationIterationLoopVerification() {
        val def = SkeletonDefinition.DEFAULT_ADULT
        val env = EnvironmentDefinition()

        val request = ExerciseGenerationRequest(
            exerciseName = "Test Push-Up",
            definition = def,
            environment = env
        )

        // Generate -> Validate -> Score -> Retry
        val snapshots = emptyList<ExerciseSnapshot>()
        val result = ExerciseGenerationResult(
            poses = emptyList(),
            sequence = ExerciseSnapshotSequence(snapshots)
        )

        // Validate
        val validationReport = ValidationReport(
            isValid = true,
            results = emptyList(),
            allIssues = emptyList()
        )

        // Score (derived from validation only; the standalone review pipeline no longer exists)
        val score = GenerationScore(
            overallScore = if (validationReport.isValid) 100 else 0,
            isValid = validationReport.isValid
        )

        // Feedback
        val feedback = ExerciseGenerationFeedback(
            validationReport = validationReport,
            suggestedCorrections = emptyList()
        )

        // Iteration coupling
        val iteration = GenerationIteration(
            iterationIndex = 0,
            request = request,
            result = result,
            feedback = feedback,
            score = score
        )

        // Assert contract bindings are correct and fully type-safe
        assertEquals(0, iteration.iterationIndex)
        assertEquals("Test Push-Up", iteration.request.exerciseName)
        assertNotNull(iteration.result)
        assertNotNull(iteration.feedback)
        assertEquals(100, iteration.score?.overallScore)
        assertTrue(iteration.score?.isValid == true)
    }
}
