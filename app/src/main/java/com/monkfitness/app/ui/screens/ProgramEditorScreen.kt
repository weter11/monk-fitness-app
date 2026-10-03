package com.monkfitness.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.annotation.StringRes
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.monkfitness.app.R
import com.monkfitness.app.domain.prescription.PrescriptionDimension
import com.monkfitness.app.domain.program.Focus
import com.monkfitness.app.domain.program.FocusPlan
import com.monkfitness.app.domain.program.Goal
import com.monkfitness.app.domain.program.ProgramDayType
import com.monkfitness.app.domain.program.ProgramDuration
import com.monkfitness.app.domain.program.ProgramMode
import com.monkfitness.app.domain.program.ProgramSchedule
import com.monkfitness.app.ui.programs.ExerciseOptionUi
import com.monkfitness.app.ui.programs.FocusPercentEntry
import com.monkfitness.app.ui.programs.ProgramDraftDayUi
import com.monkfitness.app.ui.programs.ProgramDraftElementUi
import com.monkfitness.app.ui.programs.ProgramDraftEntry
import com.monkfitness.app.ui.programs.ProgramDraftUi
import com.monkfitness.app.ui.programs.ProgramsController
import com.monkfitness.app.ui.programs.focusLabelRes
import com.monkfitness.app.ui.programs.matchesExerciseQuery
import com.monkfitness.app.ui.programs.toggledFocus

