package com.monkfitness.app.domain.usecase

import com.monkfitness.app.data.repository.ProgramAdaptiveRepository
import com.monkfitness.app.data.repository.ProgramPlanRepository
import com.monkfitness.app.data.repository.ProgramScheduleRepository
import com.monkfitness.app.data.repository.WorkoutSessionRepository
import com.monkfitness.app.di.Clock
import com.monkfitness.app.di.IdGenerator
import com.monkfitness.app.domain.adaptive.AdaptiveInputSnapshot
import com.monkfitness.app.domain.adaptive.AdaptiveScope
import com.monkfitness.app.domain.adaptive.AdaptiveState
import com.monkfitness.app.domain.adaptive.ConfidenceLevel
import com.monkfitness.app.domain.adaptive.DensityLoad
import com.monkfitness.app.domain.adaptive.EvidenceLevel
import com.monkfitness.app.domain.adaptive.ExerciseRangeSource
import com.monkfitness.app.domain.adaptive.ExposureLevel
import com.monkfitness.app.domain.adaptive.ExposureLoad
import com.monkfitness.app.domain.adaptive.ExposureObservation
import com.monkfitness.app.domain.adaptive.FamilyProgressionState
import com.monkfitness.app.domain.adaptive.IntensityEntry
import com.monkfitness.app.domain.adaptive.IntensityLoad
import com.monkfitness.app.domain.adaptive.LoadProfile
import com.monkfitness.app.domain.adaptive.PerformedSetAmount
import com.monkfitness.app.domain.adaptive.RecoveryContext
import com.monkfitness.app.domain.adaptive.VolumeLoad
import com.monkfitness.app.domain.adaptive.medianKnownRestSeconds
import com.monkfitness.app.domain.adaptive.decision.AdaptiveAdjustment
import com.monkfitness.app.domain.adaptive.decision.AdaptiveTarget
import com.monkfitness.app.domain.adaptive.decision.presentedWorkout
import com.monkfitness.app.domain.adaptive.decision.standingAdjustments
import com.monkfitness.app.domain.adaptive.engine.ProgramAdaptiveElement
import com.monkfitness.app.domain.adaptive.engine.ProgramAdaptiveEngine
import com.monkfitness.app.domain.adaptive.engine.ProgramAdaptiveEvidence
import com.monkfitness.app.domain.adaptive.engine.ProgramAdaptivePolicy
import com.monkfitness.app.domain.adaptive.engine.ProgramAdaptiveRequest
import com.monkfitness.app.domain.adaptive.engine.ProgramAdaptiveResult
import com.monkfitness.app.domain.adaptive.engine.ProgramAdaptiveSignalCalculator
import com.monkfitness.app.domain.adaptive.engine.ProgramAdaptiveWindow
import com.monkfitness.app.domain.adaptive.engine.ProgramGapFacts
import com.monkfitness.app.domain.adaptive.engine.ProgramElementOwnership
import com.monkfitness.app.domain.adaptive.engine.ProgramProgressionRelation
import com.monkfitness.app.domain.adaptive.engine.ProgramProgressionVariant
import com.monkfitness.app.domain.adaptive.engine.ProgramWindowVerdict
import com.monkfitness.app.domain.adaptive.integration.AdaptiveInputGap
import com.monkfitness.app.domain.adaptive.integration.AdaptiveIntegrationOutcome
import com.monkfitness.app.domain.adaptive.integration.AdaptiveIntegrationResult
import com.monkfitness.app.domain.adaptive.integration.AdaptiveJudgementRule
import com.monkfitness.app.domain.adaptive.integration.AdaptiveTargetElement
import com.monkfitness.app.domain.adaptive.integration.AdaptiveWindowRule
import com.monkfitness.app.domain.adaptive.integration.ExerciseFamilyClassification
import com.monkfitness.app.domain.adaptive.integration.PresentedElement
import com.monkfitness.app.domain.adaptive.integration.ProgressionRelationProvider
import com.monkfitness.app.domain.adaptive.integration.adaptiveTargetElementOf
import com.monkfitness.app.domain.adaptive.integration.adaptiveTargetSlotOf
import com.monkfitness.app.domain.adaptive.integration.ownership
import com.monkfitness.app.domain.common.AdjustmentId
import com.monkfitness.app.domain.common.DecisionId
import com.monkfitness.app.domain.common.RevisionId
import com.monkfitness.app.domain.common.SessionId
import com.monkfitness.app.domain.prescription.PrescriptionDimension
import com.monkfitness.app.domain.program.ProgramDay
import com.monkfitness.app.domain.program.ProgramExercise
import com.monkfitness.app.domain.program.ProgramMode
import com.monkfitness.app.domain.program.ProgramRevision
import com.monkfitness.app.domain.program.WorkoutSlot
import com.monkfitness.app.domain.workout.SessionExercise
import com.monkfitness.app.domain.workout.SessionStatus
import com.monkfitness.app.domain.workout.WorkoutSession
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * The adaptive integration: from a completed Session to the adaptive half of the completion unit.
 *
 * It **prepares** that half and writes nothing. Persisting it is the completion transaction, which opens the
 * session, the opportunity and the adaptive rows as one unit: the outcome carries an `AdaptiveCompletion` and
 * the runtime hands it to [ProgramAdaptiveRepository] inside that transaction. The runtime composes and never
 * calculates a signal, resolves a progression or runs a policy.
 *
 * What it decides: the **target opportunity** (the next not-yet-started, still-startable one of the same
 * Program under the same revision — no other opportunity, no other revision, and none created here, so when
 * none is eligible the answer is `NO_FUTURE_SLOT`); one **leg per family** the completion exposed, each
 * against the first element of that opportunity's presentation for its family (ownership is *not* decided
 * here, an authored or pinned element is handed to the engine as what it is, and a family the opportunity
 * does not present gets its window read and its bookkeeping advanced with no adjustment); the **window's
 * facts**; the **three judgements** (evidence, confidence, recovery), kept separate; and **identity and the
 * moment**, from the injected [IdGenerator] and [Clock].
 *
 * It runs no policy or threshold of its own, writes no ladder, fabricates no judgement, and creates, moves or
 * rewrites nothing. The ladder is the persisted catalogue's, read through [ProgressionRelationProvider]; a
 * family it declares none for is given the one rung the plan already presents, which is a position rather
 * than authored content, so the exercise climbs its own range and holds at the ceiling. The
 * exercise→family catalogue is the app's own shipped classification.
 *
 * @param planRepository read for the revision the completed Session names — its target opportunity must belong
 *   to it. Never written.
 * @param scheduleRepository read for the revision's slots and nothing else; no slot is created, moved or
 *   superseded here.
 * @param sessionRepository the completion itself and the history the window is assembled from. A session is
 *   assembled from its own stored rows, so a later revision cannot change what an old observation says.
 * @param adaptiveRepository the family's state and the adjustments standing for the target opportunity, which a
 *   new decision supersedes by reference. Nothing is written here.
 * @param clock the moment the window is captured at and the decision stamped with — read once per pass.
 * @param zone the calendar a slot's *date* is read in. A slot is planned for a date while the window is an
 *   interval of instants, and the conversion belongs to the layer that owns the clock — an explicit fact rather
 *   than a `systemDefault()` call inside the load assembly.
 * @param policy the adaptive policy every threshold, count and tolerance is read from.
 * @param exerciseRanges where the element's own progression range is read from, when a caller states one. A
 *   caller fact like the classification: this layer owns no catalogue.
 */
