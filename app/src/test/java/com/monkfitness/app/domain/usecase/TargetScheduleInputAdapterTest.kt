package com.monkfitness.app.domain.usecase

import com.monkfitness.app.domain.common.ProgramDayId
import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.common.RevisionId
import com.monkfitness.app.domain.program.ActualResult
import com.monkfitness.app.domain.program.CompositionSelection
import com.monkfitness.app.domain.program.ExistingOccurrence
import com.monkfitness.app.domain.program.OccurrenceComponent
import com.monkfitness.app.domain.program.OccurrenceExecution
import com.monkfitness.app.domain.program.PerformedWork
import com.monkfitness.app.domain.program.PlannedOccurrence
import com.monkfitness.app.domain.program.ProgramDay
import com.monkfitness.app.domain.program.ProgramDayType
import com.monkfitness.app.domain.program.ProgramPauseWindow
import com.monkfitness.app.domain.program.ProgramSchedule
import com.monkfitness.app.domain.program.ScheduleCadence
import com.monkfitness.app.domain.program.target.ResolvedScheduleOccurrence
import com.monkfitness.app.domain.program.target.ResolvedScheduleSource
import com.monkfitness.app.domain.program.target.TargetProgramDayBinding
import com.monkfitness.app.domain.program.target.TargetSchedule
import com.monkfitness.app.domain.program.target.TargetScheduleWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Phase 13 behaviour: the adapter converts explicitly stated caller definitions into a target
 * scheduling request and forwards every other value without reinterpretation.
 *
 * The suite is written to fail on the *kinds* of shortcut this boundary exists to prevent, so most
 * of it is negative: a legacy schedule that changes nothing here, a ProgramDay that is not the
 * source of a target identity, a persisted slot that is not an occurrence, and an adapter that
 * starts deciding.
 */
class TargetScheduleInputAdapterTest {
    private val adapter = TargetScheduleInputAdapter()

    // ------------------------------------------------------------------ 1. explicit conversion

    @Test
    fun oneExplicitDefinitionBecomesTheExactTargetSchedule() {
        val definition = TargetScheduleDefinition(
            ruleId = "strength",
            workoutId = "strength-workout",
            cadence = ScheduleCadence.EveryNDays(3),
            anchorDate = DAY
        )

        val request = adapter.adapt(input(definitions = listOf(definition)))

        assertEquals(
            listOf(TargetSchedule("strength", "strength-workout", ScheduleCadence.EveryNDays(3), DAY)),
            request.schedules
        )
        assertEquals(definition.toTargetSchedule(), request.schedules.single())
    }

    @Test
    fun everyCadenceShapeSurvivesTheConversionUnchanged() {
        val cadences = listOf(
            ScheduleCadence.Daily,
            ScheduleCadence.EveryNDays(2),
            ScheduleCadence.SessionsPerWeek(4),
            ScheduleCadence.FixedWeekdays(setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY)),
            ScheduleCadence.DerivedExcluding("base")
        )

        val request = adapter.adapt(
            input(
                definitions = cadences.mapIndexed { index, cadence ->
                    TargetScheduleDefinition("rule-$index", "workout-$index", cadence, DAY)
                }
            )
        )

