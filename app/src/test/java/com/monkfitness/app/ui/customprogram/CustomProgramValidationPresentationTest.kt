package com.monkfitness.app.ui.customprogram

import com.monkfitness.app.R
import com.monkfitness.app.data.model.Equipment
import com.monkfitness.app.data.model.toConfigurationMetadata
import com.monkfitness.app.domain.adaptive.BodyRegion
import com.monkfitness.app.domain.adaptive.ProgramConfigurationErrorCode
import com.monkfitness.app.domain.adaptive.ProgramConfigurationValidator
import com.monkfitness.app.domain.adaptive.ProgramConfigurationWarningCode
import com.monkfitness.app.domain.adaptive.TrainingDomain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the editor tells the user when a selection cannot be applied, and what it tells them when a
 * selection can be applied but is unbalanced.
 *
 * The boundary these tests hold:
 *
 *  * the **domain validator decides**. Each presentation is compared field-by-field against a direct
 *    `ProgramConfigurationValidator.validate` call over the same draft, metadata and equipment, so a
 *    presentation that invented, dropped, reordered or reclassified a finding fails here;
 *  * `MISSING_REQUIRED_TRAINING_DOMAIN` and `EXERCISE_REQUIRES_UNAVAILABLE_EQUIPMENT` are **hard
 *    errors** and block Apply. Task 10 made unavailable equipment an error on purpose, and the editor
 *    may not soften it back into a warning;
 *  * `BODY_REGION_NOT_COVERED` and `BODY_REGION_CONCENTRATION` are **warnings** and never block;
 *  * the exercise that caused an equipment error is still listed, still shown and still enabled — the
 *    error is presented next to it, the selection is not silently fixed, hidden or disabled;
 *  * every message is a string resource, and the domain vocabulary a message is formatted from is
 *    mapped to localization in exactly one place, here.
 */
class CustomProgramValidationPresentationTest {

    private val rig = CustomProgramEditorRig()

    @After
    fun tearDown() {
        rig.close()
    }

    // ---- hard errors --------------------------------------------------------------------------

    @Test
    fun aMissingRequiredTrainingDomainIsPresentedAsABlockingError() = rig.inRig {
        editor.open()
        idsInDomain(TrainingDomain.FLEXIBILITY).forEach { editor.toggleExercise(it) }

        assertTrue(state().isApplyBlocked)
        val error = state().errors.single()
        assertEquals(ProgramConfigurationErrorCode.MISSING_REQUIRED_TRAINING_DOMAIN, error.code)
        assertEquals(TrainingDomain.FLEXIBILITY, error.trainingDomain)
        assertEquals(R.string.custom_program_error_missing_domain, error.messageRes)
        assertEquals(R.string.custom_program_domain_flexibility, error.subjectRes)
        assertTrue("a domain error names no exercise", error.exerciseId == null)
        assertTrue(error.requiredEquipmentRes.isEmpty())
    }

    @Test
    fun aMissingStrengthDomainIsPresentedTheSameWay() = rig.inRig {
        editor.open()
        idsInDomain(TrainingDomain.STRENGTH).forEach { editor.toggleExercise(it) }

        val error = state().errors.single()
        assertEquals(ProgramConfigurationErrorCode.MISSING_REQUIRED_TRAINING_DOMAIN, error.code)
        assertEquals(TrainingDomain.STRENGTH, error.trainingDomain)
        assertEquals(R.string.custom_program_domain_strength, error.subjectRes)
    }

    @Test
    fun anExerciseTheEquipmentCannotSupportIsPresentedAsABlockingError() {
        val gatedRig = CustomProgramEditorRig(initialEquipment = setOf(Equipment.BANDS))
        try {
            gatedRig.inRig {
                editor.open()

                assertTrue(state().isApplyBlocked)
                val error = state().errors.first { it.exerciseId == "pullups" }
                assertEquals(ProgramConfigurationErrorCode.EXERCISE_REQUIRES_UNAVAILABLE_EQUIPMENT, error.code)
                assertEquals(R.string.custom_program_error_unavailable_equipment, error.messageRes)
                assertEquals("the exercise that caused it is named", R.string.ex_pullups, error.subjectRes)
                assertEquals("and what it needs is named", listOf(R.string.equipment_bar), error.requiredEquipmentRes)
                assertTrue("an equipment error names no domain", error.trainingDomain == null)
            }
        } finally {
            gatedRig.close()
        }
    }

