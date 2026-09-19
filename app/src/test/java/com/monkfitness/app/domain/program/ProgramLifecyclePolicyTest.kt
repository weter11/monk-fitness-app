package com.monkfitness.app.domain.program

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import com.monkfitness.app.domain.program.ProgramOperationResult
import com.monkfitness.app.domain.program.ProgramOperationRefusal
import com.monkfitness.app.domain.program.ProgramTransition
import com.monkfitness.app.domain.program.ProgramTransitionResult
import com.monkfitness.app.domain.program.MyPrograms
import com.monkfitness.app.domain.usecase.ProgramLifecycleService
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The transition table itself, as a pure decision — no database, no service, no clock.
 *
 * [ProgramLifecyclePolicy] is the one place the lifecycle's rules live, so this suite drives every
 * status pair through it. A mutation of the table fails one of the two exhaustive tests by name, and
 * the rule-specific tests below pin the blueprint's own wording (§3) to the decision: a planned start
 * date is not a start, `PAUSED` is an interval, `COMPLETED` is terminal, and archive is not a state.
 *
 * Pairing this with [ProgramLifecycleTest] is deliberate: the policy decides, the service applies, and
 * a rule that only one of the two tests could not be said to hold.
 */
class ProgramLifecyclePolicyTest {

    // ------------------------------------------------------------------ the legal table

    @Test
    fun theLegalTransitionsAreExactlyThese() {
        // The legal pairs, as (operation, from) — keyed on the operation because START and RESUME
        // both reach RUNNING and only the operation distinguishes their guards (§3).
        val legal = listOf(
            ProgramTransition.START to LifecycleStatus.NOT_STARTED,
            ProgramTransition.PAUSE to LifecycleStatus.RUNNING,
            ProgramTransition.RESUME to LifecycleStatus.PAUSED,
            ProgramTransition.COMPLETE to LifecycleStatus.RUNNING,
            ProgramTransition.COMPLETE to LifecycleStatus.PAUSED
        )
        LifecycleStatus.entries.forEach { from ->
            listOf(
                ProgramTransition.START, ProgramTransition.PAUSE, ProgramTransition.RESUME,
                ProgramTransition.COMPLETE
            ).forEach { transition ->
                val expected = (transition to from) in legal
                assertEquals(
                    "$transition from $from is ${if (expected) "legal" else "illegal"} (§3)",
                    expected,
                    ProgramLifecyclePolicy.isLegalTransition(from, transition)
                )
            }
        }
    }

    @Test
    fun theFourOperationsReachTheLegalTransitions() {
        assertEquals(LifecycleStatus.RUNNING, ProgramTransition.START.toStatus)
        assertEquals(LifecycleStatus.PAUSED, ProgramTransition.PAUSE.toStatus)
        assertEquals(LifecycleStatus.RUNNING, ProgramTransition.RESUME.toStatus)
        assertEquals(LifecycleStatus.COMPLETED, ProgramTransition.COMPLETE.toStatus)
        // Archive's target is a sentinel: it moves no lifecycle at all.
        assertEquals(
            "ARCHIVE is not a lifecycle state (§3)",
            LifecycleStatus.NOT_STARTED,
            ProgramTransition.ARCHIVE.toStatus
        )
    }

    // ------------------------------------------------------------------ the decisions

    @Test
    fun startIsAllowedOnlyFromNotStarted() {
        allowed(ProgramTransition.START, from = LifecycleStatus.NOT_STARTED)
        noOp(ProgramTransition.START, from = LifecycleStatus.RUNNING)
        refused(ProgramTransition.START, from = LifecycleStatus.PAUSED)
        refused(ProgramTransition.START, from = LifecycleStatus.COMPLETED)
    }

    @Test
    fun pauseIsAllowedOnlyFromRunning() {
        allowed(ProgramTransition.PAUSE, from = LifecycleStatus.RUNNING)
        refused(ProgramTransition.PAUSE, from = LifecycleStatus.NOT_STARTED)
        noOp(ProgramTransition.PAUSE, from = LifecycleStatus.PAUSED)
        refused(ProgramTransition.PAUSE, from = LifecycleStatus.COMPLETED)
    }

