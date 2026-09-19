package com.monkfitness.app.domain.program

import com.monkfitness.app.domain.common.ProgramId

/**
 * The outcome of a Program operation, in the blueprint's error contract (§28).
 *
 * §28 is explicit that *expected states are results, not exceptions*, and the Program operations are
 * almost entirely expected states: the Standard Program may not be edited or deleted; a Program with
 * an `IN_PROGRESS` session may not be deleted; an archived Program may not be archived again; a
 * completed Program may not be resumed. Each of those is a `Refused` a caller can present to the user
 * — not a crash, and not (§33) an exception caught and turned into an empty result.
 *
 * The two halves of that are kept separate here:
 *
 *  * [Refused] carries the rule that stopped the operation, as a typed value the UI can branch on
 *    rather than a string it has to parse. It is the expected, explainable answer to a question the
 *    user is allowed to ask ("can I delete this?"), and it names the Program and the reason.
 *  * [Failure] is the §28 `SYSTEM_FAILURE` class: something the storage layer threw, surfaced rather
 *    than absorbed. A repository that turned a database failure into `null`, an empty list or a
 *    `false` would be exactly the "catch Exception and return empty result" §33 forbids, so the
 *    use-case layer propagates the cause instead of masking it.
 *
 * Generic in [T]: most operations return the Program they changed ([Program]); the read operations
 * return their own value ([MyPrograms]) and the boolean ones return [Unit] or a typed decision.
 */
sealed interface ProgramOperationResult<out T> {

    /** The operation succeeded and produced [value]. */
    data class Success<T>(val value: T) : ProgramOperationResult<T>

    /**
     * The operation was refused because a rule of §3, §4 or §29 forbids it.
     *
     * @property programId the Program the operation was attempted on.
     * @property reason the typed rule. [ProgramOperationRefusal] carries the rule itself and the
     *   message that explains it, so a caller has both a machine-readable case and a user-facing
     *   sentence without one being derived from the other by string matching.
     */
    data class Refused(
        val programId: ProgramId,
        val reason: ProgramOperationRefusal
    ) : ProgramOperationResult<Nothing>

    /**
     * The operation failed for a reason that is not one of the Program rules — a storage failure, an
     * invalid persisted value, a missing Program that should exist. §28's `SYSTEM_FAILURE` /
     * `INVALID_DATA` classes; §33's prohibition on swallowing them into empty results.
     */
    data class Failure(val cause: Throwable) : ProgramOperationResult<Nothing>
}

/**
 * The rules that can refuse a Program operation (§3, §4, §6, §29), each carrying the sentence that
 * explains it.
 *
 * Typed rather than a sealed `String` carrier because two different consumers want two different
 * things from a refusal: the UI wants the [message], and the tests (and a future analytics path) want
 * to assert *which* rule fired. Deriving the case from the message by string matching would make the
 * second one brittle, and deriving the message from the case by a `when` at every call site would
 * scatter the wording.
 */
sealed interface ProgramOperationRefusal {

    /** The user-facing sentence. */
    val message: String

    /**
     * The built-in Standard Program cannot be edited or deleted directly; editing it means copying it
     * first (§4).
     */
    data object StandardProgramCannotBeEdited : ProgramOperationRefusal {
        override val message: String =
            "the built-in Standard Program cannot be edited directly; copy it first (§4)"
    }

    /** The built-in Standard Program cannot be deleted (§4). */
    data object StandardProgramCannotBeDeleted : ProgramOperationRefusal {
        override val message: String = "the built-in Standard Program cannot be deleted (§4)"
    }

    /**
     * A Program with an `IN_PROGRESS` session may not be deleted (§29).
     *
     * The deletion is refused *before* any row is removed, and the selection is not cleared to make
     * it succeed — the rule's point is that a workout in flight has an owner, and removing the owner
     * from underneath it is not an option the delete path may take.
     */
    data class HasInProgressSession(val programId: ProgramId) : ProgramOperationRefusal {
        override val message: String =
            "a Program with an IN_PROGRESS session cannot be deleted; finish or cancel the session " +
                "first (§29)"
    }

    /**
     * An archived Program's selection must move before the archive takes effect — archiving the
     * selected Program requires choosing another one (§3).
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

    /** A Program in a lifecycle that does not support this operation (§3). */
    data class IllegalTransition(val reason: ProgramTransitionResult.Refused) :
        ProgramOperationRefusal {
        override val message: String = reason.reason
    }

    /** A structural edit must go through the revision mechanism; this operation is not one (§6). */
    data object StructuralChangeNeedsARevision : ProgramOperationRefusal {
        override val message: String =
            "a structural change is saved as a new immutable Revision through ProgramPlanRepository (§6)"
    }
}

/**
 * Runs [block], mapping anything the storage layer threw onto [ProgramOperationResult.Failure] rather
 * than absorbing it (§28's `SYSTEM_FAILURE`, §33's prohibition on turning a failure into an empty
 * result). A [ProgramOperationRefusal] is *not* a failure, so a caller that has already computed one
 * returns it directly instead of going through this.
 */
internal inline fun <T> storageOutcome(block: () -> T): ProgramOperationResult<T> = try {
    ProgramOperationResult.Success(block())
} catch (failure: Throwable) {
    ProgramOperationResult.Failure(failure)
}
