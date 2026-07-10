package com.monkfitness.app.animation

data class PoseContext(
    val state: AnimationState,
    val definition: SkeletonDefinition,
    val cycleDuration: Float = 3000f
) {
    val progress: Float get() = state.progress
    val side: Side get() = state.side
    val deltaTime: Float get() = state.deltaTime
    val playbackSpeed: Float get() = state.playbackSpeed
    val mirrored: Boolean get() = state.mirrored
    val phase: Float get() = state.phase
    val loopIndex: Int get() = state.loopIndex

    constructor(
        progress: Float,
        side: Side,
        definition: SkeletonDefinition,
        deltaTime: Float = 0f,
        cycleDuration: Float = 3000f,
        playbackSpeed: Float = 1.0f,
        mirrored: Boolean = false,
        phase: Float = 0f,
        loopIndex: Int = 0
    ) : this(
        state = AnimationState(
            progress = progress,
            side = side,
            phase = phase,
            playbackSpeed = playbackSpeed,
            loopIndex = loopIndex,
            deltaTime = deltaTime,
            mirrored = mirrored
        ),
        definition = definition,
        cycleDuration = cycleDuration
    )
}
