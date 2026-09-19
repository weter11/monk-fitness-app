package com.monkfitness.app.domain.adaptive.integration

import com.monkfitness.app.domain.adaptive.AdaptiveInputSnapshot
import com.monkfitness.app.domain.adaptive.AdaptiveScope
import com.monkfitness.app.domain.adaptive.ConfidenceLevel
import com.monkfitness.app.domain.adaptive.DensityLoad
import com.monkfitness.app.domain.adaptive.EvidenceLevel
import com.monkfitness.app.domain.adaptive.ExposureLevel
import com.monkfitness.app.domain.adaptive.ExposureLoad
import com.monkfitness.app.domain.adaptive.ExposureObservation
import com.monkfitness.app.domain.adaptive.IntensityEntry
import com.monkfitness.app.domain.adaptive.IntensityLoad
import com.monkfitness.app.domain.adaptive.LoadProfile
import com.monkfitness.app.domain.adaptive.RecoveryContext
import com.monkfitness.app.domain.adaptive.VolumeLoad
import com.monkfitness.app.domain.adaptive.engine.ProgramAdaptiveElement
import com.monkfitness.app.domain.adaptive.engine.ProgramAdaptivePolicy
import com.monkfitness.app.domain.adaptive.engine.ProgramElementOwnership
import com.monkfitness.app.domain.adaptive.engine.ProgramProgressionRelation
import com.monkfitness.app.domain.adaptive.engine.ProgramProgressionVariant
import com.monkfitness.app.domain.common.ProgramDayId
import com.monkfitness.app.domain.common.ProgramExerciseId
import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.common.RevisionId
import com.monkfitness.app.domain.common.SessionExerciseId
import com.monkfitness.app.domain.common.SessionId
import com.monkfitness.app.domain.common.SlotId
import com.monkfitness.app.domain.prescription.RepPrescription
import com.monkfitness.app.domain.program.ProgramDay
import com.monkfitness.app.domain.program.ProgramDayType
import com.monkfitness.app.domain.program.ProgramExercise
import com.monkfitness.app.domain.program.ProgramExerciseOrigin
import com.monkfitness.app.domain.program.SlotStatus
import com.monkfitness.app.domain.program.WorkoutSlot
import com.monkfitness.app.domain.workout.EffectiveExercise
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * The pure half of §30 step 12: §4's target rule, §10's element choice and §9/§12/§14's three
 * judgements — the decisions the integration makes *before* any repository is read, and the ones the
 * rest of the stage is stated in terms of.
 *
 * The temporal rule gets the attention it needs here rather than in the database suite, because it is a
 * comparison of dates and instants and not a property of storage: a `MISSED` opportunity is startable
 * (the runtime will begin one), so **status alone would let the adaptive stage target a past day**, and
 * the clause that prevents it is asserted on its own, on both sides of the boundary.
 */
class AdaptiveTargetSelectionTest {

    private val today: LocalDate = LocalDate.parse("2026-09-21")

    // ------------------------------------------------------------------ §4: the target opportunity

    /** The next future opportunity, in the schedule's own order, with identity as the tiebreak. */
    @Test
    fun theTargetIsTheEarliestFutureOpportunityThatIsStillAheadOfTheDecision() {
        val slots = listOf(
            slot("past-1", "2026-09-16", SlotStatus.MISSED),
            slot("past-2", "2026-09-20", SlotStatus.PLANNED),
            slot("future-late", "2026-09-23", SlotStatus.PLANNED),
            slot("future-next", "2026-09-22", SlotStatus.PLANNED)
        )

        assertEquals(
            "the earliest future one wins, whatever order the caller discovered the slots in",
            "future-next",
            adaptiveTargetSlotOf(slots, SlotId("completed"), today)?.slotId?.value
        )
        assertEquals(
            "and the same answer comes out of the reversed input",
            "future-next",
            adaptiveTargetSlotOf(slots.reversed(), SlotId("completed"), today)?.slotId?.value
        )
    }

