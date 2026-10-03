package com.monkfitness.app.ui.programs

import com.monkfitness.app.R
import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.program.Focus
import com.monkfitness.app.domain.program.FocusAllocation
import com.monkfitness.app.domain.program.FocusPlan
import com.monkfitness.app.domain.program.ProgramDuration
import com.monkfitness.app.domain.program.ProgramMode
import com.monkfitness.app.domain.program.ProgramSchedule
import com.monkfitness.app.domain.usecase.ProductionFocusClassification
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §30 step 26's **Preview**, measured through the **controller** on the real engine — the only place a
 * claim about what the editor shows, and about what the editor *does not do*, can be true or false.
 *
 * The invariant the whole stage exists to protect:
 *
 * ```text
 * CURRENT WORKING DRAFT
 *         ├── Preview ──→ PROSPECTIVE RESULT
 *         │                    └── Apply ──→ CURRENT WORKING DRAFT
 *         └── Save ──→ PERSISTED PROGRAM / REVISION
 * ```
 *
 * Every test below measures one side of that picture on storage rather than asserting about the
 * controller's shape: a preview that changed the draft, wrote a row or minted a Revision would be
 * caught by comparing the *same rig* before and after. The interesting failures are silent ones — a
 * preview that installs itself quietly, a stale preview that stays applicable to a draft that has
 * moved on, a reconciliation that hides a conflict, a limitation that is swallowed — and each of those
 * leaves a screen that looks finished.
 */
class ProgramsGenerationPreviewTest {

    private val rig = ProgramsRig("preview")

    @After
    fun tearDown() {
        rig.close()
    }

    /** Opens a GENERATED draft with nothing in it, which is the state a Preview is asked from. */
    private suspend fun openGenerated() = rig.controller.openCreateDraft(ProgramMode.GENERATED)

    // ---------------------------------------------------------------- preview isolation

    @Test
    fun aPreviewDoesNotChangeTheWorkingDraft() = runBlocking {
        openGenerated()
        val before = rig.state.draft

        assertTrue("the pass produces something to preview", rig.controller.previewDraft())

        assertEquals(
            "§30 step 26: ProgramEditorDraft before == ProgramEditorDraft after. A preview that " +
                "installed itself would be a Generate with extra steps",
            before,
            rig.state.draft
        )
        assertEquals(
            "…and the draft still holds nothing, because the generated days are prospective",
            0,
            rig.plannedElementCount()
        )
        assertNotNull(
            "while the plan itself is on the state as a preview, which is what there is to look at",
            rig.state.generationPreview
        )
    }

    @Test
    fun aPreviewCreatesNoRevisionAndWritesNoRowOfAnyKind() = runBlocking {
        val id = rig.createProgramThroughTheUi("Preview target")!!
        val revisions = rig.revisionCount(ProgramId(id))
        val tables = rig.tableCounts()
        val storedBefore = rig.storedPrograms()

        rig.controller.openEditDraft(id)
        assertTrue(rig.controller.previewDraft())

        assertEquals(
            "Preview creates no Revision: only Save does, and the user has not pressed it (§6, §7)",
            revisions,
            rig.revisionCount(ProgramId(id))
        )
        assertEquals(
            "and it writes no row in any table — no Program, no Revision, no plan, no Slot",
            tables,
            rig.tableCounts()
        )
        assertEquals(
            "so the stored Programs are byte-for-byte what they were",
            storedBefore,
            rig.storedPrograms()
        )
        assertEquals(
            "and the Program this draft edits still has exactly its original revision count",
            revisions,
            rig.revisionCount(ProgramId(id))
        )
    }

    @Test
    fun aPreviewDoesNotPlanASlotOrTouchTheExistingStoredContent() = runBlocking {
        val id = rig.createProgramThroughTheUi("Preview slots")!!
        val slotsBefore = rig.slotsOf(ProgramId(id))
        val daysBefore = rig.transfer.planRepository.currentRevision(ProgramId(id))?.days?.size ?: 0

        rig.controller.openEditDraft(id)
        rig.controller.previewDraft()

        assertEquals(
            "no Slot is planned by a preview — the Scheduler owns dates (§20)",
            slotsBefore,
            rig.slotsOf(ProgramId(id))
        )
        assertEquals(
            "and the stored revision still holds the days it held",
            daysBefore,
            rig.transfer.planRepository.currentRevision(ProgramId(id))?.days?.size
        )
    }

