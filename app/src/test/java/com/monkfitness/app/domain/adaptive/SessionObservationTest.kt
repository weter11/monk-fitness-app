package com.monkfitness.app.domain.adaptive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Contract suite for the pure session-observation models the adaptive pipeline consumes.
 *
 * These models are the boundary between "what the session prescribed" and "what the session
 * observed": everything downstream (sig, policy, progression) reads only this shape, so the
 * invariants pinned here are the semantics the adaptive engine is allowed to assume.
 *
 * Three properties matter more than the fields themselves:
 *
 *  * **Units are never blended.** Repetitions and timed holds are different measurements. A session
 *    aggregate reports `sets` / `reps` / `durationSeconds` side by side ([Workload]); nothing in
 *    these models adds a repetition to a second, and no cross-unit scalar is manufactured here.
 *  * **Exposure is a clamped fraction with an explicit zero rule.** `completed / planned`, clamped
 *    to `0.0..1.0`, and `0.0` (never `NaN`, never `1.0`) when the exercise prescribed no work.
 *  * **Outcome and observed amounts agree.** `NOT_STARTED` carries no actual work and no stamps,
 *    `PARTIAL` carries actual work > 0, `COMPLETED` carries a finished stamp and may still be well
 *    below full exposure — a workout the user finished is not the same as a workout fully performed.
 */
class SessionObservationTest {

    private val startedAt = 1_760_000_000_000L
    private val finishedAt = startedAt + 25 * 60 * 1000L

    /** A repetition exercise as the session prescribed and observed it: 3 sets x 10 reps = 30. */
    private fun repResult(
        exerciseId: String = "pushups",
        plannedSets: Int = 3,
        completedSets: Int = 3,
        plannedReps: Int = 30,
        completedReps: Int = 30
    ) = ExerciseResult(
        exerciseId = exerciseId,
        plannedSets = plannedSets,
        completedSets = completedSets,
        plannedReps = plannedReps,
        completedReps = completedReps,
        plannedDurationSeconds = 0,
        completedDurationSeconds = 0
    )

    /** A timer exercise: 3 sets x 30 s = 90 s planned, with the elapsed seconds actually held. */
    private fun timerResult(
        exerciseId: String = "plank",
        plannedSets: Int = 3,
        completedSets: Int = 3,
        plannedDurationSeconds: Int = 90,
        completedDurationSeconds: Int = 90
    ) = ExerciseResult(
        exerciseId = exerciseId,
        plannedSets = plannedSets,
        completedSets = completedSets,
        plannedReps = 0,
        completedReps = 0,
        plannedDurationSeconds = plannedDurationSeconds,
        completedDurationSeconds = completedDurationSeconds
    )

    /** A session observation with the outcome-specific stamps and amounts the models require. */
    private fun observation(
        outcome: SessionOutcome,
        startedAt: Long? = if (outcome == SessionOutcome.NOT_STARTED) null else this.startedAt,
        finishedAt: Long? = if (outcome == SessionOutcome.COMPLETED) this.finishedAt else null,
        cycleNumber: Int = 1,
        programDay: Int = 8,
        plannedExercises: Int = 2,
        completedExercises: Int = if (outcome == SessionOutcome.NOT_STARTED) 0 else 1,
        plannedWork: Workload = Workload(sets = 5, reps = 40, durationSeconds = 60),
        actualWork: Workload = if (outcome == SessionOutcome.NOT_STARTED) Workload() else Workload(sets = 2, reps = 20),
        exerciseResults: List<ExerciseResult> = if (outcome == SessionOutcome.NOT_STARTED) emptyList() else listOf(repResult(), timerResult())
    ) = SessionObservation(
        cycleNumber = cycleNumber,
        programDay = programDay,
        startedAt = startedAt,
        finishedAt = finishedAt,
        outcome = outcome,
        plannedExercises = plannedExercises,
        completedExercises = completedExercises,
        plannedWork = plannedWork,
        actualWork = actualWork,
        exerciseResults = exerciseResults
    )

    // ---------------------------------------------------------------------------------------------
    // Outcome semantics
    // ---------------------------------------------------------------------------------------------

