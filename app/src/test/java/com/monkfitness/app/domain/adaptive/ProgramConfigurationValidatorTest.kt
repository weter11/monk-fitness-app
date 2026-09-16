package com.monkfitness.app.domain.adaptive

import com.monkfitness.app.data.model.Equipment
import com.monkfitness.app.data.model.ExerciseCategory
import com.monkfitness.app.data.model.ExerciseSubCategory
import com.monkfitness.app.data.model.bodyRegion
import com.monkfitness.app.data.model.toConfigurationMetadata
import com.monkfitness.app.data.model.trainingDomain
import com.monkfitness.app.domain.usecase.WorkoutGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The configuration-validation contract of `ProgramConfigurationValidator`.
 *
 * What is pinned here:
 *
 *  * the training-domain and body-region vocabularies are the app's own — every one of the library's
 *    real exercises is classified from its real `category` and `subCategory`, and the rules are
 *    stated over those real ids (no manufactured catalogue entries);
 *  * a selection that omits a required training domain, or that enables an exercise the user's
 *    equipment cannot support, is a HARD error (the configuration cannot be applied);
 *  * a selection that is structurally valid but leaves a body region uncovered, or concentrates on
 *    one region, is a SOFT warning — it is never upgraded into a rejection;
 *  * the validator never repairs anything: no exercise is re-enabled, inserted, merged with the
 *    defaults or replaced, the input configuration is untouched, and the result cannot even carry a
 *    selection;
 *  * the validator stays out of progression: a custom selection with no next rung is still
 *    structurally valid, nothing is invented for it, and `ProgressionResolver` keeps the HOLD;
 *  * the equipment rule is the app's own availability rule, proven exhaustively against
 *    `WorkoutGenerator.getExerciseLibrary(availableEquipment)` for every library exercise;
 *  * the output is deterministic: identical inputs always produce an identical, canonically ordered
 *    result, whatever order the library or the enabled set happened to arrive in;
 *  * the two new production files are pure domain code — no data layer, no Room/DataStore, no
 *    Android, no UI, no generator, no clock, no random source and no progression.
 */
class ProgramConfigurationValidatorTest {

    // ------------------------------------------------------------------ the real catalogue

    private val generator = WorkoutGenerator()

    /** The library's real metadata, derived from the app's own `Exercise` definitions. */
    private val library: List<ExerciseMetadata<Equipment>> =
        generator.getExerciseLibrary().map { it.toConfigurationMetadata() }

    private val metadataById: Map<String, ExerciseMetadata<Equipment>> =
        library.associateBy { it.id }

    private val libraryIds: Set<String> = library.map { it.id }.toSet()

    private val equipmentGatedIds: Set<String> =
        generator.getExerciseLibrary().filter { it.requiredEquipment.isNotEmpty() }.map { it.id }.toSet()

    private val allEquipment: Set<Equipment> = Equipment.entries.toSet()
    private val noEquipment: Set<Equipment> = setOf(Equipment.NONE)

    private fun metadataOf(exerciseId: String): ExerciseMetadata<Equipment> =
        requireNotNull(metadataById[exerciseId]) { "the library does not define $exerciseId" }

    // ------------------------------------------------------------------ fixtures (real ids only)

    /** Strength work without any flexibility work: the flexibility channel is missing. */
    private val strengthOnly = setOf("pushups", "squats", "plank")

    /** Flexibility work without any strength work: the strength channel is missing. */
    private val flexibilityOnly = setOf("cat_cow", "deep_squat", "child_pose")

    /** Every enabled exercise here needs equipment the user does not have. */
    private val equipmentBound = setOf(
        "pullups",
        "dips",
        "face_pull",
        "band_pull_aparts",
        "kettlebell_swing"
    )

    /** Applies through the validator against the real library unless a test says otherwise. */
    private fun validate(
        enabledExerciseIds: Set<String>,
        availableEquipment: Set<Equipment> = emptySet(),
        exerciseLibrary: Collection<ExerciseMetadata<Equipment>> = library
    ): ProgramConfigurationValidation = ProgramConfigurationValidator.validate(
        configuration = configurationOf(enabledExerciseIds),
        exerciseLibrary = exerciseLibrary,
        availableEquipment = availableEquipment
    )

    private fun configurationOf(enabledExerciseIds: Set<String>): ProgramConfiguration =
        ProgramConfiguration.custom(enabledExerciseIds, configurationVersion = 1)

    private fun errorCodes(validation: ProgramConfigurationValidation): List<ProgramConfigurationErrorCode> =
        validation.errors.map { it.code }

    private fun warningCodes(validation: ProgramConfigurationValidation): List<ProgramConfigurationWarningCode> =
        validation.warnings.map { it.code }

    // ------------------------------------------------------------------ the vocabulary is the app's own

