package com.monkfitness.app.domain.program

import com.monkfitness.app.domain.common.ProgramDayId
import com.monkfitness.app.domain.common.RevisionId

/**
 * The outcome of one editor operation.
 *
 * An operation answers in one of three ways: [Success] did what it said and carries what it produced;
 * [Rejected] is an *expected* answer — the draft is not finished
 * ([ProgramEditorRejection.InvalidDraft]), the Program's content is the app's, the revision a draft was
 * opened from is gone, or a Program is missing — and a refused save has written nothing; [Failed] is
 * something the storage layer threw, surfaced unchanged for the caller to decide about rather than
 * turned into `null`, an empty list or a success.
 *
 * This is not [ProgramOperationResult] because the lifecycle's envelope keys its refusals on a
 * `ProgramId` — every lifecycle operation happens *to a Program*. The editor's do not: `create` has no
 * Program yet and a copy has none either, so a refusal would have to carry an id that does not exist. The
 * two envelopes differ in exactly that one field and share everything else, including the
 * [ProgramOperationRefusal] values for the rules that are genuinely about Programs, so the sentence about
 * the built-in Program is written once.
 */
sealed interface ProgramEditorResult<out T> {

    /** The operation succeeded and produced [value]. */
    data class Success<T>(val value: T) : ProgramEditorResult<T>

    /** The operation was refused, and nothing was written. */
    data class Rejected(val rejection: ProgramEditorRejection) : ProgramEditorResult<Nothing>

    /** The operation failed for a reason that is not one of the editor's rules. */
    data class Failed(val cause: Throwable) : ProgramEditorResult<Nothing>
}

/**
 * The rules and findings that can stop an editor operation, each carrying the sentence that explains it.
 *
 * Typed rather than a message alone, for the reason [ProgramOperationRefusal] is: the UI wants the text,
 * and a test wants to know which rule fired without matching strings.
 */
sealed interface ProgramEditorRejection {

    /** The user-facing sentence. */
    val message: String

    /**
     * The draft is not finished, so nothing was saved.
     *
     * The [validation] carries every finding, which is what makes the rejection actionable rather than
     * merely negative — and it is decided before any write, so an invalid draft cannot leave a partial
     * Program behind.
     */
    data class InvalidDraft(val validation: ProgramDraftValidation) : ProgramEditorRejection {
        override val message: String =
            "this draft cannot be saved yet: " +
                validation.issues.joinToString("; ") { it.message }
    }

    /** The revision a draft was opened from is not stored, so the draft's provenance is not real. */
    data class RevisionNotFound(val revisionId: RevisionId) : ProgramEditorRejection {
        override val message: String =
            "no revision with id '${revisionId.value}' is stored; this draft was opened from a " +
                "revision that does not exist (§6)"
    }

    /**
     * A rule about Programs refused the operation — the built-in Program, or a missing row.
     *
     * The rule itself is [ProgramOperationRefusal], shared with the lifecycle layer, so the wording of the
     * copy-before-edit sentence lives in one place and the editor cannot paraphrase it.
     */
    data class Refused(val rule: ProgramOperationRefusal) : ProgramEditorRejection {
        override val message: String = rule.message
    }
}

/**
 * What one `Save` produced: one Save creates at most one new Revision, and a no-op Save creates none.
 *
 * The three cases are the whole of the Save contract, and [revisionsCreated] states it as a number a test
 * can assert without knowing which case it got: a structural change and the creation of a Program each
 * create **exactly one** revision, and anything else creates **none**. `currentRevisionId` is the
 * revision describing the plan after the save; for a no-op save it is the one the Program already had.
 */
sealed interface ProgramSaveOutcome {

    val program: Program

    val currentRevisionId: RevisionId

    val revisionsCreated: Int

    /**
     * A new revision was saved and is now the Program's current plan.
     *
     * `createdProgram` distinguishes the create and copy entry points from a new revision of an existing
     * one. `revision` is a new immutable value: the revision it replaces keeps describing exactly what it
     * described. `mintedProgramDays` is the editor's draft-handle to saved-identity correspondence, so a
     * caller can state a `workoutId -> ProgramDayId` binding against a draft handle and have it re-pointed
     * at the identity that handle became — the editor performs that re-identification, so it is the one
     * that reports it.
     */
    data class RevisionSaved(
        override val program: Program,
        val revision: ProgramRevision,
        val createdProgram: Boolean,
        val mintedProgramDays: Map<ProgramDayId, ProgramDayId>
    ) : ProgramSaveOutcome {

        override val currentRevisionId: RevisionId
            get() = revision.revisionId

        override val revisionsCreated: Int
            get() = 1
    }

    /**
     * Only the Program's own facts changed — its name or its description — so no revision was created.
     */
    data class FactsSaved(
        override val program: Program,
        override val currentRevisionId: RevisionId
    ) : ProgramSaveOutcome {

        override val revisionsCreated: Int
            get() = 0
    }

    /**
     * The save changed nothing at all: the draft's structure matches the Program's current plan and its
     * name and description are unchanged, so no row was written.
     */
    data class NothingToChange(
        override val program: Program,
        override val currentRevisionId: RevisionId
    ) : ProgramSaveOutcome {

        override val revisionsCreated: Int
            get() = 0
    }
}
