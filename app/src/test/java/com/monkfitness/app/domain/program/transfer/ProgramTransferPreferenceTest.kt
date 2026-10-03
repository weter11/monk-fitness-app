package com.monkfitness.app.domain.program.transfer

import com.monkfitness.app.domain.program.ExercisePreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §30 step 28's **exercise preference in the transfer format**, and the format-version consequence it
 * carries.
 *
 * Two directions, because a format change is only honest if both ends are measured:
 *
 * ```text
 * export   the user's exact order reaches the file, and an absent preference is a stated `[]`
 * import   a v2 file with a preference imports with that exact order
 * v1       a version-1 file is REFUSED as unsupported — never read as "a program with no preference"
 * ```
 *
 * ### Why the version bump is the point, not a detail
 *
 * `ProgramTransferFormat`'s own KDoc states the rule — *"adding a field is therefore a version bump"* —
 * and §5 requires that an unknown or unsupported version be **rejected**. So a version-1 file, which has
 * no `preferredExercises` key at all, is a different document and this reader does not know how to read
 * it. Reading it as "no preference" would be a guess about what its author meant: the file did not say
 * *nothing is preferred*, it simply predates the field, and those are not the same claim.
 *
 * The refusal is asserted as an **unsupported version**, not as a bag of schema findings, so the two
 * answers stay distinguishable — that is the same distinction the format made before this stage, and it is
 * what lets a user be told *why* their file was refused instead of *what was wrong with* it.
 */
class ProgramTransferPreferenceTest {

    // ------------------------------------------------------------------ export

    @Test
    fun theExactOrderedPreferenceIsWrittenAndAnAbsentOneIsAStatedEmptyArray() {
        val withPreference = ProgramTransferJson.write(
            ProgramTransferReader.read(
                ProgramTransferFixture.document(preferredExercises = ProgramTransferFixture.PREFERENCE)
            )
        )
        val withoutPreference = ProgramTransferJson.write(
            ProgramTransferReader.read(
                ProgramTransferFixture.document(preferredExercises = ProgramTransferFixture.NO_PREFERENCE)
            )
        )

        assertTrue(
            "the writer states the user's order verbatim, in the user's order — for this field the array " +
                "IS the ranking, and nothing is sorted or re-derived on the way out (§9)",
            "\"preferredExercises\": [\"pullups\", \"dips\"]" in withPreference
        )
        assertTrue(
            "\"no preference\" is written as an explicit empty array rather than an absent key, so a file " +
                "that says nothing is preferred is distinguishable from a file that never said (§11)",
            "\"preferredExercises\": []" in withoutPreference
        )
    }

    @Test
    fun theReversedOrderProducesDifferentBytes() {
        // Determinism is the format's own rule (§11), and this is the case where it matters most: the two
        // documents name the same two exercises and differ only in what the user said they would rather
        // train first. A writer that sorted, or a `Set` anywhere on the path, would emit identical bytes.
        val forwards = ProgramTransferJson.write(
            ProgramTransferReader.read(
                ProgramTransferFixture.document(preferredExercises = ProgramTransferFixture.PREFERENCE)
            )
        )
        val reversed = ProgramTransferJson.write(
            ProgramTransferReader.read(
                ProgramTransferFixture.document(preferredExercises = ProgramTransferFixture.REVERSED_PREFERENCE)
            )
        )

        assertTrue(
            "the same two exercises in the other order is a different statement, so it is different bytes",
            forwards != reversed
        )
        assertEquals(
            "and writing the same document twice is still the same bytes — the format's determinism rule " +
                "(§11) is what lets an exported file be compared byte for byte",
            forwards,
            ProgramTransferJson.write(
                ProgramTransferReader.read(
                    ProgramTransferFixture.document(preferredExercises = ProgramTransferFixture.PREFERENCE)
                )
            )
        )
    }

    // ------------------------------------------------------------------ import

    @Test
    fun aVersionTwoFileImportsWithTheExactOrderItStated() {
        val document = ProgramTransferReader.read(
            ProgramTransferFixture.document(preferredExercises = ProgramTransferFixture.PREFERENCE)
        )

        assertEquals(
            "§9's order survives the reader exactly as the file stated it",
            listOf("pullups", "dips"),
            document.revision.preferredExercises
        )
        assertEquals(
            "and the mapper builds the domain's own value from it, in the same order",
            ExercisePreference.of("pullups", "dips"),
            ExercisePreference(document.revision.preferredExercises)
        )
    }

    @Test
    fun aFileThatStatesNoPreferenceImportsAsTheEmptyPreferenceAndNotAsARanking() {
        val document = ProgramTransferReader.read(ProgramTransferFixture.VALID_DOCUMENT)

        assertEquals(
            "an explicitly empty array reads as the stated absence",
            emptyList<String>(),
            document.revision.preferredExercises
        )
        assertTrue(
            "which is `ExercisePreference.NONE` rather than some order the reader supplied",
            ExercisePreference(document.revision.preferredExercises).isEmpty
        )
    }

