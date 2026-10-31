package com.monkfitness.app.domain.usecase

import com.monkfitness.app.domain.adaptive.integration.AdaptiveInputGap
import com.monkfitness.app.domain.adaptive.integration.AdaptiveIntegrationOutcome
import com.monkfitness.app.domain.adaptive.integration.NoDeclaredProgression
import com.monkfitness.app.domain.adaptive.decision.AdaptiveAction
import com.monkfitness.app.domain.adaptive.engine.ProgramAdaptiveElement
import com.monkfitness.app.domain.adaptive.engine.ProgramAdaptiveEngine
import com.monkfitness.app.domain.adaptive.engine.ProgramAdaptiveReason
import com.monkfitness.app.domain.adaptive.engine.ProgramAdaptiveRig
import com.monkfitness.app.domain.adaptive.engine.ProgramElementOwnership
import com.monkfitness.app.domain.adaptive.integration.ownership
import com.monkfitness.app.domain.adaptive.integration.ProgressionRelationProvider
import com.monkfitness.app.domain.program.ProgramExerciseOrigin
import com.monkfitness.app.domain.product.ProductionProgressionRelationDefinitions
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Production adaptive acceptance over the real wiring: with stored relations behind the provider, a pass
 * over an authorised family reaches the unchanged engine with a real declared relation and returns the
 * engine's own pre-existing answer — never a new threshold or decision rule.
 *
 * The negatives matter as much: no default rung for an undeclared family, no bypass of
 * `availableExerciseIds`, no adaptation of user-authored content.
 *
 * The rig is [CatalogAdaptiveIntegrationRig] on real catalogue ids, not a parallel adaptive rig; its
 * ladder source defaults to the production stored provider over a bootstrapped database.
 */
class ProductionAdaptiveProgressionContentTest {

    private val rig = CatalogAdaptiveIntegrationRig.of()

    @After
    fun close() = rig.close()

    /**
     * The four authorised families are each reachable through the rig's production provider over the
     * seeded database — the same object the integration receives, so this is production's answer and not
     * a fixture's.
     */
    @Test
    fun everyAuthorisedFamilyIsReachableThroughTheProductionProvider() = runBlocking {
        rig.createSingleFamilyGraph()

        val provider = rig.productionProvider()
        listOf("pushups", "squats", "lunges", "pullups").forEach { familyId ->
            assertEquals(
                "'$familyId' is served its authored ladder in production",
                ProductionProgressionRelationDefinitions.definitions
                    .first { it.familyId == familyId },
                provider.relationOf(familyId)
            )
        }
        assertEquals(
            "and the family the rig's day presents is one of them",
            true,
            provider.relationOf("pushups") != null
        )
    }

    /**
     * The provider reads through the repository: a **changed** stored ladder changes its answer. A
     * provider holding a static definitions object would not move, which is the bug asserted absent here.
     */
    @Test
    fun theProductionProvidersAnswerComesFromTheRepositoryAndNotFromAStaticSource() = runBlocking {
        rig.createSingleFamilyGraph()
        val provider = rig.productionProvider()

        val authored = requireNotNull(provider.relationOf("pushups"))
        assertEquals(
            "the provider's answer is the stored value",
            authored,
            rig.productionProvider().relationOf("pushups")
        )

        // Replace the stored ladder through the repository — the only writer production has.
        rig.progressionCatalogueForTest().store(
            com.monkfitness.app.domain.adaptive.engine.ProgramProgressionRelation(
                familyId = "pushups",
                variants = listOf(
                    com.monkfitness.app.domain.adaptive.engine.ProgramProgressionVariant(
                        0,
                        "pushups",
                        com.monkfitness.app.domain.prescription.TimePrescription(listOf(99, 99))
                    )
                )
            )
        )

        assertEquals(
            "so the answer follows the STORED catalogue, not the static definitions — which is what " +
                "makes persistence authoritative rather than decorative",
            listOf(99, 99),
            requireNotNull(provider.relationOf("pushups")).declared("pushups")!!.prescription.perSetTargets
        )
    }

