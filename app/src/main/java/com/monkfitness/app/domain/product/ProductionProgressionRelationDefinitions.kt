package com.monkfitness.app.domain.product

import com.monkfitness.app.domain.adaptive.engine.ProgramProgressionRelation
import com.monkfitness.app.domain.adaptive.engine.ProgramProgressionVariant
import com.monkfitness.app.domain.prescription.RepPrescription
import com.monkfitness.app.domain.prescription.TimePrescription

/**
 * The one production source of progression-relation content: the four families this app ships a
 * ladder for, and the exact rungs each declares.
 *
 * This is DATA only. Every level, exercise id and per-set target is written out — no `when
 * (familyId)`, no catalogue ordering lookup, no difficulty coefficient, no adaptive history — and the
 * architecture gates read this file's text to prove it. A rung's value is a decision somebody
 * authored, not a number derived here.
 *
 * The prescriptions are the domain's own [RepPrescription] and [TimePrescription]; there is no
 * flattened scalar, because `listOf(8, 8, 8)` is three sets of eight, not one set of twenty-four.
 *
 * Rung counts follow the invariant that one exercise id is one position in one family's hierarchy, so
 * a family declares only as many rungs as it has distinct exercises: `pushups`, `squats` and `lunges`
 * ship four (`-1..+2`), `pullups` five (`-2..+2`). Contiguity within each relation is what the domain
 * checks — nothing requires `-2..+2`, and a fifth rung would mean inventing an exercise.
 *
 * Three deliberate exclusions: `pushups_military` is a real family exercise with no independent
 * evidence for its ordering against `pushups_wide` and `decline_pushups`; `deep_squat` is a mobility
 * hold, off the progression axis; `plank` and `glute_bridge` get nothing at all, because their
 * intended progressions need the same exercise at several levels, which this domain rejects — a
 * one-rung relation would claim a hierarchy that does not exist and pin the engine at both its floor
 * and its ceiling. `relationOf` therefore answers `null` for all three.
 */
object ProductionProgressionRelationDefinitions {

    /**
     * The push-up ladder: knee push-ups below the standard push-up, two harder variations above it.
     *
     * Volume falls as the exercise gets harder (`12` → `8` → `7` → `7`) — intent, not a typo: each rung
     * states its own target rather than computing it from a shared rule.
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

    /** The squat ladder: sumo below standard, then single-leg and ballistic variations. */
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
     * The lunge ladder: reverse lunge, forward lunge, step-up, side lunge.
     *
     * Three rungs share `[10, 10, 10]` deliberately — equal volume across distinct exercises is the
     * authored target, and writing it per rung means a later edit to one cannot silently move another.
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
     * The pull-up ladder, the only relation here spanning five levels.
     *
     * Its bottom rung is a timed dead hang and every rung above is a repetition-based variant, so the
     * ladder crosses the prescription dimension. That is intentional: dimension is a property of a
     * rung, not of a family, which is what lets this exist without a special case. The dimension is
     * never used for comparison — nothing adds a hang's seconds to a pull-up's repetitions.
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
     * Every relation this app ships, ascending by family id. The architecture gate reads this list's
     * size rather than a hard-coded count, so a fifth family can only be added here deliberately, not
     * by a seed that quietly widens its own scope.
     */
    val definitions: List<ProgramProgressionRelation> = listOf(pushups, squats, lunges, pullups)

    /**
     * The families this app declares a ladder for. The bootstrap stores exactly these and nothing
     * else, so a family outside this set cannot be seeded by widening the seed's own inputs.
     */
    val authorisedFamilyIds: Set<String> = definitions.map { it.familyId }.toSet()
}