    /**
     * The training-domain vocabulary is derived from the app's authoritative `Exercise.category`:
     * loaded strength work is the strength domain, and the flexibility channel the mobility days and
     * the posture/mobility sessions are generated from — stretching, mobility and posture — is the
     * flexibility domain. Asserted over every real exercise, so a category that stops being mapped
     * fails here rather than drifting silently.
     */
    @Test
    fun theTrainingDomainVocabularyFollowsTheAppsOwnCategories() {
        val expectedPartition = mapOf(
            TrainingDomain.STRENGTH to setOf(ExerciseCategory.STRENGTH),
            TrainingDomain.FLEXIBILITY to setOf(
                ExerciseCategory.MOBILITY,
                ExerciseCategory.STRETCHING,
                ExerciseCategory.POSTURE
            )
        )

        // The vocabulary is a partition of the app's categories, and nothing is left out of it.
        assertEquals(ExerciseCategory.entries.toSet(), expectedPartition.values.flatten().toSet())
        assertEquals(ExerciseCategory.entries.size, expectedPartition.values.flatten().size)

        generator.getExerciseLibrary().forEach { exercise ->
            val expected = expectedPartition.entries.single { exercise.category in it.value }.key
            assertEquals(
                "${exercise.id} (${exercise.category}) must be classified as $expected",
                expected,
                exercise.trainingDomain
            )
        }

        // The real library is split by loaded work versus flexibility work: 30 strength, 36 flexibility.
        assertEquals(30, library.count { it.trainingDomain == TrainingDomain.STRENGTH })
        assertEquals(36, library.count { it.trainingDomain == TrainingDomain.FLEXIBILITY })
    }

    /**
     * The body-region vocabulary is the app's own subcategory vocabulary, which is what the app
     * itself reports as its body regions. Asserted over every real exercise.
     */
    @Test
    fun theBodyRegionVocabularyFollowsTheAppsOwnSubCategories() {
        val expected = mapOf(
            ExerciseSubCategory.SHOULDERS to BodyRegion.SHOULDERS,
            ExerciseSubCategory.SPINE to BodyRegion.SPINE,
            ExerciseSubCategory.HIPS to BodyRegion.HIPS,
            ExerciseSubCategory.LEGS to BodyRegion.LEGS,
            ExerciseSubCategory.CORE to BodyRegion.CORE,
            ExerciseSubCategory.FULL_BODY to BodyRegion.FULL_BODY,
            ExerciseSubCategory.HYPERLORDOSIS to BodyRegion.HYPERLORDOSIS
        )
        assertEquals(ExerciseSubCategory.entries.size, BodyRegion.entries.size)

        generator.getExerciseLibrary().forEach { exercise ->
            assertEquals(
                "${exercise.id} (${exercise.subCategory})",
                expected.getValue(exercise.subCategory),
                exercise.bodyRegion
            )
        }

        // Measured over the library as it actually is.
        assertEquals(21, library.count { it.bodyRegion == BodyRegion.SHOULDERS })
        assertEquals(14, library.count { it.bodyRegion == BodyRegion.LEGS })
        assertEquals(8, library.count { it.bodyRegion == BodyRegion.FULL_BODY })
        assertEquals(8, library.count { it.bodyRegion == BodyRegion.SPINE })
        assertEquals(7, library.count { it.bodyRegion == BodyRegion.CORE })
        assertEquals(6, library.count { it.bodyRegion == BodyRegion.HIPS })
        assertEquals(2, library.count { it.bodyRegion == BodyRegion.HYPERLORDOSIS })
    }

    /** One exercise's metadata is its own id, family, domain, region and equipment — nothing invented. */
    @Test
    fun anExercisesMetadataIsItsOwnIdFamilyDomainRegionAndEquipment() {
        assertEquals(
            metadataOf("pullups"),
            ExerciseMetadata<Equipment>("pullups", "pullups", TrainingDomain.STRENGTH, BodyRegion.SHOULDERS, setOf(Equipment.BAR))
        )
        assertEquals(
            metadataOf("hang"),
            ExerciseMetadata<Equipment>("hang", "pullups", TrainingDomain.FLEXIBILITY, BodyRegion.SHOULDERS, setOf(Equipment.BAR))
        )
        assertEquals(
            metadataOf("deep_squat"),
            ExerciseMetadata<Equipment>("deep_squat", "squats", TrainingDomain.FLEXIBILITY, BodyRegion.LEGS, emptySet())
        )
        assertEquals(
            metadataOf("kettlebell_swing"),
            ExerciseMetadata<Equipment>("kettlebell_swing", "burpees", TrainingDomain.STRENGTH, BodyRegion.FULL_BODY, setOf(Equipment.BACKPACK))
        )
        assertEquals(
            metadataOf("plank"),
            ExerciseMetadata<Equipment>("plank", "plank", TrainingDomain.STRENGTH, BodyRegion.CORE, emptySet())
        )
        assertEquals(
            metadataOf("face_pull"),
            ExerciseMetadata<Equipment>("face_pull", "face_pull", TrainingDomain.FLEXIBILITY, BodyRegion.SHOULDERS, setOf(Equipment.BANDS))
        )
        assertEquals(
            metadataOf("cat_cow"),
            ExerciseMetadata<Equipment>("cat_cow", "cat_cow", TrainingDomain.FLEXIBILITY, BodyRegion.SPINE, emptySet())
        )
    }

