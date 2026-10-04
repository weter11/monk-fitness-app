package com.monkfitness.app.domain.usecase

import com.monkfitness.app.domain.adaptive.integration.ExerciseFamilyClassification

/**
 * P30's **production exercise→family classification**: the app's own shipped catalogue, read for the
 * one fact it already states per exercise.
 *
 * ### Why this is a read of an existing fact, not a new classification
 *
 * §9's family membership was reported missing in production because the target tree stores no
 * exercise→family map. That was true of the **target schema** and false of the **app**: every one of
 * the catalogue's 66 exercises states its own family as a constructor argument, and those family ids
 * are exactly the 28 ids the catalogue's own `families` list declares — the same values
 * [com.monkfitness.app.data.model.Exercise.toConfigurationMetadata] already hands the generation path
 * as `ExerciseMetadata.familyId`, and therefore the same values the engine's opaque `familyId` names.
 *
 * The audit that establishes this is recorded in `docs/PROGRAM_ADAPTIVE_FAMILY_CLASSIFICATION.md` §2.
 * Its three findings are what make wiring this an honest closure rather than a new opinion:
 *
 * 1. **The identity is identical, not similar.** `Exercise.familyId` is the family the app already
 *    groups exercises by in its own library UI (`MainViewModel`'s family sections read it), the family
 *    `AdaptiveTarget.Family` names, and the family a `ProgramProgressionRelation` is keyed by. There is
 *    no second family vocabulary in the tree to reconcile with.
 * 2. **It is a stored field, never derived.** The value is passed positionally into
 *    `baseRepExercise`/`baseTimerExercise` at each catalogue entry; nothing computes it from the id,
 *    the category, the subcategory, the training-style map or a name. This class reads the field and
 *    stops there — see *What it deliberately does not do*.
 * 3. **It is a compile-time fact.** There is nothing to persist, migrate, cache or invalidate, which is
 *    why this is a `val` built once at construction rather than a repository (§23 gains no table).
 *
 * ### What it deliberately does not do
 *
 * The rules that keep this a *closure* rather than a *new classification source* are architectural, and
 * the architecture suite pins each one mechanically:
 *
 * - **No inference of any kind.** Nothing here reads `category`, `subCategory`, `requiredEquipment`,
 *   `animationId` or `exerciseToFamiliesMap`. The four inference-shaped mutations in the RED suite each
 *   compile, each are plausible, and each are caught — because a family that was guessed is a family
 *   this app never stated.
 * - **No fallback family.** An exercise this classification does not know is answered `null`, which the
 *   adaptive layer reads as *not classified* (§9) rather than as a default bucket. Hard-coding one would
 *   convert the honest `NO_FAMILY_CLASSIFICATION` gap into a fabricated ladder question.
 * - **No persistence, no DAO, no repository, no clock, no id source.** The catalogue is a `private val`
 *   list built at class-initialisation; the classification is a projection of it and holds no other
 *   collaborator. There is nothing to write and nothing to expire.
 * - **No generation-domain dependency.** The direction is the app boundary → pure domain, so this class
 *   implements the domain's port and never imports `domain.program.generated`.
 *
 * ### Where it is read, and what it is not for
 *
 * [com.monkfitness.app.domain.usecase.ProgramAdaptiveIntegration] asks it for the family of an
 * exercise id during a pass, and the composition root constructs it — the single construction site.
 * This is a **read-only projection**: it exposes no `Exercise`, no name, no equipment and no animation
 * id, exactly like [ProgramExerciseLibrary] beside it, so there is nothing here for an export to copy
 * and nothing here to write back into a plan.
 *
 * **It is not a progression ladder.** Supplying this closes one of the two facts §30 step 12 recorded
 * as missing; the ladder is still undeclared, so every production pass still ends in
 * [com.monkfitness.app.domain.adaptive.integration.AdaptiveInputGap.NO_DECLARED_PROGRESSION_RELATION]
 * and no family is adapted. No ladder, default or otherwise, is supplied here, and the Stage-1
 * `PilotProgressionProfiles` are not consulted (§30 step 11).
 */
class CatalogExerciseFamilyClassification : ExerciseFamilyClassification {

    /**
     * The catalogue's own exercise id → family id projection, taken once at construction.
     *
     * `getExerciseLibrary()` with **no** equipment filter is the catalogue as the app knows it, the same
     * read [ProgramExerciseLibrary] takes: a family is a property of the exercise's definition, so
     * whether the user happens to own a bar cannot decide whether the exercise belongs to `pullups`.
     * With an empty filter that accessor returns every entry (`isAccessibleWith` holds for an empty
     * available set), so this is the whole catalogue and no exercise is silently unclassified because
     * of equipment.
     *
     * `associate` over a list with distinct ids is order-independent, which is what makes the answer
     * deterministic: the same id maps to the same family on every read, and a later catalogue revision
     * changes a family's membership only by editing the catalogue entry that states it.
     */
    private val familyByExerciseId: Map<String, String> =
        WorkoutGenerator().getExerciseLibrary()
            .associate { exercise -> exercise.id to exercise.familyId }

    /**
     * The family [exerciseId] belongs to, or `null` when this catalogue does not hold it.
     *
     * `null` is the port's own stated answer for *"not classified"* and is deliberately **not** a
     * fallback: an id the app does not ship has no family here, and the adaptive layer reports that as
     * its own gap rather than being handed an invented bucket.
     */
    override fun familyOf(exerciseId: String): String? = familyByExerciseId[exerciseId]
}