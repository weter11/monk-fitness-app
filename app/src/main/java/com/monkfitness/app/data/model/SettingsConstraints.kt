package com.monkfitness.app.data.model

import com.monkfitness.app.data.model.ExerciseCategoryFilter
import com.monkfitness.app.data.model.ExerciseSubCategory
import com.monkfitness.app.data.model.FlexibilityTrainingType
import com.monkfitness.app.data.model.Equipment

data class ConstraintResult(
    val isValid: Boolean,
    val adjustedDisabledFamilies: Set<String>,
    val adjustedFocusAreas: Set<ExerciseSubCategory>,
    val disabledOptionsExplanation: Map<String, String> = emptyMap()
)

interface ConstraintRule {
    fun evaluate(
        disabledFamilies: Set<String>,
        flexibilityType: FlexibilityTrainingType,
        focusAreas: Set<ExerciseSubCategory>
    ): ConstraintResult
}

class HyperlordosisRule : ConstraintRule {
    override fun evaluate(
        disabledFamilies: Set<String>,
        flexibilityType: FlexibilityTrainingType,
        focusAreas: Set<ExerciseSubCategory>
    ): ConstraintResult {
        val isHyperlordosisEnabled = "hyperlordosis" !in disabledFamilies
        if (isHyperlordosisEnabled) {
            val explosiveKeys = setOf("shaolin")
            val nextDisabled = disabledFamilies + explosiveKeys
            val explanation = explosiveKeys.associateWith { "Unavailable while Hyperlordosis Program is enabled." }
            return ConstraintResult(
                isValid = !explosiveKeys.any { it !in disabledFamilies },
                adjustedDisabledFamilies = nextDisabled,
                adjustedFocusAreas = focusAreas,
                disabledOptionsExplanation = explanation
            )
        }
        return ConstraintResult(true, disabledFamilies, focusAreas)
    }
}

class SeniorRule : ConstraintRule {
    override fun evaluate(
        disabledFamilies: Set<String>,
        flexibilityType: FlexibilityTrainingType,
        focusAreas: Set<ExerciseSubCategory>
    ): ConstraintResult {
        val isSeniorEnabled = "senior" !in disabledFamilies
        if (isSeniorEnabled) {
            val seniorDisabledKeys = setOf("shaolin", "calisthenics")
            val nextDisabled = disabledFamilies + seniorDisabledKeys
            val explanation = seniorDisabledKeys.associateWith { "Unavailable for Senior Friendly training." }
            return ConstraintResult(
                isValid = !seniorDisabledKeys.any { it !in disabledFamilies },
                adjustedDisabledFamilies = nextDisabled,
                adjustedFocusAreas = focusAreas,
                disabledOptionsExplanation = explanation
            )
        }
        return ConstraintResult(true, disabledFamilies, focusAreas)
    }
}

class CalisthenicsRule : ConstraintRule {
    override fun evaluate(
        disabledFamilies: Set<String>,
        flexibilityType: FlexibilityTrainingType,
        focusAreas: Set<ExerciseSubCategory>
    ): ConstraintResult {
        val isCalisthenicsEnabled = "calisthenics" !in disabledFamilies
        if (isCalisthenicsEnabled) {
            val rehabKeys = setOf("rehabilitation")
            val nextDisabled = disabledFamilies + rehabKeys
            val explanation = rehabKeys.associateWith { "Rehabilitation cannot be combined with Advanced Calisthenics." }
            return ConstraintResult(
                isValid = !rehabKeys.any { it !in disabledFamilies },
                adjustedDisabledFamilies = nextDisabled,
                adjustedFocusAreas = focusAreas,
                disabledOptionsExplanation = explanation
            )
        }
        return ConstraintResult(true, disabledFamilies, focusAreas)
    }
}

class PostureRule : ConstraintRule {
    override fun evaluate(
        disabledFamilies: Set<String>,
        flexibilityType: FlexibilityTrainingType,
        focusAreas: Set<ExerciseSubCategory>
    ): ConstraintResult {
        return ConstraintResult(true, disabledFamilies, focusAreas)
    }
}

object SettingsConstraintResolver {
    val rules = listOf(
        HyperlordosisRule(),
        SeniorRule(),
        CalisthenicsRule(),
        PostureRule()
    )

    fun resolve(
        disabledFamilies: Set<String>,
        flexibilityType: FlexibilityTrainingType,
        focusAreas: Set<ExerciseSubCategory>
    ): ConstraintResult {
        val validCategoryKeys = com.monkfitness.app.data.model.ExerciseCategoryFilter.entries.map { it.key }.toSet()

        var currentDisabled = disabledFamilies
        var currentFocusAreas = focusAreas
        var isAllValid = true
        val explanations = mutableMapOf<String, String>()

        rules.forEach { rule ->
            val res = rule.evaluate(disabledFamilies, flexibilityType, focusAreas)
            explanations.putAll(res.disabledOptionsExplanation)
        }

        rules.forEach { rule ->
            val res = rule.evaluate(currentDisabled, flexibilityType, currentFocusAreas)
            if (!res.isValid) {
                isAllValid = false
            }
            currentDisabled = res.adjustedDisabledFamilies
            currentFocusAreas = res.adjustedFocusAreas
        }

        return ConstraintResult(
            isValid = isAllValid,
            adjustedDisabledFamilies = currentDisabled.filter { it in validCategoryKeys }.toSet(),
            adjustedFocusAreas = currentFocusAreas,
            disabledOptionsExplanation = explanations.filterKeys { it in validCategoryKeys }
        )
    }
}
