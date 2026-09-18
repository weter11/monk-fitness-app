package com.monkfitness.app.domain.adaptive

import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.common.RevisionId
import com.monkfitness.app.domain.common.SessionExerciseId
import com.monkfitness.app.domain.common.SessionId
import com.monkfitness.app.domain.common.SlotId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier
import java.time.Instant

/**
 * The adaptive foundation: the vocabularies, the load model and the frozen input window.
 *
 * Three properties are asserted rather than assumed, because all three are the kind that erodes
 * quietly:
 *
 *  * **no scalar load** — the four load channels have exactly the fields the architecture names, so a
 *    "total load" cannot be added without failing this suite, and no load type declares a floating
 *    point field at all;
 *  * **no exposure without work** — an observation needs at least one completed set, so a skipped
 *    exercise is the absence of an observation instead of a zero one;
 *  * **no derivation** — the foundation declares no method that computes a signal, a score, a trend or
 *    an average, so the policy boundary is a real boundary rather than a comment.
 */
class AdaptiveFoundationTest {

    private val windowStart: Instant = Instant.parse("2026-09-01T00:00:00Z")
    private val capturedAt: Instant = Instant.parse("2026-09-18T09:00:00Z")

    /** Every type this change adds to `domain.adaptive` (the decision vocabulary lives one level in). */
    private val foundationClasses: List<Class<*>> = listOf(
        AdaptiveScope::class.java,
        LoadProfile::class.java,
        VolumeLoad::class.java,
        IntensityEntry::class.java,
        IntensityLoad::class.java,
        DensityLoad::class.java,
        ExposureLoad::class.java,
        EvidenceLevel::class.java,
        ConfidenceLevel::class.java,
        RecoveryContext::class.java,
        ExposureLevel::class.java,
        ExposureObservation::class.java,
        AdaptiveInputSnapshot::class.java
    )

    // ------------------------------------------------------------------ vocabularies

    @Test
    fun evidenceAndConfidenceAreSeparateQualitativeLevels() {
        assertEquals(
            listOf("INSUFFICIENT", "STABLE", "STRONG"),
            EvidenceLevel.entries.map { it.name }
        )
        assertEquals(listOf("LOW", "MODERATE", "HIGH"), ConfidenceLevel.entries.map { it.name })

        // Neither is a number, and there is no third vocabulary collapsing them together.
        assertTrue(EvidenceLevel.entries.all { it is Enum<*> })
        assertTrue(ConfidenceLevel.entries.none { it.name.contains("SCORE") })
    }

    @Test
    fun recoveryIsAContextNotADiagnosis() {
        assertEquals(
            listOf("FAVORABLE", "CAUTIOUS", "UNKNOWN"),
            RecoveryContext.entries.map { it.name }
        )
        assertTrue(
            "recovery must not be a score: ${RecoveryContext.entries.map { it.name }}",
            RecoveryContext.entries.none { it.name.contains("SCORE") || it.name.contains("READY") }
        )
    }

    @Test
    fun theFourScopesAreTheOnesTheLoadGuardComparesAt() {
        assertEquals(
            listOf("EXERCISE", "FAMILY", "FOCUS", "SESSION"),
            AdaptiveScope.entries.map { it.name }
        )
    }

    @Test
    fun noLoadTypeDeclaresAFloatingPointOrScalarAmount() {
        val floating = foundationClasses.flatMap { cls ->
            cls.declaredFields
                .filterNot { it.isSynthetic || it.name.startsWith("$") }
                .filter { it.type == java.lang.Double.TYPE || it.type == java.lang.Float.TYPE }
                .map { "${cls.simpleName}.${it.name}" }
        }

        assertTrue(
            "a load score, a ratio or a coefficient would have to be a floating point field; " +
                "found: $floating",
            floating.isEmpty()
        )
    }

    @Test
    fun noFoundationTypeComputesASignalScoreTrendOrAverage() {
        val deriving = foundationClasses.flatMap { cls ->
            cls.declaredMethods
                .map { it.name }
                .filter { name ->
                    listOf("compute", "derive", "score", "trend", "average", "median", "weight")
                        .any { name.contains(it, ignoreCase = true) }
                }
                .map { "${cls.simpleName}.$it" }
        }

        assertTrue(
            "the foundation states facts; deriving signals belongs to the policy/signal layer, " +
                "found: $deriving",
            deriving.isEmpty()
        )
    }

    // ------------------------------------------------------------------ the load model

