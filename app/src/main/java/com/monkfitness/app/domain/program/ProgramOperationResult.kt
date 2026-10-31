package com.monkfitness.app.domain.program

import com.monkfitness.app.domain.common.ProgramId

/**
 * The outcome of a Program operation.
 *
 * The Program operations are almost entirely expected states: the Standard Program may not be edited or
 * deleted; a Program with an `IN_PROGRESS` session may not be deleted; an archived Program may not be
 * archived again; a completed Program may not be resumed. Each of those is a `Refused` a caller can
 * present to the user — not a crash, and not an exception caught and turned into an empty result.
 *
 * The two halves are kept separate: [Refused] carries the rule that stopped the operation as a typed value
 * the UI can branch on rather than a string it has to parse, naming the Program and the reason; [Failure]
 * is a storage failure, surfaced rather than absorbed, because a repository that turned a database failure
 * into `null`, an empty list or a `false` would be exactly the swallowing that is forbidden.
 *
 * Generic in [T]: most operations return the Program they changed; the read operations return their own
 * value ([MyPrograms]) and the boolean ones return [Unit] or a typed decision.
 */
sealed interface ProgramOperationResult<out T> {

    /** The operation succeeded and produced [value]. */
    data class Success<T>(val value: T) : ProgramOperationResult<T>

    /**
     * The operation was refused because a rule forbids it.
     *
     * `reason` is the typed rule. [ProgramOperationRefusal] carries the rule itself and the message that
     * explains it, so a caller has both a machine-readable case and a user-facing sentence without one
     * being derived from the other by string matching.
     */
    data class Refused(
        val programId: ProgramId,
        val reason: ProgramOperationRefusal
    ) : ProgramOperationResult<Nothing>

    /**
     * The operation failed for a reason that is not one of the Program rules — a storage failure, an
     * invalid persisted value, a missing Program that should exist.
     */
    data class Failure(val cause: Throwable) : ProgramOperationResult<Nothing>
}

/**
 * The rules that can refuse a Program operation, each carrying the sentence that explains it.
 *
 * Typed rather than a sealed `String` carrier because two consumers want two different things: the UI
 * wants the [message], and the tests (and a future analytics path) want to assert *which* rule fired.
 * Deriving the case from the message by string matching would make the second one brittle, and deriving
 * the message from the case by a `when` at every call site would scatter the wording.
 */
sealed interface ProgramOperationRefusal {

    /** The user-facing sentence. */
    val message: String

    /**
     * The built-in Standard Program cannot be edited or deleted directly; editing it means copying it
     * first.
     */
    data object StandardProgramCannotBeEdited : ProgramOperationRefusal {
        override val message: String =
            "the built-in Standard Program cannot be edited directly; copy it first (§4)"
    }

    /** The built-in Standard Program cannot be deleted. */
    data object StandardProgramCannotBeDeleted : ProgramOperationRefusal {
        override val message: String = "the built-in Standard Program cannot be deleted (§4)"
    }

    /**
     * A Program with an `IN_PROGRESS` session may not be deleted.
     *
     * The deletion is refused *before* any row is removed, and the selection is not cleared to make it
     * succeed — the rule's point is that a workout in flight has an owner, and removing the owner from
     * underneath it is not an option the delete path may take.
     */
    data class HasInProgressSession(val programId: ProgramId) : ProgramOperationRefusal {
        override val message: String =
            "a Program with an IN_PROGRESS session cannot be deleted; finish or cancel the session " +
                "first (§29)"
    }

    /**
     * An archived Program's selection must move before the archive takes effect — archiving the selected
     * Program requires choosing another one.
     */
    data class ArchivingTheSelectionRequiresAnotherSelection(val programId: ProgramId) :
        ProgramOperationRefusal {
        override val message: String =
            "archiving the selected Program requires choosing another selected Program first (§3)"
    }

    /** The operation was attempted on a Program that is not stored. */
    data class ProgramNotFound(val programId: ProgramId) : ProgramOperationRefusal {
        override val message: String = "no Program with id '${programId.value}' is stored"
    }

    /** A Program in a lifecycle that does not support this operation. */
    data class IllegalTransition(val reason: ProgramTransitionResult.Refused) :
        ProgramOperationRefusal {
        override val message: String = reason.reason
    }

    /** A structural edit must go through the revision mechanism; this operation is not one. */
    data object StructuralChangeNeedsARevision : ProgramOperationRefusal {
        override val message: String =
            "a structural change is saved as a new immutable Revision through ProgramPlanRepository (§6)"
    }
}

/**
 * Runs [block], mapping anything the storage layer threw onto [ProgramOperationResult.Failure] rather than
 * absorbing it. A [ProgramOperationRefusal] is *not* a failure, so a caller that has already computed one
 * returns it directly instead of going through this.
 */
internal inline fun <T> storageOutcome(block: () -> T): ProgramOperationResult<T> = try {
    ProgramOperationResult.Success(block())
} catch (failure: Throwable) {
    ProgramOperationResult.Failure(failure)
}
