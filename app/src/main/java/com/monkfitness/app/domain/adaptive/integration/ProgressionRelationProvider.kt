package com.monkfitness.app.domain.adaptive.integration

import com.monkfitness.app.domain.adaptive.engine.ProgramProgressionRelation

/**
 * The caller's **exercise → family** classification, as the adaptive stage's integration sees it.
 *
 * The domain owns no exercise catalogue (§9, §25), so an exercise's family is a fact the caller states:
 * this is the same shape the pre-existing signal layer takes as a parameter
 * ([com.monkfitness.app.domain.adaptive.engine.ProgramAdaptiveSignalCalculator]) and the same one §30
 * step 10's generator states through `ExerciseMetadata`. It is a **port** rather than a value because
 * the classification is the app's, not the Program System's: a library, a stored table or a test may
 * answer it, and nothing in this package may reach for one of them itself.
 *
 * An exercise the classification does not know is answered `null` — *"not classified"* — and the layer
 * that reads it applies the signal layer's own conservative convention (an unknown exercise is its own
 * family, compared with itself and nothing else). Answering `null` is therefore not an error and not a
 * fallback to a default family: it is the absence of a fact about that exercise.
 */
fun interface ExerciseFamilyClassification {

    /** The family [exerciseId] belongs to, or `null` when this classification does not know it. */
    fun familyOf(exerciseId: String): String?
}

/**
 * The **progression ladder** of one family: the source of every `ProgramProgressionRelation` the
 * adaptive engine resolves against (§15).
 *
 * §30 step 11 deliberately owns no ladder — it takes the relation as a value and refuses to invent one
 * — and §30 step 12 must therefore say where that value comes from. It comes from here, and this type
 * is deliberately the *whole* answer: a family's relation, or an explicit `null` for *"this ladder is
 * not declared"*. There is no default family, no built-in catalogue and no fallback — a caller that
 * has no ladders for a family says so by returning `null`, and the integration reports that gap rather
 * than fabricating a progression.
 *
 * ### Why this is empty in the composition root, and what that costs
 *
 * The target tree stores **no** family/ladder catalogue: §23's entity set has no progression ladder,
 * and the only ladders the repository has ever carried are the Stage-1 pilot's
 * (`PilotProgressionProfiles`), which §30 step 11 forbids this generation to reach for and which are
 * scoped to the legacy program's own axis. So production wires [NoDeclaredProgression] — an honest
 * *"no family's ladder is declared"* — and the integration's answer for every family is the engine's
 * bounded non-progressing `PROGRESSION_UNAVAILABLE` hold rather than a fabricated ladder. Two facts
 * have to arrive before the adaptive stage can change anything in production, and both are recorded in
 * `docs/PROGRAM_ADAPTIVE_INTEGRATION.md`:
 *
 * ```text
 * 1. a persisted family ladder (which variants exist, at which positions, prescribing what);
 * 2. a persisted set of the exercises the user's own configuration enables (§9's `allowed exerciseIds`).
 * ```
 *
 * Neither is invented here. The port exists so that supplying them is a wiring change with one home,
 * and so that no global catalogue can hide behind a default.
 */
fun interface ProgressionRelationProvider {

    /** The declared ladder of [familyId], or `null` when this provider declares none for it. */
    fun relationOf(familyId: String): ProgramProgressionRelation?
}

/**
 * The classification of an app that has not authored one yet: no exercise is classified, so every
 * exercise is its own family in the signal layer's conservative reading.
 *
 * It is a named value rather than an inline lambda because the *absence* of a classification is a fact
 * worth stating once: a reader of the composition root sees that the app has no exercise→family
 * catalogue on this path today, and a future one replaces one object instead of hunting for the place
 * that invented a default.
 */
val NoExerciseFamilyClassification: ExerciseFamilyClassification =
    ExerciseFamilyClassification { null }

/**
 * The ladder source of an app that declares none: every family is answered *"not declared"*, and the
 * engine's answer is its own bounded hold (§15's `PROGRESSION_UNAVAILABLE`).
 *
 * See [ProgressionRelationProvider] for why this is what production wires, and what has to exist before
 * it can be replaced.
 */
val NoDeclaredProgression: ProgressionRelationProvider = ProgressionRelationProvider { null }
