package com.monkfitness.app.domain.program

import com.monkfitness.app.domain.common.ProgramDayId

/**
 * A Program and its first revision, prepared by the editor and **written nowhere** — §27's creation
 * unit before the two halves that are not the editor's to decide.
 *
 * The editor owns the *structure* half of a creation: validating the draft and minting the Program
 * and revision identities ([com.monkfitness.app.domain.usecase.ProgramEditorService.prepareCreation]).
 * What it deliberately does not own is on the other side of this value: the initial opportunities come
 * from the Scheduler, and the single transactional write is the repository's. The application-level
 * Save orchestration ([com.monkfitness.app.domain.usecase.ProgramSaveService]) is what joins the three
 * into §27's `Create / Copy → Program + Revision + ProgramDays + ProgramExercises + initial Slots`.
 *
 * It also carries [mintedProgramDays] — the correspondence from each of the draft's plan-day handles
 * to the identity that handle became in the saved revision. A caller that stated a `workoutId ->
 * ProgramDayId` binding against a draft handle needs that correspondence to be authorable at all (a
 * save mints a fresh identity for every day, §6), and the editor is the single component that performs
 * the re-identification, so it is the one that reports it. Nothing else re-derives it.
 *
 * @property program the Program as it would be stored, carrying the planned start date the creation
 *   request chose — a fact of the Program (§3, §6), never a field of [ProgramStructure].
 * @property revision the Program's first revision, minted with fresh day and element identities.
 * @property mintedProgramDays the editor's own draft-handle to saved-identity correspondence for this
 *   revision's plan days.
 */
data class ProgramCreation(
    val program: Program,
    val revision: ProgramRevision,
    val mintedProgramDays: Map<ProgramDayId, ProgramDayId>
)
