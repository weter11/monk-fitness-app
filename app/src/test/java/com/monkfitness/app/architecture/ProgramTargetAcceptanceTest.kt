package com.monkfitness.app.architecture

import com.monkfitness.app.domain.adaptive.integration.AdaptiveIntegrationOutcome
import com.monkfitness.app.domain.adaptive.integration.AdaptiveIntegrationResult
import com.monkfitness.app.domain.adaptive.integration.NoDeclaredProgression
import com.monkfitness.app.domain.adaptive.integration.NoExerciseFamilyClassification
import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.prescription.PrescriptionDimension
import com.monkfitness.app.domain.program.ProgramDayType
import com.monkfitness.app.domain.program.ProgramSchedulingResult
import com.monkfitness.app.domain.program.SlotStatus
import com.monkfitness.app.domain.program.transfer.ProgramTransferResult
import com.monkfitness.app.domain.progress.ProgressScope
import com.monkfitness.app.domain.usecase.ProgramAdaptiveIntegration
import com.monkfitness.app.domain.usecase.SessionRuntime
import com.monkfitness.app.domain.workout.SessionRuntimeResult
import com.monkfitness.app.domain.workout.SessionStatus
import com.monkfitness.app.ui.programs.ProgramSessionController
import com.monkfitness.app.ui.programs.SessionStage
import com.monkfitness.app.ui.programs.ProgramsRig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **The §30 step 15 acceptance scenario, over the real production services.**
 *
 * This is the stage's end-to-end claim, and it is deliberately *not* a Compose test — this project has no
 * Compose harness, and the brief allows service-level seams. What it exercises is the composition that
 * matters: the **same** repositories, the **same** Scheduler, the **same** `SessionRuntime`, the **same**
 * integration and the **same** progress layer the application wires, composed in the order a user's
 * actions compose them:
 *
 * ```text
 * Create Program → Save Revision 1 → Select → the Scheduler plans the Slot
 *   → Start Session → immutable Snapshot → Confirm Sets → the adaptive leg evaluated → Finish
 *   → Slot completed → Progress observes it
 *   → Edit (a real plan change) → Revision 2, and the old Session is unchanged
 *   → Export → Import as a new Program (its own identities) → delete it → the original is intact
 * ```
 *
 * ### The one thing it asserts an *absence* of
 *
 * The adaptive leg of a completion ends where production ends: with no persisted progression relation and
 * no exercise→family classification, the integration reports its honest boundary and nothing adaptive is
 * written. The test asserts **that**, rather than a fabricated adaptation that would need data the
 * catalogue does not carry (§10) — a test that made the engine adapt would be inventing the authoritative
 * dataset, which is exactly what this stage forbids.
 */
class ProgramTargetAcceptanceTest {

