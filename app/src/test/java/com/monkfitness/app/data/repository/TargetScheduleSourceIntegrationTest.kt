package com.monkfitness.app.data.repository

import com.monkfitness.app.domain.common.ProgramDayId
import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.common.RevisionId
import com.monkfitness.app.domain.program.CompositionSelection
import com.monkfitness.app.domain.program.ScheduleCadence
import com.monkfitness.app.domain.program.target.TargetProgramDayBinding
import com.monkfitness.app.domain.program.target.TargetScheduleWindow
import com.monkfitness.app.domain.usecase.TargetScheduleInput
import com.monkfitness.app.domain.usecase.TargetScheduleInputAdapter
import com.monkfitness.app.domain.usecase.TargetScheduleOrchestrationRequest
import com.monkfitness.app.domain.usecase.TargetScheduleSource
import com.monkfitness.app.domain.usecase.TargetScheduleSourceBridge
import com.monkfitness.app.domain.usecase.TargetScheduleSourceRead
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * The whole Stage 18 chain, end to end, on a real SQLite engine and **without the legacy scheduler**.
 *
 * ```text
 * persist an explicit, revision-owned target source
 *         ↓
 * read it back through the bridge
 *         ↓
 * build TargetScheduleInput from what was read
 *         ↓
 * the existing TargetScheduleInputAdapter
 *         ↓
 * TargetScheduleOrchestrationRequest
 * ```
 *
 * This is the claim the phase exists to make: a future production caller can obtain an honest
 * `TargetScheduleInput` from persisted, explicitly stated target semantics. The proof that nothing in
 * the chain invented a fact is that the request's schedules are *field-for-field* the values that were
 * stored — and the proof that the legacy contour was not involved is that this suite never constructs
 * `ProgramScheduler`, `SlotPlanner` or a legacy slot, and that the stored source is unchanged when the
 * revision's legacy schedule is.
 *
 * The pass is **not** run. Producing a `TargetScheduleOrchestrationRequest` is the last step here, and
 * the orchestrator stays a separately callable contour: cutover is a later phase, and wiring this
 * suite to one would perform it.
 */
class TargetScheduleSourceIntegrationTest {

    private val rig = ProgramDataAccessRig("target-source-integration")
    private val programId = ProgramId(ProgramGraphFixture.programId("target-source-integration"))
    private val revisionId = RevisionId(ProgramGraphFixture.revisionId("target-source-integration"))

    @After
    fun close() {
        rig.close()
    }

    @Test
    fun aStoredSourceBecomesARequestThroughTheExistingAdapter() = runBlocking {
        rig.createGraph()
        val source = TargetScheduleSource(
            revisionId = revisionId,
            rules = listOf(
                TargetScheduleDefinitionFactory.daily("rule-strength", "workout-strength"),
                TargetScheduleDefinitionFactory.weekdays(
                    "rule-mobility",
                    "workout-mobility",
                    setOf(DayOfWeek.WEDNESDAY, DayOfWeek.SATURDAY)
                )
            ),
            programDayBindings = listOf(
                TargetProgramDayBinding("workout-strength", dayId(1)),
                TargetProgramDayBinding("workout-mobility", dayId(3))
            )
        )
        rig.targetScheduleSourceRepository.store(source)

        // read it back
        val bridge = TargetScheduleSourceBridge(rig.targetScheduleSourceRepository)
        val read = bridge.definitionsAndBindingsOf(revisionId)
        assertTrue("the source reads back as stored", read is TargetScheduleSourceRead.Source)
        val stored = (read as TargetScheduleSourceRead.Source).source

        // build the input from what was read, and let the *existing* adapter do its job unchanged
        val input = input(
            definitions = stored.rules,
            bindings = stored.programDayBindings
        )
        val request: TargetScheduleOrchestrationRequest = TargetScheduleInputAdapter().adapt(input)

        assertEquals(
            "every rule crossed unchanged: identity, workout, cadence form, cadence payload, anchor",
            stored.rules.map { it.toTargetSchedule() },
            request.schedules
        )
        assertEquals(stored.programDayBindings, request.programDayBindings)
        assertEquals(
            "and the request names the same immutable revision the source is owned by",
            revisionId,
            request.revisionId
        )
        assertEquals(
            "the plan days came from the stored bindings, in the source's own deterministic read " +
                "order — nothing about them was derived from a position, a name or a date",
            listOf(dayId(3), dayId(1)),
            request.programDayBindings.map { it.programDayId }
        )
    }