/**
 * The Program Editor — §7's `Basics / Schedule / Plan / Review` over the target editor, draft-first.
 *
 * The screen edits a **draft** and nothing else: every change is a call on [ProgramsController] that
 * produces the next draft through `ProgramDraftEditor`, and a revision appears only when the user saves
 * (§6, §7). It renders the validation's findings and the review's answer — §7's *"one Save → at most one
 * new Revision, no-op Save → none"* is decided by the editor service and merely *shown* here.
 *
 * What the screen owns is presentation: which section is open, which dialog is up, what the working name
 * is while it is being typed. What it does not own is any rule about Programs.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ProgramEditorScreen(
    controller: ProgramsController,
    seedKey: String,
    seed: suspend () -> Unit,
    onBack: () -> Unit,
    onSaved: () -> Unit
) {
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var showExercisePicker by remember { mutableStateOf<String?>(null) }
    var showStartDatePicker by remember { mutableStateOf(false) }

    // Which of §7's entry points opened this session. It is a *seed* rather than a decision: the route
    // says `create MODE`, `edit PROGRAM` or `copy PROGRAM`, and the controller opens the draft.
    //
    // The seed runs through the controller's guard because this effect re-runs on every re-composition
    // of the destination — a rotation or any configuration change re-creates the composition while the
    // controller (and its working draft) survives — and a bare `seed()` would replace the user's
    // half-edited draft with a fresh empty one on every such event (P2 `UI-03`). Same flow, draft
    // present → the guard keeps it; a genuinely new flow → the seed runs.
    LaunchedEffect(seedKey) { controller.seedEditor(seedKey) { seed() } }

    val draft: ProgramDraftUi? = state.draft

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            when (draft?.entry) {
                                ProgramDraftEntry.COPY -> R.string.programs_editor_title_copy
                                ProgramDraftEntry.EDIT -> R.string.programs_editor_title_edit
                                else -> R.string.programs_editor_title_new
                            }
                        )
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.previous)
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { padding ->
        ProgramNoticeHost(
            notice = state.notice,
            onShown = { controller.dismissNotice() },
            snackbarHostState = snackbarHostState
        )

        val current = draft

        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (current == null) {
                Text(
                    text = stringResource(R.string.programs_editor_no_draft),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary
                )
                return@Column
            }

            OutlinedTextField(
                value = current.name,
                onValueChange = { controller.setDraftName(it) },
                label = { Text(stringResource(R.string.programs_editor_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = current.description,
                onValueChange = { controller.setDraftDescription(it) },
                label = { Text(stringResource(R.string.programs_editor_description)) },
                modifier = Modifier.fillMaxWidth()
            )

            ProgramSection(stringResource(R.string.programs_editor_mode))
            Text(text = modeLabel(current.mode), style = MaterialTheme.typography.bodyMedium)
            if (current.mode == ProgramMode.GENERATED) {
                Text(
                    text = stringResource(R.string.programs_generation_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
                TextButton(onClick = { runAction(scope) { controller.generateDraft() } }) {
                    Text(stringResource(R.string.programs_editor_generate))
                }
                TextButton(onClick = { runAction(scope) { controller.regenerateDraft() } }) {
                    Text(stringResource(R.string.programs_editor_regenerate))
                }
            }

            ProgramSection(stringResource(R.string.programs_editor_goals_focus))
            GoalsAndFocusSection(
                focus = current.focus,
                onFocus = { focus -> controller.setDraftFocus(focus) }
            )

            ProgramSection(stringResource(R.string.programs_editor_duration))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = current.duration == ProgramDuration.Indefinite,
                    onClick = { controller.setDraftDuration(ProgramDuration.Indefinite) },
                    label = { Text(stringResource(R.string.programs_duration_indefinite)) }
                )
                FilterChip(
                    selected = current.duration == ProgramDuration.FixedDays(DURATION_DAYS_30),
                    onClick = { controller.setDraftDuration(ProgramDuration.FixedDays(DURATION_DAYS_30)) },
                    label = { Text(stringResource(R.string.programs_duration_days_30)) }
                )
                FilterChip(
                    selected = current.duration == ProgramDuration.FixedDays(DURATION_DAYS_56),
                    onClick = { controller.setDraftDuration(ProgramDuration.FixedDays(DURATION_DAYS_56)) },
                    label = { Text(stringResource(R.string.programs_duration_days_56)) }
                )
            }

            // §3/§6 and P1: the planned start date is a separate configuration item of a *new*
            // Program — not a field of the structural draft (choosing it creates no Revision) and not
            // a start (nothing here moves the lifecycle). It appears on the create and copy entries,
            // where it is part of the creation request; an existing Program's date has its own owner
            // — Program Detail/settings through ProgramLifecycleService.setPlannedStartDate — and is
            // deliberately not duplicated into the edit entry.
            if (current.entry != ProgramDraftEntry.EDIT) {
                ProgramSection(stringResource(R.string.programs_editor_start_date))
                Text(
                    text = stringResource(R.string.programs_editor_start_date_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
                val chosenDate = state.draftPlannedStartDate
                if (chosenDate == null) {
                    // No exact choice: the Save resolves this to today from the injected clock. The
                    // button says what the choice *is* — a specific date — so "start with a concrete
                    // date" is distinguishable from "already started", which only the lifecycle says.
                    TextButton(onClick = { showStartDatePicker = true }) {
                        Text(stringResource(R.string.programs_editor_start_date_choose))
                    }
                } else {
                    Text(text = dateLabel(chosenDate), style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { showStartDatePicker = true }) {
                        Text(stringResource(R.string.programs_import_change_date))
                    }
                }
            }

            ProgramSection(stringResource(R.string.programs_editor_schedule))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                (SCHEDULE_OPTIONS).forEach { sessions ->
                    FilterChip(
                        selected = current.schedule == ProgramSchedule.FlexiblePerWeek(sessions),
                        onClick = {
                            controller.setDraftSchedule(ProgramSchedule.FlexiblePerWeek(sessions))
                        },
                        label = {
                            Text(stringResource(R.string.programs_schedule_sessions_per_week, sessions))
                        }
                    )
                }
            }

            ProgramSection(stringResource(R.string.programs_editor_plan))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { controller.addDraftDay(ProgramDayType.TRAINING) }) {
                    Text(stringResource(R.string.programs_editor_add_training_day))
                }
                TextButton(onClick = { controller.addDraftDay(ProgramDayType.REST) }) {
                    Text(stringResource(R.string.programs_editor_add_rest_day))
                }
            }

            if (current.days.isEmpty()) {
                Text(
                    text = stringResource(R.string.programs_editor_no_days),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary
                )
            }

            current.days.forEach { day ->
                DraftDayCard(
                    day = day,
                    onRemoveDay = { controller.removeDraftDay(day.programDayId) },
                    onAddExercise = { showExercisePicker = day.programDayId },
                    onRemoveElement = { element ->
                        controller.removeDraftElement(day.programDayId, element.programExerciseId)
                    },
                    onDuplicateElement = { element ->
                        controller.duplicateDraftElement(day.programDayId, element.programExerciseId)
                    },
                    onPin = { element, pinned ->
                        controller.setDraftPinned(day.programDayId, element.programExerciseId, pinned)
                    },
                    onPrescription = { element, sets, target ->
                        controller.setDraftPrescription(
                            day.programDayId,
                            element.programExerciseId,
                            sets,
                            target
                        )
                    }
                )
            }

            if (current.issueRes.isNotEmpty()) {
                ProgramSection(stringResource(R.string.programs_editor_findings))
                current.issueRes.forEach { issue ->
                    Text(
                        text = stringResource(issue),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { runAction(scope) { controller.reviewDraft() } },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.programs_editor_review))
                }
                Button(
                    onClick = {
                        runAction(scope) {
                            if (controller.saveDraft()) onSaved()
                        }
                    },
                    enabled = current.isValid,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.programs_editor_save))
                }
            }

            current.review?.let { review ->
                ProgramSection(stringResource(R.string.programs_review_title))
                Text(
                    text = stringResource(
                        when {
                            review.createsProgram -> R.string.programs_review_creates_program
                            review.willCreateARevision -> R.string.programs_review_creates_revision
                            else -> R.string.programs_review_no_change
                        }
                    ),
                    style = MaterialTheme.typography.bodyMedium
                )
                review.revisionNumber?.let { number ->
                    ProgramFactRow(
                        label = stringResource(R.string.programs_review_revision_number),
                        value = number.toString()
                    )
                }
                ProgramFactRow(
                    label = stringResource(R.string.programs_detail_days),
                    value = stringResource(
                        R.string.programs_detail_days_value,
                        review.dayCount,
                        review.restDayCount
                    )
                )
                ProgramFactRow(
                    label = stringResource(R.string.programs_detail_exercises),
                    value = review.exerciseCount.toString()
                )
                ProgramFactRow(
                    label = stringResource(R.string.programs_editor_sets_total),
                    value = review.setCount.toString()
                )
                if (review.changeRes.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.programs_review_changes),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    review.changeRes.forEach { change ->
                        Text(
                            text = stringResource(change),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }
            }

            TextButton(onClick = { controller.discardDraft(); onBack() }) {
                Text(stringResource(R.string.programs_editor_discard))
            }
        }

        val picker = showExercisePicker
        if (picker != null) {
            ExercisePickerDialog(
                options = state.exerciseOptions,
                onDismiss = { showExercisePicker = null },
                onPick = { exerciseId ->
                    showExercisePicker = null
                    controller.addDraftElement(picker, exerciseId)
                }
            )
        }

        // The creation request's own date choice — the same Material picker the import review uses,
        // held beside the draft rather than in it (§6: the date is no part of the structure).
        if (showStartDatePicker) {
            val datePickerState = rememberDatePickerState(
                initialSelectedDateMillis = state.draftPlannedStartDate?.toPickerMillis()
            )
            DatePickerDialog(
                onDismissRequest = { showStartDatePicker = false },
                confirmButton = {
                    TextButton(
                        onClick = {
                            datePickerState.selectedDateMillis?.let { millis ->
                                controller.setDraftPlannedStartDate(pickerMillisToDate(millis))
                            }
                            showStartDatePicker = false
                        }
                    ) {
                        Text(stringResource(R.string.ok))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showStartDatePicker = false }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            ) {
                DatePicker(state = datePickerState)
            }
        }
    }
}

/**
 * §7's **Goals & Focus** — the draft's own [FocusPlan], displayed as it is and edited by handing a
 * new one back.
 *
 * The section keeps no configuration of its own: [focus] is the draft's value, read from the
 * controller's presentation, and every choice here ends in one call to `ProgramsController.setDraftFocus`.
 * There is no second copy that could be edited and disagree with the draft the next generation reads.
 *
 * Which of the three forms is shown follows from the value itself — a `BALANCED` draft shows no
 * focuses and no percentages, a `FOCUSED` draft shows the named ones, a `CUSTOM` draft shows the
 * shares — so the section cannot present a configuration the draft is not in.
 *
 * Both choosable goals are settled by a dialog the user confirms, because each of them needs something
 * only the user can state: which focuses to train, or what share each of them gets. Tapping a goal chip
 * therefore *asks*, and the draft changes only on the confirmation — no focus, no percentage and no
 * share is ever chosen here on the user's behalf.
 *
 * For a `GENERATED` draft this section is what says what the next generation pass will be built for,
 * which is why it sits above the plan rather than after it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GoalsAndFocusSection(
    focus: FocusPlan,
    onFocus: (FocusPlan) -> Unit
) {
    var choosingFocuses by remember { mutableStateOf(false) }
    var editingShares by remember { mutableStateOf(false) }

    Text(
        text = stringResource(
            R.string.programs_editor_goal_label,
            stringResource(goalLabelRes(focus.goal))
        ),
        style = MaterialTheme.typography.bodyMedium
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = focus.goal == Goal.BALANCED,
            onClick = { onFocus(FocusPlan.Balanced) },
            label = { Text(stringResource(R.string.programs_goal_balanced)) }
        )
        FilterChip(
            selected = focus.goal == Goal.FOCUSED,
            onClick = { choosingFocuses = true },
            label = { Text(stringResource(R.string.programs_goal_focused)) }
        )
        FilterChip(
            selected = focus.goal == Goal.CUSTOM,
            onClick = { editingShares = true },
            label = { Text(stringResource(R.string.programs_goal_custom)) }
        )
    }

    when (val current = focus) {
        is FocusPlan.Balanced -> Text(
            text = stringResource(R.string.programs_editor_focus_balanced_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary
        )

        is FocusPlan.Focused -> {
            Text(
                text = stringResource(R.string.programs_editor_focus_focused_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Focus.entries.forEach { focus ->
                    val chosen = focus in current.focuses
                    FilterChip(
                        selected = chosen,
                        // Removing the last named focus would leave a FOCUSED plan naming nothing, which
                        // is the BALANCED configuration rather than a third form; the domain refuses to
                        // build it, so the control refuses to offer it.
                        enabled = chosen || current.focuses.size > 1,
                        onClick = { toggledFocus(current, focus)?.let(onFocus) },
                        label = { Text(stringResource(focusLabelRes(focus))) }
                    )
                }
            }
            if (current.focuses.size == 1) {
                Text(
                    text = stringResource(R.string.programs_editor_focus_focused_keep_one),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
            TextButton(onClick = { choosingFocuses = true }) {
                Text(stringResource(R.string.programs_editor_focus_choose))
            }
        }

        is FocusPlan.Custom -> {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(R.string.programs_editor_focus_custom_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
                current.allocations.forEach { allocation ->
                    ProgramFactRow(
                        label = stringResource(focusLabelRes(allocation.focus)),
                        value = stringResource(
                            R.string.programs_editor_focus_share,
                            allocation.percent
                        )
                    )
                }
                TextButton(onClick = { editingShares = true }) {
                    Text(stringResource(R.string.programs_editor_focus_edit))
                }
            }
        }
    }

    if (choosingFocuses) {
        FocusPickerDialog(
            initial = (focus as? FocusPlan.Focused)?.focuses.orEmpty(),
            onDismiss = { choosingFocuses = false },
            onDone = { focuses ->
                choosingFocuses = false
                // §8's canonical semantics decide the resulting configuration, not the order the user
                // happened to tap in.
                onFocus(FocusPlan.focused(focuses))
            }
        )
    }

    // The shares being typed are the dialog's own working state — deliberately not the draft's, because
    // an allocation that does not add up to 100% cannot be a `FocusPlan` at all. `Done` is offered only
    // once the domain accepts the numbers, so nothing unfinished ever reaches the draft.
    if (editingShares) {
        FocusSharesDialog(
            initial = sharesFrom(focus),
            onDismiss = { editingShares = false },
            onDone = { plan ->
                editingShares = false
                onFocus(plan)
            }
        )
    }
}

/**
 * The share dialog's starting numbers: the draft's own CUSTOM shares when it already is one, and every
 * focus unallocated otherwise.
 *
 * An unallocated focus is `0`, which is not a share the domain accepts — it is the *absence* of one, and
 * the honest state of a CUSTOM allocation the user has not typed yet. Nothing is pre-filled on their
 * behalf: there is no even split, no 100% for the first focus, no half each.
 */
