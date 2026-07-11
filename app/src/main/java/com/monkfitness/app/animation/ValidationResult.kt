package com.monkfitness.app.animation

/**
 * Encapsulates the validation outcome of a specific validation rule.
 */
data class ValidationResult(
    val ruleId: String,
    val isValid: Boolean,
    val issues: List<ValidationIssue>
)
