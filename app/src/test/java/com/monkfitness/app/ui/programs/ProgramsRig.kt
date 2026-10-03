package com.monkfitness.app.ui.programs

import com.monkfitness.app.data.model.Equipment
import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.program.ExercisePreference
import com.monkfitness.app.domain.program.MovableClock
import com.monkfitness.app.domain.program.Program
import com.monkfitness.app.domain.program.Focus
import com.monkfitness.app.domain.program.FocusPlan
import com.monkfitness.app.domain.program.ProgramDayType
import com.monkfitness.app.domain.program.ProgramMode
import com.monkfitness.app.domain.program.ScheduleCadence
import com.monkfitness.app.domain.program.SequentialIds
import com.monkfitness.app.domain.program.WorkoutSlot
import com.monkfitness.app.domain.program.target.TargetProgramDayBinding
import com.monkfitness.app.domain.program.transfer.ProgramTransferFile
import com.monkfitness.app.domain.program.transfer.ProgramTransferFixture
import com.monkfitness.app.domain.usecase.ExerciseGenerationFacts
import com.monkfitness.app.domain.usecase.ProgramEditorService
import com.monkfitness.app.domain.usecase.ProgramGenerationService
import com.monkfitness.app.domain.usecase.ProgramProgressService
import com.monkfitness.app.domain.usecase.ProgramSaveService
import com.monkfitness.app.domain.usecase.ProgramStartService
import com.monkfitness.app.domain.usecase.ProductionGenerationBoundary
import com.monkfitness.app.domain.usecase.ProgramTransferRig
import com.monkfitness.app.domain.usecase.GenerationCatalogue
import com.monkfitness.app.domain.usecase.GenerationSessionHistory
import com.monkfitness.app.domain.usecase.ProgramHistoryGenerationContext
import com.monkfitness.app.domain.usecase.SHIPPED_EXERCISE_CATALOGUE
import com.monkfitness.app.domain.usecase.ProductionFocusClassification
import com.monkfitness.app.domain.usecase.WorkoutGenerator
import com.monkfitness.app.domain.usecase.TargetExistingOccurrenceReader
import com.monkfitness.app.domain.usecase.TargetOccurrenceExecutionReader
import com.monkfitness.app.domain.usecase.TargetScheduleApplicationService
import com.monkfitness.app.domain.usecase.TargetScheduleDefinition
import com.monkfitness.app.domain.usecase.TargetScheduleInputAdapter
import com.monkfitness.app.domain.usecase.TargetScheduleOrchestrator
import com.monkfitness.app.domain.usecase.TargetScheduleProductionConsumer
import com.monkfitness.app.domain.usecase.TargetScheduleSlotPersister
import com.monkfitness.app.domain.usecase.TargetScheduleSource
import com.monkfitness.app.domain.usecase.TargetScheduleSourceBridge

/**
 * §30 step 14's rig: the Program System's application services over the data-access rig's real SQLite
 * engine, with the Program UI's state holder wired over them exactly as `MainViewModel` wires it.
 *
 * It exists so that every claim this stage makes about the UI is measured on storage through the
 * **controller** rather than argued from a screen's shape:
 *
 *  * [controller] is the object under test — the same construction `MainViewModel` performs, with the two
 *    ports the UI supplies (`catalogue`, `shareTarget`) as doubles that record what they were asked;
 *  * [transfer] is §30 step 13's rig, so the import and export halves are the production services over the
 *    production repositories, and the fixture a test imports is the format's own;
 *  * [clock] and [ids] are the two §26 ports, so a test states the moment of an import and reads back every
 *    identity the import minted;
 *  * [shares] records the file the platform boundary was handed, which is how *"Share goes through §11's
 *    path"* is measured instead of asserted.
 */
internal class ProgramsRig(key: String = "ui") {

    /** §30 step 13's rig: the real engine, the production repositories and the transfer services. */
    val transfer = ProgramTransferRig(key)

    val database get() = transfer.database

    /** The clock every fact in this suite is stamped with — movable, so a test states the moment. */
    val clock: MovableClock get() = transfer.clock

