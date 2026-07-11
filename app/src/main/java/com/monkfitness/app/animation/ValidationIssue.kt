package com.monkfitness.app.animation

/**
 * Represents a single issue identified during skeleton pose validation.
 */
data class ValidationIssue(
    val ruleId: String,
    val message: String,
    val severity: ValidationSeverity,
    val joint: Joint? = null
)