    @Test
    fun resumeIsAllowedOnlyWhenAPauseIsInEffect() {
        allowed(ProgramTransition.RESUME, from = LifecycleStatus.PAUSED, openPause = true)
        refused(ProgramTransition.RESUME, from = LifecycleStatus.PAUSED, openPause = false)
        noOp(ProgramTransition.RESUME, from = LifecycleStatus.RUNNING, openPause = false)
        refused(ProgramTransition.RESUME, from = LifecycleStatus.COMPLETED, openPause = true)
    }

    @Test
    fun completeIsAllowedFromRunningAndPausedAndIsTerminal() {
        allowed(ProgramTransition.COMPLETE, from = LifecycleStatus.RUNNING)
        allowed(ProgramTransition.COMPLETE, from = LifecycleStatus.PAUSED)
        refused(ProgramTransition.COMPLETE, from = LifecycleStatus.NOT_STARTED)
        noOp(ProgramTransition.COMPLETE, from = LifecycleStatus.COMPLETED)
    }

    @Test
    fun aRefusalNamesTheTransitionAndTheCurrentStatus() {
        val decision = ProgramLifecyclePolicy.decision(
            LifecycleStatus.COMPLETED,
            ProgramTransition.RESUME,
            openPause = false,
            archived = false
        ) as ProgramTransitionResult.Refused

        assertEquals(ProgramTransition.RESUME, decision.transition)
        assertEquals(LifecycleStatus.COMPLETED, decision.fromStatus)
        assertTrue(
            "the reason names the operation: ${decision.reason}",
            decision.reason.contains("RESUME")
        )
        assertTrue(
            "and it names the state the Program is in: ${decision.reason}",
            decision.reason.contains("COMPLETED")
        )
    }

    @Test
    fun aProgramAlreadyInTheTargetStatusIsANoOpNotAnError() {
        val already = ProgramLifecyclePolicy.decision(
            LifecycleStatus.RUNNING,
            ProgramTransition.START,
            openPause = false,
            archived = false
        )

        assertTrue("START on a RUNNING Program is a no-op, not a refusal", already is ProgramTransitionResult.AlreadyThere)
    }

    @Test
    fun anArchivedProgramStillTransitionsLifecycles() {
        // The archive stamp is not consulted by the transition table (§3).
        allowed(ProgramTransition.COMPLETE, from = LifecycleStatus.RUNNING, archived = true)
        allowed(ProgramTransition.PAUSE, from = LifecycleStatus.RUNNING, archived = true)
        allowed(ProgramTransition.RESUME, from = LifecycleStatus.PAUSED, archived = true, openPause = true)
    }

    @Test
    fun archiveIsADecisionOfItsOwnAndNeverRefuses() {
        val allowed = ProgramLifecyclePolicy.archiveDecision(LifecycleStatus.RUNNING, archived = false)
        assertTrue(allowed is ProgramTransitionResult.Allowed)

        val already = ProgramLifecyclePolicy.archiveDecision(LifecycleStatus.COMPLETED, archived = true)
        assertTrue(
            "an already-archived Program is an idempotent no-op, not an error",
            already is ProgramTransitionResult.AlreadyThere
        )
    }

    // ------------------------------------------------------------------ the value edit