    /** Deterministic identity: every id a save mints is readable in a failure message. */
    val ids: SequentialIds get() = transfer.ids

    /** The calendar every date is read in. */
    val zone get() = transfer.zone

    /** §7's editor, wired as the composition root wires it (the same three collaborators, same runner). */
    val editor = ProgramEditorService(
        programRepository = transfer.programRepository,
        planRepository = transfer.planRepository,
        clock = transfer.clock,
        idGenerator = transfer.ids,
        inTransaction = transfer.data.transaction
    )

    /**
     * §27's production Save — the creation unit (Program + first Revision + initial Slots, anchored
     * to the request's date) and the revision-plus-reconciliation pair — wired as `MainViewModel`
     * wires it through the composition root, over the same repositories, the same Scheduler and the
     * same transaction runner.
     */
    val saveService = ProgramSaveService(
        editor = editor,
        programRepository = transfer.programRepository,
        scheduler = transfer.scheduler,
        targetSourceRepository = transfer.targetScheduleSourceRepository,
        clock = transfer.clock,
        zone = transfer.zone,
        inTransaction = transfer.data.transaction
    )

    /** §21's Progress/History layer, read by the Detail screen only. */
    val progress = ProgramProgressService(
        programRepository = transfer.programRepository,
        scheduleRepository = transfer.scheduleRepository,
        sessionRepository = transfer.sessionRepository,
        clock = transfer.clock,
        zone = transfer.zone
    )

    /**
     * §30 step 24's production Generate/Regenerate, wired over the **real** shipped catalogue and the
     * **real** production focus classification, exactly as the composition root wires it.
     *
     * Deliberately not a fixture: the claim this rig exists to measure is that `Generate` runs a real
     * generation pass over the app's own exercises, and a fixture catalogue would make the UI suite
     * prove something about the rig instead. The identity source is the rig's own, so every minted
     * draft handle is readable in a failure message.
     */
    var generation: ProgramGenerationService = generationOver(ProductionFocusClassification)

    /** The service as [source] states the classification, over the real shipped catalogue. */
    private fun generationOver(
        source: ExerciseGenerationFacts.GenerationFocusSource
    ): ProgramGenerationService = ProgramGenerationService(
        catalogue = GenerationCatalogue { focusSource ->
            if (generationFails) {
                throw IllegalStateException("planted fault: the exercise catalogue could not be read")
            }
            SHIPPED_EXERCISE_CATALOGUE.catalogueOf(focusSource)
        },
        focusSource = source,
        ids = com.monkfitness.app.domain.program.DraftIdSource { transfer.ids.newId() },
        context = productionContext()
    )

    /**
     * P27's production context, wired over the rig's **real** session repository.
     *
     * Deliberately not a stated value: the claim the UI suites make about Generate and Preview is a
     * claim about the production graph, and a context source that answered from a fixture would prove
     * something about the rig instead. The read is `sessionsOfProgram` — the one path that assembles a
     * complete `WorkoutSession` — so the store-a-session-then-generate flow is measured end to end.
     */
    private fun productionContext(): ProgramHistoryGenerationContext = ProgramHistoryGenerationContext(
        sessions = GenerationSessionHistory { programId -> transfer.sessionRepository.sessionsOfProgram(programId) }
    )

    /**
     * Re-wires the controller over a different focus classification.
     *
     * The shipped classification classifies every exercise, so a **refusal** is unreachable through
     * it — which is exactly why the refusal tests state a different one rather than contorting the
     * production data to fail. Production wiring is untouched: the default is the real table, and
     * this is the same port a future user-facing Goals & Focus editor would replace.
     */
    /**
     * The catalogue's own bar-or-band exercises — the ones a user who declared no equipment cannot
     * perform. Read from the real catalogue so a test never restates the data.
     */
    fun barOrBandExercises(): Set<String> = WorkoutGenerator().getExerciseLibrary()
        .filter { exercise -> exercise.requiredEquipment.isNotEmpty() }
        .map { it.id }
        .toSet()

