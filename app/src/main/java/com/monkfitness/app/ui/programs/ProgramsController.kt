package com.monkfitness.app.ui.programs

import com.monkfitness.app.R
import com.monkfitness.app.di.Clock
import com.monkfitness.app.domain.common.ProgramDayId
import com.monkfitness.app.domain.common.ProgramExerciseId
import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.prescription.PrescriptionDimension
import com.monkfitness.app.domain.prescription.RepPrescription
import com.monkfitness.app.domain.prescription.TimePrescription
import com.monkfitness.app.domain.program.ProgramDayType
import com.monkfitness.app.domain.program.ProgramDraftIssue
import com.monkfitness.app.domain.program.ProgramDraftReview
import com.monkfitness.app.domain.program.ProgramEditorDraft
import com.monkfitness.app.domain.program.ProgramEditorRejection
import com.monkfitness.app.domain.program.ProgramEditorResult
import com.monkfitness.app.domain.program.ProgramMode
import com.monkfitness.app.domain.program.Program
import com.monkfitness.app.domain.program.ProgramOperationRefusal
import com.monkfitness.app.domain.program.ProgramOperationResult
import com.monkfitness.app.domain.program.ProgramRow
import com.monkfitness.app.domain.program.ProgramSchedulingResult
import com.monkfitness.app.domain.program.ProgramSaveOutcome
import com.monkfitness.app.domain.program.ProgramSchedule
import com.monkfitness.app.domain.program.ProgramStructureAspect
import com.monkfitness.app.domain.program.ProgramDuration
import com.monkfitness.app.domain.program.transfer.ProgramTransferFile
import com.monkfitness.app.domain.program.transfer.ProgramTransferRejection
import com.monkfitness.app.domain.program.transfer.ProgramTransferResult
import com.monkfitness.app.domain.progress.ProgressScope
import com.monkfitness.app.domain.usecase.ProgramEditorService
import com.monkfitness.app.domain.usecase.ProgramExportService
import com.monkfitness.app.domain.usecase.ProgramImportService
import com.monkfitness.app.domain.usecase.ProgramLifecycleService
import com.monkfitness.app.domain.usecase.ProgramProgressService
import com.monkfitness.app.domain.usecase.ProgramScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.LocalDate
import java.time.ZoneId

/**
 * Where a share goes once the export service has produced the file.
 *
 * A port rather than a call to `ProgramShareSheet`, because the controller is plain Kotlin that runs on
 * a JVM (§12's *"the mechanism is complete and callable"*): production hands it the Android boundary,
 * and a test hands it a recorder — which is how *"the UI invokes the target export path and the
 * platform boundary does the sharing"* is measured rather than asserted about.
 */
fun interface ProgramShareTarget {

    /** Hands [file] to the platform's share mechanism. Throws when the platform refuses. */
    suspend fun share(file: ProgramTransferFile)
}

/** Where the plan editor's exercise choices come from: the app's own catalogue, read through a port. */
fun interface ExerciseCatalogue {

    /** Every exercise the editor may add, in the catalogue's own order. */
    suspend fun options(): List<ExerciseOptionUi>
}

/**
 * The Program screens' state holder — §30 step 14's UI/application layer.
 *
 * ### What it is
 *
 * One plain-Kotlin object that the Compose screens render and drive: it holds one [ProgramsUiState],
 * calls the **application services** for every operation, and turns each typed result into a
 * [ProgramNotice] the user can read (§15, §28). It is deliberately not a `ViewModel` and not a
 * Composable: the project's unit tests run on a JVM with no Robolectric harness, so this is the layer
 * that can be *tested* — the screens above it are checked for the rules they must not break, and this
 * object is checked for the behaviour it must produce.
 *
 * ### What it may not do
 *
 * §16's chain is `Room entity ⇄ mapper ⇄ domain ⇄ use case ⇄ UI state ⇄ Composable`, and the
 * prohibitions of §6 and §13 are all properties of this constructor:
 *
 * ```text
 * no DAO, no Room entity, no AppDatabase      the constructor takes application services, nothing below
 * no repository                               every read and write goes through a service
 * no AppState mutation                        selection moves only through ProgramLifecycleService
 * no revision construction                    only ProgramEditorService and the import pipeline mint one
 * no scheduling                               only ProgramScheduler decides a date
 * no JSON, no Intent, no Uri, no ContentResolver
 * ```
 *
 * A screen that wanted any of those would have to reach around this object, which the architecture
 * suite forbids mechanically.
 *
 * ### Selection is not duplicated here
 *
 * [ProgramsUiState.selectedProgramId] is a *copy of what the lifecycle service read*, refreshed by the
 * read that follows every action. It is not a second source of truth: this object never writes a
 * selection of its own, never assumes one changed, and after every mutating call it re-reads the list
 * through [load] — so a screen that renders it renders the service's answer (§3, §21).
 *
 * @param lifecycle the selection, lifecycle, rename, archive and delete owner (§3, §4, §29).
 * @param editor the draft-first editor and the §6/§27 revision rule.
 * @param importer §5's pipeline and §27's creation unit, including the planned start date the import
 *   review chooses.
 * @param exporter §5's export half. This object never builds the JSON (§11).
 * @param progress §21's measures and history, read for the Detail screen only.
 * @param scheduler §20's timing. It is asked for a *preview* of the next opportunity, never to write.
 * @param catalogue the app's exercise catalogue, for the plan editor's choices.
 * @param shareTarget the platform boundary a share is handed to.
 * @param clock the clock the two defaults of this layer are read from: today, for the import's default
 *   planned start date, and nothing else. No stored fact is stamped here.
 * @param zone the calendar today is read in — the composition root's own value, so the default date
 *   the screen offers and the date the import writes agree about which day it is.
 */
