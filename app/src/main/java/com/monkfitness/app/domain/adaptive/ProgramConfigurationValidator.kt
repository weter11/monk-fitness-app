package com.monkfitness.app.domain.adaptive

/**
 * The custom program's validation rule set: whether a proposed exercise selection is structurally
 * and training-domain valid, and — if it is — whether it is poorly balanced.
 *
 * ## What it validates, in one sentence
 *
 * A selection is applicable when it can still cover every training domain the program's own days are
 * generated from under the equipment the user actually has; it is *warned about* when it is
 * applicable but leaves a body region the program's days are built from unaddressed, or when it
 * concentrates in one region.
 *
 * ## What it is not
 *
 * It applies nothing, persists nothing, mutates nothing, generates no workout, resolves no
 * progression and touches no adaptive state. It is a pure function of its three arguments: given the
 * same configuration, the same metadata and the same equipment availability, it always returns the
 * same result. It reads no clock and no random source, and it never reaches for the defaults — the
 * authoritative default set is the caller's, and this type cannot discover it.
 *
 * ## It never repairs a configuration
 *
 * The design names this as the invariant, so it is worth stating in full: validation never adds a
 * disabled exercise, never re-enables one, never replaces the user's selection, never merges a
 * proposal with defaults, never mutates the configuration it was handed, never mutates the metadata
 * it was handed, and never picks a different exercise to make a configuration valid. A rejected
 * configuration remains exactly the user's rejected configuration, and the result carries no
 * selection at all.
 *
 * ## The progression boundary
 *
 * Choosing the next progression rung — and the HOLD that results when the enabled graph has no
 * allowed next step — belongs to the progression resolver, not here. A custom selection whose
 * enabled exercises cannot progress at all is still structurally valid, and this validator reports
 * nothing about it: it neither prevents the HOLD nor invents a target to avoid it.
 *
 * ## Determinism
 *
 * Nothing depends on hash or iteration order: the library is indexed by id, every rule is evaluated
 * by id or by vocabulary ordinal, and the result is sorted canonically before it is returned, so
 * reordering the caller's library or the user's enabled set cannot change the answer.
 */
object ProgramConfigurationValidator {

    /**
     * Every training domain the program requires, in vocabulary order.
     *
     * All of them are required: the app's calendar generates strength days (its strength days and its
     * functional day) and flexibility days (mobility, and the posture/mobility sessions) from these
     * two channels, so a selection that covers only one of them cannot fill the program. A missing
     * domain is a hard error, not a balance concern.
     */
    val REQUIRED_TRAINING_DOMAINS: List<TrainingDomain> = TrainingDomain.entries.toList()

    /**
     * The body regions the program's strength and functional days are assembled from, in vocabulary
     * order: the strength days ask for leg, core and full-body work plus a spine/shoulder posture
     * slot, and the functional day asks for full-body work.
     *
     * A selected exercise in any of these regions can serve those days, so a selection that leaves
     * one of them entirely unaddressed is a real balance gap — but a soft one: the days can still be
     * filled from the regions that are covered, and the user may have a reason. It is warned about,
     * never rejected.
     */
    val STRENGTH_DAY_BODY_REGIONS: List<BodyRegion> = listOf(
        BodyRegion.SHOULDERS,
        BodyRegion.SPINE,
        BodyRegion.LEGS,
        BodyRegion.CORE,
        BodyRegion.FULL_BODY
    )

