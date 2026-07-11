package com.monkfitness.app.animation

/**
 * Aggregates all ValidationResults and ValidationIssues across an entire skeleton pose validation check.
 */
data class ValidationReport(
    val isValid: Boolean,
    val results: List<ValidationResult>,
    val allIssues: List<ValidationIssue>
)
