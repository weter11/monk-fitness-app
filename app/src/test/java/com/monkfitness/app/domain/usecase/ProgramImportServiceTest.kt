package com.monkfitness.app.domain.usecase

import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.common.RevisionId
import com.monkfitness.app.domain.program.AppState
import com.monkfitness.app.domain.program.LifecycleStatus
import com.monkfitness.app.domain.program.ProgramOperationResult
import com.monkfitness.app.domain.program.Program
import com.monkfitness.app.domain.program.ProgramMode
import com.monkfitness.app.domain.program.ProgramSource
import com.monkfitness.app.domain.program.StandardProgram
import com.monkfitness.app.domain.program.transfer.ProgramImportDraft
import com.monkfitness.app.domain.program.transfer.ProgramTransferFile
import com.monkfitness.app.domain.program.transfer.ProgramTransferFixture
import com.monkfitness.app.domain.program.transfer.ProgramTransferFormat
import com.monkfitness.app.domain.program.transfer.ProgramTransferIssue
import com.monkfitness.app.domain.program.transfer.ProgramTransferReader
import com.monkfitness.app.domain.program.transfer.ProgramTransferRejection
import com.monkfitness.app.domain.program.transfer.ProgramTransferResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §5's import half, §6's validation, §9's identity and selection rules, and §27's creation unit — driven
 * against the real SQLite engine through the production repositories, the production Scheduler and the
 * production selection owner.
 *
 * ```text
 * the pipeline        bytes → parse → formatVersion → schema → exerciseId → semantic → Import Draft
 * the save            Program + Revision + ProgramDays + ProgramExercises + initial Slots, in one unit
 * the identities      fresh, fresh, fresh — and nothing that came from the file or the draft's handles
 * the ownership       IMPORTED, NOT_STARTED, planned to start the day it arrived, not selected unless asked
 * the absences        no session, no set, no adaptive row, no second revision, no source-side change
 * ```
 *
 * Every refusal test states one thing wrong with one document and asserts *which* answer came back — the
 * point of §13's contract is not that an import fails but that it fails with the right name.
 */
class ProgramImportServiceTest {

    private val rig = ProgramTransferRig("import")

    @After
    fun tearDown() = rig.close()

    // ================================================================ the pipeline (§5)

    @Test
    fun aValidFileIsReviewedIntoADraftBeforeAnythingIsWritten() = runBlocking {
        val before = rig.tableCounts()

        val draft = rig.review(ProgramTransferFixture.VALID_BYTES)
        val document = ProgramTransferReader.read(ProgramTransferFixture.VALID_DOCUMENT)

        assertEquals(1, draft.formatVersion)
        assertEquals("Imported strength", draft.name)
        assertEquals("a program that arrived as a file", draft.description)
        assertEquals(ProgramMode.MANUAL, draft.mode)
        assertEquals(3, draft.days.size)
        assertEquals("four plan elements, one of them the same exercise twice", 4, draft.exerciseCount)
        assertEquals("and thirteen sets across them", 13, draft.setCount)
        assertEquals(
            "the draft the review produces is the domain's own editor draft, describing the file",
            document.revision.days.map { day -> day.exercises.size },
            draft.days.map { day -> day.exercises.size }
        )
        assertEquals(
            "reviewing writes nothing at all: an import that is never accepted leaves no trace",
            before,
            rig.tableCounts()
        )
    }

    @Test
    fun theSameFileIsReviewedIntoTheSameDraftEveryTime() = runBlocking {
        val first = rig.review(ProgramTransferFixture.VALID_BYTES)
        val second = rig.review(ProgramTransferFixture.VALID_BYTES)

        assertEquals(
            "the draft's content is a function of the document",
            first.days.map { day -> day.type.name to day.exercises.map { element -> element.exerciseId } },
            second.days.map { day -> day.type.name to day.exercises.map { element -> element.exerciseId } }
        )
        assertEquals(first.setCount, second.setCount)
        assertTrue(
            "and its working handles are minted, not carried: they differ between two reviews and are " +
                "never persisted",
            first.days.map { it.programDayId } != second.days.map { it.programDayId }
        )
    }

