package com.monkfitness.app.domain.program.generated

import com.monkfitness.app.domain.adaptive.ExerciseMetadata
import com.monkfitness.app.domain.adaptive.RecoveryContext
import com.monkfitness.app.domain.prescription.PrescriptionDimension
import com.monkfitness.app.domain.program.Focus
import com.monkfitness.app.domain.program.FocusPlan
import com.monkfitness.app.domain.program.ProgramDuration
import com.monkfitness.app.domain.program.ProgramSchedule
import java.time.DayOfWeek

/**
 * The generated planner's suite fixtures: a small library view, and the requests built from it.
 *
 * The library is deliberately **a library view and not a catalogue**: nothing here mirrors the app's
 * exercises, and every value is chosen so a test can name it. What it does have is the shape the
 * planner reads — several focuses with more than one exercise each (so selection has a real choice),
 * families that differ from the exercise ids (so family diversity is observable), an exercise whose
 * equipment is not always available (so §9's hard constraint is testable), and a candidate in a
 * dimension §10 names without implementing (so the refusal path is testable).
 */
internal enum class RigEquipment { NONE, DUMBBELLS, PULL_UP_BAR, BENCH, MAT }

internal object GeneratedPlannerRig {

    /** Every equipment value, which is what a user with a full set of equipment has. */
    val ALL_EQUIPMENT: Set<RigEquipment> = RigEquipment.entries.toSet()

    /**
     * The library view: one candidate per exercise, with the focuses it trains and the dimension a
     * generated prescription for it is written in.
     *
     * Ids are ordered so the deterministic tie-break (the canonical exercise id) is visible in a
     * failure message, and every focus of the vocabulary has at least one exercise except where a
     * test removes it on purpose.
     */
    val LIBRARY: List<GenerationCandidate<RigEquipment>> = listOf(
        candidate("core-hollow-hold", "core-stability", focuses = setOf(Focus.CORE), equipment = RigEquipment.MAT),
        candidate("core-plank", "core-stability", focuses = setOf(Focus.CORE)),
        candidate("conditioning-burpee", "conditioning", focuses = setOf(Focus.CONDITIONING)),
        candidate("conditioning-jump-rope", "conditioning", focuses = setOf(Focus.CONDITIONING)),
        candidate("legs-lunge", "legs-unilateral", focuses = setOf(Focus.LEGS)),
        candidate("legs-squat", "legs-bilateral", focuses = setOf(Focus.LEGS)),
        candidate("mobility-hip-opener", "mobility-hips", focuses = setOf(Focus.MOBILITY), equipment = RigEquipment.MAT),
        candidate("mobility-spine-wave", "mobility-spine", focuses = setOf(Focus.MOBILITY)),
        candidate("posture-wall-slide", "posture-scapular", focuses = setOf(Focus.POSTURE)),
        candidate("pull-row", "pull-unilateral", focuses = setOf(Focus.PULL), equipment = RigEquipment.DUMBBELLS),
        candidate("pull-up", "pull-vertical", focuses = setOf(Focus.PULL), equipment = RigEquipment.PULL_UP_BAR),
        candidate("push-dip", "push-vertical", focuses = setOf(Focus.PUSH), equipment = RigEquipment.BENCH),
        candidate("push-pushup", "push-horizontal", focuses = setOf(Focus.PUSH)),
        // One exercise that trains two focuses, so a slot's elements can be observed serving the
        // focuses the allocation assigned rather than the focuses a single exercise implies.
        candidate("push-plank", "push-horizontal", focuses = setOf(Focus.PUSH, Focus.CORE)),
        // And one in a dimension §10 names without implementing: the refusal path, not a prescription.
        candidate(
            "core-weighted-plank",
            "core-stability",
            focuses = setOf(Focus.CORE),
            dimension = PrescriptionDimension.SET_BASED,
            equipment = RigEquipment.DUMBBELLS
        )
    )

    /** One candidate over a metadata value the app owns, with the equipment vocabulary kept generic. */
    fun candidate(
        id: String,
        familyId: String,
        focuses: Set<Focus>,
        dimension: PrescriptionDimension = PrescriptionDimension.REP_BASED,
        equipment: RigEquipment = RigEquipment.NONE
    ): GenerationCandidate<RigEquipment> = GenerationCandidate(
        metadata = ExerciseMetadata(
            id = id,
            familyId = familyId,
            trainingDomain = com.monkfitness.app.domain.adaptive.TrainingDomain.STRENGTH,
            bodyRegion = com.monkfitness.app.domain.adaptive.BodyRegion.FULL_BODY,
            requiredEquipment = if (equipment == RigEquipment.NONE) emptySet() else setOf(equipment)
        ),
        focuses = focuses,
        dimension = dimension
    )

    /** A request over the rig's library, with everything a test does not state left at its default. */
    fun request(
        focus: FocusPlan = FocusPlan.Balanced,
        schedule: ProgramSchedule = ProgramSchedule.FlexiblePerWeek(3),
        duration: ProgramDuration = ProgramDuration.Indefinite,
        candidates: List<GenerationCandidate<RigEquipment>> = LIBRARY,
        availableEquipment: Set<RigEquipment> = ALL_EQUIPMENT,
        preferences: GenerationPreferences = GenerationPreferences.NONE,
        policy: GenerationPolicy = GenerationPolicy.DEFAULT
    ): GenerationRequest<RigEquipment> = GenerationRequest(
        focus = focus,
        schedule = schedule,
        duration = duration,
        candidates = candidates,
        availableEquipment = availableEquipment,
        preferences = preferences,
        policy = policy
    )

    /** Preferences stating the caller's plain signals, with the lists named rather than positional. */
    fun preferences(
        userPreferred: List<String> = emptyList(),
        adaptivePreferred: List<String> = emptyList(),
        recent: List<String> = emptyList(),
        recentExposure: Map<Focus, Int> = emptyMap(),
        recentLoad: Map<Focus, Int> = emptyMap(),
        recovery: RecoveryContext = RecoveryContext.UNKNOWN
    ): GenerationPreferences = GenerationPreferences(
        userPreferredExerciseIds = userPreferred,
        adaptivePreferredExerciseIds = adaptivePreferred,
        recentExerciseIds = recent,
        recentExposureByFocus = recentExposure,
        recentLoadByFocus = recentLoad,
        recovery = recovery
    )

    /** A three-day-week schedule on the rig's own weekdays, for the fixed-weekday cases. */
    val THREE_FIXED_WEEKDAYS: ProgramSchedule = ProgramSchedule.FixedWeekdays(
        setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)
    )
}
