package com.monkfitness.app.domain.program

import com.monkfitness.app.domain.common.ProgramDayId

/**
 * A Program and its first revision, prepared by the editor and **written nowhere** — the creation unit
 * before the two halves that are not the editor's to decide.
 *
 * The editor owns the *structure* half of a creation: validating the draft and minting the Program and
 * revision identities ([com.monkfitness.app.domain.usecase.ProgramEditorService.prepareCreation]). The
 * initial opportunities come from the Scheduler, and the single transactional write is the repository's;
 * the application-level Save orchestration
 * ([com.monkfitness.app.domain.usecase.ProgramSaveService]) joins the three into
 * `Create / Copy → Program + Revision + ProgramDays + ProgramExercises + initial Slots`.
 *
 * [mintedProgramDays] is the correspondence from each of the draft's plan-day handles to the identity that
 * handle became in the saved revision. A caller that stated a `workoutId -> ProgramDayId` binding against
 * a draft handle needs it, because a save mints a fresh identity for every day, and the editor is the one
 * component that performs the re-identification, so it is the one that reports it.
 *
 * The Program carries the planned start date the creation request chose — a fact of the Program, never a
 * field of [ProgramStructure].
 */
data class ProgramCreation(
    val program: Program,
    val revision: ProgramRevision,
    val mintedProgramDays: Map<ProgramDayId, ProgramDayId>
)
