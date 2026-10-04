package com.monkfitness.app.data.repository

import com.monkfitness.app.data.local.ProgressionRelationVariantDao
import com.monkfitness.app.data.mapper.storedProgressionRelation
import com.monkfitness.app.data.mapper.toProgressionVariantEntity
import com.monkfitness.app.domain.adaptive.engine.ProgramProgressionRelation

/**
 * The app-owned **progression relation catalogue**: one `ProgramProgressionRelation` per family, stored
 * and read back value for value.
 *
 * ### Why this is a separate repository, and not a method on `ProgramAdaptiveRepository`
 *
 * `ProgramAdaptiveRepository` owns adaptive **history**: a family's current state, the decision trail
 * and the adjustments decisions produced. All three are revision-scoped and append-only — they record
 * what happened to one family's progress inside one plan. A ladder is none of those things: it is a
 * **definition**, global to a family, written once and read by every plan that trains that family.
 * Folding it into the history repository would give one class two unrelated lifetimes (immutable
 * catalogue versus per-revision mutable state), would put a global table behind a name that says
 * *adaptive state*, and would make "the ladder is not adaptive history" an unverifiable claim.
 *
 * ### What it decides: nothing
 *
 * No level is computed, ordered, inferred or numbered here. The repository assembles the rows it stored
 * into the domain's own `ProgramProgressionRelation` and lets **that constructor** perform the
 * duplicate-exercise, contiguity and canonical-order checks — the persistence layer contributes no
 * rules of its own and repairs nothing. Likewise no prescription is defaulted, averaged or collapsed:
 * every variant's full per-set target list survives the round trip (§10).
 *
 * ### Absence is `null`, not an empty ladder
 *
 * [relationOf] answers `null` for a family the catalogue does not declare. That is the honest reading
 * of an app that has authored no ladder, and it is deliberately **not** an empty relation and **not** a
 * fabricated one: a relation with no variants is itself refused by the domain, and a defaulted ladder
 * would convert *"this family has no declared ladder"* into *"this family declares an empty ladder"*,
 * which is a different and much stronger claim.
 *
 * ### What it deliberately does not do
 *
 * It holds no clock and no identity generator, and it consults no plan, revision, scheduler, engine,
 * policy, `FamilyProgressionState`, family catalogue or Stage-1 source. A ladder is not derived from a
 * current exercise, a progression level already recorded, a catalogue order or an exercise name — every
 * one of those would be a heuristic producing a ladder nobody authored.
 *
 * ### Atomicity
 *
 * [replace] writes a whole definition as one unit: a ladder with only some of its rungs is not a
 * ladder, so the delete and the insert run inside the caller-supplied transaction. This repository never
 * opens a transaction of its own, because the layer that knows what else belongs in the same unit is
 * above it.
 *
 * @param relationDao the one DAO that owns `progression_relation_variant`.
 * @param inTransaction runs a block inside one database transaction; see [ProgramRepository].
 */
class ProgressionRelationRepository(
    private val relationDao: ProgressionRelationVariantDao,
    private val inTransaction: suspend (suspend () -> Unit) -> Unit
) {

    /**
     * The declared ladder of [familyId], or `null` when the catalogue declares none for it.
     *
     * This is the whole read contract `ProgressionRelationProvider.relationOf` needs, and it is why the
     * provider needs nothing else: one family id in, one validated relation or one explicit absence
     * out. A stored row set that contradicts the domain's own rules fails here rather than being
     * normalised into a plausible ladder.
     */
    suspend fun relationOf(familyId: String): ProgramProgressionRelation? {
        val rows = relationDao.variantsOfFamily(familyId)
        if (rows.isEmpty()) return null
        return storedProgressionRelation(familyId, rows)
    }

    /** Every family the catalogue declares a ladder for, in ascending family-id order. */
    suspend fun declaredFamilyIds(): List<String> = relationDao.declaredFamilyIds()

    /**
     * Stores one family's ladder, keeping every other family's rows exactly as they are.
     *
     * It is [replace] for one family and is **not** an append: storing a definition means the definition
     * that family now has. A second, different ladder for the same family is therefore a replacement,
     * which is what makes authoring idempotent rather than accumulating contradictory rungs.
     */
    suspend fun store(relation: ProgramProgressionRelation) = replace(relation)

    /**
     * Replaces one family's stored ladder with [relation], as one unit of work.
     *
     * The whole definition is dropped and re-stored inside one transaction, so a reader never observes
     * a family mid-authoring — a ladder missing its top rung would be contiguous and would silently
     * change what "the ceiling" means. No other family's rows are touched: the delete is scoped to this
     * one `familyId`.
     */
    suspend fun replace(relation: ProgramProgressionRelation) = inTransaction {
        relationDao.deleteVariantsOfFamily(relation.familyId)
        relationDao.insertVariants(
            relation.variants.map { variant -> variant.toProgressionVariantEntity(relation.familyId) }
        )
    }

    /** Removes one family's stored ladder, leaving every other family untouched. */
    suspend fun remove(familyId: String) {
        relationDao.deleteVariantsOfFamily(familyId)
    }
}