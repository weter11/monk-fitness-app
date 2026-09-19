package com.monkfitness.app.domain.usecase

import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.program.Focus
import com.monkfitness.app.domain.program.FocusAllocation
import com.monkfitness.app.domain.program.FocusPlan
import com.monkfitness.app.domain.program.Program
import com.monkfitness.app.domain.program.ProgramDuration
import com.monkfitness.app.domain.program.ProgramMode
import com.monkfitness.app.domain.program.ProgramRevision
import com.monkfitness.app.domain.program.structure
import com.monkfitness.app.domain.program.ProgramSchedule
import com.monkfitness.app.domain.program.ProgramSource
import com.monkfitness.app.domain.program.StandardProgram
import com.monkfitness.app.domain.program.transfer.ProgramTransferFile
import com.monkfitness.app.domain.program.transfer.ProgramTransferResult
import java.time.DayOfWeek
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §22 — the round-trip identity test: **one of P13's primary acceptance tests**.
 *
 * ```text
 * Program A
 *   ↓ export
 * JSON
 *   ↓ import
 * Program B
 * ```
 *
 * and then, in one place, everything that must be true of the pair at once:
 *
 * ```text
 * the definition       A's transferable definition == B's — proven at its strongest, by the two files
 *                      being byte-identical, which is what §11's determinism makes meaningful
 * the identity         A.programId != B.programId, revision ids differ, every day id differs, every
 *                      occurrence id differs, and no id in B is an id of A
 * A's history          unchanged: same rows, same values, same slots, same session, same sets
 * B's history          empty: no session, no set, no adaptive row, no second revision
 * A's adaptive state   unchanged
 * B's adaptive state   empty
 * A's selection        unchanged unless the import explicitly asked to take it
 * ```
 *
 * The round trip is run over the fixture's **whole shape space** rather than over one plan: a `MANUAL`
 * fixed-weekday program built for a `CUSTOM` focus, a `GENERATED` indefinite one with a flexible
 * frequency and no stated focus, and one built around named focuses. If a variant's configuration were
 * lossy, the byte comparison would find it — and a round trip that only worked for one shape would be a
 * round trip that worked by accident.
 */
class ProgramRoundTripTest {

    private val rig = ProgramTransferRig("roundtrip")

    @After
    fun tearDown() = rig.close()

    @Test
    fun theDefinitionSurvivesTheRoundTripByteForByteInEveryShapeTheFormatHas() = runBlocking {
        val shapes = listOf(
            "manual, fixed, fixed weekdays, custom focus" to Shape(
                mode = ProgramMode.MANUAL,
                duration = ProgramDuration.FixedDays(30),
                schedule = ProgramSchedule.FixedWeekdays(
                    setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)
                ),
                focus = FocusPlan.custom(
                    listOf(FocusAllocation(Focus.PUSH, 60), FocusAllocation(Focus.LEGS, 40))
                )
            ),
            "generated, indefinite, flexible frequency, no stated focus" to Shape(
                mode = ProgramMode.GENERATED,
                duration = ProgramDuration.Indefinite,
                schedule = ProgramSchedule.FlexiblePerWeek(4),
                focus = FocusPlan.DEFAULT
            ),
            "manual, fixed, one weekday, focused" to Shape(
                mode = ProgramMode.MANUAL,
                duration = ProgramDuration.FixedDays(14),
                schedule = ProgramSchedule.FixedWeekdays(setOf(DayOfWeek.SATURDAY)),
                focus = FocusPlan.focused(listOf(Focus.PULL, Focus.CORE))
            )
        )

        val source = rig.storeSourceProgram()

