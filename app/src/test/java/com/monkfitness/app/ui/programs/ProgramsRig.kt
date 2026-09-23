package com.monkfitness.app.ui.programs

import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.program.MovableClock
import com.monkfitness.app.domain.program.Program
import com.monkfitness.app.domain.program.ProgramDayType
import com.monkfitness.app.domain.program.ProgramMode
import com.monkfitness.app.domain.program.SequentialIds
import com.monkfitness.app.domain.program.WorkoutSlot
import com.monkfitness.app.domain.program.transfer.ProgramTransferFile
import com.monkfitness.app.domain.program.transfer.ProgramTransferFixture
import com.monkfitness.app.domain.usecase.ProgramEditorService
import com.monkfitness.app.domain.usecase.ProgramProgressService
import com.monkfitness.app.domain.usecase.ProgramSaveService
import com.monkfitness.app.domain.usecase.ProgramTransferRig

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

    /** The state holder under test, wired as `MainViewModel` wires it. */
    val controller = ProgramsController(
        lifecycle = transfer.lifecycleService,
        editor = editor,
        saver = saveService,
        importer = transfer.importService,
        exporter = transfer.exportService,
        progress = progress,
        scheduler = transfer.scheduler,
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

    /** How many revisions a Program has — the count a rename, select or archive must not change. */
    suspend fun revisionCount(programId: ProgramId): Int = transfer.revisionCount(programId)

    /** A Program's opportunities, read through the schedule repository. */
    suspend fun slotsOf(programId: ProgramId): List<WorkoutSlot> = transfer.slotsOf(programId)

    /** Every table's row count, for the atomicity claims. */
    fun tableCounts(): Map<String, Int> = transfer.tableCounts()

    /** Closes the engine. */
    fun close() = transfer.close()

    companion object {

        /** An id the fixture's document and the catalogue both know. */
        const val FIRST_EXERCISE: String = "pushups"
    }
}
