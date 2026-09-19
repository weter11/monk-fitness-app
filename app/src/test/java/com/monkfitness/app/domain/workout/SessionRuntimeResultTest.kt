package com.monkfitness.app.domain.workout

import com.monkfitness.app.domain.adaptive.decision.AdaptiveDecision
import com.monkfitness.app.domain.common.AdjustmentId
import com.monkfitness.app.domain.common.DecisionId
import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.common.RevisionId
import com.monkfitness.app.domain.common.SessionId
import com.monkfitness.app.domain.common.SlotId
import com.monkfitness.app.domain.program.SlotStatus
import com.monkfitness.app.domain.program.WorkoutSlot
import java.lang.reflect.Modifier
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The values §30 step 8 reports with — and what they are structurally incapable of carrying.
 *
 * Every operation of the session runtime answers with one of three things (§28): a value, a *rule* that
 * refused it, or a failure. What matters about the envelope is not that it has three cases but what it
 * does **not** have: no case that reports a workout as a score, no refusal that carries an amount of
 * work, no completion that could describe a decision the runtime did not receive. That is asserted here,
 * on the compiled values, because a field added to one of them is exactly how a measurement would creep
 * into a layer that owns only the state of an attempt.
 */
class SessionRuntimeResultTest {

    // ------------------------------------------------------------------ the envelope

    @Test
    fun anOperationAnswersWithExactlyThreeShapes() {
        assertEquals(
            "an operation succeeds, is refused by a rule, or fails — and nothing else (§28)",
            listOf("Failure", "Refused", "Success"),
            SessionRuntimeResult::class.java.declaredClasses.filterNot { it.isInterface }.map { it.simpleName }.sorted()
        )
    }

    @Test
    fun theFailureCaseCarriesTheCauseAndNotAMessage() {
        val cause = IllegalStateException("the database is gone")
        val failure = SessionRuntimeResult.Failure(cause)

        assertEquals(
            "§28's `SYSTEM_FAILURE` is the throwable itself, so a caller can act on it rather than " +
                "parse it",
            cause,
            failure.cause
        )
        val asResult: SessionRuntimeResult<Nothing> = SessionRuntimeResult.Failure(cause)
        assertEquals(
            "and it is not turned into an empty value on the way out (§33): the cause reaches the caller " +
                "in the case that carries causes",
            "Failure",
            asResult::class.java.simpleName
        )
    }

    @Test
    fun aRefusalCarriesTheRuleItBrokeAndNoAmountOfWork() {
        val refusal = SessionRefusal.SetIsNotInThePrescribedUnit(
            sessionExerciseId = com.monkfitness.app.domain.common.SessionExerciseId("session-ex-1"),
            dimension = com.monkfitness.app.domain.prescription.PrescriptionDimension.REP_BASED,
            completedReps = 0,
            durationSeconds = 30
        )
        val fields = refusal.javaClass.declaredFields
            .filterNot { Modifier.isStatic(it.modifiers) || it.name.startsWith("$") }
            .map { it.name }
            .sorted()

        assertEquals(
            "a refusal names the rule, the occurrence, the two amounts the caller handed in and the " +
                "sentence that explains it — the amounts are the *rejected input*, never a performance " +
                "the layer measured",
            listOf("completedReps", "dimension", "durationSeconds", "message", "sessionExerciseId"),
            fields
        )
        assertTrue(
            "every refusal explains itself in a sentence a screen can present: ${refusal.message}",
            refusal.message.isNotBlank() && refusal.message.contains("REP_BASED")
        )
    }

    @Test
    fun theRefusalVocabularyHasTheRulesThisLayerCouldBreak() {
        val cases = SessionRefusal::class.java.declaredClasses.filterNot { it.isInterface }.map { it.simpleName }.sorted()

        assertEquals(
            "one case per rule §19, §16, §4 and §20 make about an attempt: an opportunity that is not " +
                "stored, is completed, is withdrawn, presents nothing, or names a revision or day that " +
                "do not hold; a second attempt already in progress; a session that is not in progress; " +
                "an occurrence that is another session's or was skipped; a set outside its prescription; " +
                "and an adaptive decision that is not about a future opportunity of this completion " +
                "(§30 step 12 replaced the P8-era same-slot rule with the future-slot one, and the " +
                "refusal carries the clause it broke in `AdaptiveTargetRefusal`)",
            listOf(
                "AdaptiveDecisionIsNotAboutAFutureOpportunityOfThisCompletion",
                "AdaptiveTargetRefusal",
                "OccurrenceIsNotOfThisSession",
                "OccurrenceWasSkipped",
                "PlanDayPresentsNothing",
                "RevisionIsOfAnotherProgram",
                "RevisionNotFound",
                "SessionIsNotInProgress",
                "SessionNotFound",
                "SetIsNotInThePrescribedUnit",
                "SlotIsAlreadyBeingWorkedOut",
                "SlotIsAlreadyCompleted",
                "SlotIsSuperseded",
                "SlotNamesAPlanDayTheRevisionDoesNotPresent",
                "SlotNotFound",
                "StoredAdjustmentIsNotOfThisRevision"
            ),
            cases
        )
        assertFalse(
            "and there is no conflict mechanism here: this stage has no draft and no staleness check, " +
                "so §28's `RevisionConflict` is not a refusal it can produce",
            cases.contains("RevisionConflict")
        )
    }

    // ------------------------------------------------------------------ the completion

