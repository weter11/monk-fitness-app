package com.monkfitness.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.monkfitness.app.R
import com.monkfitness.app.domain.program.LifecycleStatus
import com.monkfitness.app.ui.programs.ProgramDetailUi
import com.monkfitness.app.ui.programs.ProgramsController

/**
 * **Program Detail** — blueprint §22: *current-state management, not full analytics*.
 *
 * The screen shows what the application layer can already answer: the name, the mode, the source, the
 * lifecycle, the archive state, the plan's shape, the revision the Program's pointer names, the next
 * opportunity the Scheduler would plan, the calendar's own counts and the recent attempts. Fields §22
 * names for which no read use case exists are **left out** rather than reconstructed here — §5 of this
 * stage forbids querying storage to fill a card, and the document records what is deferred and why.
 *
 * Every action is a controller call: select, edit, copy, rename, share, archive/unarchive, delete and the
 * four lifecycle transitions. The rules behind them (Standard-Program protection, the archive-of-selection
 * guard, the `IN_PROGRESS` guard, which transitions are legal) stay in the services, and a refusal is
 * surfaced as the sentence that explains it instead of being ignored.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgramDetailScreen(
    controller: ProgramsController,
    programId: String,
    onBack: () -> Unit,
    onEditProgram: (String) -> Unit,
    onCopyProgram: (String) -> Unit
) {
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    LaunchedEffect(programId) { controller.openDetail(programId) }

    val detail: ProgramDetailUi? = state.detail

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(detail?.row?.name ?: stringResource(R.string.programs_detail_title)) },
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

        val current = detail ?: return@Scaffold

        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ProgramFactRow(
                label = stringResource(R.string.programs_detail_mode),
                value = modeLabel(current.mode)
            )
            ProgramFactRow(
                label = stringResource(R.string.programs_detail_source),
                value = sourceLabel(current.row.source)
            )
            ProgramFactRow(
                label = stringResource(R.string.programs_detail_lifecycle),
                value = lifecycleLabel(current.row.lifecycleStatus)
            )
            if (current.row.isArchived) {
                ProgramFactRow(
                    label = stringResource(R.string.programs_detail_archive_state),
                    value = stringResource(R.string.programs_detail_archived)
                )
            }
            if (current.row.hasOpenPause) {
                ProgramFactRow(
                    label = stringResource(R.string.programs_detail_pause_state),
                    value = stringResource(R.string.programs_detail_paused_now)
                )
            }
            current.row.plannedStartDate?.let { planned ->
                ProgramFactRow(
                    label = stringResource(R.string.programs_detail_planned_start),
                    value = dateLabel(planned)
                )
            }

            ProgramSection(stringResource(R.string.programs_detail_plan_section))
            ProgramFactRow(
                label = stringResource(R.string.programs_detail_schedule),
                value = scheduleLabel(current.schedule)
            )
            ProgramFactRow(
                label = stringResource(R.string.programs_detail_duration),
                value = durationLabel(current.duration)
            )
            ProgramFactRow(
                label = stringResource(R.string.programs_detail_revision),
                value = stringResource(
                    R.string.programs_detail_revision_value,
                    current.revisionNumber
                )
            )
            ProgramFactRow(
                label = stringResource(R.string.programs_detail_days),
                value = stringResource(
                    R.string.programs_detail_days_value,
                    current.dayCount,
                    current.restDayCount
                )
            )
            ProgramFactRow(
                label = stringResource(R.string.programs_detail_exercises),
                value = current.exerciseCount.toString()
            )

            ProgramSection(stringResource(R.string.programs_detail_next_workout))
            Text(
                text = when {
                    current.nextOpportunity != null -> dateLabel(current.nextOpportunity)
                    current.hasNoFutureDate -> stringResource(R.string.programs_detail_no_future_date)
                    else -> stringResource(R.string.programs_detail_no_planned_date)
                },
                style = MaterialTheme.typography.bodyMedium
            )

            ProgramSection(stringResource(R.string.programs_detail_progress))
            ProgramFactRow(
                label = stringResource(R.string.programs_detail_completed),
                value = current.completed.toString()
            )
            ProgramFactRow(
                label = stringResource(R.string.programs_detail_missed),
                value = current.missed.toString()
            )
            ProgramFactRow(
                label = stringResource(R.string.programs_detail_upcoming),
                value = current.upcoming.toString()
            )

            ProgramSection(stringResource(R.string.programs_detail_recent))
            if (current.recentWorkouts.isEmpty()) {
                Text(
                    text = stringResource(R.string.programs_detail_no_recent),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary
                )
            } else {
                current.recentWorkouts.forEach { entry ->
                    ProgramFactRow(
                        label = dateLabel(entry.plannedFor),
                        value = stringResource(
                            R.string.programs_detail_recent_value,
                            sessionStatusLabel(entry.status),
                            entry.performedSets
                        )
                    )
                }
            }

            if (current.hasNextProgram) {
                ProgramFactRow(
                    label = stringResource(R.string.programs_detail_next_program),
                    value = stringResource(R.string.programs_detail_next_program_configured)
                )
            }

            ProgramSection(stringResource(R.string.programs_detail_actions))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (current.isSelectable) {
                    Button(
                        onClick = { runAction(scope) { controller.select(current.row.programId) } },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.programs_action_select))
                    }
                }
                if (!current.row.isBuiltIn) {
                    Button(
                        onClick = { onEditProgram(current.row.programId) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.programs_action_edit))
                    }
                }
                Button(
                    onClick = { onCopyProgram(current.row.programId) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.programs_action_copy))
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { showRenameDialog = true }) {
                    Text(stringResource(R.string.programs_action_rename))
                }
                TextButton(
                    onClick = { runAction(scope) { controller.share(current.row.programId) } }
                ) {
                    Text(stringResource(R.string.programs_action_share))
                }
                TextButton(
                    onClick = {
                        runAction(scope) {
                            if (current.row.isArchived) {
                                controller.unarchive(current.row.programId)
                            } else {
                                controller.archive(current.row.programId)
                            }
                        }
                    }
                ) {
                    Text(
                        stringResource(
                            if (current.row.isArchived) {
                                R.string.programs_action_unarchive
                            } else {
                                R.string.programs_action_archive
                            }
                        )
                    )
                }
            }

            ProgramSection(stringResource(R.string.programs_detail_lifecycle_actions))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (current.row.lifecycleStatus) {
                    LifecycleStatus.NOT_STARTED -> TextButton(
                        onClick = { runAction(scope) { controller.start(current.row.programId) } }
                    ) {
                        Text(stringResource(R.string.programs_action_start))
                    }

                    LifecycleStatus.RUNNING -> {
                        TextButton(
                            onClick = { runAction(scope) { controller.pause(current.row.programId) } }
                        ) {
                            Text(stringResource(R.string.programs_action_pause))
                        }
                        TextButton(
                            onClick = { runAction(scope) { controller.complete(current.row.programId) } }
                        ) {
                            Text(stringResource(R.string.programs_action_complete))
                        }
                    }

                    LifecycleStatus.PAUSED -> {
                        TextButton(
                            onClick = { runAction(scope) { controller.resume(current.row.programId) } }
                        ) {
                            Text(stringResource(R.string.programs_action_resume))
                        }
                        TextButton(
                            onClick = { runAction(scope) { controller.complete(current.row.programId) } }
                        ) {
                            Text(stringResource(R.string.programs_action_complete))
                        }
                    }

                    // A completed Program cannot be resumed directly (§3): the only offered action is
                    // making a copy, which is above.
                    LifecycleStatus.COMPLETED -> Text(
                        text = stringResource(R.string.programs_detail_completed_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }

            if (current.isDeletable) {
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { showDeleteDialog = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(R.string.programs_action_delete),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        if (showRenameDialog) {
            ProgramRenameDialog(
                initialName = current.row.name,
                onDismiss = { showRenameDialog = false },
                onConfirm = { name ->
                    showRenameDialog = false
                    runAction(scope) { controller.rename(current.row.programId, name) }
                }
            )
        }

        if (showDeleteDialog) {
            AlertDialog(
                onDismissRequest = { showDeleteDialog = false },
                title = { Text(stringResource(R.string.programs_delete_confirm_title)) },
                text = { Text(stringResource(R.string.programs_delete_confirm_text, current.row.name)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showDeleteDialog = false
                            runAction(scope) {
                                controller.delete(current.row.programId)
                                controller.closeDetail()
                                onBack()
                            }
                        }
                    ) {
                        Text(
                            text = stringResource(R.string.programs_action_delete),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteDialog = false }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            )
        }
    }
}

/** The rename dialog: one text field and two answers. The rename itself is the controller's call. */
@Composable
private fun ProgramRenameDialog(
    initialName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember { mutableStateOf(initialName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.programs_rename_title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.programs_editor_name)) },
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name) },
                enabled = name.isNotBlank()
            ) {
                Text(stringResource(R.string.programs_rename_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