class ProgramAdaptiveIntegration(
    private val planRepository: ProgramPlanRepository,
    private val scheduleRepository: ProgramScheduleRepository,
    private val sessionRepository: WorkoutSessionRepository,
    private val adaptiveRepository: ProgramAdaptiveRepository,
    private val relations: ProgressionRelationProvider,
    private val classification: ExerciseFamilyClassification,
    private val clock: Clock,
    private val idGenerator: IdGenerator,
    private val zone: ZoneId,
    private val window: AdaptiveWindowRule = AdaptiveWindowRule.V1,
    private val policy: ProgramAdaptivePolicy = ProgramAdaptivePolicy.V1,
    private val exerciseRanges: ExerciseRangeSource = ExerciseRangeSource.NONE
) {

    /**
     * The adaptive half of the completion of [sessionId], prepared but not written.
     *
     * Expected outcomes are values — `Success` of one of the four shapes — stored facts the domain refuses
     * surface as `InvalidData`, and anything else as `Failure`. Nothing is swallowed into an empty result.
     */
    suspend fun adaptAfter(sessionId: SessionId): AdaptiveIntegrationResult = try {
        pass(sessionId)
    } catch (refusedByTheDomain: IllegalArgumentException) {
        AdaptiveIntegrationResult.InvalidData(refusedByTheDomain)
    } catch (refusedByTheDomain: IllegalStateException) {
        AdaptiveIntegrationResult.InvalidData(refusedByTheDomain)
    } catch (failure: Throwable) {
        AdaptiveIntegrationResult.Failure(failure)
    }

    // The pass, in order: whose completion, which opportunity, whose element, which facts.

    private suspend fun pass(sessionId: SessionId): AdaptiveIntegrationResult {
        val session = sessionRepository.sessionById(sessionId)
            ?: return gap(AdaptiveInputGap.NO_SUCH_SESSION)
        // The trigger is the *completion* of this attempt: the completion and the adaptive half are one
        // transaction, so the pass runs while the attempt is still `IN_PROGRESS` and the completion that
        // carries its result is the one that stamps it. A cancelled attempt has no completion at all.
        if (session.status == SessionStatus.CANCELLED) {
            return gap(AdaptiveInputGap.SESSION_WAS_CANCELLED)
        }

        val revision = planRepository.revisionById(session.revisionId)
            ?: invalidData(
                "the completed session '${sessionId.value}' names revision " +
                    "'${session.revisionId.value}', which is not stored"
            )
        if (revision.mode == ProgramMode.MANUAL) return gap(AdaptiveInputGap.PROGRAM_MODE_IS_MANUAL)

        // One moment for the whole pass: the window is captured at it, the target opportunity is chosen
        // against its calendar day, and every row this produces is stamped with it.
        val capturedAt = clock.now()
        val windowStart = window.windowStart(capturedAt)

        val slots = scheduleRepository.slotsOfRevision(session.revisionId)
        val targetSlot = adaptiveTargetSlotOf(
            slots = slots,
            completedSlotId = session.slotId,
            notBefore = LocalDate.ofInstant(capturedAt, zone)
        ) ?: return gap(AdaptiveInputGap.NO_FUTURE_SLOT)

        val day = revision.days.firstOrNull { it.programDayId == targetSlot.programDayId }
            ?: invalidData(
                "the opportunity '${targetSlot.slotId.value}' names plan day " +
                    "'${targetSlot.programDayId.value}', which revision " +
                    "'${session.revisionId.value}' does not present"
            )
        if (day.exercises.isEmpty()) return gap(AdaptiveInputGap.PLAN_DAY_PRESENTS_NOTHING)

        val history = sessionRepository.sessionsOfProgram(session.programId)
        val observations = observationsOf(history, session.sessionId, windowStart, capturedAt)
        val exposed = exposedFamiliesOf(session)

        // The presentation plus the standing adjustments that make it effective: the element a decision is
        // about is the one the user would actually be shown, and a decision about it supersedes the standing
        // adjustment — by reference, never by rewriting it.
        val standing = standingAdjustments(adaptiveRepository.adjustmentsOf(targetSlot.slotId))
        val presented = presentedWorkout(day, targetSlot, standing, capturedAt)
        val ownership = day.exercises.associate { it.programExerciseId.value to it.ownership }
        val elements = presented.exercises.map { exercise ->
            PresentedElement(
                presentation = exercise,
                ownership = ownership.getValue(exercise.programExerciseId.value)
            )
        }
        val familyOfExercise = classifiedExercisesOf(observations, day)
        val today = LocalDate.ofInstant(capturedAt, zone)

        // Nothing was trained, so there is no window to read for any family. Which gap names it depends on
        // whether the day can be read at all — a different fix for a different missing fact.
        if (exposed.isEmpty()) {
            val readable = adaptiveTargetElementOf(
                presented = elements,
                plan = day,
                exposedFamilies = emptySet(),
                classification = classification,
                relations = relations
            ) !is AdaptiveTargetElement.NoFamilyIsClassified

            return gap(
                if (readable) {
                    AdaptiveInputGap.NO_EXPOSED_FAMILY_IN_THE_TARGET_SLOT
                } else {
                    AdaptiveInputGap.NO_FAMILY_CLASSIFICATION
                }
            )
        }

        val outcomes = exposed.map { familyId ->
            val stored = adaptiveRepository.familyState(session.revisionId, familyId)
            val choice = adaptiveTargetElementOf(
                presented = elements,
                plan = day,
                exposedFamilies = setOf(familyId),
                classification = classification,
                relations = relations
            )
            // A family the opportunity does not present still has a window: its position is the one it
            // already holds, or the one the ladder declares for the exercise it last performed.
            val lastPerformed = lastPerformedExerciseOf(observations, familyOfExercise, familyId)
            val element = when (choice) {
                is AdaptiveTargetElement.Chosen -> choice.element
                is AdaptiveTargetElement.NoDeclaredRelation -> choice.element
                else -> null
            }
            val relation = (choice as? AdaptiveTargetElement.Chosen)?.relation
                ?: element?.let { presented -> singleRungOf(presented) }
                ?: relations.relationOf(familyId)
            val exerciseId = element?.exerciseId ?: lastPerformed
            val level = stored?.progressionLevel
                ?: exerciseId?.let { id -> relation?.declared(id)?.level }

            val planWindow = familyPlanWindowOf(
                revision = revision,
                slots = slots,
                sessions = history,
                completion = session.sessionId,
                familyId = familyId,
                windowStart = windowStart,
                capturedAt = capturedAt
            )
            val baselineLoad = planLoadOf(level, familyId, planWindow)
            val recentLoad = performedLoadOf(
                sessions = history,
                completion = session.sessionId,
                familyId = familyId,
                windowStart = windowStart,
                capturedAt = capturedAt,
                exposure = ExposureLoad(
                    opportunities = planWindow.opportunities,
                    completedOpportunities = planWindow.completedOpportunities
                )
            )
            // The facts about *when*, from history the pass already holds: the last confirmed set anywhere
            // in it (never narrowed to the window — a set older than the window is exactly the fact a gap
            // rule needs), the stretch before this window's own session, and the user's usual interval from
            // the family's last few sessions wherever they fall. The return rule reads the last two.
            val gapFacts = programGapFactsOf(
                sessions = history,
                completion = session.sessionId,
                familyOf = { exerciseId -> classification.familyOf(exerciseId) },
                familyId = familyId,
                zone = zone,
                statedOn = today,
                policy = policy
            )
            val window = windowOf(familyId, exerciseId, stored, relation, gapFacts)

            val judgement = AdaptiveJudgementRule.of(
                snapshot = snapshotOf(
                    session = session,
                    targetSlot = targetSlot,
                    windowStart = windowStart,
                    capturedAt = capturedAt,
                    observations = observations,
                    baselineLoad = baselineLoad,
                    recentLoad = recentLoad,
                    evidence = EvidenceLevel.INSUFFICIENT,
                    confidence = ConfidenceLevel.LOW,
                    recovery = RecoveryContext.UNKNOWN
                ),
                familyOfExercise = familyOfExercise,
                familyId = familyId,
                policy = policy
            )
            val snapshot = snapshotOf(
                session = session,
                targetSlot = targetSlot,
                windowStart = windowStart,
                capturedAt = capturedAt,
                observations = observations,
                baselineLoad = baselineLoad,
                recentLoad = recentLoad,
                evidence = judgement.evidence,
                confidence = judgement.confidence,
                recovery = judgement.recovery
            )

            if (element == null || relation == null) {
                // Nothing to attach a change to: read the window, advance the family's bookkeeping, write
                // no adjustment. A family the day does not present cannot be re-prescribed.
                val signals = ProgramAdaptiveSignalCalculator.calculate(
                    snapshot = snapshot,
                    familyOfExercise = familyOfExercise,
                    familyId = familyId,
                    policy = policy
                )
                val decision = policy.evaluate(
                    ProgramAdaptiveEvidence(
                        window = window,
                        signals = signals,
                        evidence = snapshot.evidence,
                        confidence = snapshot.confidence,
                        recovery = snapshot.recovery
                    )
                )
                return@map AdaptiveIntegrationOutcome.WindowOnly(
                    familyId = familyId,
                    familyState = stateOf(
                        revisionId = session.revisionId,
                        familyId = familyId,
                        stored = stored,
                        level = level,
                        currentExerciseId = stored?.currentExerciseId ?: exerciseId,
                        state = decision.state,
                        verdict = decision.verdict,
                        spendsTheCooldown = false,
                        decidedAt = capturedAt
                    ),
                    reason = decision.reason
                )
            }

            val result = ProgramAdaptiveEngine.decide(
                ProgramAdaptiveRequest(
                    decisionId = DecisionId(idGenerator.newId()),
                    adjustmentId = AdjustmentId(idGenerator.newId()),
                    decidedAt = capturedAt,
                    snapshot = snapshot,
                    element = element,
                    relation = relation,
                    window = window,
                    availableExerciseIds = availableExerciseIdsOf(revision),
                    familyOfExercise = familyOfExercise,
                    range = exerciseRanges.rangeOf(element.exerciseId),
                    supersedesAdjustmentId = supersededBy(
                        standing,
                        element.presentation.programExerciseId.value
                    ),
                    policy = policy
                )
            )

            outcomeOf(result, stored, relation, element.exerciseId, capturedAt)
        }

        return AdaptiveIntegrationResult.Success(outcomes)
    }

    // The four outcome shapes, from the engine's own result.

    /**
     * Which of the four shapes one engine result is, and the family's state after the window.
     *
     * Every branch is a rule: a refusal by the guard is **the only** thing that produces a kept `NOT_APPLIED`
     * decision, because that is the case the domain names as one that "remains recorded" — the change was
     * resolved and refused; an applied change carries all three legs written as one unit; every other hold is
     * `NothingToAdapt` with the family's bookkeeping still advanced; and an applied change whose family has no
     * storable position fails loudly rather than being stored half-explained, since a resolved step exists
     * only because the ladder declared a level for it.
     */
    private fun outcomeOf(
        result: ProgramAdaptiveResult,
        stored: FamilyProgressionState?,
        relation: ProgramProgressionRelation,
        presentedExerciseId: String,
        decidedAt: Instant
    ): AdaptiveIntegrationOutcome {
        val adjustment = result.adjustment
        val state = stateAfter(result, stored, relation, presentedExerciseId, decidedAt)

        return when {
            adjustment != null && state != null -> AdaptiveIntegrationOutcome.AdaptiveApplied(
                decision = result.decision,
                adjustment = adjustment,
                familyState = state,
                reason = result.reason
            )

            adjustment != null -> invalidData(
                "an applied change moved the family to '${adjustment.after.exerciseId}', which the " +
                    "family's ladder must declare a position for: reason=${result.reason}"
            )

            result.wasFilteredByTheGuard && state != null -> AdaptiveIntegrationOutcome.AdaptiveFiltered(
                decision = result.decision,
                familyState = state,
                reason = result.reason
            )

            result.wasFilteredByTheGuard -> invalidData(
                "a resolved change was refused by the guard, so the family has a position to store: " +
                    "reason=${result.reason}"
            )

            else -> AdaptiveIntegrationOutcome.NothingToAdapt(reason = result.reason, familyState = state)
        }
    }

    /**
     * The family's state after one window, or `null` when there is no position to store.
     *
     * `progressionLevel` and `currentExerciseId` are what an applied change moved to, else what is stored, else
     * what the ladder declares for what the opportunity presents — the engine's own fallback, so the stored
     * state always names a position. Each count is a count of windows and a window cannot count itself, so the
     * engine reports its verdict and this method only advances. A realignment is not a level change but is
     * still one eligible window; `qualifyingWindowsSinceLastChange` stays `null` while the family never changed
     * level ("never changed" is not "0 ago"); and a recovery window is the one kind that is *not* eligible, so
     * the count holds rather than being spent for weeks the family could not use.
     */
    private fun stateAfter(
        result: ProgramAdaptiveResult,
        stored: FamilyProgressionState?,
        relation: ProgramProgressionRelation,
        presentedExerciseId: String,
        decidedAt: Instant
    ): FamilyProgressionState? {
        val movedTo = result.adjustment?.after?.exerciseId
        val level = movedTo?.let { relation.declared(it)?.level }
            ?: stored?.progressionLevel
            ?: relation.declared(presentedExerciseId)?.level
            ?: return null
        val familyId = when (val target = result.decision.target) {
            is AdaptiveTarget.Family -> target.familyId
            else -> relation.familyId
        }

        return stateOf(
            revisionId = result.decision.revisionId,
            familyId = familyId,
            stored = stored,
            level = level,
            currentExerciseId = movedTo
                ?: relation.declared(presentedExerciseId)?.exerciseId
                ?: stored?.currentExerciseId,
            state = result.state,
            verdict = result.verdict,
            spendsTheCooldown = result.spendsTheCooldown,
            decidedAt = decidedAt
        )
    }

    /**
     * One window's advance of a family's stored facts, whichever path read the window: the engine's answer
     * and a family the opportunity does not present differ only in whether anything was decided.
     */
    private fun stateOf(
        revisionId: RevisionId,
        familyId: String,
        stored: FamilyProgressionState?,
        level: Int?,
        currentExerciseId: String?,
        state: AdaptiveState,
        verdict: ProgramWindowVerdict,
        spendsTheCooldown: Boolean,
        decidedAt: Instant
    ): FamilyProgressionState? {
        if (level == null) return null

        return FamilyProgressionState(
            revisionId = revisionId,
            familyId = familyId,
            progressionLevel = level,
            adaptationState = state,
            currentExerciseId = currentExerciseId,
            updatedAt = decidedAt,
            precedingProgressQualifyingWindows =
                advanced(stored?.precedingProgressQualifyingWindows, verdict.progressQualifying),

            precedingRegressQualifyingWindows =
                advanced(stored?.precedingRegressQualifyingWindows, verdict.regressQualifying),

            precedingRecoveryQualifyingWindows =
                advanced(stored?.precedingRecoveryQualifyingWindows, verdict.recoveryQualifying),

            // The cooldown is spent by windows the family could have changed level in, so a window spent in
            // recovery holds the count: charging a family for weeks it could not use would make the cooldown
            // elapse on its behalf.
            qualifyingWindowsSinceLastChange = when {
                spendsTheCooldown -> 0
                stored?.qualifyingWindowsSinceLastChange == null -> null
                state == AdaptiveState.RECOVERY -> stored.qualifyingWindowsSinceLastChange
                else -> stored.qualifyingWindowsSinceLastChange + 1
            },
            recoveryQualifyingWindows = if (state == AdaptiveState.RECOVERY) {
                (stored?.recoveryQualifyingWindows ?: 0) + 1
            } else {
                0
            }
        )
    }

    /** The count of consecutive qualifying windows, advanced by this window's own verdict. */
    private fun advanced(stored: Int?, qualified: Boolean): Int = if (qualified) (stored ?: 0) + 1 else 0

    // The window's facts, the observations and the loads.

    /**
     * The family's maintained facts, as the engine's own window value.
     *
     * The level is the stored one, else the one the ladder declares for what the opportunity presents — the
     * same fallback the engine applies, stated here so the state this pass stores always names a position.
     */
    private fun windowOf(
        familyId: String,
        presentedExerciseId: String?,
        stored: FamilyProgressionState?,
        relation: ProgramProgressionRelation?,
        gap: ProgramGapFacts
    ): ProgramAdaptiveWindow = ProgramAdaptiveWindow(
        familyId = familyId,
        level = stored?.progressionLevel
            ?: presentedExerciseId?.let { id -> relation?.declared(id)?.level },
        state = stored?.adaptationState ?: AdaptiveState.HOLD,
        precedingProgressQualifyingWindows = stored?.precedingProgressQualifyingWindows ?: 0,
        precedingRegressQualifyingWindows = stored?.precedingRegressQualifyingWindows ?: 0,
        precedingRecoveryQualifyingWindows = stored?.precedingRecoveryQualifyingWindows ?: 0,
        qualifyingWindowsSinceLastChange = stored?.qualifyingWindowsSinceLastChange,
        recoveryQualifyingWindows = stored?.recoveryQualifyingWindows ?: 0,
        // The target tree has no source for a rest request: `REST_BASED` has no subtype, no plan element
        // prescribes rest and no screen asks for one to be *changed*, so a window never requests one. The
        // policy's unsupported-rest path stays reachable only from a caller that states it.
        restChangeRequested = false,
        // The timing facts, from the stored history. The return rule reads two of them — the stretch before
        // this window and how often the user usually trains — and no other rule consults the rest: a window
        // stating no gap decides exactly as it did before they existed.
        gap = gap
    )

    /**
     * The window's observations: what actually happened, from what the Program already stores.
     *
     * An observation needs **at least one confirmed set** — a skipped exercise produces none rather than a
     * zero-valued one. Ended attempts contribute and both endings count (a cancellation keeps its partial work
     * as partial exposure); an attempt still running has not ended, so its work is not yet an execution of
     * anything. The prescription is the session's own snapshot, so a later revision cannot change what an old
     * observation says. Ordered by start instant then occurrence identity, the order the snapshot requires.
     */
    private fun observationsOf(
        sessions: List<WorkoutSession>,
        completion: SessionId,
        windowStart: Instant,
        capturedAt: Instant
    ): List<ExposureObservation> = sessions
        .filter { it.isHistoryOfTheCompletion(completion) }
        .filter { it.startedAt >= windowStart && it.startedAt <= capturedAt }
        .flatMap { session ->
            session.exercises.mapNotNull { occurrence ->
                if (occurrence.results.isEmpty()) return@mapNotNull null
                val prescribed = occurrence.prescription.setCount
                val completed = occurrence.results.size
                ExposureObservation(
                    exerciseId = occurrence.exerciseId,
                    sessionId = session.sessionId,
                    sessionExerciseId = occurrence.sessionExerciseId,
                    level = if (completed >= prescribed) ExposureLevel.FULL else ExposureLevel.PARTIAL,
                    completedSets = completed,
                    prescribedSets = prescribed,
                    // The per-set amounts are carried, not interpreted: the counts above say how many sets
                    // happened, and the family's rest reading is taken from the same sets.
                    performedAmounts = occurrence.results.map { result ->
                        PerformedSetAmount(
                            completedReps = result.completedReps,
                            durationSeconds = result.durationSeconds,
                            restSeconds = result.restSeconds
                        )
                    },
                    startedAt = session.startedAt,
                    finishedAt = session.finishedAt
                )
            }
        }
        .sortedWith(compareBy({ it.startedAt }, { it.sessionExerciseId.value }))

    /**
     * The families the completed session **exposed**: those of the occurrences it confirmed work for, in
     * the order the session first presents them, one entry each.
     *
     * A skipped occurrence, or one with no confirmed set, exposed nothing, and the completion's own snapshot
     * decides — which makes the subject a fact rather than a reading of today's plan. An exercise the
     * classification does not know contributes **no** family: the subject must be one a ladder can be declared
     * for, and deriving it from the exercise id would invent the catalogue this stage does not own.
     * [adaptiveTargetElementOf] applies the same rule to the target's elements, so the two sides cannot
     * disagree about what a family is.
     */
    private fun exposedFamiliesOf(session: WorkoutSession): List<String> =
        session.exercises
            .filter { it.results.isNotEmpty() }
            .mapNotNull { classification.familyOf(it.exerciseId) }
            .distinct()

    /** The exercise the family most recently performed, which is the position it holds when it is absent. */
    private fun lastPerformedExerciseOf(
        observations: List<ExposureObservation>,
        familyOfExercise: Map<String, String>,
        familyId: String
    ): String? = observations
        .lastOrNull { observation ->
            (familyOfExercise[observation.exerciseId] ?: observation.exerciseId) == familyId
        }
        ?.exerciseId

    private fun classifiedExercisesOf(
        observations: List<ExposureObservation>,
        day: ProgramDay
    ): Map<String, String> =
        (observations.map { it.exerciseId } + day.exercises.map { it.exerciseId })
            .distinct()
            .mapNotNull { exerciseId -> classification.familyOf(exerciseId)?.let { exerciseId to it } }
            .toMap()

    /**
     * The plan's own opportunities for one family **inside the window**, and what they prescribed.
     *
     * An opportunity is the plan's, not the adaptive layer's: the revision's slots whose planned date falls in
     * the window and whose plan day presents the family — a slot is an opportunity, never an amount of work.
     * The date is read in the injected [zone] because a slot is planned for a date while the window is an
     * interval of instants, and only one of those converts into the other here.
     */
    private data class FamilyPlanWindow(
        val opportunities: Int,
        val completedOpportunities: Int,
        val prescribedSets: Int,
        val prescribedRepetitions: Int,
        val prescribedSeconds: Int
    )

    private fun familyPlanWindowOf(
        revision: ProgramRevision,
        slots: List<WorkoutSlot>,
        sessions: List<WorkoutSession>,
        completion: SessionId,
        familyId: String,
        windowStart: Instant,
        capturedAt: Instant
    ): FamilyPlanWindow {
        val bySlot = sessions.groupBy { it.slotId.value }
        var opportunities = 0
        var completed = 0
        var sets = 0
        var repetitions = 0
        var seconds = 0

        for (slot in slots) {
            val plannedAt = slot.plannedFor.atStartOfDay(zone).toInstant()
            if (plannedAt < windowStart || plannedAt > capturedAt) continue

            val day = revision.days.firstOrNull { it.programDayId == slot.programDayId } ?: continue
            val elements = day.exercises.filter { familyOf(it) == familyId }
            if (elements.isEmpty()) continue

            opportunities += 1
            sets += elements.sumOf { it.prescription.setCount }
            repetitions += elements
                .filter { it.prescription.dimension == PrescriptionDimension.REP_BASED }
                .sumOf { it.prescription.totalTarget }

            seconds += elements
                .filter { it.prescription.dimension == PrescriptionDimension.TIME_BASED }
                .sumOf { it.prescription.totalTarget }

            val worked = bySlot[slot.slotId.value].orEmpty().any { session ->
                session.isHistoryOfTheCompletion(completion) &&
                    session.exercises.any { it.results.isNotEmpty() && familyOf(it) == familyId }
            }
            if (worked) completed += 1
        }

        return FamilyPlanWindow(
            opportunities = opportunities,
            completedOpportunities = completed,
            prescribedSets = sets,
            prescribedRepetitions = repetitions,
            prescribedSeconds = seconds
        )
    }

    /**
     * The **baseline** side of the comparison: what the plan prescribed for the family in the window.
     *
     * Volume channels stay apart — repetitions and seconds are different measurements and nothing converts
     * between them — and the intensity channel carries the family's own position, the one level a level is
     * comparable to. `ExposureLoad` is the context channel: how much opportunity the family had, and how much
     * of it produced work.
     */
    private fun planLoadOf(
        level: Int?,
        familyId: String,
        plan: FamilyPlanWindow
    ): LoadProfile = LoadProfile(
        scope = AdaptiveScope.FAMILY,
        volume = VolumeLoad(
            sets = plan.prescribedSets,
            repetitions = plan.prescribedRepetitions,
            durationSeconds = plan.prescribedSeconds
        ),
        intensity = IntensityLoad(
            levels = level?.let { listOf(IntensityEntry(familyId, it)) } ?: emptyList()
        ),
        density = DensityLoad(
            workingSeconds = plan.prescribedSeconds,
            // The plan side records no rest: a prescription has no rest field and there is no rest
            // subtype. The channel carries the working time that *is* recorded and a zero rest, which a
            // comparison reads as "nothing recorded" — a bound that blocks nothing, never a rest proposal.
            restSeconds = 0
        ),
        exposure = ExposureLoad(
            opportunities = plan.opportunities,
            completedOpportunities = plan.completedOpportunities
        )
    )

    /**
     * The **recent** side of the comparison: what was actually performed for the family inside the window.
     *
     * `null` when the window holds no confirmed work, which is a fact and not a zero: a missed opportunity and an
     * untrained week are absences, and a zero-load recent past would read as *"the user did nothing, so add more"*.
     */
    private fun performedLoadOf(
        sessions: List<WorkoutSession>,
        completion: SessionId,
        familyId: String,
        windowStart: Instant,
        capturedAt: Instant,
        exposure: ExposureLoad
    ): LoadProfile? {
        val performed = sessions
            .filter { it.isHistoryOfTheCompletion(completion) }
            .filter { it.startedAt >= windowStart && it.startedAt <= capturedAt }
            .flatMap { session -> session.exercises.map { session to it } }
            .filter { (_, occurrence) -> occurrence.results.isNotEmpty() && familyOf(occurrence) == familyId }
        if (performed.isEmpty()) return null

        val sets = performed.sumOf { (_, occurrence) -> occurrence.results.size }
        val repetitions = performed.sumOf { (_, occurrence) -> occurrence.results.sumOf { it.completedReps } }
        val seconds = performed.sumOf { (_, occurrence) -> occurrence.results.sumOf { it.durationSeconds } }
        // Only the sets whose rest was recorded count. A window in which none was answers `0`, which is
        // the density channel's existing "nothing recorded" reading — the same behaviour a window had
        // before rest was stored, never a claim that the user rested for no seconds.
        val restSeconds = medianKnownRestSeconds(
            performed.flatMap { (_, occurrence) -> occurrence.results.map { result -> result.restSeconds } }
        ) ?: 0

        return LoadProfile(
            scope = AdaptiveScope.FAMILY,
            volume = VolumeLoad(sets = sets, repetitions = repetitions, durationSeconds = seconds),
            // The recent past's *level* is deliberately not stated: an observation records what was performed,
            // not where the family stood when it was, and the position is a fact about now. Stating today's
            // level here would claim a historical position nobody recorded, so the channel reads "not covered"
            // and blocks nothing.
            intensity = IntensityLoad(),
            density = DensityLoad(workingSeconds = seconds, restSeconds = restSeconds),
            // The exposure channel is the *plan's* opportunity record on both sides, because an opportunity is
            // the plan's and the calendar's, and the recent past has no second one to state. What it carries is
            // the pair the recent-context rule reads — how many opportunities the family had, and how many
            // produced work — so "the recent past met the plan and still owes an opportunity" is answerable.
            // Stating the performed *occurrences* here instead would make the recent side "above the plan" on
            // every window in which the family appeared more than once, which is not a load reading at all.
            exposure = exposure
        )
    }

    /**
     * Whether a stored attempt contributes to the window whose completion is being prepared.
     *
     * Ended attempts do, and both endings count. The attempt the completion is **about** does too, which is what
     * makes the single completion transaction possible: its confirmed sets are work that happened, and the end
     * stamp about to be written is not what makes them count.
     */
    private fun WorkoutSession.isHistoryOfTheCompletion(completion: SessionId): Boolean =
        sessionId == completion || status != SessionStatus.IN_PROGRESS

    /**
     * The family one occurrence belongs to, by the caller's classification — or `null` when it does not know
     * the exercise, in which case the occurrence contributes to no family's history. The decided family is
     * always one the classification named, so an unclassified occurrence can never be counted into it.
     */
    private fun familyOf(occurrence: SessionExercise): String? =
        classification.familyOf(occurrence.exerciseId)

    /** The family one plan element belongs to, by the caller's classification, or `null` when unknown. */
    private fun familyOf(exercise: ProgramExercise): String? =
        classification.familyOf(exercise.exerciseId)

    /**
     * The exercises the plan presents, as the `allowedExerciseIds` this tree can state.
     *
     * The user's own selection is authoritative over an adaptive preference, and the target schema stores no
     * enabled-exercise selection of its own: the selection a Program *does* hold is the plan the user built.
     * Reading it is the conservative statement of that fact — a progression step the plan does not present is
     * unavailable, and the engine holds rather than introducing an exercise the user's plan lacks.
     */
    private fun availableExerciseIdsOf(revision: ProgramRevision): Set<String> =
        revision.days.flatMap { day -> day.exercises.map { it.exerciseId } }.toSet()

    /**
     * The adjustment a new decision would supersede for one element of the presentation, or `null`.
     *
     * Supersession is by reference and only the **latest standing** adjustment affects the presentation, so the
     * chain is read (never rewritten) and the newest standing adjustment for the element is the one named. A
     * chain holding two adjustments for one element is invalid persisted data and fails loudly inside
     * `standingAdjustments`/`presentedWorkout` rather than being resolved by picking one.
     */
    private fun supersededBy(
        standing: List<AdaptiveAdjustment>,
        programExerciseId: String
    ): AdjustmentId? = standing
        .filter { it.after.programExerciseId.value == programExerciseId }
        .maxByOrNull { it.createdAt }
        ?.adjustmentId

    /**
     * The window as the engine's own snapshot type states it.
     *
     * The three judgement fields are parameters because the signal layer must be asked **before** they can be
     * decided: they derive from the signals, which derive from the observations and the two load profiles. The
     * first construction therefore passes the documented *absence* of every judgement and the rule it feeds
     * reads none of them — the signal calculator derives its own signals from `exposures`, `baselineLoad` and
     * `recentLoad` and never looks at the three levels, which is why they could stay inputs of the engine.
     */
    private fun snapshotOf(
        session: WorkoutSession,
        targetSlot: WorkoutSlot,
        windowStart: Instant,
        capturedAt: Instant,
        observations: List<ExposureObservation>,
        baselineLoad: LoadProfile,
        recentLoad: LoadProfile?,
        evidence: EvidenceLevel,
        confidence: ConfidenceLevel,
        recovery: RecoveryContext
    ): AdaptiveInputSnapshot = AdaptiveInputSnapshot(
        programId = session.programId,
        revisionId = session.revisionId,
        slotId = targetSlot.slotId,
        windowStart = windowStart,
        capturedAt = capturedAt,
        exposures = observations,
        evidence = evidence,
        confidence = confidence,
        recovery = recovery,
        baselineLoad = baselineLoad,
        recentLoad = recentLoad
    )

    /** A stored fact that cannot be true, as the pass reports it. */
    private fun invalidData(message: String): Nothing = throw IllegalStateException(message)

    /**
     * The one rung a family the catalogue declares nothing for is given, taken from the element the plan
     * already presents: the exercise itself, at the position a family with no hierarchy has. Nothing is
     * authored — no second exercise, no level above it — so climbing it is the exercise's own range and
     * the top of that range is the ceiling.
     */
    private fun singleRungOf(element: ProgramAdaptiveElement): ProgramProgressionRelation =
        ProgramProgressionRelation(
            familyId = element.familyId,
            variants = listOf(
                ProgramProgressionVariant(
                    level = SINGLE_RUNG_LEVEL,
                    exerciseId = element.presentation.exerciseId,
                    prescription = element.presentation.prescription
                )
            )
        )

    /** An expected absence of a fact, as the pass reports it. */
    private fun gap(gap: AdaptiveInputGap): AdaptiveIntegrationResult =
        AdaptiveIntegrationResult.Success(
            listOf(AdaptiveIntegrationOutcome.CannotBuildAdaptiveRequest(gap))
        )

    private companion object {

        /**
         * The position a family with no hierarchy has. It is a single ordinal, never a difficulty: the
         * engine steps off it only to discover the ceiling.
         */
        const val SINGLE_RUNG_LEVEL = 0
    }
}

