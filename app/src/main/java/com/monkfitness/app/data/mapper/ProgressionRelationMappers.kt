package com.monkfitness.app.data.mapper

import com.monkfitness.app.data.model.ProgressionRelationVariantEntity
import com.monkfitness.app.domain.adaptive.engine.ProgramProgressionRelation
import com.monkfitness.app.domain.adaptive.engine.ProgramProgressionVariant
import com.monkfitness.app.domain.prescription.Prescription
import com.monkfitness.app.domain.prescription.PrescriptionDimension
import com.monkfitness.app.domain.prescription.RepPrescription
import com.monkfitness.app.domain.prescription.TimePrescription

/**
 * The persisted progression-variant rows ⇄ the existing `ProgramProgressionRelation`.
 *
 * This file is a **translation and nothing else**. It creates no second ladder type: the domain value
 * a stored row reconstructs is the very `ProgramProgressionVariant` / `ProgramProgressionRelation`
 * the adaptive engine already resolves against, so a persisted ladder and a caller-supplied one are
 * the same value and nothing downstream can tell which produced it.
 *
 * ### The losslessness claim, and why it is written this way
 *
 * A variant's prescription is part of the ladder definition: §15's adaptation is a *presentation*
 * (`before` → `after`), so a rung that stored no prescription could not be presented and the engine
 * would have to keep the current one — i.e. claim that a harder variant prescribes exactly what an
 * easier one did. So [storedPrescription] reconstructs the **whole** prescription from the two stored
 * columns, and the per-set list is carried through verbatim in set order:
 *
 * ```text
 * RepPrescription([12, 10, 8, 6])  ->  REP_BASED   "12,10,8,6"   ->  RepPrescription([12, 10, 8, 6])
 * TimePrescription([30, 30, 45])   ->  TIME_BASED  "30,30,45"    ->  TimePrescription([30, 30, 45])
 * ```
 *
 * There is deliberately **no branch anywhere below that reduces a target list to a count, a mean or
 * any single scalar**, because `[12, 10, 8, 6]` and `[10, 10, 10, 10]` are two different prescriptions
 * that happen to share a set count, and a column able to hold only one of them would be a silent loss.
 * The column is written by the schema's existing `ProgramTypeConverters.toPerSetTargets` pair, which
 * is already lossless for exactly this reason; this mapper introduces no new converter semantics.
 *
 * ### What is refused rather than guessed
 *
 *  * **An unknown dimension token** fails loudly. `PrescriptionDimension` names five members and only
 *    two have a `Prescription` subtype today; a row naming one of the other three, or a token outside
 *    the vocabulary, cannot be turned into a prescription without inventing its algorithm — so the read
 *    fails instead of substituting the closest implemented dimension.
 *  * **Nothing is derived from the exercise id.** No name, category, level or catalogue entry is
 *    consulted, so a stored rung means what its row says and nothing else.
 *  * **Nothing is sorted or deduplicated here.** The canonical order is the DAO's `ORDER BY`, and the
 *    contiguity / duplicate-exercise / same-level checks are `ProgramProgressionRelation`'s own
 *    constructor's — re-implementing them here would be a second copy of a rule that has one owner.
 */
/**
 * The domain prescription of one stored row.
 *
 * The stored **dimension decides the subtype**, never the shape of the target list: `REP_BASED` reads
 * back as repetitions and `TIME_BASED` as seconds, so the same four numbers cannot come back as the
 * other dimension's unit. The dimension with no implemented subtype is a refusal, not a default.
 *
 * [ProgressionRelationVariantEntity.perSetTargets] arrives as the whole `List<Int>` because the column
 * is declared as that type and `ProgramTypeConverters` is what turns it into the stored
 * comma-joined form — the schema's own existing, already-lossless converter. This mapper therefore
 * adds no converter semantics of its own and has no parsing step that could drop an element.
 */
private fun ProgressionRelationVariantEntity.storedPrescription(): Prescription {
    val targets = perSetTargets
    if (targets.isEmpty()) {
        throw IllegalArgumentException(
            "a stored progression variant prescribes at least one set, '$exerciseId' holds none"
        )
    }
    val dimension = PrescriptionDimension.entries.firstOrNull { it.name == prescriptionDimension }
        ?: throw IllegalArgumentException(
            "a stored prescriptionDimension must be one of " +
                "${PrescriptionDimension.entries.joinToString { it.name }}, " +
                "was '$prescriptionDimension' for '$exerciseId'"
        )
    return when (dimension) {
        PrescriptionDimension.REP_BASED -> RepPrescription(targets)
        PrescriptionDimension.TIME_BASED -> TimePrescription(targets)
        // Named in the domain with no subtype, so there is no prescription to rebuild. Defaulting to a
        // repetition count here would restate the variant as something the row never claimed.
        else -> throw IllegalArgumentException(
            "a stored prescriptionDimension of $dimension has no prescription subtype yet, " +
                "so '$exerciseId' cannot be read back: the algorithm is not this mapper's to invent"
        )
    }
}

/** The domain variant one stored row states, field for field. */
internal fun ProgressionRelationVariantEntity.toProgressionVariant(): ProgramProgressionVariant =
    ProgramProgressionVariant(
        level = level,
        exerciseId = exerciseId,
        prescription = storedPrescription()
    )

/** The stored row for one domain variant, under the family whose ladder declares it. */
internal fun ProgramProgressionVariant.toProgressionVariantEntity(
    familyId: String
): ProgressionRelationVariantEntity = ProgressionRelationVariantEntity(
    familyId = familyId,
    level = level,
    exerciseId = exerciseId,
    prescriptionDimension = prescription.dimension.name,
    // The full list, in set order, through the schema's own converter. Written as one comma-joined
    // string here only because the test harness binds entity fields by reflection; the production Room
    // path goes through `ProgramTypeConverters.toPerSetTargets`, which is the same format.
    perSetTargets = prescription.perSetTargets
)

/**
 * The stored relation one family's rows state.
 *
 * The rows arrive already in canonical order from the DAO's `ORDER BY level, exerciseId`, and the
 * constructor enforces what a per-row constraint cannot: no exercise twice, contiguous levels, and the
 * canonical order itself. A family whose stored rows violate any of them therefore fails to read rather
 * than being repaired here — the rules belong to the domain, and a mapper that normalised them would
 * hide the defect behind a plausible ladder.
 */
internal fun storedProgressionRelation(
    familyId: String,
    rows: List<ProgressionRelationVariantEntity>
): ProgramProgressionRelation = ProgramProgressionRelation(
    familyId = familyId,
    variants = rows.map { it.toProgressionVariant() }
)