    @Test
    fun theRequestRunsTheStoredCadenceTheStoredWay() = runBlocking {
        rig.createGraph()
        // The request's schedules are what the resolver would read. Pinning the resolved dates here is
        // what proves the cadence survived storage as a *semantic* and not merely as text: a
        // `SessionsPerWeek(3)` that had been normalized to a weekday set or to an interval would
        // resolve to different dates.
        val source = TargetScheduleSource(
            revisionId = revisionId,
            rules = listOf(
                TargetScheduleDefinitionFactory.frequency("rule-strength", "workout-strength", 3)
            ),
            programDayBindings = listOf(TargetProgramDayBinding("workout-strength", dayId(1)))
        )
        rig.targetScheduleSourceRepository.store(source)

        val bridge = TargetScheduleSourceBridge(rig.targetScheduleSourceRepository)
        val stored = (bridge.definitionsAndBindingsOf(revisionId)
            as TargetScheduleSourceRead.Source).source
        val request = TargetScheduleInputAdapter().adapt(
            input(stored.rules, stored.programDayBindings)
        )

        val resolved = com.monkfitness.app.domain.program.target.TargetScheduleResolver.resolve(
            schedules = request.schedules,
            window = TargetScheduleWindow(ANCHOR, ANCHOR.plusDays(13))
        )

        assertEquals(
            "MON / WED / FRI, twice over the fortnight — the stored frequency's own spread, not an " +
                "every-third-day progression",
            listOf(
                ANCHOR,
                ANCHOR.plusDays(2),
                ANCHOR.plusDays(4),
                ANCHOR.plusDays(7),
                ANCHOR.plusDays(9),
                ANCHOR.plusDays(11)
            ),
            resolved.map { it.plannedDate }
        )
    }

    @Test
    fun aRevisionWithNoStatedSourceProducesNoRequestAtAll() = runBlocking {
        rig.createGraph()
        val bridge = TargetScheduleSourceBridge(rig.targetScheduleSourceRepository)

        val read = bridge.definitionsAndBindingsOf(revisionId)

        assertEquals(
            "the caller is told the source is missing; it is not handed an empty pair of lists to " +
                "schedule against, and there is no legacy fallback to take instead",
            TargetScheduleSourceRead.Missing(revisionId),
            read
        )
        assertTrue(read is TargetScheduleSourceRead.Missing)
    }

    @Test
    fun aMalformedStoredSourceProducesNoRequestAtAll() = runBlocking {
        rig.createGraph()
        rig.targetScheduleSourceRepository.store(
            TargetScheduleSource(
                revisionId = revisionId,
                rules = listOf(TargetScheduleDefinitionFactory.daily("rule-a", "workout-a")),
                programDayBindings = listOf(TargetProgramDayBinding("workout-a", dayId(1)))
            )
        )
        // A cadence token outside the vocabulary. The entity's own guard defers an unknown token to
        // the mapper, which owns the vocabulary — so the row *is* storable, and the read is what has
        // to notice, which is exactly the claim under test.
        rig.database.exec(
            "UPDATE `program_target_schedule_rule` SET `cadenceType` = 'TWICE_A_FORTNIGHT' " +
                "WHERE `revisionId` = '${revisionId.value}'"
        )
        val bridge = TargetScheduleSourceBridge(rig.targetScheduleSourceRepository)

        val read = bridge.definitionsAndBindingsOf(revisionId)

        assertTrue(
            "stored rows that contradict the vocabulary are reported, not read as some other schedule: $read",
            read is TargetScheduleSourceRead.Malformed
        )
    }