class ProgramsController(
    private val lifecycle: ProgramLifecycleService,
    private val editor: ProgramEditorService,
    private val importer: ProgramImportService,
    private val exporter: ProgramExportService,
    private val progress: ProgramProgressService,
    private val scheduler: ProgramScheduler,
    private val catalogue: ExerciseCatalogue,
    private val shareTarget: ProgramShareTarget,
    private val clock: Clock,
    private val zone: ZoneId
) {

    private val mutableState = MutableStateFlow(ProgramsUiState())

    /** The state the screens render. */
    val state: StateFlow<ProgramsUiState> = mutableState.asStateFlow()

    /**
     * The draft the editor is editing, if any.
     *
     * It is a field rather than part of [ProgramsUiState] because a draft is *edited* — each operation
     * produces the next [`ProgramEditorDraft`] (§7) — while the state carries only its presentation. The
     * presentation is rebuilt on every edit, so the two cannot drift.
     */
    private var workingDraft: ProgramEditorDraft? = null

    // ---------------------------------------------------------------- My Programs (§21)

    /**
     * Reads the saved Programs, the global selection and the next-Program fact.
     *
     * A failure is reported as [ProgramNotice.STORAGE_FAILED] and the previous rows are kept: §33 forbids
     * turning a failure into an empty list, which would read as *"you have no programs"*.
     */
    suspend fun load() {
        mutableState.update { it.copy(loading = true) }
        when (val result = lifecycle.myPrograms()) {
            is ProgramOperationResult.Success -> mutableState.update { current ->
                current.copy(
                    loading = false,
                    rows = result.value.rows.map { row -> row.toUi() },
                    selectedProgramId = result.value.selectedProgramId?.value,
                    hasNextProgram = result.value.hasNextProgram
                )
            }

            is ProgramOperationResult.Refused -> mutableState.update {
                it.copy(loading = false, notice = noticeFor(result.reason))
            }

            is ProgramOperationResult.Failure -> mutableState.update {
                it.copy(loading = false, notice = ProgramNotice.STORAGE_FAILED)
            }
        }
    }

    /** Clears the last notice, once the screen has shown it. */
    fun dismissNotice() {
        mutableState.update { it.copy(notice = null) }
    }

    // ---------------------------------------------------------------- Program Detail (§22)

    /**
     * Opens §22's Detail for [programId]: the Program's own facts, the revision its pointer names, the
     * next opportunity the Scheduler would plan, the calendar counts and the recent attempts.
     *
     * Every field is read from a service that already owns it, and a field no service exposes is *not*
     * shown rather than reconstructed from storage here (§5 of this stage: no business logic is invented
     * to fill a card).
     */
    suspend fun openDetail(programId: String) {
        val id = ProgramId(programId)
        mutableState.update { it.copy(loading = true) }
        val program = valueOf { lifecycle.program(id) } ?: return finishLoading()
        val revision = valueOf { lifecycle.currentRevision(id) } ?: return finishLoading()
        val listed = mutableState.value.rows.firstOrNull { row -> row.programId == programId }
        val row = program.toUi(
            isSelected = programId == mutableState.value.selectedProgramId,
            hasOpenPause = listed?.hasOpenPause == true
        )
        val preview = previewOf(id)
        val calendar = calendarOf(id)
        val history = historyOf(id)

        mutableState.update { current ->
            current.copy(
                loading = false,
                detail = ProgramDetailUi(
                    row = row,
                    mode = revision.mode,
                    duration = revision.duration,
                    schedule = revision.schedule,
                    revisionNumber = revision.revisionNumber,
                    dayCount = revision.days.size,
                    restDayCount = revision.days.count { day -> day.type == ProgramDayType.REST },
                    exerciseCount = revision.days.sumOf { day -> day.exercises.size },
                    nextOpportunity = preview?.nextOpportunity,
                    hasNoFutureDate = preview?.hasNoFutureDate ?: false,
                    completed = calendar?.completed ?: 0,
                    missed = calendar?.missed ?: 0,
                    upcoming = calendar?.upcoming ?: 0,
                    recentWorkouts = history.map { item ->
                        ProgramHistoryRowUi(
                            plannedFor = item.plannedFor,
                            status = item.status,
                            performedSets = item.performedSets,
                            exposedExercises = item.exposedExercises
                        )
                    },
                    hasNextProgram = current.hasNextProgram
                )
            )
        }
    }

    /** Closes the Detail screen. The list is untouched. */
    fun closeDetail() {
        mutableState.update { it.copy(detail = null) }
    }

    // ---------------------------------------------------------------- the Program actions (§4)

    /** §3's *Activate / Select*, through the layer that owns selection. */
    suspend fun select(programId: String) = action(ProgramNotice.SELECTED) {
        lifecycle.selectProgram(ProgramId(programId))
    }

    /** Renames a Program. Not a structural change, so no revision is created (§6). */
    suspend fun rename(programId: String, name: String) = action(ProgramNotice.RENAMED) {
        lifecycle.renameProgram(ProgramId(programId), name = name)
    }

    /** §29's archive: a stamp, refused for the selection until another Program is chosen (§3). */
    suspend fun archive(programId: String) = action(ProgramNotice.ARCHIVED) {
        lifecycle.archiveProgram(ProgramId(programId))
    }

    /** Removes the archive stamp. */
    suspend fun unarchive(programId: String) = action(ProgramNotice.UNARCHIVED) {
        lifecycle.unarchiveProgram(ProgramId(programId))
    }

    /** §29's delete, with §3's Standard-Program fallback and the `IN_PROGRESS` guard left to the owner. */
    suspend fun delete(programId: String) = action(ProgramNotice.DELETED) {
        lifecycle.deleteProgram(ProgramId(programId))
    }

    /** §3's *Start*, recording the factual start date inside the service. */
    suspend fun start(programId: String) = action(ProgramNotice.STARTED) {
        lifecycle.startProgram(ProgramId(programId))
    }

    suspend fun pause(programId: String) = action(ProgramNotice.PAUSED) {
        lifecycle.pauseProgram(ProgramId(programId))
    }

    suspend fun resume(programId: String) = action(ProgramNotice.RESUMED) {
        lifecycle.resumeProgram(ProgramId(programId))
    }

    suspend fun complete(programId: String) = action(ProgramNotice.COMPLETED) {
        lifecycle.completeProgram(ProgramId(programId))
    }

    /** §3's planned start date: a plan, never a start. Creates no revision. */
    suspend fun setPlannedStartDate(programId: String, date: LocalDate?) =
        action(ProgramNotice.RENAMED) {
            lifecycle.setPlannedStartDate(ProgramId(programId), date)
        }

    /**
     * §4's *Share*, through §11's platform path: the export service produces the file and the Android
     * boundary hands it to the Share Sheet. No serialization happens here, and no `file://` is built.
     *
     * A share that the platform refuses is reported — never swallowed into a share the user believes
     * happened.
     */
    suspend fun share(programId: String) {
        when (val result = exporter.export(ProgramId(programId))) {
            is ProgramTransferResult.Success -> try {
                shareTarget.share(result.value)
            } catch (failure: Throwable) {
                mutableState.update { it.copy(notice = ProgramNotice.SHARE_FAILED) }
            }

            is ProgramTransferResult.Rejected ->
                mutableState.update { it.copy(notice = noticeFor(result.rejection)) }

            is ProgramTransferResult.Failed ->
                mutableState.update { it.copy(notice = ProgramNotice.STORAGE_FAILED) }
        }
    }

    // ---------------------------------------------------------------- the editor (§7)

    /**
     * Opens a new draft: §7's two creation entry paths, as the mode the draft is being edited in.
     *
     * ```text
     * Build it myself  → MANUAL
     * Build for me     → GENERATED
     * ```
     *
     * The mode is *content* (§2, §6): it is stored with the draft and becomes the first revision's mode.
     * Nothing is generated here — see [generateDraft] for what the Generated path can and cannot do in
     * this stage.
     */
    suspend fun openCreateDraft(mode: ProgramMode) {
        loadExerciseOptions()
        workingDraft = editor.editor(editor.newDraft()).withMode(mode).draft
        publishDraft(review = null)
    }

    /** Opens the draft that edits [programId]'s current revision. Refused for the Standard Program (§4). */
    suspend fun openEditDraft(programId: String) {
        loadExerciseOptions()
        when (val result = editor.editDraft(ProgramId(programId))) {
            is ProgramEditorResult.Success -> {
                workingDraft = result.value
                publishDraft(review = null)
            }

            is ProgramEditorResult.Rejected ->
                mutableState.update { it.copy(notice = noticeFor(result.rejection)) }

            is ProgramEditorResult.Failed ->
                mutableState.update { it.copy(notice = ProgramNotice.STORAGE_FAILED) }
        }
    }

    /**
     * Opens the draft that copies [programId] into a Program the user will own — §4's copy-before-edit
     * path for the Standard Program, and the general `Copy` action.
     *
     * [copyLabel] is the localized phrase the copy's name is built around; the *name* it is built from is
     * the stored Program's, read here rather than passed in, because a navigation argument carries a
     * stable identifier and not the Program itself (§16).
     */
    suspend fun openCopyDraft(programId: String, copyLabel: String) {
        loadExerciseOptions()
        val program = valueOf { lifecycle.program(ProgramId(programId)) } ?: return
        val name = "$copyLabel ${program.name}".trim()
        when (val result = editor.copyDraft(ProgramId(programId), name)) {
            is ProgramEditorResult.Success -> {
                workingDraft = result.value
                publishDraft(review = null)
            }

            is ProgramEditorResult.Rejected ->
                mutableState.update { it.copy(notice = noticeFor(result.rejection)) }

            is ProgramEditorResult.Failed ->
                mutableState.update { it.copy(notice = ProgramNotice.STORAGE_FAILED) }
        }
    }

    /** Abandons the draft. Nothing that is stored is touched — a draft is not a revision (§6, §7). */
    fun discardDraft() {
        workingDraft = null
        mutableState.update { it.copy(draft = null) }
    }

    /**
     * §7's **Generate** for a Generated draft.
     *
     * The Generated Planner is a domain component that plans from a *library view*: a set of candidates
     * that each state the focuses they train, their family and their prescription dimension
     * (`GenerationRequest`). This app's catalogue carries no such classification — the only grouping it
     * has is a family id and a category, and mapping a category onto `PUSH / PULL / LEGS / CORE / …`
     * would be inventing a training fact the data does not hold. §30 step 12 recorded the same missing
     * artefact on the adaptive side (`NoExerciseFamilyClassification`).
     *
     * So the entry path is offered, the mode is real and saved, and the automatic plan is reported as
     * **not available yet** rather than generated from a guess. What the caller gets is a notice; what
     * the user keeps is the draft they can arrange by hand. Recorded as this stage's one architecture
     * gap in `docs/PROGRAM_UI_NAVIGATION.md`.
     *
     * @return whether a plan was produced. Always `false` in this stage, and never a silent no-op.
     */
    suspend fun generateDraft(): Boolean {
        if (workingDraft == null) return false
        mutableState.update { it.copy(notice = ProgramNotice.GENERATION_UNAVAILABLE) }
        return false
    }

    fun setDraftName(name: String) = editDraft { draft -> editor.editor(draft).renamed(name).draft }

    /** The working mode (§2): changing it is structural, and the editor service records it as content. */
    fun setDraftMode(mode: ProgramMode) = editDraft { draft -> editor.editor(draft).withMode(mode).draft }

    fun setDraftDescription(description: String) =
        editDraft { draft -> editor.editor(draft).described(description).draft }

    fun setDraftDuration(duration: ProgramDuration) =
        editDraft { draft -> editor.editor(draft).withDuration(duration).draft }

    fun setDraftSchedule(schedule: ProgramSchedule) =
        editDraft { draft -> editor.editor(draft).withSchedule(schedule).draft }

    fun addDraftDay(type: ProgramDayType) =
        editDraft { draft -> editor.editor(draft).addingDay(type).draft }

    fun removeDraftDay(programDayId: String) = editDraft { draft ->
        editor.editor(draft).removingDay(ProgramDayId(programDayId)).draft
    }

    fun renameDraftDay(programDayId: String, name: String?) = editDraft { draft ->
        editor.editor(draft).renamingDay(ProgramDayId(programDayId), name).draft
    }

    /**
     * Adds one occurrence of [exerciseId] to a day, prescribed in the catalogue's own dimension: a timed
     * exercise is prescribed in seconds and every other one in repetitions.
     *
     * The element's origin is the editor's own default (`USER_AUTHORED`), because the user is who put it
     * there (§9) — the UI does not decide origins, and has no vocabulary for the other one.
     */
    fun addDraftElement(programDayId: String, exerciseId: String) = editDraft { draft ->
        val option = mutableState.value.exerciseOptions.firstOrNull { it.exerciseId == exerciseId }
        val prescription = if (option?.isTimerBased == true) {
            TimePrescription.uniform(DEFAULT_SETS, DEFAULT_SECONDS_PER_SET)
        } else {
            RepPrescription.uniform(DEFAULT_SETS, DEFAULT_REPS_PER_SET)
        }
        editor.editor(draft)
            .addingExercise(ProgramDayId(programDayId), exerciseId, prescription)
            .draft
    }

    fun removeDraftElement(programDayId: String, programExerciseId: String) = editDraft { draft ->
        editor.editor(draft)
            .removingExercise(ProgramDayId(programDayId), ProgramExerciseId(programExerciseId))
            .draft
    }

    fun duplicateDraftElement(programDayId: String, programExerciseId: String) = editDraft { draft ->
        editor.editor(draft)
            .duplicatingExercise(ProgramDayId(programDayId), ProgramExerciseId(programExerciseId))
            .draft
    }

    /**
     * Sets one element's prescription, in its own dimension: sets and repetitions, or sets and seconds
     * when the element is timed. The dimension is the element's and is not changed here — §10's primary
     * progression dimension is plan content, and a numeric stepper is not the place to re-decide it.
     */
    fun setDraftPrescription(
        programDayId: String,
        programExerciseId: String,
        sets: Int,
        targetPerSet: Int
    ) = editDraft { draft ->
        val element = draft.days
            .firstOrNull { day -> day.programDayId.value == programDayId }
            ?.exercises?.firstOrNull { it.programExerciseId.value == programExerciseId }
        val current = editor.editor(draft)
        val prescription = when (element?.prescription?.dimension) {
            PrescriptionDimension.TIME_BASED ->
                TimePrescription.uniform(sets.coerceAtLeast(1), targetPerSet.coerceAtLeast(1))

            else -> RepPrescription.uniform(sets.coerceAtLeast(1), targetPerSet.coerceAtLeast(1))
        }
        current.settingPrescription(
            ProgramDayId(programDayId),
            ProgramExerciseId(programExerciseId),
            prescription
        ).draft
    }

    /** §7's pin. Unpinning keeps the value; it only makes the element eligible for a later change. */
    fun setDraftPinned(programDayId: String, programExerciseId: String, isPinned: Boolean) =
        editDraft { draft ->
            editor.editor(draft)
                .settingPinned(
                    ProgramDayId(programDayId),
                    ProgramExerciseId(programExerciseId),
                    isPinned
                )
                .draft
        }

    /** §7's **Review**: what saving the draft would do, decided by the editor service. */
    suspend fun reviewDraft() {
        val draft = workingDraft ?: return
        when (val result = editor.review(draft)) {
            is ProgramEditorResult.Success -> publishDraft(review = reviewUi(result.value))

            is ProgramEditorResult.Rejected ->
                mutableState.update { it.copy(notice = noticeFor(result.rejection)) }

            is ProgramEditorResult.Failed ->
                mutableState.update { it.copy(notice = ProgramNotice.STORAGE_FAILED) }
        }
    }

    /**
     * §7's **Save**: at most one new revision, or none (§6), decided by the editor service.
     *
     * @return whether the draft was written — `true` when a Program or a revision was created or the
     *   Program's own facts were saved, `false` when nothing was written. The screen uses it to decide
     *   whether to leave the editor; the *reason* a save wrote nothing is already on the state as the
     *   notice, so a refusal is never mistaken for a completed save (§15).
     */
    suspend fun saveDraft(): Boolean {
        val draft = workingDraft ?: return false
        return when (val result = editor.save(draft)) {
            is ProgramEditorResult.Success -> {
                val notice = saveNotice(result.value)
                workingDraft = null
                mutableState.update { it.copy(draft = null, notice = notice) }
                load()
                result.value !is ProgramSaveOutcome.NothingToChange
            }

            is ProgramEditorResult.Rejected -> {
                mutableState.update { it.copy(notice = noticeFor(result.rejection)) }
                false
            }

            is ProgramEditorResult.Failed -> {
                mutableState.update { it.copy(notice = ProgramNotice.STORAGE_FAILED) }
                false
            }
        }
    }

    // ---------------------------------------------------------------- the import (§5)

    /**
     * §5's review step, over the bytes the platform read — the parse, the version check, the schema, the
     * exerciseId boundary, the semantic validation and the Import Draft all happen in the import service.
     *
     * The date the review offers defaults to **today in the composition root's calendar** and the
     * checkbox defaults to **off**; both are held in the state until [confirmImport].
     */
    suspend fun reviewImport(bytes: ByteArray) {
        when (val result = importer.review(bytes)) {
            is ProgramTransferResult.Success -> {
                val plan = result.value.plan
                mutableState.update { current ->
                    current.copy(
                        importDraft = result.value,
                        importReview = ProgramImportReviewUi(
                            name = plan.name,
                            description = plan.description,
                            dayCount = plan.days.size,
                            exerciseCount = plan.days.sumOf { day -> day.exercises.size },
                            setCount = plan.days.sumOf { day ->
                                day.exercises.sumOf { element -> element.prescription.setCount }
                            },
                            plannedStartDate = today(),
                            makeActive = false
                        )
                    )
                }
            }

            is ProgramTransferResult.Rejected ->
                mutableState.update { it.copy(notice = noticeFor(result.rejection)) }

            is ProgramTransferResult.Failed ->
                mutableState.update { it.copy(notice = ProgramNotice.IMPORT_FAILED) }
        }
    }

    /** The user's chosen planned start date, held until the confirmation. */
    fun setImportStartDate(date: LocalDate) {
        mutableState.update { current ->
            current.copy(importReview = current.importReview?.copy(plannedStartDate = date))
        }
    }

    /**
     * The picked document could not be read: a revoked grant, a provider that is gone, or a stream that
     * failed part-way. Reported as a failure of the flow with another file still possible (§15) — never
     * turned into *"no file was chosen"*, which the user would read as their own mistake.
     */
    fun reportUnreadableFile() {
        mutableState.update { it.copy(notice = ProgramNotice.IMPORT_FAILED) }
    }

    /** §5's checkbox, held until the confirmation. Default OFF. */
    fun setImportMakeActive(makeActive: Boolean) {
        mutableState.update { current ->
            current.copy(importReview = current.importReview?.copy(makeActive = makeActive))
        }
    }

    /** Leaves the import flow. Nothing was written: a review is a read (§5). */
    fun discardImport() {
        mutableState.update { it.copy(importDraft = null, importReview = null) }
    }

    /**
     * §5's confirmation: the reviewed draft, the chosen planned start date and the activation choice are
     * handed to the import service, which writes them as **one transaction** (§27).
     *
     * The date travels with the same call that creates the Program and its slots, so the initial schedule
     * is anchored to it and no second transaction can move it afterwards.
     *
     * @return whether a Program was imported.
     */
    suspend fun confirmImport(): Boolean {
        val draft = mutableState.value.importDraft ?: return false
        val review = mutableState.value.importReview ?: return false
        return when (
            val result = importer.save(
                draft = draft,
                makeActive = review.makeActive,
                plannedStartDate = review.plannedStartDate
            )
        ) {
            is ProgramTransferResult.Success -> {
                mutableState.update {
                    it.copy(importDraft = null, importReview = null, notice = ProgramNotice.IMPORTED)
                }
                load()
                true
            }

            is ProgramTransferResult.Rejected -> {
                mutableState.update { it.copy(notice = noticeFor(result.rejection)) }
                false
            }

            is ProgramTransferResult.Failed -> {
                mutableState.update { it.copy(notice = ProgramNotice.IMPORT_FAILED) }
                false
            }
        }
    }

    // ---------------------------------------------------------------- the mechanism

    /** Runs one Program operation, publishes its outcome, then re-reads what the operation changed. */
    private suspend fun <T> action(
        success: ProgramNotice,
        operation: suspend () -> ProgramOperationResult<T>
    ) {
        val notice = when (val result = operation()) {
            is ProgramOperationResult.Success -> success
            is ProgramOperationResult.Refused -> noticeFor(result.reason)
            is ProgramOperationResult.Failure -> ProgramNotice.STORAGE_FAILED
        }
        mutableState.update { it.copy(notice = notice) }
        if (notice is ProgramNotice.Done) refresh()
    }

    /** Re-reads the list, and the open Detail when one is open, from the services that own them. */
    private suspend fun refresh() {
        load()
        val open = mutableState.value.detail ?: return
        openDetail(open.row.programId)
    }

    /** Reads one Program operation's value, publishing the notice when there is none. */
    private suspend fun <T> valueOf(
        read: suspend () -> ProgramOperationResult<T>
    ): T? = when (val result = read()) {
        is ProgramOperationResult.Success -> result.value
        is ProgramOperationResult.Refused -> {
            mutableState.update { it.copy(notice = noticeFor(result.reason)) }
            null
        }

        is ProgramOperationResult.Failure -> {
            mutableState.update { it.copy(notice = ProgramNotice.STORAGE_FAILED) }
            null
        }
    }

    private fun finishLoading() {
        mutableState.update { it.copy(loading = false) }
    }

    /** The Scheduler's own answer about the next opportunity, or `null` when it has none to give. */
    private suspend fun previewOf(programId: ProgramId): PreviewFacts? =
        when (val result = scheduler.preview(programId)) {
            is ProgramSchedulingResult.Success -> PreviewFacts(
                nextOpportunity = result.value.scheduledDates.minOrNull()
                    ?: result.value.created.minByOrNull { slot -> slot.plannedFor }?.plannedFor,
                hasNoFutureDate = result.value.hasNoFutureDate
            )

            // A revision with no date left, or a Program with nothing to plan from yet, is not an error
            // the detail screen should shout about: it is the honest absence of a next workout (§20).
            is ProgramSchedulingResult.Refused -> null
            is ProgramSchedulingResult.Failure -> null
        }

    private suspend fun calendarOf(programId: ProgramId) =
        try {
            progress.calendarProgress(ProgressScope.OfProgram(programId))
        } catch (failure: Throwable) {
            mutableState.update { it.copy(notice = ProgramNotice.STORAGE_FAILED) }
            null
        }

    private suspend fun historyOf(programId: ProgramId) =
        try {
            progress.history(ProgressScope.OfProgram(programId), RECENT_WORKOUTS)
        } catch (failure: Throwable) {
            mutableState.update { it.copy(notice = ProgramNotice.STORAGE_FAILED) }
            emptyList()
        }

    /**
     * Reads the catalogue the plan editor offers, once per session.
     *
     * A failure is reported (as a `SYSTEM_FAILURE`) and the draft still opens: the user's work is not lost
     * because a picker could not be filled (§15), and the failure is never turned into "this app has no
     * exercises".
     */
    private suspend fun loadExerciseOptions() {
        if (mutableState.value.exerciseOptions.isNotEmpty()) return
        val options = try {
            catalogue.options()
        } catch (failure: Throwable) {
            mutableState.update { it.copy(notice = ProgramNotice.STORAGE_FAILED) }
            emptyList()
        }
        mutableState.update { it.copy(exerciseOptions = options) }
    }

    private fun editDraft(change: (ProgramEditorDraft) -> ProgramEditorDraft) {
        val current = workingDraft ?: return
        workingDraft = change(current)
        // The review describes the draft it was computed from, so an edit clears it (§7's Review step).
        publishDraft(review = null)
    }

    private fun publishDraft(review: ProgramDraftReviewUi?) {
        val draft = workingDraft ?: return
        mutableState.update { it.copy(draft = presentationOf(draft, review)) }
    }

    /** Today, in the composition root's calendar — the default the import review offers. */
    private fun today(): LocalDate = clock.now().atZone(zone).toLocalDate()

    private fun presentationOf(draft: ProgramEditorDraft, review: ProgramDraftReviewUi?): ProgramDraftUi {
        val validation = editor.validate(draft)
        val names = mutableState.value.exerciseOptions.associateBy { option -> option.exerciseId }
        return ProgramDraftUi(
            entry = when {
                !draft.isNewProgram -> ProgramDraftEntry.EDIT
                draft.isBasedOnASavedRevision -> ProgramDraftEntry.COPY
                else -> ProgramDraftEntry.CREATE
            },
            mode = draft.mode,
            name = draft.name,
            description = draft.description,
            duration = draft.duration,
            schedule = draft.schedule,
            days = draft.days.map { day ->
                ProgramDraftDayUi(
                    programDayId = day.programDayId.value,
                    position = day.position,
                    type = day.type,
                    name = day.name,
                    elements = day.exercises.map { element ->
                        ProgramDraftElementUi(
                            programExerciseId = element.programExerciseId.value,
                            exerciseId = element.exerciseId,
                            nameRes = names[element.exerciseId]?.nameRes ?: 0,
                            dimension = element.prescription.dimension,
                            sets = element.prescription.setCount,
                            targetPerSet = element.prescription.perSetTargets.firstOrNull() ?: 0,
                            isPinned = element.isPinned
                        )
                    }
                )
            },
            isValid = validation.isValid,
            issueRes = validation.issues.map { issue -> issueRes(issue) },
            review = review
        )
    }

    private fun reviewUi(review: ProgramDraftReview) = ProgramDraftReviewUi(
        saveKind = review.saveKind,
        willCreateARevision = review.willCreateARevision,
        isSavable = review.isSavable,
        revisionNumber = review.targetRevisionNumber,
        changeRes = review.changes.map { aspect -> aspectRes(aspect) },
        dayCount = review.dayCount,
        restDayCount = review.restDayCount,
        exerciseCount = review.exerciseCount,
        setCount = review.setCount
    )

    private fun saveNotice(outcome: ProgramSaveOutcome): ProgramNotice = when (outcome) {
        is ProgramSaveOutcome.NothingToChange -> ProgramNotice.DRAFT_UNCHANGED
        else -> ProgramNotice.DRAFT_SAVED
    }

    private fun ProgramRow.toUi() = ProgramRowUi(
        programId = programId.value,
        name = name,
        source = source,
        lifecycleStatus = lifecycleStatus,
        isArchived = isArchived,
        hasOpenPause = hasOpenPause,
        isSelected = isSelected,
        isBuiltIn = isBuiltIn,
        plannedStartDate = program.plannedStartDate
    )

    private fun Program.toUi(
        isSelected: Boolean,
        hasOpenPause: Boolean
    ) = ProgramRowUi(
        programId = programId.value,
        name = name,
        source = source,
        lifecycleStatus = lifecycleStatus,
        isArchived = isArchived,
        hasOpenPause = hasOpenPause,
        isSelected = isSelected,
        isBuiltIn = source.isBuiltIn,
        plannedStartDate = plannedStartDate
    )

    /** §28's refusal vocabulary, as the sentence the user reads (§15). */
    private fun noticeFor(refusal: ProgramOperationRefusal): ProgramNotice = when (refusal) {
        ProgramOperationRefusal.StandardProgramCannotBeEdited -> ProgramNotice.STANDARD_CANNOT_BE_EDITED
        ProgramOperationRefusal.StandardProgramCannotBeDeleted -> ProgramNotice.STANDARD_CANNOT_BE_DELETED
        ProgramOperationRefusal.StructuralChangeNeedsARevision -> ProgramNotice.ILLEGAL_TRANSITION
        is ProgramOperationRefusal.HasInProgressSession -> ProgramNotice.ACTIVE_SESSION_BLOCKS_DELETE
        is ProgramOperationRefusal.ArchivingTheSelectionRequiresAnotherSelection ->
            ProgramNotice.ARCHIVE_OF_SELECTION

        is ProgramOperationRefusal.IllegalTransition -> ProgramNotice.ILLEGAL_TRANSITION
        is ProgramOperationRefusal.ProgramNotFound -> ProgramNotice.PROGRAM_NOT_FOUND
    }

    /** The editor's own refusals (§7), as the sentence the user reads. */
    private fun noticeFor(rejection: ProgramEditorRejection): ProgramNotice = when (rejection) {
        is ProgramEditorRejection.Refused -> noticeFor(rejection.rule)
        is ProgramEditorRejection.InvalidDraft -> ProgramNotice.DRAFT_REJECTED
        is ProgramEditorRejection.RevisionNotFound -> ProgramNotice.PROGRAM_NOT_FOUND
    }

    /** §5's import refusals, one case per kind the transfer stage distinguishes (§19 of its document). */
    private fun noticeFor(rejection: ProgramTransferRejection): ProgramNotice = when (rejection) {
        is ProgramTransferRejection.NotAProgramFile -> ProgramNotice.NOT_A_PROGRAM_FILE
        is ProgramTransferRejection.UnsupportedFormatVersion -> ProgramNotice.UNSUPPORTED_FORMAT_VERSION
        is ProgramTransferRejection.SchemaInvalid -> ProgramNotice.SCHEMA_INVALID
        is ProgramTransferRejection.UnknownExercises -> ProgramNotice.UNKNOWN_EXERCISES
        is ProgramTransferRejection.SemanticallyInvalid -> ProgramNotice.SEMANTICALLY_INVALID
        is ProgramTransferRejection.SchedulingRefused -> ProgramNotice.SCHEDULING_REFUSED
        is ProgramTransferRejection.ProgramNotFound -> ProgramNotice.PROGRAM_NOT_FOUND
    }

    /** The domain's draft findings, as the sentences the user reads (§14). */
    private fun issueRes(issue: ProgramDraftIssue): Int = when (issue) {
        ProgramDraftIssue.BlankName -> R.string.programs_issue_name_missing
        ProgramDraftIssue.NoPlan -> R.string.programs_issue_no_plan
        is ProgramDraftIssue.DuplicateDayIdentity -> R.string.programs_issue_duplicate_day
        is ProgramDraftIssue.DaysOutOfOrder -> R.string.programs_issue_days_out_of_order
        is ProgramDraftIssue.DayWithoutWork -> R.string.programs_issue_day_without_work
        is ProgramDraftIssue.ProgramWithoutBaseRevision -> R.string.programs_issue_no_base_revision
    }

    private fun aspectRes(aspect: ProgramStructureAspect): Int = when (aspect) {
        ProgramStructureAspect.MODE -> R.string.programs_change_mode
        ProgramStructureAspect.DURATION -> R.string.programs_change_duration
        ProgramStructureAspect.SCHEDULE -> R.string.programs_change_schedule
        ProgramStructureAspect.FOCUS -> R.string.programs_change_focus
        ProgramStructureAspect.DAYS -> R.string.programs_change_days
        ProgramStructureAspect.EXERCISES -> R.string.programs_change_exercises
        ProgramStructureAspect.PRESCRIPTIONS -> R.string.programs_change_prescriptions
        ProgramStructureAspect.PINNING -> R.string.programs_change_pinning
    }

    /** What the Scheduler's preview told the detail screen: a date, or the fact that there is none. */
    private data class PreviewFacts(val nextOpportunity: LocalDate?, val hasNoFutureDate: Boolean)

    companion object {

        /** How many recent attempts §22's Detail shows. */
        const val RECENT_WORKOUTS: Int = 3

        /** The sets a newly added plan element starts with. */
        const val DEFAULT_SETS: Int = 3

        /** The repetitions a newly added element starts with when the catalogue prescribes repetitions. */
        const val DEFAULT_REPS_PER_SET: Int = 10

        /** The seconds a newly added element starts with when the catalogue prescribes time. */
        const val DEFAULT_SECONDS_PER_SET: Int = 30
    }
}

/**
 * The scheduling refusals are visible in [ProgramsController]'s preview handling: a scheduling refusal
 * is *"no next workout"* rather than a failure the detail screen shouts about, and a scheduling failure
 * is reported like any other.
 */
