package com.monkfitness.app.domain.adaptive.integration

import com.monkfitness.app.domain.adaptive.engine.ProgramProgressionRelation

/**
 * The caller's **exercise → family** classification. The domain owns no exercise catalogue, so an
 * exercise's family is a fact the caller states — a port rather than a value, because the classification
 * is the app's and nothing in this package may reach for a catalogue itself.
 *
 * `null` means "not classified": not an error and not a fallback to a default family, but the absence of
 * a fact about that exercise.
 */
fun interface ExerciseFamilyClassification {

    fun familyOf(exerciseId: String): String?
}

/**
 * The **progression ladder** of one family: the source of every relation the engine resolves against.
 *
 * It is deliberately the *whole* answer — a relation, or an explicit `null` for "this ladder is not
 * declared" — with no default family and no fallback. The tree stores no ladder catalogue, so production
 * wires [NoDeclaredProgression] and every family gets the engine's bounded non-progressing hold rather
 * than a fabricated ladder.
 */
fun interface ProgressionRelationProvider {

    /**
     * ### Why this is `suspend`
     *
     * The answer comes from a **persisted** catalogue, so the read is a suspending database access, and a
     * port that cannot express that forces every implementation to lie about what it does. This is the only
     * domain port here whose production source is storage; the classification beside it is an in-memory
     * read and stays synchronous.
     */
    suspend fun relationOf(familyId: String): ProgramProgressionRelation?
}

/**
 * The classification of an app that has authored none: no exercise is classified, so every exercise is its
 * own family in the signal layer's conservative reading.
 *
 * A named value rather than an inline lambda, because the *absence* of a classification is a fact worth
 * stating once: replacing it is one object instead of a hunt for the place that invented a default.
 */
val NoExerciseFamilyClassification: ExerciseFamilyClassification =
    ExerciseFamilyClassification { null }

/**
 * The ladder source of an app that declares none: every family is answered *"not declared"*, so the
 * engine gives its own bounded hold. See [ProgressionRelationProvider] for why production wires this.
 */
val NoDeclaredProgression: ProgressionRelationProvider = ProgressionRelationProvider { null }