    @Test
    fun theLegacyScheduleIsNeitherAnInputNorAnOutputOfThisChain() = runBlocking {
        rig.createGraph()
        val legacyBefore = rig.database.rows(
            "SELECT `scheduleType`, `scheduleWeekdays`, `scheduleSessionsPerWeek` " +
                "FROM `program_revision` WHERE `revisionId` = '${revisionId.value}'"
        ).single()
        val source = TargetScheduleSource(
            revisionId = revisionId,
            rules = listOf(TargetScheduleDefinitionFactory.daily("rule-a", "workout-a")),
            programDayBindings = listOf(TargetProgramDayBinding("workout-a", dayId(1)))
        )
        rig.targetScheduleSourceRepository.store(source)
        val bridge = TargetScheduleSourceBridge(rig.targetScheduleSourceRepository)
        val stored = (bridge.definitionsAndBindingsOf(revisionId)
            as TargetScheduleSourceRead.Source).source

        // Change the revision's legacy schedule after the fact.
        rig.database.exec(
            "UPDATE `program_revision` SET `scheduleType` = 'FIXED_WEEKDAYS', " +
                "`scheduleWeekdays` = 'SATURDAY,SUNDAY', `scheduleSessionsPerWeek` = NULL " +
                "WHERE `revisionId` = '${revisionId.value}'"
        )

        val reread = (bridge.definitionsAndBindingsOf(revisionId) as TargetScheduleSourceRead.Source).source
        assertEquals(stored, reread)
        assertTrue(
            "and the target rule never became a restatement of the legacy schedule",
            stored.rules.single().cadence == ScheduleCadence.Daily
        )
        assertNotEquals(
            "the legacy schedule really did change, so the assertion above was not vacuous",
            legacyBefore,
            rig.database.rows(
                "SELECT `scheduleType`, `scheduleWeekdays`, `scheduleSessionsPerWeek` " +
                    "FROM `program_revision` WHERE `revisionId` = '${revisionId.value}'"
            ).single()
        )
    }

    // ------------------------------------------------------------------ helpers

    private fun dayId(position: Int) =
        ProgramDayId(ProgramGraphFixture.dayId("target-source-integration", position))

    /**
     * The caller's half of [TargetScheduleInput]: the eight values this phase explicitly does **not**
     * decide. They are stated here because a caller owns them, and because a bridge that decided any
     * of them would be making a scheduling decision.
     */
    private fun input(
        definitions: List<com.monkfitness.app.domain.usecase.TargetScheduleDefinition>,
        bindings: List<TargetProgramDayBinding>
    ) = TargetScheduleInput(
        programId = programId,
        revisionId = revisionId,
        scheduleDefinitions = definitions,
        window = TargetScheduleWindow(ANCHOR, ANCHOR.plusDays(13)),
        selection = CompositionSelection(),
        existingOccurrences = emptyList(),
        sources = emptyMap(),
        asOf = ANCHOR,
        pauses = emptyList(),
        programDayBindings = bindings
    )

    private companion object {
        val ANCHOR: LocalDate = LocalDate.parse("2026-10-05")

        /** Kept separate so a failure names which cadence form broke, not just "the rules". */
        object TargetScheduleDefinitionFactory {
            fun daily(ruleId: String, workoutId: String) =
                com.monkfitness.app.domain.usecase.TargetScheduleDefinition(
                    ruleId, workoutId, ScheduleCadence.Daily, ANCHOR
                )

            fun weekdays(ruleId: String, workoutId: String, days: Set<DayOfWeek>) =
                com.monkfitness.app.domain.usecase.TargetScheduleDefinition(
                    ruleId, workoutId, ScheduleCadence.FixedWeekdays(days), ANCHOR
                )

            fun frequency(ruleId: String, workoutId: String, sessionsPerWeek: Int) =
                com.monkfitness.app.domain.usecase.TargetScheduleDefinition(
                    ruleId, workoutId, ScheduleCadence.SessionsPerWeek(sessionsPerWeek), ANCHOR
                )
        }
    }
}
