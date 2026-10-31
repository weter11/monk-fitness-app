package com.monkfitness.app.domain.program.transfer

import com.monkfitness.app.domain.program.FocusPlan
import com.monkfitness.app.domain.program.ProgramDay
import com.monkfitness.app.domain.program.ProgramDuration
import com.monkfitness.app.domain.program.ProgramEditorDraft
import com.monkfitness.app.domain.program.ProgramMode
import com.monkfitness.app.domain.program.ProgramSchedule

/**
 * The Import Draft: a document that has passed every validation this stage has, beside the domain draft
 * it describes.
 *
 * The two are kept side by side because they answer different questions: [document] is *what the file
 * said*, and [plan] is *the plan this app would create* — the same [ProgramEditorDraft] the editor
 * edits, so reviewing an import and reviewing an edit are the same kind of thing.
 *
 * There is no `programId` and none can appear (identity is minted when the Program is saved), no
 * revision, and no persistent day or element identity: the days and elements carry working handles
 * minted from the injected source, and [ProgramTransferMapper.revisionOf] re-identifies every one of
 * them, so nothing minted for a draft can reach a row.
 *
 * The constructor is `internal` so a caller cannot assemble a draft that skipped validation: the only
 * producer is the import pipeline, and the only consumers are a review screen and the save.
 */
data class ProgramImportDraft internal constructor(
    val document: ProgramTransferDocument,
    val plan: ProgramEditorDraft
) {

    val formatVersion: Int
        get() = document.formatVersion

    val name: String
        get() = plan.name

    val description: String
        get() = plan.description

    val mode: ProgramMode
        get() = plan.mode

    val duration: ProgramDuration
        get() = plan.duration

    val schedule: ProgramSchedule
        get() = plan.schedule

    val focus: FocusPlan
        get() = plan.focus

    val days: List<ProgramDay>
        get() = plan.days

    /** How many plan elements the imported plan holds, counting repeats separately. */
    val exerciseCount: Int
        get() = days.sumOf { day -> day.exercises.size }

    val setCount: Int
        get() = days.sumOf { day -> day.exercises.sumOf { element -> element.prescription.setCount } }
}
