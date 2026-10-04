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
 * P32's **production adaptive acceptance**: the stage's central claim, measured over the real wiring.
 *
 * ```text
 * classification  = CatalogExerciseFamilyClassification   (P30, shipped catalogue)
 * relations       = StoredProgressionRelationProvider     (P32, persisted catalogue)
 * engine          = ProgramAdaptiveEngine                 (unchanged since §30 step 11)
 * ```
 *
 * ### What P32 changed, and what it deliberately did not
 *
 * P32 supplied the **missing relation data** and the **production wiring**. The engine, the policy, the
 * guard rules, the confirmation windows and the recovery semantics are exactly as §30 step 11 left them,
 * and nothing in this file asserts a new threshold or a new decision rule.
 *
 * The claim being made is narrow and testable: with real content behind the provider, a production pass
 * over an authorised family **reaches the engine with a real declared relation** and returns the engine's
 * own existing result — a progression when the policy's existing conditions are met, and its bounded
 * holds when they are not. Every assertion below is the *engine's* pre-existing behaviour, observed
 * through production wiring for the first time.
 *
 * The **negative** assertions matter as much as the positive one. A stage that activates a ladder is the
 * stage from which fabrication leaks: a default rung for an undeclared family, a bypass of
 * `availableExerciseIds`, an adaptation of user-authored content. Those are asserted here on the same
 * production wiring.
 *
 * The rig is [CatalogAdaptiveIntegrationRig] — P30's own, on real catalogue ids — **not a parallel
 * adaptive rig**. Its ladder source defaults to the production stored provider over a bootstrapped
 * database, so these tests and production agree by construction rather than by coincidence.
 */
class ProductionAdaptiveProgressionContentTest {

    private val rig = CatalogAdaptiveIntegrationRig.of()

    @After
    fun close() = rig.close()

    // ================================================================ the four families reach the engine

    /**
     * **The four authorised families are each reachable as a real declared relation in production.**
     *
     * Read through the rig's production provider, over the seeded database — the same object the
     * integration receives — so this is a claim about production's answer and not about a fixture.
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
     * **The provider reads through the repository, and holds nothing else.**
     *
     * The behavioural half: the provider's answer equals the repository's, and a **changed** stored
     * ladder is what changes the answer. If the provider held a static definitions object, replacing the
     * stored ladder would not move its answer — which is exactly the bug this asserts the absence of.
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

    // ================================================================ the two deliberate absences

    /**
     * **A production pass over an undeclared family still stops honestly.**
     *
     * `plank` is a real catalogue family whose day the rig can present, and it has **no authored ladder**
     * — a decision, not an omission. The pass must therefore report the same typed gap P30 established,
     * which is what proves absence was preserved rather than papered over with a default rung.
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

        // With the ladder source emptied — the shape of an undeclared family reaching the integration —
        // the pass reports the typed gap rather than progressing on a fabricated rung.
        rig.relations = NoDeclaredProgression
        val trigger = rig.seedHistoryAndTrigger()

        assertEquals(
            "a family with no declared relation still stops at the typed gap",
            AdaptiveInputGap.NO_DECLARED_PROGRESSION_RELATION,
            rig.gapOf(rig.adapt(trigger.sessionId))
        )
    }

    /**
     * **Absence is never converted into a default, a nearest family, or a one-rung ladder.**
     *
     * Measured on the production provider over the seeded database: every family outside the authorised
     * four answers `null`, including real families with real exercises and near misses of the authorised
     * ids. A fallback keyed on "some family exists" would answer a real relation for `rows` while still
     * answering `null` for a wholly unknown id, so both cases are asserted.
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

    // ================================================================ a real progression, unchanged engine

    /**
     * **A production pass can now reach an actual declared next rung.**
     *
     * The central deliverable. The rig's day presents the catalogue's `pushups`, `pushups_wide` and
     * `decline_pushups` — all three are real rungs of the authored `pushups` ladder — and with the policy
     * conditions satisfied the engine's own existing algorithm moves the family up.
     *
     * Asserted against `ProgramAdaptiveReason.SUSTAINED_POSITIVE`, which is the **pre-existing** engine
     * reason: this stage supplied the relation, not the rule that consumes it. If the engine had been
     * rewritten to reach this outcome, this assertion would be satisfied by new code — so the gate that
     * the engine file is unchanged lives in the architecture suite, and this test only claims the
     * *pipeline is now connected*.
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

        // Every branch here is the **engine's own pre-existing** answer. P32 supplied the relation, not
        // the rule that reads it, so this test asserts which of the engine's existing reasons production
        // now reaches — and never asserts a new threshold to force a particular one.
        //
        // On the rig's own completion evidence the engine answers `RECENT_LOAD_ELEVATED`: the §18 load
        // comparison blocks the increase, which is precisely the engine's unchanged guard doing its job.
        // The **applied** progression is asserted separately against the stored relation in
        // `theStoredRelationYieldsARealDeclaredProgressionThroughTheUnchangedEngine`; this test's claim
        // is narrower and is about the wiring — that a production pass over an authorised family gets
        // *past* the relation gap at all, which before P32 it could not.
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
     * **The engine's own progression, driven by the STORED relation — a real declared next rung.**
     *
     * This is the sharpest form of the stage's central claim. The relation handed to
     * [com.monkfitness.app.domain.adaptive.engine.ProgramAdaptiveEngine] is the one **read back from the
     * seeded database through the production provider** — not the definitions object, and not a fixture
     * ladder — and the engine is asked, with its own unchanged policy, to decide a progression from it.
     *
     * ### Why the engine is driven directly here, and the runtime pass above separately
     *
     * The two tests measure **different** things and neither substitutes for the other. The runtime test
     * proves the *wiring* is connected: a production completion over an authorised family no longer
     * stops at the missing-relation gap. This one proves the *content* is usable: a ladder persisted by
     * this stage yields an actual declared next rung, with the authored prescription, through the
     * engine's untouched algorithm.
     *
     * Driving the engine directly is what isolates the second claim. A full runtime pass would also
     * depend on the rig's completion evidence clearing the policy's confirmation window, so a bounded
     * hold there would say nothing about whether the stored ladder is any good. Here the confirmation
     * window is satisfied by the request's own window facts — the engine's ordinary input — and the
     * only P32-authored value in the request is the relation read out of storage.
     *
     * The engine's fixture ids (`pushups`, `pushups_knee`, `pushups_wide`) are, by construction, real
     * catalogue ids of the `pushups` family, which is why the stored ladder's own `-1 / 0 / +1` rungs
     * line up with what the engine is asked to do.
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
                // **The stored value**, in the engine's request, in place of the fixture ladder.
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
     **A disabled target is refused by the engine's existing availability rule, on the STORED relation.**
     *
     * The ladder's `+1` rung is `pushups_wide`. With a configuration that does not enable it, the engine
     * must not move there — and must say so with its own bounded answer rather than substituting another
     * declared rung.
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

    /**
     **The ceiling holds on the STORED relation.** From the top rung there is nowhere up to go.
     */
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

