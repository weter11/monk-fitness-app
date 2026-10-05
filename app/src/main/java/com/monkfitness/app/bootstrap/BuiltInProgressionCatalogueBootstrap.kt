package com.monkfitness.app.bootstrap

import com.monkfitness.app.data.repository.ProgressionRelationRepository
import com.monkfitness.app.domain.product.ProductionProgressionRelationDefinitions

/**
 * P32's **built-in progression catalogue bootstrap**: the one boundary that guarantees the app's four
 * authored ladders exist in storage before any production consumer can read the catalogue.
 *
 * ```text
 * ProductionProgressionRelationDefinitions   the authored content
 *            ↓
 * BuiltInProgressionCatalogueBootstrap        this boundary — at application start
 *            ↓
 * progression_relation_variant                P31's table, schema 18, unchanged
 * ```
 *
 * ### Why the content lives in storage at all, rather than being read as a constant
 *
 * Because the provider's source is the **persisted catalogue** and nothing else. If the provider read
 * `ProductionProgressionRelationDefinitions` directly, the shipped content and the stored content would
 * be two claims about the same ladders with no defined relationship — and the stored catalogue would be
 * decorative. Storing the authored ladders makes the catalogue authoritative: the adaptive engine
 * resolves against what the app actually persisted, and a future stage that lets a user author or edit
 * a ladder writes through the same table with no second path.
 *
 * ### Where initialization happens, and why both fresh and existing databases are covered
 *
 * This runs from `Application.onCreate`, alongside the Standard Program bootstrap, and it is the **only**
 * place seeded content enters the catalogue. The table itself is created by P31's `MIGRATION_17_18`,
 * which ships **no rows**, so:
 *
 *  * a **fresh** install creates the table through the same migration chain and is then seeded here;
 *  * an **existing** database already at schema 18 is opened unchanged — no migration runs for this
 *    content, because seeding content is not a schema change — and is then seeded here too.
 *
 * Both cases converge on the same single call site, which is what makes "fresh" and "upgraded"
 * indistinguishable from the bootstrap's point of view: it never asks which it is, it asks what is
 * already there.
 *
 * ### Ordering: why the bootstrap completes before the provider can observe the catalogue
 *
 * The ordering is enforced **structurally, not by timing**. Two facts do the work:
 *
 *  1. **The provider never seeds.** [com.monkfitness.app.domain.usecase.StoredProgressionRelationProvider]
 *     is a pure read of the repository. There is no branch in it that can write, no "if the catalogue is
 *     empty, then author it" fallback, and no lazy initialisation — so a consumer that reads the
 *     catalogue *early* sees an honestly empty catalogue rather than a race against a seed. This is the
 *     load-bearing half: a provider that seeded on read would make its own answer depend on timing.
 *  2. **The adaptive pass is not reachable until a Session completes.** `ProgramAdaptiveIntegration`
 *     runs from `sessionRuntime.finishSession`, which is a user action minutes into the app's life,
 *     while the bootstrap runs in `Application.onCreate`. The ordering therefore does not rest on the
 *     seed being fast; it rests on no consumer existing yet.
 *
 * The consequence P32 documents honestly: on the very first launch, a completion cannot precede
 * `onCreate`, so the catalogue is seeded before any adaptive pass can ask. A **deleted** ladder, by
 * contrast, is *not* silently restored during a provider read or an adaptive evaluation — the provider
 * would answer `null` and the integration would report `NO_DECLARED_PROGRESSION_RELATION` for that
 * family. It is restored only on the next application start, which is a deliberate policy: authored
 * built-in content is app-owned and re-established at startup, while nothing a consumer observes is
 * quietly rewritten underneath it.
 *
 * ### Why it is idempotent, and why it never overwrites
 *
 * [bootstrap] stores a family **only when the catalogue declares nothing for it**, and it asks the
 * repository for that family's own declaration rather than counting rows or reading the table. So:
 *
 *  * running it three times stores one ladder once — the second and third calls find the family already
 *    declared and write nothing;
 *  * an existing ladder is **never overwritten**, so a future user-authored ladder for an authorised
 *    family is preserved rather than replaced by the shipped definition;
 *  * no family outside the four authorised ones can be seeded, because the input list *is* the four
 *    authorised families — there is no separate seed scope to widen by accident.
 *
 * The check is per family rather than a single "has anything been seeded" flag, so a **partial**
 * catalogue is completed rather than skipped: a database that somehow lost one authorised family gets
 * that one family back, and the other three are left exactly as they are.
 *
 * @param progressionRelationRepository the P31 catalogue repository — the only persistence collaborator.
 */
class BuiltInProgressionCatalogueBootstrap(
    private val progressionRelationRepository: ProgressionRelationRepository
) {

    /**
     * Stores every authored ladder the catalogue does not already declare.
     *
     * Suspending because the repository is, and awaiting rather than blocking because this runs on the
     * application's own start-up scope — the one place in this stage where content and persistence meet.
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