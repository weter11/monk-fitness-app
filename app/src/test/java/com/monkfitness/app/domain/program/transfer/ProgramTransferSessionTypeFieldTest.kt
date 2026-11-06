package com.monkfitness.app.domain.program.transfer

import com.monkfitness.app.domain.program.DraftIdSource
import com.monkfitness.app.domain.program.SessionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The session-type field in the file itself: what an older file says, what a selection says, and what a
 * token the vocabulary does not hold does.
 *
 * The fixture's documents are written by hand, so these are claims about the *reader* — a file this app
 * did not write, which is the only kind of file an import has to survive.
 */
class ProgramTransferSessionTypeFieldTest {

    private val ids: DraftIdSource = ids()

    @Test
    fun aFileWrittenBeforeTheSelectionExistedReadsAsMixed() {
        val document = ProgramTransferReader.read(ProgramTransferFixture.VALID_DOCUMENT)

        assertEquals(
            "the field is absent, and absent is Mixed — the selection a file shared before the " +
                "selection existed was built from",
            emptySet<SessionType>(),
            document.revision.sessionTypes
        )
        assertTrue(
            "and the draft the import reviews is Mixed too, not a selection nobody stated",
            ProgramTransferMapper.draftOf(document, ids).isMixedSessionTypes
        )
    }

    @Test
    fun aSelectionInAFileIsReadAsTheSelectionItNamesAndWrittenInTheVocabularysOrder() {
        val document = ProgramTransferReader.read(
            ProgramTransferFixture.edited(
                "\"preferredExercises\": []",
                "\"preferredExercises\": [],\n            \"sessionTypes\": [\"POSTURE\", \"STRENGTH\"]"
            )
        )

        assertEquals(
            "both types arrive, whatever order the file states them in",
            setOf(SessionType.STRENGTH, SessionType.POSTURE),
            document.revision.sessionTypes
        )
        assertEquals(
            "and the draft the editor reviews carries the same selection",
            setOf(SessionType.STRENGTH, SessionType.POSTURE),
            ProgramTransferMapper.draftOf(document, ids).sessionTypes
        )
        assertTrue(
            "the writer states one order, so two equivalent files are one file: " +
                ProgramTransferJson.write(document),
            "\"sessionTypes\": [\"STRENGTH\", \"POSTURE\"]" in ProgramTransferJson.write(document)
        )
    }

    @Test
    fun anUnknownSessionTypeTokenIsRefusedRatherThanDropped() {
        val refused = schemaRefusal(
            ProgramTransferFixture.edited(
                "\"preferredExercises\": []",
                "\"preferredExercises\": [],\n            \"sessionTypes\": [\"YOGA\"]"
            )
        )

        val unknown = refused.issues.filterIsInstance<ProgramTransferIssue.UnknownToken>().singleOrNull()
        assertTrue(
            "a token this vocabulary does not hold is a finding at its own path, not an entry quietly " +
                "dropped: ${refused.issues.map { it.message }}",
            unknown != null && unknown.path == "document.revision.sessionTypes[0]" &&
                unknown.token == "YOGA"
        )
    }

    @Test
    fun aTypeNamedTwiceIsRefused() {
        val refused = schemaRefusal(
            ProgramTransferFixture.edited(
                "\"preferredExercises\": []",
                "\"preferredExercises\": [],\n            \"sessionTypes\": [\"STRENGTH\", \"STRENGTH\"]"
            )
        )

        assertTrue(
            "a set that names a member twice is refused rather than collapsed: " +
                refused.issues.map { it.message },
            refused.issues.any { issue -> issue.message.contains("more than once") }
        )
    }

    private fun schemaRefusal(text: String): SchemaViolation = try {
        ProgramTransferReader.read(text)
        throw AssertionError("expected the document to be refused")
    } catch (refused: SchemaViolation) {
        refused
    }

    private fun ids(): DraftIdSource {
        var next = 0
        return DraftIdSource {
            next += 1
            "draft-$next"
        }
    }
}