    // ------------------------------------------------------------------ the refusals, one each

    @Test
    fun malformedJsonIsRejectedAsNotAProgramFile() = runBlocking {
        val rejection = rig.rejectionOf(ProgramTransferFixture.VALID_DOCUMENT.dropLast(40).toByteArray())

        assertTrue("$rejection", rejection is ProgramTransferRejection.NotAProgramFile)
    }

    @Test
    fun bytesThatAreNotUtf8AreRejectedAtTheEncodingBoundary() = runBlocking {
        val broken = ProgramTransferFixture.VALID_BYTES.toMutableList().apply {
            set(10, 0xC3.toByte())
            add(11, 0x28)
        }.toByteArray()

        val rejection = rig.rejectionOf(broken)

        assertTrue(
            "§5's UTF-8 is enforced rather than assumed: $rejection",
            rejection is ProgramTransferRejection.NotAProgramFile &&
                rejection.reason.contains("UTF-8")
        )
    }

    @Test
    fun aFileThatIsNotThisFormatsFileIsRejected() = runBlocking {
        val rejection = rig.rejectionOf(
            ProgramTransferFixture.editedBytes("\"monkfitness.program\"", "\"some.other.format\"")
        )

        assertTrue("$rejection", rejection is ProgramTransferRejection.NotAProgramFile)
    }

    @Test
    fun aMissingFormatVersionIsARequiredFieldFailure() = runBlocking {
        val rejection = rig.rejectionOf(ProgramTransferFixture.editedBytes("\"formatVersion\": 1,", ""))

        assertTrue(
            "a required field the document does not carry: $rejection",
            rejection is ProgramTransferRejection.SchemaInvalid &&
                rejection.issues.any { issue -> issue is ProgramTransferIssue.MissingField && issue.name == "formatVersion" }
        )
    }

    @Test
    fun anUnsupportedFormatVersionIsItsOwnAnswer() = runBlocking {
        val rejection = rig.rejectionOf(
            ProgramTransferFixture.editedBytes("\"formatVersion\": 1", "\"formatVersion\": 7")
        ) as? ProgramTransferRejection.UnsupportedFormatVersion

        assertEquals(
            "a version this reader does not know is refused as a version, not as a bag of schema findings",
            7,
            rejection?.found
        )
        assertEquals("and the version this app reads is stated with it", 1, rejection?.supported)
    }

    @Test
    fun aMissingRequiredFieldIsRejected() = runBlocking {
        val rejection = rig.rejectionOf(ProgramTransferFixture.editedBytes("\"name\": \"Imported strength\",", ""))

        assertTrue(
            "$rejection",
            rejection is ProgramTransferRejection.SchemaInvalid &&
                rejection.issues.any { issue -> issue is ProgramTransferIssue.MissingField && issue.name == "name" }
        )
    }

    @Test
    fun aFieldTheFormatDoesNotDefineIsRejected() = runBlocking {
        val rejection = rig.rejectionOf(
            ProgramTransferFixture.editedBytes(
                "\"formatVersion\": 1,",
                "\"formatVersion\": 1,\n  \"programId\": \"program-secret\","
            )
        )

        assertTrue(
            "an exported file can never carry an identity, and a file that claims one is refused: $rejection",
            rejection is ProgramTransferRejection.SchemaInvalid &&
                rejection.issues.any { issue -> issue is ProgramTransferIssue.UnknownField && issue.name == "programId" }
        )
    }

    @Test
    fun anUnknownExerciseIsRejectedAndNothingIsImported() = runBlocking {
        val before = rig.tableCounts()

        val rejection = rig.rejectionOf(
            ProgramTransferFixture.editedBytes("\"exerciseId\": \"plank\"", "\"exerciseId\": \"invented_exercise\"")
        )

        assertEquals(
            "the unknown id is named, so the user knows what their file needs (§5)",
            listOf("invented_exercise"),
            (rejection as? ProgramTransferRejection.UnknownExercises)?.exerciseIds
        )
        assertEquals(
            "and nothing was invented, substituted, dropped or written",
            before,
            rig.tableCounts()
        )
    }

