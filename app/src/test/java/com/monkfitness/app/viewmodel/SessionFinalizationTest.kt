package com.monkfitness.app.viewmodel

import com.monkfitness.app.data.model.Equipment
import com.monkfitness.app.domain.adaptive.ProgramConfiguration
import com.monkfitness.app.domain.adaptive.ProgramConfigurationSource
import com.monkfitness.app.domain.adaptive.ProgramType
import com.monkfitness.app.domain.adaptive.WorkoutConfigurationSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/**
 * The finalization boundary: the request a finished session produces is built from the session's **frozen**
 * facts and from nothing else.
 *
 * `MainViewModel` is an `AndroidViewModel` and this project has no Robolectric harness, so the completion
 * path cannot be driven end to end here. What it can drive is the decision it makes: which values a
 * session's finalization carries. The rule this suite pins is the repair of the live-context blocker —
 * a session started under revision 0 on calendar A, with configuration A, must produce a request carrying
 * exactly those values, no matter what the live flows report at the moment it is completed. Reading
 * `programRevision` / `programStartDate` / the configuration store at completion time is what the repair
 * removed, and the holder's own suite (`WorkoutConfigurationSnapshotTest`) proves those frozen values are
 * the ones the session kept.
 *
 * The builder is a pure function of a session value, so production and the test exercise the same code:
 * there is no second construction path to drift.
 */
class SessionFinalizationTest {

    private val calendarA = LocalDate.of(2026, 8, 31)
    private val calendarB = LocalDate.of(2026, 10, 30)

    private val selectionA = setOf("pushups", "pushups_wide")
    private val selectionB = setOf("squats", "cossack_squat")

    private fun snapshot(selection: Set<String>, version: Int = 1): WorkoutConfigurationSnapshot =
        WorkoutConfigurationSnapshot.capture(
            ProgramConfiguration(
                source = ProgramConfigurationSource.CUSTOM,
                enabledExerciseIds = selection,
                configurationVersion = version
            )
        )

    private fun session(
        day: Int = 10,
        programCycle: Int = 1,
        programRevision: Int = 0,
        programStartDate: LocalDate = calendarA,
        selection: Set<String> = selectionA,
        configurationVersion: Int = 1,
        isPostureMobilitySession: Boolean = false
    ) = ActiveWorkoutSession(
        identity = WorkoutSessionIdentity(day = day, isPostureMobilitySession = isPostureMobilitySession),
        context = WorkoutSessionContext(
            programCycle = programCycle,
            programRevision = programRevision,
            programStartDate = programStartDate
        ),
        configuration = snapshot(selection, configurationVersion)
    )

    @Test
    fun theRequestCarriesTheFactsTheSessionFrozeAtItsStart() {
        val request = requireNotNull(sessionFinalizationRequest(session(), availableEquipment = emptySet()))

        assertEquals(1, request.programCycle)
        assertEquals(10, request.programDay)
        assertEquals(0, request.programRevision)
        assertEquals(calendarA, request.programStartDate)
        assertEquals(1, request.configuration.configurationVersion)
        assertEquals(selectionA, request.configuration.enabledExerciseIds)
    }

    @Test
    fun theFrozenFactsAreWhatTheRequestReportsEvenWhenTheLiveStateMovedOn() {
        // The same story as the blocker, in one call: the session was started under revision 0 on
        // calendar A, and by the time it is completed the live program is revision 1 on calendar B (the
        // object below is simply the one the holder kept — the live values are never read here at all).
        val frozenSession = session(
            day = 10,
            programCycle = 1,
            programRevision = 0,
            programStartDate = calendarA,
            selection = selectionA
        )

        val request = requireNotNull(sessionFinalizationRequest(frozenSession, emptySet()))

        assertEquals("revision 0, not the live revision 1", 0, request.programRevision)
        assertEquals("calendar A, not the live calendar B", calendarA, request.programStartDate)
        assertEquals("configuration A, not the live selection B", selectionA, request.configuration.enabledExerciseIds)
        assertEquals(10, request.programDay)
        assertEquals(1, request.programCycle)
    }

    @Test
    fun aSessionWithoutAFrozenConfigurationHasNothingToFinalize() {
        val notYetCaptured = ActiveWorkoutSession(
            identity = WorkoutSessionIdentity(day = 10, isPostureMobilitySession = false),
            context = WorkoutSessionContext(
                programCycle = 1,
                programRevision = 0,
                programStartDate = calendarA
            ),
            configuration = null
        )

        assertNull(
            "a session whose configuration read has not landed records no decision",
            sessionFinalizationRequest(notYetCaptured, emptySet())
        )
    }

    @Test
    fun aSessionWithoutAFrozenContextHasNothingToFinalize() {
        val withoutContext = ActiveWorkoutSession(
            identity = WorkoutSessionIdentity(day = 10, isPostureMobilitySession = false),
            context = null,
            configuration = snapshot(selectionA)
        )

        assertNull(
            "a session that never froze its program context records no decision",
            sessionFinalizationRequest(withoutContext, emptySet())
        )
    }

    @Test
    fun aSessionThatHasNotStartedIsNotAFinalization() {
        assertNull("no session, no request", sessionFinalizationRequest(null, emptySet()))
    }

    @Test
    fun theFrozenRevisionDecidesWhichProgramIdentityTheRequestReports() {
        assertEquals(
            "revision 0 is the program as first started",
            ProgramType.STANDARD,
            requireNotNull(sessionFinalizationRequest(session(), emptySet())).programType
        )
        assertEquals(
            "every later revision is a revised program",
            ProgramType.REVISED,
            requireNotNull(sessionFinalizationRequest(session(programRevision = 3), emptySet())).programType
        )
        assertEquals(
            "and the type follows the FROZEN revision, never a live one",
            ProgramType.REVISED,
            requireNotNull(
                sessionFinalizationRequest(
                    session(programRevision = 1, programStartDate = calendarB),
                    emptySet()
                )
            ).programType
        )
    }

    @Test
    fun theRequestCarriesTheEquipmentTheFinalizationWasGiven() {
        val request = requireNotNull(
            sessionFinalizationRequest(session(), availableEquipment = setOf(Equipment.BANDS))
        )

        assertEquals(setOf(Equipment.BANDS), request.availableEquipment)
    }

    @Test
    fun aLaterSessionCarriesItsOwnFrozenFacts() {
        // The future-only half of the freeze: nothing is "frozen forever" — the next session reports what
        // was frozen at ITS start.
        val next = session(
            day = 11,
            programRevision = 1,
            programStartDate = calendarB,
            selection = selectionB,
            configurationVersion = 4
        )

        val request = requireNotNull(sessionFinalizationRequest(next, emptySet()))

        assertEquals(11, request.programDay)
        assertEquals(1, request.programRevision)
        assertEquals(calendarB, request.programStartDate)
        assertEquals(selectionB, request.configuration.enabledExerciseIds)
        assertEquals(4, request.configuration.configurationVersion)
    }
}
