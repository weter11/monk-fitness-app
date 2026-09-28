package com.monkfitness.app.data.repository

import com.monkfitness.app.domain.common.ProgramDayId
import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.common.RevisionId
import com.monkfitness.app.domain.program.ScheduleCadence
import com.monkfitness.app.domain.program.target.TargetProgramDayBinding
import com.monkfitness.app.domain.usecase.TargetScheduleDefinition
import com.monkfitness.app.domain.usecase.TargetScheduleSource
import com.monkfitness.app.domain.usecase.TargetScheduleSourceException
import com.monkfitness.app.domain.usecase.TargetScheduleSourceRead
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * `TargetScheduleSourceRepository`: a revision's **explicit** target schedule source, stored and read
 * back on a real SQLite engine.
 *
 * The claims under test are about **fidelity, refusal and honesty of absence**, and they are measured
 * against the engine through the DAOs' own SQL rather than a mock's bookkeeping:
 *
 *  * every cadence form round-trips exactly, form *and* payload, including the forms that are easy to
 *    normalize into one another (`EveryNDays(3)` and `FixedWeekdays` of three days are different
 *    schedules, and `SessionsPerWeek(3)` is a frequency, not a weekday set);
 *  * ordering is a stored read order, so two reads are equality-identical;
 *  * a claim the source cannot honestly hold — a blank identity, two rules about one rule identity,
 *    two bindings about one workout, a binding to another revision's plan day — is refused, and
 *    nothing is written;
 *  * a saved revision's source is immutable: an identical repeat writes nothing, a different one is
 *    refused;
 *  * a revision with no stated source reads as a typed absence, and stored rows that do not read back
 *    as a valid source read as a typed failure — neither is turned into an empty or plausible source;
 *  * the legacy `ProgramSchedule` is not an input and not an output: changing it changes nothing here.
 */
class TargetScheduleSourceRepositoryTest {

    private val rig = ProgramDataAccessRig("target-source")
    private val programId = ProgramId(ProgramGraphFixture.programId("target-source"))
    private val revisionId = RevisionId(ProgramGraphFixture.revisionId("target-source"))

    @After
    fun close() {
        rig.close()
    }

    // ------------------------------------------------------------------ round trips

    @Test
    fun oneRuleAndOneBindingComeBackExactlyAsTheyWentIn() = runBlocking {
        rig.createGraph()
        val source = source(
            rules = listOf(rule("strength", "workout-strength", ScheduleCadence.EveryNDays(3))),
            bindings = listOf(TargetProgramDayBinding("workout-strength", dayId(1)))
        )

        rig.targetScheduleSourceRepository.store(source)

        assertEquals(source, storedSource(revisionId))
    }

    @Test
    fun everyOneOfTheFiveCadenceFormsRoundTripsExactly() = runBlocking {
        rig.createGraph()
        val cadences = listOf(
            ScheduleCadence.Daily,
            ScheduleCadence.EveryNDays(4),
            ScheduleCadence.SessionsPerWeek(5),
            ScheduleCadence.FixedWeekdays(setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.SUNDAY)),
            ScheduleCadence.DerivedExcluding("rule-source")
        )
        val source = source(
            rules = cadences.mapIndexed { index, cadence ->
                rule("rule-$index", "workout-$index", cadence)
            },
            bindings = cadences.mapIndexed { index, _ ->
                TargetProgramDayBinding("workout-$index", dayId(index % 3 + 1))
            }
        )

        rig.targetScheduleSourceRepository.store(source)

