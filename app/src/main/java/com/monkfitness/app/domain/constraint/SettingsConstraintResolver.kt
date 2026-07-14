package com.monkfitness.app.domain.constraint

import com.monkfitness.app.data.model.FlexibilityTrainingType
import com.monkfitness.app.data.model.ExerciseCategoryFilter

class HyperlordosisRule : ConstraintRule {
    override fun validate(settings: WorkoutSettings): RuleResult {
        val disabled = settings.disabledExerciseFamilies
        val hyperlordosisEnabled = "hyperlordosis" !in disabled
        val functionalEnabled = "functional_fitness" !in disabled

        return if (hyperlordosisEnabled && functionalEnabled) {
            RuleResult(
                settings = settings.copy(disabledExerciseFamilies = disabled + "functional_fitness"),
                message = "Explosive Training is unavailable while Hyperlordosis Program is enabled."
            )
        } else {
            RuleResult(settings)
        }
    }
}

class SeniorRule : ConstraintRule {
    override fun validate(settings: WorkoutSettings): RuleResult {
        val disabled = settings.disabledExerciseFamilies
        val seniorEnabled = "senior" !in disabled
        val calisthenicsEnabled = "calisthenics" !in disabled
        val shaolinEnabled = "shaolin" !in disabled

        return if (seniorEnabled && (calisthenicsEnabled || shaolinEnabled)) {
            val nextDisabled = disabled.toMutableSet()
            if (calisthenicsEnabled) nextDisabled.add("calisthenics")
            if (shaolinEnabled) nextDisabled.add("shaolin")
            RuleResult(
                settings = settings.copy(disabledExerciseFamilies = nextDisabled),
                message = "Calisthenics and Shaolin are unavailable when Senior Friendly program is enabled."
            )
        } else {
            RuleResult(settings)
        }
    }
}

class CalisthenicsRule : ConstraintRule {
    override fun validate(settings: WorkoutSettings): RuleResult {
        val disabled = settings.disabledExerciseFamilies
        val rehabEnabled = "rehabilitation" !in disabled
        val calisthenicsEnabled = "calisthenics" !in disabled

        return if (rehabEnabled && calisthenicsEnabled) {
            RuleResult(
                settings = settings.copy(disabledExerciseFamilies = disabled + "calisthenics"),
                message = "Calisthenics is unavailable when Rehabilitation program is enabled."
            )
        } else {
            RuleResult(settings)
        }
    }
}

class PostureRule : ConstraintRule {
    override fun validate(settings: WorkoutSettings): RuleResult {
        val disabled = settings.disabledExerciseFamilies
        val postureEnabled = "posture_correction" !in disabled
        val stretchingOnly = settings.flexibilityTrainingType == FlexibilityTrainingType.STRETCHING

        return if (postureEnabled && stretchingOnly) {
            RuleResult(
                settings = settings.copy(flexibilityTrainingType = FlexibilityTrainingType.BOTH),
                message = "Flexibility training type adjusted to Both to support Posture Correction."
            )
        } else {
            RuleResult(settings)
        }
    }
}

class SettingsConstraintResolver(
    private val rules: List<ConstraintRule> = listOf(
        HyperlordosisRule(),
        SeniorRule(),
        CalisthenicsRule(),
        PostureRule()
    )
) {
    fun resolve(settings: WorkoutSettings): ConstraintResult {
        var currentSettings = settings
        var anyAdjustment = false
        val allMessages = mutableListOf<String>()

        var iterations = 0
        var changed: Boolean
        do {
            changed = false
            for (rule in rules) {
                val result = rule.validate(currentSettings)
                if (result.settings != currentSettings) {
                    currentSettings = result.settings
                    result.message?.let { allMessages.add(it) }
                    changed = true
                    anyAdjustment = true
                }
            }
            // Fallback safety check: make sure at least one category filter key is enabled
            val allKeys = ExerciseCategoryFilter.entries.map { it.key }.toSet()
            if (currentSettings.disabledExerciseFamilies.size >= allKeys.size) {
                // Keep mobility enabled
                currentSettings = currentSettings.copy(
                    disabledExerciseFamilies = currentSettings.disabledExerciseFamilies - "mobility"
                )
                anyAdjustment = true
            }
            iterations++
        } while (changed && iterations < 10)

        return ConstraintResult(
            adjustedSettings = currentSettings,
            adjustmentsMade = anyAdjustment,
            messages = allMessages.distinct()
        )
    }
}
