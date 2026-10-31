package com.monkfitness.app.domain.usecase

import com.monkfitness.app.data.model.Equipment
import com.monkfitness.app.data.model.Exercise
import com.monkfitness.app.data.model.toConfigurationMetadata
import com.monkfitness.app.domain.program.FocusPlan
import com.monkfitness.app.domain.program.ProgramDuration
import com.monkfitness.app.domain.program.ProgramSchedule
import com.monkfitness.app.domain.program.generated.GenerationCandidate
import com.monkfitness.app.domain.program.generated.GenerationPolicy
import com.monkfitness.app.domain.program.generated.GenerationPreferences
import com.monkfitness.app.domain.program.generated.GenerationRequest
import com.monkfitness.app.domain.usecase.ExerciseGenerationFacts.DimensionOf
import com.monkfitness.app.domain.usecase.ExerciseGenerationFacts.GenerationFocusSource

/**
 * The production generation boundary: the one place where the app's own catalogue is stated to the pure
 * Generated Planner as `GenerationCandidate`s.
 *
 * It lives here because the two cannot meet anywhere else: the generated package may not import
 * `android`, `androidx`, the data layer or the legacy engine (`ProgramGeneratedArchitectureTest` enforces
 * that by name), while the catalogue is an Android-flavoured `private val` inside `WorkoutGenerator`. So
 * this file names `WorkoutGenerator` and nothing in `domain/program/generated` does; the planner is handed
 * finished candidates and never learns a catalogue or a resource exists.
 *
 * Focus membership is a boundary gap and stays visible. The catalogue's category, sub-category and
 * `exerciseToFamiliesMap` are not [com.monkfitness.app.domain.program.Focus], and mapping one onto the
 * other would invent a training fact the data does not contain — so membership is an explicit input
 * ([GenerationFocusSource]), and an exercise the source does not state is named in
 * [ProductionGenerationCatalogue.unclassifiedExerciseIds] instead of being quietly admitted. This file
 * never substitutes the whole vocabulary (an exercise that trains everything is a candidate for nothing),
 * never substitutes an empty set (`GenerationCandidate` refuses it at construction), and never derives
 * membership from those three identifiers — the architecture suite bans them by name.
 *
 * With no source supplied the catalogue classifies nothing and yields **no** candidates and **all**
 * exercises unclassified; that is the honest answer, not a plan built on a guess.
 *
 * No clock, no random source, no repository, no policy of its own: every function here is a function of
 * its arguments, so the same catalogue and stated facts give the same candidates in the same order.
 */
object ProductionGenerationBoundary {

    /**
     * Two fields rather than one filtered list: an exercise that was never classified is a **fact about
     * the catalogue**, not a failure, so the caller can report it or supply a classification and retry.
     * Collapsing them would make "nothing was classified" and "some things were" indistinguishable.
     *
     * Both lists keep the catalogue's own order.
     */
    data class ProductionGenerationCatalogue(
        val candidates: List<GenerationCandidate<Equipment>>,
        val unclassifiedExerciseIds: List<String>
    ) {

        init {
            require(candidates.map { it.exerciseId }.toSet().size == candidates.size) {
                "the library view names each exercise once, found " +
                    candidates.groupingBy { it.exerciseId }.eachCount().filterValues { it > 1 }.keys
            }
            require(
                candidates.map { it.exerciseId }.toSet()
                    .intersect(unclassifiedExerciseIds.toSet()).isEmpty()
            ) {
                "an exercise is either classified or unclassified, never both"
            }
        }

        /** The candidate for [exerciseId], or `null` when the catalogue states none. */
        fun candidateFor(exerciseId: String): GenerationCandidate<Equipment>? =
            candidates.firstOrNull { it.exerciseId == exerciseId }

        /** Whether [exerciseId] is in the catalogue at all — classified or not. */
        fun contains(exerciseId: String): Boolean =
            candidates.any { it.exerciseId == exerciseId } || exerciseId in unclassifiedExerciseIds
    }

    /**
     * Read with no equipment filter, as `ProgramExerciseLibrary` reads it: a candidate list is a
     * *statement about the library*, not a selection. Whether an exercise is usable for this user is
     * `GenerationRequest`'s question and it answers by *reporting* an unusable candidate; filtering here
     * would hide the fact the planner is supposed to report.
     */
    fun catalogueOfShippedExercises(
        focusSource: GenerationFocusSource
    ): ProductionGenerationCatalogue =
        catalogueOf(WorkoutGenerator().getExerciseLibrary(), focusSource)

    /**
     * Order is the catalogue's own: the planner ranks candidates itself with a total deterministic
     * order ending in the canonical id, so re-ordering here could only invent a preference the caller
     * never stated.
     */
    fun catalogueOf(
        exercises: List<Exercise>,
        focusSource: GenerationFocusSource
    ): ProductionGenerationCatalogue {
        val candidates = mutableListOf<GenerationCandidate<Equipment>>()
        val unclassified = mutableListOf<String>()
        exercises.forEach { exercise ->
            when (val focuses = focusSource.focusesOf(exercise.id)) {
                null -> unclassified += exercise.id
                else -> candidates += exercise.asCandidate(focuses)
            }
        }
        return ProductionGenerationCatalogue(candidates, unclassified)
    }

    /**
     * The generation request this catalogue supports: every field forwarded unchanged from the caller,
     * with nothing defaulted, filtered, sorted or substituted — the only decision here is whether a
     * request can be stated at all.
     *
     * A catalogue that classified nothing is **refused**, not assembled into an empty request: an empty
     * candidate list would be a plan of nothing reading as a success, so the caller sees "the app states
     * no focus classification" rather than "your program has no exercises".
     *
     * `availableEquipment` is passed through exactly as stated, with no normalisation. That deliberately
     * declines the legacy `isAccessibleWith` reading in which an empty set means "nothing configured, so
     * constrain nothing": here an empty set means the user has nothing, so only exercises requiring
     * nothing are selectable. Inheriting the legacy rule would silently offer bar-and-band exercises to a
     * user who declared no equipment.
     */
    fun generationRequest(
        catalogue: ProductionGenerationCatalogue,
        focus: FocusPlan,
        schedule: ProgramSchedule,
        duration: ProgramDuration,
        availableEquipment: Set<Equipment>,
        preferences: GenerationPreferences = GenerationPreferences.NONE,
        policy: GenerationPolicy = GenerationPolicy.DEFAULT
    ): GenerationRequest<Equipment> {
        require(catalogue.candidates.isNotEmpty()) {
            "no exercise in the catalogue states which focuses it trains, so there is nothing to " +
                "generate from: ${catalogue.unclassifiedExerciseIds.size} exercise(s) are " +
                "unclassified. Generation classifies nothing rather than guessing (§6, §8)"
        }
        return GenerationRequest(
            focus = focus,
            schedule = schedule,
            duration = duration,
            candidates = catalogue.candidates,
            availableEquipment = availableEquipment,
            preferences = preferences,
            policy = policy
        )
    }

    /**
     * A field-for-field conversion of facts the catalogue already holds. The only branch is the one the
     * data makes — timed in seconds, everything else in repetitions — and no field is invented or widened.
     */
    private fun Exercise.asCandidate(focuses: Set<com.monkfitness.app.domain.program.Focus>):
        GenerationCandidate<Equipment> = GenerationCandidate(
        metadata = toConfigurationMetadata(),
        focuses = focuses,
        dimension = DimensionOf(isTimerBased)
    )
}