    /** The decision's own day is not strictly ahead of it, and neither is anything before it. */
    @Test
    fun anOpportunityPlannedForTheDecisionsOwnDayOrEarlierIsNotAFutureOpportunity() {
        val slots = listOf(
            slot("yesterday", "2026-09-20", SlotStatus.PLANNED),
            slot("today", "2026-09-21", SlotStatus.PLANNED),
            slot("tomorrow", "2026-09-22", SlotStatus.PLANNED)
        )

        assertEquals(
            "the boundary is exclusive on the decision's own day: an opportunity planned for today " +
                "may already be under way, and the rule takes the first one planned after it",
            "tomorrow",
            adaptiveTargetSlotOf(slots, SlotId("completed"), today)?.slotId?.value
        )
    }

    /**
     * A past `MISSED` opportunity is startable — the runtime begins one — and it is still never the
     * target: the temporal clause, not the status clause, is what excludes it.
     */
    @Test
    fun aPastStartableOpportunityIsNeverTheTarget() {
        val missed = slot("missed", "2026-09-18", SlotStatus.MISSED)
        assertTrue(
            "the fixture is what it says: a missed opportunity is one a session may still be started for",
            missed.isStartable
        )

        assertNull(
            "and it is still not a future opportunity, so with nothing else eligible there is no target",
            adaptiveTargetSlotOf(listOf(missed), SlotId("completed"), today)
        )
    }

    /** An opportunity the schedule withdrew, or one already taken, is not ahead of the user. */
    @Test
    fun anOpportunityThatIsNoLongerStartableIsNeverTheTarget() {
        val slots = listOf(
            slot("superseded", "2026-09-22", SlotStatus.SUPERSEDED),
            slot("taken", "2026-09-23", SlotStatus.COMPLETED, attempts = listOf(SessionId("session-1")))
        )

        assertNull(
            "a withdrawn opportunity was not expected of the user and a taken one was already trained",
            adaptiveTargetSlotOf(slots, SlotId("completed"), today)
        )
    }

    /** An opportunity whose snapshot has been taken cannot consume an adjustment either. */
    @Test
    fun anOpportunityThatAlreadyHasAnAttemptIsNeverTheTarget() {
        val slots = listOf(
            slot("started", "2026-09-22", SlotStatus.PLANNED, attempts = listOf(SessionId("session-1"))),
            slot("open", "2026-09-23", SlotStatus.PLANNED)
        )

        assertEquals(
            "its presentation is frozen, so the change would never be shown",
            "open",
            adaptiveTargetSlotOf(slots, SlotId("completed"), today)?.slotId?.value
        )
    }

    /** The opportunity the completion just took is never its own target, whatever the dates say. */
    @Test
    fun theCompletedOpportunityIsNeverTheTargetEvenWhenItsDayIsStillAhead() {
        val completing = slot("this-one", "2026-09-22", SlotStatus.PLANNED)

        assertNull(
            "§4 forbids an adaptation targeting the already-completed current opportunity outright",
            adaptiveTargetSlotOf(listOf(completing), SlotId("this-one"), today)
        )
    }

    // ------------------------------------------------------------------ §10: the element choice

    /** The element is the first of the day that the ladder can resolve for an exposed family. */
    @Test
    fun theElementIsTheFirstExposedFamilyTheLadderCanResolve() {
        val choice = adaptiveTargetElementOf(
            presented = presented("plan-1" to "pushup", "plan-2" to "pike_pushup"),
            plan = day("plan-1" to "pushup", "plan-2" to "pike_pushup"),
            exposedFamilies = setOf(PUSH_FAMILY),
            classification = pushFamilies(),
            relations = ladder()
        )

        assertEquals(
            "the first element in the presentation's own order, with the family's own ladder",
            listOf("plan-1", PUSH_FAMILY, PUSH_FAMILY),
            listOf(
                (choice as AdaptiveTargetElement.Chosen).element.presentation.programExerciseId.value,
                choice.element.familyId,
                choice.relation.familyId
            )
        )
    }

