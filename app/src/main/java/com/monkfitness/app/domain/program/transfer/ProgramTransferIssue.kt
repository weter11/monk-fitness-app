package com.monkfitness.app.domain.program.transfer

import com.monkfitness.app.domain.prescription.PrescriptionDimension
import com.monkfitness.app.domain.program.ProgramDraftIssue

/**
 * One reason a transferred document is not a valid program definition — with the place in the document
 * it was found.
 *
 * ### Two layers, one vocabulary
 *
 * §5's pipeline has two validations between the bytes and the draft, and they answer different
 * questions:
 *
 * ```text
 * schema validation      "is this document the shape the format defines?"      MissingField, WrongType,
 *                                                                              UnknownField, UnknownToken
 * semantic validation    "is what it says a program this app can hold?"        RestDayWithExercises,
 *                                                                              InvalidFocus, PlanNotSavable, …
 * ```
 *
 * They are separate rejection cases at the boundary ([ProgramTransferRejection.SchemaInvalid] and
 * [ProgramTransferRejection.SemanticallyInvalid]) and one issue type here, because both are the same
 * kind of finding — *a place, and what is wrong with it* — and a UI renders them the same way.
 *
 * ### What an issue is not
 *
 * It is **not a repair.** There is no corrected value, no substituted exercise, no reordered weekday
 * list and no clamped percentage anywhere in this file (§33: *"silently repair"* is prohibited). §5's
 * rule — *"do not silently repair malformed prescriptions … invalid schedule/focus/day structures"* —
 * is kept by having nothing to repair *with*: the import's answer to a document it does not accept is
 * the list of what is wrong with it.
 *
 * Every issue names its [path] as a JSON path (`revision.days[2].exercises[0].prescription`), because
 * a finding about a file has to say *where* in the file, and a count of exercises would not.
 */
sealed interface ProgramTransferIssue {

    /** Where in the document the finding is, as a JSON path. */
    val path: String

    /** The user-facing sentence. */
    val message: String

    // ------------------------------------------------------------------ the schema layer (§5)

    /** A field the schema requires is absent. */
    data class MissingField(override val path: String, val name: String) : ProgramTransferIssue {
        override val message: String = "$path: the required field \"$name\" is missing"
    }

    /** A field is present with a value of a kind the schema does not allow. */
    data class WrongType(
        override val path: String,
        val expected: String,
        val found: String
    ) : ProgramTransferIssue {
        override val message: String = "$path: expected $expected, found $found"
    }

    /**
     * A field the schema does not define.
     *
     * Refused rather than ignored, and that is §2's *"allowlist design, not a blacklist"* read as a rule
     * of the reader: a document that carries a field this format does not define is a document written
     * by something that is not this format (or by a newer version that should have bumped
     * `formatVersion`), and reading it "successfully" would mean guessing which parts of it were meant.
     */
    data class UnknownField(override val path: String, val name: String) : ProgramTransferIssue {
        override val message: String = "$path: the field \"$name\" is not part of this format"
    }

    /** A token whose value is not one of the ones the field admits. */
    data class UnknownToken(
        override val path: String,
        val token: String,
        val allowed: List<String>
    ) : ProgramTransferIssue {
        override val message: String =
            "$path: \"$token\" is not one of ${allowed.joinToString(", ")}"
    }

    // ------------------------------------------------------------------ the semantic layer (§6)

    /** A plan element that names no exercise. Exercises are never invented or substituted (§5). */
    data class BlankExerciseId(override val path: String) : ProgramTransferIssue {
        override val message: String = "$path: the exercise id is blank"
    }

    /**
     * A prescription whose dimension the target model cannot hold.
     *
     * §10 names five dimensions and implements two: `SET_BASED`, `DIFFICULTY_BASED` and `REST_BASED`
     * have no subtype, so a document that prescribes in one of them describes a plan this app cannot
     * represent — and §6 asks exactly this: *"prescription dimensions are supported by the target
     * model"*.
     */
    data class UnsupportedPrescriptionDimension(
        override val path: String,
        val dimension: PrescriptionDimension
    ) : ProgramTransferIssue {
        override val message: String =
            "$path: a ${dimension.name} prescription is not a dimension this app prescribes in yet (§10)"
    }

    /** A prescription that composes no set — a plan element with nothing to do. */
    data class EmptyPrescription(override val path: String) : ProgramTransferIssue {
        override val message: String = "$path: the prescription composes no set"
    }

    /** A set that asks for no work (a target of zero or less). */
    data class NonPositiveTarget(
        override val path: String,
        val target: Int
    ) : ProgramTransferIssue {
        override val message: String = "$path: a prescribed set asks for $target, not for work"
    }

    /** A day that carries a label and does not fill it. */
    data class BlankDayName(override val path: String) : ProgramTransferIssue {
        override val message: String = "$path: a named day has a blank name; an unnamed day states none"
    }

    /** A rest day that prescribes something (§20: a rest day prescribes no exercises). */
    data class RestDayWithExercises(
        override val path: String,
        val exercises: Int
    ) : ProgramTransferIssue {
        override val message: String =
            "$path: a REST day prescribes nothing, and this one plans $exercises element(s) (§20)"
    }

    /** A duration the domain cannot hold (§20). */
    data class InvalidDuration(
        override val path: String,
        val reason: String
    ) : ProgramTransferIssue {
        override val message: String = "$path: $reason"
    }

    /** A schedule the domain cannot hold (§20). */
    data class InvalidSchedule(
        override val path: String,
        val reason: String
    ) : ProgramTransferIssue {
        override val message: String = "$path: $reason"
    }

    /** A Goals & Focus configuration the domain cannot hold (§8). */
    data class InvalidFocus(
        override val path: String,
        val reason: String
    ) : ProgramTransferIssue {
        override val message: String = "$path: $reason"
    }

    /**
     * The document describes a value the domain's own type refuses to hold.
     *
     * This is the belt the semantic layer wears *beside* its own rules: every rule this stage knows about
     * is decided by [ProgramTransferValidation] before anything is built, so a domain `require` firing here
     * means a rule was missing above — and the honest answer to a file that produced one is still a finding
     * about the file rather than a system failure, because the user picked it and the app did not.
     */
    data class InvalidDefinition(
        override val path: String,
        val reason: String
    ) : ProgramTransferIssue {
        override val message: String = "$path: $reason"
    }

    /**
     * The draft this document produced is not one the domain would let the editor save (§7).
     *
     * The findings are the domain's own ([ProgramDraftIssue]) rather than a second copy of the rules, and
     * they cover the rules the *transfer* cannot state because they are about a plan as a whole: no name,
     * no days, days numbered out of order, a work day that plans nothing, a Program named without the
     * revision it was opened from. Carrying them means an import can never produce a draft that `Save`
     * would refuse afterwards — the validation happens before anything is written (§6).
     */
    data class PlanNotSavable(val findings: List<ProgramDraftIssue>) : ProgramTransferIssue {

        override val path: String = "revision"

        override val message: String =
            "this document does not describe a program that can be saved: " +
                findings.joinToString("; ") { finding -> finding.message }
    }
}