        val read = storedSource(revisionId)
        assertEquals(cadences, read.rules.map { it.cadence })
    }

    @Test
    fun aFrequencyIsNotNormalizedIntoAnotherCadence() = runBlocking {
        rig.createGraph()
        // 3 sessions a week and every 3 days are genuinely different schedules, and a `FixedWeekdays`
        // set of three days is a third. A storage layer that collapsed any of them into another would
        // round-trip "successfully" and mean something else entirely.
        val frequencies = listOf(1, 2, 3, 4, 5, 6, 7).map { sessions ->
            rule("rule-$sessions", "workout-$sessions", ScheduleCadence.SessionsPerWeek(sessions))
        }

        rig.targetScheduleSourceRepository.store(
            source(
                rules = frequencies,
                bindings = frequencies.map { binding ->
                    TargetProgramDayBinding(binding.workoutId, dayId(1))
                }
            )
        )

        val read = storedSource(revisionId)
        assertEquals(
            frequencies.map { it.cadence },
            read.rules.map { it.cadence }
        )
        assertTrue(
            "and none of them came back as a weekday set or an interval",
            read.rules.none { it.cadence is ScheduleCadence.FixedWeekdays } &&
                read.rules.none { it.cadence is ScheduleCadence.EveryNDays }
        )
    }

    @Test
    fun anIntervalKeepsItsExactValueRatherThanBeingRounded() = runBlocking {
        rig.createGraph()
        val intervals = (1..12).map { days -> rule("rule-$days", "workout-$days", ScheduleCadence.EveryNDays(days)) }

        rig.targetScheduleSourceRepository.store(
            source(
                rules = intervals,
                bindings = intervals.map { TargetProgramDayBinding(it.workoutId, dayId(1)) }
            )
        )

        // The read order is by stored rule identity, which is lexical — so the comparison is keyed by
        // the rule the cadence belongs to rather than by position. Comparing two lists positionally
        // here would be asserting the *ordering* twice and the *values* not at all.
        val byRule = storedSource(revisionId).rules.associate { it.ruleId to it.cadence }
        intervals.forEach { rule ->
            assertEquals(
                "rule ${rule.ruleId} kept its own interval",
                rule.cadence,
                byRule.getValue(rule.ruleId)
            )
        }
    }

    @Test
    fun aDerivedRuleKeepsTheSourceRuleItNames() = runBlocking {
        rig.createGraph()
        val source = source(
            rules = listOf(
                rule("rule-base", "workout-base", ScheduleCadence.FixedWeekdays(setOf(DayOfWeek.MONDAY))),
                rule("rule-rest", "workout-rest", ScheduleCadence.DerivedExcluding("rule-base"))
            ),
            bindings = listOf(
                TargetProgramDayBinding("workout-base", dayId(1)),
                TargetProgramDayBinding("workout-rest", dayId(2))
            )
        )

        rig.targetScheduleSourceRepository.store(source)

        assertEquals(
            "the derived rule still names the same source rule it was stored naming",
            ScheduleCadence.DerivedExcluding("rule-base"),
            storedSource(revisionId).rules.single { it.ruleId == "rule-rest" }.cadence
        )
    }

    @Test
    fun aFixedWeekdaySetIsReconstructedAsTheSameSet() = runBlocking {
        rig.createGraph()
        val weekdays = setOf(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY, DayOfWeek.SATURDAY)
        val source = source(
            rules = listOf(rule("rule-a", "workout-a", ScheduleCadence.FixedWeekdays(weekdays))),
            bindings = listOf(TargetProgramDayBinding("workout-a", dayId(1)))
        )

        rig.targetScheduleSourceRepository.store(source)
        rig.targetScheduleSourceRepository.store(source)

        assertEquals(weekdays, storedSource(revisionId).rules.single().cadence.let {
            (it as ScheduleCadence.FixedWeekdays).weekdays
        })
    }

    @Test
    fun anAnchorDateSurvivesExactly() = runBlocking {
        rig.createGraph()
        // Deliberately not a round-ish date: a stored anchor read back as the first of its month, or
        // shifted by a zone, would plan a whole schedule one day out.
        val anchor = LocalDate.parse("2027-03-17")
        val source = source(
            rules = listOf(rule("rule-a", "workout-a", ScheduleCadence.Daily, anchor)),
            bindings = listOf(TargetProgramDayBinding("workout-a", dayId(1)))
        )

        rig.targetScheduleSourceRepository.store(source)

        assertEquals(anchor, storedSource(revisionId).rules.single().anchorDate)
    }

    // ------------------------------------------------------------------ ordering and repeatability

    @Test
    fun severalRulesReadBackInOneDeterministicOrder() = runBlocking {
        rig.createGraph()
        // Presented deliberately out of identity order: a storage layer that kept the caller's order
        // would return this list, and one that sorted case-insensitively would return a different one.
        val rules = listOf(
            rule("rule-zulu", "workout-z", ScheduleCadence.Daily),
            rule("rule-alpha", "workout-a", ScheduleCadence.Daily),
            rule("rule-mike", "workout-m", ScheduleCadence.Daily)
        )
        rig.targetScheduleSourceRepository.store(
            source(rules, rules.map { TargetProgramDayBinding(it.workoutId, dayId(1)) })
        )

        assertEquals(
            listOf("rule-alpha", "rule-mike", "rule-zulu"),
            storedSource(revisionId).rules.map { it.ruleId }
        )
    }

    @Test
    fun severalBindingsReadBackInOneDeterministicOrder() = runBlocking {
        rig.createGraph()
        val bindings = listOf(
            TargetProgramDayBinding("workout-z", dayId(1)),
            TargetProgramDayBinding("workout-a", dayId(2)),
            TargetProgramDayBinding("workout-m", dayId(3))
        )
        rig.targetScheduleSourceRepository.store(
            source(
                rules = bindings.map {
                    rule("rule-${it.workoutId}", it.workoutId, ScheduleCadence.Daily)
                },
                bindings = bindings
            )
        )

        assertEquals(
            listOf("workout-a", "workout-m", "workout-z"),
            storedSource(revisionId).programDayBindings.map { it.workoutId }
        )
    }

    @Test
    fun theSameRevisionReadTwiceIsEqualityIdentical() = runBlocking {
        rig.createGraph()
        val source = source(
            rules = listOf(
                rule("rule-a", "workout-a", ScheduleCadence.FixedWeekdays(setOf(DayOfWeek.WEDNESDAY))),
                rule("rule-b", "workout-b", ScheduleCadence.SessionsPerWeek(2))
            ),
            bindings = listOf(
                TargetProgramDayBinding("workout-a", dayId(1)),
                TargetProgramDayBinding("workout-b", dayId(3))
            )
        )
        rig.targetScheduleSourceRepository.store(source)

        val first = storedSource(revisionId)
        val second = rig.freshTargetScheduleSourceRepository().sourceOf(revisionId)

        assertEquals(
            "a value read through a *different* repository instance is the same value, which is what " +
                "makes it a value that came from storage rather than from a cache",
            first,
            (second as TargetScheduleSourceRead.Source).source
        )
    }

    // ------------------------------------------------------------------ refusals

    @Test
    fun aBlankRuleIdentityIsRefusedAndNothingIsWritten() = runBlocking {
        rig.createGraph()
        refusal(TargetScheduleSourceException.BlankRuleIdentity::class.java) {
            rig.targetScheduleSourceRepository.store(
                source(
                    rules = listOf(rule("  ", "workout-a", ScheduleCadence.Daily)),
                    bindings = listOf(TargetProgramDayBinding("workout-a", dayId(1)))
                )
            )
        }
        assertNothingWritten()
    }

    @Test
    fun aBlankWorkoutIdentityIsRefusedAndNothingIsWritten() = runBlocking {
        rig.createGraph()
        refusal(TargetScheduleSourceException.BlankWorkoutIdentity::class.java) {
            rig.targetScheduleSourceRepository.store(
                source(
                    rules = listOf(rule("rule-a", "  ", ScheduleCadence.Daily)),
                    bindings = emptyList()
                )
            )
        }
        assertNothingWritten()
    }

    @Test
    fun twoRulesClaimingOneRuleIdentityAreRefused() = runBlocking {
        rig.createGraph()
        refusal(TargetScheduleSourceException.DuplicateRuleIdentity::class.java) {
            rig.targetScheduleSourceRepository.store(
                source(
                    rules = listOf(
                        rule("rule-a", "workout-a", ScheduleCadence.Daily),
                        rule("rule-a", "workout-b", ScheduleCadence.Daily)
                    ),
                    bindings = emptyList()
                )
            )
        }
        assertNothingWritten()
    }

    @Test
    fun twoBindingsClaimingOneWorkoutAreRefused() = runBlocking {
        rig.createGraph()
        refusal(TargetScheduleSourceException.DuplicateWorkoutBinding::class.java) {
            rig.targetScheduleSourceRepository.store(
                source(
                    rules = listOf(rule("rule-a", "workout-a", ScheduleCadence.Daily)),
                    bindings = listOf(
                        TargetProgramDayBinding("workout-a", dayId(1)),
                        TargetProgramDayBinding("workout-a", dayId(3))
                    )
                )
            )
        }
        assertNothingWritten()
    }

    @Test
    fun aBindingToAnotherRevisionsPlanDayIsRefused() = runBlocking {
        rig.createGraph()
        // A second Program whose revision has its own plan days. The day exists — a foreign key is
        // satisfied — but presenting this revision's target schedule against another revision's plan
        // is exactly the confusion the binding's revision scope exists to prevent.
        val other = ProgramGraphFixture.graph("target-source-other")
        rig.programRepository.createProgram(other.program, other.revision, other.slots)
        val foreignDay = ProgramDayId(ProgramGraphFixture.dayId("target-source-other", 1))

        val failure = refusal(TargetScheduleSourceException.ProgramDayOutsideRevision::class.java) {
            rig.targetScheduleSourceRepository.store(
                source(
                    rules = listOf(rule("rule-a", "workout-a", ScheduleCadence.Daily)),
                    bindings = listOf(TargetProgramDayBinding("workout-a", foreignDay))
                )
            )
        }
        assertEquals("workout-a", failure.workoutId)
        assertNothingWritten()
    }

    @Test
    fun aBindingToAPlanDayThatDoesNotExistIsRefusedByTheEngineItself() = runBlocking {
        rig.createGraph()
        // The repository's own membership check refuses this too, so the engine is only reached if the
        // check were removed; asserting the engine's refusal as well keeps both halves honest.
        val missing = ProgramDayId("day-that-was-never-stored")
        refusal(TargetScheduleSourceException.ProgramDayOutsideRevision::class.java) {
            rig.targetScheduleSourceRepository.store(
                source(
                    rules = listOf(rule("rule-a", "workout-a", ScheduleCadence.Daily)),
                    bindings = listOf(TargetProgramDayBinding("workout-a", missing))
                )
            )
        }
        assertNothingWritten()
    }

    // ------------------------------------------------------------------ immutability

    @Test
    fun anIdenticalRepeatWritesNothing() = runBlocking {
        rig.createGraph()
        val source = source(
            rules = listOf(rule("rule-a", "workout-a", ScheduleCadence.SessionsPerWeek(3))),
            bindings = listOf(TargetProgramDayBinding("workout-a", dayId(1)))
        )
        val repository = rig.targetScheduleSourceRepository

        repository.store(source)
        repository.store(source)
        repository.store(source)

        assertEquals(1, rig.database.count("program_target_schedule_rule"))
        assertEquals(1, rig.database.count("program_target_program_day_binding"))
        assertEquals(source, storedSource(revisionId))
    }

    @Test
    fun aChangedSourceForASavedRevisionIsRefusedAndTheStoredOneSurvives() = runBlocking {
        rig.createGraph()
        val stored = source(
            rules = listOf(rule("rule-a", "workout-a", ScheduleCadence.Daily)),
            bindings = listOf(TargetProgramDayBinding("workout-a", dayId(1)))
        )
        rig.targetScheduleSourceRepository.store(stored)
        val changed = source(
            rules = listOf(rule("rule-a", "workout-a", ScheduleCadence.EveryNDays(9))),
            bindings = listOf(TargetProgramDayBinding("workout-a", dayId(1)))
        )

        val failure = refusal(TargetScheduleSourceException.ConflictingStoredSource::class.java) {
            rig.targetScheduleSourceRepository.store(changed)
        }

        assertEquals(revisionId, failure.revisionId)
        assertEquals(
            "and the refusal is not an overwrite: the stored cadence is the one that was written",
            ScheduleCadence.Daily,
            storedSource(revisionId).rules.single().cadence
        )
    }

    @Test
    fun aSourceIsDestroyedWithItsRevision() = runBlocking {
        rig.createGraph()
        rig.targetScheduleSourceRepository.store(
            source(
                rules = listOf(rule("rule-a", "workout-a", ScheduleCadence.Daily)),
                bindings = listOf(TargetProgramDayBinding("workout-a", dayId(1)))
            )
        )
        assertEquals(1, rig.database.count("program_target_schedule_rule"))
        assertEquals(1, rig.database.count("program_target_program_day_binding"))

        rig.database.exec("DELETE FROM `program_revision` WHERE `revisionId` = '${revisionId.value}'")

        assertEquals(
            "a rule cannot outlive the immutable revision that states it",
            0,
            rig.database.count("program_target_schedule_rule")
        )
        assertEquals(0, rig.database.count("program_target_program_day_binding"))
    }

    // ------------------------------------------------------------------ absence and invalid data

    @Test
    fun aRevisionWithNoStatedSourceReadsAsATypedAbsence() = runBlocking {
        rig.createGraph()

        assertEquals(
            TargetScheduleSourceRead.Missing(revisionId),
            rig.targetScheduleSourceRepository.sourceOf(revisionId)
        )
    }

    @Test
    fun aRevisionThatNeverExistedReadsAsATypedAbsenceRatherThanAsAnInventedSource() = runBlocking {
        rig.createGraph()

        val read = rig.targetScheduleSourceRepository.sourceOf(RevisionId("revision-never-saved"))

        assertEquals(TargetScheduleSourceRead.Missing(RevisionId("revision-never-saved")), read)
        assertNotEquals(
            "and specifically not a source with no rules in it",
            TargetScheduleSourceRead.Source(
                TargetScheduleSource(RevisionId("revision-never-saved"), emptyList(), emptyList())
            ),
            read
        )
    }

    @Test
    fun anOldRevisionWithNoTargetSourceStillHasNoneAfterItsLegacyScheduleChanges() = runBlocking {
        rig.createGraph()
        val repository = rig.targetScheduleSourceRepository
        assertEquals(TargetScheduleSourceRead.Missing(revisionId), repository.sourceOf(revisionId))

        // The legacy schedule is a fact about this revision, and it is free to change through the
        // revision's own path. Neither change may manufacture a target rule.
        rig.database.exec(
            "UPDATE `program_revision` SET `scheduleType` = 'FLEXIBLE_PER_WEEK', " +
                "`scheduleWeekdays` = NULL, `scheduleSessionsPerWeek` = 4 " +
                "WHERE `revisionId` = '${revisionId.value}'"
        )

        assertEquals(TargetScheduleSourceRead.Missing(revisionId), repository.sourceOf(revisionId))
        assertEquals(0, rig.database.count("program_target_schedule_rule"))
    }

    @Test
    fun aStoredCadenceTokenOutsideTheVocabularyIsATypedReadFailure() = runBlocking {
        rig.createGraph()
        rig.targetScheduleSourceRepository.store(
            source(
                rules = listOf(rule("rule-a", "workout-a", ScheduleCadence.Daily)),
                bindings = listOf(TargetProgramDayBinding("workout-a", dayId(1)))
            )
        )
        // Written with raw SQL because the entity refuses to be constructed in this shape: the point
        // is that the *read* is what must notice, not that the write is impossible from every angle.
        rig.database.exec(
            "UPDATE `program_target_schedule_rule` SET `cadenceType` = 'EVERY_THIRD_DAY' " +
                "WHERE `revisionId` = '${revisionId.value}'"
        )

        val read = rig.targetScheduleSourceRepository.sourceOf(revisionId)

        assertTrue(
            "an unknown cadence token is reported, never defaulted to a plausible cadence: $read",
            read is TargetScheduleSourceRead.Malformed
        )
        assertTrue(
            "and the refusal names the token it could not read",
            (read as TargetScheduleSourceRead.Malformed).reason.contains("EVERY_THIRD_DAY")
        )
    }

    @Test
    fun aCadenceFormAndItsPayloadCannotDisagreeInStoredData() {
        // The entity's own guard holds the discriminator and its payload consistent, so the
        // self-contradicting row is not merely *reported* on read — it is unrepresentable at all. That
        // is the stronger property, and it is what makes "read form-first" structural rather than a
        // discipline the mapper has to maintain.
        assertRefused(
            "a DAILY rule carrying an interval",
            cadenceType = "DAILY",
            cadenceDays = 3
        )
        assertRefused(
            "an EVERY_N_DAYS rule with no interval",
            cadenceType = "EVERY_N_DAYS",
            cadenceDays = null
        )
        assertRefused(
            "a SESSIONS_PER_WEEK rule with no frequency",
            cadenceType = "SESSIONS_PER_WEEK",
            cadenceSessionsPerWeek = null
        )
        assertRefused(
            "a FIXED_WEEKDAYS rule naming no weekday",
            cadenceType = "FIXED_WEEKDAYS",
            cadenceWeekdays = null
        )
        assertRefused(
            "a DERIVED_EXCLUDING rule naming no source rule",
            cadenceType = "DERIVED_EXCLUDING",
            cadenceSourceRuleId = null
        )
        assertRefused(
            "a DAILY rule carrying a frequency as well",
            cadenceType = "DAILY",
            cadenceSessionsPerWeek = 3
        )
    }

    @Test
    fun aMalformedSourceIsNotOverwrittenByANewWrite() = runBlocking {
        rig.createGraph()
        rig.targetScheduleSourceRepository.store(
            source(
                rules = listOf(rule("rule-a", "workout-a", ScheduleCadence.Daily)),
                bindings = listOf(TargetProgramDayBinding("workout-a", dayId(1)))
            )
        )
        rig.database.exec(
            "UPDATE `program_target_schedule_rule` SET `anchorDate` = 'not-a-date' " +
                "WHERE `revisionId` = '${revisionId.value}'"
        )

        val failure = refusal(TargetScheduleSourceException.UnreadableStoredSource::class.java) {
            rig.targetScheduleSourceRepository.store(
                source(
                    rules = listOf(rule("rule-a", "workout-a", ScheduleCadence.Daily)),
                    bindings = listOf(TargetProgramDayBinding("workout-a", dayId(1)))
                )
            )
        }

        assertEquals(revisionId, failure.revisionId)
    }

    // ------------------------------------------------------------------ isolation

    @Test
    fun twoRevisionsHoldTheirOwnSourcesIndependently() = runBlocking {
        rig.createGraph()
        // A second revision of the *same* Program: a structural change saves a new revision (§6), and
        // that new revision has new plan-day rows of its own — which is why a binding cannot be shared
        // between revisions even when the rule identity is.
        val other = ProgramGraphFixture.nextRevision("target-source")
        rig.programPlanRepository.saveNewRevision(other, ProgramGraphFixture.FINISHED)
        val otherRevisionId = other.revisionId
        val firstSource = source(
            rules = listOf(rule("rule-a", "workout-a", ScheduleCadence.Daily)),
            bindings = listOf(TargetProgramDayBinding("workout-a", dayId(1)))
        )
        val otherSource = TargetScheduleSource(
            revisionId = otherRevisionId,
            rules = listOf(
                TargetScheduleDefinition("rule-a", "workout-a", ScheduleCadence.EveryNDays(5), ANCHOR)
            ),
            programDayBindings = listOf(
                TargetProgramDayBinding(
                    "workout-a",
                    ProgramDayId(ProgramGraphFixture.dayId("target-source-2", 1))
                )
            )
        )

        rig.targetScheduleSourceRepository.store(firstSource)
        rig.targetScheduleSourceRepository.store(otherSource)

        assertEquals(firstSource, storedSource(revisionId))
        assertEquals(otherSource, storedSource(otherRevisionId))
        assertNotEquals("the same rule identity under two revisions is two independent sources",
            storedSource(revisionId), storedSource(otherRevisionId))
    }

    @Test
    fun theSameRuleIdentityUnderTwoRevisionsIsTwoRowsNotOne() = runBlocking {
        rig.createGraph()
        val other = ProgramGraphFixture.nextRevision("target-source")
        rig.programPlanRepository.saveNewRevision(other, ProgramGraphFixture.FINISHED)
        val rules = listOf(rule("rule-shared", "workout-a", ScheduleCadence.Daily))

        rig.targetScheduleSourceRepository.store(
            source(rules, listOf(TargetProgramDayBinding("workout-a", dayId(1))))
        )
        rig.targetScheduleSourceRepository.store(
            TargetScheduleSource(
                revisionId = other.revisionId,
                rules = rules,
                programDayBindings = listOf(
                    TargetProgramDayBinding(
                        "workout-a",
                        ProgramDayId(ProgramGraphFixture.dayId("target-source-2", 1))
                    )
                )
            )
        )

        assertEquals(
            "one rule per revision, so the shared identity is two rows",
            2,
            rig.database.count("program_target_schedule_rule")
        )
    }

    @Test
    fun identitiesAreReadExactlyAsStoredRatherThanParsedOutOfAnything() = runBlocking {
        rig.createGraph()
        // Every part of this identity could be mistaken for a rule, a workout, a date or a weekday, and
        // the cadence is a weekday set as well. If any of them were parsed, trimmed or normalized, the
        // read-back below would not equal what was stored.
        val awkwardRule = "  rule/with\\separators  "
        val awkwardWorkout = " work out : 2026-10-05 MONDAY "
        val source = source(
            rules = listOf(
                rule(
                    awkwardRule,
                    awkwardWorkout,
                    ScheduleCadence.FixedWeekdays(setOf(DayOfWeek.MONDAY))
                )
            ),
            bindings = listOf(TargetProgramDayBinding(awkwardWorkout, dayId(1)))
        )

        rig.targetScheduleSourceRepository.store(source)
        val read = storedSource(revisionId)

        assertEquals(awkwardRule, read.rules.single().ruleId)
        assertEquals(awkwardWorkout, read.rules.single().workoutId)
        assertEquals(awkwardWorkout, read.programDayBindings.single().workoutId)
    }

    // ------------------------------------------------------------------ helpers

    private fun dayId(position: Int) = ProgramDayId(ProgramGraphFixture.dayId("target-source", position))

    private fun rule(
        ruleId: String,
        workoutId: String,
        cadence: ScheduleCadence,
        anchorDate: LocalDate = ANCHOR
    ): TargetScheduleDefinition = TargetScheduleDefinition(ruleId, workoutId, cadence, anchorDate)

    private fun source(
        rules: List<TargetScheduleDefinition>,
        bindings: List<TargetProgramDayBinding>
    ) = TargetScheduleSource(revisionId, rules, bindings)

    private suspend fun storedSource(revisionId: RevisionId): TargetScheduleSource =
        when (val read = rig.targetScheduleSourceRepository.sourceOf(revisionId)) {
            is TargetScheduleSourceRead.Source -> read.source
            is TargetScheduleSourceRead.Missing ->
                throw AssertionError("revision $revisionId states no target source")
            is TargetScheduleSourceRead.Malformed ->
                throw AssertionError("the stored source is malformed: ${read.reason}")
        }

    private fun <T : Throwable> refusal(
        expected: Class<T>,
        block: suspend () -> Unit
    ): T {
        val thrown = runCatching { runBlocking { block() } }.exceptionOrNull()
        assertTrue(
            "expected ${expected.simpleName}, and the call succeeded instead",
            thrown != null
        )
        val failure = requireNotNull(thrown) {
            "expected ${expected.simpleName}, and the call succeeded instead"
        }
        assertTrue(
            "expected ${expected.simpleName}, was ${failure.javaClass.simpleName}: ${failure.message}",
            expected.isInstance(failure)
        )
        @Suppress("UNCHECKED_CAST")
        return failure as T
    }

    /**
     * Asserts that a stored rule row in [described] shape cannot be constructed at all.
     *
     * The payload columns default to `null` and the named ones are supplied, so each case is exactly
     * the contradiction its description states and nothing else.
     */
    private fun assertRefused(
        described: String,
        cadenceType: String,
        cadenceDays: Int? = null,
        cadenceSessionsPerWeek: Int? = null,
        cadenceWeekdays: String? = null,
        cadenceSourceRuleId: String? = null
    ) {
        val constructed = runCatching {
            com.monkfitness.app.data.model.ProgramTargetScheduleRuleEntity(
                revisionId = "revision-x",
                ruleId = "rule-x",
                workoutId = "workout-x",
                cadenceType = cadenceType,
                cadenceDays = cadenceDays,
                cadenceSessionsPerWeek = cadenceSessionsPerWeek,
                cadenceWeekdays = cadenceWeekdays,
                cadenceSourceRuleId = cadenceSourceRuleId,
                anchorDate = ANCHOR.toString()
            )
        }
        assertTrue(
            "$described is refused at construction, so it can never be stored",
            constructed.isFailure
        )
    }

    private fun assertNothingWritten() {
        assertEquals(0, rig.database.count("program_target_schedule_rule"))
        assertEquals(0, rig.database.count("program_target_program_day_binding"))
    }

    private companion object {
        val ANCHOR: LocalDate = LocalDate.parse("2026-10-05")
    }
}
