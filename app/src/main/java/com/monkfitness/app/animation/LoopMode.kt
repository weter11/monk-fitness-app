package com.monkfitness.app.animation

enum class LoopMode {
    LOOP,      // Repeats progress 0 -> 1 -> 0 (simple repeat)
    HOLD,      // Progress driven by breathing cycle or fixed at 1.0
    PING_PONG, // Progress 0 -> 1 then 1 -> 0
    ONCE       // Progress 0 -> 1 then stays at 1
}
