package com.monkfitness.app.domain.program.transfer

import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.program.ProgramSchedulingRefusal

/**
 * One exported Program file, as the share boundary receives it.
 *
 * Three facts and no fourth: the name the receiving app will show, the type it can dispatch on, and the
 * bytes — the format's UTF-8 text exactly as it was written. The file name is fixed and never built from
 * the Program. Nothing here knows what a `content://` URI is, what an `Intent` is or that Android exists:
 * the Android layer turns this value into a share, which is what keeps the export testable without a
 * device.
 */
data class ProgramTransferFile(
    val fileName: String,
    val mimeType: String,
    val bytes: ByteArray
) {

    val text: String
        get() = ProgramTransferFormat.decode(bytes)

    /** Whether [other] carries the same bytes. `data class` equality on arrays is identity, so it is named. */
    fun hasTheSameBytesAs(other: ProgramTransferFile): Boolean =
        fileName == other.fileName && mimeType == other.mimeType && bytes.contentEquals(other.bytes)
}

/**
 * The outcome of one transfer operation.
 *
 * `Success` is the operation doing what it said; `Rejected` is an **expected** answer — this document is
 * not a program file, its version is unsupported, its shape or content does not describe a savable
 * program, it names exercises this app does not have, or a scheduling rule refused the save — and
 * `Failed` is the `SYSTEM_FAILURE` class: something the storage layer threw, surfaced unchanged rather
 * than absorbed into a `null`, a `false` or an empty program.
 *
 * Each rejection states what a caller can do about it: pick another file (`NotAProgramFile`,
 * `SchemaInvalid`, `SemanticallyInvalid`, `UnknownExercises`), nothing (a `UnsupportedFormatVersion` file
 * is newer than this app), set a start date first (`SchedulingRefused`), or retry (`Failed`).
 *
 * This envelope is the import/export's own because the value an export produces is a *file* and the value
 * an import produces is a *Program that did not exist before*, so a refusal cannot be keyed on a
 * `ProgramId` — an import has no Program yet, and a rejection is about *a document*, not about a row. What
 * is shared is the vocabulary: the refusals name [ProgramSchedulingRefusal] where a scheduling rule fired,
 * so the Scheduler's own sentence is written once.
 */
sealed interface ProgramTransferResult<out T> {

    data class Success<T>(val value: T) : ProgramTransferResult<T>

    /** The operation was refused by a rule about the document or the Program. Nothing was written. */
    data class Rejected(val rejection: ProgramTransferRejection) : ProgramTransferResult<Nothing>

    /** The operation failed for a reason that is not one of those rules (`SYSTEM_FAILURE`). */
    data class Failed(val cause: Throwable) : ProgramTransferResult<Nothing>
}

/**
 * The rules that can refuse a transfer, each carrying the sentence that explains it.
 *
 * One case per pipeline step, in the order the pipeline can reach them: the bytes, `formatVersion`, the
 * schema, exercise ids, semantic validity, the save's scheduling decision, and the Program an export was
 * asked for.
 *
 * A persistence failure is *not* here: it is [ProgramTransferResult.Failed], so an import that dies
 * half-way reports the cause rather than a plausible-looking refusal.
 */
sealed interface ProgramTransferRejection {

    val message: String

    data class NotAProgramFile(val reason: String) : ProgramTransferRejection {
        override val message: String = "this is not a Monk Fitness program file: $reason"
    }

    /**
     * The document is this format, in a version this reader does not know.
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

    data class SchemaInvalid(val issues: List<ProgramTransferIssue>) : ProgramTransferRejection {
        override val message: String =
            "this program file does not have the shape this format defines: " +
                issues.joinToString("; ") { issue -> issue.message }
    }

    /**
     * The document names exercises the app's library does not hold.
     *
     * Exercises are never invented, substituted or auto-created, and an unknown one is never dropped
     * silently: the import refuses the whole document and names every id it could not resolve.
     */
    data class UnknownExercises(val exerciseIds: List<String>) : ProgramTransferRejection {
        override val message: String =
            "this program file uses exercises this app does not have: " +
                "${exerciseIds.joinToString(", ")}; nothing was imported (§5)"
    }

    data class SemanticallyInvalid(val issues: List<ProgramTransferIssue>) :
        ProgramTransferRejection {
        override val message: String =
            "this program file does not describe a valid program: " +
                issues.joinToString("; ") { issue -> issue.message }
    }

    /**
     * The save's own scheduling decision was refused by a scheduling rule.
     *
     * Unreachable through the importer by construction — the imported Program is planned to start on the
     * day it arrives, so the Scheduler always has an anchor — and reported as a value rather than assumed
     * away, because the Scheduler's refusals are answered, not skipped.
     */
    data class SchedulingRefused(val refusal: ProgramSchedulingRefusal) : ProgramTransferRejection {
        override val message: String = refusal.message
    }

    data class ProgramNotFound(val programId: ProgramId) : ProgramTransferRejection {
        override val message: String =
            com.monkfitness.app.domain.program.ProgramOperationRefusal.ProgramNotFound(programId).message
    }
}

/** The text is not one JSON document: a syntax failure of the bytes. */
internal class MalformedDocument(val reason: String) : IllegalArgumentException(reason)

internal class UnsupportedDocumentVersion(val found: Int) :
    IllegalArgumentException(ProgramTransferRejection.UnsupportedFormatVersion(found, ProgramTransferFormat.VERSION).message)

internal class SchemaViolation(val issues: List<ProgramTransferIssue>) :
    IllegalArgumentException(issues.joinToString("; ") { it.message })

internal class SemanticViolation(val issues: List<ProgramTransferIssue>) :
    IllegalArgumentException(issues.joinToString("; ") { it.message })

internal class UnknownDocumentExercises(val exerciseIds: List<String>) :
    IllegalArgumentException(exerciseIds.joinToString(", "))

internal class ExportProgramMissing(val programId: ProgramId) :
    IllegalArgumentException(ProgramTransferRejection.ProgramNotFound(programId).message)

internal class ImportSchedulingRefused(val refusal: ProgramSchedulingRefusal) :
    IllegalStateException(refusal.message)

/**
 * Runs [block], mapping anything it throws onto [ProgramTransferResult.Failed] rather than absorbing it — a
 * failure is never turned into an empty result.
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