    @Test
    fun severalUnknownExercisesAreReportedTogether() = runBlocking {
        val rejection = rig.rejectionOf(
            ProgramTransferFixture.VALID_DOCUMENT
                .replace("\"exerciseId\": \"pushups\"", "\"exerciseId\": \"nope_one\"")
                .replace("\"exerciseId\": \"plank\"", "\"exerciseId\": \"nope_two\"")
                .let { ProgramTransferFormat.encode(it) }
        )

        assertEquals(
            "every id it could not resolve, each once",
            listOf("nope_one", "nope_two"),
            (rejection as? ProgramTransferRejection.UnknownExercises)?.exerciseIds
        )
    }

    @Test
    fun anInvalidPrescriptionIsRejected() = runBlocking {
        val rejection = rig.rejectionOf(
            ProgramTransferFixture.editedBytes("\"perSetTargets\": [12, 10, 8, 6]", "\"perSetTargets\": [12, 0]")
        )

        assertTrue(
            "a set that asks for no work is not a prescription: $rejection",
            rejection is ProgramTransferRejection.SemanticallyInvalid &&
                rejection.issues.any { issue -> issue is ProgramTransferIssue.NonPositiveTarget }
        )
    }

    @Test
    fun anUnsupportedPrescriptionDimensionIsRejected() = runBlocking {
        val rejection = rig.rejectionOf(
            ProgramTransferFixture.editedBytes("\"dimension\": \"TIME_BASED\"", "\"dimension\": \"SET_BASED\"")
        )

        assertTrue(
            "§10 names five dimensions and implements two: $rejection",
            rejection is ProgramTransferRejection.SemanticallyInvalid &&
                rejection.issues.any { issue -> issue is ProgramTransferIssue.UnsupportedPrescriptionDimension }
        )
    }

    @Test
    fun anInvalidRestDayIsRejected() = runBlocking {
        val rejection = rig.rejectionOf(
            ProgramTransferFixture.editedBytes(
                """{ "type": "REST", "exercises": [] }""",
                """{ "type": "REST", "exercises": [ { "exerciseId": "plank", "prescription": { "dimension": "TIME_BASED", "perSetTargets": [30] }, "origin": "USER_AUTHORED", "pinned": false } ] }"""
            )
        )

        assertTrue(
            "§20's rest day prescribes nothing: $rejection",
            rejection is ProgramTransferRejection.SemanticallyInvalid &&
                rejection.issues.any { issue -> issue is ProgramTransferIssue.RestDayWithExercises }
        )
    }

    @Test
    fun anInvalidFocusIsRejected() = runBlocking {
        val rejection = rig.rejectionOf(
            ProgramTransferFixture.editedBytes("\"percent\": 60", "\"percent\": 61")
        )

        assertTrue(
            "§8's shares must sum to a hundred: $rejection",
            rejection is ProgramTransferRejection.SemanticallyInvalid &&
                rejection.issues.any { issue -> issue is ProgramTransferIssue.InvalidFocus }
        )
    }

    @Test
    fun anInvalidScheduleIsRejected() = runBlocking {
        val rejection = rig.rejectionOf(
            ProgramTransferFixture.editedBytes(
                """{ "kind": "FIXED_WEEKDAYS", "weekdays": ["MONDAY", "WEDNESDAY", "FRIDAY"] }""",
                """{ "kind": "FLEXIBLE_PER_WEEK", "sessionsPerWeek": 9 }"""
            )
        )

        assertTrue(
            "the user's rhythm is stated within a week: $rejection",
            rejection is ProgramTransferRejection.SemanticallyInvalid &&
                rejection.issues.any { issue -> issue is ProgramTransferIssue.InvalidSchedule }
        )
    }

    @Test
    fun anInvalidModeIsRejected() = runBlocking {
        val rejection = rig.rejectionOf(
            ProgramTransferFixture.editedBytes("\"mode\": \"MANUAL\"", "\"mode\": \"SEMI_AUTO\"")
        )

        assertTrue(
            "§2's two modes are the whole vocabulary, and the token is read against it: $rejection",
            rejection is ProgramTransferRejection.SchemaInvalid &&
                rejection.issues.any { issue -> issue is ProgramTransferIssue.UnknownToken }
        )
    }

