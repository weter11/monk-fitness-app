package com.monkfitness.app.domain.program.transfer

import com.monkfitness.app.domain.prescription.PrescriptionDimension
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §6's **semantic validation**, over documents the schema layer accepts.
 *
 * The separation this suite pins is the one that matters: every document below is *well-shaped* — the
 * reader reads all of them — and every one of them is a program this app cannot hold. Each rule is
 * decided here rather than left to the domain's constructors, which is what makes the mapping step total
 * and what turns "a corrupt file" into a finding about a file instead of a crash.
 *
 * Two rules are checked through the *domain's own* validation rather than restated here — a Program needs a
 * name, and it needs days that are numbered in order — because §6 asks for exactly that reuse. They are
 * covered end to end in `ProgramImportServiceTest`; this suite covers the rules the transfer layer owns.
 */
class ProgramTransferValidationTest {

    @Test
    fun aValidDocumentHasNoFindingsAtAll() {
        val document = ProgramTransferReader.read(ProgramTransferFixture.VALID_DOCUMENT)

        assertEquals(
            "the fixture is a program this app can hold",
            emptyList<ProgramTransferIssue>(),
            ProgramTransferValidation.issuesIn(document)
        )
    }

    // ------------------------------------------------------------------ duration, schedule, focus

    @Test
    fun aFixedDurationOfNoDaysIsRefused() {
        val issues = issuesOf(
            ProgramTransferFixture.edited("{ \"kind\": \"FIXED_DAYS\", \"days\": 30 }", "{ \"kind\": \"FIXED_DAYS\", \"days\": 0 }")
        )

        assertEquals(1, issues.size)
        assertTrue(
            "a fixed program runs for at least one calendar day (§20): ${issues.first().message}",
            issues.first().message.contains("at least one calendar day")
        )
        assertTrue("and the finding says where", issues.first().path == "revision.duration.days")
    }

    @Test
    fun anIndefiniteDurationIsFineAndCarriesNoEnd() {
        val document = ProgramTransferReader.read(
            ProgramTransferFixture.edited(
                "{ \"kind\": \"FIXED_DAYS\", \"days\": 30 }",
                "{ \"kind\": \"INDEFINITE\" }"
            )
        )

        assertEquals(emptyList<ProgramTransferIssue>(), ProgramTransferValidation.issuesIn(document))
    }

    @Test
    fun aWeekdayScheduleIsRefusedWhenItIsEmptyDuplicatedOrOutOfIsoOrder() {
        val empty = issuesOf(ProgramTransferFixture.edited("\"weekdays\": [\"MONDAY\", \"WEDNESDAY\", \"FRIDAY\"]", "\"weekdays\": []"))
        val duplicated = issuesOf(ProgramTransferFixture.edited("\"weekdays\": [\"MONDAY\", \"WEDNESDAY\", \"FRIDAY\"]", "\"weekdays\": [\"MONDAY\", \"MONDAY\"]"))
        val outOfOrder = issuesOf(ProgramTransferFixture.edited("\"weekdays\": [\"MONDAY\", \"WEDNESDAY\", \"FRIDAY\"]", "\"weekdays\": [\"FRIDAY\", \"MONDAY\"]"))

        assertTrue("an empty weekday set names no rhythm: ${empty.first().message}", empty.first().message.contains("at least one weekday"))
        assertTrue("a weekday is named once: ${duplicated.first().message}", duplicated.first().message.contains("at most once"))
        assertTrue(
            "the weekdays are held in ISO order: ${outOfOrder.first().message}",
            outOfOrder.first().message.contains("ISO order")
        )
    }

    @Test
    fun aFrequencyOutsideTheWeekIsRefused() {
        val tooFew = issuesOf(
            ProgramTransferFixture.edited(
                "{ \"kind\": \"FIXED_WEEKDAYS\", \"weekdays\": [\"MONDAY\", \"WEDNESDAY\", \"FRIDAY\"] }",
                "{ \"kind\": \"FLEXIBLE_PER_WEEK\", \"sessionsPerWeek\": 0 }"
            )
        )
        val tooMany = issuesOf(
            ProgramTransferFixture.edited(
                "{ \"kind\": \"FIXED_WEEKDAYS\", \"weekdays\": [\"MONDAY\", \"WEDNESDAY\", \"FRIDAY\"] }",
                "{ \"kind\": \"FLEXIBLE_PER_WEEK\", \"sessionsPerWeek\": 8 }"
            )
        )

        assertTrue("${tooFew.first().message}", tooFew.first().message.contains("between 1 and 7"))
        assertTrue("${tooMany.first().message}", tooMany.first().message.contains("between 1 and 7"))
    }

    @Test
    fun aCustomFocusThatDoesNotSumToAHundredIsRefused() {
        val issues = issuesOf(
            ProgramTransferFixture.edited("\"percent\": 60", "\"percent\": 59")
        )

        assertEquals(1, issues.size)
        assertTrue(
            "§8's rule, with the shares it read: ${issues.first().message}",
            issues.first().message.contains("sum to 100%") && issues.first().message.contains("PUSH=59%")
        )
    }

