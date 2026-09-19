package com.monkfitness.app.domain.program.transfer

import com.monkfitness.app.domain.prescription.PrescriptionDimension
import com.monkfitness.app.domain.program.Focus
import com.monkfitness.app.domain.program.ProgramDayType
import com.monkfitness.app.domain.program.ProgramExerciseOrigin
import com.monkfitness.app.domain.program.ProgramMode
import java.time.DayOfWeek
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * §5's first step and §11's determinism, measured on the reader and the writer themselves.
 *
 * Two things are being pinned here, and they are the two halves of *"malformed JSON must not become an
 * empty Program"* plus *"the same Program input produces byte-equivalent JSON"*:
 *
 *  * **the reader is strict.** A document that is not one JSON value, that repeats a field name, that
 *    carries a fraction, a lone surrogate or a raw control character is refused with the place it was
 *    refused at — and refused *by not producing a value*, which is what makes "there is no partially read
 *    plan" a property of the code path rather than a rule the callers remember;
 *  * **the writer is a function.** The same document produces the same bytes twice over, the field order
 *    and the indentation are the format's own, `null` is never written for an absent optional fact, and
 *    nothing in the writer can consult a clock, a locale or a random source — it holds no collaborator at
 *    all.
 */
class ProgramTransferJsonTest {

    // ------------------------------------------------------------------ the reader

    @Test
    fun aDocumentIsReadIntoItsValues() {
        val read = Json.read("""{"a": 1, "b": [true, null, "x"], "c": {"d": -2}}""")

        val record = read as JsonValue.JsonObject
        assertEquals(
            "the document's fields are read in document order",
            listOf("a", "b", "c"),
            record.names
        )
        assertEquals(JsonValue.JsonNumber(1), record.named("a"))
        assertEquals(
            JsonValue.JsonArray(
                listOf(
                    JsonValue.JsonBoolean(true),
                    JsonValue.JsonNull,
                    JsonValue.JsonString("x")
                )
            ),
            record.named("b")
        )
        assertEquals(
            JsonValue.JsonNumber(-2),
            (record.named("c") as JsonValue.JsonObject).named("d")
        )
    }

    @Test
    fun theReaderAcceptsWhitespaceAndEveryTextEscapeTheFormatUses() {
        val read = Json.read(
            "{\n  \"quoted\"\t: \"a\\\"b\\\\c\\/d\\b\\f\\n\\r\\t\",\n  \"uni\": \"\\u041f\\u0443\\u0448\"\n}"
        ) as JsonValue.JsonObject

        assertEquals(
            "every escape JSON defines is read as the character it stands for",
            "a\"b\\c/d\b\u000C\n\r\t",
            (read.named("quoted") as JsonValue.JsonString).value
        )
        assertEquals(
            "a \\u escape outside the BMP is read as the character it is",
            "Пуш",
            (read.named("uni") as JsonValue.JsonString).value
        )
        assertEquals(
            "a surrogate pair is one character, not two halves",
            "\uD83D\uDE00",
            (Json.read("\"\\uD83D\\uDE00\"") as JsonValue.JsonString).value
        )
    }

    @Test
    fun textIsReadExactlyAsWrittenAndNeverNormalized() {
        val text = "  Пуш  \u00A0 pushups  "

        assertEquals(
            "the reader decodes; it does not trim, fold or normalize",
            text,
            (Json.read("\"$text\"") as JsonValue.JsonString).value
        )
    }

    @Test
    fun malformedTextIsASyntaxFailureAndNeverAValue() {
        val malformed = listOf(
            "",
            "   ",
            "{",
            "}",
            "[]]",
            "{\"a\": 1} {\"b\": 2}",
            "{a: 1}",
            "{\"a\" 1}",
            "{\"a\": }",
            "{\"a\": 1,}",
            "[1,]",
            "\"unterminated",
            "'single quotes'",
            "nul",
            "tru",
            "{\"a\": 01}",
            "{\"a\": 1.5}",
            "{\"a\": 1e3}",
            "{\"a\": \"\\x\"}",
            "{\"a\": \"\u0001\"}",
            "{\"a\": \"\\uD83D\"}",
            "{\"a\": \"\\uDE00\"}",
            "{\"a\": \"\\u00\"}",
            "{\"a\": 1, \"a\": 2}",
            "\uFEFF{\"a\": 1}"
        )

        malformed.forEach { text ->
            try {
                Json.read(text)
                fail("expected a syntax failure for: ${text.replace("\uFEFF", "<BOM>")}")
            } catch (failure: JsonSyntaxError) {
                assertTrue(
                    "a syntax failure says what was wrong and where: ${failure.message}",
                    failure.reason.isNotBlank() && failure.line >= 1 && failure.column >= 1
                )
            }
        }
    }

