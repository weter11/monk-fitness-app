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
 * The **production generation boundary** — the one place where the app's own exercise catalogue is
 * stated to the pure Generated Planner as `GenerationCandidate`s.
 *
 * ```text
 * production exercise catalogue            ← the shipped `Exercise` list
 *          ↓
 * ProductionGenerationBoundary              ← this file: adapter + application layer
 *          ↓
 * GenerationCandidate<Equipment>           ← the generated-domain's own value
 *          ↓
 * GeneratedPlanner                          ← untouched, and untouched by anything below this line
 * ```
 *
 * ### Why this lives here and not in the generated domain
 *
 * The generated planner is pure by construction (§30 step 10, §25): its package may not import
 * `android`, `androidx`, the data layer, the UI or a legacy engine, and
 * `ProgramGeneratedArchitectureTest` enforces that by name. The production catalogue is an
 * Android-flavoured data model — `@StringRes` labels, drawables, an equipment enum — held in a
 * `private val` inside the legacy `WorkoutGenerator`. So the two cannot meet anywhere but in the
 * application layer, and this is that meeting point: it reads the catalogue and it **names**
 * `WorkoutGenerator`, while nothing in `domain/program/generated` names either. The generated planner
 * never learns that a catalogue, a legacy engine or an Android resource exists; it is handed finished
 * candidates.
 *
 * The legacy `WorkoutGenerator` is read **here and only here**, as the app's single current source of
 * exercise truth — exactly the justification `ProgramExerciseLibrary` records for its own read of the
 * same list. Removing the legacy engine is a later stage's removal; this stage only declines to let
 * the generator depend on it.
 *
 * ### Where each of the four facts comes from
 *
 * | fact the planner needs | production source of truth |
 * | --- | --- |
 * | exercise identity, family, training domain, body region, required equipment | `Exercise.toConfigurationMetadata()` — the app's own derived metadata, over the exercise's own fields. Nothing here is restated: the id, the family, the category, the sub-category and the equipment set are the catalogue's, read through the mapper that already exists. |
 * | `PrescriptionDimension` | `Exercise.isTimerBased` (see [DimensionOf]). A timed exercise is prescribed in seconds and every other one in repetitions — the rule the plan editor already applies when a user adds an exercise by hand, so generation and hand-authoring cannot disagree about an exercise's dimension. |
 * | focus membership | **not held anywhere in production** — supplied through [GenerationFocusSource], and an exercise the source does not state is reported, never defaulted. See below. |
 * | the user's available equipment | the caller's own `Set<Equipment>`, forwarded verbatim. See "Equipment is forwarded, never filtered" below. |
 *
 * ### Focus membership is a boundary gap, and it stays visible
 *
 * The catalogue's own groupings are **not** the focus vocabulary, and this file refuses to pretend
 * otherwise. It holds a category (`STRENGTH` / `MOBILITY` / `STRETCHING` / `POSTURE`), a sub-category
 * (a body region), and a training-style map (`exerciseToFamiliesMap`, whose values are
 * `ExerciseCategoryFilter`s such as `CALISTHENICS`, `NECK` or `LOWER_BACK`). None of those three is a
 * [com.monkfitness.app.domain.program.Focus] (`PUSH` / `PULL` / `LEGS` / `CORE` / `MOBILITY` /
 * `POSTURE` / `CONDITIONING`), and mapping one onto the other would be inventing a training fact the
 * data does not contain — the same gap `ProgramsController.generateDraft` records as
 * `GENERATION_UNAVAILABLE`, and the same `NoExerciseFamilyClassification` the adaptive side recorded.
 *
 * So membership is an **explicit input**, and an exercise the source does not classify is named in
 * [ProductionGenerationCatalogue.unclassifiedExerciseIds] instead of being quietly admitted. Three
 * things this file deliberately does *not* do:
 *
 *  * it never substitutes the whole focus vocabulary for a missing answer (an exercise that trains
 *    everything is not a candidate for anything);
 *  * it never substitutes an empty set — `GenerationCandidate` refuses that at construction, and that
 *    refusal is the right one, as its own KDoc states;
 *  * it never derives membership from the category, the sub-category or the training-style map. The
 *    architecture suite bans those three identifiers from this file by name and pins the catalogue's
 *    real groupings in the test that reads them, so a future "obviously this is a push exercise"
 *    refactor is a failing test rather than a silent change in what the generator trains.
 *
 * The consequence is honest rather than convenient: with no classification source supplied, the
 * catalogue classifies nothing and the boundary yields **no** candidates and **all** exercises
 * unclassified. That is the state P24 inherits, and it is the correct one — it says the app states no
 * focus classification yet, rather than presenting a plan built from a guess.
 *
 * ### Equipment is forwarded, never filtered
 *
 * The available equipment is passed into [generationRequest] exactly as the caller states it, and
 * this file applies **no** normalisation to it. That is a deliberate refusal to inherit the legacy
 * `isAccessibleWith` reading, in which an empty set means *"no equipment was configured, so constrain
 * nothing"*. Generation's reading is the strict one: an empty available set means the user has
 * nothing, so only exercises requiring nothing are selectable. Reusing the legacy rule here would
 * silently offer bar-and-band exercises to a user who has declared no equipment — the exact hidden
 * default this boundary exists to prevent. The catalogue's own required-equipment sets are already
 * normalised at construction (`Equipment.NONE` is dropped by the generator's factories), so no
 * candidate ever *requires* `NONE`; the test asserts that rather than filtering for it here.
 *
 * ### It holds no state and decides nothing
 *
 * Every function here is a function of its arguments: the same catalogue and the same stated facts
 * produce the same candidates in the same order, on any device, in any process. No clock, no random
 * source, no repository, no DAO, no persistence, no scheduling, no adaptive policy, and no business
 * rule of its own — selection, allocation, reconciliation and the equipment constraint are all the
 * generated planner's, and none of them is restated or second-guessed here.
 */
object ProductionGenerationBoundary {

    /**
     * The catalogue's reading as candidates, plus what it could not state.
     *
     * The two halves are separate fields rather than one filtered list, because an exercise that was
     * never classified is a **fact about the catalogue** and not a failure: the caller can report it,
     * record it as a declared gap, or supply a classification and try again. Collapsing the two into a
     * single list would make "nothing was classified" and "some things were" indistinguishable.
     *
     * @property candidates the exercises this catalogue can state completely, in the catalogue's own
     *   order. Empty exactly when every exercise was unclassified.
     * @property unclassifiedExerciseIds the exercises the focus source did not state, in the
     *   catalogue's own order. Never defaulted and never inferred.
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
     * Reads the **shipped** catalogue — the app's own list, with no equipment filter, exactly as
     * `ProgramExerciseLibrary` reads it for membership.
     *
     * No filter, because a candidate list is a *statement about the library*, not a selection: whether
     * an exercise is usable for this user is `GenerationRequest`'s question, and it answers it by
     * *reporting* an unusable candidate rather than by removing it (§9's "hard execution constraints
     * must never be silently violated"). Filtering here would hide the very fact the planner is
     * supposed to report.
     */
    fun catalogueOfShippedExercises(
        focusSource: GenerationFocusSource
    ): ProductionGenerationCatalogue =
        catalogueOf(WorkoutGenerator().getExerciseLibrary(), focusSource)

    /**
     * Reads [exercises] as candidates — the boundary itself, with the catalogue as an argument so the
     * mapping is a function of the exercises and the stated facts alone.
     *
     * Order is the catalogue's own: the generated planner ranks candidates itself (§9's total
     * deterministic ranking, ending in the canonical id), so re-ordering here could only invent a
     * preference the caller never stated.
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
     * The generation request this catalogue supports: §30 step 10's pure input, assembled from the
     * production catalogue and the caller's configuration.
     *
     * Every field is a value the caller already holds and every one is forwarded unchanged — the focus
     * configuration, the weekly rhythm, the Program's duration, the plain signals, the policy and above
     * all the user's available equipment. Nothing is defaulted, filtered, sorted or substituted here;
     * the only decision this function makes is whether a request can be stated at all.
     *
     * A catalogue that classified nothing is **refused**, not assembled into an empty request. An
     * empty candidate list would be a plan of nothing reading as a success (§33), and the honest answer
     * is a refusal that names the gap — so the caller sees "the app states no focus classification"
     * rather than "your program has no exercises".
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
     * This one exercise as the planner's own candidate.
     *
     * A field-for-field conversion of facts the catalogue already holds: its own metadata through
     * [toConfigurationMetadata], the focuses the caller stated, and its own prescription dimension.
     * The only branch is the one the catalogue's own data makes — a timed exercise is prescribed in
     * seconds, every other one in repetitions — and no field is invented, defaulted or widened.
     */
    private fun Exercise.asCandidate(focuses: Set<com.monkfitness.app.domain.program.Focus>):
        GenerationCandidate<Equipment> = GenerationCandidate(
        metadata = toConfigurationMetadata(),
        focuses = focuses,
        dimension = DimensionOf(isTimerBased)
    )
}