    /**
     * The rule tables are the documented ones, pinned as literals so a quietly loosened table cannot
     * ship: both training domains are required, and the body regions the program's strength and
     * functional days are assembled from are the five asserted here.
     */
    @Test
    fun theRequiredRuleTablesAreTheDocumentedOnes() {
        assertEquals(listOf(TrainingDomain.STRENGTH, TrainingDomain.FLEXIBILITY), ProgramConfigurationValidator.REQUIRED_TRAINING_DOMAINS)
        assertEquals(
            listOf(BodyRegion.SHOULDERS, BodyRegion.SPINE, BodyRegion.LEGS, BodyRegion.CORE, BodyRegion.FULL_BODY),
            ProgramConfigurationValidator.STRENGTH_DAY_BODY_REGIONS
        )
        // Every required domain is in the vocabulary and every domain is required: there is no
        // optional domain, so a selection can never be valid without covering all of them.
        assertEquals(TrainingDomain.entries.toSet(), ProgramConfigurationValidator.REQUIRED_TRAINING_DOMAINS.toSet())
    }

    /** No required domain or required region is unreachable in the real library. */
    @Test
    fun everyRequiredDomainAndRegionIsCoveredByTheRealLibrary() {
        ProgramConfigurationValidator.REQUIRED_TRAINING_DOMAINS.forEach { domain ->
            assertTrue(
                "the library defines no $domain exercise",
                library.any { it.trainingDomain == domain }
            )
        }
        ProgramConfigurationValidator.STRENGTH_DAY_BODY_REGIONS.forEach { region ->
            assertTrue(
                "the library defines no $region exercise",
                library.any { it.bodyRegion == region }
            )
        }
    }

    // ------------------------------------------------------------------ hard errors: required domains

    /** A program with no strength work at all omits a required training domain: it cannot be applied. */
    @Test
    fun aSelectionWithoutAnyStrengthWorkIsRejected() {
        val validation = validate(flexibilityOnly)

        assertFalse(validation.isValid)
        assertEquals(listOf(ProgramConfigurationErrorCode.MISSING_REQUIRED_TRAINING_DOMAIN), errorCodes(validation))
        assertEquals(TrainingDomain.STRENGTH, validation.errors.single().trainingDomain)
        assertEquals(null, validation.errors.single().exerciseId)
    }

    /** And the mirror image: no flexibility work is missing the other required domain. */
    @Test
    fun aSelectionWithoutAnyFlexibilityWorkIsRejected() {
        val validation = validate(strengthOnly)

        assertFalse(validation.isValid)
        assertEquals(listOf(ProgramConfigurationErrorCode.MISSING_REQUIRED_TRAINING_DOMAIN), errorCodes(validation))
        assertEquals(TrainingDomain.FLEXIBILITY, validation.errors.single().trainingDomain)
    }

    /** An empty selection is not silently completed with defaults: it omits every required domain. */
    @Test
    fun anEmptySelectionReportsEveryRequiredDomain() {
        val validation = validate(emptySet())

        assertFalse(validation.isValid)
        assertEquals(
            listOf(
                ProgramConfigurationErrorCode.MISSING_REQUIRED_TRAINING_DOMAIN,
                ProgramConfigurationErrorCode.MISSING_REQUIRED_TRAINING_DOMAIN
            ),
            errorCodes(validation)
        )
        assertEquals(
            ProgramConfigurationValidator.REQUIRED_TRAINING_DOMAINS,
            validation.errors.map { it.trainingDomain }
        )
    }

    // ------------------------------------------------------------------ hard errors: equipment

    /** An exercise the user's equipment cannot support is selected but unusable. */
    @Test
    fun aSelectedExerciseRequiringUnavailableEquipmentIsAHardError() {
        val validation = validate(setOf("pullups", "squats", "deep_squat", "cat_cow"), noEquipment)

        assertFalse(validation.isValid)
        assertEquals(
            listOf(ProgramConfigurationErrorCode.EXERCISE_REQUIRES_UNAVAILABLE_EQUIPMENT),
            errorCodes(validation)
        )
        assertEquals("pullups", validation.errors.single().exerciseId)
        assertEquals(null, validation.errors.single().trainingDomain)
    }