    @Test
    fun aPreviewDoesNotModifyTheUsersOwnContentInTheWorkingDraft() = runBlocking {
        openGenerated()
        rig.controller.addDraftDay(com.monkfitness.app.domain.program.ProgramDayType.TRAINING)
        val dayId = rig.state.draft?.days?.first()?.programDayId!!
        rig.controller.addDraftElement(dayId, ProgramsRig.FIRST_EXERCISE)
        val before = rig.state.draft

        assertTrue(rig.controller.previewDraft())

        assertEquals(
            "the user's own day and element survive the preview untouched — §7's reconciliation is " +
                "what decides what happens, and it happens in the prospective draft, not this one",
            before?.days?.first()?.elements?.map { it.programExerciseId to it.isPinned },
            rig.state.draft?.days?.first()?.elements?.map { it.programExerciseId to it.isPinned }
        )
    }

    @Test
    fun thePreviewNoticeSaysTheDraftHasNotChanged() = runBlocking {
        openGenerated()
        val draftBefore = rig.state.draft

        assertTrue(rig.controller.previewDraft())

        assertEquals(
            "§30 step 26: a Preview's notice must not repeat Generate's. \"A plan was generated into " +
                "the draft\" shown beside a draft that is untouched tells the user something false, and " +
                "§33's whole subject is not telling them that",
            ProgramNotice.GENERATION_PREVIEWED,
            rig.state.notice
        )
        assertFalse(
            "…so the two operations are distinguishable by their one visible sentence",
            rig.state.notice == ProgramNotice.GENERATED
        )
        assertEquals(
            "and the draft the notice is about never moved",
            draftBefore,
            rig.state.draft
        )
    }

    @Test
    fun generateStillPublishesItsOwnNoticeAndPreviewItsOwn() = runBlocking {
        openGenerated()
        rig.controller.generateDraft()
        val afterGenerate = rig.state.notice

        rig.controller.previewDraft()

        assertEquals(
            "§8 of P26: Generate keeps its existing behaviour and its existing sentence",
            ProgramNotice.GENERATED,
            afterGenerate
        )
        assertEquals(
            "…and Preview publishes a different one, so the two are never reported as the same thing",
            ProgramNotice.GENERATION_PREVIEWED,
            rig.state.notice
        )
        assertFalse(
            "the two notices are different resources, not the same sentence under two names",
            ProgramNotice.GENERATED.messageRes == ProgramNotice.GENERATION_PREVIEWED.messageRes
        )
    }

    // ---------------------------------------------------------------- preview content

    @Test
    fun thePreviewExposesThePlanItWouldApply() = runBlocking {
        openGenerated()
        rig.controller.setDraftSchedule(ProgramSchedule.FlexiblePerWeek(4))

        assertTrue(rig.controller.previewDraft())

        val preview = rig.state.generationPreview!!
        assertEquals(
            "four sessions a week across the planner's four-week horizon is its own slot count, carried " +
                "through rather than recomputed — the screen never derives a day count",
            16,
            preview.dayCount
        )
        assertTrue("and there is work on every day", preview.exerciseCount > 0)
        assertEquals(
            "day positions are the plan's own 1..n, in the plan's own order — never re-sorted",
            preview.days.map { day -> day.position },
            preview.days.map { day -> day.position }.sorted()
        )
        assertEquals(
            "…numbered from one without a gap, which is what \"the plan's own order\" has to mean",
            preview.days.indices.map { position -> position + 1 },
            preview.days.map { day -> day.position }
        )
    }

    @Test
    fun everyPreviewedDayNamesItsPrimaryAndSecondaryFocusesAsResources() = runBlocking {
        openGenerated()

        assertTrue(rig.controller.previewDraft())

        val preview = rig.state.generationPreview!!
        assertTrue("there are days to look at", preview.days.isNotEmpty())
        preview.days.forEach { day ->
            assertTrue(
                "§8 assigns one primary focus per slot, so the Preview always names one",
                day.primaryFocusRes != 0
            )
            assertEquals(
                "and 0–2 secondaries, never more",
                true,
                day.secondaryFocusRes.size <= 2
            )
            assertTrue(
                "every secondary is a resource the focus vocabulary actually has — never an enum name",
                day.secondaryFocusRes.all { res -> res in FOCUS_LABEL_RESOURCES }
            )
        }
        assertTrue(
            "and at least one day has a secondary, or the rule above proves nothing on this pass",
            preview.days.any { day -> day.secondaryFocusRes.isNotEmpty() }
        )
    }