    /**
     * A day whose elements no classification knows is its own gap — *"we do not know the family"* —
     * and not the ladder's: the two are fixed by different artefacts, and the integration reports them
     * separately.
     */
    @Test
    fun aDayNoClassificationKnowsIsItsOwnGap() {
        assertEquals(
            AdaptiveTargetElement.NoFamilyIsClassified,
            adaptiveTargetElementOf(
                presented = presented("plan-1" to "pushup"),
                plan = day("plan-1" to "pushup"),
                exposedFamilies = setOf(PUSH_FAMILY),
                classification = ExerciseFamilyClassification { null },
                relations = ladder()
            )
        )
    }

    /** A known family with no declared ladder is a different gap, and never a fabricated step. */
    @Test
    fun aKnownFamilyWithNoDeclaredLadderIsItsOwnGap() {
        assertEquals(
            AdaptiveTargetElement.NoDeclaredRelation,
            adaptiveTargetElementOf(
                presented = presented("plan-1" to "pushup"),
                plan = day("plan-1" to "pushup"),
                exposedFamilies = setOf(PUSH_FAMILY),
                classification = pushFamilies(),
                relations = ProgressionRelationProvider { null }
            )
        )
    }

    /** A day that trains other families is not adapted now — the family's own window opens later. */
    @Test
    fun aDayThatTrainsNoExposedFamilyIsItsOwnGap() {
        assertEquals(
            "a classified family the completion did not expose is not a subject: adapting it would " +
                "pick an element the completion says nothing about",
            AdaptiveTargetElement.NoExposedFamilyIsPresented,
            adaptiveTargetElementOf(
                presented = presented("plan-1" to "plank"),
                plan = day("plan-1" to "plank"),
                exposedFamilies = setOf(PUSH_FAMILY),
                classification = ExerciseFamilyClassification { exerciseId ->
                    if (exerciseId == "plank") CORE_FAMILY else pushFamilies().familyOf(exerciseId)
                },
                relations = ladder()
            )
        )
    }

    /** Ownership is read from the two facts the plan stores, and pinning outranks authorship. */
    @Test
    fun ownershipIsReadFromThePlansOwnTwoFactsAndPinningWins() {
        assertEquals(
            ProgramElementOwnership.AUTOMATIC,
            element("pushup", ProgramExerciseOrigin.GENERATED).ownership
        )
        assertEquals(
            ProgramElementOwnership.USER_AUTHORED,
            element("pushup", ProgramExerciseOrigin.USER_AUTHORED).ownership
        )
        assertEquals(
            ProgramElementOwnership.PINNED,
            element("pushup", ProgramExerciseOrigin.GENERATED, pinned = true).ownership
        )
        assertEquals(
            "a pinned element the user also edited is the user's *and* fixed, and the engine treats " +
                "the pin as the stronger statement",
            ProgramElementOwnership.PINNED,
            element("pushup", ProgramExerciseOrigin.USER_AUTHORED, pinned = true).ownership
        )
    }

    // ------------------------------------------------------------------ §8: the window rule

    /** The lookback is an interval from the capture, and it is stated rather than hidden. */
    @Test
    fun theWindowStartsExactlyTheLookbackBeforeTheCapture() {
        val capturedAt = Instant.parse("2026-09-21T08:20:00Z")

        assertEquals(
            Duration.ofDays(28),
            Duration.between(AdaptiveWindowRule.V1.windowStart(capturedAt), capturedAt)
        )
        val refusal = try {
            AdaptiveWindowRule(Duration.ZERO)
            null
        } catch (failure: IllegalArgumentException) {
            failure
        }
        assertTrue(
            "a window of no length reaches no exposure at all: ${refusal?.message}",
            refusal != null
        )
    }

    // ------------------------------------------------------------------ §9/§12/§14: the judgements