// The window's facts about *when*. Stated here, read by the return rule.

/**
 * The gap facts of one pass: when the family was last trained, the stretch before this window, and how often
 * the user trains.
 *
 * All three come from data the pass already holds. [lastConfirmedSetOn] reads the **whole** history — not
 * narrowed to the window, because a family untouched for six weeks has its last set *outside* every window and
 * "how long has this been?" needs that date. [daysAway] excludes the completing session, so a return measures
 * the stretch they came back *from* rather than zero. [usualInterval] reads the family's recent sessions in the
 * whole history, not the window's exposures, which hold nothing after a gap.
 */
internal fun programGapFactsOf(
    sessions: List<WorkoutSession>,
    completion: SessionId,
    familyOf: (String) -> String?,
    familyId: String,
    zone: ZoneId,
    statedOn: LocalDate,
    policy: ProgramAdaptivePolicy
): ProgramGapFacts = ProgramGapFacts(
    lastConfirmedSetOn = lastConfirmedSetOn(sessions, completion, familyOf, familyId, zone),
    daysAway = daysAwayFromFamily(sessions, completion, familyOf, familyId, statedOn, zone),
    usualInterval = usualIntervalOf(
        sessions,
        completion,
        familyOf,
        familyId,
        sessionsToConsider = policy.usualIntervalSessions,
        minimumSessions = policy.sessionsBeforeAnIntervalIsUsual
    )
)

