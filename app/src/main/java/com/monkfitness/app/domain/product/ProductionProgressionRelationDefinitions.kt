package com.monkfitness.app.domain.product

import com.monkfitness.app.domain.adaptive.engine.ProgramProgressionRelation
import com.monkfitness.app.domain.adaptive.engine.ProgramProgressionVariant
import com.monkfitness.app.domain.prescription.RepPrescription
import com.monkfitness.app.domain.prescription.TimePrescription

/**
 * P32's **one production source of progression-relation content**: the four families this app ships a
 * ladder for, and the exact rungs each declares.
 *
 * ```text
 * ProductionProgressionRelationDefinitions
 *         ↓  BuiltInProgressionCatalogueBootstrap
 * progression_relation_variant
 *         ↓  ProgressionRelationRepository
 * StoredProgressionRelationProvider
 * ```
 *
 * ### This is DATA, and the whole claim of the file is that it is only that
 *
 * Every level, every exercise id and every per-set target below is **written out**. There is no
 * `when (familyId)`, no branch over categories or subcategories, no lookup into the shipped exercise
 * catalogue's ordering, no `Exercise` metadata read, no difficulty coefficient, no plan phase, no
 * `FamilyProgressionState`, no adaptive history and no legacy pilot source anywhere in this file — and
 * the architecture gates read this file's text to prove it. A ladder is a **definition somebody
 * authored**, so the file that holds the definitions holds decisions, and every value a rung carries is
 * a decision somebody made rather than a number this code derived.
 *
 * The prescriptions are the domain's own [RepPrescription] and [TimePrescription]. There is no second
 * prescription model here and no flattened scalar: `RepPrescription(listOf(8, 8, 8))` is three sets of
 * eight, which is a different prescription from one set of twenty-four and cannot be recovered from a
 * total.
 *
 * ### Why each relation is *four* or *five* rungs, and not five every time
 *
 * The domain's audited invariant — stated in `ProgramProgressionRelation` and enforced by its
 * constructor — is that **one exercise id is one position in one family's hierarchy**. So a rung is
 * identified by its exercise, and a family can only declare as many rungs as it has distinct exercises
 * to declare. That is why `pushups`, `squats` and `lunges` ship four rungs (`-1..+2`) and `pullups`
 * ships five (`-2..+2`): `pullups` is the only one of the four with five distinct catalogue exercises.
 *
 * Contiguity still holds — each relation's own lowest and highest level are its floor and its ceiling,
 * and the domain checks exactly that. Nothing here requires `-2..+2`, and manufacturing a fifth rung
 * for a family that has only four exercises would mean inventing an exercise or relaxing the identity
 * rule, and this stage does neither. See `docs/PROGRAM_ADAPTIVE_PROGRESSION_CONTENT.md`.
 *
 * ### The three exclusions are decisions, not gaps
 *
 *  * **`pushups_military`** is a real catalogue exercise of the `pushups` family and is deliberately not
 *    a rung. Placing it would mean asserting where it sits relative to `pushups_wide` and
 *    `decline_pushups`, and there is no independent evidence here for that ordering. A ladder is
 *    authored content; filling a position to look complete is how a fabricated ordering enters a system.
 *  * **`deep_squat`** is a mobility **hold**, not a squat variation that is easier or harder than
 *    another. §15's adaptation vocabulary is about progression of one family's variants, and a hold is
 *    not on that axis, so it is not a rung.
 *  * **`plank`** and **`glute_bridge`** get **nothing at all** — not a one-rung relation. `plank`'s
 *    intended five-step time progression (20/25/30/35/40s) and `glute_bridge`'s five-step volume
 *    progression (11→19) both require *the same exercise at several levels*, which this domain
 *    deliberately rejects. Manufacturing a single-rung "ladder" for them would turn an honest absence
 *    into a claim that the family declares a hierarchy, and a one-rung relation makes the engine hold
 *    at both its floor and its ceiling — a different claim, arrived at by hiding the absence. They are
 *    therefore undeclared, and `relationOf` answers `null` for both.
 *
 * @property definitions the four authored relations, in ascending family-id order.
 */
object ProductionProgressionRelationDefinitions {

