package com.monkfitness.app.data.model

import androidx.room.Entity

/**
 * One stored **progression variant**: one exercise at one level of one family's declared ladder,
 * with the prescription that variant presents.
 *
 * ### Why this table is global and carries no Program or revision
 *
 * A `ProgramProgressionRelation` is keyed by `familyId` and by nothing else. Its `level` is an
 * ordinal **inside that one family's hierarchy**, and the domain states outright that levels are not
 * comparable across families — so a ladder is a property of a family, not of a plan. Making this row
 * revision-owned would mean the same family's ladder was restated per revision, and two revisions of
 * one Program could disagree about what `level 2` means, which is precisely the cross-family
 * comparison the domain forbids. There is deliberately **no `programId`, no `revisionId`, no current
 * state, no adaptive outcome, no policy, no timestamp, no ranking and no difficulty score** here: this
 * is catalogue/configuration, and adaptive history lives in `ProgramAdaptiveRepository`'s own tables.
 *
 * ### What each column is
 *
 *  * `familyId` — the opaque family identity, copied verbatim. This schema owns **no family
 *    catalogue**, so the id is stored as given and is never parsed, normalised or derived.
 *  * `level` — the family's own ordinal position. An `INTEGER`, not a name, because the domain's rule
 *    is arithmetic: the positions must be contiguous, and contiguity is only checkable over numbers.
 *  * `exerciseId` — the opaque exercise identity this position presents.
 *  * `prescriptionDimension` — the existing `PrescriptionDimension`, spelled as its own enum name.
 *    Only `REP_BASED` and `TIME_BASED` have a `Prescription` subtype today; the other three are named
 *    in the domain with no subtype, and a row naming one is refused by the mapper rather than guessed at.
 *  * `perSetTargets` — the **full ordered list** of per-set targets, through the schema's existing
 *    `ProgramTypeConverters` pair. It is a list because §10 requires the plan to record what each set
 *    asks for: `12 / 10 / 8 / 6` is four sets and cannot be recovered from a single `10`, a set count,
 *    an average or any other scalar. No new converter semantics are introduced for this column.
 *
 * ### Identity, and what the database therefore enforces
 *
 * Membership is `(familyId, exerciseId)` and that pair **is** the primary key: it makes
 * *one exercise cannot be declared twice in one family* a structural fact of the table rather than a
 * rule only the domain enforces on the way in, so a second row for the same pair cannot be inserted
 * at all. What the primary key deliberately does **not** do is forbid two exercises at one `level` —
 * that is a legitimate declaration (§15's same-level variants), so no uniqueness constraint is declared
 * over `(familyId, level)`. Contiguity of levels is a property of the *set* of a family's rows, which
 * no per-row constraint can express, so it stays the domain's rule and the repository assembles the
 * relation through `ProgramProgressionRelation`'s own constructor — which performs the check.
 *
 * The table declares **no index**: `familyId` is the primary key's leading column and the only column
 * this table is ever read by, exactly as `program_target_schedule_rule` declares none.
 *
 * @property familyId the family whose ladder this row is one rung of.
 * @property level the position inside that family's hierarchy, contiguous from its lowest to its highest.
 * @property exerciseId the exercise this position presents, at most once per family.
 * @property prescriptionDimension the dimension this variant progresses in.
 * @property perSetTargets the per-set targets in set order; never empty.
 */
@Entity(tableName = "progression_relation_variant", primaryKeys = ["familyId", "exerciseId"])
data class ProgressionRelationVariantEntity(
    val familyId: String,
    val level: Int,
    val exerciseId: String,
    val prescriptionDimension: String,
    val perSetTargets: List<Int>
) {

    init {
        require(familyId.isNotBlank()) {
            "a stored progression variant needs the family whose ladder it is one rung of"
        }
        require(exerciseId.isNotBlank()) {
            "a stored progression variant needs the exercise its position presents"
        }
        require(perSetTargets.isNotEmpty()) {
            "a stored progression variant prescribes at least one set, got none: $exerciseId"
        }
    }
}