    @Test
    fun aFocusStatedAtNoShareIsRefused() {
        val issues = issuesOf(ProgramTransferFixture.edited("\"percent\": 60", "\"percent\": 0"))

        assertTrue(
            "a focus the user did not ask for is left out of the configuration, not stated at 0%: " +
                "${issues.first().message}",
            issues.first().message.contains("not an allocation")
        )
    }

    @Test
    fun aFocusConfigurationRefusesAnEmptyDuplicatedOrNonCanonicalStatement() {
        val empty = issuesOf(ProgramTransferFixture.document(focus = """{ "goal": "FOCUSED", "focuses": [] }"""))
        val duplicated = issuesOf(
            ProgramTransferFixture.edited(
                """{ "focus": "LEGS", "percent": 40 }""",
                """{ "focus": "PUSH", "percent": 40 }"""
            )
        )
        val outOfOrder = issuesOf(
            ProgramTransferFixture.document(focus = ProgramTransferFixture.CUSTOM_FOCUS_OUT_OF_ORDER)
        )

        assertTrue("${empty.first().message}", empty.first().message.contains("names the focuses"))
        assertTrue("${duplicated.first().message}", duplicated.first().message.contains("one share"))
        assertTrue("${outOfOrder.first().message}", outOfOrder.first().message.contains("own order"))
    }

    // ------------------------------------------------------------------ the plan

    @Test
    fun aPlanElementThatNamesNoExerciseIsRefused() {
        val issues = issuesOf(ProgramTransferFixture.edited("\"exerciseId\": \"plank\"", "\"exerciseId\": \"   \""))

        assertEquals(1, issues.size)
        assertTrue(
            "an exercise is never invented or substituted, and a blank id names none: " +
                "${issues.first().message}",
            issues.first().message.contains("blank")
        )
    }

    @Test
    fun aPrescriptionInADimensionTheTargetModelDoesNotImplementIsRefused() {
        val issues = issuesOf(ProgramTransferFixture.edited("\"dimension\": \"TIME_BASED\"", "\"dimension\": \"SET_BASED\""))

        assertEquals(1, issues.size)
        assertTrue(
            "§10 names five dimensions and implements two: ${issues.first().message}",
            issues.first().message.contains("SET_BASED")
        )
    }

    @Test
    fun aPrescriptionThatComposesNoSetOrAsksForNoWorkIsRefused() {
        val noSets = issuesOf(ProgramTransferFixture.edited("\"perSetTargets\": [30, 30, 45]", "\"perSetTargets\": []"))
        val noWork = issuesOf(ProgramTransferFixture.edited("\"perSetTargets\": [30, 30, 45]", "\"perSetTargets\": [30, 0, 45]"))
        val negative = issuesOf(ProgramTransferFixture.edited("\"perSetTargets\": [8, 8]", "\"perSetTargets\": [8, -1]"))

        assertTrue("${noSets.first().message}", noSets.first().message.contains("composes no set"))
        assertEquals("one set of the three asks for nothing", 1, noWork.size)
        assertTrue(
            "and the finding names the set: ${noWork.first().path}",
            noWork.first().path == "revision.days[2].exercises[0].prescription.perSetTargets[1]"
        )
        assertEquals("a negative target is not work either", 1, negative.size)
    }

    @Test
    fun aRestDayThatPrescribesSomethingIsRefused() {
        val issues = issuesOf(
            ProgramTransferFixture.edited(
                """{ "type": "REST", "exercises": [] }""",
                """{ "type": "REST", "exercises": [ { "exerciseId": "plank", "prescription": { "dimension": "TIME_BASED", "perSetTargets": [30] }, "origin": "USER_AUTHORED", "pinned": false } ] }"""
            )
        )

        assertEquals(1, issues.size)
        assertTrue(
            "§20's rule, with the count it read: ${issues.first().message}",
            issues.first().message.contains("REST") && issues.first().message.contains("1 element")
        )
    }

    @Test
    fun aNamedDayWithABlankNameIsRefused() {
        val issues = issuesOf(ProgramTransferFixture.edited("\"name\": \"Mobility\"", "\"name\": \"  \""))

        assertTrue(
            "an unnamed day states no name; a named one that is blank is neither: ${issues.first().message}",
            issues.first().message.contains("blank")
        )
    }

    @Test
    fun aWorkDayThatPlansNothingIsTheDomainIsRuleAndIsNotRestatedHere() {
        val document = ProgramTransferReader.read(
            ProgramTransferFixture.document(
                days = ProgramTransferFixture.PLAN_WITH_A_WORK_DAY_THAT_PLANS_NOTHING
            )
        )

        assertEquals(
            "an empty work day is a plan the domain's own draft validation refuses, not a transfer rule " +
                "(§6 asks for that reuse rather than a second copy)",
            emptyList<ProgramTransferIssue>(),
            ProgramTransferValidation.issuesIn(document)
        )
    }

    // ------------------------------------------------------------------ helpers

    private fun issuesOf(text: String): List<ProgramTransferIssue> {
        val document = ProgramTransferReader.read(text)
        return ProgramTransferValidation.issuesIn(document)
    }
}
