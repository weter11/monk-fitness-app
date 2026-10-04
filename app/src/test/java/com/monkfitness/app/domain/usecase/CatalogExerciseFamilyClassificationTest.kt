package com.monkfitness.app.domain.usecase

import com.monkfitness.app.data.model.toConfigurationMetadata
import com.monkfitness.app.domain.adaptive.integration.ExerciseFamilyClassification
import com.monkfitness.app.domain.prescription.PrescriptionDimension
import com.monkfitness.app.domain.program.Focus
import com.monkfitness.app.domain.program.generated.GenerationCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P30's classification, measured against the **shipped catalogue itself** rather than a fixture.
 *
 * Every expectation in this suite is derived by asking [WorkoutGenerator] what it holds. That is the
 * point: a table of expected families written out by hand would be a *second catalogue*, and it would
 * agree with this one only until the catalogue changed — at which point the suite would be asserting a
 * fiction while production kept working. Reading the same source production reads turns every claim into
 * a statement about agreement between two consumers of one fact, which is the only claim P30 makes.
 */
class CatalogExerciseFamilyClassificationTest {

    private val classification: ExerciseFamilyClassification = CatalogExerciseFamilyClassification()
    private val catalogue = WorkoutGenerator().getExerciseLibrary()

    /**
     * A known exercise answers its **exact stored family** — not a family it happens to share, not a
     * normalised form. Asserted against every catalogue entry, so a single mis-read fails the suite.
     */
    @Test
    fun everyKnownExerciseAnswersItsExactStoredFamily() {
        assertTrue("the catalogue must not be empty for this to mean anything", catalogue.isNotEmpty())
        catalogue.forEach { exercise ->
            assertEquals(
                "exercise '${exercise.id}' states family '${exercise.familyId}' in the catalogue, " +
                    "and the classification must answer that exact value",
                exercise.familyId,
                classification.familyOf(exercise.id)
            )
        }
    }

    /**
     * Several exercises in one family resolve to **one identical** string — the property that makes the
     * classification a grouping rather than a relabelling. `pushups` is the largest family in the
     * catalogue, so a per-exercise answer that leaked through would show up here first.
     */
    @Test
    fun severalExercisesInOneFamilyResolveIdentically() {
        val families = catalogue.groupBy { exercise -> exercise.familyId }
        val multiMember = families.filterValues { members -> members.size > 1 }
        assertTrue(
            "the catalogue must hold multi-member families for this to mean anything ($multiMember)",
            multiMember.isNotEmpty()
        )

        multiMember.forEach { (familyId, members) ->
            val answered = members.map { exercise -> classification.familyOf(exercise.id) }
            assertEquals(
                "all ${members.size} members of family '$familyId' must answer one family, got $answered",
                1,
                answered.toSet().size
            )
            assertEquals(
                "and that one family is '$familyId'",
                setOf(familyId),
                answered.toSet()
            )
        }
    }

    /**
     * Different families stay **distinct**. Without this, a source that answered one constant for every
     * exercise would satisfy the previous test perfectly — the two assertions are what separate a
     * grouping from a constant.
     */
    @Test
    fun differentFamilyIdsRemainDistinct() {
        val familyIds = catalogue.map { exercise -> exercise.familyId }.toSet()
        assertTrue("the catalogue must hold several families", familyIds.size > 1)

        familyIds.forEach { familyId ->
            val member = catalogue.first { exercise -> exercise.familyId == familyId }
            assertEquals(
                "a member of '$familyId' must answer '$familyId'",
                familyId,
                classification.familyOf(member.id)
            )
        }

        // And the classification never collapses two declared families onto one another.
        assertEquals(
            "every declared family must be answered as itself, so none is shadowed by another",
            familyIds.size,
            familyIds.map { familyId ->
                catalogue.first { exercise -> exercise.familyId == familyId }.let { member ->
                    classification.familyOf(member.id)
                }
            }.toSet().size
        )
    }

    /**
     * An exercise id the app does not ship is answered **absence**, never a guess.
     *
     * Three shapes of unknown are used deliberately: an id that is not in the catalogue at all, an id
     * that is a *different* exercise's id with a suffix, and the empty string. A source that fell back
     * to a default family, or that parsed a family out of the id text, would answer all three wrongly.
     */
    @Test
    fun anUnknownExerciseReturnsAbsence() {
        val known = catalogue.first { exercise -> exercise.familyId == "pushups" }

        assertNull(
            "an id the app does not ship is 'not classified', not a default family",
            classification.familyOf("no_such_exercise")
        )
        assertNull(
            "an id that merely resembles a known one is still unknown — no parsing of the id text",
            classification.familyOf("${known.id}_extra")
        )
        assertNull("the empty id names no exercise", classification.familyOf(""))
    }

