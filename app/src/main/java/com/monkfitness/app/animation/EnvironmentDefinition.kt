package com.monkfitness.app.animation

data class GroundDefinition(
    val visible: Boolean = true,
    val level: Float = 0f
)

data class EnvironmentDefinition(
    val ground: GroundDefinition = GroundDefinition(),
    val props: List<EnvironmentProp> = emptyList(),
    val anchors: List<EnvironmentAnchor> = emptyList()
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