    @Test
    fun theTwoCharactersThatMeanTwoThingsAreToldApartWhereTheyFail() {
        val atTheStart = failureOf("{")
        val afterTheValue = failureOf("{\"a\": 1} x")

        assertEquals("a failure inside the document names the line", 1, atTheStart.line)
        assertTrue(
            "trailing content is refused as trailing content: ${afterTheValue.reason}",
            afterTheValue.reason.contains("unexpected content")
        )
    }

    @Test
    fun theReaderRefusesNestingDeeperThanItsGuardSays() {
        val deep = "[".repeat(Json.MAXIMUM_DEPTH + 1) + "]".repeat(Json.MAXIMUM_DEPTH + 1)

        val failure = failureOf(deep)

        assertTrue(
            "a document that nests past the guard is refused with the guard's own limit (§14): " +
                failure.reason,
            failure.reason.contains(Json.MAXIMUM_DEPTH.toString())
        )
    }

    @Test
    fun theGuardAdmitsADocumentThatNestsExactlyAsDeepAsItAllows() {
        val allowed = "[".repeat(Json.MAXIMUM_DEPTH - 1) + "1" + "]".repeat(Json.MAXIMUM_DEPTH - 1)

        val read = Json.read(allowed)

        assertTrue("the guard is a bound and not a refusal", read is JsonValue.JsonArray)
    }

    // ------------------------------------------------------------------ the writer

