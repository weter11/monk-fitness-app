package com.monkfitness.app.domain.program.transfer

import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.program.ProgramSchedulingRefusal

/**
 * One exported Program file, as the share boundary receives it (§11).
 *
 * Three facts and no fourth: the name the receiving app will show, the type it can dispatch on, and
 * the bytes — which are the format's UTF-8 text exactly as it was written. Nothing here knows what a
 * `content://` URI is, what an `Intent` is or that Android exists: the Android layer
 * (`com.monkfitness.app.platform`) is what turns this value into a share, and that is what keeps the
 * export testable without a device (§12, §21).
 *
 * @property fileName the fixed name of the transferred file (§5, §11). Never built from the Program.
 * @property mimeType the type of the bytes ([ProgramTransferFormat.MIME_TYPE]).
 * @property bytes the document itself, UTF-8.
 */
data class ProgramTransferFile(
    val fileName: String,
    val mimeType: String,
    val bytes: ByteArray
) {

    /** The document as text — the same bytes, decoded the way the format defines them. */
    val text: String
        get() = ProgramTransferFormat.decode(bytes)

    /** Whether [other] carries the same bytes. `data class` equality on arrays is identity, so it is named. */
    fun hasTheSameBytesAs(other: ProgramTransferFile): Boolean =
        fileName == other.fileName && mimeType == other.mimeType && bytes.contentEquals(other.bytes)
}

/**
 * The outcome of one transfer operation, in §28's error contract.
 *
 * ```text
 * Success                the operation did what it said, and [Success.value] is what it produced
 * Rejected               an EXPECTED answer: this document is not a program file, its version is not
 *                        supported, its shape or its content does not describe a savable program, it
 *                        names exercises this app does not have, or a rule of §3/§20 refused the save
 *                        (INVALID_DATA for the shape and the content, EXPECTED for the rest)
 * Failed                 the §28 SYSTEM_FAILURE class: something the storage layer threw, surfaced
 *                        unchanged rather than absorbed into a `null`, a `false` or an empty program
 *                        (§33: "catch Exception and return empty result" is prohibited)
 * ```
 *
 * ### Why this envelope is the import/export's own
 *
 * For the reason the editor's and the Scheduler's are their own: the value an export produces is a
 * *file* and the value an import produces is a *Program that did not exist before*, so a refusal cannot
 * be keyed on a `ProgramId` the way [com.monkfitness.app.domain.program.ProgramOperationResult]'s are —
 * an import has no Program yet, and a rejection is about *a document*, not about a row. What is shared is
 * the vocabulary: the refusals name [ProgramSchedulingRefusal] where a scheduling rule fired, so the
 * Scheduler's own sentence about a program with no date to plan from is written once.
 *
 * ### Recoverability (§28)
 *
 * Each rejection states what a caller can do about it, and the classes above carry it:
 *
 * ```text
 * NotAProgramFile, SchemaInvalid, SemanticallyInvalid, UnknownExercises   USER_ACTION  pick another file
 * UnsupportedFormatVersion                                              TERMINAL     the file is newer
 * SchedulingRefused                                                     USER_ACTION  set a start date first
 * Failed                                                                RETRY        storage
 * ```
 */
sealed interface ProgramTransferResult<out T> {

    /** The operation succeeded and produced [value]. */
    data class Success<T>(val value: T) : ProgramTransferResult<T>

    /** The operation was refused by a rule about the document or the Program. Nothing was written. */
    data class Rejected(val rejection: ProgramTransferRejection) : ProgramTransferResult<Nothing>

    /** The operation failed for a reason that is not one of those rules (§28 `SYSTEM_FAILURE`). */
    data class Failed(val cause: Throwable) : ProgramTransferResult<Nothing>
}

/**
 * The rules that can refuse a transfer, each carrying the sentence that explains it.
 *
 * The cases are §13's minimum list, one case each, in the order the pipeline can reach them:
 *
 * ```text
 * bytes          NotAProgramFile            not UTF-8, or not JSON, or not this app's format, or too large
 * formatVersion  UnsupportedFormatVersion   a version this reader does not know
 * schema         SchemaInvalid              the document is not the shape this format defines
 * exerciseId     UnknownExercises           an exercise this app's library does not hold
 * semantic       SemanticallyInvalid        a shape-valid document that is not a savable program
 * scheduling     SchedulingRefused          a rule of §3/§20 stopped the save's own scheduling decision
 * export         ProgramNotFound            the Program an export was asked for is not stored
 * ```
 *
 * A persistence failure is *not* here: it is [ProgramTransferResult.Failed], so an import that dies
 * half-way reports the cause rather than a plausible-looking refusal (§13: *"do not swallow persistence
 * failures"*).
 */
sealed interface ProgramTransferRejection {

    /** The user-facing sentence. */
    val message: String

    /** The bytes are not a program file at all: not UTF-8, not JSON, not this format, or too large. */
    data class NotAProgramFile(val reason: String) : ProgramTransferRejection {
        override val message: String = "this is not a Monk Fitness program file: $reason"
    }

    /**
     * The document is this format, in a version this reader does not know (§5).
     *
     * A version this app has never heard of is refused rather than partially read: a newer document may
     * mean something different by a field this one shares, and guessing would be a silent misreading.
     */
    data class UnsupportedFormatVersion(
        val found: Int,
        val supported: Int
    ) : ProgramTransferRejection {
        override val message: String =
            "this program file is written in format version $found, and this app reads version " +
                "$supported: update the app to import it (§5)"
    }