    @Test
    fun aPlanWithoutADayIsRejectedByTheDomainsOwnValidation() = runBlocking {
        val rejection = rig.rejectionOf(
            ProgramTransferFixture.document(days = "[]").let { ProgramTransferFormat.encode(it) }
        )

        assertTrue(
            "an empty plan is the domain's rule, reported with the domain's own finding: $rejection",
            rejection is ProgramTransferRejection.SemanticallyInvalid &&
                rejection.issues.any { issue ->
                    issue is ProgramTransferIssue.PlanNotSavable &&
                        issue.findings.any { finding ->
                            finding is com.monkfitness.app.domain.program.ProgramDraftIssue.NoPlan
                        }
                }
        )
    }

    @Test
    fun aBlankNameIsRejectedByTheDomainsOwnValidation() = runBlocking {
        val rejection = rig.rejectionOf(
            ProgramTransferFixture.editedBytes("\"Imported strength\"", "\"   \"")
        )

        assertTrue(
            "$rejection",
            rejection is ProgramTransferRejection.SemanticallyInvalid &&
                rejection.issues.any { issue ->
                    issue is ProgramTransferIssue.PlanNotSavable &&
                        issue.findings.any { finding ->
                            finding is com.monkfitness.app.domain.program.ProgramDraftIssue.BlankName
                        }
                }
        )
    }

    @Test
    fun aWorkDayThatPlansNothingIsRejectedByTheDomainsOwnValidation() = runBlocking {
        val rejection = rig.rejectionOf(
            ProgramTransferFixture.document(
                days = ProgramTransferFixture.PLAN_WITH_A_WORK_DAY_THAT_PLANS_NOTHING
            ).let { ProgramTransferFormat.encode(it) }
        )

        assertTrue(
            "§20's other half, decided by the domain and not restated by the transfer: $rejection",
            rejection is ProgramTransferRejection.SemanticallyInvalid &&
                rejection.issues.any { issue ->
                    issue is ProgramTransferIssue.PlanNotSavable &&
                        issue.findings.any { finding ->
                            finding is com.monkfitness.app.domain.program.ProgramDraftIssue.DayWithoutWork
                        }
                }
        )
    }

    // ================================================================ the save (§27)

    @Test
    fun savingCreatesTheProgramItsFirstRevisionAndItsInitialOpportunities() = runBlocking {
        val imported = rig.save(ProgramTransferFixture.VALID_BYTES)

        assertEquals("Imported strength", imported.name)
        assertEquals(ProgramSource.IMPORTED, imported.source)
        assertEquals(LifecycleStatus.NOT_STARTED, imported.lifecycleStatus)
        assertNull("an import is not a start", imported.actualStartDate)
        assertNull(imported.archivedAt)
        assertEquals(
            "every timestamp of the new graph is the clock's own reading (§18)",
            ProgramTransferFixture.importedAt(),
            imported.createdAt
        )
        assertEquals(imported.createdAt, imported.updatedAt)

        val revision = rig.currentRevision(imported.programId)
        assertNotNull("a Program always has a current revision (§23)", revision)
        assertEquals(
            "§17: an imported Program's first revision is revision 1",
            1,
            revision!!.revisionNumber
        )
        assertEquals(imported.programId, revision.programId)
        assertEquals(3, revision.days.size)
        assertEquals(listOf(3, 0, 1), revision.days.map { day -> day.exercises.size })

        val slots = rig.slotsOf(imported.programId)
        assertEquals(
            "a thirty-day Monday/Wednesday/Friday program starting on Monday 21 September is planned on " +
                "thirteen dates inside its own run (§20)",
            listOf(
                "2026-09-21", "2026-09-23", "2026-09-25", "2026-09-28", "2026-09-30",
                "2026-10-02", "2026-10-05", "2026-10-07", "2026-10-09", "2026-10-12",
                "2026-10-14", "2026-10-16", "2026-10-19"
            ),
            slots.map { slot -> slot.plannedFor.toString() }
        )
        assertEquals(
            "and the plan's own days cycle in order across them, starting at day one",
            listOf(1, 2, 3, 1, 2, 3, 1, 2, 3, 1, 2, 3, 1),
            slots.map { slot -> revision.days.single { it.programDayId == slot.programDayId }.position }
        )
        assertTrue(
            "every opportunity names the imported Program and the imported revision",
            slots.all { slot -> slot.programId == imported.programId && slot.revisionId == revision.revisionId }
        )
        assertEquals(
            "and all of them are open opportunities, none of them taken (§20)",
            listOf(com.monkfitness.app.domain.program.SlotStatus.PLANNED),
            slots.map { slot -> slot.status }.distinct()
        )
    }