private fun sharesFrom(focus: FocusPlan): FocusPercentEntry {
    val stated = (focus as? FocusPlan.Custom)?.allocations.orEmpty()
    return Focus.entries.fold(FocusPercentEntry()) { entry, focus ->
        entry.withPercent(focus, stated.firstOrNull { it.focus == focus }?.percent ?: 0)
    }
}

/**
 * §7's FOCUSED chooser: any number of the seven focuses, named by the user.
 *
 * `Done` stays disabled while none is chosen, because a FOCUSED plan that names no focus is the
 * BALANCED configuration — a different form, not an empty FOCUSED one. The chips are listed in the
 * vocabulary's own order and the result goes through [FocusPlan.focused], so the order they were
 * tapped in cannot survive as a hidden priority.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FocusPickerDialog(
    initial: List<Focus>,
    onDismiss: () -> Unit,
    onDone: (List<Focus>) -> Unit
) {
    var chosen by remember { mutableStateOf(initial.toSet()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.programs_editor_focus_pick_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.programs_editor_focus_focused_desc),
                    style = MaterialTheme.typography.bodySmall
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Focus.entries.forEach { focus ->
                        FilterChip(
                            selected = focus in chosen,
                            onClick = { chosen = if (focus in chosen) chosen - focus else chosen + focus },
                            label = { Text(stringResource(focusLabelRes(focus))) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onDone(chosen.toList()) }, enabled = chosen.isNotEmpty()) {
                Text(stringResource(R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

/**
 * §7's CUSTOM editor: one whole percent per focus, and what is still unassigned.
 *
 * The shares are the user's own numbers. Nothing here completes an allocation, redistributes a
 * percentage or picks a focus: `Done` hands the typed numbers to [FocusPercentEntry.toFocusPlan],
 * which goes through `FocusPlan.custom`, and a sum the domain refuses leaves the draft untouched.
 */