        shapes.forEachIndexed { index, (description, shape) ->
            // Each shape is one more revision of the same Program, so each one becomes the current
            // configuration an export carries (§17) — and the Program's identity stays where it was.
            rig.storeRevision(
                source = source,
                revisionNumber = index + 2,
                mode = shape.mode,
                duration = shape.duration,
                schedule = shape.schedule,
                focus = shape.focus
            )

            val exportedFromA = rig.exported(source.program.programId)
            val imported = rig.saved(exportedFromA.bytes)
            val exportedFromB = rig.exported(imported.programId)

            assertTrue(
                "$description: the definition is the file, so the round trip is byte-for-byte — a " +
                    "difference here would be a fact the format drops or invents",
                exportedFromA.hasTheSameBytesAs(exportedFromB)
            )
            assertEquals(
                "$description: and the imported Program's own name and description came across",
                source.program.name to source.program.description,
                imported.name to imported.description
            )
        }
    }

    @Test
    fun everyIdentityDiffersWhileEveryDefinedFactIsEqual() = runBlocking {
        val source = rig.storeSourceProgram()
        val sourceRevision = source.revision
        val exported = rig.exported(source.program.programId)

        val imported = rig.saved(exported.bytes)
        val importedRevision = rig.currentRevision(imported.programId)!!

        assertTrue("A.programId != B.programId", source.program.programId != imported.programId)
        assertTrue("A.revisionId != B.revisionId", sourceRevision.revisionId != importedRevision.revisionId)
        assertEquals(
            "every day id differs, and the imported revision has as many days as A's plan",
            sourceRevision.days.size,
            importedRevision.days.size
        )
        assertTrue(
            "no day id of A is a day id of B",
            importedRevision.days.map { it.programDayId }
                .intersect(sourceRevision.days.map { it.programDayId }.toSet())
                .isEmpty()
        )
        val sourceOccurrences = sourceRevision.days.flatMap { day ->
            day.exercises.map { element -> element.programExerciseId }
        }
        val importedOccurrences = importedRevision.days.flatMap { day ->
            day.exercises.map { element -> element.programExerciseId }
        }
        assertEquals(
            "and as many occurrences, in the same order",
            sourceOccurrences.size,
            importedOccurrences.size
        )
        assertTrue(
            "with no occurrence id shared between the two Programs",
            importedOccurrences.toSet().intersect(sourceOccurrences.toSet()).isEmpty()
        )
        assertTrue(
            "and no id of the imported graph mentions the source's identity",
            importedRevision.days.none { day ->
                day.programDayId.value.contains(source.program.programId.value)
            } && importedOccurrences.none { occurrence ->
                occurrence.value.contains(source.program.programId.value)
            }
        )

        assertEquals(
            "what the format defines is equal: the two revisions' structures are one structure",
            sourceRevision.structureForTest(),
            importedRevision.structureForTest()
        )
    }

    @Test
    fun theSourcesHistoryAndAdaptiveStateAreUntouchedAndTheImportsAreEmpty() = runBlocking {
        val source = rig.storeSourceProgram()
        rig.startSessionOnSource()
        rig.seedAdaptiveStateOnSource()
        val sourceRowsBefore = listOf(
            rig.rowsOfProgram("workout_session", rig.sourceProgramId),
            rig.rowsOfSetLogs(rig.sourceProgramId),
            rig.rowsOfRevision("program_family_progression_state", source.revision.revisionId),
            rig.rowsOfProgram("program_adaptive_decision_record", rig.sourceProgramId),
            rig.rowsOfProgram("adaptive_adjustment", rig.sourceProgramId),
            rig.rowsOfProgram("program_workout_slot", rig.sourceProgramId)
        )
        assertTrue(
            "the fixture really does carry history and adaptive state: a non-empty source is what makes " +
                "the empty side of this test mean something",
            sourceRowsBefore.all { count -> count > 0 }
        )

        val exported = rig.exported(source.program.programId)
        val imported = rig.saved(exported.bytes)
        val importedRevision = rig.currentRevision(imported.programId)!!

        assertEquals(
            "A's history and adaptive state are exactly what they were",
            sourceRowsBefore,
            listOf(
                rig.rowsOfProgram("workout_session", rig.sourceProgramId),
                rig.rowsOfSetLogs(rig.sourceProgramId),
                rig.rowsOfRevision("program_family_progression_state", source.revision.revisionId),
                rig.rowsOfProgram("program_adaptive_decision_record", rig.sourceProgramId),
                rig.rowsOfProgram("adaptive_adjustment", rig.sourceProgramId),
                rig.rowsOfProgram("program_workout_slot", rig.sourceProgramId)
            )
        )
        assertEquals(
            "B's history and adaptive state are empty: nothing historical crosses a transfer (§15, §16)",
            listOf(0, 0, 0, 0, 0),
            listOf(
                rig.rowsOfProgram("workout_session", imported.programId),
                rig.rowsOfSetLogs(imported.programId),
                rig.rowsOfRevision("program_family_progression_state", importedRevision.revisionId),
                rig.rowsOfProgram("program_adaptive_decision_record", imported.programId),
                rig.rowsOfProgram("adaptive_adjustment", imported.programId)
            )
        )
        assertEquals(
            "and the only reason B has an empty adaptive state is that it has no rows at all — not that a " +
                "state was reconstructed and then cleared",
            0,
            rig.rowsOfRevision("program_family_progression_state", importedRevision.revisionId)
        )
        assertEquals("B has exactly one revision (§17)", 1, rig.revisionCount(imported.programId))
    }

    @Test
    fun theSelectionMovesOnlyWhenTheImportExplicitlyAsksForIt() = runBlocking {
        val source = rig.storeSourceProgram()
        rig.select(rig.sourceProgramId)

        val untouched = rig.saved(rig.exported(source.program.programId).bytes)
        assertEquals(
            "an import is not a selection (§9): the round trip left A selected and B nothing",
            source.program.programId,
            rig.selection()!!.selectedProgramId
        )
        assertTrue(untouched.programId != rig.selection()!!.selectedProgramId)

        val taken = rig.saved(rig.exported(source.program.programId).bytes, makeActive = true)
        assertEquals(
            "and the one explicit choice moves it — to B, and to nothing else",
            taken.programId,
            rig.selection()!!.selectedProgramId
        )
        assertFalse(taken.programId == source.program.programId)
    }

    @Test
    fun theTransferIsIdempotentSoASecondHandCopyIsTheSameProgram() = runBlocking {
        val source = rig.storeSourceProgram()
        val fromA = rig.exported(source.program.programId)

        val b = rig.saved(fromA.bytes)
        val fromB = rig.exported(b.programId)
        val c = rig.saved(fromB.bytes)
        val fromC = rig.exported(c.programId)

        assertTrue("A → B is the definition", fromA.hasTheSameBytesAs(fromB))
        assertTrue("and B → C adds nothing and loses nothing", fromB.hasTheSameBytesAs(fromC))
        assertTrue(
            "while the three Programs remain three Programs",
            setOf(source.program.programId, b.programId, c.programId).size == 3
        )
    }

    @Test
    fun anExportedStandardProgramBecomesAnIndependentImportThatIsNoLongerProtected() = runBlocking {
        rig.seedStandardProgram()

        val exported = rig.exported(StandardProgram.programId)
        val imported = rig.saved(exported.bytes)

        assertNotNull(
            "§10: the built-in Program may be shared, and what arrives is the user's own Program",
            imported.programId
        )
        assertEquals(ProgramSource.IMPORTED, imported.source)
        assertFalse(
            "so it is not protected by §4's copy-before-edit or delete rules",
            imported.source.isBuiltIn
        )
        assertEquals(
            "and the built-in Program is still the one it was",
            ProgramSource.STANDARD,
            rig.storedProgram(StandardProgram.programId)!!.source
        )
    }

    // ------------------------------------------------------------------ helpers

    /** One configuration to round-trip. */
    private data class Shape(
        val mode: ProgramMode,
        val duration: ProgramDuration,
        val schedule: ProgramSchedule,
        val focus: FocusPlan
    )

    /**
     * A revision's **transferable structure**, as one comparable value.
     *
     * It is the domain's own `structure` — mode, duration, schedule, focus, and the days with their
     * elements' exercise, prescription, authorship and pinning, with every identity dropped — which is
     * exactly the projection the format carries. Comparing it is how "every defined fact is equal" is
     * stated without listing the facts a second time.
     */
    private fun ProgramRevision.structureForTest() = structure

    private suspend fun ProgramTransferRig.exported(programId: ProgramId): ProgramTransferFile =
        when (val result = exportService.export(programId)) {
            is ProgramTransferResult.Success -> result.value
            else -> throw AssertionError("expected the export to succeed, was $result")
        }

    private suspend fun ProgramTransferRig.saved(bytes: ByteArray, makeActive: Boolean = false): Program {
        val draft = when (val reviewed = importService.review(bytes)) {
            is ProgramTransferResult.Success -> reviewed.value
            else -> throw AssertionError("expected the file to be accepted, was $reviewed")
        }
        return when (val result = importService.save(draft, makeActive)) {
            is ProgramTransferResult.Success -> result.value
            else -> throw AssertionError("expected the import to succeed, was $result")
        }
    }
}
