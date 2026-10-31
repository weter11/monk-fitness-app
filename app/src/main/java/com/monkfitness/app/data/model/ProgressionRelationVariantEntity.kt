package com.monkfitness.app.data.model

import androidx.room.Entity

/**
 * One stored **progression variant**: one exercise at one level of one family's declared ladder,
 * with the prescription that variant presents.
 *
 * The table is global and carries no Program or revision: a `ProgramProgressionRelation` is keyed by
 * `familyId` and by nothing else, and its `level` is an ordinal **inside that one family's hierarchy** —
 * levels are not comparable across families. A ladder is a property of a family, not of a plan, so
 * making this row revision-owned would restate the ladder per revision and let two revisions of one
 * Program disagree about what `level 2` means. There is deliberately **no `programId`, no `revisionId`,
 * no current state, no adaptive outcome, no policy, no timestamp, no ranking and no difficulty score**
 * here: this is catalogue/configuration, and adaptive history lives in its own tables.
 *
 * `familyId` is the opaque family identity, copied verbatim — this schema owns **no family catalogue**,
 * so the id is stored as given and is never parsed, normalised or derived. `level` is an `INTEGER`, not
 * a name, because the domain's rule is arithmetic: positions must be contiguous, and contiguity is only
 * checkable over numbers. `prescriptionDimension` is spelled as its own enum name; only `REP_BASED` and
 * `TIME_BASED` have a `Prescription` subtype today, so a row naming one of the other three is refused by
 * the mapper rather than guessed at. `perSetTargets` is the **full ordered list** of per-set targets
 * through the schema's existing converter pair: `12 / 10 / 8 / 6` is four sets and cannot be recovered
 * from a single `10`, a set count or an average.
 *
 * Membership is `(familyId, exerciseId)` and that pair **is** the primary key: *one exercise cannot be
 * declared twice in one family* is a structural fact of the table rather than a rule only the domain
 * enforces on the way in. What it deliberately does **not** do is forbid two exercises at one `level` —
 * that is a legitimate declaration (same-level variants) — so no uniqueness constraint is declared over
 * `(familyId, level)`. Contiguity of levels is a property of the *set* of a family's rows, which no
 * per-row constraint can express, so it stays the domain's rule and the repository assembles the
 * relation through `ProgramProgressionRelation`'s own constructor, which performs the check.
 *
 * The table declares **no index**: `familyId` is the primary key's leading column and the only column
 * this table is ever read by, exactly as `program_target_schedule_rule` declares none.
 *
 * @property level the position inside that family's hierarchy, contiguous from its lowest to its highest.
 * @property exerciseId the exercise this position presents, at most once per family.
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