@Composable
private fun FocusSharesDialog(
    initial: FocusPercentEntry,
    onDismiss: () -> Unit,
    onDone: (FocusPlan) -> Unit
) {
    var entry by remember { mutableStateOf(initial) }
    val finished = entry.toFocusPlan()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.programs_editor_focus_custom_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Focus.entries.forEach { focus ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(focusLabelRes(focus)),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = entry.percentOf(focus).takeIf { it > 0 }?.toString() ?: "",
                            onValueChange = { typed ->
                                entry = entry.withPercent(
                                    focus,
                                    typed.filter { it.isDigit() }.toIntOrNull() ?: 0
                                )
                            },
                            singleLine = true,
                            modifier = Modifier.width(PERCENT_FIELD_WIDTH)
                        )
                    }
                }
                Text(
                    text = stringResource(
                        R.string.programs_editor_focus_remaining,
                        entry.remainingPercent()
                    ),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { finished?.let(onDone) }, enabled = finished != null) {
                Text(stringResource(R.string.programs_editor_focus_custom_done))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

/** §7's three goals, named in the domain's own vocabulary (§8). */
@StringRes
private fun goalLabelRes(goal: Goal): Int = when (goal) {
    Goal.BALANCED -> R.string.programs_goal_balanced
    Goal.FOCUSED -> R.string.programs_goal_focused
    Goal.CUSTOM -> R.string.programs_goal_custom
}

/** One plan day while editing: its heading, its elements and the three things a day accepts. */
@Composable
private fun DraftDayCard(
    day: ProgramDraftDayUi,
    onRemoveDay: () -> Unit,
    onAddExercise: () -> Unit,
    onRemoveElement: (ProgramDraftElementUi) -> Unit,
    onDuplicateElement: (ProgramDraftElementUi) -> Unit,
    onPin: (ProgramDraftElementUi, Boolean) -> Unit,
    onPrescription: (ProgramDraftElementUi, Int, Int) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(
                        R.string.programs_editor_day_header,
                        day.position,
                        dayTypeLabel(day.type)
                    ),
                    style = MaterialTheme.typography.titleSmall
                )
                TextButton(onClick = onRemoveDay) {
                    Text(stringResource(R.string.programs_editor_remove_day))
                }
            }

            day.elements.forEach { element ->
                DraftElementRow(
                    element = element,
                    onRemove = { onRemoveElement(element) },
                    onDuplicate = { onDuplicateElement(element) },
                    onPin = { pinned -> onPin(element, pinned) },
                    onPrescription = { sets, target -> onPrescription(element, sets, target) }
                )
            }

            TextButton(onClick = onAddExercise) {
                Text(stringResource(R.string.programs_editor_add_exercise))
            }
        }
    }
}