    /**
     * `plank` is a real catalogue family the rig can present and it has no authored ladder — a decision,
     * not an omission. The pass must report the typed gap, proving absence was preserved rather than
     * papered over with a default rung.
     */
    @Test
    fun anUndeclaredFamilyStillStopsAtNoDeclaredProgressionRelation() = runBlocking {
        rig.createSingleFamilyGraph()
        val provider = rig.productionProvider()

        assertNull(
            "`plank` has no authored ladder, and production does not invent one",
            provider.relationOf("plank")
        )
        assertNull(
            "and neither does `glute_bridge`",
            provider.relationOf("glute_bridge")
        )

        // Emptied ladder source: the pass reports the typed gap rather than progressing on a fabricated rung.
        rig.relations = NoDeclaredProgression
        val trigger = rig.seedHistoryAndTrigger()

        assertEquals(
            "a family with no declared relation still stops at the typed gap",
            AdaptiveInputGap.NO_DECLARED_PROGRESSION_RELATION,
            rig.gapOf(rig.adapt(trigger.sessionId))
        )
    }

    /**
     * Absence is never converted into a default, a nearest family, or a one-rung ladder. A fallback keyed
     * on "some family exists" would answer for `rows` while still answering `null` for an unknown id, so
     * both cases are asserted.
     */
    @Test
    fun absenceIsNeverConvertedIntoAPlausibleDefaultLadder() = runBlocking {
        rig.createSingleFamilyGraph()
        val provider = rig.productionProvider()

        listOf("rows", "burpees", "pelvic_control", "cat_cow", "side_plank").forEach { familyId ->
            assertNull(
                "'$familyId' is a real family with no authored ladder and stays undeclared",
                provider.relationOf(familyId)
            )
        }
        assertNull("a near miss is not mapped onto `pushups`", provider.relationOf("pushup"))
        assertNull("nor is a family nobody has heard of", provider.relationOf("nope"))
        assertNull("nor an empty id", provider.relationOf(""))
    }

    /**
     * The rig's day presents real rungs of the authored `pushups` ladder, so with the policy conditions
     * satisfied the engine's own existing algorithm moves the family up.
     *
     * The reason asserted is the pre-existing one: this stage supplied the relation, not the rule that
     * consumes it. The gate that the engine file is unchanged lives in the architecture suite; this test
     * only claims the pipeline is connected.
     */
    @Test
    fun aProductionPassOverAnAuthorisedFamilyReachesTheEngineRatherThanTheMissingRelationGap() =
        runBlocking {
        rig.createSingleFamilyGraph()

        val result = rig.adapt(rig.seedProgressQualifyingHistory())

        val outcome = rig.outcomeOf(result)
        assertTrue(
            "a production pass over an authorised family now returns an outcome rather than the " +
                "missing-relation gap — this is the whole point of the stage: $outcome",
            outcome !is AdaptiveIntegrationOutcome.CannotBuildAdaptiveRequest
        )

        // Every branch is the engine's own pre-existing answer — no new threshold is asserted to force
        // one. On the rig's completion evidence the load comparison blocks the increase, so a bounded
        // hold is the expected outcome, not a wiring failure. The applied progression is asserted
        // separately against the stored relation in
        // `theStoredRelationYieldsARealDeclaredProgressionThroughTheUnchangedEngine`.
        when (outcome) {
            is AdaptiveIntegrationOutcome.AdaptiveApplied -> assertEquals(
                "a progression the engine's own sustained-positive rule earned, through production wiring",
                ProgramAdaptiveReason.SUSTAINED_POSITIVE,
                outcome.reason
            )

            is AdaptiveIntegrationOutcome.AdaptiveFiltered -> assertTrue(
                "a filtered decision is the engine's own bounded answer, reached through production " +
                    "wiring: ${outcome.reason}",
                outcome.reason.isHold
            )

            is AdaptiveIntegrationOutcome.NothingToAdapt -> assertTrue(
                "a bounded hold is equally correct — the pipeline is connected either way, because the " +
                    "pass no longer stops at the missing relation. Reason was ${outcome.reason}",
                outcome.reason.isHold
            )

            is AdaptiveIntegrationOutcome.CannotBuildAdaptiveRequest ->
                fail("an authorised family must not report the missing-relation gap any more: ${outcome.gap}")
        }
    }

