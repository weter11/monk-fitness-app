package com.monkfitness.app.animation

data class ValidationReport(
    val isValid: Boolean,
    val results: List<ValidationResult>,
    val allIssues: List<ValidationIssue>
)