    @Test
    fun anImportedProgramIsPlannedToStartOnTheDayItArrivedAndIsNotStarted() = runBlocking {
        val imported = rig.save(ProgramTransferFixture.VALID_BYTES)

        assertEquals(
            "the imported Program is planned to start the day it was imported — a plan, from the injected " +
                "clock and calendar, so §27's creation unit has an anchor to plan from",
            ProgramTransferFixture.IMPORTED_ON,
            imported.plannedStartDate
        )
        assertEquals(
            "and a planned start date starts nothing (§3): the lifecycle is NOT_STARTED and there is no " +
                "actual start",
            LifecycleStatus.NOT_STARTED,
            imported.lifecycleStatus
        )
        assertNull(imported.actualStartDate)
    }

    @Test
    fun everyIdentityIsFreshAndNoneComesFromTheDraftOrTheSource() = runBlocking {
        val source = rig.storeSourceProgram()
        val draft = rig.review(ProgramTransferFixture.VALID_BYTES)

        val imported = rig.saved(draft)
        val revision = rig.currentRevision(imported.programId)!!

        assertTrue("the Program's identity is new", imported.programId != source.program.programId)
        assertTrue("the revision's identity is new", revision.revisionId != source.revision.revisionId)
        assertEquals(
            "every day of the imported revision has an identity of its own",
            3,
            revision.days.map { day -> day.programDayId }.toSet().size
        )
        val draftDayHandles = draft.days.map { day -> day.programDayId }
        assertTrue(
            "and none of them is a handle the draft was reviewing with",
            revision.days.none { day -> day.programDayId in draftDayHandles }
        )
        val occurrences = revision.days.flatMap { day -> day.exercises.map { element -> element.programExerciseId } }
        assertEquals(
            "each occurrence has its own identity — including the same exercise used twice in one day (§9)",
            occurrences.size,
            occurrences.toSet().size
        )
        val draftOccurrenceHandles = draft.days.flatMap { day ->
            day.exercises.map { element -> element.programExerciseId }
        }
        assertTrue(
            "and none of them is a handle the draft was reviewing with",
            occurrences.none { occurrence -> occurrence in draftOccurrenceHandles }
        )
        assertTrue(
            "nothing in the imported graph reuses an identity of the Program it was exported from",
            revision.days.none { day -> day.programDayId.value.contains(source.program.programId.value) } &&
                occurrences.none { occurrence -> occurrence.value.contains(source.program.programId.value) }
        )
    }

    @Test
    fun theImportedProgramOwnsNoHistoryAndNoAdaptiveState() = runBlocking {
        rig.storeSourceProgram()
        rig.startSessionOnSource()
        rig.seedAdaptiveStateOnSource()
        val before = rig.tableCounts()

        val imported = rig.save(ProgramTransferFixture.VALID_BYTES)
        val after = rig.tableCounts()
        val revision = rig.currentRevision(imported.programId)!!

        assertEquals(
            "the creation unit writes the Program, its revision, its days, its elements and its " +
                "opportunities — and nothing else in the whole database moves",
            mapOf(
                "program" to 1,
                "program_revision" to 1,
                "program_day" to 3,
                "program_exercise" to 4,
                "program_workout_slot" to 13
            ),
            movedTables(before, after)
        )
        assertEquals(
            "and the imported Program owns no row of any historical or adaptive table",
            listOf(0, 0, 0, 0),
            listOf(
                rig.rowsOfProgram("workout_session", imported.programId),
                rig.rowsOfProgram("program_adaptive_decision_record", imported.programId),
                rig.rowsOfProgram("adaptive_adjustment", imported.programId),
                rig.rowsOfRevision("program_family_progression_state", revision.revisionId)
            )
        )
        assertEquals(
            "a freshly imported Program has exactly one revision, and no history behind it (§17)",
            1,
            rig.revisionCount(imported.programId)
        )
    }