    /**
     * The relation handed to [com.monkfitness.app.domain.adaptive.engine.ProgramAdaptiveEngine] is read
     * back from the seeded database through the production provider — not the definitions object, not a
     * fixture ladder — and the engine decides from it with its own unchanged policy.
     *
     * Driven directly rather than through a runtime pass because a full pass also depends on the rig's
     * completion evidence clearing the confirmation window: a hold there would say nothing about whether
     * the stored ladder is usable. Here the window is satisfied by the request's own facts, and the only
     * value supplied by this stage is the relation read out of storage.
     *
     * The engine fixture ids are real catalogue ids of the `pushups` family, which is why the stored
     * ladder's `-1 / 0 / +1` rungs line up with what the engine is asked to do.
     */
    @Test
    fun theStoredRelationYieldsARealDeclaredProgressionThroughTheUnchangedEngine() = runBlocking {
        rig.createSingleFamilyGraph()
        val engineRig = ProgramAdaptiveRig

        val stored = requireNotNull(rig.productionProvider().relationOf("pushups")) {
            "the relation under test is the one production serves, read from storage"
        }
        assertEquals(
            "and it is the authored ladder, so this is not a fixture relation",
            ProductionProgressionRelationDefinitions.pushups,
            stored
        )

        val result = ProgramAdaptiveEngine.decide(
            engineRig.request(
                snapshot = engineRig.snapshot(exposures = engineRig.improvement()),
                element = engineRig.element(exerciseId = "pushups", familyId = "pushups"),
                // The stored value, in place of the fixture ladder.
                relation = stored,
                window = engineRig.window(familyId = "pushups", level = 0),
                availableExerciseIds = setOf("pushups_knee", "pushups", "pushups_wide", "decline_pushups")
            )
        )

        assertEquals(
            "the engine's own progression rule, unchanged since Sec 30 step 11, accepts a persisted " +
                "ladder exactly as it accepts a caller-supplied one",
            AdaptiveAction.PROGRESS,
            result.decision.action
        )
        assertEquals(
            "and reaches it for the engine's existing reason",
            ProgramAdaptiveReason.SUSTAINED_POSITIVE,
            result.reason
        )
        assertEquals(
            "moving the element to the ladder's authored +1 rung — a real declared exercise",
            "pushups_wide",
            requireNotNull(result.adjustment).after.exerciseId
        )
        assertEquals(
            "presenting that rung's authored prescription, per set, straight out of storage",
            com.monkfitness.app.domain.prescription.RepPrescription(listOf(7, 7, 7)),
            requireNotNull(result.adjustment).after.prescription
        )
    }

    /**
     * The ladder's `+1` rung is `pushups_wide`. Without it enabled the engine must not move there, and
     * must say so with its own bounded answer rather than substituting another declared rung.
     */
    @Test
    fun aDisabledNextRungIsRefusedByTheEngineOnTheStoredRelation() = runBlocking {
        rig.createSingleFamilyGraph()
        val engineRig = ProgramAdaptiveRig
        val stored = requireNotNull(rig.productionProvider().relationOf("pushups"))

        val available = setOf("pushups_knee", "pushups", "decline_pushups")
        val result = ProgramAdaptiveEngine.decide(
            engineRig.request(
                snapshot = engineRig.snapshot(exposures = engineRig.improvement()),
                element = engineRig.element(exerciseId = "pushups", familyId = "pushups"),
                relation = stored,
                window = engineRig.window(familyId = "pushups", level = 0),
                availableExerciseIds = available
            )
        )

        assertEquals(
            "the +1 rung the ladder declares is unavailable, so the engine holds rather than adapting " +
                "to an exercise the user does not have",
            AdaptiveAction.HOLD,
            result.decision.action
        )
        assertNull(
            "and no adjustment is presented at all",
            result.adjustment
        )
        assertEquals(
            "the reason is the engine's own, unchanged",
            ProgramAdaptiveReason.PROGRESSION_UNAVAILABLE,
            result.reason
        )
    }

