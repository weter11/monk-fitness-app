package com.monkfitness.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.monkfitness.app.data.model.ProgressionRelationVariantEntity

/**
 * Persistence for the app-owned **progression relation catalogue**: the variants one family declares,
 * at the positions it declares them.
 *
 * ### What this DAO can and cannot do
 *
 * An insert, one family's rows, and a delete scoped to one family. That is the whole surface, and the
 * delete is not an edit path: a ladder is a *definition*, so replacing one is "drop this family's rows
 * and store the new definition", which the repository performs as one transaction. There is no
 * `UPDATE`, because an update would let a row be re-pointed from one exercise or level to another
 * without the relation ever being assembled — and the family's contiguity and canonical order are
 * claims about the whole set, not about one row.
 *
 * ### The read is the family's, in the domain's own canonical order
 *
 * [variantsOfFamily] is keyed on `familyId` and nothing else: no Program, no revision, no state row
 * and no date participate in the lookup, because a ladder belongs to a family and to no plan.
 *
 * The `ORDER BY level ASC, exerciseId ASC` **is** the canonical order `ProgramProgressionRelation`
 * requires its variants to be held in — so the read returns a value the domain would accept as-is,
 * rather than an unordered collection the domain would then have to be handed in order. The ordering is
 * a read contract derived from the stored columns, never a decision about which variant matters.
 */
@Dao
interface ProgressionRelationVariantDao {

    /**
     * Stores the variants of one relation, each under its own `(familyId, exerciseId)`.
     *
     * A plain `@Insert`: a second row for an already-declared pair is refused by the primary key rather
     * than silently replacing the first, because "one exercise is one position in its family's ladder"
     * is a rule the table holds rather than one the caller is trusted to observe.
     */
    @Insert
    suspend fun insertVariants(variants: List<ProgressionRelationVariantEntity>)

    /** Every stored variant of one family, in level then exercise-id order. */
    // Written as a single literal, not a concatenated pair: the architecture test that compares every
    // `@Query` a target DAO declares against the statement the suites execute reads the annotation with
    // one regex, so a multi-line literal would fall out of that comparison entirely rather than fail it.
    @Query("SELECT * FROM `progression_relation_variant` WHERE `familyId` = :familyId ORDER BY `level` ASC, `exerciseId` ASC")
    suspend fun variantsOfFamily(familyId: String): List<ProgressionRelationVariantEntity>

    /** Drops one family's declared variants, leaving every other family's ladder untouched. */
    @Query("DELETE FROM `progression_relation_variant` WHERE `familyId` = :familyId")
    suspend fun deleteVariantsOfFamily(familyId: String)

    /**
     * Every family that has at least one stored variant, each once, in ascending family-id order.
     *
     * `DISTINCT` rather than a scan-and-unique so the answer is the catalogue's own set of families and
     * not an artefact of the row order; the ordering is a read contract so two reads agree.
     */
    @Query("SELECT DISTINCT `familyId` FROM `progression_relation_variant` ORDER BY `familyId` ASC")
    suspend fun declaredFamilyIds(): List<String>
}