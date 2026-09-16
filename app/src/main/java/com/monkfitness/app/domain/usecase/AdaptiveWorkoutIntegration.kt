package com.monkfitness.app.domain.usecase

import com.monkfitness.app.data.model.Equipment
import com.monkfitness.app.data.model.ExerciseSubCategory
import com.monkfitness.app.data.model.FamilyProgressionState
import com.monkfitness.app.data.model.FlexibilityTrainingType
import com.monkfitness.app.data.model.Workout
import com.monkfitness.app.domain.adaptive.AdaptivePolicy
import com.monkfitness.app.domain.adaptive.AdaptiveProgressionPlan
import com.monkfitness.app.domain.adaptive.AdaptiveProgramEngine
import com.monkfitness.app.domain.adaptive.AdaptiveProgramInput
import com.monkfitness.app.domain.adaptive.ProgramType
import com.monkfitness.app.domain.adaptive.SessionObservation
import com.monkfitness.app.domain.adaptive.WorkoutConfigurationSnapshot

/**
 * Everything the adaptive plan of one session depends on, gathered where the session starts.
 *
 * Every value is already-normalized domain data, and every one of them is a *frozen* fact about the
 * session rather than a live reading: the calendar position the session was started on, the
 * configuration snapshot Task 12 captured at its start, the equipment the user has, the observed
 * history the adaptive signals are measured from, and the persisted family progression of the
 * session's program revision.
 *
 * @property programDay the program day being generated, as the existing 56-day calendar defines it.
 * @property programCycle the program cycle that day belongs to. Context, carried into the decision.
 * @property programType which program identity the session belongs to. Context, carried into the
 *   decision; nothing here reads a calendar rule from it.
 * @property configuration the configuration captured at the session's start — never the live
 *   configuration store, and never re-read once the session is running.
 * @property availableEquipment the equipment the generation may rely on, in the app's existing
 *   semantics (empty means "not established", which permits everything, exactly as
 *   [WorkoutGenerator] already reads it).
 * @property recentSessions the normalized history the signals are calculated from, oldest first.
 * @property familyStates the persisted family progression rows of [programType]'s revision. Read
 *   only: no operation of this integration writes one.
 * @property policy the single source of every threshold the decision uses.
 */
data class AdaptiveSessionRequest(
    val programDay: Int,
    val programCycle: Int,
    val programType: ProgramType = ProgramType.STANDARD,
    val configuration: WorkoutConfigurationSnapshot,
    val availableEquipment: Set<Equipment> = emptySet(),
    val recentSessions: List<SessionObservation> = emptyList(),
    val familyStates: List<FamilyProgressionState> = emptyList(),
    val policy: AdaptivePolicy = AdaptivePolicy.V1
) {

    init {
        require(programDay >= 1) { "programDay must be >= 1, was $programDay" }
        require(programCycle >= 1) { "programCycle must be >= 1, was $programCycle" }
        val familyIds = familyStates.map { it.familyId }
        require(familyIds.toSet().size == familyIds.size) {
            "familyStates must carry at most one state per family, were $familyIds"
        }
    }
}

/**
 * The adaptive plan of one session: which exercises the session's frozen configuration and equipment
 * permit, and what each family's own ladder resolved to inside that permission.
 *
 * It is the value a generation path is constrained by. It holds no clock, no storage handle and no
 * configuration source of its own — the configuration it was planned from is identified by
 * [configurationVersion] and nothing here can reach a newer one.
 *
 * @property progression the per-family decisions and their resolutions.
 * @property permittedExerciseIds the exercises generation may use: the captured configuration's
 *   enabled ids intersected with the ids the available equipment supports. A hard constraint, not a
 *   suggestion, and never widened by a fallback.
 * @property configurationVersion the version of the configuration the session captured.
 */
