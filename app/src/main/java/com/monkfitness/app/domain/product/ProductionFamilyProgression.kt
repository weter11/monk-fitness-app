package com.monkfitness.app.domain.product

import com.monkfitness.app.domain.adaptive.engine.FamilyProgression

/**
 * Every family the app ships, classified for the adaptive stage: the four with an authored ladder in
 * [ProductionProgressionRelationDefinitions] climb variants, and every other family advances inside each
 * exercise's own range.
 *
 * This is DATA only and it is exhaustive on purpose: the engine treats a family it cannot classify as a
 * reportable gap, so a family added to the catalogue without a line here is a compile-time test failure
 * rather than a family that is silently never adapted.
 */
object ProductionFamilyProgression {

    val classifications: Map<String, FamilyProgression> = mapOf(
        "pushups" to FamilyProgression.VARIANT_LADDER,
        "squats" to FamilyProgression.VARIANT_LADDER,
        "pullups" to FamilyProgression.VARIANT_LADDER,
        "lunges" to FamilyProgression.VARIANT_LADDER,

        "plank" to FamilyProgression.WITHIN_EXERCISE,
        "rows" to FamilyProgression.WITHIN_EXERCISE,
        "glute_bridge" to FamilyProgression.WITHIN_EXERCISE,
        "pelvic_control" to FamilyProgression.WITHIN_EXERCISE,
        "hip_flexor" to FamilyProgression.WITHIN_EXERCISE,
        "cat_cow" to FamilyProgression.WITHIN_EXERCISE,
        "cobra" to FamilyProgression.WITHIN_EXERCISE,
        "birddog" to FamilyProgression.WITHIN_EXERCISE,
        "thoracic" to FamilyProgression.WITHIN_EXERCISE,
        "hamstring" to FamilyProgression.WITHIN_EXERCISE,
        "burpees" to FamilyProgression.WITHIN_EXERCISE,
        "face_pull" to FamilyProgression.WITHIN_EXERCISE,
        "reverse_snow_angels" to FamilyProgression.WITHIN_EXERCISE,
        "shoulder_mobility" to FamilyProgression.WITHIN_EXERCISE,
        "hip_mobility" to FamilyProgression.WITHIN_EXERCISE,
        "ankle_mobility" to FamilyProgression.WITHIN_EXERCISE,
        "dips" to FamilyProgression.WITHIN_EXERCISE,
        "pike_pushups" to FamilyProgression.WITHIN_EXERCISE,
        "wall_sit" to FamilyProgression.WITHIN_EXERCISE,
        "neck_mobility" to FamilyProgression.WITHIN_EXERCISE,
        "jumping_jacks" to FamilyProgression.WITHIN_EXERCISE,
        "horse_stance" to FamilyProgression.WITHIN_EXERCISE,
        "superman" to FamilyProgression.WITHIN_EXERCISE,
        "child_pose" to FamilyProgression.WITHIN_EXERCISE
    )

    /** `null` is *"this app does not classify that family"*, which the engine reports. */
    fun of(familyId: String): FamilyProgression? = classifications[familyId]

    /** The families whose ladder is the source of their progression. */
    val ladderFamilies: Set<String> =
        classifications.filterValues { progression -> progression == FamilyProgression.VARIANT_LADDER }.keys
}