/**
 * One plan element: what it is, how much of it the user prescribes, and the three edits a plan element
 * takes. The prescription is edited in the element's **own** dimension (§10): repetitions, or seconds
 * when the catalogue records the exercise as timed.
 */
@Composable
private fun DraftElementRow(
    element: ProgramDraftElementUi,
    onRemove: () -> Unit,
    onDuplicate: () -> Unit,
    onPin: (Boolean) -> Unit,
    onPrescription: (Int, Int) -> Unit
) {
    val name = if (element.nameRes != 0) {
        stringResource(element.nameRes)
    } else {
        element.exerciseId
    }
    val targetLabel = stringResource(
        if (element.dimension == PrescriptionDimension.TIME_BASED) {
            R.string.programs_editor_target_seconds
        } else {
            R.string.programs_editor_target_reps
        }
    )

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(text = name, style = MaterialTheme.typography.bodyMedium)

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Stepper(
                label = stringResource(R.string.programs_editor_sets),
                value = element.sets,
                onChange = { onPrescription(it, element.targetPerSet) }
            )
            Stepper(
                label = targetLabel,
                value = element.targetPerSet,
                onChange = { onPrescription(element.sets, it) }
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { onPin(!element.isPinned) }) {
                Text(
                    stringResource(
                        if (element.isPinned) {
                            R.string.programs_editor_unpin
                        } else {
                            R.string.programs_editor_pin
                        }
                    )
                )
            }
            TextButton(onClick = onDuplicate) {
                Text(stringResource(R.string.programs_editor_duplicate))
            }
            TextButton(onClick = onRemove) {
                Text(stringResource(R.string.programs_editor_remove_element))
            }
        }
    }
}