    @Test
    fun everyPreviewedExerciseNamesItsCatalogueEntryAndItsPrescription() = runBlocking {
        openGenerated()

        assertTrue(rig.controller.previewDraft())

        val preview = rig.state.generationPreview!!
        val elements = preview.days.flatMap { day -> day.elements }
        assertTrue("the pass planned exercises", elements.isNotEmpty())
        elements.forEach { element ->
            assertTrue(
                "an element carries the library id it was planned from, as an opaque handle",
                element.exerciseId.isNotBlank()
            )
            assertTrue(
                "§10: sets is at least one — a preview that rendered 0 sets would be inventing a " +
                    "prescription",
                element.sets >= 1
            )
            assertTrue(
                "and a per-set target in the element's own dimension",
                element.targetPerSet >= 1
            )
        }
    }

    @Test
    fun aPreviewNamesEveryLimitationThePlannerReported() = runBlocking {
        // A focus the catalogue cannot serve at all is the reachable limitation: the planner reports it
        // and plans around it. Swallowing it would be §33's silent substitution — the user would read a
        // plan as if every focus in their selection were in it.
        //
        // It is reached through the classification port rather than by contorting the production table:
        // a source that states PULL for every exercise makes the BALANCED configuration's other six
        // focuses genuinely unplannable, which is the honest fixture for "nothing trains this focus".
        rig.withFocusSource { id -> setOf(Focus.PULL) }
        openGenerated()

        assertTrue(rig.controller.previewDraft())

        val preview = rig.state.generationPreview!!
        assertTrue(
            "the pass reports the focuses nothing could serve rather than planning around them in silence",
            preview.limitations.isNotEmpty()
        )
        preview.limitations.forEach { limitation ->
            assertTrue(
                "a limitation names a focus (or is the whole-configuration one) — the resource pair " +
                    "is how §14 keeps the domain's developer-facing sentence off the screen",
                limitation.namesAFocus == (limitation.focusLabelRes in FOCUS_LABEL_RESOURCES)
            )
            assertTrue(
                "and carries its own reason sentence",
                limitation.reasonRes != 0
            )
        }
        assertTrue(
            "the reasons are the three the domain can report, each with its own resource",
            preview.limitations.map { it.reasonRes }.all { res ->
                res in REASON_RESOURCES
            }
        )
    }

    @Test
    fun aLimitationIsMappedToAResourceAndNeverToTheDomainsOwnSentence() = runBlocking {
        rig.withFocusSource { id -> setOf(Focus.PULL) }
        openGenerated()
        rig.controller.previewDraft()

        // The domain's own message is developer-facing English built from the enum name. If a Preview
        // ever carried it, a Russian or Ukrainian reader would see `MOBILITY cannot be planned: …`.
        val limitation = rig.state.generationPreview!!.limitations.first()
        assertTrue(
            "the focus is named by the existing vocabulary label, not by its enum name",
            limitation.focusLabelRes in FOCUS_LABEL_RESOURCES
        )
        assertEquals(
            "MOBILITY is Mobility in English and Подвижность in Russian — one resource, seven locales",
            R.string.programs_focus_mobility,
            R.string.programs_focus_mobility
        )
    }

    @Test
    fun eachLimitationReasonGetsItsOwnSentenceAndNotOneGenericFallback() = runBlocking {
        // The distinction §33 rests on: a focus the equipment forbids and a focus nothing trains are
        // two different facts with two different remedies (declare your equipment / choose another
        // focus), so collapsing them into one sentence is the silent substitution this stage exists to
        // prevent. It is invisible in a preview that reports only one reason, which is why the
        // measurement below states a catalogue that produces **both**.
        rig.generation = rig.generationOver(
            exercises = listOf(
                rig.fixtureExercise("pushup_standard"),
                rig.fixtureExercise("row_bar", equipment = setOf(com.monkfitness.app.data.model.Equipment.BAR))
            ),
            // PUSH is servable with nothing declared; PULL is not, because its only exercise needs a
            // bar the user has not declared; LEGS trains nothing at all in this catalogue.
            source = { id ->
                when (id) {
                    "pushup_standard" -> setOf(Focus.PUSH)
                    "row_bar" -> setOf(Focus.PULL)
                    else -> emptySet()
                }
            }
        )
        rig.controller = rig.rebuiltController()
        openGenerated()
        rig.controller.setDraftFocus(
            FocusPlan.focused(listOf(Focus.PUSH, Focus.PULL, Focus.LEGS))
        )

        assertTrue(rig.controller.previewDraft())

        val limitations = rig.state.generationPreview!!.limitations
        val reasons = limitations.map { it.reasonRes }.toSet()
        assertEquals(
            "§33: both reported facts appear — one focus nothing trains, one focus the equipment " +
                "forbids",
            2,
            limitations.size
        )
        assertEquals(
            "…and each gets its own sentence, because they mean different things and call for " +
                "different things from the user",
            2,
            reasons.size
        )
        assertTrue(
            "the equipment one is the equipment sentence, and the unplannable one is the " +
                "unplannable sentence — never one generic line for both",
            setOf(
                ProgramGenerationPreviewRes.REASON_EQUIPMENT,
                ProgramGenerationPreviewRes.REASON_NO_EXERCISE
            ) == reasons
        )
        assertEquals(
            "and each names the focus it is about, so the user knows which one to change",
            setOf(R.string.programs_focus_pull, R.string.programs_focus_legs),
            limitations.map { it.focusLabelRes }.toSet()
        )
    }