    /**
     * The classification is **deterministic**: repeated reads of the same id, and a second instance
     * built independently, agree. Two passes over a shared mutable source would be the failure this
     * catches, and the second instance is what rules out an answer that depends on construction order.
     */
    @Test
    fun classificationIsDeterministicAcrossReadsAndAcrossInstances() {
        val second = CatalogExerciseFamilyClassification()
        catalogue.forEach { exercise ->
            val first = classification.familyOf(exercise.id)
            repeat(3) {
                assertEquals(
                    "repeated reads of '${exercise.id}' must agree",
                    first,
                    classification.familyOf(exercise.id)
                )
            }
            assertEquals(
                "an independently constructed instance must answer '${exercise.id}' identically",
                first,
                second.familyOf(exercise.id)
            )
        }
    }

    /**
     * **Source fidelity with the generation candidate metadata.** §30 step 10's planner receives each
     * exercise as a [GenerationCandidate], whose `familyId` is the *same stored field*; the adaptive
     * classification must answer exactly what generation was handed, or the two halves of the app would
     * disagree about what family an exercise is in.
     *
     * This is the assertion P30 exists for, and it is why the source is legitimate: it is not a second
     * opinion about families, it is the same fact read by a second consumer. The candidate is built
     * rather than merely read, so the assertion runs through the value generation actually holds.
     */
    @Test
    fun classificationAgreesWithTheGenerationCandidateMetadataForEveryExercise() {
        catalogue.forEach { exercise ->
            val candidate = GenerationCandidate(
                metadata = exercise.toConfigurationMetadata(),
                focuses = setOf(Focus.PUSH),
                dimension = PrescriptionDimension.REP_BASED
            )
            assertEquals(
                "generation is handed family '${candidate.familyId}' for " +
                    "'${candidate.exerciseId}', and the adaptive classification must agree",
                candidate.familyId,
                classification.familyOf(candidate.exerciseId)
            )
        }
    }

    /**
     * **A later catalogue revision cannot retroactively alter historical meaning.** The classification is
     * a compile-time projection, so what an old stored session meant by an exercise's family is fixed by
     * the catalogue entry itself and not re-derived per pass. What is asserted here is the property that
     * makes that true: the answer for an id is the **stored field**, so it is whatever the entry says,
     * with no per-call input that a revision, a date or a Program could vary.
     *
     * A second classification holding a different answer for the same id is exactly what a per-revision or
     * per-Program classification would produce, so the two are held to the same answer.
     */
    @Test
    fun theFamilyMeaningOfAnIdIsTheStoredFieldAndCarriesNoRevisionOrProgramInput() {
        val declarations = CatalogExerciseFamilyClassification::class.java.declaredConstructors
            .first { !it.isSynthetic }
        assertEquals(
            "the classification takes no collaborator at all, so no revision, Program or date can " +
                "vary its answer (found ${declarations.parameterCount} parameters)",
            0,
            declarations.parameterCount
        )

        // The answer is the field, verbatim: an entry whose family is unusual must come back unchanged.
        catalogue.map { exercise -> exercise.id to exercise.familyId }.forEach { (id, familyId) ->
            assertEquals(
                "the answer for '$id' is the stored field and nothing else",
                familyId,
                classification.familyOf(id)
            )
        }

        // An id that is not in the catalogue has no meaning to preserve, in any revision.
        assertNull(classification.familyOf("pushups_v2"))
        assertNotEquals(
            "the catalogue's own id is not the unknown id, so the two are genuinely different facts",
            classification.familyOf("pushups"),
            classification.familyOf("pushups_v2")
        )
    }

    /**
     * **No cross-Program behaviour.** The classification is a pure function of an exercise id: it takes
     * no Program, no revision and no session, so the same exercise means the same family to every
     * Program in the app. Asserted as a property of the port's own signature rather than through a
     * Program, because there is no Program for it to leak.
     */
    @Test
    fun theAnswerDependsOnTheExerciseIdAloneAndCarriesNoProgramScope() {
        val method = ExerciseFamilyClassification::class.java.methods
            .first { it.name == "familyOf" }
        assertEquals(
            "the port answers an exercise id and nothing else",
            1,
            method.parameterCount
        )
        assertEquals("and its answer is a family or its absence", String::class.java, method.returnType)

        val pushups = catalogue.first { exercise -> exercise.familyId == "pushups" }
        val squats = catalogue.first { exercise -> exercise.familyId == "squats" }
        assertNotEquals(
            "two families in the same catalogue are two families to every Program alike",
            classification.familyOf(pushups.id),
            classification.familyOf(squats.id)
        )
    }
}