    @Test
    fun theWholeTargetLifecycleComposes() = runBlocking {
        val rig = ProgramsRig("acceptance")
        try {
            val database = rig.database
            val runtime = SessionRuntime(
                planRepository = rig.transfer.planRepository,
                scheduleRepository = rig.transfer.scheduleRepository,
                sessionRepository = rig.transfer.sessionRepository,
                adaptiveRepository = rig.transfer.adaptiveRepository,
                clock = rig.clock,
                idGenerator = rig.ids,
                zone = rig.zone,
                inTransaction = { block -> database.transaction { block() } }
            )
            val integration = ProgramAdaptiveIntegration(
                planRepository = rig.transfer.planRepository,
                scheduleRepository = rig.transfer.scheduleRepository,
                sessionRepository = rig.transfer.sessionRepository,
                adaptiveRepository = rig.transfer.adaptiveRepository,
                relations = NoDeclaredProgression,
                classification = NoExerciseFamilyClassification,
                clock = rig.clock,
                idGenerator = rig.ids,
                zone = rig.zone
            )

            // The production UI half, over the same runtime: the screen renders this object and every
            // operation it offers is a call on the runtime. Driving the scenario through it — rather than
            // through the runtime directly — is what makes "the target runtime is the production path"
            // measurable instead of assumed.
            val session = ProgramSessionController(
                runtime = runtime,
                adaptive = integration,
                lifecycle = rig.transfer.lifecycleService,
                catalogue = { rig.catalogueOptions }
            )

            // ---- 1. Create a Program — as §27's whole unit ------------------------------------------
            val createdId = rig.createProgramThroughTheUi("Acceptance Program")
            assertNotNull("the Save stored a Program", createdId)
            val id = ProgramId(createdId!!)
            assertEquals("a creation mints exactly one revision", 1, rig.revisionCount(id))

            // Revised with the creation remediation: the anchor and the initial opportunities are part
            // of the creation unit itself. The previous version of this scenario asserted the *defect*
            // here — a refused pass and a manual `setPlannedStartDate` before anything could be
            // scheduled — and that manual stepping is exactly what no longer exists: the request named
            // no exact date, so the anchor is today from the injected clock and calendar, and the
            // Scheduler's initial opportunities were written in the same transaction.
            val createdProgram = rig.storedProgram(id)!!
            assertEquals(
                "no exact date was chosen, so plannedStartDate is today from the injected clock",
                rig.clock.now().atZone(rig.zone).toLocalDate(),
                createdProgram.plannedStartDate
            )
            assertEquals(
                "creating is not starting (§3)",
                com.monkfitness.app.domain.program.LifecycleStatus.NOT_STARTED,
                createdProgram.lifecycleStatus
            )
            assertTrue(
                "and the creation unit carries the Scheduler's initial opportunities",
                rig.slotsOf(id).isNotEmpty()
            )

            // ---- 2. Select it, and take the next startable opportunity -------------------------------
            rig.controller.select(createdId)
            assertEquals("the selection is the global one", id, rig.selectedProgramId())

            assertTrue(
                "a pass runs on the anchored Program — the Scheduler still refuses only anchorless " +
                    "ones, and a creation is never anchorless",
                rig.transfer.scheduler.schedule(id) is ProgramSchedulingResult.Success
            )
            val next = (rig.transfer.scheduler.nextOpportunity(id) as ProgramSchedulingResult.Success).value
            assertNotNull("the Scheduler names a next opportunity", next)
            val slotId = next!!.slotId
            assertEquals(SlotStatus.PLANNED, next.status)

            // ---- 3. Start the Session through the production state holder ---------------------------
            session.open(slotId.value)
            assertEquals("the screen presents the attempt", SessionStage.PRESENTING, session.state.value.stage)
            val sessionId = com.monkfitness.app.domain.common.SessionId(session.state.value.sessionId!!)
            assertEquals("the only production runtime started it", slotId, runtimeRead(runtime, sessionId).slotId)
            assertTrue("the snapshot presents something", session.state.value.exercises.isNotEmpty())
            assertEquals("and it is bound to the opportunity it started", slotId.value, session.state.value.slotId)

            val capturedBefore = runtimeRead(runtime, sessionId).snapshot
            assertTrue("the captured presentation is stored", capturedBefore.workout.exercises.isNotEmpty())

            // ---- 4. Confirm every set of every occurrence, through the screen's own operations -------
            var guard = 0
            while (!session.state.value.everythingConfirmed && guard++ < 20) {
                val current = session.state.value.currentExercise
                    ?: break
                if (current.dimension == PrescriptionDimension.TIME_BASED) {
                    session.confirmSet(durationSeconds = current.nextTarget)
                } else {
                    session.confirmSet(completedReps = current.nextTarget)
                }
            }
            assertTrue("every prescribed set is confirmed", session.state.value.everythingConfirmed)
            assertEquals(
                "and the screen's count is the runtime's count, read back from the stored rows",
                session.state.value.prescribedSetCount,
                runtimeRead(runtime, sessionId).exercises.sumOf { it.results.size }
            )

            // ---- 5. The adaptive leg is evaluated, and reports its honest boundary -------------------
            val adaptive = integration.adaptAfter(sessionId)
            assertTrue("the pass produced an outcome", adaptive is AdaptiveIntegrationResult.Success)
            val outcome = (adaptive as AdaptiveIntegrationResult.Success).outcome
            assertTrue(
                "with no declared progression relation and no exercise→family classification the " +
                    "integration says so rather than inventing an adaptation, got $outcome",
                outcome is AdaptiveIntegrationOutcome.CannotBuildAdaptiveRequest ||
                    outcome is AdaptiveIntegrationOutcome.NothingToAdapt
            )
            assertEquals(
                "and a completion that decided nothing writes no adaptive row",
                0,
                database.count("program_adaptive_decision_record")
            )

            // ---- 6. Finish through the screen: session + opportunity + adaptive, one transaction ---
            session.finish()
            assertEquals("the screen reports the completion", SessionStage.COMPLETED, session.state.value.stage)
            val completed = (runtime.restoreSession(sessionId) as SessionRuntimeResult.Success).value
            assertEquals(SessionStatus.COMPLETED, completed.status)
            assertEquals(
                "and the opportunity is taken",
                SlotStatus.COMPLETED,
                (rig.transfer.scheduleRepository.slotById(slotId)
                    ?: error("the slot vanished"))!!.status
            )

            // ---- 7. Progress observes it ------------------------------------------------------------
            val scope = ProgressScope.OfProgram(id)
            assertEquals(
                "the calendar counts the completed opportunity",
                1,
                rig.progress.calendarProgress(scope).completed
            )
            val history = rig.progress.history(scope)
            assertEquals("and the history holds the attempt", 1, history.size)
            assertEquals(sessionId, history.single().sessionId)

            // ---- 8. Edit → revision 2, and the old session is untouched -----------------------------
            rig.controller.openEditDraft(createdId)
            rig.controller.addDraftDay(ProgramDayType.TRAINING)
            // A day with nothing in it is not a plan: the editor rejects it, which is §10's rule that a
            // training day presents at least one element. The edit therefore adds a day *and* its work —
            // which is also what makes it a real plan change rather than a no-op save.
            val addedDayId = rig.state.draft?.days?.lastOrNull()?.programDayId
            assertNotNull("the draft gained a day", addedDayId)
            rig.controller.addDraftElement(addedDayId!!, ProgramsRig.FIRST_EXERCISE)
            rig.controller.reviewDraft()
            val savedAgain = rig.controller.saveDraft()
            assertTrue(
                "the editor's second save stored a revision, but it reported ${rig.state.notice}",
                savedAgain
            )
            assertTrue(
                "editing created a new immutable revision rather than rewriting the one in use",
                rig.revisionCount(id) >= 2
            )
            assertEquals(
                "the completed session's captured presentation is what it was: a §6 save cannot " +
                    "re-explain a workout that already happened (§19)",
                capturedBefore,
                runtimeRead(runtime, sessionId).snapshot
            )
            assertEquals(
                "and the session is still bound to the revision it started under",
                capturedBefore.workout.revisionId,
                runtimeRead(runtime, sessionId).revisionId
            )

            // ---- 9. Export, import as a new Program, and keep the two independent -------------------
            val file = (rig.transfer.exportService.export(id) as ProgramTransferResult.Success).value
            assertTrue("the export produced a file", file.bytes.isNotEmpty())

            val draft = (rig.transfer.importService.review(file.bytes) as ProgramTransferResult.Success).value
            val imported = (rig.transfer.importService.save(draft, makeActive = false)
                as ProgramTransferResult.Success).value
            assertNotEquals(
                "an imported Program has its own identity — it is not the original wearing a new name",
                id,
                imported.programId
            )
            assertEquals("with its own first revision", 1, rig.revisionCount(imported.programId))
            assertEquals(
                "and the import did not touch the original's history",
                1,
                rig.progress.history(scope).size
            )

            rig.transfer.lifecycleService.deleteProgram(imported.programId)
            assertNotNull("the original survives deleting the import", rig.transfer.storedProgram(id))
            assertEquals(
                "with its completed session still in its history",
                1,
                rig.progress.history(scope).size
            )
            assertNull("and the deleted import is gone", rig.transfer.storedProgram(imported.programId))
        } finally {
            rig.close()
        }
    }

    @Test
    fun theRetiredRuntimeIsNotWhatIsRunning() = runBlocking {
        // A cheaper statement of the same cutover: the tables the retired runtime wrote are not in the
        // schema at all, so "the old runtime is unreachable" is not a claim about call sites — there is
        // nowhere left for it to write.
        val rig = ProgramsRig("acceptance-schema")
        try {
            for (retired in listOf(
                "user_progress", "program_day_state", "set_log",
                "family_progression_state", "adaptive_decision_record"
            )) {
                assertFalse("`$retired` is not in the schema at all", rig.database.tableNames().contains(retired))
            }
            assertTrue("and the target set log is", rig.database.tableNames().contains("program_set_log"))
        } finally {
            rig.close()
        }
    }

    /** Reads an attempt back through the runtime — the same read the screen performs on restore. */
    private suspend fun runtimeRead(
        runtime: SessionRuntime,
        sessionId: com.monkfitness.app.domain.common.SessionId
    ) = (runtime.restoreSession(sessionId) as SessionRuntimeResult.Success).value
}