    @Test
    fun aPreferredExerciseIsAReferenceAndIsValidatedWithTheRest() {
        // §5's *"Unknown exerciseId is rejected"* covers every id the document names, not only the ones a
        // day plans. A preference names exercises the plan may not contain, so a file naming one the
        // receiving app does not ship is a file whose preference could never be honoured.
        val document = ProgramTransferReader.read(
            ProgramTransferFixture.document(preferredExercises = """["pullups", "an-exercise-nobody-ships"]""")
        )

        assertTrue(
            "the preference's ids join the document's own exercise references, so the importer asks the " +
                "library once about all of them",
            "an-exercise-nobody-ships" in document.referencedExerciseIds
        )
        assertTrue(
            "and an exercise that is both planned and preferred is still asked about exactly once",
            document.referencedExerciseIds.count { id -> id == "squats" } <= 1
        )
    }

    @Test
    fun aRepeatedPreferenceIsRefusedBecauseTheOrderWouldHaveTwoAnswersAtOnePosition() {
        try {
            ProgramTransferReader.read(
                ProgramTransferFixture.document(preferredExercises = """["pullups", "dips", "pullups"]""")
            )
            throw AssertionError("expected the repeated entry to be refused")
        } catch (refused: SchemaViolation) {
            assertTrue(
                "a preference is an ORDER, so naming one exercise twice makes the order mean two things " +
                    "at the same position (§9): ${refused.issues.map { it.message }}",
                refused.issues.any { issue -> issue.message.contains("more than once") }
            )
        }
    }

    @Test
    fun aBlankPreferredExerciseIdIsRefusedRatherThanStoredAsAnUnroutableEntry() {
        try {
            ProgramTransferReader.read(
                ProgramTransferFixture.document(preferredExercises = """["pullups", "  "]""")
            )
            throw AssertionError("expected the blank id to be refused")
        } catch (refused: SchemaViolation) {
            assertTrue(
                "a blank id is not an exercise, and storing one would put an unroutable entry into the " +
                    "next generation's request: ${refused.issues.map { it.message }}",
                refused.issues.any { issue -> issue.message.contains("blank") }
            )
        }
    }

    // ------------------------------------------------------------------ the version consequence

    @Test
    fun aVersionOneFileIsRefusedAsUnsupportedAndNeverReadAsHavingNoPreference() {
        // A version-1 document with the field removed is what a file shared before this stage looks like.
        // The two candidate answers are both wrong in different ways: reading it would invent a claim, and
        // reading it as "no preference" would be a specific guess. §5's answer is to reject it.
        val versionTwo = ProgramTransferFixture.edited(
            "\"formatVersion\": 2,",
            "\"formatVersion\": 1,"
        )
        // The whole `, "preferredExercises": []` pair goes, comma included: leaving the comma behind
        // would make the file malformed JSON, and a malformed file is refused for a *different* reason
        // than an unsupported one — which is precisely the distinction this test keeps separate.
        // Built by locating the key rather than by restating its indentation, so a re-indent of the
        // fixture cannot silently turn this into a "fixture does not contain" failure.
        val keyStart = versionTwo.lastIndexOf(",\n", versionTwo.indexOf("\"preferredExercises\""))
        val keyEnd = versionTwo.indexOf("\"preferredExercises\"")
        val lineEnd = versionTwo.indexOf("\n", keyEnd)
        val versionOne = versionTwo.removeRange(keyStart, lineEnd)
        assertTrue(
            "the fixture really does look like a version-1 file: it states version 1 and carries no " +
                "preference key at all, which is exactly what a file shared before this stage looks like",
            "\"formatVersion\": 1" in versionOne && "preferredExercises" !in versionOne
        )

        try {
            ProgramTransferReader.read(versionOne)
            throw AssertionError("expected the version-1 file to be refused")
        } catch (refused: UnsupportedDocumentVersion) {
            assertEquals("the version the file states is what it reports", 1, refused.found)
            assertEquals(
                "and the only version this app reads is stated with it — there is deliberately no " +
                    "backward reader for v1, because the format's rule is one known version at a time (§5)",
                ProgramTransferFormat.VERSION,
                ProgramTransferRejection.UnsupportedFormatVersion(refused.found, ProgramTransferFormat.VERSION)
                    .supported
            )
        }
    }

    @Test
    fun aVersionTwoFileIsTheOneThatIsReadAndTheStampMatchesIt() {
        assertEquals(
            "the reader accepts exactly the version the writer stamps, and this stage made that 2",
            2,
            ProgramTransferFormat.VERSION
        )
        assertEquals(
            "a document read back reports the version it was written in",
            ProgramTransferFormat.VERSION,
            ProgramTransferReader.read(ProgramTransferFixture.VALID_DOCUMENT).formatVersion
        )
    }
}