    /**
     * Rebuilds [controller] over the **current** [generation].
     *
     * P26 needs to hand the state holder a service that fails (§28's `SYSTEM_FAILURE` class), and a
     * controller takes its collaborators at construction. Rather than a second construction site — which
     * `ProgramsArchitectureTest` pins to exactly two files — the test states the service and asks the
     * rig to rewire, so the graph under test stays the one the composition root builds.
     */
    fun rebuiltController(): ProgramsController = buildController()

    /**
     * The generation service over a **fixture** catalogue rather than the shipped one.
     *
     * §30 step 26 needs a pass that reports *two different* planner limitations in one result —
     * "nothing trains this focus" and "every exercise for it needs equipment you do not have" — and the
     * shipped catalogue cannot produce that pair: its classification covers every exercise, so the only
     * reachable limitation is the equipment one. A fixture states the two facts instead of arguing the
     * production data into failing, which is the same discipline P25's refusal tests used.
     */
    fun generationOver(
        exercises: List<com.monkfitness.app.data.model.Exercise>,
        source: ExerciseGenerationFacts.GenerationFocusSource
    ): ProgramGenerationService = ProgramGenerationService(
        catalogue = GenerationCatalogue { focusSource ->
            ProductionGenerationBoundary.catalogueOf(exercises, focusSource)
        },
        focusSource = source,
        ids = com.monkfitness.app.domain.program.DraftIdSource { transfer.ids.newId() },
        context = productionContext()
    )

    /** A catalogue entry the fixture states, in the shape the generation boundary reads. */
    fun fixtureExercise(
        id: String,
        equipment: Set<Equipment> = emptySet()
    ): com.monkfitness.app.data.model.Exercise = com.monkfitness.app.data.model.Exercise(
        id = id,
        familyId = "family_$id",
        animationId = "anim_$id",
        nameRes = 0,
        descriptionRes = 0,
        techniqueRes = 0,
        imageRes = null,
        sets = 3,
        reps = 10,
        category = com.monkfitness.app.data.model.ExerciseCategory.STRENGTH,
        subCategory = com.monkfitness.app.data.model.ExerciseSubCategory.FULL_BODY,
        requiredEquipment = equipment
    )

    /**
     * When set, the generation pass fails at the catalogue read — §28's `SYSTEM_FAILURE` class, on
     * purpose and without a device.
     *
     * It is a flag on the **catalogue port** rather than a second service handed to the state holder,
     * because the controller takes its collaborators at construction: rebuilding the controller to give
     * it a failing service would throw away the very working draft a failure test is about. The failure
     * therefore arrives through the same seam a real one does.
     */
    var generationFails: Boolean = false

    fun withFocusSource(source: ExerciseGenerationFacts.GenerationFocusSource) {
        generation = generationOver(source)
        controller = buildController()
    }

    /**
     * The equipment the controller states at the moment of a generation pass. Settable, so a test can
     * state "the user owns a bar" and measure what the plan does with it — and it defaults to the
     * **empty** set, which is what `SettingsManager.availableEquipmentFlow` defaults to and what P24
     * reads as *the user declared no equipment* rather than *constrain nothing*.
     */
    var declaredEquipment: Set<Equipment> = emptySet()

    /** Every file the platform boundary was handed, in order. */
    val shares: MutableList<ProgramTransferFile> = mutableListOf()

    /** When set, the platform boundary refuses — the failure path of a share. */
    var shareFails: Boolean = false

    /** When set, the catalogue read fails — the failure path of opening the editor. */
    var catalogueFails: Boolean = false

    /**
     * The exercise choices the plan editor is offered: the ids the fixtures plan with, so a draft built by
     * a test adds exercises the app's own categories would recognise. `nameRes = 0` is the adapter's own
     * "the catalogue has no label for this id" case, which the screens render as the id.
     */
    val catalogueOptions: MutableList<ExerciseOptionUi> = ProgramTransferFixture.KNOWN_EXERCISE_IDS
        .sorted()
        .map { id -> ExerciseOptionUi(exerciseId = id, nameRes = 0, familyId = id, isTimerBased = false) }
        .toMutableList()

