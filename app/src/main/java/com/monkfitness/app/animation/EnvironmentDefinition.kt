package com.monkfitness.app.animation

data class EnvironmentDefinition(
    val groundVisible: Boolean = true,
    val props: List<EnvironmentProp> = emptyList()
)

sealed interface EnvironmentProp

data class BoxProp(
    val center: Vector3,
    val width: Float,
    val height: Float,
    val depth: Float
) : EnvironmentProp

data class StepProp(
    val center: Vector3,
    val width: Float,
    val height: Float,
    val depth: Float
) : EnvironmentProp

data class BenchProp(
    val center: Vector3,
    val width: Float,
    val height: Float,
    val depth: Float
) : EnvironmentProp

data class WallProp(
    val center: Vector3,
    val width: Float,
    val height: Float,
    val depth: Float
) : EnvironmentProp