data class AdaptiveSessionPlan(
    val progression: AdaptiveProgressionPlan,
    val permittedExerciseIds: Set<String>,
    val configurationVersion: Int
) {

    /** The exercises the families resolved to — the progression's own preference inside the permitted set. */
    val resolvedExerciseIds: Set<String>
        get() = progression.resolvedExerciseIds

    /** The low-level adjustment each resolved exercise carries. */
    val adjustments: Map<String, Int>
        get() = progression.adjustments

    /**
     * The user's own low-level adjustments with the adaptive step composed into them.
     *
     * The two are summed and nothing is clamped here: the caller applies the result through the
     * existing `Exercise.applyDifficultyAdjustment`, which is the single place that bounds an
     * adjustment, so a second clamp would be a second rule. An exercise the progression left alone
     * keeps exactly the adjustment the user gave it.
     */
    fun effectiveAdjustments(userAdjustments: Map<String, Int>): Map<String, Int> {
        if (adjustments.isEmpty()) return userAdjustments

        return userAdjustments + adjustments.mapValues { (exerciseId, adaptive) ->
            adaptive + (userAdjustments[exerciseId] ?: 0)
        }
    }
}

/**
 * The inputs of one generation that are not part of the session's frozen plan: the routine the
 * calendar prescribes, how the user wants flexibility work filtered, and which of the app's
 * training-style filters are off.
 *
 * @property disabledFamilies the app's existing training-style filter, unchanged by Task 13.
 * @property isPostureMobilitySession whether the optional posture/mobility routine is being generated
 *   instead of the calendar day's own workout.
 */
data class AdaptiveWorkoutGenerationRequest(
    val programDay: Int,
    val trainingType: FlexibilityTrainingType = FlexibilityTrainingType.BOTH,
    val focusAreas: Set<ExerciseSubCategory> = setOf(ExerciseSubCategory.FULL_BODY),
    val availableEquipment: Set<Equipment> = emptySet(),
    val disabledFamilies: Set<String> = emptySet(),
    val isPostureMobilitySession: Boolean = false
) {

    init {
        require(programDay >= 1) { "programDay must be >= 1, was $programDay" }
    }
}

/**
 * Task 13's integration point: the adapter between the session's frozen adaptive inputs and the
 * workout the session presents.
 *
 * ```
 * AdaptiveSessionRequest
 *         ↓
 * [AdaptiveProgramEngine]      → AdaptiveProgramDecision   (one decision per enabled family)
 *         ↓
 * [AdaptiveProgressionPlan]    → the family's own step     (through [ProgressionResolver])
 *         ↓
 * permitted = captured configuration ∩ equipment
 *         ↓
 * [WorkoutGenerator]           → the routine               (the sole concrete builder)
 * ```
 *
 * ## What it is
 *
 * An adapter and nothing more. It decides no threshold, no state transition, no progression step and
 * no exercise: the policy decides the state, the family's ladder decides the step, and the generator
 * decides the routine. What it does own is the *ordering* the task requires — adaptive progression
 * chooses among permitted exercises; it may not override the user's configuration:
 *
 *  1. the engine sees the families of the captured configuration's enabled exercises, so a family
 *     the user disabled is not evaluated and not re-enabled;
 *  2. the resolver is handed the *permitted* set — the configuration intersected with what the
 *     available equipment supports — so a candidate the user disabled, or one the equipment cannot
 *     support, is not selectable, and a substitution cannot reach it either;
 *  3. the generator is handed the permitted set as a hard constraint and the resolved exercises as a
 *     preference, and it remains the only thing that builds a workout.
 *
 * ## What it does not do
 *
 * It reads no clock, no storage and no configuration source: the history and the family states
 * arrive in the request, and the configuration arrives as the session's snapshot. It writes nothing —
 * in particular it never persists a decision or a progression level, so a recovery session cannot
 * erase what the user earned. It applies no low-level adjustment itself: it reports the step it
 * resolved and leaves applying it to the caller's existing difficulty mechanism. It reads no
 * equipment model of its own: "usable with this equipment" is [WorkoutGenerator]'s own answer. And it
 * is deterministic: identical requests produce identical plans and identical workouts.
 *
 * @param generator the app's workout builder. The integration calls it and never replaces it, and the
 *   default instance is the same generator the app already has — passed in so a caller can drive the
 *   integration on the JVM with the very builder production uses.
 */