/**
 * The calendar date of the family's most recent confirmed set, or `null` when it has never had one.
 *
 * The date is the set's own `performedAt` — the moment the work happened — never the session's start or end
 * stamp. The occurrence must belong to [familyId] by the caller's classification, and the attempt must have
 * **ended** (or be this completion), the same rule the window's observations use. No window bound is applied:
 * that absence is the fact.
 *
 * The instant becomes a date in [zone], because "the last set was on the 3rd" is a calendar statement.
 */
internal fun lastConfirmedSetOn(
    sessions: List<WorkoutSession>,
    completion: SessionId,
    familyOf: (String) -> String?,
    familyId: String,
    zone: ZoneId
): LocalDate? = sessions
    .filter { session -> session.sessionId == completion || session.status != SessionStatus.IN_PROGRESS }
    .flatMap { session -> session.exercises }
    .filter { occurrence -> occurrence.results.isNotEmpty() && familyOf(occurrence.exerciseId) == familyId }
    .flatMap { occurrence -> occurrence.results }
    .maxOfOrNull { set -> set.performedAt }
    ?.let { performedAt -> LocalDate.ofInstant(performedAt, zone) }

/**
 * Whole calendar days between the family's last confirmed set **before** [completion] and [statedOn], or
 * `null` when the family had no earlier session.
 *
 * The completing session is excluded on purpose: its sets are already facts, and including them would make
 * every return measure as a zero-day gap. Completed and cancelled attempts both count as an earlier session;
 * an attempt still running does not.
 */