    private fun document(
        name: String = "Push day",
        description: String = "a description",
        focus: FocusTransfer = FocusTransfer.Custom(
            listOf(FocusShare(Focus.PUSH, 60), FocusShare(Focus.LEGS, 40))
        ),
        duration: DurationTransfer = DurationTransfer.FixedDays(30)
    ) = ProgramTransferDocument(
        formatVersion = ProgramTransferFormat.VERSION,
        name = name,
        description = description,
        revision = RevisionTransfer(
            mode = ProgramMode.MANUAL,
            duration = duration,
            schedule = ScheduleTransfer.FixedWeekdays(
                listOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)
            ),
            focus = focus,
            days = listOf(
                DayTransfer(
                    type = ProgramDayType.TRAINING,
                    name = "Day one",
                    exercises = listOf(
                        ExerciseTransfer(
                            exerciseId = "pushups",
                            prescription = PrescriptionTransfer(
                                PrescriptionDimension.REP_BASED,
                                listOf(12, 10, 8, 6)
                            ),
                            origin = ProgramExerciseOrigin.GENERATED,
                            isPinned = false
                        )
                    )
                ),
                DayTransfer(type = ProgramDayType.REST, name = null, exercises = emptyList())
            )
        )
    )

    @Test
    fun theWriterIsExactlyTheFormatAndAlwaysTheSameBytes() {
        val written = ProgramTransferJson.write(document())

        assertEquals(
            "the document is written field by field, in the format's own order and indentation, with " +
                "one trailing newline — and the day's absent name is an absent field, never a null",
            """
            {
              "format": "monkfitness.program",
              "formatVersion": 1,
              "program": {
                "name": "Push day",
                "description": "a description"
              },
              "revision": {
                "mode": "MANUAL",
                "duration": {
                  "kind": "FIXED_DAYS",
                  "days": 30
                },
                "schedule": {
                  "kind": "FIXED_WEEKDAYS",
                  "weekdays": ["MONDAY", "WEDNESDAY", "FRIDAY"]
                },
                "focus": {
                  "goal": "CUSTOM",
                  "allocations": [
                    {
                      "focus": "PUSH",
                      "percent": 60
                    },
                    {
                      "focus": "LEGS",
                      "percent": 40
                    }
                  ]
                },
                "days": [
                  {
                    "type": "TRAINING",
                    "name": "Day one",
                    "exercises": [
                      {
                        "exerciseId": "pushups",
                        "prescription": {
                          "dimension": "REP_BASED",
                          "perSetTargets": [12, 10, 8, 6]
                        },
                        "origin": "GENERATED",
                        "pinned": false
                      }
                    ]
                  },
                  {
                    "type": "REST",
                    "exercises": []
                  }
                ]
              }
            }
            """.trimIndent() + "\n",
            written
        )
        assertEquals(
            "writing the same document twice produces the same bytes",
            written,
            ProgramTransferJson.write(document())
        )
    }

    @Test
    fun theAbsentOptionalFactIsAbsentAndNotNull() {
        val written = ProgramTransferJson.write(document())

        assertTrue(
            "an unnamed day states no name",
            "\"type\": \"REST\",\n        \"exercises\": []" in written
        )
        assertTrue("and nothing in the document is written as null", "null" !in written)
    }

    @Test
    fun theWriterEscapesExactlyWhatTheFormatEscapesAndWritesEverythingElseRaw() {
        val written = ProgramTransferJson.write(
            document(
                name = "Пуш \"день\" \\ \u0001",
                description = "line\nbreak\ttab"
            )
        )

        assertTrue(
            "the escapes are JSON's own and the rest of the text is raw UTF-8: $written",
            "\"name\": \"Пуш \\\"день\\\" \\\\ \\u0001\"" in written
        )
        assertTrue("\"description\": \"line\\nbreak\\ttab\"" in written)
        val reparsed = Json.read(written) as JsonValue.JsonObject
        val program = reparsed.named("program") as JsonValue.JsonObject
        assertEquals(
            "and what the writer escaped is exactly what the reader reads back",
            "Пуш \"день\" \\ \u0001",
            (program.named("name") as JsonValue.JsonString).value
        )
    }

    @Test
    fun theWriterWritesBothDurationFormsAndBothScheduleFormsAndAllThreeFocusForms() {
        val forms = listOf(
            document(duration = DurationTransfer.Indefinite),
            document(
                focus = FocusTransfer.Balanced
            ),
            document(
                focus = FocusTransfer.Focused(listOf(Focus.PUSH, Focus.LEGS))
            )
        )

        val written = forms.map { form -> ProgramTransferJson.write(form) }

        assertTrue(
            "an indefinite program states no day count at all",
            "      \"kind\": \"INDEFINITE\"\n    }" in written[0]
        )
        assertTrue("BALANCED states no share", "\"goal\": \"BALANCED\"" in written[1])
        assertTrue(
            "FOCUSED states the focuses it is built around",
            "\"goal\": \"FOCUSED\"" in written[2] && "\"focuses\": [\"PUSH\", \"LEGS\"]" in written[2]
        )
    }

    @Test
    fun theFormatConstantsAreTheOnesTheFormatIsReadAndWrittenWith() {
        assertEquals("monkfitness.program", ProgramTransferFormat.MARKER)
        assertEquals(1, ProgramTransferFormat.VERSION)
        assertEquals("MonkFitnessProgram.mfp.json", ProgramTransferFormat.FILE_NAME)
        assertEquals("application/json", ProgramTransferFormat.MIME_TYPE)
        assertEquals("the file is named for the format, never for a Program", -1, ProgramTransferFormat.FILE_NAME.indexOf("program-"))
    }

    @Test
    fun theEncodersRoundTripAndRefuseBytesThatAreNotUtf8() {
        val text = "Пуш 12/10/8 \uD83D\uDE00"

        assertEquals(text, ProgramTransferFormat.decode(ProgramTransferFormat.encode(text)))
        try {
            ProgramTransferFormat.decode(byteArrayOf(0x7B, 0xC3.toByte(), 0x28, 0x7D))
            fail("expected a decoding failure for bytes that are not UTF-8")
        } catch (failure: ProgramTransferTextError) {
            assertTrue(
                "the failure says the bytes are not UTF-8 rather than substituting a replacement " +
                    "character: ${failure.reason}",
                failure.reason.contains("UTF-8")
            )
        }
    }

    @Test
    fun aValueThatIsNotARecordIsNotADocument() {
        assertNull("a bare scalar has no fields to read", (Json.read("1") as? JsonValue.JsonObject))
    }

    private fun failureOf(text: String): JsonSyntaxError = try {
        Json.read(text)
        throw AssertionError("expected a syntax failure for: $text")
    } catch (failure: JsonSyntaxError) {
        failure
    }
}