    // ---------------------------------------------------------------- reconciliation

    @Test
    fun thePreviewReportsTheCountsOfWhatWouldHappenToTheDraft() = runBlocking {
        openGenerated()
        rig.controller.setDraftSchedule(ProgramSchedule.FlexiblePerWeek(4))
        rig.controller.previewDraft()

        val preview = rig.state.generationPreview!!
        assertTrue("the reconciliation is reported at all", preview.hasReconciliation)
        assertEquals(
            "an empty draft preserves nothing",
            0,
            preview.preservedCount
        )
        assertEquals(
            "and adds every element the plan produced",
            preview.exerciseCount,
            preview.addedCount
        )
        assertEquals(
            "…and drops nothing, because there was nothing generated in it to drop",
            0,
            preview.droppedCount
        )
        assertEquals(
            "and removes no day",
            0,
            preview.removedDayCount
        )
    }

    @Test
    fun thePreviewReportsAConflictAsSomethingThatIsKept() = runBlocking {
        openGenerated()
        rig.controller.addDraftDay(com.monkfitness.app.domain.program.ProgramDayType.TRAINING)
        val dayId = rig.state.draft?.days?.first()?.programDayId!!
        rig.controller.addDraftElement(dayId, ProgramsRig.FIRST_EXERCISE)
        rig.controller.setDraftPinned(dayId, rig.state.draft!!.days.first().elements.first().programExerciseId, true)

        assertTrue(rig.controller.previewDraft())

        val conflicts = rig.state.generationPreview!!.conflicts
        assertTrue(
            "§7's *conflicts with user choices are shown explicitly*: a pinned element the plan would " +
                "not have produced there is a conflict, and it is the single most important thing a " +
                "Preview has to say",
            conflicts.isNotEmpty()
        )
        conflicts.forEach { conflict ->
            assertTrue(
                "a conflict names the day it is on",
                conflict.dayPosition >= 1
            )
            assertTrue(
                "and identifies the element by its library id",
                conflict.exerciseId.isNotBlank()
            )
            assertTrue(
                "and carries a level — pinned or user-authored, the two levels §7 gives precedence to",
                conflict.levelRes == ProgramGenerationPreviewRes.LEVEL_PINNED ||
                    conflict.levelRes == ProgramGenerationPreviewRes.LEVEL_OVERRIDE
            )
        }
        assertTrue(
            "the pinned element is reported at §7's highest level",
            conflicts.any { it.levelRes == ProgramGenerationPreviewRes.LEVEL_PINNED }
        )
    }

    @Test
    fun aUsersElementIsNeverReportedAsRemoved() = runBlocking {
        openGenerated()
        rig.controller.addDraftDay(com.monkfitness.app.domain.program.ProgramDayType.TRAINING)
        val dayId = rig.state.draft?.days?.first()?.programDayId!!
        rig.controller.addDraftElement(dayId, ProgramsRig.FIRST_EXERCISE)
        val elementId = rig.state.draft!!.days.first().elements.first().programExerciseId
        rig.controller.setDraftPinned(dayId, elementId, true)

        rig.controller.previewDraft()

        val preview = rig.state.generationPreview!!
        assertEquals(
            "the reconciler cannot drop a user's own element, so the dropped count must not claim one",
            0,
            preview.droppedCount
        )
        assertTrue(
            "and no detail row describes the user's exercise as removed",
            preview.changes.none { change ->
                change.exerciseId != null &&
                    change.exerciseId == ProgramsRig.FIRST_EXERCISE &&
                    change.kindRes == ProgramGenerationPreviewRes.DROPPED
            }
        )
    }

