package com.monkfitness.app.domain.program.transfer

import com.monkfitness.app.domain.common.ProgramDayId
import com.monkfitness.app.domain.common.ProgramExerciseId
import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.common.RevisionId
import com.monkfitness.app.domain.prescription.Prescription
import com.monkfitness.app.domain.prescription.RepPrescription
import com.monkfitness.app.domain.prescription.TimePrescription
import com.monkfitness.app.domain.program.DraftIdSource
import com.monkfitness.app.domain.program.FocusPlan
import com.monkfitness.app.domain.program.Program
import com.monkfitness.app.domain.program.ProgramDay
import com.monkfitness.app.domain.program.ProgramDuration
import com.monkfitness.app.domain.program.ProgramEditorDraft
import com.monkfitness.app.domain.program.ProgramExercise
import com.monkfitness.app.domain.program.ProgramRevision
import com.monkfitness.app.domain.program.ProgramSchedule
import com.monkfitness.app.domain.program.structure
import com.monkfitness.app.domain.program.withRenumberedDays
import java.time.Instant

/**
 * §25's **Import/Export Mapper**: between the transfer model and the domain, in both directions.
 *
 * ```text
 * Program (domain)  → documentOf →  ProgramTransferDocument  → ProgramTransferJson → bytes
 * bytes → ProgramTransferReader → ProgramTransferDocument → draftOf → ProgramEditorDraft (domain)
 * ```
 *
 * ### What the export direction can and cannot see
 *
 * [documentOf] takes the **Program and its current revision** and nothing else. It is not handed a
 * repository, a session, a progress fact or an adaptive row, and it cannot reach one: the absence of the
 * collaborators is the guarantee that §15 and §16's prohibitions hold, in the same way §30 step 7's
 * Scheduler cannot create a session because it holds no session repository. What it *reads* is narrower
 * still — the Program's name and description, and the revision's mode, duration, schedule, focus, days,
 * prescriptions, authorship and pinning — which is the allowlist this stage's document names.
 *
 * ### What the import direction produces
 *
 * [draftOf] produces a [ProgramEditorDraft] — the §7 editor's own "review an import" entry point, which
 * its KDoc already names (`programId = null, baseRevisionId = null → create (or review an import)`). That
 * is the *Import Draft* §5's pipeline ends at and §7 requires: a value a screen can inspect (name,
 * description, mode, duration, schedule, goals/focus, day structure, exercise structure) that
 * **acquires no persistent identity merely because it was parsed**.
 *
 * The day and element identities it does carry are the draft's own working handles, minted from the
 * injected source exactly as the editor mints them, and they are *never persisted*: [revisionOf]
 * re-identifies every one of them (§6, §23), which is the same seam §30 step 6 established for a save.
 *
 * ### Why the import has its own re-identification
 *
 * `ProgramEditorService.mintRevision` is private, and reaching the editor's save from here would not work
 * even if it were not: the editor's creation path sets `ProgramSource.USER`, sets no planned start date
 * and writes no initial slots, while §9 requires the imported Program to be `IMPORTED` and §27/§8 require
 * its creation to be *"Program + Revision + initial Slots"* in one unit. So the import re-identifies its
 * own revision — five lines — and the two are held to one behaviour by the same guard the editor applies:
 * `require(revision.structure == plan.structure)`, the claim that re-identifying a plan changes nothing
 * else about it. A test asserts it for an imported Program and for an edited one alike.
 */
object ProgramTransferMapper {

    /**
     * The transferable definition of [program] as [revision] describes it.
     *
     * The two arguments are the Program's own facts and the revision its pointer names, and there is no
     * third: this function cannot see a session, a statistic or an adaptive row, because nothing here
     * holds one.
     */
    fun documentOf(program: Program, revision: ProgramRevision): ProgramTransferDocument =
        ProgramTransferDocument(
            formatVersion = ProgramTransferFormat.VERSION,
            name = program.name,
            description = program.description,
            revision = RevisionTransfer(
                mode = revision.mode,
                duration = transferOf(revision.duration),
                schedule = transferOf(revision.schedule),
                focus = transferOf(revision.focus),
                days = revision.days.map { day ->
                    DayTransfer(
                        type = day.type,
                        name = day.name,
                        exercises = day.exercises.map { element ->
                            ExerciseTransfer(
                                exerciseId = element.exerciseId,
                                prescription = PrescriptionTransfer(
                                    dimension = element.prescription.dimension,
                                    perSetTargets = element.prescription.perSetTargets
                                ),
                                origin = element.origin,
                                isPinned = element.isPinned
                            )
                        }
                    )
                }
            )
        )