    /** Every offending exercise is reported, once each, ordered by id. */
    @Test
    fun everyOffendingExerciseIsReportedOnceInIdOrder() {
        val validation = validate(equipmentBound, noEquipment)

        assertEquals(
            listOf(
                "band_pull_aparts",
                "dips",
                "face_pull",
                "kettlebell_swing",
                "pullups"
            ),
            validation.errors.mapNotNull { it.exerciseId }
        )
        assertTrue(
            validation.errors
                .filter { it.exerciseId != null }
                .all { it.code == ProgramConfigurationErrorCode.EXERCISE_REQUIRES_UNAVAILABLE_EQUIPMENT }
        )
        // Each offender appears exactly once even though they were enabled as one set.
        val equipmentErrors = validation.errors.filter { it.exerciseId != null }
        assertEquals(equipmentErrors.size, equipmentErrors.mapNotNull { it.exerciseId }.distinct().size)
    }

    /**
     * Availability is the app's own rule: equipment never recorded (the empty set) leaves every
     * exercise usable, and a recorded set admits exactly the exercises it covers. Proven
     * exhaustively against `WorkoutGenerator.getExerciseLibrary(availableEquipment)` — the app's
     * authoritative filter — for every exercise in the library.
     */
    @Test
    fun theEquipmentRuleIsTheAppsOwnAccessibilityRule() {
        val availabilities = listOf(
            emptySet(),
            noEquipment,
            setOf(Equipment.BAR),
            setOf(Equipment.BANDS),
            setOf(Equipment.BACKPACK),
            setOf(Equipment.BAR, Equipment.BANDS, Equipment.BACKPACK),
            allEquipment
        )

        availabilities.forEach { available ->
            val accessible = generator.getExerciseLibrary(available).map { it.id }.toSet()
            val validation = validate(libraryIds, available)
            val reported = validation.errors.mapNotNull { it.exerciseId }.toSet()

            assertEquals("unusable exercises for $available", libraryIds - accessible, reported)
            assertEquals("reported twice for $available", reported.size, validation.errors.size)
            assertEquals(libraryIds.size, accessible.size + reported.size)
        }
    }

    /** Recording no equipment is not the same as recording "I have nothing". */
    @Test
    fun noEquipmentRecordedIsNotTheSameAsHavingNoEquipment() {
        assertTrue(validate(libraryIds).isValid)
        assertEquals(emptyList<ProgramConfigurationError>(), validate(libraryIds).errors)

        val withoutAnything = validate(libraryIds, noEquipment)
        assertFalse(withoutAnything.isValid)
        assertEquals(equipmentGatedIds, withoutAnything.errors.mapNotNull { it.exerciseId }.toSet())
    }

    /** Both kinds of hard error can be present at once, in one deterministic order. */
    @Test
    fun bothKindsOfHardErrorAreReportedInOneDeterministicOrder() {
        // pullups, dips and rows need a bar the user does not have; what remains needs no equipment
        // but is flexibility work only, so the strength domain ends up uncovered as well.
        val validation = validate(setOf("pullups", "dips", "rows", "deep_squat", "cat_cow"), noEquipment)

        assertEquals(
            listOf(
                ProgramConfigurationErrorCode.MISSING_REQUIRED_TRAINING_DOMAIN,
                ProgramConfigurationErrorCode.EXERCISE_REQUIRES_UNAVAILABLE_EQUIPMENT,
                ProgramConfigurationErrorCode.EXERCISE_REQUIRES_UNAVAILABLE_EQUIPMENT,
                ProgramConfigurationErrorCode.EXERCISE_REQUIRES_UNAVAILABLE_EQUIPMENT
            ),
            errorCodes(validation)
        )
        assertEquals(TrainingDomain.STRENGTH, validation.errors.first().trainingDomain)
        assertEquals(listOf("dips", "pullups", "rows"), validation.errors.drop(1).map { it.exerciseId })
    }

    // ------------------------------------------------------------------ soft warnings

    /** A valid selection with a balance gap warns; it is not rejected. */
    @Test
    fun aTechnicallyValidSelectionWithABalanceGapWarnsInsteadOfFailing() {
        val validation = validate(setOf("pushups", "pushups_wide", "pushups_military", "deep_squat"))

        assertTrue("the selection covers both required domains", validation.isValid)
        assertEquals(emptyList<ProgramConfigurationError>(), validation.errors)
        assertTrue(validation.hasWarnings)
        assertEquals(
            listOf(
                ProgramConfigurationWarningCode.BODY_REGION_NOT_COVERED,
                ProgramConfigurationWarningCode.BODY_REGION_NOT_COVERED
            ),
            warningCodes(validation)
        )
        assertEquals(
            listOf(BodyRegion.SPINE, BodyRegion.CORE),
            validation.warnings.map { it.bodyRegion }
        )
        assertTrue(validation.warnings.all { it.usableExerciseCount == 0 })
    }