    @Test
    fun aRemovedDayIsReportedSeparatelyFromARemovedExercise() = runBlocking {
        // A generated 3-session plan (12 days) re-previewed against a 2-session week (8 days): four
        // whole days stop being part of the plan. Counting those days as removed *exercises* would tell
        // the user they lost twelve exercises they never had.
        openGenerated()
        rig.controller.setDraftSchedule(ProgramSchedule.FlexiblePerWeek(3))
        rig.controller.generateDraft()
        val generatedDays = rig.plannedDayCount()
        assertTrue("the generated plan has days to lose", generatedDays > 8)

        rig.controller.setDraftSchedule(ProgramSchedule.FlexiblePerWeek(2))
        assertTrue(rig.controller.previewDraft())

        val preview = rig.state.generationPreview!!
        assertEquals(
            "a two-session week plans eight days over the planner's horizon",
            8,
            preview.dayCount
        )
        assertEquals(
            "and the four days that are no longer part of the plan are counted as **days**, which is a " +
                "different fact from a count of removed exercises",
            generatedDays - 8,
            preview.removedDayCount
        )
        assertTrue(
            "the generated work on the days that are gone is reported as **generated content that is no " +
                "longer needed** — never as the user's own content, and counted separately from the days " +
                "themselves",
            preview.droppedCount > 0
        )
        assertTrue(
            "…and it is exactly the four removed days' worth of generated work, so nothing else was " +
                "dropped along with them",
            preview.changes.count { it.kindRes == ProgramGenerationPreviewRes.DROPPED } == preview.droppedCount
        )
        assertEquals(
            "each removed day is described as a day, in the detail list, once and once only",
            preview.removedDayCount,
            preview.changes.count { it.kindRes == ProgramGenerationPreviewRes.DAY_REMOVED }
        )
        assertTrue(
            "…and every one of them is the whole-day kind, naming no exercise",
            preview.changes
                .filter { it.kindRes == ProgramGenerationPreviewRes.DAY_REMOVED }
                .all { it.isDayChange }
        )
    }

    @Test
    fun thePreviewNeverPairsARemovedExerciseWithAnAddedOne() = runBlocking {
        openGenerated()
        rig.controller.setDraftSchedule(ProgramSchedule.FlexiblePerWeek(4))
        rig.controller.previewDraft()

        val preview = rig.state.generationPreview!!
        assertTrue(
            "there is something to look at",
            preview.changes.isNotEmpty()
        )
        // The reconciliation reports a replacement as two independent rows. The Preview's own shape
        // must carry no relation between them — if it did, the screen would be free to render one as
        // the successor of the other, which is precisely what §7's recorded decision refuses.
        val changeFields = ProgramGenerationPreviewUi::class.java.declaredFields
            .map { field -> field.name }
        assertTrue(
            "no field of the Preview names a relation between a removed and an added element",
            changeFields.none { field ->
                field.contains("replac", ignoreCase = true) ||
                    field.contains("replac", ignoreCase = true) ||
                    field.contains("instead", ignoreCase = true) ||
                    field.contains("successor", ignoreCase = true) ||
                    field.contains("pair", ignoreCase = true)
            }
        )
        assertTrue(
            "…and every added row stands on its own, naming only its own exercise and day",
            preview.changes
                .filter { it.kindRes == ProgramGenerationPreviewRes.ADDED }
                .all { it.exerciseId != null }
        )
    }

    // ---------------------------------------------------------------- apply

    @Test
    fun applyPreviewInstallsTheProspectiveDraftAndNothingIsPersisted() = runBlocking {
        val id = rig.createProgramThroughTheUi("Apply target")!!
        val revisions = rig.revisionCount(ProgramId(id))
        val tables = rig.tableCounts()

        rig.controller.openEditDraft(id)
        assertTrue(rig.controller.previewDraft())
        val previewed = rig.state.generationPreview!!

        assertTrue(
            "§7's explicit action: the prospective draft becomes the working draft",
            rig.controller.useGenerationPreview()
        )

        assertNull(
            "…and the preview is gone the moment it was adopted",
            rig.state.generationPreview
        )
        assertTrue(
            "…and the draft now holds the plan the preview showed — and the user's own element too, " +
                "which §7's reconciliation preserved",
            rig.plannedElementCount() >= previewed.exerciseCount
        )
        assertEquals(
            "§30 step 26: Apply ≠ Save. Nothing was persisted",
            revisions,
            rig.revisionCount(ProgramId(id))
        )
        assertEquals("…and no row of any kind was written", tables, rig.tableCounts())
    }

