package com.monkfitness.app.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.monkfitness.app.R
import com.monkfitness.app.ui.programs.ProgramRowUi
import com.monkfitness.app.ui.programs.ProgramsController

/**
 * **My Programs** (§21) — the management list, and the entry point the Settings screen no longer hides.
 *
 * It renders exactly what [ProgramsController] read from the lifecycle service: every saved Program, which
 * one is selected, where each one came from, where it is in its life, whether it is archived and whether a
 * pause is in effect. It is *not* an analytics screen (§22): no volume, no frequency, no focus
 * distribution — Progress and History own those, and My Programs shows state.
 *
 * Every action on this screen is a call on the controller, which calls an application service. The screen
 * decides nothing: it does not select, does not compare a name, does not decide whether a Program may be
 * deleted and does not hold a selection of its own.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyProgramsScreen(
    controller: ProgramsController,
    onBack: () -> Unit,
    onOpenProgram: (String) -> Unit,
    onCreateProgram: () -> Unit,
    onImportProgram: () -> Unit
) {
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) { controller.load() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.programs_my_programs)) },
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

        Column(modifier = Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onCreateProgram, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.programs_create))
                }
                Button(onClick = onImportProgram, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.programs_import))
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (state.isEmpty && !state.loading) {
                Text(
                    text = stringResource(R.string.programs_no_programs),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary
                )
            }

            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.activeRows, key = { row -> row.programId }) { row ->
                    ProgramRowCard(
                        row = row,
                        onOpen = { onOpenProgram(row.programId) },
                        onSelect = { runAction(scope) { controller.select(row.programId) } }
                    )
                }

                if (state.archivedRows.isNotEmpty()) {
                    item { ProgramSection(stringResource(R.string.programs_archived_section)) }
                    items(state.archivedRows, key = { row -> row.programId }) { row ->
                        ProgramRowCard(
                            row = row,
                            onOpen = { onOpenProgram(row.programId) },
                            onSelect = { runAction(scope) { controller.select(row.programId) } }
                        )
                    }
                }
            }
        }
    }
}

/**
 * One saved Program: its name, the three facts the list shows, and the select action.
 *
 * The row is *presented* — the selected state is the service's answer, refreshed by the controller, and
 * the select button is offered only when selecting would change something.
 */
@Composable
private fun ProgramRowCard(row: ProgramRowUi, onOpen: () -> Unit, onSelect: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = row.name, style = MaterialTheme.typography.titleMedium)
                if (row.isSelected) {
                    Text(
                        text = stringResource(R.string.programs_row_selected),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            ProgramFactRow(
                label = stringResource(R.string.programs_detail_source),
                value = sourceLabel(row.source)
            )
            ProgramFactRow(
                label = stringResource(R.string.programs_detail_lifecycle),
                value = programRowSummary(row)
            )
            row.plannedStartDate?.let { planned ->
                ProgramFactRow(
                    label = stringResource(R.string.programs_detail_planned_start),
                    value = dateLabel(planned)
                )
            }

            if (!row.isSelected) {
                TextButton(onClick = onSelect) {
                    Text(stringResource(R.string.programs_action_select))
                }
            }
        }
    }
}