    /**
     * Validates [configuration] against [exerciseLibrary] and [availableEquipment].
     *
     * @param configuration the user's proposed selection. It is only read: nothing about it is
     *   written, and the result is computed from it exactly as it is.
     * @param exerciseLibrary one metadata entry per library exercise, in any order. The library is
     *   the metadata source: an enabled id the library does not define has no training domain, no
     *   body region and no equipment requirement, so it contributes coverage to nothing. It is
     *   neither repaired nor removed, and it is not reported as an error either — membership of the
     *   library is enforced where a configuration is persisted, and restating that rule here would
     *   create a second source of truth for it.
     * @param availableEquipment the equipment the user has, in the caller's own vocabulary. An empty
     *   set means equipment was never recorded, which the app's own availability rule reads as
     *   unconstrained; a recorded set admits exactly the exercises whose requirements it covers.
     * @return the structured result: the hard errors that make the configuration unapplicable and
     *   the soft warnings about its balance, each canonically ordered.
     */
    fun <E> validate(
        configuration: ProgramConfiguration,
        exerciseLibrary: Collection<ExerciseMetadata<E>>,
        availableEquipment: Set<E>
    ): ProgramConfigurationValidation {
        requireDistinctExerciseIds(exerciseLibrary)

        val metadataById = exerciseLibrary.associateBy { it.id }

        // The selection, in the library's own terms. An id with no metadata contributes nothing.
        val enabled = configuration.enabledExerciseIds.mapNotNull { metadataById[it] }

        // An exercise the equipment cannot support cannot cover anything either: coverage is counted
        // over what the user can actually perform.
        val (usable, unusable) = enabled.partition { it.isUsableWith(availableEquipment) }

        val missingDomains = REQUIRED_TRAINING_DOMAINS
            .filterNot { domain -> usable.any { it.trainingDomain == domain } }
            .map { domain ->
                ProgramConfigurationError(
                    ProgramConfigurationErrorCode.MISSING_REQUIRED_TRAINING_DOMAIN,
                    trainingDomain = domain
                )
            }
        val unusableExercises = unusable.map { metadata ->
            ProgramConfigurationError(
                ProgramConfigurationErrorCode.EXERCISE_REQUIRES_UNAVAILABLE_EQUIPMENT,
                exerciseId = metadata.id
            )
        }
        val errors = (missingDomains + unusableExercises).sortedWith(ERROR_ORDER)

        val usableByRegion = usable.groupingBy { it.bodyRegion }.eachCount()

        val coverageWarnings = STRENGTH_DAY_BODY_REGIONS
            .filterNot { region -> region in usableByRegion }
            .map { region ->
                ProgramConfigurationWarning(
                    ProgramConfigurationWarningCode.BODY_REGION_NOT_COVERED,
                    bodyRegion = region,
                    usableExerciseCount = 0
                )
            }
        val concentrated = concentratedRegion(usableByRegion, usable.size)
        val concentrationWarnings: List<ProgramConfigurationWarning> = if (concentrated == null) {
            emptyList()
        } else {
            listOf(
                ProgramConfigurationWarning(
                    ProgramConfigurationWarningCode.BODY_REGION_CONCENTRATION,
                    bodyRegion = concentrated.first,
                    usableExerciseCount = concentrated.second
                )
            )
        }

        val warnings = (coverageWarnings + concentrationWarnings).sortedWith(WARNING_ORDER)

        return ProgramConfigurationValidation(errors = errors, warnings = warnings)
    }

    /**
     * Whether an exercise is usable with [availableEquipment].
     *
     * This is the app's own availability rule, in the one shape a pure domain can state it: an empty
     * availability means equipment was never recorded and constrains nothing, and an exercise's
     * requirements must all be covered otherwise. The app's own filter is the authority for that
     * rule, and the validation can never disagree with it — the requirement sets this reads exclude
     * the app's "no equipment" token, so a recorded set of exactly that token admits exactly the
     * exercises that need nothing.
     */
    private fun <E> ExerciseMetadata<E>.isUsableWith(availableEquipment: Set<E>): Boolean =
        availableEquipment.isEmpty() || requiredEquipment.all { it in availableEquipment }

    /**
     * The single body region that holds more usable exercises than all the others combined, or `null`
     * when no region does.
     *
     * The comparison is strict, so at most one region can qualify and there is nothing to break a tie
     * between; the vocabulary order is still applied so the result stays deterministic even if that
     * reasoning is ever invalidated.
     */
    private fun concentratedRegion(
        counts: Map<BodyRegion, Int>,
        usableCount: Int
    ): Pair<BodyRegion, Int>? = counts.entries
        .filter { (_, count) -> count > usableCount - count }
        .minByOrNull { (region, _) -> region.ordinal }
        ?.let { (region, count) -> region to count }

    /** Errors are ordered by code, then by the exercise id or the domain they name. */
    private val ERROR_ORDER: Comparator<ProgramConfigurationError> = compareBy(
        { it.code.ordinal },
        { it.exerciseId ?: "" },
        { it.trainingDomain?.ordinal ?: -1 }
    )

    /** Warnings are ordered by code, then by the region vocabulary. */
    private val WARNING_ORDER: Comparator<ProgramConfigurationWarning> = compareBy(
        { it.code.ordinal },
        { it.bodyRegion.ordinal }
    )

    private fun <E> requireDistinctExerciseIds(exerciseLibrary: Collection<ExerciseMetadata<E>>) {
        val duplicated = exerciseLibrary
            .groupingBy { it.id }
            .eachCount()
            .filterValues { count -> count > 1 }
            .keys
        require(duplicated.isEmpty()) {
            "an exercise library must define each exercise once; duplicated: ${duplicated.sorted()}"
        }
    }
}