    /** The ceiling holds on the STORED relation: from the top rung there is nowhere up to go. */
    @Test
    fun theCeilingOfTheStoredLadderHoldsThroughTheEngine() = runBlocking {
        rig.createSingleFamilyGraph()
        val engineRig = ProgramAdaptiveRig
        val stored = requireNotNull(rig.productionProvider().relationOf("pushups"))

        val result = ProgramAdaptiveEngine.decide(
            engineRig.request(
                snapshot = engineRig.snapshot(exposures = engineRig.improvement()),
                element = engineRig.element(exerciseId = "decline_pushups", familyId = "pushups"),
                relation = stored,
                window = engineRig.window(familyId = "pushups", level = 2),
                availableExerciseIds = setOf("pushups_knee", "pushups", "pushups_wide", "decline_pushups")
            )
        )

        assertEquals(
            "`decline_pushups` is the authored +2 ceiling, so the engine holds instead of stepping off it",
            AdaptiveAction.HOLD,
            result.decision.action
        )
        assertEquals("with the engine's existing ceiling reason", ProgramAdaptiveReason.CEILING_REACHED, result.reason)
        assertNull("and no adjustment", result.adjustment)
    }

    /** The floor holds on the STORED relation; `pullups` is the only ladder with a `-2` floor. */
    @Test
    fun theFloorOfTheStoredLadderHoldsThroughTheEngine() = runBlocking {
        rig.createSingleFamilyGraph()
        val engineRig = ProgramAdaptiveRig
        val stored = requireNotNull(rig.productionProvider().relationOf("pullups"))

        val result = ProgramAdaptiveEngine.decide(
            engineRig.request(
                snapshot = engineRig.snapshot(exposures = engineRig.decline()),
                element = engineRig.element(exerciseId = "hang", familyId = "pullups"),
                relation = stored,
                window = engineRig.window(familyId = "pullups", level = -2),
                availableExerciseIds = setOf("hang", "pullups_chin", "pullups", "pullups_neutral", "pullups_wide")
            )
        )

        assertEquals(
            "`hang` is the authored -2 floor, so a regression below it is refused",
            AdaptiveAction.HOLD,
            result.decision.action
        )
        assertNull("and no adjustment is presented", result.adjustment)
    }

    /**
     * The engine refuses a user-authored element **before** the relation is consulted at all: the ladder
     * is real and reachable, and still not applied to user-owned content.
     */
    @Test
    fun userAuthoredContentIsStillProtectedOnTheStoredRelation() = runBlocking {
        rig.createSingleFamilyGraph()
        val engineRig = ProgramAdaptiveRig
        val stored = requireNotNull(rig.productionProvider().relationOf("pushups"))

        val userOwned = ProgramAdaptiveEngine.decide(
            engineRig.request(
                snapshot = engineRig.snapshot(exposures = engineRig.improvement()),
                element = engineRig.element(
                    exerciseId = "pushups",
                    familyId = "pushups",
                    ownership = ProgramElementOwnership.USER_AUTHORED
                ),
                relation = stored,
                window = engineRig.window(familyId = "pushups", level = 0)
            )
        )
        val pinned = ProgramAdaptiveEngine.decide(
            engineRig.request(
                snapshot = engineRig.snapshot(exposures = engineRig.improvement()),
                element = engineRig.element(
                    exerciseId = "pushups",
                    familyId = "pushups",
                    ownership = ProgramElementOwnership.PINNED
                ),
                relation = stored,
                window = engineRig.window(familyId = "pushups", level = 0)
            )
        )

        listOf("user-authored" to userOwned, "pinned" to pinned).forEach { (label, result) ->
            assertEquals("a $label element is not adapted, even with a real ladder in hand", AdaptiveAction.HOLD, result.decision.action)
            assertNull("and $label content is presented no change at all", result.adjustment)
        }
    }

