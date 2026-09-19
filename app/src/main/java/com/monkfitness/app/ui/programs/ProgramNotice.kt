package com.monkfitness.app.ui.programs

import com.monkfitness.app.R

/**
 * What the Program screens report after an operation, in the four classes §28 and §15 require the user
 * to be able to tell apart.
 *
 * Every path through the Program System already answers with a typed result
 * ([com.monkfitness.app.domain.program.ProgramOperationResult],
 * [com.monkfitness.app.domain.program.ProgramEditorResult],
 * [com.monkfitness.app.domain.program.transfer.ProgramTransferResult]), so nothing in the UI has to
 * interpret an exception or a boolean. What is left for this type is the one thing those results
 * deliberately do not carry: **the sentence a user reads**, as a string resource rather than a literal
 * (§14).
 *
 * ```text
 * Done      the operation happened, and this is what it did
 * Refused   a rule of §3, §4 or §29 stopped it — the user can act and try again
 * Invalid   the file or the draft is not acceptable — the user picks another file / fixes the draft
 * Failed    storage or the platform failed — recoverable, and never presented as success
 * ```
 *
 * The distinction between [Refused] and [Invalid] is §15's *"expected/refused action requiring user
 * action"* versus *"invalid imported data"*; the distinction between both and [Failed] is §33's
 * prohibition on turning a failure into an empty result or a silent no-op. A screen shows the message
 * and, for the three non-[Done] cases, keeps the user on the screen they were on.
 */
sealed interface ProgramNotice {

    /** The user-facing sentence: a string resource, never a literal built here. */
    val messageRes: Int

    /** The operation completed. */
    data class Done(override val messageRes: Int) : ProgramNotice

    /** A rule refused the operation as an expected state (§28). */
    data class Refused(override val messageRes: Int) : ProgramNotice

    /** The data offered was not acceptable: a file that is not a program, or a draft that cannot be saved. */
    data class Invalid(override val messageRes: Int) : ProgramNotice

    /** Storage or the platform failed (§28's `SYSTEM_FAILURE`). Never reported as a success. */
    data class Failed(override val messageRes: Int) : ProgramNotice

    companion object {

        /** The one place the notices are declared, so a screen never has to build one ad hoc. */
        val SELECTED: ProgramNotice = Done(R.string.programs_notice_selected)
        val RENAMED: ProgramNotice = Done(R.string.programs_notice_renamed)
        val ARCHIVED: ProgramNotice = Done(R.string.programs_notice_archived)
        val UNARCHIVED: ProgramNotice = Done(R.string.programs_notice_unarchived)
        val DELETED: ProgramNotice = Done(R.string.programs_notice_deleted)
        val STARTED: ProgramNotice = Done(R.string.programs_notice_started)
        val PAUSED: ProgramNotice = Done(R.string.programs_notice_paused)
        val RESUMED: ProgramNotice = Done(R.string.programs_notice_resumed)
        val COMPLETED: ProgramNotice = Done(R.string.programs_notice_completed)
        val DRAFT_SAVED: ProgramNotice = Done(R.string.programs_notice_saved)
        val DRAFT_UNCHANGED: ProgramNotice = Done(R.string.programs_notice_nothing_changed)
        val IMPORTED: ProgramNotice = Done(R.string.programs_notice_imported)

        /** §3, §4 and §29's refusals, each with the sentence that explains it. */
        val STANDARD_CANNOT_BE_EDITED: ProgramNotice =
            Refused(R.string.programs_refused_standard_edit)
        val STANDARD_CANNOT_BE_DELETED: ProgramNotice =
            Refused(R.string.programs_refused_standard_delete)
        val ACTIVE_SESSION_BLOCKS_DELETE: ProgramNotice =
            Refused(R.string.programs_refused_active_session)
        val ARCHIVE_OF_SELECTION: ProgramNotice = Refused(R.string.programs_refused_archive_selection)
        val ILLEGAL_TRANSITION: ProgramNotice = Refused(R.string.programs_refused_transition)
        val PROGRAM_NOT_FOUND: ProgramNotice = Refused(R.string.programs_refused_not_found)

        /** §15's *"invalid imported data"*, one case per refusal kind §19 of the transfer stage names. */
        val NOT_A_PROGRAM_FILE: ProgramNotice = Invalid(R.string.programs_import_not_a_file)
        val UNSUPPORTED_FORMAT_VERSION: ProgramNotice =
            Invalid(R.string.programs_import_unsupported_version)
        val SCHEMA_INVALID: ProgramNotice = Invalid(R.string.programs_import_schema_invalid)
        val UNKNOWN_EXERCISES: ProgramNotice = Invalid(R.string.programs_import_unknown_exercises)
        val SEMANTICALLY_INVALID: ProgramNotice = Invalid(R.string.programs_import_semantically_invalid)
        val SCHEDULING_REFUSED: ProgramNotice = Invalid(R.string.programs_import_scheduling_refused)
        val DRAFT_REJECTED: ProgramNotice = Invalid(R.string.programs_draft_rejected)

        /**
         * §7's Generated path, reported as unavailable rather than fabricated.
         *
         * The planner needs a library view that states each exercise's focuses; this app's catalogue does
         * not carry that classification, and §30 step 12 recorded the same missing artefact on the
         * adaptive side. The mode is offered and saved; only the automatic plan is deferred.
         */
        val GENERATION_UNAVAILABLE: ProgramNotice = Refused(R.string.programs_generation_unavailable)


        /** §28's `SYSTEM_FAILURE`: reported, retryable, and never mistaken for a completed operation. */
        val STORAGE_FAILED: ProgramNotice = Failed(R.string.programs_notice_failed)
        val IMPORT_FAILED: ProgramNotice = Failed(R.string.programs_import_failed)
        val SHARE_FAILED: ProgramNotice = Failed(R.string.programs_notice_share_failed)
    }
}