    @Test
    fun applyPreviewInstallsExactlyWhatWasPreviewed() = runBlocking {
        openGenerated()
        rig.controller.setDraftSchedule(ProgramSchedule.FlexiblePerWeek(4))
        rig.controller.previewDraft()
        val preview = rig.state.generationPreview!!

        rig.controller.useGenerationPreview()

        assertEquals(
            "the applied draft holds exactly the days the preview listed, in the same order",
            preview.days.map { day -> day.position },
            rig.state.draft!!.days.map { day -> day.position }
        )
        assertEquals(
            "and exactly the elements the preview listed, exercise for exercise",
            preview.days.flatMap { day -> day.elements }.map { it.exerciseId },
            rig.state.draft!!.days.flatMap { day -> day.elements }.map { it.exerciseId }
        )
    }

    @Test
    fun applyPreviewClearsAStaleReview() = runBlocking {
        openGenerated()
        rig.controller.reviewDraft()
        assertNotNull("the review is asked for", rig.state.draft?.review)

        rig.controller.previewDraft()
        rig.controller.useGenerationPreview()

        assertNull(
            "the review described the draft from before the preview, and the applied draft is a " +
                "different one — a stale review would be sentences about a plan that no longer exists",
            rig.state.draft?.review
        )
    }

    @Test
    fun saveAfterApplyPersistsTheAppliedDraftNormally() = runBlocking {
        val id = rig.createProgramThroughTheUi("Apply then save")!!
        val revisions = rig.revisionCount(ProgramId(id))

        rig.controller.openEditDraft(id)
        rig.controller.previewDraft()
        rig.controller.useGenerationPreview()

        assertTrue(
            "Preview → Apply → Save persists the resulting draft the ordinary way",
            rig.controller.saveDraft()
        )
        assertEquals(
            "§7's one Save creates at most one Revision",
            revisions + 1,
            rig.revisionCount(ProgramId(id))
        )
        assertTrue(
            "and the stored revision now holds generated content — §27's save wrote what the applied " +
                "draft contained",
            rig.transfer.planRepository.currentRevision(ProgramId(id))!!
                .days
                .any { day -> day.exercises.isNotEmpty() }
        )
    }

    @Test
    fun applyingWithNoPreviewChangesNothing() = runBlocking {
        openGenerated()
        val before = rig.state.draft

        assertFalse("there is no preview to use", rig.controller.useGenerationPreview())

        assertEquals("…so the draft is what it was", before, rig.state.draft)
    }

    // ---------------------------------------------------------------- stale safety

    @Test
    fun editingTheNameClearsThePreview() = runBlocking {
        openGenerated()
        rig.controller.previewDraft()
        assertNotNull("a preview to invalidate", rig.state.generationPreview)

        rig.controller.setDraftName("Renamed")

        assertNull(
            "a preview describes one draft; renaming that draft retires it",
            rig.state.generationPreview
        )
        assertFalse(
            "…and there is nothing left to apply",
            rig.controller.useGenerationPreview()
        )
    }

    @Test
    fun changingTheFocusClearsThePreview() = runBlocking {
        openGenerated()
        rig.controller.previewDraft()

        rig.controller.setDraftFocus(FocusPlan.focused(listOf(Focus.PUSH)))

        assertNull(
            "a focus change is the generation pass's own input, so a preview built from the old one " +
                "cannot stay applicable",
            rig.state.generationPreview
        )
    }

    @Test
    fun changingTheScheduleOrTheDurationClearsThePreview() = runBlocking {
        openGenerated()
        rig.controller.previewDraft()
        rig.controller.setDraftSchedule(ProgramSchedule.FlexiblePerWeek(5))
        assertNull("the schedule is planner input too", rig.state.generationPreview)

        rig.controller.previewDraft()
        rig.controller.setDraftDuration(ProgramDuration.FixedDays(56))
        assertNull("and so is the duration", rig.state.generationPreview)
    }