class AdaptiveWorkoutIntegration(
    private val generator: WorkoutGenerator = WorkoutGenerator()
) {

    /**
     * The library's exercise-to-family map: the granularity a family is evaluated and resolved at.
     *
     * It is derived from the one exercise catalogue the app has, so a family id here always matches
     * the library's own `familyId` and the signal layer's grouping — no second catalogue, and no
     * family invented for an exercise the library does not classify.
     */
    private val familyOfExercise: Map<String, String> =
        generator.getExerciseLibrary().associate { exercise -> exercise.id to exercise.familyId }

    /**
     * The adaptive plan of one session: the decided window, the per-family resolutions inside the
     * permitted set, and the permitted set itself.
     */
    fun planSession(request: AdaptiveSessionRequest): AdaptiveSessionPlan {
        val permitted = permittedExerciseIds(request.configuration, request.availableEquipment)
        val states = request.familyStates

        val decision = AdaptiveProgramEngine.evaluate(
            AdaptiveProgramInput(
                programDay = request.programDay,
                programCycle = request.programCycle,
                programType = request.programType,
                enabledExerciseIds = request.configuration.orderedExerciseIds,
                recentSessions = request.recentSessions,
                currentProgressionStates = states.map { it.toAdaptationState() },
                familyOfExercise = familyOfExercise,
                policy = request.policy
            )
        )

        return AdaptiveSessionPlan(
            progression = AdaptiveProgressionPlan.of(
                decision = decision,
                currentStates = states.map { it.toAdaptationState() },
                permittedExerciseIds = permitted,
                currentExerciseIdOf = { familyId ->
                    states.firstOrNull { it.familyId == familyId }?.currentExerciseId
                }
            ),
            permittedExerciseIds = permitted,
            configurationVersion = request.configuration.configurationVersion
        )
    }

    /**
     * The exercises one session may use: the exercises its captured configuration enables **and**
     * the available equipment supports.
     *
     * Both halves are the app's existing answers. The configuration half is the user's own selection,
     * captured at the session's start; the equipment half is [WorkoutGenerator.getExerciseLibrary],
     * which is where the app's equipment semantics already live. An id the configuration enables but
     * the equipment cannot support is simply not permitted, and nothing here substitutes for it.
     */
    fun permittedExerciseIds(
        configuration: WorkoutConfigurationSnapshot,
        availableEquipment: Set<Equipment> = emptySet()
    ): Set<String> =
        configuration.enabledExerciseIds intersect
            generator.getExerciseLibrary(availableEquipment).map { it.id }.toSet()

    /**
     * The session's workout, built by the existing [WorkoutGenerator] inside the plan's constraints.
     *
     * The generator keeps its own rules, its own routine structure, its own phase interpolation and
     * its own deterministic ordering; the only thing this adds is what generation may choose from —
     * [AdaptiveSessionPlan.permittedExerciseIds] as a hard constraint and
     * [AdaptiveSessionPlan.resolvedExerciseIds] as a preference among the choices those rules already
     * offer. Low-level adjustments are not applied here; see
     * [AdaptiveSessionPlan.effectiveAdjustments] and the caller's difficulty mechanism.
     */
    fun generateWorkout(
        request: AdaptiveWorkoutGenerationRequest,
        plan: AdaptiveSessionPlan
    ): Workout = if (request.isPostureMobilitySession) {
        generator.generatePostureMobilityWorkout(
            day = request.programDay,
            flexibilityTrainingType = request.trainingType,
            focusAreas = request.focusAreas,
            availableEquipment = request.availableEquipment,
            disabledFamilies = request.disabledFamilies,
            allowedExerciseIds = plan.permittedExerciseIds,
            preferredExerciseIds = plan.resolvedExerciseIds
        )
    } else {
        generator.generateWorkout(
            day = request.programDay,
            flexibilityTrainingType = request.trainingType,
            focusAreas = request.focusAreas,
            availableEquipment = request.availableEquipment,
            disabledFamilies = request.disabledFamilies,
            allowedExerciseIds = plan.permittedExerciseIds,
            preferredExerciseIds = plan.resolvedExerciseIds
        )
    }
}