    @Test
    fun anUnusableExerciseBlocksApplyRatherThanWarning() {
        val gatedRig = CustomProgramEditorRig(initialEquipment = setOf(Equipment.BACKPACK))
        try {
            gatedRig.inRig {
                editor.open()

                assertTrue(
                    "the equipment finding is a hard error",
                    state().errors.any { it.code == ProgramConfigurationErrorCode.EXERCISE_REQUIRES_UNAVAILABLE_EQUIPMENT }
                )
                assertTrue("and it blocks Apply", state().isApplyBlocked)
                assertTrue(
                    "the finding is an error, not a balance warning the user could ignore",
                    state().errors.isNotEmpty() && state().warnings.isEmpty()
                )
            }
        } finally {
            gatedRig.close()
        }
    }

    @Test
    fun theOffendingExerciseIsStillListedShownAndSelectable() {
        val gatedRig = CustomProgramEditorRig(initialEquipment = setOf(Equipment.BANDS))
        try {
            gatedRig.inRig {
                editor.open()

                val pullups = group("pullups")
                val row = pullups.exercises.first { it.id == "pullups" }
                assertTrue("the offending exercise is not hidden from the family", pullups.isVisible)
                assertTrue("it is still ticked, exactly as the user left it", row.enabled)
                assertTrue(
                    "and the validator disagrees about it rather than the UI",
                    state().errors.any { it.exerciseId == "pullups" }
                )
            }
        } finally {
            gatedRig.close()
        }
    }

    @Test
    fun theEquipmentIsReadWhenTheEditorOpensSoAStaleSelectionIsReported() = rig.inRig {
        editor.open()
        assertTrue("every exercise is usable when no equipment was recorded", state().errors.isEmpty())
        val draftBefore = state().draftEnabledExerciseIds

        equipment = setOf(Equipment.BANDS)
        editor.open()

        assertTrue("the equipment error is reported on the next open", state().errors.any { it.exerciseId == "pullups" })
        assertEquals("the draft is untouched by the equipment change", draftBefore, state().draftEnabledExerciseIds)
        assertEquals("and nothing was persisted", defaultEnabledExerciseIds, repository.load().enabledExerciseIds)
        assertEquals(CustomProgramEditorRig.ABSENT, storedDigest())
    }

    // ---- soft warnings ------------------------------------------------------------------------

    @Test
    fun anUncoveredBodyRegionIsPresentedAsAWarningThatDoesNotBlock() = rig.inRig {
        editor.open()
        idsInRegion(BodyRegion.SHOULDERS).forEach { editor.toggleExercise(it) }

        assertFalse(state().isApplyBlocked)
        assertTrue(state().errors.isEmpty())
        val warning = state().warnings.first { it.code == ProgramConfigurationWarningCode.BODY_REGION_NOT_COVERED }
        assertEquals(BodyRegion.SHOULDERS, warning.bodyRegion)
        assertEquals(0, warning.usableExerciseCount)
        assertEquals(R.string.custom_program_warning_region_not_covered, warning.messageRes)
        assertEquals(R.string.subcategory_shoulders, warning.subjectRes)
    }

    @Test
    fun aConcentratedSelectionIsPresentedAsAWarningWithItsMeasuredCount() = rig.inRig {
        editor.open()
        (defaultEnabledExerciseIds - idsInFamily("pullups")).forEach { editor.toggleExercise(it) }

        assertTrue("a concentrated selection is still applicable", state().errors.isEmpty())
        val warning = state().warnings.first { it.code == ProgramConfigurationWarningCode.BODY_REGION_CONCENTRATION }
        assertEquals(BodyRegion.SHOULDERS, warning.bodyRegion)
        assertEquals("the count is the validator's measurement", idsInFamily("pullups").size, warning.usableExerciseCount)
        assertEquals(R.string.custom_program_warning_region_concentration, warning.messageRes)
        assertEquals(R.string.subcategory_shoulders, warning.subjectRes)

        val result = editor.apply()
        assertTrue("warnings never block Apply", result is CustomProgramApplyResult.Applied)
    }