    /** Warnings are ordered by the region vocabulary, whatever order the rules ran in. */
    @Test
    fun warningsAreOrderedByTheRegionVocabulary() {
        val validation = validate(flexibilityOnly)

        assertEquals(
            listOf(
                ProgramConfigurationWarningCode.BODY_REGION_NOT_COVERED,
                ProgramConfigurationWarningCode.BODY_REGION_NOT_COVERED,
                ProgramConfigurationWarningCode.BODY_REGION_NOT_COVERED,
                ProgramConfigurationWarningCode.BODY_REGION_CONCENTRATION
            ),
            warningCodes(validation)
        )
        assertEquals(
            listOf(BodyRegion.SHOULDERS, BodyRegion.CORE, BodyRegion.FULL_BODY, BodyRegion.SPINE),
            validation.warnings.map { it.bodyRegion }
        )
        assertEquals(2, validation.warnings.last().usableExerciseCount)
    }

    /** Extreme concentration — one region holding more than all the others combined — warns only. */
    @Test
    fun extremeConcentrationWarnsWithoutRejecting() {
        val validation = validate(
            setOf("hang", "pullups", "pullups_chin", "pullups_neutral", "pullups_wide", "rows", "deep_squat", "cat_cow"),
            setOf(Equipment.BAR)
        )

        assertTrue(validation.isValid)
        assertEquals(emptyList<ProgramConfigurationError>(), validation.errors)
        assertEquals(
            ProgramConfigurationWarning(
                code = ProgramConfigurationWarningCode.BODY_REGION_CONCENTRATION,
                bodyRegion = BodyRegion.SHOULDERS,
                usableExerciseCount = 6
            ),
            validation.warnings.last()
        )
        assertEquals(
            listOf(BodyRegion.CORE, BodyRegion.FULL_BODY),
            validation.warnings.dropLast(1).map { it.bodyRegion }
        )
    }

    /** A region that merely ties the rest is not a concentration: the rule is strictly greater. */
    @Test
    fun aRegionThatOnlyTiesTheOthersIsNotAConcentration() {
        // full body 3 (push-ups) against legs 2 + spine 1: three against three.
        val validation = validate(
            setOf("pushups", "pushups_military", "pushups_knee", "squats", "lunges", "cat_cow")
        )

        assertTrue(validation.isValid)
        assertFalse(
            "three against three is not more than all the others combined",
            warningCodes(validation).contains(ProgramConfigurationWarningCode.BODY_REGION_CONCENTRATION)
        )
    }

    /** At most one region can satisfy the concentration rule, so the warning is never ambiguous. */
    @Test
    fun concentrationIsReportedForAtMostOneRegion() {
        listOf(
            setOf("hang", "pullups", "pullups_chin", "pullups_neutral", "pullups_wide", "rows", "deep_squat", "cat_cow"),
            flexibilityOnly,
            strengthOnly,
            libraryIds,
            equipmentBound
        ).forEach { selection ->
            val concentrated = validate(selection, setOf(Equipment.BAR, Equipment.BANDS, Equipment.BACKPACK))
                .warnings.filter { it.code == ProgramConfigurationWarningCode.BODY_REGION_CONCENTRATION }
            assertTrue("$selection produced ${concentrated.size} concentration warnings", concentrated.size <= 1)
        }
    }

    /** A balanced real selection warns about nothing. */
    @Test
    fun aBalancedRealSelectionWarnsAboutNothing() {
        val validation = validate(
            setOf("pushups", "squats", "pike_pushups", "plank", "cat_cow", "child_pose", "deep_squat", "horse_stance")
        )

        assertTrue(validation.isValid)
        assertEquals(emptyList<ProgramConfigurationError>(), validation.errors)
        assertEquals(emptyList<ProgramConfigurationWarning>(), validation.warnings)
    }

    /**
     * The authoritative default — every exercise the app ships — is applicable and warns about
     * nothing, which is the sanity anchor for both soft rules: they must not fire on a selection the
     * app itself considers complete.
     */
    @Test
    fun theAuthoritativeDefaultSelectionIsApplicableAndWarnFree() {
        val validation = validate(libraryIds)

        assertTrue(validation.isValid)
        assertEquals(emptyList<ProgramConfigurationError>(), validation.errors)
        assertEquals(emptyList<ProgramConfigurationWarning>(), validation.warnings)
    }

    /** A warning is never a rejection, and a rejection is never downgraded to a warning. */
    @Test
    fun softAndHardResultsAreNeverSwapped() {
        val warned = validate(setOf("pushups", "pushups_wide", "pushups_military", "deep_squat"))
        assertTrue(warned.isValid)
        assertTrue(warned.hasWarnings)

        val rejected = validate(strengthOnly)
        assertFalse(rejected.isValid)
        assertEquals(
            listOf(ProgramConfigurationErrorCode.MISSING_REQUIRED_TRAINING_DOMAIN),
            errorCodes(rejected)
        )
        // The balance warnings a rejection may also carry are still balance warnings: the missing
        // required domain is reported as the hard error it is, and never as one of them.
        assertTrue(
            warningCodes(rejected).all {
                it == ProgramConfigurationWarningCode.BODY_REGION_NOT_COVERED ||
                    it == ProgramConfigurationWarningCode.BODY_REGION_CONCENTRATION
            }
        )
    }