    @Test
    fun theSourceProgramIsUntouchedAndTheTwoAreIndependent() = runBlocking {
        val source = rig.storeSourceProgram()
        rig.startSessionOnSource()
        rig.seedAdaptiveStateOnSource()
        val sourceBefore = sourceRows(rig.sourceProgramId, source.revision.revisionId)

        val imported = rig.save(ProgramTransferFixture.VALID_BYTES)

        assertEquals(
            "importing reads one document and no Program: the source's rows are exactly what they were",
            sourceBefore,
            sourceRows(rig.sourceProgramId, source.revision.revisionId)
        )

        // Modifying the imported Program must not modify the source.
        val renamed = rig.lifecycleService.renameProgram(imported.programId, name = "Renamed after import")
        assertTrue("the rename applied to the imported Program", renamed is ProgramOperationResult.Success)
        assertEquals(
            "the source Program's own name is what it was — the imported Program is a different Program, " +
                "with its own facts",
            source.program.name,
            rig.storedProgram(rig.sourceProgramId)!!.name
        )
        assertEquals(
            "and the imported Program is the one that was renamed",
            "Renamed after import",
            rig.storedProgram(imported.programId)!!.name
        )
        assertEquals(
            "and the imported Program's own revision is untouched by its rename (§6)",
            1,
            rig.revisionCount(imported.programId)
        )

        // Deleting the imported Program must not touch the source either.
        rig.seedStandardProgram()
        val deleted = rig.lifecycleService.deleteProgram(imported.programId)
        assertTrue("the imported Program is the user's own, so it is deletable (§4)", deleted is ProgramOperationResult.Success)
        assertNull("the import is gone", rig.storedProgram(imported.programId))
        assertEquals(
            "and the Program it was exported from is exactly where it was",
            sourceBefore,
            sourceRows(rig.sourceProgramId, source.revision.revisionId)
        )
        assertEquals(0, rig.rowsOfProgram("workout_session", imported.programId))
    }

    @Test
    fun importingTheSameFileTwiceCreatesTwoIndependentPrograms() = runBlocking {
        val first = rig.save(ProgramTransferFixture.VALID_BYTES)
        val second = rig.save(ProgramTransferFixture.VALID_BYTES)

        assertTrue("two imports are two Programs (§5)", first.programId != second.programId)
        assertTrue(
            "with two revisions and two plans of their own",
            rig.currentRevision(first.programId)!!.revisionId !=
                rig.currentRevision(second.programId)!!.revisionId
        )
        assertEquals(2, rig.storedPrograms().count { program -> program.source == ProgramSource.IMPORTED })
        assertEquals(
            "one revision each, numbered one (§17)",
            listOf(1 to 1, 1 to 1),
            listOf(
                rig.revisionCount(first.programId) to rig.currentRevision(first.programId)!!.revisionNumber,
                rig.revisionCount(second.programId) to rig.currentRevision(second.programId)!!.revisionNumber
            )
        )
        assertEquals(
            "and neither of them shares a day identity with the other",
            true,
            rig.currentRevision(first.programId)!!.days.map { it.programDayId }
                .intersect(rig.currentRevision(second.programId)!!.days.map { it.programDayId }.toSet())
                .isEmpty()
        )
    }

