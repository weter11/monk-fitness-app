package com.monkfitness.app.ui.programs

import com.monkfitness.app.R
import com.monkfitness.app.data.model.Equipment
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
import com.monkfitness.app.domain.program.ExercisePreference
import com.monkfitness.app.domain.program.FocusPlan
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
import com.monkfitness.app.domain.program.generated.ChangeKind
import com.monkfitness.app.domain.program.generated.GeneratedDraftEdit
import com.monkfitness.app.domain.program.generated.GenerationLimitation
import com.monkfitness.app.domain.program.generated.PreservationLevel
import com.monkfitness.app.domain.program.transfer.ProgramTransferFile
import com.monkfitness.app.domain.program.transfer.ProgramTransferRejection
import com.monkfitness.app.domain.program.transfer.ProgramTransferResult
import com.monkfitness.app.domain.progress.ProgressScope
import com.monkfitness.app.domain.usecase.ProgramEditorService
import com.monkfitness.app.domain.usecase.ProgramExportService
import com.monkfitness.app.domain.usecase.ProgramGenerationRefusal
import com.monkfitness.app.domain.usecase.ProgramGenerationResult
import com.monkfitness.app.domain.usecase.ProgramGenerationService
import com.monkfitness.app.domain.usecase.ProgramImportService
import com.monkfitness.app.domain.usecase.ProgramLifecycleService
import com.monkfitness.app.domain.usecase.ProgramProgressService
import com.monkfitness.app.domain.usecase.ProgramSaveService
import com.monkfitness.app.domain.usecase.ProgramScheduler
import com.monkfitness.app.domain.usecase.ProgramStartResult
import com.monkfitness.app.domain.usecase.ProgramStartService
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
 * @param lifecycle the selection, lifecycle, rename, archive and delete owner (§3, §4, §29). It is
 *   *not* asked to start a Program: §30 step 21's [starter] is the operation that owns Start, so the
 *   target scheduling policy cannot leak into the lifecycle layer.
 * @param starter §30 step 21's one application-level Start: the lifecycle transition, then — only
 *   when it succeeded — one controlled target scheduling pass with the run context this stage's
 *   policy states. The screen reaches target scheduling only through it.
 * @param editor the draft-first editor: opening drafts, validating them, reviewing them, and the §6
 *   revision rule for an edit's own save.
 * @param saver §27's production Save: the creation unit (Program + first Revision + initial Slots,
 *   anchored to the creation request's date) and the revision-plus-reconciliation pair. This object
 *   hands it the draft and the date the user chose — it never builds a slot, never chooses a date
 *   itself and never runs a scheduling pass.
 * @param importer §5's pipeline and §27's creation unit, including the planned start date the import
 *   review chooses.
 * @param exporter §5's export half. This object never builds the JSON (§11).
 * @param progress §21's measures and history, read for the Detail screen only.
 * @param scheduler §20's timing. It is asked for a *preview* of the next opportunity, never to write.
 * @param generation §30 step 24's one application-level Generate/Regenerate: it reads the working
 *   draft's own configuration, the production catalogue and the classification, builds the request
 *   and runs the generated editor. This object hands it the draft and publishes what comes back; it
 *   builds no request, maps no exercise, holds no focus table and reads no storage, so the
 *   orchestration has exactly one implementation and one place to look for it.
 * @param availableEquipment the equipment the user has, read at the moment of a generation pass and
 *   forwarded verbatim. **An empty set means the user declared no equipment** — the P24 reading of
 *   `SettingsManager.availableEquipmentFlow`, deliberately not the legacy "empty means unconstrained"
 *   rule. This object applies no rule of its own to it; the request's own usability test does.
 * @param catalogue the app's exercise catalogue, for the plan editor's choices.
 * @param shareTarget the platform boundary a share is handed to.
 * @param clock the clock the two defaults of this layer are read from: today, for the import's default
 *   planned start date, and nothing else. No stored fact is stamped here.
 * @param zone the calendar today is read in — the composition root's own value, so the default date
 *   the screen offers and the date the import writes agree about which day it is.
 */
