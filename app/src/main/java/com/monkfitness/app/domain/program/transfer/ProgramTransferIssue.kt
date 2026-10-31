package com.monkfitness.app.domain.program.transfer

import com.monkfitness.app.domain.prescription.PrescriptionDimension
import com.monkfitness.app.domain.program.ProgramDraftIssue

/**
 * One reason a transferred document is not a valid program definition — with the place in the document
 * it was found.
 *
 * Schema and semantic validation answer different questions ("is this the shape the format defines?" and
 * "is what it says a program this app can hold?") and are separate rejection cases at the boundary, but
 * they are one issue type here: both are the same kind of finding — a place, and what is wrong with it —
 * and a UI renders them the same way.
 *
 * An issue is **not a repair**: there is no corrected value, no substituted exercise, no reordered
 * weekday list and no clamped percentage anywhere in this file. The import's answer to a document it does
 * not accept is the list of what is wrong with it.
 *
 * Every issue names its [path] as a JSON path (`revision.days[2].exercises[0].prescription`), because a
 * finding about a file has to say *where* in the file.
 */
sealed interface ProgramTransferIssue {

    val path: String

    val message: String

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
     * Refused rather than ignored: a document that carries a field this format does not define was
     * written by something that is not this format (or by a newer version that should have bumped
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

    /** A plan element that names no exercise. Exercises are never invented or substituted. */
    data class BlankExerciseId(override val path: String) : ProgramTransferIssue {
        override val message: String = "$path: the exercise id is blank"
    }

    /**
     * A prescription whose dimension the target model cannot hold. The domain names five dimensions and
     * implements two: `SET_BASED`, `DIFFICULTY_BASED` and `REST_BASED` have no subtype, so a document
     * prescribing in one of them describes a plan this app cannot represent.
     */
    data class UnsupportedPrescriptionDimension(
        override val path: String,
        val dimension: PrescriptionDimension
    ) : ProgramTransferIssue {
        override val message: String =
            "$path: a ${dimension.name} prescription is not a dimension this app prescribes in yet (§10)"
    }

    data class EmptyPrescription(override val path: String) : ProgramTransferIssue {
        override val message: String = "$path: the prescription composes no set"
    }

    data class NonPositiveTarget(
        override val path: String,
        val target: Int
    ) : ProgramTransferIssue {
        override val message: String = "$path: a prescribed set asks for $target, not for work"
    }

    data class BlankDayName(override val path: String) : ProgramTransferIssue {
        override val message: String = "$path: a named day has a blank name; an unnamed day states none"
    }

    data class RestDayWithExercises(
        override val path: String,
        val exercises: Int
    ) : ProgramTransferIssue {
        override val message: String =
            "$path: a REST day prescribes nothing, and this one plans $exercises element(s) (§20)"
    }

    data class InvalidDuration(
        override val path: String,
        val reason: String
    ) : ProgramTransferIssue {
        override val message: String = "$path: $reason"
    }

    data class InvalidSchedule(
        override val path: String,
        val reason: String
    ) : ProgramTransferIssue {
        override val message: String = "$path: $reason"
    }

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
     * is decided by [ProgramTransferValidation] before anything is built, so a domain `require` firing
     * here means a rule was missing above — and the honest answer to a file that produced one is still a
     * finding about the file rather than a system failure, because the user picked it and the app did not.
     */
    data class InvalidDefinition(
        override val path: String,
        val reason: String
    ) : ProgramTransferIssue {
        override val message: String = "$path: $reason"
    }

    /**
     * The draft this document produced is not one the domain would let the editor save.
     *
     * The findings are the domain's own ([ProgramDraftIssue]) rather than a second copy of the rules, and
     * they cover the rules the *transfer* cannot state because they are about a plan as a whole: no name,
     * no days, days numbered out of order, a work day that plans nothing. Carrying them means an import
     * can never produce a draft that `Save` would refuse afterwards — the validation happens before
     * anything is written.
     */
    data class PlanNotSavable(val findings: List<ProgramDraftIssue>) : ProgramTransferIssue {

        override val path: String = "revision"

        override val message: String =
            "this document does not describe a program that can be saved: " +
                findings.joinToString("; ") { finding -> finding.message }
    }
}
