package com.monkfitness.app.domain.usecase

import com.monkfitness.app.data.repository.ProgressionRelationRepository
import com.monkfitness.app.domain.adaptive.engine.ProgramProgressionRelation
import com.monkfitness.app.domain.adaptive.integration.ProgressionRelationProvider

/**
 * The production `ProgressionRelationProvider`: a read-only projection of the persisted progression
 * catalogue, and nothing else.
 *
 * It holds no default ladder, no hard-coded family, no generated level and no inference of any kind —
 * no branch here can produce a relation the repository did not return, so an empty catalogue yields
 * `null` for every family rather than a plausible-looking substitute. The catalogue is authoritative:
 * a static read here would let shipped content and stored content disagree.
 */
class StoredProgressionRelationProvider(
    private val relations: ProgressionRelationRepository
) : ProgressionRelationProvider {

    /**
     * The persisted ladder of [familyId], or `null` when the catalogue declares none. The `null` is
     * forwarded verbatim and never converted into a default: only a *stored* relation may be produced.
     */
    override suspend fun relationOf(familyId: String): ProgramProgressionRelation? =
        relations.relationOf(familyId)
}