    @Test
    fun aCompletionIsTheSessionTheSlotAndWhatTheAdaptiveHalfWrote() {
        val fields = SessionCompletion::class.java.declaredFields
            .filterNot { Modifier.isStatic(it.modifiers) || it.name.startsWith("$") }
            .map { it.name }

        assertEquals(
            "one completion reports exactly the three legs §27 writes — the attempt, the opportunity it " +
                "took, and what the adaptive half stored — so nothing about a workout can be reported " +
                "that is not one of those",
            listOf("session", "slot", "adaptive"),
            fields
        )

        val completion = SessionCompletion(
            session = completedSession(),
            slot = completedSlot(),
            adaptive = AdaptiveOutcome.NothingDecided
        )

        assertFalse(
            "a completion that recorded no decision says so instead of implying one",
            completion.recordedAnAdaptiveDecision
        )
        assertTrue(
            "and one that did says so too",
            completion.copy(
                adaptive = AdaptiveOutcome.Stored(DecisionId("decision-1"), AdjustmentId("adjustment-1"))
            ).recordedAnAdaptiveDecision
        )
    }

    @Test
    fun theAdaptiveHalfOfACompletionHasExactlyThreeShapesAndNoneInventsADecision() {
        assertEquals(
            "the adaptive stage evaluated no window, evaluated one and held, or decided something — " +
                "three shapes and no fourth: a completion is not evidence for a decision, so nothing " +
                "lets the runtime make one up, and \"a window that decided nothing\" is told apart " +
                "from \"no window at all\" because only the first advances a family's bookkeeping " +
                "(§11, §30 step 12)",
            listOf("Decided", "NothingDecided", "WindowEvaluated"),
            AdaptiveCompletion::class.java.declaredClasses.filterNot { it.isInterface }.map { it.simpleName }.sorted()
        )
        assertEquals(
            "every shape that evaluated a window carries the family's state leg §27 writes, so the " +
                "unit of work cannot be opened without it",
            listOf("familyState"),
            AdaptiveCompletion.WindowEvaluated::class.java.declaredFields
                .filterNot { Modifier.isStatic(it.modifiers) || it.name.startsWith("$") }
                .map { it.name }
        )
        assertEquals(
            "and what the completion reports mirrors that: one decision was stored, with or without the " +
                "adjustment it applied, or nothing was",
            listOf("NothingDecided", "Stored"),
            AdaptiveOutcome::class.java.declaredClasses.filterNot { it.isInterface }.map { it.simpleName }.sorted()
        )
        assertEquals(
            "which is why the two cases of what is *stored* are told apart by values — the decision and " +
                "the adjustment it produced — rather than by another field saying whether a write happened",
            listOf("decisionId", "adjustmentId"),
            AdaptiveOutcome.Stored::class.java.declaredFields
                .filterNot { Modifier.isStatic(it.modifiers) || it.name.startsWith("$") }
                .map { it.name }
        )
    }

    @Test
    fun theCompletionValuesArePureValuesOfTheDomain() {
        val values = listOf(
            SessionCompletion::class.java,
            AdaptiveOutcome.Stored::class.java,
            AdaptiveOutcome.NothingDecided::class.java,
            AdaptiveCompletion.Decided::class.java
        )

        val offenders = values.flatMap { type ->
            type.declaredFields
                .filterNot { Modifier.isStatic(it.modifiers) }
                .mapNotNull { field ->
                    val name = if (field.type.isArray) field.type.componentType.name else field.type.name
                    val allowed = name.startsWith("kotlin.") || name.startsWith("java.") ||
                        name.startsWith("com.monkfitness.app.domain.") || field.type.isPrimitive
                    if (allowed) null else "${type.simpleName}.${field.name} is a $name"
                }
        }

        assertTrue(
            "these values may only mention the domain, `kotlin.` and `java.` — no Room type, no entity, " +
                "no Android type and no holder that would let state accumulate. Found: $offenders",
            offenders.isEmpty()
        )
        values.forEach { type ->
            assertTrue(
                "${type.simpleName} is an immutable value",
                type.declaredFields
                    .filterNot { Modifier.isStatic(it.modifiers) }
                    .all { Modifier.isFinal(it.modifiers) }
            )
        }
    }

    // ------------------------------------------------------------------ fixtures

    private fun completedSlot() = WorkoutSlot(
        slotId = SlotId("slot-1"),
        programId = ProgramId("program-1"),
        revisionId = RevisionId("revision-1"),
        programDayId = com.monkfitness.app.domain.common.ProgramDayId("day-1"),
        plannedFor = LocalDate.parse("2026-09-21"),
        status = SlotStatus.PLANNED
    )

    private fun completedSession() = WorkoutSession(
        sessionId = SessionId("session-1"),
        slotId = SlotId("slot-1"),
        programId = ProgramId("program-1"),
        revisionId = RevisionId("revision-1"),
        snapshot = WorkoutSessionSnapshot(
            sessionId = SessionId("session-1"),
            capturedAt = java.time.Instant.parse("2026-09-21T07:30:00Z"),
            workout = EffectiveWorkout(
                slotId = SlotId("slot-1"),
                programId = ProgramId("program-1"),
                revisionId = RevisionId("revision-1"),
                plannedFor = LocalDate.parse("2026-09-21"),
                computedAt = java.time.Instant.parse("2026-09-21T07:30:00Z"),
                exercises = emptyList()
            )
        ),
        status = SessionStatus.IN_PROGRESS,
        startedAt = java.time.Instant.parse("2026-09-21T07:30:00Z")
    )
}