class ProgramsController(
    private val lifecycle: ProgramLifecycleService,
    private val starter: ProgramStartService,
    private val editor: ProgramEditorService,
    private val saver: ProgramSaveService,
    private val importer: ProgramImportService,
    private val exporter: ProgramExportService,
    private val progress: ProgramProgressService,
    private val scheduler: ProgramScheduler,
    private val generation: ProgramGenerationService,
    private val availableEquipment: suspend () -> Set<Equipment>,
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

    /**
     * Which editor flow the [workingDraft] belongs to — the route-derived key its seed opened it with.
     *
     * The screens re-run their seed on every composition of an editor destination, which includes the
     * recreations a rotation or a configuration change causes. Without recording *which flow* the
     * working draft came from, "the screen appeared again" and "the user opened a new editor" would be
     * indistinguishable, and the first of them would silently destroy the user's work — [seedEditor]
     * is what tells them apart: same key with a draft present → keep it; anything else → seed.
     *
     * It does **not** survive process death: `workingDraft` lives in this object, which lives in the
     * view model, which the platform may destroy without a configuration change. Restoring an unsaved
     * draft across a process death needs a persistence/restore seam that does not exist yet; until it
     * does, the guarantee this field supports is scoped to *configuration* recreation, and losing a
     * draft to process death is a recorded gap rather than a silent one (see
     * `docs/PROGRAM_UI_NAVIGATION.md`).
     */
    private var draftSeedKey: String? = null

    /**
     * The prospective result of the last Preview — §30 step 26's *temporary operation result*.
     *
     * ### What it is for, and what it is not
     *
     * ```text
     * CURRENT WORKING DRAFT
     *         │
     *         ├── Preview ──→ PROSPECTIVE RESULT      ← this field, and nothing else
     *         │                    │
     *         │                    └── Apply ──→ CURRENT WORKING DRAFT
     *         │
     *         └── Save ──→ PERSISTED PROGRAM / REVISION
     * ```
     *
     * It holds exactly what the service returned — the prospective draft, the plan and the
     * reconciliation report — because [useGenerationPreview] has to install *that* draft rather than
     * one it rebuilt. It is deliberately **not** a source of truth: there is no second
     * `ProgramEditorDraft` in the UI state, no `FocusPlan` of its own, no schedule, no generated
     * configuration, and nothing here is ever saved, because the only route from the UI layer to
     * storage remains [saveDraft].
     *
     * ### Why it is invalidated rather than compared
     *
     * Every operation that changes the working draft clears this field immediately
     * ([clearGenerationPreview]), and so does opening another editor flow, discarding the draft, and
     * Generate/Regenerate. The alternative — holding a hash, a timestamp or a revision identity of the
     * draft a preview was built from and re-deciding staleness against it — would be a *second
     * identity system* invented for one screen, and every way of getting it wrong (a rebuild that
     * mints a new identity, a rebase that quietly edits the preview, a conflict type with no owner)
     * is worse than the honest answer. Clearing is also the only option that cannot leave a user
     * looking at a plan built for a draft that no longer exists.
     */
    private var pendingPreview: GeneratedDraftEdit? = null

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
                    nextOpportunity = preview.nextOpportunity,
                    hasNoFutureDate = preview.hasNoFutureDate,
                    nextWorkoutUnreadable = preview.unreadable,
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

    /**
     * §29's delete, with §3's Standard-Program fallback and the `IN_PROGRESS` guard left to the owner.
     *
     * It **returns** the outcome it published, because the screen that offers Delete has to know whether
     * the Program is gone before it leaves the Detail (§15): a refusal — an `IN_PROGRESS` session, the
     * built-in Program — and a storage failure must both keep the user where they are, with the sentence
     * that explains it. The rule stays in the lifecycle layer; the screen only branches on the answer.
     */
    suspend fun delete(programId: String): ProgramNotice = action(ProgramNotice.DELETED) {
        lifecycle.deleteProgram(ProgramId(programId))
    }

    /**
     * §3's *Start*, recording the factual start date inside the service — and §30 step 21's one
     * controlled target invocation, run by the application operation this screen is given.
     *
     * The screen asks for **one** thing and is told one answer. It never builds a target window, a
     * composition selection, a resolved-source map, a pause window or an as-of date, and it never
     * decides whether a target refusal is acceptable: the application boundary owns all of that, and
     * this function only maps the operation's own vocabulary onto a notice the user can read.
     *
     * The notice deliberately distinguishes the two halves. "Started" says the Program started and
     * its target schedule was planned; the unavailable notice says the Program started and the
     * target schedule was not, which is a different thing for the user to know and must not be
     * reported as a lifecycle refusal — the Program really is running.
     */
    suspend fun start(programId: String) = startAction {
        starter.start(ProgramId(programId))
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

    /**
     * §3's planned start date: a plan, never a start. Creates no revision.
     *
     * Its notice is its own — the date was saved, and that is not a rename.
     */
    suspend fun setPlannedStartDate(programId: String, date: LocalDate?): ProgramNotice =
        action(ProgramNotice.PLANNED_START_DATE_SET) {
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
     * Runs the editor flow's seed **unless the same flow's working draft is already open**.
     *
     * A screen re-runs its seed every time its destination composes again — a rotation, a density or
     * language-driven configuration change, the screen simply reappearing — and [openCreateDraft] and
     * friends would replace the user's half-edited draft with a fresh empty one each time (P2
     * `UI-03`). This is the guard: the seed key names the flow (`create MANUAL`, `edit <id>`,
     * `copy <id>`), and
     *
     * ```text
     * a draft exists AND the key matches   → the same flow re-appeared: keep the draft, do nothing
     * anything else                        → a genuinely new editor flow (or none open): run the seed
     * ```
     *
     * A *different* flow still replaces the draft — returning from `edit A` into `create` must show
     * `create`, not Program A's plan — and an explicit [discardDraft], [saveDraft] or direct
     * `open…Draft` call clears the key with the draft, so the next seed of any key runs.
     *
     * @param seedKey the route-derived identity of the editor flow, owned by the screen that has the
     *   route; this layer never builds one.
     * @param seed the screen's own call into `openCreateDraft` / `openEditDraft` / `openCopyDraft`.
     */
    suspend fun seedEditor(seedKey: String, seed: suspend () -> Unit) {
        if (workingDraft != null && draftSeedKey == seedKey) return
        seed()
        draftSeedKey = seedKey
    }

    /**
     * Opens a new draft: §7's two creation entry paths, as the mode the draft is being edited in.
     *
     * ```text
     * Build it myself  → MANUAL
     * Build for me     → GENERATED
     * ```
     *
     * The mode is *content* (§2, §6): it is stored with the draft and becomes the first revision's mode.
     * Nothing is generated here — [generateDraft] is the operation that runs a generation pass, and
     * it is the one the `Build for me` entry path leaves to the user to ask for.
     */
    suspend fun openCreateDraft(mode: ProgramMode) {
        beginEditorFlow()
        loadExerciseOptions()
        workingDraft = editor.editor(editor.newDraft()).withMode(mode).draft
        publishDraft(review = null)
    }

    /**
     * Leaving whichever editor flow was open: its draft identity and its creation date are that
     * flow's alone, so a new flow starts with neither. Called by every explicit `open…Draft` — the
     * seed guard above funnels through them for the same reason.
     */
    private fun beginEditorFlow() {
        draftSeedKey = null
        // Another editor flow is another draft: a preview built for the previous one has no subject.
        clearGenerationPreview()
        mutableState.update { it.copy(draftPlannedStartDate = null) }
    }

    /** Opens the draft that edits [programId]'s current revision. Refused for the Standard Program (§4). */
    suspend fun openEditDraft(programId: String) {
        beginEditorFlow()
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
        beginEditorFlow()
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
        draftSeedKey = null
        clearGenerationPreview()
        mutableState.update { it.copy(draft = null, draftPlannedStartDate = null) }
    }

    /**
     * The planned start date the user explicitly chose for a **new** Program, or `null` when they
     * chose none — the creation request's own fact (§3, §6), held beside the draft rather than inside
     * it: the draft is structure, and changing only this must never create a revision. `null` means
     * *no exact choice*, which [ProgramSaveService.save] resolves to today from the injected clock at
     * Save time — not at the moment this was opened.
     */
    fun setDraftPlannedStartDate(date: LocalDate?) {
        mutableState.update { it.copy(draftPlannedStartDate = date) }
    }

    /**
     * §7's **Generate** for a Generated draft — a real generation pass.
     *
     * The Generated Planner plans from a *library view*: candidates that each state the focuses they
     * train, their family and their prescription dimension. P24 closed the gap P23 recorded — the
     * production catalogue now states an explicit classification for every shipped exercise
     * ([com.monkfitness.app.domain.usecase.ProductionFocusClassification]) — so this button runs the
     * whole chain rather than reporting that generation is unavailable:
     *
     * ```text
     * working draft → ProgramGenerationService → GenerationRequest → GeneratedPlanner
     *              → ProgramGeneratedEditor → PlanReconciler → the next working draft
     * ```
     *
     * The controller's own part is deliberately small: it hands the working draft to the service,
     * takes the typed result, and publishes it. It builds no `GenerationRequest`, maps no `Exercise`,
     * reads no DAO, holds no focus table and applies no equipment rule — every one of those belongs
     * to a layer that owns it, and a screen that wanted any of them would have to reach around this
     * object, which the architecture suite forbids mechanically.
     *
     * **Generation alters only the draft.** No revision is created, no Program is written, no slot
     * is planned and no date is chosen: `Save` remains the only route to persistence (§6, §7), and
     * the draft the user already had survives a refusal or a failure untouched. Pinned and
     * user-authored content is preserved by `PlanReconciler`, so pressing Generate does not discard
     * what the user put there.
     *
     * @return whether a new working draft was produced. A refusal and a failure both return `false`
     *   with the reason already on the state as the notice, so neither reads as a completed
     *   generation (§15, §33).
     */
    suspend fun generateDraft(): Boolean {
        val draft = workingDraft ?: return false
        // Generate is the immediate application of a pass; a preview the user never chose must not
        // stay on screen beside a plan that was applied without asking.
        clearGenerationPreview()
        val result = try {
            generation.generate(draft, availableEquipment())
        } catch (failure: Throwable) {
            ProgramGenerationResult.Failed(failure)
        }
        return when (result) {
            is ProgramGenerationResult.Generated -> {
                workingDraft = result.edit.draft
                // The review describes the draft it was computed from, so a generation clears it too.
                publishDraft(review = null)
                mutableState.update { it.copy(notice = ProgramNotice.GENERATED) }
                true
            }

            is ProgramGenerationResult.Refused -> {
                mutableState.update { it.copy(notice = generationNoticeFor(result.reason)) }
                false
            }

            is ProgramGenerationResult.Failed -> {
                mutableState.update { it.copy(notice = ProgramNotice.GENERATION_FAILED) }
                false
            }
        }
    }

    /**
     * §7's **Regenerate** — the same pass, asked for again.
     *
     * It exists beside [generateDraft] because the domain's decision is that the two are one
     * reconciliation (§7's precedence has no exception for which button was pressed), and a caller
     * that has to be told which one it used is a caller that could treat them differently.
     */
    suspend fun regenerateDraft(): Boolean = generateDraft()

    /**
     * §7's **Preview** for a Generated draft — the same generation pass, offered for reading.
     *
     * ```text
     * working draft → ProgramGenerationService.preview → GenerationRequest → GeneratedPlanner
     *              → ProgramGeneratedEditor → PlanReconciler → a PROSPECTIVE draft, held beside the
     *                                                        working one and never installed
     * ```
     *
     * ### The invariant this method exists to protect
     *
     * ```text
     * ProgramEditorDraft before  ==  ProgramEditorDraft after
     * ```
     *
     * Nothing here touches [workingDraft]. The service returns the next immutable value and this
     * object keeps it in [pendingPreview] instead of publishing it as the draft, which is why a Preview
     * creates no Revision, writes no Program row, plans no Slot and modifies no user content: it never
     * reaches [saveDraft], and the only state it publishes is [ProgramsUiState.generationPreview], a
     * presentation that no save path reads.
     *
     * Generate and Regenerate keep their existing meaning — *apply the result now* — and clear any
     * pending preview, so a plan the user has not chosen is never left on screen beside a plan that was
     * applied without asking.
     *
     * A refusal and a failure behave exactly as they do for Generate: the working draft is untouched,
     * no preview is published, and the reason is on the state as the notice. The absence of a plan is
     * never turned into an empty preview, and a fake plan is never invented to fill one.
     *
     * @return whether a prospective result was produced.
     */
    suspend fun previewDraft(): Boolean {
        val draft = workingDraft ?: return false
        val result = try {
            generation.preview(draft, availableEquipment())
        } catch (failure: Throwable) {
            ProgramGenerationResult.Failed(failure)
        }
        return when (result) {
            is ProgramGenerationResult.Generated -> {
                pendingPreview = result.edit
                mutableState.update {
                    it.copy(
                        generationPreview = previewUiOf(result.edit),
                        notice = ProgramNotice.GENERATION_PREVIEWED
                    )
                }
                true
            }

            is ProgramGenerationResult.Refused -> {
                mutableState.update { it.copy(notice = generationNoticeFor(result.reason)) }
                false
            }

            is ProgramGenerationResult.Failed -> {
                mutableState.update { it.copy(notice = ProgramNotice.GENERATION_FAILED) }
                false
            }
        }
    }

    /**
     * §7's explicit **Use this plan**: the one place a prospective result becomes the working draft.
     *
     * ```text
     * pending preview → prospective draft → workingDraft → publishDraft() → pending preview = null
     * ```
     *
     * The draft installed is *exactly* the one the preview pass produced, including the identities the
     * reconciler minted for added days and elements. Rebuilding one here would be a second
     * reconciliation, and reconciling the same plan twice is not idempotent: the second pass would see
     * the first pass's additions as the draft's own content and report a different reconciliation.
     *
     * Nothing is persisted here either — `Preview`, `Apply` and `Save` are three different operations.
     * After this the user is looking at a draft that has been generated but not stored, the preview is
     * gone, the Review is cleared (it described the previous draft), and **Save** is still required.
     *
     * @return whether a preview was applied.
     */
    fun useGenerationPreview(): Boolean {
        val preview = pendingPreview ?: return false
        pendingPreview = null
        workingDraft = preview.draft
        // The review and the preview both describe a draft that is no longer the working one.
        publishDraft(review = null)
        mutableState.update { it.copy(generationPreview = null) }
        return true
    }

    /** §7's generation refusals, as the sentence the user reads. */
    private fun generationNoticeFor(refusal: ProgramGenerationRefusal): ProgramNotice = when (refusal) {
        is ProgramGenerationRefusal.NoExerciseStatesItsFocus ->
            ProgramNotice.GENERATION_UNAVAILABLE

        is ProgramGenerationRefusal.NothingPlannable -> ProgramNotice.GENERATION_REFUSED
    }

    /**
     * Closes a Preview without adopting it.
     *
     * The same [clearGenerationPreview] every draft change uses: the prospective result is dropped,
     * the working draft was never touched, and nothing is persisted. It exists because a user who has
     * read a preview and does not want it needs a way out that is *not* "apply it" — otherwise the
     * only visible ending would be the one that changes their draft.
     */
    fun dismissGenerationPreview() {
        clearGenerationPreview()
    }

    /**
     * One `GeneratedDraftEdit` as the Preview the screen renders — the whole of §30 step 26's
     * application-side mapping, and nothing but a mapping.
     *
     * It reads the plan **in the plan's own order** and copies no counts of its own: every figure comes
     * from [GeneratedPlan] or [ReconciliationReport], so the screen has no arithmetic to do and no way
     * to disagree with the pass that produced the numbers. Exercises are resolved through the same
     * catalogue mapping the draft's own elements use, which is what keeps a localized name in front of
     * a raw exercise id.
     *
     * A `DROPPED` change is reported as a dropped *element*, and a `DAY_REMOVED` change as a removed
     * *day*, in the reconciliation's own vocabulary. No dropped element is paired with an added one:
     * [ReconciliationReport] reports a replacement as two halves precisely because the planner states
     * no relation between them, and inventing one here would assert something the domain refused to.
     */
    private fun previewUiOf(edit: GeneratedDraftEdit): ProgramGenerationPreviewUi {
        val names = mutableState.value.exerciseOptions.associateBy { option -> option.exerciseId }
        fun labelOf(exerciseId: String?): Int = exerciseId?.let { id -> names[id]?.nameRes } ?: 0

        return ProgramGenerationPreviewUi(
            days = edit.plan.slots.map { slot ->
                ProgramGenerationPreviewDayUi(
                    position = slot.position,
                    primaryFocusRes = focusLabelRes(slot.assignment.primary),
                    secondaryFocusRes = slot.assignment.secondary.map { focus ->
                        focusLabelRes(focus)
                    },
                    elements = slot.elements.map { element ->
                        ProgramGenerationPreviewElementUi(
                            exerciseId = element.exerciseId,
                            nameRes = labelOf(element.exerciseId),
                            dimension = element.prescription.dimension,
                            sets = element.prescription.setCount,
                            // The whole per-set sequence, in the prescription's own order: §10's
                            // shapes are unequal (12/10/8/6, 30/30/45) and flattening them to the
                            // first term would understate what the plan actually prescribes.
                            targetsPerSet = element.prescription.perSetTargets.toList()
                        )
                    }
                )
            },
            // §33: a limitation is *reported*, never worked around, so none is dropped here — the
            // domain's own sentence is developer-facing English and is replaced by a resource pair.
            limitations = edit.plan.limitations.map { limitation ->
                when (limitation) {
                    is GenerationLimitation.UnusableFocus -> ProgramGenerationLimitationUi(
                        focusLabelRes = focusLabelRes(limitation.focus),
                        reasonRes = focusUnusableReasonRes(limitation.reason)
                    )

                    GenerationLimitation.NoPlannableFocus -> ProgramGenerationLimitationUi(
                        focusLabelRes = 0,
                        reasonRes = ProgramGenerationPreviewRes.REASON_NO_PLANNABLE_FOCUS
                    )
                }
            },
            preservedCount = edit.reconciliation.preservedCount,
            addedCount = edit.reconciliation.addedCount,
            // A user's own element is never counted as dropped: the reconciler cannot drop one, and
            // reporting it as removed would tell the user their own work is gone.
            droppedCount = edit.reconciliation.droppedCount,
            removedDayCount = edit.reconciliation.removedDayCount,
            conflicts = edit.reconciliation.conflicts.map { change ->
                ProgramGenerationPreviewConflictUi(
                    levelRes = when (change.level) {
                        PreservationLevel.PINNED -> ProgramGenerationPreviewRes.LEVEL_PINNED
                        PreservationLevel.USER_OVERRIDE ->
                            ProgramGenerationPreviewRes.LEVEL_OVERRIDE

                        PreservationLevel.COMPATIBLE, PreservationLevel.GENERATED ->
                            ProgramGenerationPreviewRes.LEVEL_OVERRIDE
                    },
                    dayPosition = change.dayPosition,
                    exerciseId = change.exerciseId.orEmpty(),
                    nameRes = labelOf(change.exerciseId)
                )
            },
            changes = edit.reconciliation.changes.map { change ->
                ProgramGenerationPreviewChangeUi(
                    kindRes = when (change.kind) {
                        ChangeKind.PRESERVED -> ProgramGenerationPreviewRes.PRESERVED
                        ChangeKind.ADDED -> ProgramGenerationPreviewRes.ADDED
                        ChangeKind.DROPPED -> ProgramGenerationPreviewRes.DROPPED
                        ChangeKind.DAY_REMOVED -> ProgramGenerationPreviewRes.DAY_REMOVED
                    },
                    dayPosition = change.dayPosition,
                    exerciseId = change.exerciseId,
                    nameRes = labelOf(change.exerciseId)
                )
            }
        )
    }

    /**
     * Drops the pending preview and everything published from it.
     *
     * Called from every path that changes the working draft, from opening another editor flow, from
     * discarding the draft, from Generate/Regenerate, and from [useGenerationPreview] once the
     * prospective draft has been installed. That list is the whole of §30 step 26's invalidation rule,
     * and keeping it in one function is what makes it checkable: a new draft-mutating operation that
     * forgets it is a preview left applicable to a draft that no longer exists.
     */
    private fun clearGenerationPreview() {
        pendingPreview = null
        mutableState.update { it.copy(generationPreview = null) }
    }

    fun setDraftName(name: String) = editDraft { draft -> editor.editor(draft).renamed(name).draft }

    /**
     * The working draft's own **preference**, or `ExercisePreference.NONE` when no editor is open.
     *
     * Read from [workingDraft] — the domain value every other edit goes through — rather than from the
     * published draft-**UI** model, which is a nullable projection. That is what keeps a single copy of the
     * preference: the UI model is rebuilt from this draft, so reading it here instead would be reading a
     * round-tripped copy of the thing being edited. The `NONE` stand-in makes every operation below a
     * no-op while no editor is open, which is also when `editDraft` refuses to write anything at all.
     */
    private fun currentPreference(): ExercisePreference =
        workingDraft?.preferredExercises ?: ExercisePreference.NONE

    /** The working mode (§2): changing it is structural, and the editor service records it as content. */
    fun setDraftMode(mode: ProgramMode) = editDraft { draft -> editor.editor(draft).withMode(mode).draft }

    fun setDraftDescription(description: String) =
        editDraft { draft -> editor.editor(draft).described(description).draft }

    fun setDraftDuration(duration: ProgramDuration) =
        editDraft { draft -> editor.editor(draft).withDuration(duration).draft }

    fun setDraftSchedule(schedule: ProgramSchedule) =
        editDraft { draft -> editor.editor(draft).withSchedule(schedule).draft }

    /**
     * §7's *Goals & Focus* configuration — the one setting a generation pass reads as its planning
     * input, which is why it is editable while a Generated draft is open: a user who cannot be served
     * because the library has no PULL work for their equipment has to be able to say so here.
     *
     * It is structural content (§6): a save that changes it and nothing else still creates a
     * revision, which is the domain editor's own rule and not this method's.
     */
    fun setDraftFocus(focus: FocusPlan) =
        editDraft { draft -> editor.editor(draft).withFocus(focus).draft }

    /**
     * §7's **exercise preference**: the exercises the next generation should reach for, most preferred
     * first (§9's *user choice*).
     *
     * One whole-value operation, like [setDraftFocus] and for the same reason: the three user actions are
     * add, remove and move, and each of them is a method on [ExercisePreference] whose result is this
     * method's argument. What the controller does *not* do is decide the order — it hands the value the
     * authoring rules produced, so there is no second place where a rank could be invented.
     *
     * Structural content, like [setDraftFocus]: §6 makes *program-behavior* changes revision-creating,
     * and a save that reorders the preference and changes nothing else still creates a revision. That
     * is the domain's rule, stated in `ProgramStructureAspect.PREFERRED_EXERCISES`, not this method's.
     */
    fun setDraftPreferredExercises(preference: ExercisePreference) =
        editDraft { draft -> editor.editor(draft).withPreferredExercises(preference).draft }

    /**
     * Prefers [exerciseId], as the least preferred entry.
     *
     * The add button. It returns **without changing the draft** when the catalogue does not offer that
     * exercise, so an id the user could not have picked from the screen can never reach the draft — the
     * check belongs here because this is the layer that holds the catalogue, and the domain value
     * cannot see it (§5's split applied to a preference).
     */
    fun preferDraftExercise(exerciseId: String) {
        // The catalogue is the one that can say whether this exercise exists at all. Reading the same
        // `exerciseOptions` the add-an-element path already reads keeps a single vocabulary of offered
        // ids, so "addable to the plan" and "preferenceable" cannot disagree about what exists.
        if (mutableState.value.exerciseOptions.none { option -> option.exerciseId == exerciseId }) return
        setDraftPreferredExercises(currentPreference().preferring(exerciseId))
    }

    /** Stops preferring [exerciseId], leaving every other entry exactly where it was. */
    fun unpreferDraftExercise(exerciseId: String) =
        setDraftPreferredExercises(currentPreference().without(exerciseId))

    /**
     * Moves the preference on [exerciseId] one place towards the front — "most preferred" is the first
     * entry, so the first entry is the one with nowhere to move up to.
     */
    fun promoteDraftPreferredExercise(exerciseId: String) =
        moveDraftPreferredExercise(exerciseId, -1)

    /** Moves the preference on [exerciseId] one place towards the end; the last entry has nowhere to go. */
    fun demoteDraftPreferredExercise(exerciseId: String) =
        moveDraftPreferredExercise(exerciseId, +1)

    /**
     * Moves the preference on [exerciseId] by [offset] places, or changes nothing when it would leave
     * the list.
     *
     * The boundary check is [ExercisePreference.moved]'s own — it refuses an out-of-range position — so
     * the controller never clamps a move into a silent success: an entry that is already first stays
     * first because the action is a no-op at the end of the list, not because the position was rewritten.
     */
    private fun moveDraftPreferredExercise(exerciseId: String, offset: Int) {
        val current = currentPreference()
        val position = current.exerciseIds.indexOf(exerciseId)
        if (position < 0) return
        val target = position + 1 + offset
        if (target !in 1..current.exerciseIds.size) return
        setDraftPreferredExercises(current.moved(exerciseId, target))
    }

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
     * §7's **Save**, through §27's production Save: at most one new revision, or none (§6) — and for
     * a create or a copy, the whole creation unit (Program + first Revision + plan + initial Slots,
     * anchored to the chosen or defaulted start date).
     *
     * The date handed down is the user's explicit choice or `null` (→ *today*, read by the save
     * service from the injected clock at this moment); it travels with the same call that creates the
     * Program and its slots, so no second transaction can move the anchor after the slots are planned
     * from it.
     *
     * @return whether the draft was written — `true` when a Program or a revision was created or the
     *   Program's own facts were saved, `false` when nothing was written. The screen uses it to decide
     *   whether to leave the editor; the *reason* a save wrote nothing is already on the state as the
     *   notice, so a refusal is never mistaken for a completed save (§15).
     */
    suspend fun saveDraft(): Boolean {
        val draft = workingDraft ?: return false
        return when (
            val result = saver.save(draft, mutableState.value.draftPlannedStartDate)
        ) {
            is ProgramEditorResult.Success -> {
                val notice = saveNotice(result.value)
                workingDraft = null
                draftSeedKey = null
                clearGenerationPreview()
                mutableState.update {
                    it.copy(
                        draft = null,
                        draftPlannedStartDate = null,
                        notice = notice
                    )
                }
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

    /**
     * Runs one Program operation, publishes its outcome, then re-reads what the operation changed.
     *
     * @return the notice it published — the same value the state now carries. A screen that has to decide
     *   something *after* an action (leaving a screen when a Program is gone, staying when it is not) reads
     *   §15's own classification off it instead of re-deriving one from the domain result.
     */
    private suspend fun <T> action(
        success: ProgramNotice,
        operation: suspend () -> ProgramOperationResult<T>
    ): ProgramNotice {
        val notice = when (val result = operation()) {
            is ProgramOperationResult.Success -> success
            is ProgramOperationResult.Refused -> noticeFor(result.reason)
            is ProgramOperationResult.Failure -> ProgramNotice.STORAGE_FAILED
        }
        mutableState.update { it.copy(notice = notice) }
        if (notice is ProgramNotice.Done) refresh()
        return notice
    }

    /**
     * The same publication for the one *composed* operation, whose two halves can disagree.
     *
     * It is a separate helper rather than a widened [action] because the mapping is genuinely
     * different: a start that succeeded while its target scheduling was refused is **not** the
     * start's success notice, and collapsing it would tell the user a Program was planned when
     * nothing about it was. The state is refreshed whenever the Program really did start, because
     * its lifecycle changed whatever the second half reported.
     */
    private suspend fun startAction(
        operation: suspend () -> ProgramStartResult
    ): ProgramNotice {
        val result = operation()
        val notice = when (result) {
            is ProgramStartResult.Started -> ProgramNotice.STARTED
            is ProgramStartResult.TargetSchedulingRefused ->
                ProgramNotice.TARGET_SCHEDULING_UNAVAILABLE
            is ProgramStartResult.StartRefused -> noticeFor(result.reason)
            is ProgramStartResult.StartFailed -> ProgramNotice.STORAGE_FAILED
        }
        mutableState.update { it.copy(notice = notice) }
        if (result is ProgramStartResult.Started || result is ProgramStartResult.TargetSchedulingRefused) {
            refresh()
        }
        return notice
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

    /**
     * The Scheduler's answer about the detail screen's next workout, and the three meanings it can have
     * (§20, §28, §15):
     *
     * ```text
     * Success   the pass decided. The answer is the Scheduler's, including "the revision has no date left"
     * Refused   a rule stopped the pass — no scheduling anchor yet, an archived Program, a completed one.
     *           That is the honest absence of a next workout, not an error: no date is shown, and none is
     *           invented.
     * Failure   storage failed, or the persisted data is invalid. That is **not** an absence, so it is
     *           published as a SYSTEM_FAILURE and the detail says the schedule could not be read — it is
     *           never rendered as the ordinary "nothing planned yet" line (§33).
     * ```
     */
    private suspend fun previewOf(programId: ProgramId): PreviewFacts =
        when (val result = scheduler.preview(programId)) {
            is ProgramSchedulingResult.Success -> PreviewFacts(
                nextOpportunity = result.value.scheduledDates.minOrNull()
                    ?: result.value.created.minByOrNull { slot -> slot.plannedFor }?.plannedFor,
                hasNoFutureDate = result.value.hasNoFutureDate,
                unreadable = false
            )

            is ProgramSchedulingResult.Refused -> PreviewFacts(
                nextOpportunity = null,
                hasNoFutureDate = false,
                unreadable = false
            )

            is ProgramSchedulingResult.Failure -> {
                mutableState.update { it.copy(notice = ProgramNotice.STORAGE_FAILED) }
                PreviewFacts(nextOpportunity = null, hasNoFutureDate = false, unreadable = true)
            }
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
        // A pending Preview describes one draft just as exactly, so the same edit invalidates it: the
        // prospective result was built for a draft that no longer exists and must not stay applicable.
        clearGenerationPreview()
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
            // §7's Goals & Focus: the draft's own configuration, handed over rather than rebuilt. The
            // screen displays this value and edits it by handing a new one back to `setDraftFocus`,
            // so there is exactly one copy of it in the UI layer — the draft's.
            focus = draft.focus,
            // §9's *user choice*, held for exactly the same reason: the draft's own ordering, which the
            // screen shows as a ranking and edits by handing a whole `ExercisePreference` back. The
            // order it displays is therefore the order the next generation will be planned against.
            preferredExercises = draft.preferredExercises,
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
        ProgramStructureAspect.PREFERRED_EXERCISES -> R.string.programs_change_preferred_exercises
        ProgramStructureAspect.DAYS -> R.string.programs_change_days
        ProgramStructureAspect.EXERCISES -> R.string.programs_change_exercises
        ProgramStructureAspect.PRESCRIPTIONS -> R.string.programs_change_prescriptions
        ProgramStructureAspect.PINNING -> R.string.programs_change_pinning
    }

    /**
     * What the Scheduler's preview told the detail screen: a date, the fact that there is none, or the fact
     * that the answer could not be read at all — three states, because §15 forbids collapsing the third into
     * the first two.
     */
    private data class PreviewFacts(
        val nextOpportunity: LocalDate?,
        val hasNoFutureDate: Boolean,
        val unreadable: Boolean
    )

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
