package com.monkfitness.app.validation

import androidx.annotation.StringRes
import com.monkfitness.app.R
import com.monkfitness.app.animation.PoseBuilder

/**
 * A single static reference pose used purely for engine validation.
 *
 * A [ValidationPose] is NOT an [com.monkfitness.app.data.model.Exercise]. It is a
 * frozen snapshot of the skeleton produced by a [PoseBuilder] and rendered through the
 * shared animation pipeline. It never participates in workouts, statistics, progression,
 * recommendations or any training logic.
 *
 * @param id Stable identifier, also used as the navigation argument.
 * @param nameRes Localized display name.
 * @param descriptionRes Localized short description shown in the viewer.
 * @param techniqueRes Localized note describing what the pose validates.
 * @param builder The [PoseBuilder] that produces the static snapshot.
 * @param alternating Whether the underlying builder alternates sides (always false here).
 * @param purposeRes Localized bullet points describing the engine aspects validated.
 */
data class ValidationPose(
    val id: String,
    @StringRes val nameRes: Int,
    @StringRes val descriptionRes: Int,
    @StringRes val techniqueRes: Int,
    val builder: PoseBuilder,
    val alternating: Boolean = false,
    val purposeRes: List<Int> = emptyList()
)
