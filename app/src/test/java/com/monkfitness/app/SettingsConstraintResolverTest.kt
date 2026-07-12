package com.monkfitness.app

import com.monkfitness.app.data.model.FlexibilityTrainingType
import com.monkfitness.app.domain.constraint.WorkoutSettings
import com.monkfitness.app.domain.constraint.SettingsConstraintResolver
import com.monkfitness.app.domain.constraint.HyperlordosisRule
import com.monkfitness.app.domain.constraint.SeniorRule
import com.monkfitness.app.domain.constraint.CalisthenicsRule
import com.monkfitness.app.domain.constraint.PostureRule
import com.monkfitness.app.data.model.ExerciseCategoryFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsConstraintResolverTest {

    private val resolver = SettingsConstraintResolver()

    @Test
    fun testValidCombination() {
        val settings = WorkoutSettings(
            disabledExerciseFamilies = setOf("senior", "hyperlordosis", "rehabilitation", "posture_correction")
        )
        val result = resolver.resolve(settings)
        assertFalse(result.adjustmentsMade)
        assertEquals(settings, result.adjustedSettings)
    }

    @Test
    fun testHyperlordosisConflict() {
        val settings = WorkoutSettings(
            disabledExerciseFamilies = setOf("senior", "rehabilitation")
        )
        val result = resolver.resolve(settings)
        assertTrue(result.adjustmentsMade)
        assertTrue("functional_fitness" in result.adjustedSettings.disabledExerciseFamilies)
    }

    @Test
    fun testSeniorConflict() {
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
        val settings = WorkoutSettings(
            disabledExerciseFamilies = setOf("senior", "hyperlordosis")
        )
        val result = resolver.resolve(settings)
        assertTrue(result.adjustmentsMade)
        assertTrue("calisthenics" in result.adjustedSettings.disabledExerciseFamilies)
    }

    @Test
    fun testPostureCorrectionConflict() {
        val settings = WorkoutSettings(
            flexibilityTrainingType = FlexibilityTrainingType.STRETCHING,
            disabledExerciseFamilies = setOf("senior", "hyperlordosis", "rehabilitation")
        )
        val result = resolver.resolve(settings)
        assertTrue(result.adjustmentsMade)
        assertEquals(FlexibilityTrainingType.BOTH, result.adjustedSettings.flexibilityTrainingType)
    }

    // 1. Cover every constraint rule individually (isolated unit tests)
    @Test
    fun testIndividualHyperlordosisRule() {
        val rule = HyperlordosisRule()
        val settings = WorkoutSettings(disabledExerciseFamilies = emptySet())
        val result = rule.validate(settings)
        assertTrue("functional_fitness" in result.settings.disabledExerciseFamilies)
        assertEquals("Explosive Training is unavailable while Hyperlordosis Program is enabled.", result.message)
    }

    @Test
    fun testIndividualSeniorRule() {
        val rule = SeniorRule()
        val settings = WorkoutSettings(disabledExerciseFamilies = emptySet())
        val result = rule.validate(settings)
        assertTrue("calisthenics" in result.settings.disabledExerciseFamilies)
        assertTrue("shaolin" in result.settings.disabledExerciseFamilies)
        assertEquals("Calisthenics and Shaolin are unavailable when Senior Friendly program is enabled.", result.message)
    }

    @Test
    fun testIndividualCalisthenicsRule() {
        val rule = CalisthenicsRule()
        val settings = WorkoutSettings(disabledExerciseFamilies = emptySet())
        val result = rule.validate(settings)
        assertTrue("calisthenics" in result.settings.disabledExerciseFamilies)
        assertEquals("Calisthenics is unavailable when Rehabilitation program is enabled.", result.message)
    }

    @Test
    fun testIndividualPostureRule() {
        val rule = PostureRule()
        val settings = WorkoutSettings(
            flexibilityTrainingType = FlexibilityTrainingType.STRETCHING,
            disabledExerciseFamilies = emptySet()
        )
        val result = rule.validate(settings)
        assertEquals(FlexibilityTrainingType.BOTH, result.settings.flexibilityTrainingType)
        assertEquals("Flexibility training type adjusted to Both to support Posture Correction.", result.message)
    }

    // 2. Combinations of multiple rules simultaneously
    @Test
    fun testMultipleRulesSimultaneously() {
        val settings = WorkoutSettings(
            flexibilityTrainingType = FlexibilityTrainingType.STRETCHING,
            disabledExerciseFamilies = emptySet() // Everything is enabled, which triggers all rules!
        )
        val result = resolver.resolve(settings)
        assertTrue(result.adjustmentsMade)
        // Check that Hyperlordosis resolved functional_fitness
        assertTrue("functional_fitness" in result.adjustedSettings.disabledExerciseFamilies)
        // Check that Senior resolved calisthenics & shaolin
        assertTrue("calisthenics" in result.adjustedSettings.disabledExerciseFamilies)
        assertTrue("shaolin" in result.adjustedSettings.disabledExerciseFamilies)
        // Check that Posture resolved training type
        assertEquals(FlexibilityTrainingType.BOTH, result.adjustedSettings.flexibilityTrainingType)
        // Check that we collected multiple messages
        assertTrue(result.messages.size >= 3)
    }

    // 3. Loading legacy settings from older versions
    @Test
    fun testSanitizingLegacySettings() {
        // Suppose legacy settings from older version contained incompatible combinations
        val legacySettings = WorkoutSettings(
            flexibilityTrainingType = FlexibilityTrainingType.STRETCHING,
            disabledExerciseFamilies = setOf("senior") // hyperlordosis & posture are enabled but STRETCHING only is set
        )
        val result = resolver.resolve(legacySettings)
        assertTrue(result.adjustmentsMade)
        // Verify STRETCHING only was corrected to BOTH because posture_correction is enabled
        assertEquals(FlexibilityTrainingType.BOTH, result.adjustedSettings.flexibilityTrainingType)
        // Verify hyperlordosis corrected functional_fitness
        assertTrue("functional_fitness" in result.adjustedSettings.disabledExerciseFamilies)
    }

    // 4. Attempting to disable every exercise category simultaneously
    @Test
    fun testAttemptingToDisableAllCategories() {
        val allKeys = ExerciseCategoryFilter.entries.map { it.key }.toSet()
        val settings = WorkoutSettings(
            disabledExerciseFamilies = allKeys
        )
        val result = resolver.resolve(settings)
        assertTrue(result.adjustmentsMade)
        // Ensure at least one category (mobility) was kept enabled (i.e. removed from disabledExerciseFamilies)
        assertTrue(result.adjustedSettings.disabledExerciseFamilies.size < allKeys.size)
        assertFalse("mobility" in result.adjustedSettings.disabledExerciseFamilies)
    }
}