    /**
     * The **Import Draft**: the editor draft a document describes.
     *
     * Its day identities are minted in document order, so the draft's days are addressable by the editor's
     * own operations while the user reviews them — and every one of them is discarded at [revisionOf].
     * The identities are the only thing minted here; nothing is written, and no repository is consulted.
     *
     * The document this is called with must have passed [ProgramTransferValidation]: the values it builds
     * are the domain's, and a domain value refuses an invalid plan in its own constructors.
     */
    fun draftOf(document: ProgramTransferDocument, ids: DraftIdSource): ProgramEditorDraft =
        ProgramEditorDraft(
            // No Program and no base revision: this draft creates a Program, which is §7's third entry
            // point and the one an import review uses.
            programId = null,
            baseRevisionId = null,
            name = document.name,
            description = document.description,
            mode = document.revision.mode,
            duration = durationOf(document.revision.duration),
            schedule = scheduleOf(document.revision.schedule),
            days = document.revision.days.mapIndexed { index, day ->
                ProgramDay(
                    programDayId = ProgramDayId(ids.newId()),
                    position = index + 1,
                    type = day.type,
                    name = day.name,
                    exercises = day.exercises.map { element ->
                        ProgramExercise(
                            programExerciseId = ProgramExerciseId(ids.newId()),
                            exerciseId = element.exerciseId,
                            prescription = prescriptionOf(element.prescription),
                            origin = element.origin,
                            isPinned = element.isPinned
                        )
                    }
                )
            },
            focus = focusOf(document.revision.focus)
        )

    /**
     * The revision a save persists for [plan]: the draft's plan, **re-identified** (§6, §23).
     *
     * A new revision identity, a new identity for every day and a new identity for every plan element —
     * and nothing else changes. That is what makes an imported Program's graph entirely its own: no
     * identity in it came from the file (the file has none), none from a source Program (an import reads
     * one document and no Program), and none from the draft's working handles (they are replaced here).
     *
     * The `require` is the second half of that sentence as a guard: if re-identifying a plan ever changed
     * its content, the save would fail loudly instead of persisting a plan nobody reviewed.
     */
    fun revisionOf(
        plan: ProgramEditorDraft,
        programId: ProgramId,
        at: Instant,
        ids: DraftIdSource
    ): ProgramRevision {
        val renumbered = plan.withRenumberedDays()
        val revision = ProgramRevision(
            revisionId = RevisionId(ids.newId()),
            programId = programId,
            // §17: an imported Program's first revision is revision 1, whatever the file was written
            // from. A revision *number* is a human-readable ordinal and never an identity, so no
            // history can be inferred from it and none is carried.
            revisionNumber = ProgramRevision.FIRST_REVISION_NUMBER,
            mode = renumbered.mode,
            duration = renumbered.duration,
            schedule = renumbered.schedule,
            days = renumbered.days.map { day ->
                day.copy(
                    programDayId = ProgramDayId(ids.newId()),
                    exercises = day.exercises.map { element ->
                        element.copy(programExerciseId = ProgramExerciseId(ids.newId()))
                    }
                )
            },
            createdAt = at,
            focus = renumbered.focus
        )
        require(revision.structure == renumbered.structure) {
            "re-identifying a plan changes nothing else about it: the minted revision and the draft it " +
                "came from disagree structurally"
        }
        return revision
    }

    // ------------------------------------------------------------------ the leaves, both directions

    private fun prescriptionOf(prescription: PrescriptionTransfer): Prescription =
        when (prescription.dimension) {
            com.monkfitness.app.domain.prescription.PrescriptionDimension.REP_BASED ->
                RepPrescription(prescription.perSetTargets)
            com.monkfitness.app.domain.prescription.PrescriptionDimension.TIME_BASED ->
                TimePrescription(prescription.perSetTargets)
            else -> throw IllegalArgumentException(
                "a ${prescription.dimension.name} prescription has no subtype in the target model (§10); " +
                    "this document was not validated before it was mapped"
            )
        }

    private fun durationOf(duration: DurationTransfer): ProgramDuration = when (duration) {
        is DurationTransfer.FixedDays -> ProgramDuration.FixedDays(duration.days)
        DurationTransfer.Indefinite -> ProgramDuration.Indefinite
    }

    private fun scheduleOf(schedule: ScheduleTransfer): ProgramSchedule = when (schedule) {
        is ScheduleTransfer.FixedWeekdays -> ProgramSchedule.FixedWeekdays(schedule.weekdays.toSet())
        is ScheduleTransfer.FlexiblePerWeek ->
            ProgramSchedule.FlexiblePerWeek(schedule.sessionsPerWeek)
    }

    private fun focusOf(focus: FocusTransfer): FocusPlan = when (focus) {
        FocusTransfer.Balanced -> FocusPlan.Balanced
        is FocusTransfer.Focused -> FocusPlan.Focused(focus.focuses)
        is FocusTransfer.Custom -> FocusPlan.Custom(
            focus.allocations.map { share ->
                com.monkfitness.app.domain.program.FocusAllocation(share.focus, share.percent)
            }
        )
    }

    /** The transferable definition of [focus] (§8): the three forms §8 names, each as itself. */
    private fun transferOf(focus: FocusPlan): FocusTransfer = when (focus) {
        is FocusPlan.Balanced -> FocusTransfer.Balanced
        is FocusPlan.Focused -> FocusTransfer.Focused(focus.focuses)
        is FocusPlan.Custom -> FocusTransfer.Custom(
            focus.allocations.map { allocation ->
                FocusShare(allocation.focus, allocation.percent)
            }
        )
    }
}