    /** The counts a warning reports are the ones that were measured, not a restated rule. */
    @Test
    fun warningCountsAreTheMeasuredOnes() {
        val selection = setOf("hang", "pullups", "pullups_chin", "pullups_neutral", "pullups_wide", "rows", "deep_squat", "cat_cow")
        val available = setOf(Equipment.BAR)
        val usableInShoulders = selection
            .map { metadataOf(it) }
            .count { it.bodyRegion == BodyRegion.SHOULDERS && it.requiredEquipment.all { equipment -> equipment in available } }

        val concentration = validate(selection, available)
            .warnings.single { it.code == ProgramConfigurationWarningCode.BODY_REGION_CONCENTRATION }

        assertEquals(usableInShoulders, concentration.usableExerciseCount)
        // Without the bar, five of those six exercises cannot be performed at all, and only the two
        // exercises that need nothing remain — one region each, so nothing is concentrated.
        assertFalse(
            "exercises the equipment cannot support must not count towards a region",
            validate(selection, noEquipment).warnings
                .any { it.code == ProgramConfigurationWarningCode.BODY_REGION_CONCENTRATION }
        )
    }

    // ------------------------------------------------------------------ no silent repair

    /**
     * The invariant the design names explicitly: the validator never silently re-enables a disabled
     * exercise, inserts an omitted one, or merges the selection with the defaults. The configuration
     * the user proposed is the configuration that comes back — untouched.
     */
    @Test
    fun theValidatorNeverRepairsTheConfigurationItIsGiven() {
        val selections = listOf(
            emptySet(),
            strengthOnly,
            flexibilityOnly,
            equipmentBound,
            setOf("pushups", "pushups_wide", "pushups_military", "deep_squat"),
            setOf("hang", "pullups", "pullups_chin", "deep_squat", "cat_cow"),
            libraryIds
        )

        selections.forEach { selection ->
            val configuration = configurationOf(selection)
            val enabledReference = configuration.enabledExerciseIds
            val enabledBefore = configuration.enabledExerciseIds.toSet()
            val versionBefore = configuration.configurationVersion
            val sourceBefore = configuration.source

            val validation = ProgramConfigurationValidator.validate(
                configuration = configuration,
                exerciseLibrary = library,
                availableEquipment = noEquipment
            )

            assertEquals("$selection was rewritten", enabledBefore, configuration.enabledExerciseIds)
            assertEquals("$selection lost its version", versionBefore, configuration.configurationVersion)
            assertEquals("$selection lost its source", sourceBefore, configuration.source)
            assertSame(
                "the enabled set instance was replaced",
                enabledReference,
                configuration.enabledExerciseIds
            )
            // The result names only exercises the user selected — never a substitute for one.
            assertTrue(
                "$selection: the result named an exercise outside the selection",
                validation.errors.mapNotNull { it.exerciseId }.all { it in enabledBefore }
            )
        }
    }

    /** A partial selection is never completed from the authoritative default set. */
    @Test
    fun aPartialSelectionIsNeverMergedWithTheDefaults() {
        val partial = setOf("pushups", "squats", "deep_squat")
        val configuration = configurationOf(partial)
        val default = configurationOf(libraryIds)

        ProgramConfigurationValidator.validate(configuration, library, emptySet())

        assertEquals(partial, configuration.enabledExerciseIds)
        assertEquals(3, configuration.enabledExerciseIds.size)
        assertFalse(configuration.hasSameEffectiveSelectionAs(default))
    }

    /** Validity is not produced by fulfilling a selection on the user's behalf. */
    @Test
    fun anInvalidSelectionIsNotMadeValidByInsertingTheMissingDomain() {
        val configuration = configurationOf(strengthOnly)

        val validation = ProgramConfigurationValidator.validate(configuration, library, emptySet())

        assertFalse(validation.isValid)
        assertFalse(
            "a flexibility exercise was not added for the user",
            configuration.enabledExerciseIds.any { metadataById.getValue(it).trainingDomain == TrainingDomain.FLEXIBILITY }
        )
        assertEquals(strengthOnly, configuration.enabledExerciseIds)
    }

    /**
     * The result type itself cannot carry a selection: it is its two result lists and nothing else,
     * so no caller can read a repaired configuration out of a validation.
     */
    @Test
    fun theValidationResultCarriesNoSelection() {
        // Compiler-generated fields (the Compose stability marker) are not part of the contract.
        val declared = ProgramConfigurationValidation::class.java.declaredFields
            .map { it.name }
            .filterNot { it.startsWith("$") }
            .sorted()
        assertEquals(listOf("errors", "warnings"), declared)
        assertTrue(
            "the result must not be able to name a repaired selection",
            declared.none { name ->
                val normalized = name.lowercase()
                normalized.contains("enabled") || normalized.contains("repair") ||
                    normalized.contains("replacement") || normalized.contains("default")
            }
        )
    }