    /**
     * The three judgements are three separate answers: the evidence buckets are the policy's own
     * exposure counts, confidence adds how many *sessions* that history came from, and recovery is the
     * window's own context — never one blended score.
     */
    @Test
    fun theThreeJudgementsAreSeparateAnswersAboutTheSameWindow() {
        val policy = ProgramAdaptivePolicy.V1

        val thin = AdaptiveJudgementRule.of(
            snapshot = snapshot(exposures = 2, sessions = 2),
            familyOfExercise = mapOf("pushup" to PUSH_FAMILY),
            familyId = PUSH_FAMILY,
            policy = policy
        )
        val measured = AdaptiveJudgementRule.of(
            snapshot = snapshot(exposures = 4, sessions = 2),
            familyOfExercise = mapOf("pushup" to PUSH_FAMILY),
            familyId = PUSH_FAMILY,
            policy = policy
        )
        val oneOccasion = AdaptiveJudgementRule.of(
            snapshot = snapshot(exposures = 4, sessions = 1),
            familyOfExercise = mapOf("pushup" to PUSH_FAMILY),
            familyId = PUSH_FAMILY,
            policy = policy
        )

        assertEquals(
            "two comparable exposures are below the trend minimum: nothing can be measured",
            listOf(EvidenceLevel.INSUFFICIENT, ConfidenceLevel.LOW),
            listOf(thin.evidence, thin.confidence)
        )
        assertEquals(
            "the policy's progression minimum is where a direction is measurable at all",
            EvidenceLevel.STRONG,
            measured.evidence
        )
        assertEquals(
            "and four exposures from one workout are the same count and a thinner history than four " +
                "from two — which is exactly what confidence is for",
            ConfidenceLevel.MODERATE,
            oneOccasion.confidence
        )
        assertEquals(
            "while recovery is a third answer about the same window: it observed the family, the recent " +
                "past is not above the plan and its opportunities were attended, so nothing calls for " +
                "caution — and none of that is derived from the evidence level",
            RecoveryContext.FAVORABLE,
            measured.recovery
        )
    }

    /** An idle window is `UNKNOWN`: inactivity is not favour and it is not caution either (§14). */
    @Test
    fun anIdleWindowIsUnknownAndNeverFavourable() {
        val judgement = AdaptiveJudgementRule.of(
            snapshot = snapshot(exposures = 0, sessions = 0),
            familyOfExercise = emptyMap(),
            familyId = PUSH_FAMILY,
            policy = ProgramAdaptivePolicy.V1
        )

        assertEquals(
            "a window in which the family was not observed says nothing about the context",
            RecoveryContext.UNKNOWN,
            judgement.recovery
        )
        assertEquals(EvidenceLevel.INSUFFICIENT, judgement.evidence)
        assertEquals(ConfidenceLevel.LOW, judgement.confidence)
    }

    /** An exercise the classification does not know is its own family, as the signal layer reads it. */
    @Test
    fun anUnclassifiedExerciseIsItsOwnFamilyInTheWindow() {
        val judgement = AdaptiveJudgementRule.of(
            snapshot = snapshot(exposures = 4, sessions = 2, exerciseId = "mystery"),
            familyOfExercise = emptyMap(),
            familyId = "mystery",
            policy = ProgramAdaptivePolicy.V1
        )
        assertEquals(
            "the conservative reading: it is compared with itself and nothing else",
            EvidenceLevel.STRONG,
            judgement.evidence
        )
        assertEquals(
            "and a family that is only ever this one exercise on two occasions has a history",
            ConfidenceLevel.HIGH,
            judgement.confidence
        )
    }

    // ------------------------------------------------------------------ fixtures

    private fun slot(
        id: String,
        date: String,
        status: SlotStatus,
        attempts: List<SessionId> = emptyList()
    ): WorkoutSlot = WorkoutSlot(
        slotId = SlotId(id),
        programId = ProgramId("program-1"),
        revisionId = RevisionId("revision-1"),
        programDayId = ProgramDayId("day-1"),
        plannedFor = LocalDate.parse(date),
        status = status,
        attempts = attempts,
        completedAt = if (status == SlotStatus.COMPLETED) Instant.parse("2026-09-01T08:00:00Z") else null
    )