    @Test
    fun applyingATransitionChangesOnlyTheLifecycleAndItsStamps() {
        val now = java.time.Instant.parse("2026-09-18T09:00:00Z")
        val program = Program(
            programId = com.monkfitness.app.domain.common.ProgramId("program-a"),
            name = "A",
            description = "",
            source = ProgramSource.USER,
            lifecycleStatus = LifecycleStatus.NOT_STARTED,
            currentRevisionId = com.monkfitness.app.domain.common.RevisionId("revision-a"),
            createdAt = now.minusSeconds(60),
            updatedAt = now.minusSeconds(60)
        )

        val started = program.applying(ProgramTransition.START, now)

        assertEquals(LifecycleStatus.RUNNING, started.lifecycleStatus)
        assertEquals("the start is factual", now, started.actualStartDate)
        assertEquals(now, started.updatedAt)
        assertEquals(
            "and nothing structural moved — no revision (§6)",
            program.currentRevisionId,
            started.currentRevisionId
        )

        val paused = started.applying(ProgramTransition.PAUSE, now.plusSeconds(60))
        assertEquals(LifecycleStatus.PAUSED, paused.lifecycleStatus)
        assertEquals("a pause does not rewrite the factual start", now, paused.actualStartDate)

        val completed = paused.applying(ProgramTransition.COMPLETE, now.plusSeconds(120))
        assertEquals(LifecycleStatus.COMPLETED, completed.lifecycleStatus)
        assertEquals("nor does a completion", now, completed.actualStartDate)
    }

    @Test
    fun applyingAnArchiveStampsTheArchiveAndMovesNoLifecycle() {
        val now = java.time.Instant.parse("2026-09-18T09:00:00Z")
        val running = aProgram(LifecycleStatus.RUNNING, startedAt = now.minusSeconds(60))

        val archived = running.applying(ProgramTransition.ARCHIVE, now)

        assertTrue(archived.isArchived)
        assertEquals(now, archived.archivedAt)
        assertEquals("the lifecycle is exactly what it was", LifecycleStatus.RUNNING, archived.lifecycleStatus)

        val back = archived.applying(ProgramTransition.UNARCHIVE, now.plusSeconds(60))
        assertFalse(back.isArchived)
        assertEquals(LifecycleStatus.RUNNING, back.lifecycleStatus)
    }

    @Test
    fun aPlannedStartDateIsNeverReadByThePolicy() {
        // The decision functions take no date at all, which is the whole of the rule (§3): there is
        // no parameter through which a LocalDate could start a Program.
        val decision = ProgramLifecyclePolicy.decision(
            LifecycleStatus.NOT_STARTED,
            ProgramTransition.START,
            openPause = false,
            archived = false
        )
        assertTrue(decision is ProgramTransitionResult.Allowed)
        // And the NOT_STARTED status a planned date would leave the Program in requires no start.
        assertFalse(LifecycleStatus.NOT_STARTED.isTerminal)
    }

    // ------------------------------------------------------------------ helpers

    private fun allowed(
        transition: ProgramTransition,
        from: LifecycleStatus,
        openPause: Boolean = false,
        archived: Boolean = false
    ) {
        val decision = ProgramLifecyclePolicy.decision(from, transition, openPause, archived)
        assertTrue("$transition from $from should be allowed, was $decision", decision is ProgramTransitionResult.Allowed)
    }

    private fun refused(
        transition: ProgramTransition,
        from: LifecycleStatus,
        openPause: Boolean = false,
        archived: Boolean = false
    ) {
        val decision = ProgramLifecyclePolicy.decision(from, transition, openPause, archived)
        assertTrue("$transition from $from should be refused, was $decision", decision is ProgramTransitionResult.Refused)
    }

    private fun noOp(
        transition: ProgramTransition,
        from: LifecycleStatus,
        openPause: Boolean = false,
        archived: Boolean = false
    ) {
        val decision = ProgramLifecyclePolicy.decision(from, transition, openPause, archived)
        assertTrue(
            "$transition from $from is an idempotent no-op, not an illegality: $decision",
            decision is ProgramTransitionResult.AlreadyThere
        )
    }

    private fun aProgram(status: LifecycleStatus, startedAt: java.time.Instant): Program = Program(
        programId = com.monkfitness.app.domain.common.ProgramId("program-a"),
        name = "A",
        description = "",
        source = ProgramSource.USER,
        lifecycleStatus = status,
        currentRevisionId = com.monkfitness.app.domain.common.RevisionId("revision-a"),
        createdAt = startedAt.minusSeconds(120),
        updatedAt = startedAt.minusSeconds(60),
        actualStartDate = if (status == LifecycleStatus.NOT_STARTED) null else startedAt
    )
}
