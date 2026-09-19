package com.monkfitness.app.domain.program.transfer

import com.monkfitness.app.domain.prescription.PrescriptionDimension
import com.monkfitness.app.domain.program.Focus
import com.monkfitness.app.domain.program.ProgramDayType
import com.monkfitness.app.domain.program.ProgramExerciseOrigin
import com.monkfitness.app.domain.program.ProgramMode
import java.time.DayOfWeek
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * §5's *parse*, *formatVersion* and *schema validation* steps, over the reader itself.
 *
 * Every test here states **one** thing a file can get wrong and asserts the answer is a finding that names
 * it — never an exception with no place, never a value read from a document that is not the format. The
 * last group is the interesting one for this stage: a document that carries an identity, a timestamp, a
 * source or a lifecycle token is not "a document with extra fields ignored" but a `UnknownField` finding,
 * which is what makes §2's allowlist a property of the reader rather than a habit of the writer.
 */
class ProgramTransferSchemaTest {

    // ------------------------------------------------------------------ the happy path

    @Test
    fun aValidDocumentIsReadIntoTheTransferModel() {
        val document = ProgramTransferReader.read(ProgramTransferFixture.VALID_DOCUMENT)

        assertEquals(ProgramTransferFormat.VERSION, document.formatVersion)
        assertEquals("Imported strength", document.name)
        assertEquals("a program that arrived as a file", document.description)
        assertEquals(ProgramMode.MANUAL, document.revision.mode)
        assertEquals(DurationTransfer.FixedDays(30), document.revision.duration)
        assertEquals(
            ScheduleTransfer.FixedWeekdays(
                listOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)
            ),
            document.revision.schedule
        )
        assertEquals(
            FocusTransfer.Custom(
                listOf(FocusShare(Focus.PUSH, 60), FocusShare(Focus.LEGS, 40))
            ),
            document.revision.focus
        )
        assertEquals(
            "the days are read in document order, and their positions are that order",
            listOf(ProgramDayType.TRAINING, ProgramDayType.REST, ProgramDayType.MOBILITY),
            document.revision.days.map { day -> day.type }
        )
        assertEquals("Push day", document.revision.days[0].name)
        assertEquals(null, document.revision.days[1].name)
        assertEquals(
            "a repeated exercise is two occurrences with their own prescriptions",
            listOf("pushups", "pushups", "pike_pushups"),
            document.revision.days[0].exercises.map { element -> element.exerciseId }
        )
        assertEquals(
            PrescriptionTransfer(PrescriptionDimension.REP_BASED, listOf(12, 10, 8, 6)),
            document.revision.days[0].exercises[0].prescription
        )
        assertEquals(ProgramExerciseOrigin.GENERATED, document.revision.days[0].exercises[0].origin)
        assertTrue("a pinned element is read as pinned", document.revision.days[0].exercises[2].isPinned)
        assertEquals(
            PrescriptionTransfer(PrescriptionDimension.TIME_BASED, listOf(30, 30, 45)),
            document.revision.days[2].exercises[0].prescription
        )
    }

    @Test
    fun everyReferencedExerciseIsListedOnceInDocumentOrder() {
        val document = ProgramTransferReader.read(ProgramTransferFixture.VALID_DOCUMENT)

        assertEquals(
            "the ids an import must validate against the library, each once",
            listOf("pushups", "pike_pushups", "plank"),
            document.referencedExerciseIds
        )
    }

    // ------------------------------------------------------------------ the document's own identity

    @Test
    fun aDocumentThatIsNotThisFormatIsRefusedAsNotAProgramFile() {
        val failure = malformed(
            ProgramTransferFixture.edited("\"monkfitness.program\"", "\"some.other.format\"")
        )

        assertTrue(
            "a foreign marker is a sentence about the file, not a schema complaint about a program: " +
                failure.reason,
            failure.reason.contains("format marker")
        )
    }

    @Test
    fun aMissingMarkerIsAMissingRequiredField() {
        val issues = schemaIssues(ProgramTransferFixture.edited("\"format\": \"monkfitness.program\",", ""))

        assertTrue(
            "the format marker is a required field: $issues",
            issues.any { it is ProgramTransferIssue.MissingField && it.name == "format" }
        )
    }

    @Test
    fun aMissingFormatVersionIsARequiredFieldFinding() {
        val issues = schemaIssues(ProgramTransferFixture.edited("\"formatVersion\": 1,", ""))

        assertTrue(
            "$issues",
            issues.any { it is ProgramTransferIssue.MissingField && it.name == "formatVersion" }
        )
    }

    @Test
    fun anUnsupportedVersionIsItsOwnAnswerAndNotABagOfSchemaFindings() {
        val failure: UnsupportedDocumentVersion = try {
            ProgramTransferReader.read(ProgramTransferFixture.edited("\"formatVersion\": 1", "\"formatVersion\": 2"))
            throw AssertionError("expected the version to be refused")
        } catch (thrown: UnsupportedDocumentVersion) {
            thrown
        }

        assertEquals("the version the file states is reported", 2, failure.found)
    }

    @Test
    fun aVersionThatIsNotAWholeNumberIsASyntaxFailureBeforeItIsAVersion() {
        assertTrue(
            "a fractional version cannot be compared, so it is refused where it is read",
            malformed(ProgramTransferFixture.edited("\"formatVersion\": 1", "\"formatVersion\": 1.5"))
                .reason.contains("whole")
        )
    }

    // ------------------------------------------------------------------ the allowlist

    @Test
    fun aFieldTheFormatDoesNotDefineIsRefusedWhereverItAppears() {
        val atTheRoot = schemaIssues(ProgramTransferFixture.edited("\"format\": \"monkfitness.program\",", "\"format\": \"monkfitness.program\",\n  \"exportedAt\": 1,"))
        val inTheProgram = schemaIssues(ProgramTransferFixture.edited("\"description\": \"a program that arrived as a file\"", "\"description\": \"a program that arrived as a file\",\n      \"name2\": \"x\""))
        val inADay = schemaIssues(
            ProgramTransferFixture.edited(
                """{ "type": "TRAINING", "name": "Push day""",
                """{ "position": 1, "type": "TRAINING", "name": "Push day"""
            )
        )

        assertTrue(
            "an undefined field at the document's root is named with its place: $atTheRoot",
            atTheRoot.any { it is ProgramTransferIssue.UnknownField && it.path == "document" && it.name == "exportedAt" }
        )
        assertTrue(
            "$inTheProgram",
            inTheProgram.any { it is ProgramTransferIssue.UnknownField && it.path == "document.program" }
        )
        assertTrue(
            "a day has no position field: the order of the array is the position (§6): $inADay",
            inADay.any { it is ProgramTransferIssue.UnknownField && it.path == "document.revision.days[0]" && it.name == "position" }
        )
    }

    @Test
    fun theFormatCarriesNoIdentityAndNoLifecycleStateSoADocumentThatDoesIsRefused() {
        val withIdentity = schemaIssues(
            ProgramTransferFixture.edited(
                "\"name\": \"Imported strength\",",
                "\"name\": \"Imported strength\",\n      \"programId\": \"program-secret\",\n      \"revisionId\": \"revision-secret\",\n      \"createdAt\": 1700000000000,\n      \"source\": \"STANDARD\","
            )
        )
        val names = withIdentity.filterIsInstance<ProgramTransferIssue.UnknownField>().map { it.name }

        assertEquals(
            "§2's excluded facts are not fields this format has, so a file that carries one is a file this " +
                "reader does not have a reading for",
            listOf("programId", "revisionId", "createdAt", "source"),
            names
        )
    }

    @Test
    fun anUnknownTokenIsRefusedWithTheTokensTheFieldAdmits() {
        val mode = schemaIssues(ProgramTransferFixture.edited("\"mode\": \"MANUAL\"", "\"mode\": \"SEMI_AUTO\""))
        val dayType = schemaIssues(ProgramTransferFixture.edited("\"type\": \"REST\"", "\"type\": \"DELOAD\""))
        val dimension = schemaIssues(
            ProgramTransferFixture.edited("\"dimension\": \"TIME_BASED\"", "\"dimension\": \"VOLUME_BASED\"")
        )

        listOf(mode, dayType, dimension).forEach { issues ->
            val unknown = issues.filterIsInstance<ProgramTransferIssue.UnknownToken>()
            assertEquals("one token finding: $issues", 1, unknown.size)
            assertTrue(
                "and it lists what the field admits: ${unknown.first().allowed}",
                unknown.first().allowed.isNotEmpty()
            )
        }
        assertTrue(
            "the day's own token list is the day vocabulary",
            dayType.filterIsInstance<ProgramTransferIssue.UnknownToken>().first()
                .allowed.contains("POSTURE_MOBILITY")
        )
    }

    @Test
    fun aValueOfTheWrongKindIsRefusedWithBothKinds() {
        val notAList = schemaIssues(
            ProgramTransferFixture.edited("\"perSetTargets\": [12, 10, 8, 6]", "\"perSetTargets\": 4")
        )
        val notText = schemaIssues(ProgramTransferFixture.edited("\"name\": \"Imported strength\"", "\"name\": 7"))
        val notABoolean = schemaIssues(ProgramTransferFixture.edited("\"pinned\": true", "\"pinned\": \"yes\""))

        assertTrue(
            "$notAList",
            notAList.any { it is ProgramTransferIssue.WrongType && it.expected == "a JSON array" && it.found == "a number" }
        )
        assertTrue("$notText", notText.any { it is ProgramTransferIssue.WrongType && it.expected == "a text value" })
        assertTrue("$notABoolean", notABoolean.any { it is ProgramTransferIssue.WrongType && it.expected == "a boolean" })
    }

    @Test
    fun theVariantAFieldSetIsTheVariantsOwn() {
        val indefiniteWithDays = schemaIssues(
            ProgramTransferFixture.edited(
                "{ \"kind\": \"FIXED_DAYS\", \"days\": 30 }",
                "{ \"kind\": \"INDEFINITE\", \"days\": 30 }"
            )
        )
        val weekdaysWithAFrequency = schemaIssues(
            ProgramTransferFixture.edited(
                "\"weekdays\": [\"MONDAY\", \"WEDNESDAY\", \"FRIDAY\"] }",
                "\"weekdays\": [\"MONDAY\", \"WEDNESDAY\", \"FRIDAY\"], \"sessionsPerWeek\": 3 }"
            )
        )
        val balancedWithFocuses = schemaIssues(
            ProgramTransferFixture.edited("\"goal\": \"CUSTOM\",", "\"goal\": \"BALANCED\",\n              \"focuses\": [\"PUSH\"],")
        )

        listOf(indefiniteWithDays, weekdaysWithAFrequency, balancedWithFocuses).forEach { issues ->
            assertTrue(
                "a variant that carries the other variant's field states one fact twice: $issues",
                issues.any { it is ProgramTransferIssue.UnknownField }
            )
        }
    }

    @Test
    fun onePassReportsEveryFindingItCanSee() {
        val issues = schemaIssues(
            ProgramTransferFixture.VALID_DOCUMENT
                .replace("\"mode\": \"MANUAL\"", "\"mode\": \"SEMI_AUTO\"")
                .replace("\"formatVersion\": 1,", "\"formatVersion\": 1,\n  \"exportedAt\": 1,")
                .replace("\"pinned\": true", "\"pinned\": \"yes\"")
        )

        assertTrue(
            "a document with several problems reports all of them, each with its own place: $issues",
            issues.size >= 3
        )
        assertTrue(
            "and every finding names a place in the file",
            issues.all { it.path.startsWith("document") }
        )
    }

    @Test
    fun aDocumentLargerThanTheFormatAllowsIsRefusedBeforeItIsParsed() {
        val padding = " ".repeat(ProgramTransferFormat.MAXIMUM_DOCUMENT_BYTES)

        val failure = malformed(ProgramTransferFixture.VALID_DOCUMENT + padding)

        assertTrue(
            "the limit is named so the user knows what happened: ${failure.reason}",
            failure.reason.contains(ProgramTransferFormat.MAXIMUM_DOCUMENT_BYTES.toString())
        )
    }

    // ------------------------------------------------------------------ helpers

    private fun schemaIssues(text: String): List<ProgramTransferIssue> = try {
        ProgramTransferReader.read(text)
        throw AssertionError("expected the document to be refused: $text")
    } catch (failure: SchemaViolation) {
        failure.issues
    }

    private fun malformed(text: String): MalformedDocument = try {
        ProgramTransferReader.read(text)
        throw AssertionError("expected the document to be refused as not a program file")
    } catch (failure: MalformedDocument) {
        failure
    }
}