    private fun element(
        exerciseId: String,
        origin: ProgramExerciseOrigin,
        pinned: Boolean = false
    ): ProgramExercise = ProgramExercise(
        programExerciseId = ProgramExerciseId("plan-$exerciseId"),
        exerciseId = exerciseId,
        prescription = RepPrescription(listOf(8, 8)),
        origin = origin,
        isPinned = pinned
    )

    private fun day(vararg elements: Pair<String, String>): ProgramDay = ProgramDay(
        programDayId = ProgramDayId("day-1"),
        position = 1,
        type = ProgramDayType.TRAINING,
        name = "Training day",
        exercises = elements.map { (id, exerciseId) ->
            ProgramExercise(
                programExerciseId = ProgramExerciseId(id),
                exerciseId = exerciseId,
                prescription = RepPrescription(listOf(8, 8)),
                origin = ProgramExerciseOrigin.GENERATED
            )
        }
    )

    private fun presented(vararg elements: Pair<String, String>): List<PresentedElement> =
        elements.map { (id, exerciseId) ->
            PresentedElement(
                presentation = EffectiveExercise(
                    ProgramExerciseId(id),
                    exerciseId,
                    RepPrescription(listOf(8, 8))
                ),
                ownership = ProgramElementOwnership.AUTOMATIC
            )
        }

    private fun pushFamilies(): ExerciseFamilyClassification = ExerciseFamilyClassification { exerciseId ->
        when (exerciseId) {
            "pushup", "pike_pushup" -> PUSH_FAMILY
            else -> null
        }
    }

    private fun ladder(): ProgressionRelationProvider {
        val relation = ProgramProgressionRelation(
            familyId = PUSH_FAMILY,
            variants = listOf(
                ProgramProgressionVariant(1, "pushup", RepPrescription(listOf(8, 8))),
                ProgramProgressionVariant(2, "pike_pushup", RepPrescription(listOf(8, 8)))
            )
        )
        return ProgressionRelationProvider { familyId -> relation.takeIf { it.familyId == familyId } }
    }

    /** A window with [exposures] comparable observations, spread over [sessions] session identities. */
    private fun snapshot(
        exposures: Int,
        sessions: Int,
        exerciseId: String = "pushup"
    ): AdaptiveInputSnapshot {
        val observations = (0 until exposures).map { index ->
            ExposureObservation(
                exerciseId = exerciseId,
                sessionId = SessionId("session-${index % sessions.coerceAtLeast(1)}"),
                sessionExerciseId = SessionExerciseId("occurrence-$index"),
                level = ExposureLevel.FULL,
                completedSets = 2,
                prescribedSets = 2,
                startedAt = Instant.parse("2026-09-10T08:00:00Z").plusSeconds(index.toLong() * 86_400)
            )
        }
        val capturedAt = Instant.parse("2026-09-21T08:20:00Z")
        return AdaptiveInputSnapshot(
            programId = ProgramId("program-1"),
            revisionId = RevisionId("revision-1"),
            slotId = SlotId("slot-1"),
            windowStart = capturedAt.minus(Duration.ofDays(28)),
            capturedAt = capturedAt,
            exposures = observations,
            evidence = EvidenceLevel.STRONG,
            confidence = ConfidenceLevel.HIGH,
            recovery = RecoveryContext.FAVORABLE,
            baselineLoad = LoadProfile(
                scope = AdaptiveScope.FAMILY,
                volume = VolumeLoad(sets = 12, repetitions = 96),
                intensity = IntensityLoad(listOf(IntensityEntry(PUSH_FAMILY, 1))),
                density = DensityLoad(workingSeconds = 0, restSeconds = 0),
                exposure = ExposureLoad(opportunities = 3, completedOpportunities = 3)
            ),
            recentLoad = null
        )
    }

    private companion object {
        const val PUSH_FAMILY = "push-family"
        const val CORE_FAMILY = "core-family"
    }
}