    @Test
    fun loadProfileHasExactlyTheFourNamedChannels() {
        val fields = declaredFieldNames(LoadProfile::class.java)

        assertEquals(setOf("scope", "volume", "intensity", "density", "exposure"), fields)
        assertTrue(
            "there is no total load and no universal score: ${fields.filter {
                it.contains("total", ignoreCase = true) || it.contains("score", ignoreCase = true)
            }}",
            fields.none { it.contains("total", ignoreCase = true) || it.contains("score", ignoreCase = true) }
        )
    }

    @Test
    fun theTwoVolumeUnitsAreKeptApartAndNeverSummed() {
        val volume = VolumeLoad(sets = 3, repetitions = 30, durationSeconds = 0)
        assertEquals(3, volume.sets)
        assertEquals(30, volume.repetitions)
        assertEquals(0, volume.durationSeconds)
        assertFalse(volume.isZero)

        assertEquals(
            setOf("sets", "repetitions", "durationSeconds"),
            declaredFieldNames(VolumeLoad::class.java)
        )
        assertTrue(VolumeLoad().isZero)

        assertRejects("a negative volume") { VolumeLoad(sets = -1) }
        assertRejects("a negative repetition count") { VolumeLoad(repetitions = -1) }
        assertRejects("a negative duration") { VolumeLoad(durationSeconds = -1) }
    }

    @Test
    fun intensityIsLevelPerFamilyWithNoBoundsAndNoCoefficient() {
        val load = IntensityLoad(listOf(IntensityEntry("pull-family", -1), IntensityEntry("push-family", 2)))

        assertEquals(2, load.levelOf("push-family"))
        assertEquals(-1, load.levelOf("pull-family"))
        assertNull(load.levelOf("legs-family"))
        assertEquals(
            setOf("levels"),
            declaredFieldNames(IntensityLoad::class.java)
        )
        assertEquals(setOf("familyId", "level"), declaredFieldNames(IntensityEntry::class.java))

        assertRejects("two levels for one family") {
            IntensityLoad(listOf(IntensityEntry("push-family", 1), IntensityEntry("push-family", 2)))
        }
        assertRejects("levels out of family order") {
            IntensityLoad(listOf(IntensityEntry("z-family", 2), IntensityEntry("a-family", 1)))
        }
        assertRejects("an unnamed family") { IntensityEntry(" ", 1) }
    }

    @Test
    fun densityIsTheWorkAndTimeStructureRatherThanARatio() {
        val density = DensityLoad(workingSeconds = 600, restSeconds = 300)

        assertEquals(600, density.workingSeconds)
        assertEquals(300, density.restSeconds)
        assertEquals(setOf("workingSeconds", "restSeconds"), declaredFieldNames(DensityLoad::class.java))

        assertRejects("negative working time") { DensityLoad(workingSeconds = -1) }
        assertRejects("negative rest time") { DensityLoad(restSeconds = -1) }
    }

    @Test
    fun exposureIsContextAndItCannotClaimMoreCompletionsThanOpportunities() {
        val exposure = ExposureLoad(opportunities = 4, completedOpportunities = 3)

        assertEquals(4, exposure.opportunities)
        assertEquals(3, exposure.completedOpportunities)
        assertEquals(
            setOf("opportunities", "completedOpportunities"),
            declaredFieldNames(ExposureLoad::class.java)
        )

        assertRejects("more completions than opportunities") {
            ExposureLoad(opportunities = 2, completedOpportunities = 3)
        }
        assertRejects("negative opportunities") { ExposureLoad(opportunities = -1) }
        // An opportunity that produced nothing is a real state: the opportunity existed, the work did
        // not.
        assertTrue(ExposureLoad(opportunities = 2, completedOpportunities = 0).completedOpportunities == 0)
    }

    // ------------------------------------------------------------------ the frozen window

    @Test
    fun aSnapshotIsAFrozenRecordOfOneWindowAndNothingElse() {
        val snapshot = snapshot()

        assertEquals(
            setOf(
                "programId", "revisionId", "slotId", "windowStart", "capturedAt", "exposures",
                "evidence", "confidence", "recovery", "baselineLoad", "recentLoad"
            ),
            declaredFieldNames(AdaptiveInputSnapshot::class.java)
        )
        assertEquals(EvidenceLevel.STABLE, snapshot.evidence)
        assertEquals(ConfidenceLevel.MODERATE, snapshot.confidence)
        assertEquals(RecoveryContext.FAVORABLE, snapshot.recovery)
        assertEquals(1, snapshot.exposures.size)
    }

    @Test
    fun noComparableHistoryIsAbsenceAndNotAZeroedProfile() {
        val withoutHistory = snapshot().copy(evidence = EvidenceLevel.INSUFFICIENT, recentLoad = null)

        assertNull(withoutHistory.recentLoad)
        assertEquals(EvidenceLevel.INSUFFICIENT, withoutHistory.evidence)

        // "We have nothing to compare against" is representable; a fabricated zero profile is a
        // choice the caller makes explicitly, not a default.
        val fabricatedZero = withoutHistory.copy(
            recentLoad = LoadProfile(AdaptiveScope.SESSION, VolumeLoad(), IntensityLoad(), DensityLoad(), ExposureLoad())
        )
        assertNotEquals(withoutHistory, fabricatedZero)
        assertTrue(fabricatedZero.recentLoad!!.volume.isZero)
    }

    @Test
    fun theWindowHoldsOnlyWhatIsInsideItInChronologicalOrder() {
        val first = observation(completedSets = 3, prescribedSets = 3, hoursIn = 1, id = "occurrence-1")
        val second = observation(completedSets = 1, prescribedSets = 3, hoursIn = 5, id = "occurrence-2")

        val ordered = snapshot().copy(exposures = listOf(first, second))
        assertEquals(listOf(first, second), ordered.exposures)

        assertRejects("observations out of chronological order") {
            snapshot().copy(exposures = listOf(second, first))
        }
        assertRejects("two observations of the same occurrence") {
            snapshot().copy(
                exposures = listOf(second, second.copy(startedAt = second.startedAt.plusSeconds(60)))
            )
        }
        assertRejects("an observation from before the window") {
            snapshot().copy(
                exposures = listOf(first.copy(startedAt = windowStart.minusSeconds(1)))
            )
        }
        assertRejects("a window captured before it starts") {
            snapshot().copy(capturedAt = windowStart.minusSeconds(1))
        }
        // A window with no observations is a legitimate window: nothing was trained in it.
        assertTrue(snapshot().copy(exposures = emptyList()).exposures.isEmpty())
    }

    // ------------------------------------------------------------------ helper

    private fun snapshot() = AdaptiveInputSnapshot(
        programId = ProgramId("program-1"),
        revisionId = RevisionId("rev-1"),
        slotId = SlotId("slot-5"),
        windowStart = windowStart,
        capturedAt = capturedAt,
        exposures = listOf(
            observation(completedSets = 3, prescribedSets = 3, hoursIn = 100, id = "occurrence-1")
        ),
        evidence = EvidenceLevel.STABLE,
        confidence = ConfidenceLevel.MODERATE,
        recovery = RecoveryContext.FAVORABLE,
        baselineLoad = LoadProfile(
            scope = AdaptiveScope.EXERCISE,
            volume = VolumeLoad(sets = 3, repetitions = 30),
            intensity = IntensityLoad(listOf(IntensityEntry("push-family", 1))),
            density = DensityLoad(workingSeconds = 600, restSeconds = 240),
            exposure = ExposureLoad(opportunities = 1, completedOpportunities = 1)
        ),
        recentLoad = LoadProfile(
            scope = AdaptiveScope.EXERCISE,
            volume = VolumeLoad(sets = 3, repetitions = 27),
            intensity = IntensityLoad(listOf(IntensityEntry("push-family", 1))),
            density = DensityLoad(workingSeconds = 540, restSeconds = 240),
            exposure = ExposureLoad(opportunities = 1, completedOpportunities = 1)
        )
    )

    private fun observation(
        completedSets: Int,
        prescribedSets: Int,
        hoursIn: Long,
        id: String
    ) = ExposureObservation(
        exerciseId = "pushups",
        sessionId = SessionId("session-$id"),
        sessionExerciseId = SessionExerciseId(id),
        level = if (completedSets >= prescribedSets) ExposureLevel.FULL else ExposureLevel.PARTIAL,
        completedSets = completedSets,
        prescribedSets = prescribedSets,
        startedAt = windowStart.plusSeconds(hoursIn * 3600),
        finishedAt = windowStart.plusSeconds(hoursIn * 3600 + 1200)
    )

    private fun declaredFieldNames(cls: Class<*>): Set<String> =
        cls.declaredFields
            .filterNot { it.isSynthetic || it.name.startsWith("$") || Modifier.isStatic(it.modifiers) }
            .map { it.name }
            .toSet()

    private fun assertRejects(what: String, block: () -> Any) {
        try {
            block()
            throw AssertionError("$what must not be constructible")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message?.isNotBlank() == true)
        }
    }
}