/** A labelled `- value +` control, the smallest honest editor for one integer of a prescription. */
@Composable
private fun Stepper(label: String, value: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text = label, style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { onChange(value - 1) }, enabled = value > 1) {
            Text(text = MINUS)
        }
        Text(text = value.toString(), style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = { onChange(value + 1) }) {
            Text(text = PLUS)
        }
    }
}

/** §7's plan: the catalogue's own exercises, offered as the elements a day may hold. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExercisePickerDialog(
    options: List<ExerciseOptionUi>,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit
) {
    var query by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.programs_editor_choose_exercise)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.programs_editor_search_exercise)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                // P3 `UI-04`: the search matches the **user's localized display name** (resolved
                // here for this locale) *and* the stable id — it used to match the id alone, so
                // `Flexion` found nothing in Spanish while the Spanish reader saw no other name.
                // Resolving the labels once outside the filter also means the row the user taps
                // shows exactly the string the match was made against.
                val named = options.map { option ->
                    option to if (option.nameRes != 0) {
                        stringResource(option.nameRes)
                    } else {
                        option.exerciseId
                    }
                }
                val filtered = remember(query, named) {
                    named.filter { (option, displayName) ->
                        matchesExerciseQuery(query, option.exerciseId, displayName)
                    }
                }
                if (filtered.isEmpty()) {
                    // A search with no results is an explained state, never a blank list (P3).
                    Text(
                        text = stringResource(R.string.programs_editor_search_no_results),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                    )
                } else {
                    LazyColumn(modifier = Modifier.height(PICKER_HEIGHT)) {
                        items(filtered, key = { (option, _) -> option.exerciseId }) { (option, displayName) ->
                            TextButton(
                                onClick = { onPick(option.exerciseId) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(text = displayName)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

/** The fixed durations the editor offers; §20 lets a duration be anything, and this is what the UI shows. */
private const val DURATION_DAYS_30 = 30

private const val DURATION_DAYS_56 = 56

/** The weekly frequencies the editor offers. */
private val SCHEDULE_OPTIONS = listOf(2, 3, 4, 5, 6)

/** The share dialog's percent field, wide enough for three digits and the field's own padding. */
private val PERCENT_FIELD_WIDTH = 88.dp

/** The picker's own height, so the dialog stays on screen. */
private val PICKER_HEIGHT = 240.dp

private const val MINUS = "-"

private const val PLUS = "+"