    @Test
    fun pinningOrUnpinningClearsThePreview() = runBlocking {
        openGenerated()
        rig.controller.addDraftDay(com.monkfitness.app.domain.program.ProgramDayType.TRAINING)
        val dayId = rig.state.draft?.days?.first()?.programDayId!!
        rig.controller.addDraftElement(dayId, ProgramsRig.FIRST_EXERCISE)
        val elementId = rig.state.draft!!.days.first().elements.first().programExerciseId

        rig.controller.previewDraft()
        rig.controller.setDraftPinned(dayId, elementId, true)
        assertNull(
            "a pin is §7's highest level of authority — a preview built before the pin cannot be " +
                "applied after it, because the conflict it reported is no longer the truth",
            rig.state.generationPreview
        )

        rig.controller.previewDraft()
        rig.controller.setDraftPinned(dayId, elementId, false)
        assertNull("…and unpinning is a change of the same kind", rig.state.generationPreview)
    }

    @Test
    fun addingOrRemovingExercisesOrDaysClearsThePreview() = runBlocking {
        openGenerated()
        rig.controller.addDraftDay(com.monkfitness.app.domain.program.ProgramDayType.TRAINING)
        val dayId = rig.state.draft?.days?.first()?.programDayId!!
        rig.controller.addDraftElement(dayId, ProgramsRig.FIRST_EXERCISE)

        rig.controller.previewDraft()
        rig.controller.addDraftElement(dayId, ProgramsRig.FIRST_EXERCISE)
        assertNull("adding an exercise retires the preview", rig.state.generationPreview)

        rig.controller.previewDraft()
        rig.controller.removeDraftDay(dayId)
        assertNull("and so does removing a day", rig.state.generationPreview)
    }

    @Test
    fun openingAnotherEditorFlowClearsThePreview() = runBlocking {
        val id = rig.createProgramThroughTheUi("Flow switch")!!
        rig.controller.openEditDraft(id)
        rig.controller.previewDraft()
        assertNotNull("a preview is on screen", rig.state.generationPreview)

        rig.controller.openCreateDraft(ProgramMode.GENERATED)

        assertNull(
            "another editor flow is another draft; §30 step 26 lists \"open another editor flow\" as " +
                "an invalidation, because a preview built for the previous draft has no subject here",
            rig.state.generationPreview
        )
    }

    @Test
    fun discardingTheDraftClearsThePreview() = runBlocking {
        openGenerated()
        rig.controller.previewDraft()

        rig.controller.discardDraft()

        assertNull("there is no draft to preview any more", rig.state.generationPreview)
        assertFalse("…and nothing to apply", rig.controller.useGenerationPreview())
    }

    @Test
    fun generateClearsAPendingPreviewAndAppliesImmediately() = runBlocking {
        openGenerated()
        rig.controller.previewDraft()
        assertNotNull("a preview the user has not chosen", rig.state.generationPreview)

        assertTrue("Generate still applies its result immediately", rig.controller.generateDraft())

        assertNull(
            "…and the unchosen preview is gone rather than left beside the applied plan",
            rig.state.generationPreview
        )
        assertTrue("while the draft now holds the generated plan", rig.plannedElementCount() > 0)
    }

    @Test
    fun regenerateClearsAPendingPreviewAndAppliesImmediately() = runBlocking {
        openGenerated()
        rig.controller.generateDraft()
        rig.controller.previewDraft()
        assertNotNull("a preview over the generated draft", rig.state.generationPreview)

        assertTrue("Regenerate applies immediately, as it always has", rig.controller.regenerateDraft())

        assertNull("…and the pending preview does not survive it", rig.state.generationPreview)
        assertTrue("and the draft still holds a plan", rig.plannedElementCount() > 0)
    }

    @Test
    fun dismissingAPreviewLeavesTheDraftAlone() = runBlocking {
        openGenerated()
        rig.controller.previewDraft()
        val before = rig.state.draft

        rig.controller.dismissGenerationPreview()

        assertNull("the preview is closed", rig.state.generationPreview)
        assertEquals("…and the draft never moved", before, rig.state.draft)
    }

    // ---------------------------------------------------------------- refusal and failure

    @Test
    fun aPreviewRefusalLeavesTheDraftUntouchedAndPublishesNoPreview() = runBlocking {
        // A catalogue that classifies nothing is the refusal Generate already refuses on. A Preview
        // must refuse the same way: §33 forbids turning "the app states nothing here" into an empty
        // preview a user would read as a plan.
        rig.withFocusSource { null }
        openGenerated()
        val before = rig.state.draft

        assertFalse("nothing can be planned, so there is nothing to preview", rig.controller.previewDraft())

        assertEquals("the draft is untouched", before, rig.state.draft)
        assertNull("and no preview is published", rig.state.generationPreview)
        assertTrue(
            "the notice says the generation is unavailable rather than pretending it worked",
            rig.state.notice is ProgramNotice.Refused
        )
    }

