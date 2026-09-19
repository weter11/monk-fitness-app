package com.monkfitness.app.domain.program.transfer

import com.monkfitness.app.domain.program.FocusPlan
import com.monkfitness.app.domain.program.ProgramDay
import com.monkfitness.app.domain.program.ProgramDuration
import com.monkfitness.app.domain.program.ProgramEditorDraft
import com.monkfitness.app.domain.program.ProgramMode
import com.monkfitness.app.domain.program.ProgramSchedule

/**
 * The **Import Draft** — §5's pipeline end and §7's *review an import* entry point.
 *
 * ```text
 * bytes → parse → formatVersion → schema → exerciseId → semantic → Import Draft → save new Program
 *                                                                   ^^^^^^^^^^^^
 * ```
 *
 * ### What it is
 *
 * A document that has passed every validation this stage has, together with the domain draft it
 * describes. The two are kept side by side because they answer different questions and a review screen
 * may want either: [document] is *what the file said* (its format version, its own field structure), and
 * [plan] is *the plan this app would create* — the same [ProgramEditorDraft] the editor itself edits, so
 * the review of an import and the review of an edit are the same kind of thing and a future editor screen
 * does not need a second renderer.
 *
 * ### What it deliberately does not have
 *
 *  * **no `programId`** — and none can appear: the only identity an import will ever have is minted when
 *    the Program is saved (§3, §9);
 *  * **no revision**, no `revisionNumber` and no `revisionId`: §5's draft is not a revision, and §17 says
 *    the Program an import creates has exactly one, numbered 1, minted at save;
 *  * **no persistent day or element identity.** The draft's days and elements carry *working handles* —
 *    the same kind of handle [ProgramEditorDraft] carries — because the editor's operations address a day
 *    by id. They are minted from the injected source and are discarded by
 *    [ProgramTransferMapper.revisionOf], which re-identifies every one of them: nothing minted for a
 *    draft can reach a row (§6, §23). That is §7's *"the draft must not acquire persistent IDs merely
 *    because it was parsed"* read as a property of the save rather than as a promise about the draft.
 *
 * ### It is constructed by the pipeline and by nothing else
 *
 * The constructor is `internal`, so a caller cannot assemble a draft that skipped validation and hand it
 * to the save: the only producer is the import pipeline, and the only consumers are a review screen and
 * the save. That is the same shape §30 step 6 gave the editor's own draft — a value whose invariants are
 * established where it is made.
 *
 * @property document the parsed, validated document.
 * @property plan the domain draft it describes, ready for review and for a save.
 */
data class ProgramImportDraft internal constructor(
    val document: ProgramTransferDocument,
    val plan: ProgramEditorDraft
) {

    /** The format version the file was written in (§5). */
    val formatVersion: Int
        get() = document.formatVersion

    /** The Program's name, as the file states it. */
    val name: String
        get() = plan.name

    /** The Program's description, as the file states it. */
    val description: String
        get() = plan.description

    /** The mode the imported Program will be in (§2). */
    val mode: ProgramMode
        get() = plan.mode

    /** How long the imported revision runs (§20). */
    val duration: ProgramDuration
        get() = plan.duration

    /** When the imported revision's opportunities fall (§20). */
    val schedule: ProgramSchedule
        get() = plan.schedule

    /** The Goals & Focus configuration it is built for (§8). */
    val focus: FocusPlan
        get() = plan.focus

    /** The imported plan's days, in order. */
    val days: List<ProgramDay>
        get() = plan.days

    /** How many plan elements the imported plan holds, counting repeats separately (§9). */
    val exerciseCount: Int
        get() = days.sumOf { day -> day.exercises.size }

    /** How many sets the whole imported plan prescribes. */
    val setCount: Int
        get() = days.sumOf { day -> day.exercises.sumOf { element -> element.prescription.setCount } }
}