    @Test
    fun allThreeSessionOutcomesAreRepresentable() {
        val notStarted = observation(SessionOutcome.NOT_STARTED)
        val partial = observation(SessionOutcome.PARTIAL)
        val completed = observation(SessionOutcome.COMPLETED)

        assertEquals(SessionOutcome.NOT_STARTED, notStarted.outcome)
        assertEquals(SessionOutcome.PARTIAL, partial.outcome)
        assertEquals(SessionOutcome.COMPLETED, completed.outcome)
    }

    @Test
    fun notStartedSessionCarriesThePlanAndNoActualWork() {
        val notStarted = observation(SessionOutcome.NOT_STARTED)

        assertEquals(Workload(sets = 5, reps = 40, durationSeconds = 60), notStarted.plannedWork)
        assertEquals(Workload(), notStarted.actualWork)
        assertTrue(notStarted.actualWork.isZero)
        assertEquals(null, notStarted.startedAt)
        assertEquals(null, notStarted.finishedAt)
        assertEquals(0, notStarted.completedExercises)
    }

    @Test
    fun notStartedSessionWithActualWorkIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            observation(SessionOutcome.NOT_STARTED, actualWork = Workload(sets = 1, reps = 10))
        }
    }

    @Test
    fun notStartedSessionWithAStartStampIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            observation(SessionOutcome.NOT_STARTED, startedAt = startedAt)
        }
    }

    @Test
    fun notStartedSessionWithCompletedExercisesIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            observation(SessionOutcome.NOT_STARTED, completedExercises = 1)
        }
    }

    @Test
    fun partialSessionRequiresActualWork() {
        val abandoned = observation(SessionOutcome.PARTIAL)

        assertEquals(startedAt, abandoned.startedAt)
        assertEquals(null, abandoned.finishedAt)
        assertFalse(abandoned.actualWork.isZero)
    }

    @Test
    fun abandonedSessionIsAPartialObservationWithAnEndingStamp() {
        // The user started, did part of the work, and ended the session without satisfying the
        // completion condition. Abandonment is not a field of its own at this layer: it is a PARTIAL
        // observation that carries the moment the session stopped being active.
        val abandoned = observation(
            SessionOutcome.PARTIAL,
            finishedAt = finishedAt,
            actualWork = Workload(sets = 1, reps = 10)
        )

        assertEquals(SessionOutcome.PARTIAL, abandoned.outcome)
        assertEquals(finishedAt, abandoned.finishedAt)
        assertFalse(abandoned.actualWork.isZero)
    }

    @Test
    fun partialSessionWithoutActualWorkIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            observation(SessionOutcome.PARTIAL, actualWork = Workload())
        }
    }

    @Test
    fun partialSessionWithoutAStartStampIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            observation(SessionOutcome.PARTIAL, startedAt = null)
        }
    }

    @Test
    fun completedSessionRequiresAFinishedStamp() {
        assertThrows(IllegalArgumentException::class.java) {
            observation(SessionOutcome.COMPLETED, finishedAt = null)
        }
    }

    @Test
    fun completedSessionWithExposureBelowOneRemainsValid() {
        // The completion condition is "the user finished the workout", not "every prescribed set was
        // performed": 3 of 5 planned sets done, one exercise held for a third of its time.
        val completed = observation(
            SessionOutcome.COMPLETED,
            plannedExercises = 3,
            completedExercises = 3,
            plannedWork = Workload(sets = 9, reps = 60, durationSeconds = 90),
            actualWork = Workload(sets = 5, reps = 40, durationSeconds = 30),
            exerciseResults = listOf(
                repResult(completedSets = 2, completedReps = 20),
                timerResult(completedSets = 3, completedDurationSeconds = 30)
            )
        )

        assertEquals(SessionOutcome.COMPLETED, completed.outcome)
        assertEquals(finishedAt, completed.finishedAt)
        assertEquals(2.0 / 3.0, completed.exerciseResults.first().exposure, 1e-9)
        assertEquals(1.0 / 3.0, completed.exerciseResults.last().exposure, 1e-9)
    }

    // ---------------------------------------------------------------------------------------------
    // Exposure — repetition and timer channels
    // ---------------------------------------------------------------------------------------------

    @Test
    fun repetitionExposureIsTheCompletedFractionOfThePlannedRepetitions() {
        assertEquals(0.5, repResult(plannedReps = 30, completedReps = 15).exposure, 1e-9)
    }

    @Test
    fun repetitionExposureOfAFullyPerformedExerciseIsOne() {
        assertEquals(1.0, repResult(plannedReps = 30, completedReps = 30).exposure, 1e-9)
    }

    @Test
    fun repetitionExposureOfASkippedExerciseIsZero() {
        assertEquals(0.0, repResult(plannedReps = 30, completedReps = 0).exposure, 1e-9)
    }

    @Test
    fun repetitionExposureFollowsTheSetsTheSessionConfirmed() {
        // One of three prescribed sets confirmed: the reps of the sets that happened, nothing more.
        val oneOfThree = repResult(plannedSets = 3, completedSets = 1, plannedReps = 30, completedReps = 10)

        assertEquals(1.0 / 3.0, oneOfThree.exposure, 1e-9)
    }

    @Test
    fun timerExposureIsTheCompletedFractionOfThePlannedDuration() {
        assertEquals(0.5, timerResult(plannedDurationSeconds = 90, completedDurationSeconds = 45).exposure, 1e-9)
    }

    @Test
    fun timerExposureUsesTheElapsedSecondsOfEachConfirmedHold() {
        // 3 sets x 30 s planned; holds of 30 s and 10 s happened, the third never started.
        val partialHolds = timerResult(
            plannedSets = 3,
            completedSets = 2,
            plannedDurationSeconds = 90,
            completedDurationSeconds = 40
        )

        assertEquals(0.4444444, partialHolds.exposure, 1e-6)
    }

    @Test
    fun timerExposureOfASkippedExerciseIsZero() {
        assertEquals(0.0, timerResult(plannedDurationSeconds = 90, completedDurationSeconds = 0).exposure, 1e-9)
    }

    @Test
    fun exposureIsClampedToOneWhenMoreWorkWasRecordedThanPlanned() {
        assertEquals(1.0, repResult(plannedReps = 30, completedReps = 45).exposure, 1e-9)
        assertEquals(1.0, timerResult(plannedDurationSeconds = 90, completedDurationSeconds = 120).exposure, 1e-9)
        assertEquals(1.0, repetitionExposure(completedReps = 45, plannedReps = 30), 1e-9)
        assertEquals(1.0, timerExposure(completedDurationSeconds = 120, plannedDurationSeconds = 90), 1e-9)
    }

    @Test
    fun exposureIsClampedToZeroWhenNothingWasCompletedOrWorkIsNegative() {
        assertEquals(0.0, repResult(plannedReps = 30, completedReps = 0).exposure, 1e-9)
        assertEquals(0.0, repetitionExposure(completedReps = -5, plannedReps = 30), 1e-9)
        assertEquals(0.0, timerExposure(completedDurationSeconds = -30, plannedDurationSeconds = 90), 1e-9)
    }

    @Test
    fun exposureOfAnExerciseWithNoPlannedWorkIsZeroAndNeverNaN() {
        val noPlan = repResult(plannedSets = 0, completedSets = 0, plannedReps = 0, completedReps = 0)

        assertEquals(0.0, noPlan.exposure, 0.0)
        assertFalse(noPlan.exposure.isNaN())
        assertFalse(noPlan.exposure.isInfinite())
    }

    @Test
    fun exposureHelpersDefineZeroPlannedWorkAsZeroRatherThanDividing() {
        assertEquals(0.0, repetitionExposure(completedReps = 0, plannedReps = 0), 0.0)
        assertEquals(0.0, repetitionExposure(completedReps = 5, plannedReps = 0), 0.0)
        assertEquals(0.0, timerExposure(completedDurationSeconds = 0, plannedDurationSeconds = 0), 0.0)
        assertEquals(0.0, timerExposure(completedDurationSeconds = 30, plannedDurationSeconds = 0), 0.0)
        assertEquals(0.0, repetitionExposure(completedReps = 10, plannedReps = -10), 0.0)
    }

    @Test
    fun exposureOfATimerExerciseUsesTheTimedChannelEvenWhenAPlaceholderRepCountIsPresent() {
        // Generated timer exercises ship `reps = 1` as a placeholder. A normalized observation keeps
        // that placeholder out of the repetition channel, and the timed channel is never mixed with it.
        val plank = ExerciseResult(
            exerciseId = "plank",
            plannedSets = 3,
            completedSets = 1,
            plannedReps = 0,
            completedReps = 0,
            plannedDurationSeconds = 90,
            completedDurationSeconds = 30
        )

        assertEquals(1.0 / 3.0, plank.exposure, 1e-9)
    }

    // ---------------------------------------------------------------------------------------------
    // Field preservation, units, equality
    // ---------------------------------------------------------------------------------------------

    @Test
    fun exerciseResultPreservesEveryNormalizedField() {
        val result = ExerciseResult(
            exerciseId = "squats",
            plannedSets = 3,
            completedSets = 2,
            plannedReps = 45,
            completedReps = 30,
            plannedDurationSeconds = 0,
            completedDurationSeconds = 0
        )

        assertEquals("squats", result.exerciseId)
        assertEquals(3, result.plannedSets)
        assertEquals(2, result.completedSets)
        assertEquals(45, result.plannedReps)
        assertEquals(30, result.completedReps)
        assertEquals(0, result.plannedDurationSeconds)
        assertEquals(0, result.completedDurationSeconds)
        assertEquals(2.0 / 3.0, result.exposure, 1e-9)
    }

    @Test
    fun sessionObservationPreservesEveryNormalizedFieldInOrder() {
        val results = listOf(repResult(exerciseId = "pushups"), timerResult(exerciseId = "plank"))
        val observed = observation(SessionOutcome.COMPLETED, exerciseResults = results)

        assertEquals(1, observed.cycleNumber)
        assertEquals(8, observed.programDay)
        assertEquals(startedAt, observed.startedAt)
        assertEquals(finishedAt, observed.finishedAt)
        assertEquals(SessionOutcome.COMPLETED, observed.outcome)
        assertEquals(2, observed.plannedExercises)
        assertEquals(1, observed.completedExercises)
        assertEquals(Workload(sets = 5, reps = 40, durationSeconds = 60), observed.plannedWork)
        assertEquals(Workload(sets = 2, reps = 20), observed.actualWork)
        assertEquals(listOf("pushups", "plank"), observed.exerciseResults.map { it.exerciseId })
    }

    @Test
    fun sessionRejectsMoreCompletedExercisesThanPlanned() {
        assertThrows(IllegalArgumentException::class.java) {
            observation(SessionOutcome.COMPLETED, plannedExercises = 2, completedExercises = 3)
        }
    }

    @Test
    fun sessionRejectsAnOutOfRangeCycleOrProgramDay() {
        assertThrows(IllegalArgumentException::class.java) {
            observation(SessionOutcome.NOT_STARTED, cycleNumber = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            observation(SessionOutcome.NOT_STARTED, programDay = 0)
        }
    }

    @Test
    fun equalObservationsAreEqualAndShareTheSameHashCode() {
        val first = observation(SessionOutcome.PARTIAL)
        val second = observation(SessionOutcome.PARTIAL)

        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
        assertEquals(first.exerciseResults, second.exerciseResults)
    }

    @Test
    fun observationsDifferingInOneMeasuredAmountAreNotEqual() {
        val base = observation(SessionOutcome.PARTIAL)
        val other = observation(SessionOutcome.PARTIAL, actualWork = Workload(sets = 2, reps = 21))

        assertNotEquals(base, other)
    }

    @Test
    fun copyingAnObservationLeavesTheOriginalUnchanged() {
        val original = observation(SessionOutcome.PARTIAL)
        val prolonged = original.copy(finishedAt = finishedAt, outcome = SessionOutcome.COMPLETED)

        assertEquals(SessionOutcome.PARTIAL, original.outcome)
        assertEquals(null, original.finishedAt)
        assertEquals(SessionOutcome.COMPLETED, prolonged.outcome)
        assertEquals(finishedAt, prolonged.finishedAt)
        assertEquals(original.actualWork, prolonged.actualWork)
    }

    @Test
    fun sessionObservationExposesNoMutableState() {
        // `val` properties compile to getters only: a setter appearing here would mean the model can
        // be mutated in place, which the adaptive pipeline must never be able to do.
        val setters = SessionObservation::class.java.methods
            .filter { it.name.startsWith("set") }
            .map { it.name }

        assertEquals(emptyList<String>(), setters)
    }

    @Test
    fun workloadKeepsRepetitionsAndSecondsInSeparateUnits() {
        val sessionWork = Workload(sets = 5, reps = 40, durationSeconds = 60)

        assertEquals(5, sessionWork.sets)
        assertEquals(40, sessionWork.reps)
        assertEquals(60, sessionWork.durationSeconds)
        // A repetition count and a second count are not interchangeable: the same `sets` with the
        // same number in a different unit is a different amount of work, and must not compare equal.
        assertNotEquals(Workload(sets = 5, reps = 40), Workload(sets = 5, durationSeconds = 40))
    }

    @Test
    fun workloadIsZeroOnlyWhenEveryUnitIsZero() {
        assertTrue(Workload().isZero)
        assertTrue(Workload(sets = 3).isZero.not())
        assertTrue(Workload(reps = 1).isZero.not())
        assertTrue(Workload(durationSeconds = 5).isZero.not())
    }

    @Test
    fun workloadRejectsNegativeAmounts() {
        assertThrows(IllegalArgumentException::class.java) { Workload(reps = -1) }
        assertThrows(IllegalArgumentException::class.java) { Workload(durationSeconds = -1) }
        assertThrows(IllegalArgumentException::class.java) { Workload(sets = -1) }
    }

    @Test
    fun workloadManufacturesNoCrossUnitScalar() {
        // Stage 1 keeps cross-unit reduction out of the observation models: no member of `Workload`
        // may return a blended number. The policy/signal layer owns any normalized scalar, and only
        // where it is explicitly defined and unit-tested.
        val blended = Workload::class.java.methods
            .filter { it.returnType == Double::class.javaPrimitiveType || it.returnType == Float::class.javaPrimitiveType }
            .map { it.name }

        assertEquals(emptyList<String>(), blended)
    }

    @Test
    fun exerciseResultRejectsAMixOfRepetitionAndTimedPlannedWork() {
        assertThrows(IllegalArgumentException::class.java) {
            ExerciseResult(
                exerciseId = "hybrid",
                plannedSets = 3,
                completedSets = 3,
                plannedReps = 30,
                completedReps = 30,
                plannedDurationSeconds = 90,
                completedDurationSeconds = 90
            )
        }
    }

    @Test
    fun exerciseResultRejectsNegativeAmountsAndABlankExerciseId() {
        assertThrows(IllegalArgumentException::class.java) {
            repResult(plannedReps = -10, completedReps = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            repResult(plannedReps = 10, completedReps = -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            repResult(exerciseId = " ")
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Purity: the models must stay free of Android, Room, Compose and data-layer dependencies
    // ---------------------------------------------------------------------------------------------

    @Test
    fun domainModelsDependOnNoAndroidRoomUiOrDataLayerType() {
        val root = moduleRoot()
        val files = listOf(
            "SessionObservation.kt",
            "ExerciseResult.kt",
            "SessionOutcome.kt"
        ).map { File(root, "src/main/java/com/monkfitness/app/domain/adaptive/$it") }

        files.forEach { file ->
            assertTrue("expected ${file.path} to exist", file.isFile)
            val source = file.readText()
            val imports = source.lines().filter { it.trimStart().startsWith("import ") }
            val foreignImports = imports.filterNot { it.trim().startsWith("import kotlin.") }
            assertEquals("${file.name} must import only Kotlin stdlib", emptyList<String>(), foreignImports)
            listOf("androidx.", "android.", "androidx.room", "com.monkfitness.app.data", "com.monkfitness.app.ui")
                .forEach { forbidden ->
                    assertFalse("${file.name} must not reference $forbidden", source.contains(forbidden))
                }
        }
    }

    /** The app module root, found by walking up from the test JVM's working directory. */
    private fun moduleRoot(): File {
        var dir = File(System.getProperty("user.dir"))
        for (attempt in 0 until 8) {
            if (File(dir, "src/main/java/com/monkfitness/app").isDirectory) return dir
            dir = dir.parentFile ?: break
        }
        error("Could not locate the app module root from ${System.getProperty("user.dir")}")
    }
}