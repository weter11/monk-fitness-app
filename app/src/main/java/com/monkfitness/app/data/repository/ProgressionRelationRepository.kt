package com.monkfitness.app.data.repository

import com.monkfitness.app.data.local.ProgressionRelationVariantDao
import com.monkfitness.app.data.mapper.storedProgressionRelation
import com.monkfitness.app.data.mapper.toProgressionVariantEntity
import com.monkfitness.app.domain.adaptive.engine.ProgramProgressionRelation

/**
 * The app-owned **progression relation catalogue**: one `ProgramProgressionRelation` per family, stored
 * and read back value for value.
 *
 * It is separate from `ProgramAdaptiveRepository`, which owns revision-scoped, append-only adaptive
 * *history* (a family's current state, the decision trail, the adjustments). A ladder is none of those:
 * it is a **definition**, global to a family, written once and read by every plan that trains that
 * family — folding it in would give one class two unrelated lifetimes and put a global table behind a
 * name that says *adaptive state*.
 *
 * It decides nothing: no level is computed, ordered, inferred or numbered here. The domain's own
 * constructor performs the duplicate-exercise, contiguity and canonical-order checks, and no
 * prescription is defaulted, averaged or collapsed — every variant's full per-set target list survives
 * the round trip. [relationOf] answers `null` for a family the catalogue does not declare: not an empty
 * relation (the domain refuses one) and not a fabricated one, which would turn "no declared ladder" into
 * the stronger claim "declares an empty ladder". It holds no clock and no id generator, and consults no
 * plan, revision, scheduler, engine, policy or family state — a ladder derived from any of those would
 * be a heuristic producing a ladder nobody authored.
 *
 * [replace] writes a whole definition as one unit: a ladder with only some of its rungs is not a ladder,
 * so the delete and the insert run inside the caller-supplied transaction. This repository never opens a
 * transaction of its own, because the layer that knows what else belongs in the same unit is above it.
 */
class ProgressionRelationRepository(
    private val relationDao: ProgressionRelationVariantDao,
    private val inTransaction: suspend (suspend () -> Unit) -> Unit
) {

    /**
     * The declared ladder of [familyId], or `null` when the catalogue declares none for it — the whole
     * read contract `ProgressionRelationProvider.relationOf` needs: one family id in, one validated
     * relation or one explicit absence out. A stored row set that contradicts the domain's own rules
     * fails here rather than being normalised into a plausible ladder.
     */
    suspend fun relationOf(familyId: String): ProgramProgressionRelation? {
        val rows = relationDao.variantsOfFamily(familyId)
        if (rows.isEmpty()) return null
        return storedProgressionRelation(familyId, rows)
    }

    /** Every family the catalogue declares a ladder for, in ascending family-id order. */
    suspend fun declaredFamilyIds(): List<String> = relationDao.declaredFamilyIds()

    /**
     * Stores one family's ladder, keeping every other family's rows exactly as they are. It is **not**
     * an append: storing a definition means the definition that family now has, so a second, different
     * ladder for the same family is a replacement — which is what makes authoring idempotent rather than
     * accumulating contradictory rungs.
     */
    suspend fun store(relation: ProgramProgressionRelation) = replace(relation)

    /**
     * Replaces one family's stored ladder with [relation], as one unit of work.
     *
     * The whole definition is dropped and re-stored inside one transaction, so a reader never observes a
     * family mid-authoring — a ladder missing its top rung would be contiguous and would silently change
     * what "the ceiling" means. No other family's rows are touched: the delete is scoped to this one
     * `familyId`.
     */
    suspend fun replace(relation: ProgramProgressionRelation) = inTransaction {
        relationDao.deleteVariantsOfFamily(relation.familyId)
        relationDao.insertVariants(
            relation.variants.map { variant -> variant.toProgressionVariantEntity(relation.familyId) }
        )
    }

    suspend fun remove(familyId: String) {
        relationDao.deleteVariantsOfFamily(familyId)
    }
}