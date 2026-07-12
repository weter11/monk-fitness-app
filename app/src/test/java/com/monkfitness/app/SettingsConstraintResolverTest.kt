package com.monkfitness.app

import com.monkfitness.app.data.model.FlexibilityTrainingType
import com.monkfitness.app.domain.constraint.WorkoutSettings
import com.monkfitness.app.domain.constraint.SettingsConstraintResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsConstraintResolverTest {

    private val resolver = SettingsConstraintResolver()

    @Test
    fun testValidCombination() {
        val settings = WorkoutSettings(
            // Disable all conflicting special programs to have a valid conflict-free settings state
            disabledExerciseFamilies = setOf("senior", "hyperlordosis", "rehabilitation", "posture_correction")
        )
        val result = resolver.resolve(settings)
        assertFalse(result.adjustmentsMade)
        assertEquals(settings, result.adjustedSettings)
    }

    @Test
    fun testHyperlordosisConflict() {
        // Hyperlordosis enabled (not disabled), Senior disabled, rehab disabled
        val settings = WorkoutSettings(
            disabledExerciseFamilies = setOf("senior", "rehabilitation")
        )
        val result = resolver.resolve(settings)
        assertTrue(result.adjustmentsMade)
        assertTrue("functional_fitness" in result.adjustedSettings.disabledExerciseFamilies)
    }

    @Test
    fun testSeniorConflict() {
        // Senior enabled, hyperlordosis disabled, rehab disabled
        val settings = WorkoutSettings(
            disabledExerciseFamilies = setOf("hyperlordosis", "rehabilitation")
        )
        val result = resolver.resolve(settings)
        assertTrue(result.adjustmentsMade)
        assertTrue("calisthenics" in result.adjustedSettings.disabledExerciseFamilies)
        assertTrue("shaolin" in result.adjustedSettings.disabledExerciseFamilies)
    }

    @Test
    fun testRehabilitationAndCalisthenicsConflict() {
        // Rehabilitation enabled, senior disabled, hyperlordosis disabled
        val settings = WorkoutSettings(
            disabledExerciseFamilies = setOf("senior", "hyperlordosis")
        )
        val result = resolver.resolve(settings)
        assertTrue(result.adjustmentsMade)
        assertTrue("calisthenics" in result.adjustedSettings.disabledExerciseFamilies)
    }

    @Test
    fun testPostureCorrectionConflict() {
        // Posture correction enabled, senior/hyperlordosis/rehab disabled, STRETCHING only training type
        val settings = WorkoutSettings(
            flexibilityTrainingType = FlexibilityTrainingType.STRETCHING,
            disabledExerciseFamilies = setOf("senior", "hyperlordosis", "rehabilitation")
        )
        val result = resolver.resolve(settings)
        assertTrue(result.adjustmentsMade)
        assertEquals(FlexibilityTrainingType.BOTH, result.adjustedSettings.flexibilityTrainingType)
    }
}