    /**
     * The push-up ladder: knee push-ups below the standard push-up, the standard push-up in the
     * middle, and two harder variations above it.
     *
     * The volume *falls* as the exercise gets harder (`12` → `8` → `7` → `7`). That is the intent and
     * not a typo: a harder variation is prescribed fewer repetitions, and this file states each rung's
     * own target rather than computing it from a shared rule, so the inversion is a decision that is
     * visible rather than one buried in a formula.
     */
    val pushups: ProgramProgressionRelation = ProgramProgressionRelation(
        familyId = "pushups",
        variants = listOf(
            ProgramProgressionVariant(-1, "pushups_knee", RepPrescription(listOf(12, 12, 12))),
            ProgramProgressionVariant(0, "pushups", RepPrescription(listOf(8, 8, 8))),
            ProgramProgressionVariant(1, "pushups_wide", RepPrescription(listOf(7, 7, 7))),
            ProgramProgressionVariant(2, "decline_pushups", RepPrescription(listOf(7, 7, 7)))
        )
    )

    /**
     * The squat ladder: the sumo variation below the standard squat, then the standard squat, then two
     * harder single-leg and ballistic variations.
     */
    val squats: ProgramProgressionRelation = ProgramProgressionRelation(
        familyId = "squats",
        variants = listOf(
            ProgramProgressionVariant(-1, "squats_sumo", RepPrescription(listOf(14, 14, 14))),
            ProgramProgressionVariant(0, "squats", RepPrescription(listOf(15, 15, 15))),
            ProgramProgressionVariant(1, "cossack_squat", RepPrescription(listOf(10, 10, 10))),
            ProgramProgressionVariant(2, "squats_jump", RepPrescription(listOf(12, 12, 12)))
        )
    )

    /**
     * The lunge ladder: the reverse lunge below the forward lunge, then the forward lunge, then the
     * step-up and the side lunge.
     *
     * Three rungs share `[10, 10, 10]` deliberately. Equal volume across distinct exercises is not a
     * defect — it is the authored target, and writing it four times rather than hoisting it into a
     * shared constant keeps each rung's prescription readable on its own, so a later edit to one rung
     * cannot silently move another.
     */
    val lunges: ProgramProgressionRelation = ProgramProgressionRelation(
        familyId = "lunges",
        variants = listOf(
            ProgramProgressionVariant(-1, "lunges_reverse", RepPrescription(listOf(10, 10, 10))),
            ProgramProgressionVariant(0, "lunges", RepPrescription(listOf(10, 10, 10))),
            ProgramProgressionVariant(1, "step_ups", RepPrescription(listOf(10, 10, 10))),
            ProgramProgressionVariant(2, "lunges_side", RepPrescription(listOf(10, 10, 10)))
        )
    )

    /**
     * The pull-up ladder, and the only relation here spanning five levels.
     *
     * Its bottom rung is a **dead hang** — a timed hold — and every rung above it is a repetition-based
     * pull-up variant. The ladder therefore **crosses the prescription dimension**: rung `-2` is a
     * [TimePrescription] and rungs `-1..+2` are [RepPrescription]s.
     *
     * That crossing is intentional and is not a defect in the model. It records what the family really
     * is: an entry point that is a supported hang rather than a repetition, and then four graded
     * repetitions. The domain models prescription dimension as a property of a rung rather than of a
     * family, which is exactly what lets this exist without a special case; the one thing the dimension
     * is never used for here is comparison (nothing adds a hang's seconds to a pull-up's
     * repetitions).
     */
    val pullups: ProgramProgressionRelation = ProgramProgressionRelation(
        familyId = "pullups",
        variants = listOf(
            ProgramProgressionVariant(-2, "hang", TimePrescription(listOf(30, 30, 30))),
            ProgramProgressionVariant(-1, "pullups_chin", RepPrescription(listOf(6, 6, 6))),
            ProgramProgressionVariant(0, "pullups", RepPrescription(listOf(5, 5, 5))),
            ProgramProgressionVariant(1, "pullups_neutral", RepPrescription(listOf(5, 5, 5))),
            ProgramProgressionVariant(2, "pullups_wide", RepPrescription(listOf(4, 4, 4)))
        )
    )

    /**
     * Every relation this app ships, in ascending family-id order.
     *
     * Exactly four, and the architecture gate reads this list's size rather than a hard-coded count, so
     * a fifth family can only be added here deliberately — where the decision is visible — and not by
     * a seed that quietly widens its own scope.
     */
    val definitions: List<ProgramProgressionRelation> = listOf(pushups, squats, lunges, pullups)

    /**
     * The families this app declares a ladder for.
     *
     * Read by the bootstrap, which stores exactly these and nothing else, so a family outside this set
     * cannot be seeded by widening the seed's own inputs.
     */
    val authorisedFamilyIds: Set<String> = definitions.map { it.familyId }.toSet()
}