    @Test
    fun anImportedCopyOfTheStandardProgramIsAUserOwnedImport() = runBlocking {
        rig.seedStandardProgram()
        val exported = rig.exported(StandardProgram.programId)

        val imported = rig.save(exported.bytes)

        assertEquals(
            "§10: an exported Standard Program is shared like any other, and its import is a user-owned " +
                "import — never STANDARD, so it is editable and deletable",
            ProgramSource.IMPORTED,
            imported.source
        )
        assertFalse(imported.source.isBuiltIn)
        val renamed = rig.lifecycleService.renameProgram(imported.programId, name = "My imported copy")
        assertTrue(
            "which is exactly what the copy-before-edit rule would forbid for the built-in one (§4)",
            renamed is ProgramOperationResult.Success
        )
        assertTrue(
            "and the built-in Program itself is untouched",
            rig.storedProgram(StandardProgram.programId)!!.name == StandardProgram.NAME
        )
    }

    // ================================================================ selection (§9)

    @Test
    fun theDefaultLeavesTheSelectionExactlyAsItWas() = runBlocking {
        rig.storeSourceProgram()
        rig.select(rig.sourceProgramId)
        val before = rig.selection()

        val imported = rig.save(ProgramTransferFixture.VALID_BYTES)

        assertEquals(
            "§9: an import is not a selection. With the choice off, the selection is not compared, not " +
                "cleared and not moved",
            before,
            rig.selection()
        )
        assertTrue("and the imported Program is not the selected one", rig.selection()!!.selectedProgramId != imported.programId)
    }

    @Test
    fun theExplicitChoiceSelectsTheImportedProgram() = runBlocking {
        rig.storeSourceProgram()
        rig.select(rig.sourceProgramId)

        val imported = rig.save(ProgramTransferFixture.VALID_BYTES, makeActive = true)

        assertEquals(
            "with the choice on, the imported Program becomes the selected one — through the layer that " +
                "owns selection (§3, §21)",
            imported.programId,
            rig.selection()!!.selectedProgramId
        )
        assertTrue(
            "and there is exactly one state row, so the selection really moved rather than being added to",
            rig.appStateRepository.state() == AppState(selectedProgramId = imported.programId)
        )
    }

    @Test
    fun anImportWithTheChoiceOffLeavesNoStateRowWhenThereWasNone() = runBlocking {
        val imported = rig.save(ProgramTransferFixture.VALID_BYTES)

        assertNull(
            "the importer writes no AppState of its own: with nothing selected before and the choice off, " +
                "nothing is selected after",
            rig.selection()?.selectedProgramId
        )
        assertNotNull(imported.programId)
    }

    // ================================================================ atomicity (§27)

    @Test
    fun aFailureWhereThePlanIsWrittenLeavesNoProgramNoRevisionAndNoOpportunity() = runBlocking {
        val before = rig.tableCounts()
        rig.data.faults.failExerciseInsert = true

        val result = rig.importService.save(rig.review(ProgramTransferFixture.VALID_BYTES))

        assertTrue(
            "the failure is surfaced rather than absorbed (§13, §33): $result",
            result is ProgramTransferResult.Failed
        )
        assertEquals(
            "and the transaction took every leg with it: no Program, no revision, no day, no element, no " +
                "opportunity — the plan-elements write is the fourth leg, so the three before it are " +
                "rolled back too",
            before,
            rig.tableCounts()
        )
    }

    @Test
    fun aFailureWhereTheOpportunitiesAreWrittenLeavesNoProgramAtAll() = runBlocking {
        val before = rig.tableCounts()
        rig.faults.failSlotInsert = true

        val result = rig.importService.save(rig.review(ProgramTransferFixture.VALID_BYTES))

        assertTrue("$result", result is ProgramTransferResult.Failed)
        assertEquals(
            "the opportunity write is the last leg of §27's unit, so it proves the whole graph rolled back",
            before,
            rig.tableCounts()
        )
    }