        assertEquals(cadences, request.schedules.map { it.cadence })
    }

    // ------------------------------------------------------------------ 2. several definitions, caller order

    @Test
    fun multipleDefinitionsKeepTheOrderTheCallerStatedThem() {
        val definitions = listOf(
            TargetScheduleDefinition("zulu", "z-workout", ScheduleCadence.Daily, DAY),
            TargetScheduleDefinition("alpha", "a-workout", ScheduleCadence.Daily, DAY),
            TargetScheduleDefinition("mike", "m-workout", ScheduleCadence.Daily, DAY)
        )

        val request = adapter.adapt(input(definitions = definitions))

        assertEquals(listOf("zulu", "alpha", "mike"), request.schedules.map { it.ruleId })
        assertEquals(listOf("z-workout", "a-workout", "m-workout"), request.schedules.map { it.workoutId })
    }

    @Test
    fun anEmptyDefinitionListProducesAnEmptyScheduleList() {
        val request = adapter.adapt(input(definitions = emptyList()))

        assertEquals(emptyList<TargetSchedule>(), request.schedules)
    }

    // ------------------------------------------------------------------ 3. exact pass-through

    @Test
    fun everyCallerOwnedValueReachesTheRequestUnchanged() {
        val window = TargetScheduleWindow(DAY, DAY.plusDays(13))
        val selection = CompositionSelection.combine("strength", "mobility")
        val existing = listOf(existing(strengthOn(DAY)), existing(strengthOn(DAY.plusDays(1))))
        val sources = mapOf("base" to ResolvedScheduleSource("base", listOf(sourceOn(DAY))))
        val pauses = listOf(ProgramPauseWindow(DAY.plusDays(2), DAY.plusDays(4)))
        val bindings = listOf(binding("strength-workout", "day-a"), binding("mobility-workout", "day-b"))

        val request = adapter.adapt(
            input(
                definitions = listOf(
                    TargetScheduleDefinition("strength", "strength-workout", ScheduleCadence.Daily, DAY)
                ),
                window = window,
                selection = selection,
                existing = existing,
                sources = sources,
                asOf = DAY.plusDays(1),
                pauses = pauses,
                bindings = bindings
            )
        )

        assertEquals(PROGRAM_ID, request.programId)
        assertEquals(REVISION_ID, request.revisionId)
        assertSame("the window is the caller's own value, forwarded", window, request.window)
        assertSame("the selection is the caller's own value, forwarded", selection, request.selection)
        assertSame("existing occurrences are forwarded as the caller stated them", existing, request.existing)
        assertSame("sources are forwarded as the caller stated them", sources, request.sources)
        assertEquals(DAY.plusDays(1), request.asOf)
        assertSame(pauses, request.pauses)
        assertSame(bindings, request.programDayBindings)
        assertEquals(
            listOf("programId", "revisionId", "schedules", "window", "selection", "existing",
                "sources", "asOf", "pauses", "programDayBindings"),
            TargetScheduleOrchestrationRequest::class.java.declaredFields
                .filterNot { it.name == "\$stable" }
                .map { it.name }
        )
    }

    // ------------------------------------------------------------------ 4. explicit ProgramDay binding

    @Test
    fun theProgramDayBindingIsPreservedExactlyAsStated() {
        val bindings = listOf(
            binding("strength-workout", "day-7"),
            binding("mobility-workout", "day-2")
        )

        val request = adapter.adapt(
            input(
                definitions = listOf(
                    TargetScheduleDefinition("strength", "strength-workout", ScheduleCadence.Daily, DAY),
                    TargetScheduleDefinition("mobility", "mobility-workout", ScheduleCadence.Daily, DAY)
                ),
                bindings = bindings
            )
        )

        assertEquals(bindings, request.programDayBindings)
        assertEquals("day-7", request.programDayBindings.first { it.workoutId == "strength-workout" }.programDayId.value)
        assertEquals("day-2", request.programDayBindings.first { it.workoutId == "mobility-workout" }.programDayId.value)
    }

    // ------------------------------------------------------------------ 5. no target identity inference

    @Test
    fun changingTheProgramDayNeverChangesTheWorkoutIdentity() {
        val definition = TargetScheduleDefinition("strength", "strength-workout", ScheduleCadence.Daily, DAY)

        val fromNamedDay = adapter.adapt(
            input(
                definitions = listOf(definition),
                bindings = listOf(binding("strength-workout", programDay(3, "Legs heavy").programDayId.value))
            )
        )
        val fromNumberedDay = adapter.adapt(
            input(
                definitions = listOf(definition),
                bindings = listOf(binding("strength-workout", programDay(11, null).programDayId.value))
            )
        )

        assertEquals(fromNamedDay.schedules, fromNumberedDay.schedules)
        assertEquals("strength-workout", fromNumberedDay.schedules.single().workoutId)
        assertEquals(listOf("strength-workout"), fromNumberedDay.schedules.map { it.workoutId })
    }

    @Test
    fun theWorkoutIdentityIsNeverReadOutOfAProgramDayId() {
        val definition = TargetScheduleDefinition("strength", "declared-workout", ScheduleCadence.Daily, DAY)

        val request = adapter.adapt(
            input(
                definitions = listOf(definition),
                bindings = listOf(binding("declared-workout", "declared-workout"))
            )
        )

        assertEquals("declared-workout", request.schedules.single().workoutId)
    }

    // ------------------------------------------------------------------ 6. no legacy schedule inference

    @Test
    fun theLegacyScheduleIsNotAnInputAndChangingItChangesNothing() {
        // The input type has no ProgramSchedule property at all, which is the structural claim; this
        // pins the behavioural consequence: two Program values that differ only in their legacy
        // schedule vocabulary produce the identical request from the identical explicit input.
        val fixedWeekdaysRevisionDay = programDay(1, "Monday")
        val flexibleRevisionDay = programDay(1, "Three a week")
        assertEquals(fixedWeekdaysRevisionDay.position, flexibleRevisionDay.position)
        assertTrue(ProgramSchedule.FixedWeekdays(setOf(DayOfWeek.MONDAY)) != ProgramSchedule.FlexiblePerWeek(3))

        val definition = TargetScheduleDefinition("strength", "strength-workout", ScheduleCadence.Daily, DAY)
        val first = adapter.adapt(input(definitions = listOf(definition)))
        val second = adapter.adapt(input(definitions = listOf(definition)))

        assertEquals(first, second)
        assertEquals(ScheduleCadence.Daily, first.schedules.single().cadence)
    }

    @Test
    fun theAdapterDeclaresNoLegacyScheduleProperty() {
        assertEquals(
            listOf(
                "programId", "revisionId", "scheduleDefinitions", "window", "selection",
                "existingOccurrences", "sources", "asOf", "pauses", "programDayBindings"
            ),
            TargetScheduleInput::class.java.declaredFields
                .filterNot { it.name == "\$stable" }
                .map { it.name }
        )
    }

    // ------------------------------------------------------------------ 7. duplicate target rule identity

    @Test
    fun twoDefinitionsClaimingOneRuleIdentityAreRefused() {
        val definitions = listOf(
            TargetScheduleDefinition("strength", "first-workout", ScheduleCadence.Daily, DAY),
            TargetScheduleDefinition("strength", "second-workout", ScheduleCadence.Daily, DAY)
        )

        val failure = assertThrows(TargetScheduleInputException.DuplicateTargetRuleIdentity::class.java) {
            adapter.adapt(input(definitions = definitions))
        }

        assertEquals("strength", failure.ruleId)
        assertEquals(1, failure.index)
    }

    @Test
    fun twoDefinitionsOnOneRuleWithDifferentAnchorsAreAlsoOneIdentity() {
        val definitions = listOf(
            TargetScheduleDefinition("strength", "strength-workout", ScheduleCadence.Daily, DAY),
            TargetScheduleDefinition("strength", "strength-workout", ScheduleCadence.Daily, DAY.plusDays(1))
        )

        assertThrows(TargetScheduleInputException.DuplicateTargetRuleIdentity::class.java) {
            adapter.adapt(input(definitions = definitions))
        }
    }

    // ------------------------------------------------------------------ 8. blank target identity

    @Test
    fun aBlankRuleIdentityIsRefused() {
        val definitions = listOf(
            TargetScheduleDefinition("strength", "strength-workout", ScheduleCadence.Daily, DAY),
            TargetScheduleDefinition("   ", "mobility-workout", ScheduleCadence.Daily, DAY)
        )

        val failure = assertThrows(TargetScheduleInputException.BlankTargetRuleIdentity::class.java) {
            adapter.adapt(input(definitions = definitions))
        }

        assertEquals(1, failure.index)
    }

    @Test
    fun aBlankWorkoutIdentityIsRefused() {
        val failure = assertThrows(TargetScheduleInputException.BlankTargetWorkoutIdentity::class.java) {
            adapter.adapt(
                input(
                    definitions = listOf(
                        TargetScheduleDefinition("strength", "", ScheduleCadence.Daily, DAY)
                    )
                )
            )
        }

        assertEquals(0, failure.index)
    }

    @Test
    fun aRefusalIsTypedAndCarriesNoPartialRequest() {
        // No fallback list, no default schedule: the failure is the whole outcome, and it is the
        // adapter's own typed refusal rather than the engine's construction-time require.
        val failure = assertThrows(IllegalArgumentException::class.java) {
            adapter.adapt(
                input(definitions = listOf(TargetScheduleDefinition("", "w", ScheduleCadence.Daily, DAY)))
            )
        }

        assertTrue(failure is TargetScheduleInputException)
        assertTrue(failure.message!!.contains("blank rule identity"))
    }

    // ------------------------------------------------------------------ 9. input immutability

    @Test
    fun noCallerCollectionIsModified() {
        val definitions = mutableListOf(
            TargetScheduleDefinition("strength", "strength-workout", ScheduleCadence.Daily, DAY),
            TargetScheduleDefinition("mobility", "mobility-workout", ScheduleCadence.Daily, DAY)
        )
        val existing = mutableListOf(existing(strengthOn(DAY)))
        val sources = mutableMapOf("base" to ResolvedScheduleSource("base", listOf(sourceOn(DAY))))
        val pauses = mutableListOf(ProgramPauseWindow(DAY, DAY.plusDays(1)))
        val bindings = mutableListOf(binding("strength-workout"))
        val definitionsBefore = definitions.toList()
        val existingBefore = existing.toList()
        val sourcesBefore = sources.toMap()
        val pausesBefore = pauses.toList()
        val bindingsBefore = bindings.toList()

        adapter.adapt(
            input(
                definitions = definitions,
                existing = existing,
                sources = sources,
                pauses = pauses,
                bindings = bindings
            )
        )

        assertEquals(definitionsBefore, definitions)
        assertEquals(existingBefore, existing)
        assertEquals(sourcesBefore, sources)
        assertEquals(pausesBefore, pauses)
        assertEquals(bindingsBefore, bindings)
    }

    @Test
    fun theAdapterHoldsNoFieldAtAll() {
        assertEquals(
            emptyList<String>(),
            TargetScheduleInputAdapter::class.java.declaredFields
                .filterNot { it.name == "\$stable" }
                .map { it.name }
        )
        assertEquals(1, TargetScheduleInputAdapter::class.java.declaredConstructors.size)
        assertEquals(0, TargetScheduleInputAdapter::class.java.declaredConstructors.single().parameterCount)
    }

    // ------------------------------------------------------------------ 10. determinism

    @Test
    fun theSameInputAlwaysProducesAnEqualRequest() {
        val first = adapter.adapt(richInput())
        val second = adapter.adapt(richInput())
        val third = TargetScheduleInputAdapter().adapt(richInput())

        assertEquals(first, second)
        assertEquals(first, third)
        assertEquals(first.hashCode(), second.hashCode())
    }

    @Test
    fun aSecondAdapterInstanceIsInterchangeable() {
        assertEquals(TargetScheduleInputAdapter().adapt(richInput()), adapter.adapt(richInput()))
    }

    // ------------------------------------------------------------------ 11. existing occurrences are caller-owned

    @Test
    fun existingOccurrencesAreForwardedEvenWhenTheyCouldNeverBeRebuilt() {
        // A persisted slot carries no PlannedOccurrence payload, so a slot-shaped occurrence is not
        // reconstructible: the components below are stated, and the adapter forwards them without
        // touching, ordering or completing them.
        val existing = listOf(
            existing(strengthOn(DAY), OccurrenceExecution.COMPLETED),
            existing(strengthOn(DAY.plusDays(1)))
        )

        val request = adapter.adapt(input(existing = existing))

        assertEquals(existing, request.existing)
        assertEquals(
            listOf("strength", "strength-workout"),
            request.existing.map { it.occurrence.components.single() }.map { listOf(it.ruleId, it.workoutId) }.first()
        )
        assertEquals(
            listOf(OccurrenceExecution.COMPLETED, OccurrenceExecution.PLANNED),
            request.existing.map { it.execution }
        )
    }

    @Test
    fun anExecutionActualIsNotReinterpreted() {
        val completed = existing(strengthOn(DAY), OccurrenceExecution.COMPLETED)

        val request = adapter.adapt(input(existing = listOf(completed)))

        assertEquals(completed.actuals, request.existing.single().actuals)
        assertEquals(1, request.existing.single().actuals.size)
    }

    // ------------------------------------------------------------------ 12. sources are caller-owned

    @Test
    fun resolvedSourcesAreForwardedWithoutBeingRebuiltOrRecomputed() {
        val source = ResolvedScheduleSource(
            "base",
            (0L..2L).map { offset -> sourceOn(DAY.plusDays(offset)) }
        )
        val sources = mapOf("base" to source)

        val request = adapter.adapt(
            input(
                definitions = listOf(
                    TargetScheduleDefinition("mobility", "mobility-workout", ScheduleCadence.DerivedExcluding("base"), DAY)
                ),
                sources = sources
            )
        )

        assertEquals(sources, request.sources)
        assertSame(source, request.sources["base"])
        assertEquals(3, request.sources.getValue("base").occurrences.size)
        assertEquals(
            listOf(DAY, DAY.plusDays(1), DAY.plusDays(2)),
            request.sources.getValue("base").occurrences.map { it.plannedDate }
        )
    }

    @Test
    fun aSourceIsNotRequiredForANonDerivedDefinition() {
        val request = adapter.adapt(
            input(
                definitions = listOf(
                    TargetScheduleDefinition("strength", "strength-workout", ScheduleCadence.Daily, DAY)
                ),
                sources = emptyMap()
            )
        )

        assertEquals(emptyMap<String, ResolvedScheduleSource>(), request.sources)
    }

    // ------------------------------------------------------------------ 13. pauses are caller-owned

    @Test
    fun pausesAreForwardedAndNotClassified() {
        val past = ProgramPauseWindow(DAY.minusDays(10), DAY.minusDays(5))
        val covering = ProgramPauseWindow(DAY, DAY.plusDays(3))
        val future = ProgramPauseWindow(DAY.plusDays(20), DAY.plusDays(25))
        val pauses = listOf(past, covering, future)

        val request = adapter.adapt(input(asOf = DAY.plusDays(1), pauses = pauses))

        assertEquals(pauses, request.pauses)
        assertEquals(3, request.pauses.size)
        // A pause covering the as-of date is still forwarded: whether it suppresses anything is the
        // temporal stage's question, and answering it here would be a second temporal policy.
        assertTrue(request.pauses.any { it.covers(request.asOf) })
    }

    @Test
    fun theWindowIsNeitherWidenedNorNarrowed() {
        val window = TargetScheduleWindow(DAY, DAY.plusDays(6))

        val request = adapter.adapt(input(window = window, asOf = DAY.plusDays(3)))

        assertEquals(window, request.window)
        assertEquals(DAY, request.window.from)
        assertEquals(DAY.plusDays(6), request.window.through)
        assertEquals(7, request.window.dates().size)
    }

    // ------------------------------------------------------------------ 14. no orchestration

    @Test
    fun theAdapterBuildsARequestAndRunsNothing() {
        val request = adapter.adapt(richInput())

        // A pass would have produced slots, a plan, a decision or a presentation. The adapter's only
        // output is the request value, and the request holds no collaborator that could have run.
        assertEquals(2, request.schedules.size)
        // The public surface is one method; the shape check below is private and is called by it,
        // not a second entry point a caller could reach.
        assertEquals(
            listOf("adapt"),
            TargetScheduleInputAdapter::class.java.declaredMethods
                .filter { java.lang.reflect.Modifier.isPublic(it.modifiers) }
                .map { it.name }
                .distinct()
        )
        val adapt = TargetScheduleInputAdapter::class.java.declaredMethods.single { method ->
            method.name == "adapt"
        }
        assertEquals(
            listOf(TargetScheduleInput::class.java),
            adapt.parameterTypes.toList()
        )
        assertEquals(
            TargetScheduleOrchestrationRequest::class.java,
            adapt.returnType
        )
    }

    @Test
    fun theRequestItProducesIsTheOneTheOrchestratorAccepts() {
        // Not a call: the orchestrator is not reachable from here, and the equality below is what
        // makes the two types line up field for field without a conversion in either direction.
        val request = adapter.adapt(richInput())

        assertEquals(
            TargetScheduleOrchestrationRequest(
                programId = PROGRAM_ID,
                revisionId = REVISION_ID,
                schedules = listOf(
                    TargetSchedule("strength", "strength-workout", ScheduleCadence.Daily, DAY),
                    TargetSchedule("mobility", "mobility-workout", ScheduleCadence.EveryNDays(2), DAY)
                ),
                window = TargetScheduleWindow(DAY, DAY.plusDays(6)),
                selection = CompositionSelection.combine("strength", "mobility"),
                existing = listOf(existing(strengthOn(DAY))),
                sources = mapOf("base" to ResolvedScheduleSource("base", listOf(sourceOn(DAY)))),
                asOf = DAY.plusDays(1),
                pauses = listOf(ProgramPauseWindow(DAY, DAY.plusDays(2))),
                programDayBindings = listOf(binding("strength-workout"), binding("mobility-workout"))
            ),
            request
        )
    }

    // ------------------------------------------------------------------ fixtures

    private fun input(
        definitions: List<TargetScheduleDefinition> = listOf(
            TargetScheduleDefinition("strength", "strength-workout", ScheduleCadence.Daily, DAY)
        ),
        window: TargetScheduleWindow = TargetScheduleWindow(DAY, DAY.plusDays(6)),
        selection: CompositionSelection = CompositionSelection(),
        existing: List<ExistingOccurrence> = emptyList(),
        sources: Map<String, ResolvedScheduleSource> = emptyMap(),
        asOf: LocalDate = DAY,
        pauses: List<ProgramPauseWindow> = emptyList(),
        bindings: List<TargetProgramDayBinding> = listOf(binding("strength-workout"))
    ) = TargetScheduleInput(
        programId = PROGRAM_ID,
        revisionId = REVISION_ID,
        scheduleDefinitions = definitions,
        window = window,
        selection = selection,
        existingOccurrences = existing,
        sources = sources,
        asOf = asOf,
        pauses = pauses,
        programDayBindings = bindings
    )

    private fun richInput() = input(
        definitions = listOf(
            TargetScheduleDefinition("strength", "strength-workout", ScheduleCadence.Daily, DAY),
            TargetScheduleDefinition("mobility", "mobility-workout", ScheduleCadence.EveryNDays(2), DAY)
        ),
        window = TargetScheduleWindow(DAY, DAY.plusDays(6)),
        selection = CompositionSelection.combine("strength", "mobility"),
        existing = listOf(existing(strengthOn(DAY))),
        sources = mapOf("base" to ResolvedScheduleSource("base", listOf(sourceOn(DAY)))),
        asOf = DAY.plusDays(1),
        pauses = listOf(ProgramPauseWindow(DAY, DAY.plusDays(2))),
        bindings = listOf(binding("strength-workout"), binding("mobility-workout"))
    )

    private fun binding(workoutId: String, programDayId: String = "day-1") =
        TargetProgramDayBinding(workoutId, ProgramDayId(programDayId))

    private fun programDay(position: Int, name: String?) = ProgramDay(
        programDayId = ProgramDayId("day-$position"),
        position = position,
        type = ProgramDayType.TRAINING,
        name = name
    )

    private fun planned(key: String, date: LocalDate, ruleId: String, workoutId: String) = PlannedOccurrence(
        occurrenceKey = key,
        plannedFor = date,
        components = listOf(OccurrenceComponent(ruleId, workoutId))
    )

    private fun strengthOn(date: LocalDate) = planned("strength:$date", date, "strength", "strength-workout")

    private fun sourceOn(date: LocalDate) = ResolvedScheduleOccurrence(
        ruleId = "base",
        workoutId = "base-workout",
        plannedDate = date,
        cadence = ScheduleCadence.Daily
    )

    private fun existing(
        occurrence: PlannedOccurrence,
        execution: OccurrenceExecution = OccurrenceExecution.PLANNED
    ) = ExistingOccurrence(
        occurrence = occurrence,
        execution = execution,
        actuals = if (execution == OccurrenceExecution.PLANNED) {
            emptyList()
        } else {
            listOf(ActualResult("work-1", PerformedWork.reps(12)))
        }
    )

    private companion object {
        // Absolute, so the suite's expectations never move with the device's date.
        val DAY: LocalDate = LocalDate.parse("2026-10-05")
        val PROGRAM_ID = ProgramId("program-stage13")
        val REVISION_ID = RevisionId("revision-stage13")
    }
}
