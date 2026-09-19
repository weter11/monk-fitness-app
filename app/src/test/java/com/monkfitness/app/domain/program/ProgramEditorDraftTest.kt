package com.monkfitness.app.domain.program

import com.monkfitness.app.domain.common.ProgramDayId
import com.monkfitness.app.domain.common.ProgramExerciseId
import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.common.RevisionId
import com.monkfitness.app.domain.prescription.RepPrescription
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The editor draft is a separate concept from a revision, and this suite pins the separation in both
 * directions: what a draft can represent that a revision cannot, and what a revision owns that a draft
 * must not pretend to own.
 *
 * The reason the distinction is worth a test is that the two types would otherwise converge under
 * pressure — "the editor needs a revision id to autosave against", "the draft is basically a revision
 * with a flag" — and the moment they do, `Save` stops being the only thing that creates revisions.
 */
class ProgramEditorDraftTest {

    // ------------------------------------------------------------------ draft is not a revision

    @Test
    fun aDraftHasNoRevisionIdentityOfItsOwn() {
        val draftFields = ProgramEditorDraft::class.java.declaredFields
            .filterNot { it.isSynthetic || it.name.startsWith("$") }
            .map { it.name }
            .toSet()

        assertEquals(
            "the working facts of a draft, with the Goals & Focus configuration among them (§6, §8) " +
                "and no revision identity anywhere",
            setOf(
                "programId", "baseRevisionId", "name", "description", "mode", "duration",
                "schedule", "days", "focus"
            ),
            draftFields
        )
        assertFalse(
            "a draft is not committed, so it cannot own a revision id",
            draftFields.contains("revisionId")
        )
        assertTrue(
            "but it does remember the revision it was opened from",
            ProgramRevision::class.java.declaredFields.any { it.name == "revisionId" }
        )
    }

    @Test
    fun aDraftMayHoldAnIncompletePlanWhileARevisionMayNot() {
        val emptyDraft = ProgramEditorDraft()
        assertTrue(emptyDraft.days.isEmpty())
        assertEquals("", emptyDraft.name)

        // The same emptiness is a refusal for a revision: draft-first editing is what makes an
        // unfinished plan representable at all (§6, §7).
        assertRejects("a saved revision with no plan") {
            ProgramRevision(
                revisionId = RevisionId("rev-1"),
                programId = ProgramId("program-1"),
                revisionNumber = 1,
                mode = ProgramMode.MANUAL,
                duration = ProgramDuration.FixedDays(28),
                schedule = ProgramSchedule.FlexiblePerWeek(3),
                days = emptyList(),
                createdAt = java.time.Instant.parse("2026-09-18T09:00:00Z")
            )
        }
    }

    // ------------------------------------------------------------------ the four entry points

    @Test
    fun theDraftItselfSaysWhichEntryPointOpenedIt() {
        val create = ProgramEditorDraft()
        assertTrue(create.isNewProgram)
        assertFalse(create.isBasedOnASavedRevision)
        assertFalse(create.editsExistingProgram)

        val copy = ProgramEditorDraft(baseRevisionId = RevisionId("rev-1"))
        assertTrue(copy.isNewProgram)
        assertTrue(copy.isBasedOnASavedRevision)
        assertFalse(copy.editsExistingProgram)

        val edit = ProgramEditorDraft(
            programId = ProgramId("program-1"),
            baseRevisionId = RevisionId("rev-1")
        )
        assertFalse(edit.isNewProgram)
        assertTrue(edit.editsExistingProgram)

        // Reviewing an import is a create: the file brings a program definition, never an identity.
        val imported = ProgramEditorDraft(name = "Imported program")
        assertTrue(imported.isNewProgram)
        assertNull(imported.baseRevisionId)
    }

    // ------------------------------------------------------------------ manual and generated alike

    @Test
    fun aManualDraftAndAGeneratedDraftAreBothRepresentable() {
        val manual = ProgramEditorDraft(mode = ProgramMode.MANUAL, days = listOf(day()))
        assertFalse(manual.isGenerated)

        val generated = ProgramEditorDraft(
            mode = ProgramMode.GENERATED,
            days = listOf(
                day(
                    elements = listOf(
                        element(id = "element-1"),
                        element(id = "element-2").copy(isPinned = true),
                        element(id = "element-3")
                            .copy(origin = ProgramExerciseOrigin.USER_AUTHORED)
                    )
                )
            )
        )

        assertTrue(generated.isGenerated)
        // Reconciliation is representable on the draft: a generated element, a pinned one and one the
        // user authored are all distinguishable before anything is saved (§7).
        val elements = generated.days.single().exercises
        assertEquals(3, elements.size)
        assertTrue(elements.any { it.isPinned })
        assertTrue(elements.any { it.origin == ProgramExerciseOrigin.USER_AUTHORED })
        assertTrue(elements.any { it.origin == ProgramExerciseOrigin.GENERATED && !it.isPinned })
    }

    @Test
    fun switchingTheModeIsTheDraftsOwnProperty() {
        val opened = ProgramEditorDraft(
            programId = ProgramId("program-1"),
            baseRevisionId = RevisionId("rev-1"),
            mode = ProgramMode.MANUAL
        )

        val switched = opened.copy(mode = ProgramMode.GENERATED)

        assertEquals(ProgramMode.MANUAL, opened.mode)
        assertTrue(switched.isGenerated)
        assertEquals(opened.baseRevisionId, switched.baseRevisionId)
    }

    // ------------------------------------------------------------------ helpers

    private fun element(
        id: String,
        exerciseId: String = "pushups"
    ) = ProgramExercise(
        programExerciseId = ProgramExerciseId(id),
        exerciseId = exerciseId,
        prescription = RepPrescription(listOf(10, 8)),
        origin = ProgramExerciseOrigin.GENERATED
    )

    private fun day(elements: List<ProgramExercise> = listOf(element(id = "element-1"))) = ProgramDay(
        programDayId = ProgramDayId("day-1"),
        position = 1,
        type = ProgramDayType.TRAINING,
        name = null,
        exercises = elements
    )

    private fun assertRejects(what: String, block: () -> Any) {
        try {
            block()
            throw AssertionError("$what must not be constructible")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message?.isNotBlank() == true)
        }
    }
}
