package com.monkfitness.app.animation

data class ValidationResult(
    val ruleId: String,
    val isValid: Boolean,
    val issues: List<ValidationIssue>
)