    // ------------------------------------------------------------------ progression boundary

    /**
     * A valid custom configuration may have no valid next progression step at all, and that is not
     * this layer's problem: the configuration stays applicable, stays exactly as proposed, and no
     * replacement exercise is invented for it. The eventual answer — HOLD — belongs to
     * `ProgressionResolver`, which is exercised here only to prove the boundary holds.
     */
    @Test
    fun aCustomSelectionWithoutANextProgressionStepIsStillApplicable() {
        // The squash ladder's next rung above `squats` is `cossack_squat`; the user enabled neither it
        // nor `squats_jump`, and the family declares no volume fallback.
        val selection = setOf("squats", "pushups", "deep_squat")
        val configuration = configurationOf(selection)

        val validation = ProgramConfigurationValidator.validate(configuration, library, emptySet())

        assertTrue(validation.isValid)
        assertEquals(emptyList<ProgramConfigurationError>(), validation.errors)
        assertEquals(selection, configuration.enabledExerciseIds)
        assertFalse(configuration.enabledExerciseIds.any { it in setOf("squats_sumo", "cossack_squat", "squats_jump") })
        assertTrue(
            "no error or warning is a progression verdict",
            validation.errors.all { it.code == ProgramConfigurationErrorCode.MISSING_REQUIRED_TRAINING_DOMAIN || it.code == ProgramConfigurationErrorCode.EXERCISE_REQUIRES_UNAVAILABLE_EQUIPMENT }
        )
    }

    /** The resolver holds where no allowed rung exists — validation neither prevented nor produced it. */
    @Test
    fun theResolverKeepsTheHoldWhenTheEnabledGraphHasNoNextStep() {
        val selection = setOf("squats", "pushups", "deep_squat")
        val profile = PilotProgressionProfiles.squats

        val resolution = ProgressionResolver.resolve(
            familyId = profile.familyId,
            level = 0,
            direction = AdaptiveAction.INCREASE_STIMULUS,
            currentExerciseId = "squats",
            allowedExerciseIds = selection,
            profile = profile
        )

        assertEquals(ProgressionOutcome.HOLD, resolution.outcome)
        assertEquals(null, resolution.exerciseId)
        assertEquals(0, resolution.level)
        assertEquals(0, resolution.adjustment)

        // And validating the selection neither changed it nor made the rung reachable.
        ProgramConfigurationValidator.validate(configurationOf(selection), library, emptySet())
        val after = ProgressionResolver.resolve(
            familyId = profile.familyId,
            level = 0,
            direction = AdaptiveAction.INCREASE_STIMULUS,
            currentExerciseId = "squats",
            allowedExerciseIds = selection,
            profile = profile
        )
        assertEquals(resolution, after)
    }

    // ------------------------------------------------------------------ unknown ids

    /**
     * Membership is the persistence boundary's rule (Task 9's repository refuses an id outside the
     * library), so the validator does not restate it: an id it has no metadata for contributes no
     * domain, no region and no equipment requirement. It is neither repaired nor removed, and it can
     * still make a selection invalid the honest way — by covering nothing.
     */
    @Test
    fun anIdOutsideTheLibraryContributesNothingAndIsNeverRepaired() {
        val withUnknown = setOf("pushups", "squats", "deep_squat", "no_such_exercise")
        val configuration = configurationOf(withUnknown)

        val validation = ProgramConfigurationValidator.validate(configuration, library, emptySet())

        assertTrue(validation.isValid)
        assertEquals(emptyList<ProgramConfigurationError>(), validation.errors)
        assertTrue("the unknown id was not removed", "no_such_exercise" in configuration.enabledExerciseIds)
        assertEquals(withUnknown, configuration.enabledExerciseIds)

        // A selection made only of ids the library does not define covers no domain at all.
        val unknownOnly = ProgramConfigurationValidator.validate(configurationOf(setOf("no_such_exercise")), library, emptySet())
        assertFalse(unknownOnly.isValid)
        assertEquals(
            ProgramConfigurationValidator.REQUIRED_TRAINING_DOMAINS,
            unknownOnly.errors.map { it.trainingDomain }
        )
    }

    /** Two metadata entries for one exercise id would make the answer ambiguous: that is refused. */
    @Test
    fun twoMetadataEntriesForOneExerciseAreRefused() {
        val duplicated = library + metadataOf("pushups")

        assertThrows(IllegalArgumentException::class.java) {
            ProgramConfigurationValidator.validate(configurationOf(strengthOnly), duplicated, emptySet())
        }
    }

    // ------------------------------------------------------------------ determinism

    /** Identical inputs always produce an identical result. */
    @Test
    fun identicalInputsAlwaysProduceAnIdenticalResult() {
        val selection = setOf("hang", "pullups", "pullups_chin", "deep_squat", "cat_cow", "no_such_exercise")

        val first = validate(selection, setOf(Equipment.BAR))
        val second = validate(selection, setOf(Equipment.BAR))

        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
        assertEquals(first.warnings, second.warnings)
    }

