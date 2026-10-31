package com.monkfitness.app.animation

data class ValidationIssue(
    val ruleId: String,
    val message: String,
    val severity: ValidationSeverity,
    val joint: Joint? = null
)