    /**
     * §30 step 21's composed Start, wired as the composition root wires it: the real lifecycle
     * service, the real target production consumer over the real adapter / orchestrator / persister,
     * the real pause repository and the rig's own calendar.
     *
     * The UI suites reach target scheduling only through this node, exactly as `MainViewModel` wires
     * it, so a claim such as "a start with no target authoring writes no target row" is measured on
     * storage through the controller rather than argued from the controller's shape.
     */
    val startService = ProgramStartService(
        lifecycle = transfer.lifecycleService,
        consumer = TargetScheduleProductionConsumer(
            programRepository = transfer.programRepository,
            planRepository = transfer.planRepository,
            sourceBridge = TargetScheduleSourceBridge(transfer.targetScheduleSourceRepository),
            occurrenceRepository = transfer.data.targetScheduleOccurrenceRepository,
            existingOccurrenceReader = TargetExistingOccurrenceReader(
                TargetOccurrenceExecutionReader(
                    occurrenceRepository = transfer.data.targetScheduleOccurrenceRepository,
                    scheduleRepository = transfer.scheduleRepository,
                    sessionRepository = transfer.sessionRepository
                )
            ),
            inputAdapter = TargetScheduleInputAdapter(),
            orchestrator = TargetScheduleOrchestrator(
                TargetScheduleApplicationService(
                    TargetScheduleSlotPersister(
                        scheduleRepository = transfer.scheduleRepository,
                        occurrenceRepository = transfer.data.targetScheduleOccurrenceRepository,
                        idGenerator = transfer.ids,
                        inTransaction = transfer.data.transaction
                    )
                )
            )
        ),
        scheduleRepository = transfer.scheduleRepository,
        zone = transfer.zone
    )

    /** The state holder under test, wired as `MainViewModel` wires it. */
    var controller: ProgramsController = buildController()

    /** The one construction of the state holder, so a re-wire above is a single implementation. */
    private fun buildController(): ProgramsController = ProgramsController(
        lifecycle = transfer.lifecycleService,
        starter = startService,
        editor = editor,
        saver = saveService,
        importer = transfer.importService,
        exporter = transfer.exportService,
        progress = progress,
        scheduler = transfer.scheduler,
        generation = generation,
        availableEquipment = { declaredEquipment },
        catalogue = {
            if (catalogueFails) {
                throw IllegalStateException("planted fault: the exercise catalogue could not be read")
            }
            catalogueOptions.toList()
        },
        shareTarget = { file ->
            if (shareFails) {
                throw IllegalStateException("planted fault: the platform refused the share")
            }
            shares += file
        },
        clock = transfer.clock,
        zone = transfer.zone
    )

    /** The state the screens would render, read as a value. */
    val state: ProgramsUiState get() = controller.state.value

    // ---------------------------------------------------------------- what the tests start from

    /** Stores the fixture's non-trivial source Program, which an export and an import both use. */
    suspend fun storeSourceProgram() = transfer.storeSourceProgram()

    /** Creates the built-in Standard Program, so §3's delete fallback has somewhere to fall back to. */
    suspend fun seedStandardProgram() = transfer.seedStandardProgram()

    /** The source Program's identity. */
    val sourceProgramId: ProgramId get() = transfer.sourceProgramId

    /**
     * Creates a Program through the **editor**, as the UI's `Build it myself` path does: open a MANUAL
     * draft, name it, add one training day with one exercise, save.
     *
     * @return the new Program's id, read back from the state the save refreshed.
     */
    suspend fun createProgramThroughTheUi(name: String, exerciseId: String = FIRST_EXERCISE): String? {
        controller.openCreateDraft(ProgramMode.MANUAL)
        controller.setDraftName(name)
        controller.addDraftDay(ProgramDayType.TRAINING)
        val dayId = state.draft?.days?.firstOrNull()?.programDayId
        if (dayId != null) controller.addDraftElement(dayId, exerciseId)
        controller.saveDraft()
        return state.rows.firstOrNull { row -> row.name == name }?.programId
    }

    /** The Programs as stored, read through a fresh repository so nothing is a cache. */
    suspend fun storedPrograms(): List<Program> = transfer.storedPrograms()

