package com.monkfitness.app.domain.progress

import com.monkfitness.app.domain.common.SetLogId
import com.monkfitness.app.domain.prescription.RepPrescription
import com.monkfitness.app.domain.prescription.TimePrescription
import com.monkfitness.app.domain.progress.ProgressFixture.PROGRAM_A
import com.monkfitness.app.domain.workout.SessionStatus
import com.monkfitness.app.domain.workout.SetResult
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The kilogram half of the Progress layer: volume as `load x reps`, and the records built from it.
 *
 * Three properties are the point of the suite, and each is asserted against hand-written sets rather than
 * against the calculator's own output: a set without a load contributes to the repetition total and to
 * **nothing** in kilograms (it is never added as zero); the two units are never summed into one number;
 * and a set the layer already excludes — a placeholder, a skipped occurrence — contributes to neither.
 */
class WeightedVolumeTest {

    private val zone: ZoneId = ZoneId.of("UTC")
    private val calculator = ProgressCalculator(zone)
    private val window = ProgressWindow(LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-30"))

    private fun set(id: String, index: Int, reps: Int, at: String, loadKg: Double? = null) = SetResult(
        setLogId = SetLogId(id),
        setIndex = index,
        completedReps = reps,
        durationSeconds = 0,
        performedAt = Instant.parse(at),
        loadKg = loadKg
    )

    private fun hold(id: String, index: Int, seconds: Int, at: String, loadKg: Double? = null) = SetResult(
        setLogId = SetLogId(id),
        setIndex = index,
        completedReps = 0,
        durationSeconds = seconds,
        performedAt = Instant.parse(at),
        loadKg = loadKg
    )

    /**
     * One completed attempt of Program A holding exactly [pushupSets] and [plankSets], on the slot
     * `2026-09-14`. Nothing else is in the facts, so every total below is the sum of what is passed in.
     */
    private fun factsWith(
        pushupSets: List<SetResult>,
        plankSets: List<SetResult> = emptyList()
    ): ProgressFacts {
        val slot = ProgressFixture.slot(
            "slot-w",
            "2026-09-14",
            status = com.monkfitness.app.domain.program.SlotStatus.COMPLETED,
            attempts = listOf(com.monkfitness.app.domain.common.SessionId("session-w")),
            completedAt = Instant.parse("2026-09-14T08:00:00Z")
        )
        val session = ProgressFixture.session(
            key = "w",
            slot = slot,
            startedAt = Instant.parse("2026-09-14T07:30:00Z"),
            finishedAt = Instant.parse("2026-09-14T08:00:00Z"),
            status = SessionStatus.COMPLETED,
            performed = listOfNotNull(
                ProgressFixture.Performed("pushups", RepPrescription(listOf(10)), pushupSets)
                    .takeIf { pushupSets.isNotEmpty() },
                ProgressFixture.Performed("plank", TimePrescription(listOf(30)), plankSets)
                    .takeIf { plankSets.isNotEmpty() }
            )
        )
        return ProgressFacts(ProgressScope.OfProgram(PROGRAM_A), listOf(slot), listOf(session))
    }

    @Test
    fun everySetWeighedSumsLoadTimesRepetitions() {
        val training = calculator.training(
            factsWith(
                pushupSets = listOf(
                    set("s1", 1, 10, "2026-09-14T07:35:00Z", 60.0),
                    set("s2", 2, 8, "2026-09-14T07:40:00Z", 60.0),
                    set("s3", 3, 6, "2026-09-14T07:45:00Z", 62.5)
                )
            ),
            window
        )

        val exercise = training.exerciseVolumes.single()
        assertEquals("repetitions are still the repetition total", 24, exercise.repetitions)
        assertEquals(
            "and the kilogram volume is 10x60 + 8x60 + 6x62.5",
            1455.0,
            requireNotNull(exercise.volumeKg),
            1e-9
        )
        assertEquals("the attempt carries the same kilogram volume", 1455.0, requireNotNull(training.sessionVolumes.single().volumeKg), 1e-9)
        assertEquals("and so does the week", 1455.0, requireNotNull(training.weekVolumes.single().volumeKg), 1e-9)
    }

    @Test
    fun aSetWithoutALoadCountsAsRepetitionsAndNotAsZeroKilograms() {
        val training = calculator.training(
            factsWith(
                pushupSets = listOf(
                    set("s1", 1, 10, "2026-09-14T07:35:00Z", 60.0),
                    set("s2", 2, 10, "2026-09-14T07:40:00Z", null)
                )
            ),
            window
        )

        val exercise = training.exerciseVolumes.single()
        assertEquals("both sets are repetitions", 20, exercise.repetitions)
        assertEquals(
            "but only the weighed one is kilograms — the other is not a zero-kilogram set",
            600.0,
            requireNotNull(exercise.volumeKg),
            1e-9
        )
        assertEquals("the unweighed set is still a performed set", 2, training.series.single().performedSets)
        assertEquals("and it is counted as unweighed", 1, training.series.single().weighedSets)
    }

    @Test
    fun nothingWeighedReportsNoKilogramVolumeRatherThanZero() {
        val training = calculator.training(
            factsWith(pushupSets = listOf(set("s1", 1, 12, "2026-09-14T07:35:00Z", null))),
            window
        )

        assertNull("no set was weighed, so there is no kilogram volume", training.exerciseVolumes.single().volumeKg)
        assertNull("and the attempt has none either", training.sessionVolumes.single().volumeKg)
        assertNull("nor the week", training.weekVolumes.single().volumeKg)
        assertNull("nor the context", training.volumes.single().volumeKg)
        assertEquals("while the repetition total is unchanged", 12, training.exerciseVolumes.single().repetitions)
    }

    @Test
    fun aTimedSetCarriesItsLoadButAddsNoKilogramVolume() {
        val training = calculator.training(
            factsWith(
                pushupSets = listOf(set("s1", 1, 10, "2026-09-14T07:35:00Z", 20.0)),
                plankSets = listOf(hold("s2", 1, 45, "2026-09-14T07:40:00Z", 10.0))
            ),
            window
        )

        val hold = training.exerciseVolumes.single { volume -> volume.exerciseId == "plank" }
        assertEquals("the hold accumulates seconds", 45, hold.seconds)
        assertNull(
            "a weight multiplied by a duration is not a kilogram volume",
            hold.volumeKg
        )
        assertEquals(
            "and the attempt's kilogram volume is the repetition work alone",
            200.0,
            requireNotNull(training.sessionVolumes.single().volumeKg),
            1e-9
        )
    }

    @Test
    fun aCancelledAttemptsWeighedSetsStayInTheVolume() {
        val slot = ProgressFixture.slot(
            "slot-c",
            "2026-09-15",
            status = com.monkfitness.app.domain.program.SlotStatus.MISSED,
            attempts = listOf(com.monkfitness.app.domain.common.SessionId("session-c"))
        )
        val cancelled = ProgressFixture.session(
            key = "c",
            slot = slot,
            startedAt = Instant.parse("2026-09-15T07:00:00Z"),
            finishedAt = Instant.parse("2026-09-15T07:10:00Z"),
            status = SessionStatus.CANCELLED,
            performed = listOf(
                ProgressFixture.Performed(
                    "pushups",
                    RepPrescription(listOf(10)),
                    listOf(set("s1", 1, 5, "2026-09-15T07:05:00Z", 40.0))
                )
            )
        )
        val training = calculator.training(
            ProgressFacts(ProgressScope.OfProgram(PROGRAM_A), listOf(slot), listOf(cancelled)),
            window
        )

        assertEquals(
            "work that happened is volume even when the attempt was later cancelled",
            200.0,
            requireNotNull(training.exerciseVolumes.single().volumeKg),
            1e-9
        )
    }

    @Test
    fun theRecordsAreTheHeaviestSetAndTheBestSingleSetVolume() {
        val training = calculator.training(
            factsWith(
                pushupSets = listOf(
                    set("s1", 1, 5, "2026-09-14T07:35:00Z", 100.0),
                    set("s2", 2, 12, "2026-09-14T07:40:00Z", 60.0),
                    set("s3", 3, 8, "2026-09-14T07:45:00Z", null)
                )
            ),
            window
        )

        val series = training.series.single()
        val heaviest = requireNotNull(series.heaviestSet) { "a weighed set was performed" }
        assertEquals("the heaviest set is the heaviest weight", 100.0, requireNotNull(heaviest.loadKg), 1e-9)
        assertEquals("and its repetitions", 5, heaviest.repetitions)
        assertEquals(
            "the best single-set volume is the largest load x reps, not the heaviest weight",
            720.0,
            requireNotNull(series.bestSetVolumeKg),
            1e-9
        )
        assertEquals("the unweighed set is still an observation", 3, series.performedSets)
    }

    @Test
    fun aTieOnWeightIsBrokenByTheRepetitions() {
        val series = calculator.training(
            factsWith(
                pushupSets = listOf(
                    set("s1", 1, 6, "2026-09-14T07:35:00Z", 80.0),
                    set("s2", 2, 9, "2026-09-14T07:40:00Z", 80.0)
                )
            ),
            window
        ).series.single()

        assertEquals(
            "the same weight for more repetitions is the better set",
            9,
            requireNotNull(series.heaviestSet).repetitions
        )
    }

    @Test
    fun theUnitsAreNeverAddedIntoOneNumber() {
        val volume = calculator.training(
            factsWith(
                pushupSets = listOf(set("s1", 1, 10, "2026-09-14T07:35:00Z", 60.0)),
                plankSets = listOf(hold("s2", 1, 45, "2026-09-14T07:40:00Z"))
            ),
            window
        ).sessionVolumes.single()

        assertEquals("repetitions, seconds and kilograms are three numbers", 10, volume.repetitions)
        assertEquals(45, volume.seconds)
        assertEquals(600.0, requireNotNull(volume.volumeKg), 1e-9)
    }
}