internal fun daysAwayFromFamily(
    sessions: List<WorkoutSession>,
    completion: SessionId,
    familyOf: (String) -> String?,
    familyId: String,
    statedOn: LocalDate,
    zone: ZoneId
): Long? {
    val lastBefore = sessions
        .filter { session -> session.sessionId != completion && session.status != SessionStatus.IN_PROGRESS }
        .flatMap { session -> session.exercises }
        .filter { occurrence -> occurrence.results.isNotEmpty() && familyOf(occurrence.exerciseId) == familyId }
        .flatMap { occurrence -> occurrence.results }
        .maxOfOrNull { set -> set.performedAt }
        ?: return null
    return ChronoUnit.DAYS.between(LocalDate.ofInstant(lastBefore, zone), statedOn)
}

/**
 * The user's usual interval between sessions — or `null` when there is not enough data to state one.
 *
 * The **median**, because that is what "usual" means: one long holiday must not move it the way a mean would.
 * The whole history is read and only then narrowed to the last [sessionsToConsider] sessions: after a
 * three-week gap the window holds almost nothing, and an interval measured over it would be measured over the
 * gap itself — the one span certainly not the habit.
 *
 * The completing session is excluded for [daysAwayFromFamily]'s reason: the stretch that led to it **is** the
 * gap, and counting it would let the absence define the habit it interrupted. Seconds are the unit (no floating
 * point in this layer), and fewer than [minimumSessions] sessions is `null`, not a guess —
 * what to do about an absent habit is the policy's decision.
 */
internal fun usualIntervalOf(
    sessions: List<WorkoutSession>,
    completion: SessionId,
    familyOf: (String) -> String?,
    familyId: String,
    sessionsToConsider: Int,
    minimumSessions: Int
): Duration? {
    val starts = sessions
        .filter { session -> session.sessionId != completion && session.status != SessionStatus.IN_PROGRESS }
        .filter { session ->
            session.exercises.any { occurrence ->
                occurrence.results.isNotEmpty() && familyOf(occurrence.exerciseId) == familyId
            }
        }
        .map { session -> session.startedAt }
        .distinct()
        .sorted()
        .takeLast(sessionsToConsider.coerceAtLeast(1))
    if (starts.size < minimumSessions) return null

    val intervals = starts.zipWithNext { earlier, later -> Duration.between(earlier, later) }.sorted()
    val middle = intervals.size / 2
    return if (intervals.size % 2 == 1) {
        intervals[middle]
    } else {
        Duration.ofSeconds((intervals[middle - 1].seconds + intervals[middle].seconds) / 2)
    }
}
