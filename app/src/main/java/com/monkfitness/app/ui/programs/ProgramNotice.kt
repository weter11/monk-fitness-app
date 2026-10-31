package com.monkfitness.app.ui.programs

import com.monkfitness.app.R

/**
 * What the Program screens report after an operation, in the four classes the user must be able to tell
 * apart.
 *
 * Every path already answers with a typed result, so nothing in the UI has to interpret an exception or a
 * boolean; what is left for this type is the one thing those results deliberately do not carry: **the
 * sentence a user reads**, as a string resource rather than a literal.
 *
 * [Done] is the operation that happened and what it did; [Refused] a rule stopped it and the user can act
 * and try again; [Invalid] the file or the draft is not acceptable, so the user picks another file or fixes
 * the draft; [Failed] storage or the platform failed, recoverable and never presented as success. The
 * [Refused]/[Invalid] split is *"expected/refused action requiring user action"* versus *"invalid imported
 * data"*, and both stay apart from [Failed] so a failure is never turned into an empty result or a silent
 * no-op. A screen shows the message and, for the three non-[Done] cases, keeps the user where they were.
 */
sealed interface ProgramNotice {

    /** The user-facing sentence: a string resource, never a literal built here. */
    val messageRes: Int

    data class Done(override val messageRes: Int) : ProgramNotice

    /** A rule refused the operation: an expected state, and one the user can act on. */
    data class Refused(override val messageRes: Int) : ProgramNotice

    /** The data offered was not acceptable: a file that is not a program, or a draft that cannot be saved. */
    data class Invalid(override val messageRes: Int) : ProgramNotice

    /** Storage or the platform failed. Never reported as a success. */
    data class Failed(override val messageRes: Int) : ProgramNotice

    companion object {

        /** The one place the notices are declared, so a screen never has to build one ad hoc. */
        val SELECTED: ProgramNotice = Done(R.string.programs_notice_selected)
        val RENAMED: ProgramNotice = Done(R.string.programs_notice_renamed)

        /** The planned start date was saved, which is a different sentence from a rename. */
        val PLANNED_START_DATE_SET: ProgramNotice = Done(R.string.programs_notice_planned_start_set)
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

        val STANDARD_CANNOT_BE_EDITED: ProgramNotice =
            Refused(R.string.programs_refused_standard_edit)
        val STANDARD_CANNOT_BE_DELETED: ProgramNotice =
            Refused(R.string.programs_refused_standard_delete)
        val ACTIVE_SESSION_BLOCKS_DELETE: ProgramNotice =
            Refused(R.string.programs_refused_active_session)
        val ARCHIVE_OF_SELECTION: ProgramNotice = Refused(R.string.programs_refused_archive_selection)
        val ILLEGAL_TRANSITION: ProgramNotice = Refused(R.string.programs_refused_transition)
        val PROGRAM_NOT_FOUND: ProgramNotice = Refused(R.string.programs_refused_not_found)

        val NOT_A_PROGRAM_FILE: ProgramNotice = Invalid(R.string.programs_import_not_a_file)
        val UNSUPPORTED_FORMAT_VERSION: ProgramNotice =
            Invalid(R.string.programs_import_unsupported_version)
        val SCHEMA_INVALID: ProgramNotice = Invalid(R.string.programs_import_schema_invalid)
        val UNKNOWN_EXERCISES: ProgramNotice = Invalid(R.string.programs_import_unknown_exercises)
        val SEMANTICALLY_INVALID: ProgramNotice = Invalid(R.string.programs_import_semantically_invalid)
        val SCHEDULING_REFUSED: ProgramNotice = Invalid(R.string.programs_import_scheduling_refused)
        val DRAFT_REJECTED: ProgramNotice = Invalid(R.string.programs_draft_rejected)

        /**
         * The Generated path when the app states **no** focus classification for any exercise.
         *
         * Not the ordinary answer to Generate — the shipped catalogue is classified — but the answer for a
         * catalogue that has regressed to stating nothing, which is exactly the state a user must be told
         * about rather than shown a plan built from a guess. Kept, not deleted, because a gap that can
         * reopen needs a sentence.
         */
        val GENERATION_UNAVAILABLE: ProgramNotice = Refused(R.string.programs_generation_unavailable)

        /** Generate produced a plan and reconciled it into the draft; nothing was persisted. */
        val GENERATED: ProgramNotice = Done(R.string.programs_notice_generated)

        /**
         * A **Preview** was produced and is on screen. Nothing about the draft has changed, so the
         * sentence says so rather than repeating `GENERATED`: a user who reads *"a plan was generated into
         * the draft"* next to an untouched draft has been told something false.
         */
        val GENERATION_PREVIEWED: ProgramNotice = Done(R.string.programs_notice_previewed)

        /**
         * Generate was refused for this configuration: nothing in the library can serve it with the
         * equipment available.
         *
         * A [Refused] rather than a [Failed] and rather than a success with an empty plan: the user's own
         * draft is untouched and the reason is one they can act on — declare the equipment they own, or
         * choose focuses the library can serve.
         */
        val GENERATION_REFUSED: ProgramNotice = Refused(R.string.programs_generation_refused)

        /**
         * The generation pass itself failed. The draft is unchanged and the user is told so: never
         * presented as a completed generation.
         */
        val GENERATION_FAILED: ProgramNotice = Failed(R.string.programs_generation_failed)

        /**
         * The Program **started**, and its target schedule was not planned.
         *
         * Not a lifecycle refusal — the Program is running and its factual start date is recorded — and not
         * a success either, because nothing was planned for it: reporting the start's own success would
         * tell the user a Program was scheduled when no target row exists, so the composition's second
         * half gets its own sentence.
         */
        val TARGET_SCHEDULING_UNAVAILABLE: ProgramNotice =
            Invalid(R.string.programs_notice_target_scheduling_unavailable)


        /** A system failure: reported, retryable, and never mistaken for a completed operation. */
        val STORAGE_FAILED: ProgramNotice = Failed(R.string.programs_notice_failed)
        val IMPORT_FAILED: ProgramNotice = Failed(R.string.programs_import_failed)
        val SHARE_FAILED: ProgramNotice = Failed(R.string.programs_notice_share_failed)
    }
}
