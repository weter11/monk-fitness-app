package com.monkfitness.app

import com.monkfitness.app.animation.*
import org.junit.Assert.*
import org.junit.Test

class ExerciseGenerationContractTest {

    @Test
    fun testGenerationIterationLoopVerification() {
        val def = SkeletonDefinition.DEFAULT_ADULT
        val env = EnvironmentDefinition()

        // 1. Generate Request
        val request = ExerciseGenerationRequest(
            exerciseName = "Test Push-Up",
            definition = def,
            environment = env
        )

        // 2. Mock generation result showing the loop parameters:
        // Generate -> Validate -> Review -> Score -> Retry
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

        // Review
        val reviewReport = ExerciseReview.review(validationReport, result.sequence)

        // Score
        val score = GenerationScore(
            overallScore = reviewReport.score,
            isValid = validationReport.isValid
        )

        // Feedback
        val feedback = ExerciseGenerationFeedback(
            validationReport = validationReport,
            reviewReport = reviewReport,
            suggestedCorrections = reviewReport.recommendations
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