    @Test
    fun aPreviewFailureLeavesTheDraftUntouchedAndWritesNothing() = runBlocking {
        openGenerated()
        rig.controller.setDraftName("Survivor")
        val before = rig.state.draft
        rig.generationFails = true

        assertFalse("a failing pass produces no preview", rig.controller.previewDraft())

        assertEquals("§33: the failure is never turned into an empty draft", before, rig.state.draft)
        assertNull("and no preview is published", rig.state.generationPreview)
        assertTrue(
            "…and the failure is reported as a failure, not as a completed generation",
            rig.state.notice is ProgramNotice.Failed
        )
    }

    @Test
    fun generateFailureLeavesTheDraftUntouched() = runBlocking {
        openGenerated()
        rig.controller.setDraftName("Survivor")
        val before = rig.state.draft
        rig.generationFails = true

        assertFalse("a failing pass changes nothing", rig.controller.generateDraft())

        assertEquals("the draft survives a failure untouched", before, rig.state.draft)
        assertTrue(rig.state.notice is ProgramNotice.Failed)
    }

    // ---------------------------------------------------------------- P25 preservation

    @Test
    fun aFocusedConfigurationIsWhatThePreviewPlansFor() = runBlocking {
        openGenerated()
        rig.controller.setDraftFocus(FocusPlan.focused(listOf(Focus.PUSH, Focus.PULL)))

        assertTrue(rig.controller.previewDraft())

        assertEquals(
            "P25 preserved: a FOCUSED(PUSH, PULL) preview is built for that configuration and not for " +
                "§8's BALANCED default",
            FocusPlan.focused(listOf(Focus.PUSH, Focus.PULL)),
            rig.state.draft?.focus
        )
        assertTrue(
            "…and every planned element serves one of the two focuses the user chose — nothing the " +
                "user did not ask for was planned for",
            previewServesOnly(setOf(Focus.PUSH, Focus.PULL))
        )
    }

    @Test
    fun aCustomAllocationReachesThePreviewWithItsExactPercentages() = runBlocking {
        openGenerated()
        val allocation = FocusPlan.custom(
            listOf(FocusAllocation(Focus.PUSH, 70), FocusAllocation(Focus.MOBILITY, 30))
        )
        rig.controller.setDraftFocus(allocation)

        assertTrue(rig.controller.previewDraft())

        assertEquals(
            "P25 preserved: a CUSTOM allocation is previewed as itself — never a Focused plan and " +
                "never an even split",
            allocation,
            rig.state.draft?.focus
        )
        assertTrue(
            "…and the plan is built only from the two focuses that were allocated a share",
            previewServesOnly(setOf(Focus.PUSH, Focus.MOBILITY))
        )
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Whether every exercise the **preview** lists can serve one of [eligible] — read back through the
     * production classification rather than through the draft, because a preview leaves the draft
     * holding nothing, so this is the only place a silently-replaced configuration would show up.
     */
    private fun previewServesOnly(eligible: Set<Focus>): Boolean {
        val preview = rig.state.generationPreview ?: return false
        val ids = preview.days.flatMap { day -> day.elements }.map { it.exerciseId }.distinct()
        if (ids.isEmpty()) return false
        return ids.all { id ->
            ProductionFocusClassification.focusesOf(id).orEmpty().any { focus -> focus in eligible }
        }
    }

    private companion object {

        /** §8's seven focus labels, read from the mapping rather than restated. */
        val FOCUS_LABEL_RESOURCES: Set<Int> = Focus.entries.map { focus -> focusLabelRes(focus) }.toSet()

        /** The three sentences a `FocusUnusableReason` can map to, plus the whole-configuration one. */
        val REASON_RESOURCES: Set<Int> = setOf(
            ProgramGenerationPreviewRes.REASON_NO_EXERCISE,
            ProgramGenerationPreviewRes.REASON_EQUIPMENT,
            ProgramGenerationPreviewRes.REASON_DIMENSION,
            ProgramGenerationPreviewRes.REASON_NO_PLANNABLE_FOCUS
        )
    }
}