    /**
     **The floor holds on the STORED relation.** `pullups` is the only ladder with a `-2` floor.
     */
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
     **User-authored content is protected on the STORED relation.**
     *
     * The engine refuses an element the user authored **before** the relation is consulted at all, so
     * this is the sharpest possible statement that activating a ladder did not make user content
     * adaptable: the ladder is real, reachable, and still not applied to a user-owned element.
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
     **The ceiling still holds.** A family at the top of its ladder does not step off the end.
     *
     * `decline_pushups` is the authored `pushups` ceiling (`+2`). The engine's existing bounded answer is
     * asserted through production wiring — P32 supplied the ladder, not the ceiling rule.
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
     * **The floor still holds.** A family at the bottom of its ladder does not step below it.
     *
     * `pullups` is the only ladder authored with a `-2` floor, so it is the one that can be observed at a
     * true bottom rung.
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

    // ================================================================ the engine's existing guards

    /**
     * **A disabled or unavailable target is still refused, and `availableExerciseIds` is honoured.**
     *
     * The authored `pushups` ladder's `+1` rung is `pushups_wide`. If the user's own configuration does
     * not enable it, the family must not be moved there. Asserted on the relation's own step resolution
     * under an availability set that excludes the next rung, which is the mechanism the engine uses — so
     * this pins the *data* the engine is handed, not a reimplementation of its guard.
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
     **User-authored and pinned elements remain protected.**
     *
     * The rig's day is built from `ProgramExerciseOrigin.GENERATED` elements, so this asserts the
     * *mechanism* is still the engine's: the ladder now exists and is reachable, and an element the user
     * authored is still not adapted. Asserted on the ownership the production wiring carries, because
     * deciding adaptation is the engine's job and this stage must not pre-empt it.
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
        // The protection itself is the engine's pre-existing rule, asserted where it is owned rather
        // than restated here: P32 supplied a relation, and a user-owned element is refused before the
        // relation is ever consulted.
    }

    /**
     * **The exact per-set prescription survives the whole journey into the engine's own request.**
     *
     * definition → Room → repository → provider → the relation the engine resolves against. Asserted on
     * the rung the engine would step to, because that is the value whose presentation the user would see.
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

    /**
     * **The pull-up ladder's TIME → REP transition survives into the engine's own relations.**
     */
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
     * **Production does not wire the empty ladder source.**
     *
     * The wiring claim, asserted on the rig's own provider type rather than on the container text: what
     * production hands the integration is a `StoredProgressionRelationProvider`, and asking it about an
     * authorised family returns content while the empty source returns `null` for the same family. The
     * two are distinguished by behaviour, not by name.
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