package com.monkfitness.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.monkfitness.app.R
import com.monkfitness.app.platform.ProgramDocumentImport
import com.monkfitness.app.ui.programs.ProgramsController

/**
 * **Import Program** — §5's flow, on §30 step 13's platform boundary:
 *
 * ```text
 * ACTION_OPEN_DOCUMENT → content:// URI → bounded read → the import service's review
 *   → Program name / plan summary → planned start date → the activation checkbox → Confirm
 * ```
 *
 * The screen parses nothing: the bytes go to [ProgramsController.reviewImport], which hands them to the
 * import service, and the review the user sees is the *pipeline's* own draft (§7 of the transfer
 * document: the draft's constructor is internal, so a screen could not assemble one). The two choices on
 * the review — the planned start date and whether the imported Program becomes the active one — are held
 * until the confirmation, and travel with it into the one transaction that writes the Program and its
 * initial schedule.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgramImportScreen(
    controller: ProgramsController,
    onBack: () -> Unit,
    onImported: () -> Unit
) {
    val context = LocalContext.current
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var showDatePicker by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.data
        if (uri != null) {
            runAction(scope) {
                val bytes = try {
                    ProgramDocumentImport.read(context, uri)
                } catch (failure: Throwable) {
                    // A revoked grant, a provider that is gone or a stream that failed part-way is
                    // reported: it is never read as an empty file (§15).
                    controller.reportUnreadableFile()
                    return@runAction
                }
                controller.reviewImport(bytes)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.programs_import)) },
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

        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { picker.launch(ProgramDocumentImport.openDocumentIntent()) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.programs_import_choose_file))
            }

            val review = state.importReview
            if (review == null) {
                Text(
                    text = stringResource(R.string.programs_import_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary
                )
                return@Column
            }

            ProgramSection(stringResource(R.string.programs_import_review_title))

            ProgramFactRow(
                label = stringResource(R.string.programs_editor_name),
                value = review.name
            )
            if (review.description.isNotBlank()) {
                Text(text = review.description, style = MaterialTheme.typography.bodyMedium)
            }
            ProgramFactRow(
                label = stringResource(R.string.programs_detail_days),
                value = review.dayCount.toString()
            )
            ProgramFactRow(
                label = stringResource(R.string.programs_detail_exercises),
                value = review.exerciseCount.toString()
            )
            ProgramFactRow(
                label = stringResource(R.string.programs_editor_sets_total),
                value = review.setCount.toString()
            )

            ProgramSection(stringResource(R.string.programs_import_start_date))
            ProgramFactRow(
                label = stringResource(R.string.programs_detail_planned_start),
                value = dateLabel(review.plannedStartDate)
            )
            TextButton(onClick = { showDatePicker = true }) {
                Text(stringResource(R.string.programs_import_change_date))
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = review.makeActive,
                    onCheckedChange = { controller.setImportMakeActive(it) }
                )
                Text(
                    text = stringResource(R.string.programs_import_make_active),
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = {
                    runAction(scope) {
                        if (controller.confirmImport()) onImported()
                    }
                },
                enabled = review.hasDays,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.programs_import_confirm))
            }
            TextButton(onClick = { controller.discardImport() }) {
                Text(stringResource(R.string.cancel))
            }
        }

        if (showDatePicker) {
            val review = state.importReview
            if (review != null) {
                val datePickerState = rememberDatePickerState(
                    initialSelectedDateMillis = review.plannedStartDate.toPickerMillis()
                )
                DatePickerDialog(
                    onDismissRequest = { showDatePicker = false },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                datePickerState.selectedDateMillis?.let { millis ->
                                    controller.setImportStartDate(pickerMillisToDate(millis))
                                }
                                showDatePicker = false
                            }
                        ) {
                            Text(stringResource(R.string.ok))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showDatePicker = false }) {
                            Text(stringResource(R.string.cancel))
                        }
                    }
                ) {
                    DatePicker(state = datePickerState)
                }
            }
        }
    }
}
