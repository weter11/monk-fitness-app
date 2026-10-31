package com.monkfitness.app.bootstrap

import com.monkfitness.app.data.repository.ProgressionRelationRepository
import com.monkfitness.app.domain.product.ProductionProgressionRelationDefinitions

/**
 * The one boundary that guarantees the four authored ladders exist in storage before any production
 * consumer reads the catalogue.
 *
 * The provider's source is the persisted catalogue and nothing else — reading
 * `ProductionProgressionRelationDefinitions` directly would leave the stored catalogue decorative, so
 * storing the ladders is what makes it authoritative. `MIGRATION_17_18` creates the table with no
 * rows, so a fresh install and an existing schema-18 database converge on this same single call site
 * from `Application.onCreate`, the only place seeded content enters.
 *
 * Ordering is structural, not timing: the provider never seeds (no write branch, no lazy init), and an
 * adaptive pass is only reachable once a Session completes, minutes after `onCreate`. A deleted ladder
 * is therefore not restored mid-read or mid-evaluation (the provider answers `null`) — only at the
 * next application start.
 *
 * [bootstrap] is idempotent and never overwrites: it asks the catalogue per family and stores only an
 * absent one, so a partial catalogue is completed rather than skipped.
 */
class BuiltInProgressionCatalogueBootstrap(
    private val progressionRelationRepository: ProgressionRelationRepository
) {

    /**
     * Suspending because the repository is, and awaited rather than blocked because this runs on the
     * application's own start-up scope.
     */
    suspend fun bootstrap() {
        ProductionProgressionRelationDefinitions.definitions.forEach { definition ->
            // The catalogue's own answer is the check: a family it already declares is left alone, so
            // this is an absent-only fill and never an overwrite.
            if (progressionRelationRepository.relationOf(definition.familyId) != null) return@forEach
            progressionRelationRepository.store(definition)
        }
    }
}