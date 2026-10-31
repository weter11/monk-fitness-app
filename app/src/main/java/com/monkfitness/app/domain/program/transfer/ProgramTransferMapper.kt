package com.monkfitness.app.domain.program.transfer

import com.monkfitness.app.domain.common.ProgramDayId
import com.monkfitness.app.domain.common.ProgramExerciseId
import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.common.RevisionId
import com.monkfitness.app.domain.prescription.Prescription
import com.monkfitness.app.domain.prescription.RepPrescription
import com.monkfitness.app.domain.prescription.TimePrescription
import com.monkfitness.app.domain.program.DraftIdSource
import com.monkfitness.app.domain.program.ExercisePreference
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
 * The Import/Export Mapper: between the transfer model and the domain, in both directions.
 *
 * [documentOf] takes the **Program and its current revision** and nothing else — not a repository, a
 * session, a progress fact or an adaptive row — and the absence of those collaborators is what makes the
 * prohibitions on exporting history and runtime state hold. What it reads is narrower still: the
 * Program's name and description, and the revision's mode, duration, schedule, focus, days,
 * prescriptions, authorship and pinning.
 *
 * [draftOf] produces a [ProgramEditorDraft] — the editor's own "review an import" entry point
 * (`programId = null, baseRevisionId = null`) — so a screen can inspect it, and it **acquires no
 * persistent identity merely because it was parsed**. The day and element identities it carries are the
 * draft's working handles, minted from the injected source exactly as the editor mints them, and
 * [revisionOf] re-identifies every one of them so none is ever persisted.
 *
 * The import re-identifies its own revision because `ProgramEditorService.mintRevision` is private, and
 * because the editor's creation path would be wrong here anyway: it sets `ProgramSource.USER`, sets no
 * planned start date and writes no initial slots, while an imported Program must be `IMPORTED` and be
 * created as Program + Revision + initial Slots in one unit. Both paths are held to one behaviour by
 * `require(revision.structure == plan.structure)` — the claim that re-identifying a plan changes nothing
 * else about it.
 */
object ProgramTransferMapper {

    /**
     * The transferable definition of [program] as [revision] describes it. The two arguments are the
     * Program's own facts and the revision its pointer names, and there is no third: this function cannot
     * see a session, a statistic or an adaptive row, because nothing here holds one.
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
                },
                // The user's preference, carried verbatim in the user's own order. Not re-sorted and not
                // filtered against the plan — a preferred exercise the exported plan happens not to use
                // today is still the exercise the user asked for first.
                preferredExercises = revision.preferredExercises.exerciseIds
            )
        )

    /**
     * The **Import Draft**: the editor draft a document describes.
     *
     * Its day identities are minted in document order, so the draft's days are addressable by the editor's
     * own operations while the user reviews them — and every one of them is discarded at [revisionOf]. The
     * identities are the only thing minted here; nothing is written and no repository is consulted.
     *
     * The document this is called with must have passed [ProgramTransferValidation]: the values it builds
     * are the domain's, and a domain value refuses an invalid plan in its own constructors.
     */
    fun draftOf(document: ProgramTransferDocument, ids: DraftIdSource): ProgramEditorDraft =
        ProgramEditorDraft(
            // No Program and no base revision: this draft creates a Program, which is the editor's entry
            // point for creating one and the one an import review uses.
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
            focus = focusOf(document.revision.focus),
            preferredExercises = ExercisePreference(document.revision.preferredExercises)
        )

    /**
     * The revision a save persists for [plan]: the draft's plan, **re-identified**.
     *
     * A new revision identity, a new identity for every day and for every plan element — and nothing else
     * changes. That is what makes an imported Program's graph entirely its own: no identity in it came
     * from the file (the file has none), none from a source Program, and none from the draft's working
     * handles (they are replaced here). The `require` is the guard: if re-identifying a plan ever changed
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
            // An imported Program's first revision is revision 1, whatever the file was written from. A
            // revision *number* is a human-readable ordinal and never an identity, so no history can be
            // inferred from it and none is carried.
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
            focus = renumbered.focus,
            preferredExercises = renumbered.preferredExercises
        )
        require(revision.structure == renumbered.structure) {
            "re-identifying a plan changes nothing else about it: the minted revision and the draft it " +
                "came from disagree structurally"
        }
        return revision
    }

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