    // ---- the validator stays the source of truth ----------------------------------------------

    @Test
    fun everyPresentedFindingIsTheValidatorsOwnFindingInTheValidatorsOwnOrder() = rig.inRig {
        editor.open()
        idsInRegion(BodyRegion.CORE).forEach { editor.toggleExercise(it) }
        idsInDomain(TrainingDomain.FLEXIBILITY).forEach { editor.toggleExercise(it) }

        val expected = validateTheDraftDirectly()

        assertEquals(
            "the error list is the validator's list",
            expected.errors.map { Triple(it.code, it.trainingDomain, it.exerciseId) },
            state().errors.map { Triple(it.code, it.trainingDomain, it.exerciseId) }
        )
        assertEquals(
            "the warning list is the validator's list",
            expected.warnings.map { Triple(it.code, it.bodyRegion, it.usableExerciseCount) },
            state().warnings.map { Triple(it.code, it.bodyRegion, it.usableExerciseCount) }
        )
        assertEquals(expected.isValid, !state().isApplyBlocked)
    }

    @Test
    fun thePresentationOfAValidAndBalancedSelectionIsEmpty() = rig.inRig {
        editor.open()

        assertTrue(state().errors.isEmpty())
        assertTrue(state().warnings.isEmpty())
        assertFalse(state().isApplyBlocked)
    }

    @Test
    fun eachHardErrorKindCarriesItsOwnLocalizedMessage() {
        // A selection with both kinds of hard error at once: exercises the user's equipment cannot
        // support (still enabled, reported) and — once the usable flexibility work is switched off —
        // no exercise of a required training domain.
        val gatedRig = CustomProgramEditorRig(initialEquipment = setOf(Equipment.BANDS))
        try {
            gatedRig.inRig {
                editor.open()
                val unusable = state().errors
                    .filter { it.code == ProgramConfigurationErrorCode.EXERCISE_REQUIRES_UNAVAILABLE_EQUIPMENT }
                    .mapNotNull { it.exerciseId }
                    .toSet()
                assertTrue("the equipment error must be present to test the pair", unusable.isNotEmpty())

                (idsInDomain(TrainingDomain.FLEXIBILITY) - unusable).forEach { editor.toggleExercise(it) }

                val messages = state().errors.map { it.messageRes }.toSet()
                assertEquals("the two hard-error kinds are two different messages", 2, messages.size)
                assertEquals(
                    setOf(
                        R.string.custom_program_error_missing_domain,
                        R.string.custom_program_error_unavailable_equipment
                    ),
                    messages
                )
                assertTrue(
                    "every presented finding carries a real message and a real subject",
                    state().errors.all { it.messageRes != 0 && it.subjectRes != 0 }
                )
                assertTrue(
                    "the equipment error still names its exercises",
                    state().errors.any { it.exerciseId == "pullups" && it.subjectRes == R.string.ex_pullups }
                )
            }
        } finally {
            gatedRig.close()
        }
    }

    @Test
    fun applyingAnEquipmentRejectedSelectionPersistsNothing() {
        val gatedRig = CustomProgramEditorRig(initialEquipment = setOf(Equipment.BANDS))
        try {
            gatedRig.inRig {
                editor.open()
                val result = editor.apply()

                assertTrue(result is CustomProgramApplyResult.Rejected)
                assertEquals(CustomProgramEditorRig.ABSENT, storedDigest())
                assertEquals(defaultEnabledExerciseIds, repository.load().enabledExerciseIds)
                assertTrue(state().isOpen)
            }
        } finally {
            gatedRig.close()
        }
    }

    private suspend fun CustomProgramEditorRig.validateTheDraftDirectly() =
        ProgramConfigurationValidator.validate(
            configuration = repository.load().applying(state().draftEnabledExerciseIds, defaultEnabledExerciseIds),
            exerciseLibrary = library.map { it.toConfigurationMetadata() },
            availableEquipment = equipment
        )
}