    /** The order the library is handed in cannot change the answer. */
    @Test
    fun theLibraryIterationOrderCannotChangeTheAnswer() {
        val selection = setOf("hang", "pullups", "deep_squat", "cat_cow")
        val expected = validate(selection, setOf(Equipment.BAR))

        listOf(library.reversed(), library.sortedByDescending { it.id }, library.filterIndexed { index, _ -> index % 2 == 1 } + library.filterIndexed { index, _ -> index % 2 == 0 }).forEach { reordered ->
            assertEquals("reordering the library changed the answer", expected, validate(selection, setOf(Equipment.BAR), reordered))
        }
    }

    /** The order the enabled ids arrive in cannot change the error order. */
    @Test
    fun theEnabledSetIterationOrderCannotChangeTheErrorOrder() {
        val ids = listOf("pullups", "dips", "rows", "face_pull", "kettlebell_swing", "deep_squat")
        val forward = LinkedHashSet(ids)
        val backward = LinkedHashSet(ids.reversed())

        assertEquals(forward.toSet(), backward.toSet())
        assertEquals(
            validate(forward, noEquipment),
            validate(backward, noEquipment)
        )
        assertEquals(
            listOf("dips", "face_pull", "kettlebell_swing", "pullups", "rows"),
            validate(forward, noEquipment).errors.mapNotNull { it.exerciseId }
        )
    }

    /** The vocabulary names are stable tokens a later task can persist or switch on. */
    @Test
    fun theVocabularyNamesAreStableTokens() {
        assertEquals(listOf("STRENGTH", "FLEXIBILITY"), TrainingDomain.entries.map { it.name })
        assertEquals(
            listOf("SHOULDERS", "SPINE", "HIPS", "LEGS", "CORE", "FULL_BODY", "HYPERLORDOSIS"),
            BodyRegion.entries.map { it.name }
        )
        assertEquals(
            listOf("MISSING_REQUIRED_TRAINING_DOMAIN", "EXERCISE_REQUIRES_UNAVAILABLE_EQUIPMENT"),
            ProgramConfigurationErrorCode.entries.map { it.name }
        )
        assertEquals(
            listOf("BODY_REGION_NOT_COVERED", "BODY_REGION_CONCENTRATION"),
            ProgramConfigurationWarningCode.entries.map { it.name }
        )
    }

    // ------------------------------------------------------------------ purity

    /**
     * The architectural boundary, mechanically: the validation model and the validator are pure
     * domain Kotlin — no data layer, no Android, no Room/DataStore, no UI, no repository or
     * ViewModel, no generator, no clock, no random source — and no progression responsibility has
     * been absorbed into them.
     */
    @Test
    fun theValidatorAndItsModelArePureDomainCode() {
        val forbidden = listOf(
            "import android.", "import androidx.", "import com.monkfitness.app.data.",
            "import com.monkfitness.app.ui.", "import com.monkfitness.app.viewmodel.",
            "import kotlinx.",
            "Context", "Room", "DataStore", "AppDatabase", "SettingsManager",
            "MainViewModel", "AdaptiveRepository", "ProgramConfigurationRepository",
            "WorkoutGenerator", "applyDifficultyAdjustment", "Workout(",
            "ProgressionResolver", "ProgressionProfile", "PilotProgressionProfiles", "ProgressionOutcome",
            "FamilyAdaptationState", "AdaptiveDecision", "AdaptiveProgramEngine", "AdaptivePolicy",
            "AdaptiveSignalCalculator", "AdaptiveAction",
            "System.currentTimeMillis", "System.nanoTime", "LocalDate", "Random", "UUID",
            "ProgramConfiguration.default(", "resetToDefault(", ".applying(", "defaultEnabledExerciseIds"
        )

        listOf("ProgramConfigurationValidation.kt", "ProgramConfigurationValidator.kt").forEach { name ->
            val source = File(adaptiveSourceDir, name)
            assertTrue("expected $name at ${source.absolutePath}", source.isFile)
            val hits = forbidden.filter { source.readText().contains(it) }
            assertTrue("$name must stay pure domain code, found: $hits", hits.isEmpty())
        }
    }

    /** The validator's only dependency on the app's model is the mapping its caller supplies. */
    @Test
    fun theValidatorDependsOnlyOnItsThreeArguments() {
        val methods = ProgramConfigurationValidator::class.java.declaredMethods
            .filter { it.name == "validate" }

        assertEquals(1, methods.size)
        assertEquals(3, methods.single().parameterCount)
        assertEquals(ProgramConfigurationValidation::class.java, methods.single().returnType)
    }

    private val adaptiveSourceDir = File("src/main/java/com/monkfitness/app/domain/adaptive")
        .let { dir -> if (dir.isDirectory) dir else File("app/src/main/java/com/monkfitness/app/domain/adaptive") }
}
