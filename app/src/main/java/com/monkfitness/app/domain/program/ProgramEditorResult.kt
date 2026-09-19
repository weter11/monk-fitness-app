package com.monkfitness.app.domain.program

import com.monkfitness.app.domain.common.RevisionId

/**
 * The outcome of one editor operation, in §28's error contract.
 *
 * §28 asks for three things of an operation's result — expected states are results rather than
 * exceptions, invalid data is a result too, and a failure is never swallowed into an empty success —
 * and this is the editor's reading of them:
 *
 *  * [Success] — the operation did what it said, and [Success.value] is what it produced.
 *  * [Rejected] — an *expected* answer: the draft is not finished ([ProgramEditorRejection.InvalidDraft]),
 *    the Program's content is the app's (§4), the revision a draft was opened from is gone, or a
 *    Program is missing (§28's `INVALID_DATA`). A refused save has written nothing.
 *  * [Failed] — the §28 `SYSTEM_FAILURE` class: something the storage layer threw, surfaced unchanged
 *    for the caller to decide about (§33 forbids turning it into `null`, an empty list or a success).
 *
 * ### Why this is not [ProgramOperationResult]
 *
 * The lifecycle's envelope keys its refusals on a `ProgramId` — every operation of §3, §4 and §29
 * happens *to a Program*. The editor's do not: `create` has no Program yet, and a copy has none
 * either, so a refusal would have to carry an id that does not exist. The two envelopes therefore
 * differ in exactly that one field, and they share everything else: the same §28 classes, and — for
 * the rules that are genuinely about Programs — the same [ProgramOperationRefusal] values, so §4's
 * sentence about the built-in Program is written once.
 */
sealed interface ProgramEditorResult<out T> {

    /** The operation succeeded and produced [value]. */
    data class Success<T>(val value: T) : ProgramEditorResult<T>

    /** The operation was refused, and nothing was written. */
    data class Rejected(val rejection: ProgramEditorRejection) : ProgramEditorResult<Nothing>

    /** The operation failed for a reason that is not one of the editor's rules (§28 `SYSTEM_FAILURE`). */
    data class Failed(val cause: Throwable) : ProgramEditorResult<Nothing>
}

/**
 * The rules and findings that can stop an editor operation, each carrying the sentence that explains
 * it.
 *
 * Typed rather than a message alone, for the reason [ProgramOperationRefusal] is: the UI wants the
 * text, and a test wants to know which rule fired without matching strings.
 */
sealed interface ProgramEditorRejection {

    /** The user-facing sentence. */
    val message: String

    /**
     * The draft is not finished, so nothing was saved (§7's validate step, §28 `INVALID_DATA`).
     *
     * The [validation] carries every finding, which is what makes the rejection actionable rather
     * than merely negative — and it is decided before any write, so an invalid draft cannot leave a
     * partial Program behind.
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
     * A rule about Programs refused the operation — §4's built-in Program, or §28's missing row.
     *
     * The rule itself is [ProgramOperationRefusal], shared with the lifecycle layer, so the wording
     * of §4's copy-before-edit sentence lives in one place and the editor cannot paraphrase it.
     */
    data class Refused(val rule: ProgramOperationRefusal) : ProgramEditorRejection {
        override val message: String = rule.message
    }
}

/**
 * What one `Save` produced (§6: *One Save creates at most one new Revision. No-op Save creates
 * none.*).
 *
 * The three cases are the whole of §6's Save contract, and [revisionsCreated] states it as a number a
 * test can assert without knowing which case it got: a structural change and the creation of a
 * Program each create **exactly one** revision, and anything else creates **none**.
 *
 * @property program the Program as it now stands: the created one, or the edited one carrying the
 *   facts that were applied.
 * @property currentRevisionId the revision that describes the Program's plan after the save. For a
 *   no-op save this is the revision it already had — the save did not move it.
 * @property revisionsCreated how many revisions this save created: `1` or `0`, never more.
 */
sealed interface ProgramSaveOutcome {

    val program: Program

    val currentRevisionId: RevisionId

    val revisionsCreated: Int

    /**
     * A new revision was saved and is now the Program's current plan.
     *
     * @property createdProgram whether the Program itself was created — the create and copy entry
     *   points — as opposed to a new revision of an existing one.
     * @property revision the revision that was saved. It is a new immutable value: the revision it
     *   replaces keeps describing exactly what it described (§6).
     */
    data class RevisionSaved(
        override val program: Program,
        val revision: ProgramRevision,
        val createdProgram: Boolean
    ) : ProgramSaveOutcome {

        override val currentRevisionId: RevisionId
            get() = revision.revisionId

        override val revisionsCreated: Int
            get() = 1
    }

    /**
     * Only the Program's own facts changed — its name or its description — so no revision was created
     * (§6).
     */
    data class FactsSaved(
        override val program: Program,
        override val currentRevisionId: RevisionId
    ) : ProgramSaveOutcome {

        override val revisionsCreated: Int
            get() = 0
    }

    /**
     * The save changed nothing at all (§28's `NothingToChange`): the draft's structure matches the
     * Program's current plan and its name and description are unchanged, so no row was written.
     */
    data class NothingToChange(
        override val program: Program,
        override val currentRevisionId: RevisionId
    ) : ProgramSaveOutcome {

        override val revisionsCreated: Int
            get() = 0
    }
}