    @Test
    fun aFailedImportSelectsNothingEvenWhenTheChoiceIsOn() = runBlocking {
        rig.storeSourceProgram()
        rig.select(rig.sourceProgramId)
        val before = rig.tableCounts()
        rig.data.faults.failExerciseInsert = true

        val result = rig.importService.save(rig.review(ProgramTransferFixture.VALID_BYTES), makeActive = true)

        assertTrue("$result", result is ProgramTransferResult.Failed)
        assertEquals(
            "the selection move is part of the same unit, so a failed import cannot leave the user " +
                "pointed at a Program that does not exist",
            rig.sourceProgramId,
            rig.selection()!!.selectedProgramId
        )
        assertEquals(
            "and no row of the creation unit survived the rollback — there is no Program for the " +
                "selection to have been moved to",
            before,
            rig.tableCounts()
        )
        assertEquals(
            "exactly one Program is stored, and it is the source",
            listOf(rig.sourceProgramId),
            rig.storedPrograms().map { program -> program.programId }
        )
    }

    @Test
    fun aReviewedFileLeavesNothingUntilItIsSaved() = runBlocking {
        val before = rig.tableCounts()

        rig.review(ProgramTransferFixture.VALID_BYTES)
        rig.review(ProgramTransferFixture.VALID_BYTES)

        assertEquals(
            "§5's draft step is a read: the pipeline decides everything that can be known about a document " +
                "before a single row is written",
            before,
            rig.tableCounts()
        )
    }

    // ================================================================ helpers

    /** Which tables grew, and by how much — the census every "writes nothing else" claim is made against. */
    private fun movedTables(before: Map<String, Int>, after: Map<String, Int>): Map<String, Int> =
        after.filter { (table, count) -> count != before[table] }
            .mapValues { (table, count) -> count - (before[table] ?: 0) }

    /** The source Program's own rows: its identity, its plan, its opportunities, its history and its state. */
    private suspend fun sourceRows(programId: ProgramId, revisionId: RevisionId): List<Int> = listOf(
        rig.rowsOfProgram("program", programId),
        rig.rowsOfProgram("program_revision", programId),
        rig.rowsOfRevision("program_day", revisionId),
        rig.rowsOfPlanElements(revisionId),
        rig.rowsOfProgram("program_workout_slot", programId),
        rig.rowsOfProgram("workout_session", programId),
        rig.rowsOfRevision("program_family_progression_state", revisionId),
        rig.rowsOfProgram("program_adaptive_decision_record", programId),
        rig.rowsOfProgram("adaptive_adjustment", programId)
    )
}

/** A reviewed draft, or the rejection as the test's own assertion. */
private suspend fun ProgramTransferRig.review(bytes: ByteArray): ProgramImportDraft =
    when (val result = importService.review(bytes)) {
        is ProgramTransferResult.Success -> result.value
        else -> throw AssertionError("expected the file to be accepted, was $result")
    }

/** One imported Program from a draft that was reviewed earlier, or the failure as the test's own assertion. */
private suspend fun ProgramTransferRig.saved(
    draft: ProgramImportDraft,
    makeActive: Boolean = false
): Program {
    return when (val result = importService.save(draft, makeActive)) {
        is ProgramTransferResult.Success -> result.value
        else -> throw AssertionError("expected the import to succeed, was $result")
    }
}

/** One imported Program, or the failure as the test's own assertion. */
private suspend fun ProgramTransferRig.save(
    bytes: ByteArray,
    makeActive: Boolean = false
): com.monkfitness.app.domain.program.Program {
    val draft = review(bytes)
    return when (val result = importService.save(draft, makeActive)) {
        is ProgramTransferResult.Success -> result.value
        else -> throw AssertionError("expected the import to succeed, was $result")
    }
}

/** The typed rejection an unacceptable document produces. */
private suspend fun ProgramTransferRig.rejectionOf(bytes: ByteArray): ProgramTransferRejection =
    when (val result = importService.review(bytes)) {
        is ProgramTransferResult.Rejected -> result.rejection
        else -> throw AssertionError("expected the document to be refused, was $result")
    }

/** One exported file, for the Standard-Program case. */
private suspend fun ProgramTransferRig.exported(
    programId: ProgramId
): ProgramTransferFile =
    when (val result = exportService.export(programId)) {
        is ProgramTransferResult.Success -> result.value
        else -> throw AssertionError("expected the export to succeed, was $result")
    }