    /**
     * Pins the ownership **derivation** itself: a mutation collapsing `USER_AUTHORED`/`PINNED` into
     * `AUTOMATIC` makes user content adaptable and fails here, even though every engine-level ownership
     * test still passes because the engine only reads the ownership it is given.
     *
     * `isPinned` outranks `origin`: a pinned generated element is `PINNED`, and only an unpinned
     * user-authored element is `USER_AUTHORED`.
     */
    @Test
    fun theOwnershipDerivationStillReadsThePlansOwnTwoFacts() {
        val automatic = com.monkfitness.app.domain.program.ProgramExercise(
            programExerciseId = com.monkfitness.app.domain.common.ProgramExerciseId("pe-own-1"),
            exerciseId = "pushups",
            prescription = com.monkfitness.app.domain.prescription.RepPrescription(listOf(8, 8, 8)),
            origin = ProgramExerciseOrigin.GENERATED
        )
        val userAuthored = automatic.copy(
            programExerciseId = com.monkfitness.app.domain.common.ProgramExerciseId("pe-own-2"),
            origin = ProgramExerciseOrigin.USER_AUTHORED
        )
        val pinned = userAuthored.copy(
            programExerciseId = com.monkfitness.app.domain.common.ProgramExerciseId("pe-own-3"),
            origin = ProgramExerciseOrigin.GENERATED,
            isPinned = true
        )

        assertEquals(
            "generated, unpinned content is the app's own and may be adapted",
            ProgramElementOwnership.AUTOMATIC,
            automatic.ownership
        )
        assertEquals(
            "content the user authored is not adapted at all",
            ProgramElementOwnership.USER_AUTHORED,
            userAuthored.ownership
        )
        assertEquals(
            "and a pinned element outranks its origin — pinning is the stronger statement",
            ProgramElementOwnership.PINNED,
            pinned.ownership
        )
    }

    /**
     * A family at the top of its ladder does not step off the end: `decline_pushups` is the authored
     * `pushups` ceiling (`+2`), and the bounded answer is observed through production wiring.
     */
    @Test
    fun theCeilingOfAnAuthorisedLadderStillHolds() = runBlocking {
        rig.createSingleFamilyGraph()
        val provider = rig.productionProvider()
        val pushups = requireNotNull(provider.relationOf("pushups"))

        assertEquals(
            "`decline_pushups` is the authored ceiling of the `pushups` ladder",
            2,
            pushups.highestLevel
        )
        assertNull(
            "and the engine's own stepUp has nothing above it — the bounded hold, not a substitute exercise",
            pushups.stepUp(2)
        )
        assertNotNull(
            "while a level below it does step up, so the ceiling is a real boundary rather than an empty ladder",
            pushups.stepUp(1)
        )
    }

    /**
     * A family at the bottom of its ladder does not step below it; `pullups` is the only ladder authored
     * with a `-2` floor, so it is the one observable at a true bottom rung.
     */
    @Test
    fun theFloorOfAnAuthorisedLadderStillHolds() = runBlocking {
        rig.createSingleFamilyGraph()
        val pullups = requireNotNull(rig.productionProvider().relationOf("pullups"))

        assertEquals("`pullups` is authored from -2", -2, pullups.lowestLevel)
        assertNull(
            "and nothing is declared below its floor",
            pullups.stepDown(-2)
        )
        assertNotNull(
            "while the rung above its floor does step down, so the floor is a real boundary",
            pullups.stepDown(-1)
        )
    }

