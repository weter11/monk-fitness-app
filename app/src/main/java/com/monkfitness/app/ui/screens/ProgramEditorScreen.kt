package com.monkfitness.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.monkfitness.app.R
import com.monkfitness.app.domain.prescription.PrescriptionDimension
import com.monkfitness.app.domain.program.ProgramDayType
import com.monkfitness.app.domain.program.ProgramDuration
import com.monkfitness.app.domain.program.ProgramMode
import com.monkfitness.app.domain.program.ProgramSchedule
import com.monkfitness.app.ui.programs.ExerciseOptionUi
import com.monkfitness.app.ui.programs.ProgramDraftDayUi
import com.monkfitness.app.ui.programs.ProgramDraftElementUi
import com.monkfitness.app.ui.programs.ProgramDraftEntry
import com.monkfitness.app.ui.programs.ProgramDraftUi
import com.monkfitness.app.ui.programs.ProgramsController

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
@OptIn(ExperimentalMaterial3Api::class)
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

    // Which of §7's entry points opened this session. It is a *seed* rather than a decision: the route
    // says `create MODE`, `edit PROGRAM` or `copy PROGRAM`, and the controller opens the draft.
    LaunchedEffect(seedKey) { seed() }

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
                    text = stringResource(R.string.programs_generation_unavailable_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
                TextButton(onClick = { runAction(scope) { controller.generateDraft() } }) {
                    Text(stringResource(R.string.programs_editor_generate))
                }
            }

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

            ProgramSection(stringResource(R.string.programs_editor_schedule))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
    }
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
                val filtered = remember(query, options) {
                    options.filter { option ->
                        query.isBlank() || option.exerciseId.contains(query, ignoreCase = true)
                    }
                }
                LazyColumn(modifier = Modifier.height(PICKER_HEIGHT)) {
                    items(filtered, key = { option -> option.exerciseId }) { option ->
                        TextButton(
                            onClick = { onPick(option.exerciseId) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = if (option.nameRes != 0) {
                                    stringResource(option.nameRes)
                                } else {
                                    option.exerciseId
                                }
                            )
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

/** The picker's own height, so the dialog stays on screen. */
private val PICKER_HEIGHT = 240.dp

private const val MINUS = "-"

private const val PLUS = "+"
