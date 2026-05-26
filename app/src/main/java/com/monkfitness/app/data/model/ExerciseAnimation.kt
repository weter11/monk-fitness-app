package com.monkfitness.app.data.model

import com.monkfitness.app.ui.components.animation.SkeletonAnimation
import com.monkfitness.app.ui.components.animation.exerciseSkeletonAnimation

val Exercise.skeletonAnimation: SkeletonAnimation?
    get() = exerciseSkeletonAnimation(id)

fun Exercise.hasAnimatedVariant(): Boolean = skeletonAnimation != null
