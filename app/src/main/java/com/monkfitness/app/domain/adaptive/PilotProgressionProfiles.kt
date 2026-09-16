package com.monkfitness.app.domain.adaptive

/**
 * The six pilot family profiles: the Stage 1 ladders for `pushups`, `squats`, `lunges`, `plank`,
 * `pullups` and `glute_bridge`.
 *
 * Every id below is an exercise the library already defines, in the family it already belongs to —
 * `ProgressionResolverTest` reads the library catalogue and fails if that stops being true. No
 * exercise is added, no exercise metadata is restated, and a family that has no profile here cannot be
 * progressed at all: that is the honest state until its ladder is designed, and neither the resolver
 * nor this object invents one for it.
 *
 * The ladders are v1 pilot choices, not physiological truths. Their order follows the library's own
 * definitions — principally its rep budgets, which tighten as a variation gets harder — and they are
 * meant to be replaced by a later profile set without touching the resolver.
 */
object PilotProgressionProfiles {

    /**
     * The push-up ladder. The library's own budgets tier it: knee push-ups (8-12) are the entry
     * variation, standard push-ups (6-8) the baseline, and the wide and decline variations (both 5-7)
     * are the steps above it. The military push-up is a *variation of the same step*, not a harder
     * one (it carries the standard budget), so it is deliberately not a rung.
     *
     * The family declares the pilot volume fallback: with the harder variations disabled, the family
     * keeps the variation the user enabled and adds one rep step to it.
     */
    val pushups: ProgressionProfile = ProgressionProfile(
        familyId = "pushups",
        axis = ProgressionAxis.REP_VARIATION,
        ladder = listOf(
            step("pushups_knee"),                        // -2
            step("pushups", adjustment = -1),            // -1: standard push-ups at reduced volume
            step("pushups"),                             //  0: the library's standard push-up
            step("pushups_wide"),                        // +1
            step("decline_pushups")                      // +2
        ),
        fallbackStep = 1
    )

    /**
     * The squat ladder: the wide-base regression, the standard squat, then the unilateral cossack
     * squat (6-10) and the plyometric jump squat (8-12). The family's timer exercise, the deep-squat
     * hold, is a mobility hold rather than an easier or harder squat, so it is not a rung.
     *
     * No fallback: a squat variation the user disabled holds the family rather than turning into a
     * rep change on every workout.
     */
    val squats: ProgressionProfile = ProgressionProfile(
        familyId = "squats",
        axis = ProgressionAxis.REP_VARIATION,
        ladder = listOf(
            step("squats_sumo"),                         // -2
            step("squats", adjustment = -1),             // -1: standard squats at reduced volume
            step("squats"),                              //  0: the library's standard squat
            step("cossack_squat"),                       // +1
            step("squats_jump")                          // +2
        )
    )

    /**
     * The lunge ladder: the reverse lunge as the entry variation, forward lunges as the baseline, then
     * the step-up (balance and elevation added) and the side lunge (the library's tightest lunge
     * budget, 6-10).
     */
    val lunges: ProgressionProfile = ProgressionProfile(
        familyId = "lunges",
        axis = ProgressionAxis.REP_VARIATION,
        ladder = listOf(
            step("lunges_reverse"),                      // -2
            step("lunges", adjustment = -1),             // -1: forward lunges at reduced volume
            step("lunges"),                              //  0: the library's forward lunge
            step("step_ups"),                            // +1
            step("lunges_side")                          // +2
        )
    )

    /**
     * The plank ladder is a duration ladder: one exercise, five hold lengths one adjustment step
     * apart, on the library's 30-second base. Nothing here is a variation change — the side plank is a
     * different hold, not a harder or easier plank, so it is not a rung.
     */
    val plank: ProgressionProfile = ProgressionProfile(
        familyId = "plank",
        axis = ProgressionAxis.TIMER,
        ladder = listOf(
            step("plank", adjustment = -2),              // -2
            step("plank", adjustment = -1),              // -1
            step("plank"),                               //  0: the library's standard hold
            step("plank", adjustment = 1),               // +1
            step("plank", adjustment = 2)                // +2
        )
    )

    /**
     * The pull-up ladder is the mixed one: its entry rung is the timed dead hang (a duration hold, the
     * regression a user who cannot yet pull is actually given), and every rung above it is a grip
     * variation ordered by the library's own budgets — chin-up (4-6), standard (3-5), neutral (3-5),
     * wide (2-4).
     */
    val pullups: ProgressionProfile = ProgressionProfile(
        familyId = "pullups",
        axis = ProgressionAxis.MIXED,
        ladder = listOf(
            step("hang"),                                // -2: the timed dead hang
            step("pullups_chin"),                        // -1
            step("pullups"),                             //  0: the library's standard pull-up
            step("pullups_neutral"),                     // +1
            step("pullups_wide")                         // +2
        )
    )

    /**
     * The glute-bridge ladder is corrective volume: the library defines exactly one glute-bridge
     * variation, so the level moves the volume and never the exercise. A harder variation would have
     * to be invented, which this layer must never do.
     */
    val gluteBridge: ProgressionProfile = ProgressionProfile(
        familyId = "glute_bridge",
        axis = ProgressionAxis.CORRECTIVE,
        ladder = listOf(
            step("glute_bridge", adjustment = -2),       // -2
            step("glute_bridge", adjustment = -1),       // -1
            step("glute_bridge"),                        //  0: the library's standard bridge
            step("glute_bridge", adjustment = 1),        // +1
            step("glute_bridge", adjustment = 2)         // +2
        )
    )

    /** The pilot profiles, in a stable order. */
    val all: List<ProgressionProfile> = listOf(pushups, squats, lunges, plank, pullups, gluteBridge)

    /**
     * The pilot profile for [familyId], or `null` when the family has none. A `null` is not a gap to
     * fill with a default profile: a family whose ladder has not been designed cannot be progressed.
     */
    fun forFamily(familyId: String): ProgressionProfile? = all.firstOrNull { it.familyId == familyId }

    private fun step(exerciseId: String, adjustment: Int = 0): ProgressionStep =
        ProgressionStep(exerciseId = exerciseId, adjustment = adjustment)
}