    /** A Program as stored, or `null`. */
    suspend fun storedProgram(programId: ProgramId): Program? = transfer.storedProgram(programId)

    /** The selection, read from the state row itself. */
    suspend fun selectedProgramId(): ProgramId? = transfer.selection()?.selectedProgramId

    /**
     * A Program's stored Goals & Focus configuration, read off its **current Revision** through a fresh
     * repository read — so a test proves what Save persisted rather than what the controller still holds.
     */
    suspend fun storedFocus(programId: ProgramId): FocusPlan? =
        transfer.planRepository.currentRevision(programId)?.focus

    /**
     * A Program's stored **exercise preference**, read off its current Revision through a fresh
     * repository — so a test proves what `Save` persisted rather than what the controller still holds.
     */
    suspend fun storedPreference(programId: ProgramId): ExercisePreference? =
        transfer.planRepository.currentRevision(programId)?.preferredExercises

    /**
     * Whether every exercise in the draft the last generation pass produced **can serve** one of
     * [eligible] — the claim the planner's own candidate filter makes, read back through the
     * **production** classification.
     *
     * It is deliberately not "the union of the exercises' focuses equals the user's selection": an
     * exercise states every focus it trains, so a mobility drill selected for MOBILITY may also state
     * CORE and POSTURE. What must hold is that no planned exercise is one that serves *none* of the
     * focuses the user chose — which is what a configuration that was silently replaced would produce.
     */
    suspend fun everyPlannedExerciseServes(eligible: Set<Focus>): Boolean {
        val draft = state.draft ?: return false
        val classification = ProductionFocusClassification
        return draft.days
            .flatMap { day -> day.elements }
            .all { element ->
                val focuses = classification.focusesOf(element.exerciseId).orEmpty()
                focuses.any { focus -> focus in eligible }
            }
    }

    /** How many exercises the last generation pass put into the draft. */
    suspend fun plannedElementCount(): Int =
        state.draft?.days?.sumOf { day -> day.elements.size } ?: 0

    /** How many plan days the last generation pass put into the draft. */
    suspend fun plannedDayCount(): Int = state.draft?.days?.size ?: 0

    /** How many revisions a Program has — the count a rename, select or archive must not change. */
    suspend fun revisionCount(programId: ProgramId): Int = transfer.revisionCount(programId)

    /** A Program's opportunities, read through the schedule repository. */
    suspend fun slotsOf(programId: ProgramId): List<WorkoutSlot> = transfer.slotsOf(programId)

    /**
     * Stores an explicit target authoring against [programId]'s current revision — the one fact
     * §30 step 21's controlled invocation needs before a target pass has anything to plan from.
     *
     * The rule is anchored on the rig's own clock in the rig's own calendar, which is exactly the
     * date `startProgram` will stamp as the factual start, so the authored cadence actually
     * resolves rather than being silently filtered out by its own anchor.
     */
    suspend fun authorTargetSource(programId: ProgramId) {
        val revision = transfer.planRepository.currentRevision(programId)!!
        transfer.targetScheduleSourceRepository.store(
            TargetScheduleSource(
                revisionId = revision.revisionId,
                rules = listOf(
                    TargetScheduleDefinition(
                        ruleId = "rule-strength",
                        workoutId = "workout-strength",
                        cadence = ScheduleCadence.Daily,
                        anchorDate = transfer.clock.now().atZone(transfer.zone).toLocalDate()
                    )
                ),
                programDayBindings = listOf(
                    TargetProgramDayBinding("workout-strength", revision.days.first().programDayId)
                )
            )
        )
    }

    /** The target-owned row counts, read straight off the engine rather than through a cache. */
    fun targetRowCounts(): Pair<Int, Int> = transfer.data.targetRowCounts()

    /** Every table's row count, for the atomicity claims. */
    fun tableCounts(): Map<String, Int> = transfer.tableCounts()

    /** Closes the engine. */
    fun close() = transfer.close()

    companion object {

        /** An id the fixture's document and the catalogue both know. */
        const val FIRST_EXERCISE: String = "pushups"
    }
}