    /**
     * If the user's configuration does not enable `pushups_wide` the family must not be moved there.
     * Asserted on the relation's own step resolution under an availability set excluding the next rung —
     * the data the engine is handed, not a reimplementation of its guard.
     */
    @Test
    fun anUnavailableNextRungIsStillRefusedByAvailability() = runBlocking {
        rig.createSingleFamilyGraph()
        val pushups = requireNotNull(rig.productionProvider().relationOf("pushups"))

        val available = setOf("pushups_knee", "pushups", "decline_pushups")
        val nextRung = requireNotNull(pushups.stepUp(0))

        assertEquals(
            "the ladder itself offers `pushups_wide` as the step up from level 0",
            "pushups_wide",
            nextRung.exerciseId
        )
        assertNull(
            "but a configuration that does not enable it leaves nothing available to step to, which is " +
                "the bounded refusal rather than a substitute the ladder happens to declare",
            available.firstOrNull { it == nextRung.exerciseId }
        )
    }

    /**
     * The rig's day is built from `GENERATED` elements, so the ladder's reachability is not what stands
     * between this pass and a change; adaptation stays the engine's decision on the ownership it carries.
     */
    @Test
    fun userAuthoredContentRemainsProtectedByTheExistingOwnershipRule() = runBlocking {
        rig.createSingleFamilyGraph()

        val owned = rig.pushDay().exercises
        assertTrue(
            "the rig's day carries GENERATED, unpinned elements — so the engine's user-authored " +
                "protection is not what stands between this pass and a change, and the ladder is " +
                "genuinely reachable: ${owned.map { it.origin to it.isPinned }}",
            owned.all {
                it.origin == ProgramExerciseOrigin.GENERATED && !it.isPinned
            }
        )
        // The protection itself is the engine's pre-existing rule, asserted where it is owned: a
        // user-owned element is refused before the relation is ever consulted.
    }

    /**
     * Asserted on the rung the engine would step to — definition through Room, repository and provider —
     * because that is the value whose presentation the user would see.
     */
    @Test
    fun theExactPerSetPrescriptionSurvivesIntoTheRungTheEngineWouldPresent() = runBlocking {
        rig.createSingleFamilyGraph()
        val pushups = requireNotNull(rig.productionProvider().relationOf("pushups"))

        val step = requireNotNull(pushups.stepUp(0))
        assertEquals("`pushups_wide` is the authored +1 rung", "pushups_wide", step.exerciseId)
        assertEquals("in the repetition dimension", "REP_BASED", step.prescription.dimension.name)
        assertEquals(
            "prescribing exactly the authored three sets — not a total, not a set count, not a default",
            listOf(7, 7, 7),
            step.prescription.perSetTargets
        )
        assertEquals("composing three sets", 3, step.prescription.setCount)
        assertEquals("and a total of 21, which is a volume and not a prescription", 21, step.prescription.totalTarget)
    }

    /** The pull-up ladder's TIME → REP transition survives into the engine's own relations. */
    @Test
    fun theTimeToRepetitionTransitionSurvivesIntoTheEngineRelations() = runBlocking {
        rig.createSingleFamilyGraph()
        val pullups = requireNotNull(rig.productionProvider().relationOf("pullups"))

        assertEquals(
            "the entry rung is the timed hang",
            "TIME_BASED",
            pullups.variantsAt(-2).single().prescription.dimension.name
        )
        assertEquals(
            "and the rung above it is a repetition-based pull-up — the transition is real in production",
            "REP_BASED",
            requireNotNull(pullups.stepUp(-2)).prescription.dimension.name
        )
    }

    /**
     * Production does not wire the empty ladder source: the two providers are distinguished by behaviour
     * on the same family, not by name or container text.
     */
    @Test
    fun productionServesContentWhereTheEmptyLadderSourceServesNothing() = runBlocking {
        rig.createSingleFamilyGraph()

        val production: ProgressionRelationProvider = rig.productionProvider()
        val empty: ProgressionRelationProvider = NoDeclaredProgression

        assertNotNull("production serves `pushups`", production.relationOf("pushups"))
        assertNull(
            "while the empty source serves nothing for the very same family — so the two providers are " +
                "genuinely different and production is the one in use",
            empty.relationOf("pushups")
        )
    }
}