    /** The document is not the shape this format defines (a required field, a kind, a token). */
    data class SchemaInvalid(val issues: List<ProgramTransferIssue>) : ProgramTransferRejection {
        override val message: String =
            "this program file does not have the shape this format defines: " +
                issues.joinToString("; ") { issue -> issue.message }
    }

    /**
     * The document names exercises the app's library does not hold (§5).
     *
     * Exercises are never invented, substituted or auto-created, and an unknown one is never dropped
     * silently: the import refuses the whole document and names every id it could not resolve.
     */
    data class UnknownExercises(val exerciseIds: List<String>) : ProgramTransferRejection {
        override val message: String =
            "this program file uses exercises this app does not have: " +
                "${exerciseIds.joinToString(", ")}; nothing was imported (§5)"
    }

    /** The document is well-shaped but does not describe a program this app can hold (§6). */
    data class SemanticallyInvalid(val issues: List<ProgramTransferIssue>) :
        ProgramTransferRejection {
        override val message: String =
            "this program file does not describe a valid program: " +
                issues.joinToString("; ") { issue -> issue.message }
    }

    /**
     * The save's own scheduling decision was refused by §3/§20.
     *
     * Unreachable through the importer by construction — the imported Program is planned to start on the
     * day it arrives, so the Scheduler always has an anchor — and reported as a value rather than
     * assumed away, because "the Scheduler decides what opportunities the imported revision receives"
     * means its refusals are answered, not skipped (§8).
     */
    data class SchedulingRefused(val refusal: ProgramSchedulingRefusal) : ProgramTransferRejection {
        override val message: String = refusal.message
    }

    /** The Program an export was asked for is not stored (§28 `INVALID_DATA`). */
    data class ProgramNotFound(val programId: ProgramId) : ProgramTransferRejection {
        override val message: String =
            com.monkfitness.app.domain.program.ProgramOperationRefusal.ProgramNotFound(programId).message
    }
}

// ------------------------------------------------------------------------------------------------
// The failures the pipeline throws, and the boundary that turns them into the results above.
// ------------------------------------------------------------------------------------------------

/** The text is not one JSON document: a syntax failure of the bytes (§5's *parse* step). */
internal class MalformedDocument(val reason: String) : IllegalArgumentException(reason)

/** The document carries a `formatVersion` this reader does not know (§5). */
internal class UnsupportedDocumentVersion(val found: Int) :
    IllegalArgumentException(ProgramTransferRejection.UnsupportedFormatVersion(found, ProgramTransferFormat.VERSION).message)

/** The document is not the shape the format defines. */
internal class SchemaViolation(val issues: List<ProgramTransferIssue>) :
    IllegalArgumentException(issues.joinToString("; ") { it.message })

/** The document is well-shaped but not a savable program. */
internal class SemanticViolation(val issues: List<ProgramTransferIssue>) :
    IllegalArgumentException(issues.joinToString("; ") { it.message })

/** The document names exercises the library does not hold. */
internal class UnknownDocumentExercises(val exerciseIds: List<String>) :
    IllegalArgumentException(exerciseIds.joinToString(", "))

/** The Program an export names is not stored. */
internal class ExportProgramMissing(val programId: ProgramId) :
    IllegalArgumentException(ProgramTransferRejection.ProgramNotFound(programId).message)

/** The save's scheduling decision was refused (§3, §20). */
internal class ImportSchedulingRefused(val refusal: ProgramSchedulingRefusal) :
    IllegalStateException(refusal.message)

/**
 * Runs [block], mapping anything it throws onto [ProgramTransferResult.Failed] rather than absorbing it
 * (§28's `SYSTEM_FAILURE`, §33's prohibition on turning a failure into an empty result).
 */
internal inline fun <T> transferResult(block: () -> T): ProgramTransferResult<T> = try {
    ProgramTransferResult.Success(block())
} catch (failure: Throwable) {
    ProgramTransferResult.Failed(failure)
}

/** Maps the pipeline's internal failures onto the typed rejections a caller receives. */
internal fun <T> ProgramTransferResult<T>.rejecting(): ProgramTransferResult<T> = when (this) {
    is ProgramTransferResult.Success -> this
    is ProgramTransferResult.Rejected -> this
    is ProgramTransferResult.Failed -> when (val cause = cause) {
        is ProgramTransferTextError -> ProgramTransferResult.Rejected(
            ProgramTransferRejection.NotAProgramFile(cause.reason)
        )
        is MalformedDocument -> ProgramTransferResult.Rejected(
            ProgramTransferRejection.NotAProgramFile(cause.reason)
        )
        is UnsupportedDocumentVersion -> ProgramTransferResult.Rejected(
            ProgramTransferRejection.UnsupportedFormatVersion(cause.found, ProgramTransferFormat.VERSION)
        )
        is SchemaViolation -> ProgramTransferResult.Rejected(
            ProgramTransferRejection.SchemaInvalid(cause.issues)
        )
        is UnknownDocumentExercises -> ProgramTransferResult.Rejected(
            ProgramTransferRejection.UnknownExercises(cause.exerciseIds)
        )
        is SemanticViolation -> ProgramTransferResult.Rejected(
            ProgramTransferRejection.SemanticallyInvalid(cause.issues)
        )
        is ImportSchedulingRefused -> ProgramTransferResult.Rejected(
            ProgramTransferRejection.SchedulingRefused(cause.refusal)
        )
        is ExportProgramMissing -> ProgramTransferResult.Rejected(
            ProgramTransferRejection.ProgramNotFound(cause.programId)
        )
        else -> this
    }
}
