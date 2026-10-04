package com.monkfitness.app.domain.usecase

import com.monkfitness.app.data.repository.ProgressionRelationRepository
import com.monkfitness.app.domain.adaptive.engine.ProgramProgressionRelation
import com.monkfitness.app.domain.adaptive.integration.ProgressionRelationProvider
import kotlinx.coroutines.runBlocking

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
 * ### Why it is allowed to block on the read
 *
 * The port's `relationOf` is not `suspend`, so this class bridges the gap with [runBlocking] rather
 * than by changing the port the engine resolves against. That is the lesser of two evils *for this
 * stage*, and it is recorded rather than hidden: the alternative — a port that could be `suspend` — is
 * a change to the domain interface the adaptive engine and its tests already depend on, which P31 does
 * not own. The bridge performs **one indexed primary-key lookup** per call and no I/O beyond it, so the
 * block is bounded by a single row read; the port's shape can be revisited when the caller is an
 * integration that is already suspending.
 *
 * ### It is not wired into production yet, on purpose
 *
 * The catalogue is created empty by the migration and this stage authors **no ladder content at all**,
 * so wiring this in place of `NoDeclaredProgression` would change no observable behaviour while adding
 * a node to the composition root. `AppContainer` therefore keeps `NoDeclaredProgression`, and production
 * still reports `NO_DECLARED_PROGRESSION_RELATION` — see §6 of `docs/PROGRAM_ADAPTIVE_PROGRESSION_RELATIONS.md`.
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
    override fun relationOf(familyId: String): ProgramProgressionRelation? = runBlocking {
        relations.relationOf(familyId)
    }
}