package com.monkfitness.app.domain.usecase

import com.monkfitness.app.domain.adaptive.decision.AdaptiveAction
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
import com.monkfitness.app.domain.adaptive.ExposureLevel
import com.monkfitness.app.domain.adaptive.ExposureLoad
import com.monkfitness.app.domain.adaptive.ExposureObservation
import com.monkfitness.app.domain.adaptive.FamilyProgressionState
import com.monkfitness.app.domain.adaptive.IntensityEntry
import com.monkfitness.app.domain.adaptive.IntensityLoad
import com.monkfitness.app.domain.adaptive.LoadProfile
import com.monkfitness.app.domain.adaptive.RecoveryContext
import com.monkfitness.app.domain.adaptive.VolumeLoad
import com.monkfitness.app.domain.adaptive.decision.AdaptiveAdjustment
import com.monkfitness.app.domain.adaptive.decision.AdaptiveTarget
import com.monkfitness.app.domain.adaptive.decision.presentedWorkout
import com.monkfitness.app.domain.adaptive.decision.standingAdjustments
import com.monkfitness.app.domain.adaptive.engine.ProgramAdaptiveElement
import com.monkfitness.app.domain.adaptive.engine.ProgramAdaptiveEngine
import com.monkfitness.app.domain.adaptive.engine.ProgramAdaptivePolicy
import com.monkfitness.app.domain.adaptive.engine.ProgramAdaptiveRequest
import com.monkfitness.app.domain.adaptive.engine.ProgramAdaptiveResult
import com.monkfitness.app.domain.adaptive.engine.ProgramAdaptiveWindow
import com.monkfitness.app.domain.adaptive.engine.ProgramElementOwnership
import com.monkfitness.app.domain.adaptive.engine.ProgramProgressionRelation
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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * §30 step 12's **adaptive integration**: from a completed Session to the adaptive half of §27's
 * completion unit.
 *
 * ```text
 * the Session being completed  (its confirmed sets are already facts)
 *     ↓  the families the completion exposed, and their real window of history      §6, §11
 * next not-yet-started, still-startable Slot of the same Program + Revision         §4
 *     ↓  its presentation: the revision's plan day + the adjustments standing for it §16
 *     ↓  the element of that presentation whose ladder can resolve it                §10
 * AdaptiveInputSnapshot + ProgramAdaptiveRequest   (real facts, supplied identity)   §5, §8
 *     ↓
 * ProgramAdaptiveEngine.decide                    (pure, collaborator-free)          §11
 *     ↓
 * ProgramAdaptiveResult                           (decision, adjustment, family state)
 *     ↓
 * AdaptiveIntegrationOutcome                      (§20's four shapes)
 * ```
 *
 * This class **prepares** the adaptive half and writes nothing. Persisting it is §27's completion
 * transaction, which `SessionRuntime.finishSession` opens and which owns the session, the opportunity
 * and the adaptive rows as one unit of work: the outcome carries an
 * [com.monkfitness.app.domain.workout.AdaptiveCompletion], and the runtime hands it to
 * [ProgramAdaptiveRepository] inside the transaction. That split is §17's: the runtime composes
 * *"session + slot + already-produced adaptive completion"* and never calculates a signal, resolves a
 * progression, runs a policy or selects an exercise.
 *
 * ### What it decides
 *
 *  * **the target opportunity** — the next not-yet-started, still-startable opportunity of the same
 *    Program under the same revision the completion belonged to ([adaptiveTargetSlotOf]). No other
 *    opportunity, no other revision, no other Program, and no slot created here: when none is eligible
 *    the answer is [AdaptiveInputGap.NO_FUTURE_SLOT] and nothing is written (§4, §25);
 *  * **the subject** — the first element of that opportunity's presentation whose family the completion
 *    exposed and for which a ladder is declared ([adaptiveTargetElementOf]). Ownership is *not* decided
 *    here: an element the user authored or pinned is handed to the engine as what it is, and the engine's
 *    own rule holds on it (§15, §19);
 *  * **the window's facts** — observations from the stored sessions, the plan's own opportunities for the
 *    family, what was actually performed, and the family's maintained bookkeeping (§6, §7, §13);
 *  * **the three judgements** — evidence, confidence and recovery, derived from those facts by
 *    [AdaptiveJudgementRule] and kept separate (§9, §12, §14);
 *  * **identity and the moment** — the decision and adjustment ids from the injected [IdGenerator], and
 *    `decidedAt`/`capturedAt` from the injected [Clock], read once per pass (§5, §26).
 *
 * ### What it does not do
 *
 * It runs no policy of its own, applies no threshold of its own, invents no ladder, fabricates no
 * judgement, creates no row, moves no date, creates no slot, creates or rewrites no revision and mutates
 * no session. Two facts production does not have are recorded rather than hidden: the target schema
 * stores neither a family ladder nor an exercise→family catalogue, so the composition root wires
 * `NoDeclaredProgression` and `NoExerciseFamilyClassification` and **no family is adapted in production
 * today** — every pass stops at [AdaptiveInputGap.NO_DECLARED_PROGRESSION_RELATION] instead of
 * fabricating a progression. `docs/PROGRAM_ADAPTIVE_INTEGRATION.md` names both artefacts; the suites
 * drive the whole flow with a ladder and a classification supplied.
 *
 * @param planRepository the revision mechanism, read for the revision the completed Session names — the
 *   revision its target opportunity must belong to. Read, never written (§6).
 * @param scheduleRepository the opportunities: read for the revision's slots and for nothing else. No
 *   slot is created, moved, renumbered or superseded here (§20, §25).
 * @param sessionRepository the session graph: the completion itself, and the Program's history the window
 *   is assembled from. A session is assembled from its own stored rows, so a later revision or adjustment
 *   cannot change what an old observation says (§19).
 * @param adaptiveRepository the target adaptive tables: read for the family's state and for the
 *   adjustments standing for the target opportunity, which a new decision supersedes by reference (§14,
 *   §16). Nothing is written here.
 * @param relations the ladder source (§10).
 * @param classification the caller's exercise→family classification (§9).
 * @param clock the moment the window is captured at and the decision is stamped with — read once per pass
 *   (§26).
 * @param idGenerator the identity source the decision and its adjustment are minted through (§26).
 * @param zone the calendar zone a slot's *date* is read in. A slot is planned for a date while the window
 *   is an interval of instants, and the conversion belongs to the layer that owns the clock rather than
 *   to the decision (§26), which is why it is an explicit constructor fact rather than a
 *   `systemDefault()` call inside the load assembly.
 * @param window how far back the decision window reaches (§8's lookback rule, owned by the integration).
 * @param policy the adaptive policy every threshold, count and tolerance is read from (§15).
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
    private val policy: ProgramAdaptivePolicy = ProgramAdaptivePolicy.V1
) {

    /**
     * The adaptive half of the completion of [sessionId], prepared but not written.
     *
     * Expected outcomes are values — [AdaptiveIntegrationResult.Success] of one of §20's four shapes —
     * stored facts the domain refuses surface as [AdaptiveIntegrationResult.InvalidData], and anything
     * else as [AdaptiveIntegrationResult.Failure]. Nothing is ever swallowed into an empty result (§28).
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

    // ---------------------------------------------------------------------------------------------
    // The pass, in §5's order: whose completion, which opportunity, whose element, which facts.
    // ---------------------------------------------------------------------------------------------

    private suspend fun pass(sessionId: SessionId): AdaptiveIntegrationResult {
        val session = sessionRepository.sessionById(sessionId)
            ?: return gap(AdaptiveInputGap.NO_SUCH_SESSION)
        // The trigger is the *completion* of this attempt: §27 makes the completion and the adaptive
        // half one transaction, so the pass runs while the attempt is still `IN_PROGRESS` and the
        // completion that carries its result is the one that stamps it. A cancelled attempt is the
        // case that has no completion at all.
        if (session.status == SessionStatus.CANCELLED) {
            return gap(AdaptiveInputGap.SESSION_WAS_CANCELLED)
        }

        val revision = planRepository.revisionById(session.revisionId)
            ?: invalidData(
                "the completed session '${sessionId.value}' names revision " +
                    "'${session.revisionId.value}', which is not stored"
            )
        if (revision.mode == ProgramMode.MANUAL) return gap(AdaptiveInputGap.PROGRAM_MODE_IS_MANUAL)

        // One moment for the whole pass, read once (§26): the window is captured at it, the target
        // opportunity is chosen against its own calendar day, the presentation is composed at it, and
        // every row this produces is stamped with it.
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

        // §16's composition, plus the standing adjustments that make it effective: the element the
        // decision is about is the one the user would actually be shown, and a decision about it
        // supersedes the adjustment standing for it — by reference, never by rewriting it.
        val standing = standingAdjustments(adaptiveRepository.adjustmentsOf(targetSlot.slotId))
        val presented = presentedWorkout(day, targetSlot, standing, capturedAt)
        val ownership = day.exercises.associate { it.programExerciseId.value to it.ownership }
        val elements = presented.exercises.map { exercise ->
            PresentedElement(
                presentation = exercise,
                ownership = ownership.getValue(exercise.programExerciseId.value)
            )
        }

        val chosen = when (
            val choice = adaptiveTargetElementOf(
                presented = elements,
                plan = day,
                exposedFamilies = exposed,
                classification = classification,
                relations = relations
            )
        ) {
            is AdaptiveTargetElement.Chosen -> choice
            AdaptiveTargetElement.NoFamilyIsClassified ->
                return gap(AdaptiveInputGap.NO_FAMILY_CLASSIFICATION)

            AdaptiveTargetElement.NoExposedFamilyIsPresented ->
                return gap(AdaptiveInputGap.NO_EXPOSED_FAMILY_IN_THE_TARGET_SLOT)

            AdaptiveTargetElement.NoDeclaredRelation ->
                return gap(AdaptiveInputGap.NO_DECLARED_PROGRESSION_RELATION)
        }

        val element: ProgramAdaptiveElement = chosen.element
        val relation: ProgramProgressionRelation = chosen.relation
        val familyId = element.familyId

        val stored = adaptiveRepository.familyState(session.revisionId, familyId)
        val level = stored?.progressionLevel ?: relation.declared(element.exerciseId)?.level
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
        val familyOfExercise = classifiedExercisesOf(observations, day)

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

        val result = ProgramAdaptiveEngine.decide(
            ProgramAdaptiveRequest(
                decisionId = DecisionId(idGenerator.newId()),
                adjustmentId = AdjustmentId(idGenerator.newId()),
                decidedAt = capturedAt,
                snapshot = snapshot,
                element = element,
                relation = relation,
                window = windowOf(familyId, element.exerciseId, stored, relation),
                availableExerciseIds = availableExerciseIdsOf(revision),
                familyOfExercise = familyOfExercise,
                supersedesAdjustmentId = supersededBy(standing, element.presentation.programExerciseId.value),
                policy = policy
            )
        )

        return AdaptiveIntegrationResult.Success(
            outcomeOf(result, stored, relation, element.exerciseId, capturedAt)
        )
    }

    // ---------------------------------------------------------------------------------------------
    // §20's four shapes, from the engine's own result.
    // ---------------------------------------------------------------------------------------------

    /**
     * Which of §20's four shapes one engine result is, and the family's state after the window.
     *
     * Every branch is a rule rather than a convenience:
     *
     *  * the guard's refusal is **the only** thing that produces a kept `NOT_APPLIED` decision, because
     *    §18 names exactly that case as one that "remains recorded" — the change was resolved and
     *    refused;
     *  * an applied change is [AdaptiveIntegrationOutcome.AdaptiveApplied] and carries all three legs §27
     *    writes as one unit: the state, the decision and the adjustment;
     *  * every other hold is [AdaptiveIntegrationOutcome.NothingToAdapt] — no decision row and no
     *    adjustment, with the family's own bookkeeping advanced (§11);
     *  * an applied change whose family has no storable position fails loudly instead of being stored
     *    half-explained: a resolved step exists only because the ladder declared a level for it.
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
     * The family's state after one window, or `null` when there is no position to store (§11, §23).
     *
     * ```text
     * progressionLevel   the level an applied change moved to, else the stored level, else the level
     *                    the ladder declares for what the opportunity presents
     * currentExerciseId  the variant an applied change moved to, else the declared variant the
     *                    opportunity presents, else the stored one
     * adaptationState    the engine's own state for the family after the window
     * ```
     *
     * and the five window facts advance by the engine's own verdict
     * ([com.monkfitness.app.domain.adaptive.engine.ProgramWindowVerdict]):
     *
     * ```text
     * precedingProgressQualifyingWindows  +1 when §7's progression conditions held, else 0
     * precedingRegressQualifyingWindows   +1 when §7's regression conditions held, else 0
     * precedingRecoveryQualifyingWindows  +1 when §14's reduced-absorption pattern held, else 0
     * recoveryQualifyingWindows           +1 while the family is in recovery, else 0
     * qualifyingWindowsSinceLastChange    0 after a level change, else +1 — and `null` while the family
     *                                     has never changed level, because "never changed" is not "0 ago"
     * ```
     *
     * Each of those is a count of **windows**, and this window is one of them: a window cannot count
     * itself, which is why the engine reports the verdict it decided on and this method only advances the
     * bookkeeping. A realignment is not a level change and does not reset the cooldown (§15's decision
     * 4); it is still one eligible window and is counted as one.
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

        val verdict = result.verdict
        val levelChange = result.isApplied && result.decision.action != AdaptiveAction.CHANGE_VARIANT
        val familyId = when (val target = result.decision.target) {
            is AdaptiveTarget.Family -> target.familyId
            else -> relation.familyId
        }

        return FamilyProgressionState(
            revisionId = result.decision.revisionId,
            familyId = familyId,
            progressionLevel = level,
            adaptationState = result.state,
            currentExerciseId = movedTo
                ?: relation.declared(presentedExerciseId)?.exerciseId
                ?: stored?.currentExerciseId,
            updatedAt = decidedAt,
            precedingProgressQualifyingWindows =
                advanced(stored?.precedingProgressQualifyingWindows, verdict.progressQualifying),

            precedingRegressQualifyingWindows =
                advanced(stored?.precedingRegressQualifyingWindows, verdict.regressQualifying),

            precedingRecoveryQualifyingWindows =
                advanced(stored?.precedingRecoveryQualifyingWindows, verdict.recoveryQualifying),

            qualifyingWindowsSinceLastChange = when {
                levelChange -> 0
                stored?.qualifyingWindowsSinceLastChange == null -> null
                else -> stored.qualifyingWindowsSinceLastChange + 1
            },
            recoveryQualifyingWindows = if (result.state == AdaptiveState.RECOVERY) {
                (stored?.recoveryQualifyingWindows ?: 0) + 1
            } else {
                0
            }
        )
    }

    /** The count of consecutive qualifying windows, advanced by this window's own verdict. */
    private fun advanced(stored: Int?, qualified: Boolean): Int = if (qualified) (stored ?: 0) + 1 else 0

    // ---------------------------------------------------------------------------------------------
    // §11's window facts, §6's observations, §17's loads.
    // ---------------------------------------------------------------------------------------------

    /**
     * The family's maintained facts, as the engine's own window value (§15).
     *
     * The level is the stored one when the family has a state, and otherwise the one the ladder declares
     * for what the opportunity presents — which is the same fallback the engine itself applies, stated
     * here so the stored state that comes out of this pass always names a position ([stateAfter]).
     */
    private fun windowOf(
        familyId: String,
        presentedExerciseId: String,
        stored: FamilyProgressionState?,
        relation: ProgramProgressionRelation
    ): ProgramAdaptiveWindow = ProgramAdaptiveWindow(
        familyId = familyId,
        level = stored?.progressionLevel ?: relation.declared(presentedExerciseId)?.level,
        state = stored?.adaptationState ?: AdaptiveState.HOLD,
        precedingProgressQualifyingWindows = stored?.precedingProgressQualifyingWindows ?: 0,
        precedingRegressQualifyingWindows = stored?.precedingRegressQualifyingWindows ?: 0,
        precedingRecoveryQualifyingWindows = stored?.precedingRecoveryQualifyingWindows ?: 0,
        qualifyingWindowsSinceLastChange = stored?.qualifyingWindowsSinceLastChange,
        recoveryQualifyingWindows = stored?.recoveryQualifyingWindows ?: 0,
        // The target tree has no source for a rest request: §10's REST_BASED has no subtype, no plan
        // element prescribes rest and no screen can ask for one, so a window never *asks*. The policy's
        // unsupported-rest path stays reachable only from a caller that states it (§15).
        restChangeRequested = false
    )

    /**
     * The window's observations: what actually happened, from the sessions the Program already stores
     * (§6, §12).
     *
     * Three rules, each of them §12's:
     *
     *  * an observation is built from an occurrence with **at least one confirmed set** — a skipped
     *    exercise and an opportunity that was never trained produce no observation at all rather than a
     *    zero-valued one;
     *  * a session that **ended** contributes, and both endings do: a completed session and a cancelled
     *    one, because a cancellation keeps its partial work as partial exposure while never becoming a
     *    full one. An attempt still in progress is not counted yet — it has not ended, so its work is not
     *    yet an execution of anything;
     *  * the prescription an observation is measured against is the session's own **snapshot**, which is
     *    why a later revision or adjustment cannot change what an old observation says.
     *
     * The set is ordered by start instant and occurrence identity, which is the order the snapshot
     * requires and the order the signal layer counts in.
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
                    startedAt = session.startedAt,
                    finishedAt = session.finishedAt
                )
            }
        }
        .sortedWith(compareBy({ it.startedAt }, { it.sessionExerciseId.value }))

    /**
     * The families the completed session **exposed**: the families of the occurrences it confirmed work
     * for.
     *
     * A skipped occurrence exposed nothing, and neither did an occurrence with no confirmed set (§12), so
     * neither is part of this set. The completion's own snapshot decides — which is what makes the subject
     * of the decision a fact rather than a reading of today's plan.
     *
     * An occurrence whose exercise the classification does not know contributes **no** family here: the
     * subject of a decision has to be a family a ladder can be declared for, and deriving one from the
     * exercise id would be inventing the catalogue this stage is explicit about not owning. That is the
     * same rule [adaptiveTargetElementOf] applies to the target opportunity's own elements, so the two
     * sides of the match cannot disagree about what a family is.
     */
    private fun exposedFamiliesOf(session: WorkoutSession): Set<String> =
        session.exercises
            .filter { it.results.isNotEmpty() }
            .mapNotNull { classification.familyOf(it.exerciseId) }
            .toSet()

    /** The caller's exercise→family classification, for the exercises of the window and of the day. */
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
     * An opportunity is the plan's, not the adaptive layer's: the revision's slots whose planned date falls
     * inside the window and whose plan day presents the family (§20 — a slot is an opportunity, never an
     * amount of work). The date is read in the injected [zone], because a slot is planned for a date while
     * the window is an interval of instants, and only one of those two facts is convert into the other
     * here.
     *
     * @property opportunities how many of the plan's opportunities presented this family.
     * @property completedOpportunities how many of them produced confirmed work for it.
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
     * The **baseline** side of §18's comparison: what the plan prescribed for the family in the window.
     *
     * The volume channels are kept apart — repetitions and seconds are different measurements and nothing
     * converts between them (§17) — and the intensity channel carries the family's own position, which is
     * the one level a level is comparable to. [ExposureLoad] is the context channel: how much opportunity
     * the family had, and how much of it produced work.
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
            // Rest is recorded nowhere in the target tree: a prescription has no rest field and §10's
            // REST_BASED has no subtype. The channel therefore carries the working time that *is*
            // recorded and a zero rest, which a comparison reads as "nothing recorded" on that channel —
            // a bound that blocks nothing, never a rest proposal (§18).
            restSeconds = 0
        ),
        exposure = ExposureLoad(
            opportunities = plan.opportunities,
            completedOpportunities = plan.completedOpportunities
        )
    )

    /**
     * The **recent** side of §18's comparison: what was actually performed for the family inside the
     * window, from the sessions' own confirmed sets.
     *
     * It is `null` when the window holds no confirmed work for the family, which is a fact and not a zero
     * (§11): a missed opportunity and an untrained week are absences, and reporting them as a zero-load
     * recent past would read as *"the user did nothing, so add more"* — the inversion §12 and §18 forbid.
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

        return LoadProfile(
            scope = AdaptiveScope.FAMILY,
            volume = VolumeLoad(sets = sets, repetitions = repetitions, durationSeconds = seconds),
            // The recent past's *level* is deliberately not stated: an observation records what was
            // performed, not where the family stood when it was performed, and the position is a fact
            // about now. Stating today's level on the recent side would claim a historical position
            // nobody recorded, so the channel reads "not covered" and blocks nothing (§17).
            intensity = IntensityLoad(),
            density = DensityLoad(workingSeconds = seconds, restSeconds = 0),
            // The exposure channel is the *plan's* opportunity record on both sides, because that is
            // what an opportunity is: the plan's and the calendar's (§20), and the recent past does not
            // have a second one to state. What the channel carries is the pair §18's recent-context rule
            // reads — how many opportunities the family had, and how many of them produced work — so
            // "the recent past met the plan and still owes an opportunity" is answerable. Stating the
            // performed *occurrences* here instead would make the recent side "above the plan" on every
            // window in which the family appeared more than once, which is not a load reading at all.
            exposure = exposure
        )
    }

    /**
     * Whether a stored attempt contributes to the window whose completion is being prepared.
     *
     * An attempt that **ended** does: a completed one and a cancelled one, because a cancellation keeps
     * its partial work as partial exposure (§12) while never becoming a full one. The attempt the
     * completion is **about** does too, and that is the case that makes §27's single transaction
     * possible: its confirmed sets are work that happened, and the end stamp the completion is about to
     * write is not what makes them count. An attempt still in progress that is *not* this completion is
     * not counted yet — it has not ended, so its work is not yet an execution of anything.
     */
    private fun WorkoutSession.isHistoryOfTheCompletion(completion: SessionId): Boolean =
        sessionId == completion || status != SessionStatus.IN_PROGRESS

    /**
     * The family one occurrence belongs to, by the caller's classification — or `null` when the
     * classification does not know the exercise, in which case the occurrence contributes to no family's
     * history. The decided family is always one the classification named (the target element was chosen
     * through it), so an unclassified occurrence can never be counted into it by accident.
     */
    private fun familyOf(occurrence: SessionExercise): String? =
        classification.familyOf(occurrence.exerciseId)

    /** The family one plan element belongs to, by the caller's classification, or `null` when unknown. */
    private fun familyOf(exercise: ProgramExercise): String? =
        classification.familyOf(exercise.exerciseId)

    /**
     * The exercises the plan presents — §9's `allowed exerciseIds`, as far as this tree states them.
     *
     * §9 makes the user's own selection authoritative over an adaptive preference, and the target schema
     * stores no enabled-exercise selection of its own: the selection a Program *does* hold is the plan the
     * user built. Reading it here is the conservative statement of that fact — a progression step the
     * plan does not present is unavailable and the engine holds rather than introducing an exercise the
     * user's own plan does not contain. The alternative (a persisted per-user selection) is a missing
     * artefact, recorded in `docs/PROGRAM_ADAPTIVE_INTEGRATION.md`, not guessed at here.
     */
    private fun availableExerciseIdsOf(revision: ProgramRevision): Set<String> =
        revision.days.flatMap { day -> day.exercises.map { it.exerciseId } }.toSet()

    /**
     * The adjustment a new decision would supersede for one element of the presentation, or `null`.
     *
     * §16's supersession is by reference and only the **latest standing** adjustment affects the
     * presentation, so the chain is read (never rewritten) and the newest standing adjustment for this
     * element is the one named. A chain that somehow holds two adjustments for one element is invalid
     * persisted data and fails loudly in `standingAdjustments`/`presentedWorkout` rather than being
     * resolved by picking one.
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
     * The three judgement fields are parameters rather than a single value because the signal layer must
     * be asked **before** they can be decided: they are derived from the signals, the signals are derived
     * from the observations and the two load profiles, and only then does the snapshot carry the real
     * levels. The first construction therefore passes the documented *absence* of every judgement
     * (`INSUFFICIENT` / `LOW` / `UNKNOWN`) and the rule it feeds reads none of them — the signal
     * calculator derives its own signals from `exposures`, `baselineLoad` and `recentLoad` and never looks
     * at the three levels, which is exactly why they could stay inputs of the engine rather than being
     * computed inside it.
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

    /** A stored fact that cannot be true, as the pass reports it (§28's `INVALID_DATA`). */
    private fun invalidData(message: String): Nothing = throw IllegalStateException(message)

    /** An expected absence of a fact, as the pass reports it (§20, §28's `EXPECTED`). */
    private fun gap(gap: AdaptiveInputGap): AdaptiveIntegrationResult =
        AdaptiveIntegrationResult.Success(AdaptiveIntegrationOutcome.CannotBuildAdaptiveRequest(gap))
}
