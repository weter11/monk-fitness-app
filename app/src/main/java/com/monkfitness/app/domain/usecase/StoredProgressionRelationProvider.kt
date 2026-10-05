package com.monkfitness.app.domain.usecase

import com.monkfitness.app.data.repository.ProgressionRelationRepository
import com.monkfitness.app.domain.adaptive.engine.ProgramProgressionRelation
import com.monkfitness.app.domain.adaptive.integration.ProgressionRelationProvider

/**
 * P31's **production `ProgressionRelationProvider`**: a read-only projection of the app-owned
 * persisted progression catalogue.
 *
 * ```text
 * familyId ──▶ ProgressionRelationRepository ──▶ validated ProgramProgressionRelation?
 * ```
 *
 * ### What it is, and what it is deliberately not
 *
 * It is a **thin projection**, and its thinness is the claim. The provider holds one collaborator — the
 * catalogue repository — and answers exactly the port's one question. It holds **no default ladder, no
 * hard-coded family, no generated level and no inference of any kind**: there is no branch here that
 * can produce a relation the repository did not return, which is why an empty catalogue produces
 * `null` for every family rather than a plausible-looking substitute.
 *
 * Specifically absent, each for the reason it is the wrong answer:
 *
 *  * **no fallback family** — an unknown family is not silently mapped onto a nearby one;
 *  * **no catalogue / phase / difficulty inference** — a level is never computed from an exercise name,
 *    a catalogue position, a plan-day phase or a `WorkoutGenerator` difficulty coefficient;
 *  * **no `FamilyProgressionState.currentExerciseId` as a ladder** — a family's *current* position is
 *    history, and a one-rung "ladder" read out of it would be an invented definition;
 *  * **no adaptive decisions as a ladder** — what a family did last window is not what it may do next;
 *  * **no Stage-1 `PilotProgressionProfiles`** — that generation is retired and its profiles are
 *    scoped to the legacy program's own axis;
 *  * **no recovery, no ranking, no scoring** — a ladder is a definition, and none of those is one.
 *
 * ### Why it does not block
 *
 * P31 recorded this as an explicit gap: the port's `relationOf` was not `suspend`, so this class
 * bridged the suspending repository read with `runBlocking`. **P32 closes that gap.** Its production
 * caller — `ProgramAdaptiveIntegration`, itself suspending end to end — is able to await the read, so
 * the port is now `suspend` and this class simply delegates. There is no blocking bridge anywhere in the
 * progression-relation provider path.
 *
 * The gap was worth closing here rather than in P31 because the answer comes from **storage**: a port
 * that cannot express a database read forces its implementation to hide one behind a blocking call,
 * and the honest signature is the one the cost actually has.
 *
 * ### It is the production ladder source
 *
 * P31 wired nothing here, because P31's catalogue was empty and a provider over an empty table would
 * have been a graph node changing no observable behaviour. P32 seeds the built-in catalogue, so
 * `AppContainer` wires **this** provider in place of `NoDeclaredProgression` and the ladder is real.
 *
 * Its source is the **persisted catalogue and nothing else**. The four authored ladders live in
 * `ProductionProgressionRelationDefinitions` and reach production only through the rows this reads: a
 * static read here would mean the shipped content and the stored content could disagree, and the stored
 * catalogue would stop being authoritative.
 */
class StoredProgressionRelationProvider(
    private val relations: ProgressionRelationRepository
) : ProgressionRelationProvider {

    /**
     * The persisted ladder of [familyId], or `null` when the catalogue declares none.
     *
     * The `null` is forwarded verbatim and is never converted into a default: the whole point of the
     * stage is that a *missing* storage boundary and a *stored* one are now distinguishable, and only
     * the stored one can ever produce a relation.
     */
    override suspend fun relationOf(familyId: String): ProgramProgressionRelation? =
        relations.relationOf(familyId)
}