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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.monkfitness.app.R
import com.monkfitness.app.domain.program.LifecycleStatus
import com.monkfitness.app.domain.program.ProgramDayType
import com.monkfitness.app.domain.program.ProgramDuration
import com.monkfitness.app.domain.program.ProgramMode
import com.monkfitness.app.domain.program.ProgramSchedule
import com.monkfitness.app.domain.program.ProgramSource
import com.monkfitness.app.domain.workout.SessionStatus
import com.monkfitness.app.ui.programs.ProgramNotice
import com.monkfitness.app.ui.programs.ProgramRowUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The Program System's entry point in the app's **one** navigation graph.
 *
 * It is a plain destination like any other: three entries, no state machine and no second graph. The
 * screens below it (My Programs, Detail, Editor, Import) are the same graph's destinations, reached with
 * stable identifiers — a `programId` string, and nothing else — so §16's rule about navigation
 * arguments holds by construction.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgramsScreen(
    onBack: () -> Unit,
    onOpenMyPrograms: () -> Unit,
    onOpenCreate: () -> Unit,
    onOpenImport: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.programs_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.previous)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = stringResource(R.string.programs_hub_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary
            )

            ProgramEntry(
                titleRes = R.string.programs_my_programs,
                descriptionRes = R.string.programs_my_programs_desc,
                onClick = onOpenMyPrograms
            )
            ProgramEntry(
                titleRes = R.string.programs_create,
                descriptionRes = R.string.programs_create_desc,
                onClick = onOpenCreate
            )
            ProgramEntry(
                titleRes = R.string.programs_import,
                descriptionRes = R.string.programs_import_desc,
                onClick = onOpenImport
            )
        }
    }
}

/**
 * **Create Program** — §7's two creation entry paths, and §2's two modes.
 *
 * ```text
 * Build it myself → MANUAL
 * Build for me    → GENERATED
 * ```
 *
 * Both open the same editor, with the draft's mode set to the user's choice. It is deliberately not a
 * third mode and not a wizard: the mode is *content* (§2, §6), and the editor is the one place a plan is
 * built.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgramCreateChoiceScreen(
    onBack: () -> Unit,
    onManual: () -> Unit,
    onGenerated: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.programs_create)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.previous)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            ProgramEntry(
                titleRes = R.string.programs_create_manual,
                descriptionRes = R.string.programs_create_manual_desc,
                onClick = onManual
            )
            ProgramEntry(
                titleRes = R.string.programs_create_generated,
                descriptionRes = R.string.programs_create_generated_desc,
                onClick = onGenerated
            )
        }
    }
}

/** One entry of the Programs hub: a title, the sentence that explains it and the tap that opens it. */
@Composable
private fun ProgramEntry(titleRes: Int, descriptionRes: Int, onClick: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
            Text(text = stringResource(titleRes))
        }
        Text(
            text = stringResource(descriptionRes),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary
        )
    }
}

/**
 * Shows the last operation's outcome, **awaits the snackbar, and only then clears it** (P2 `UI-01`).
 *
 * §15's four classes are what the user must be able to tell apart, so the duration is not a detail: a
 * [ProgramNotice.Done] disappears by itself, while a refusal, an invalid file and a failure stay until
 * the user dismisses them — a failed operation must never scroll past looking like a completed one.
 *
 * The lifecycle is what makes that true. The effect is keyed on [notice], so the moment the notice
 * changes this coroutine is cancelled and relaunched — which means clearing the notice *inside* it,
 * as this host used to do, cancelled `showSnackbar` at its very first suspension point and the user
 * saw nothing: `Generation unavailable`, a refusal, a save failure could all vanish unread. The order
 * is therefore
 *
 * ```text
 * notice published → effect starts → old snackbar dismissed → showSnackbar(...) awaited
 *                 → onShown() clears the notice → the key change retires the (already finished) effect
 * ```
 *
 * `showSnackbar` suspends until the snackbar is dismissed — immediately for the action-labeled
 * indefinite ones (the user pressed OK), after its duration for the Short ones — so "awaited" is
 * exactly "handled by the user or timed out". A newer notice published meanwhile simply replaces
 * this coroutine with one for the newer value, and the dismissal above guarantees the older message
 * does not sit on top of it. The notice itself is domain input: this host decides no new taxonomy,
 * it only decides *when* the controller may clear it.
 */
@Composable
fun ProgramNoticeHost(
    notice: ProgramNotice?,
    onShown: () -> Unit,
    snackbarHostState: SnackbarHostState
) {
    val context = LocalContext.current
    val dismissLabel = stringResource(R.string.ok)
    LaunchedEffect(notice) {
        val current = notice ?: return@LaunchedEffect
        snackbarHostState.currentSnackbarData?.dismiss()
        snackbarHostState.showSnackbar(
            message = context.getString(current.messageRes),
            actionLabel = if (current is ProgramNotice.Done) null else dismissLabel,
            duration = if (current is ProgramNotice.Done) {
                SnackbarDuration.Short
            } else {
                SnackbarDuration.Indefinite
            }
        )
        // After the await: the user has seen this notice, so — and only so — the controller clears it.
        onShown()
    }
}

/** Localized name of where a Program came from. */
@Composable
fun sourceLabel(source: ProgramSource): String = stringResource(
    when (source) {
        ProgramSource.STANDARD -> R.string.programs_source_standard
        ProgramSource.USER -> R.string.programs_source_user
        ProgramSource.IMPORTED -> R.string.programs_source_imported
    }
)

/** Localized name of a lifecycle state. Archive is not one and is labelled separately (§3). */
@Composable
fun lifecycleLabel(status: LifecycleStatus): String = stringResource(
    when (status) {
        LifecycleStatus.NOT_STARTED -> R.string.programs_lifecycle_not_started
        LifecycleStatus.RUNNING -> R.string.programs_lifecycle_running
        LifecycleStatus.PAUSED -> R.string.programs_lifecycle_paused
        LifecycleStatus.COMPLETED -> R.string.programs_lifecycle_completed
    }
)

/** Localized name of one of §2's two permanent modes. */
@Composable
fun modeLabel(mode: ProgramMode): String = stringResource(
    when (mode) {
        ProgramMode.MANUAL -> R.string.programs_mode_manual
        ProgramMode.GENERATED -> R.string.programs_mode_generated
    }
)

/** Localized name of a plan day's kind. */
@Composable
fun dayTypeLabel(type: ProgramDayType): String = stringResource(
    when (type) {
        ProgramDayType.TRAINING -> R.string.programs_day_training
        ProgramDayType.MOBILITY -> R.string.programs_day_mobility
        ProgramDayType.POSTURE_MOBILITY -> R.string.programs_day_posture_mobility
        ProgramDayType.REST -> R.string.programs_day_rest
    }
)

/** Localized description of how long a revision runs (§20). */
@Composable
fun durationLabel(duration: ProgramDuration): String = when (duration) {
    is ProgramDuration.FixedDays ->
        stringResource(R.string.programs_duration_fixed, duration.days)

    ProgramDuration.Indefinite -> stringResource(R.string.programs_duration_indefinite)
}

/** Localized description of when a revision's slots fall (§20). */
@Composable
fun scheduleLabel(schedule: ProgramSchedule): String = when (schedule) {
    is ProgramSchedule.FixedWeekdays ->
        stringResource(R.string.programs_schedule_fixed, schedule.weekdays.size)

    is ProgramSchedule.FlexiblePerWeek ->
        stringResource(R.string.programs_schedule_flexible, schedule.sessionsPerWeek)
}

/** Localized name of an attempt's state in the history list (§19). */
@Composable
fun sessionStatusLabel(status: SessionStatus): String = stringResource(
    when (status) {
        SessionStatus.IN_PROGRESS -> R.string.programs_session_in_progress
        SessionStatus.COMPLETED -> R.string.programs_session_completed
        SessionStatus.CANCELLED -> R.string.programs_session_cancelled
    }
)

/** A read-only fact rendered as `label — value`, the shape every card on these screens uses. */
@Composable
fun ProgramFactRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 8.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/** A section heading with the app's own spacing. */
@Composable
fun ProgramSection(title: String) {
    Spacer(modifier = Modifier.height(8.dp))
    Text(text = title, style = MaterialTheme.typography.titleMedium)
}

/** The date the app shows for a plan or an opportunity, localized by the platform's own formatter. */
@Composable
fun dateLabel(date: LocalDate): String =
    java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM)
        .withLocale(java.util.Locale.getDefault())
        .format(date)

/** Localized summary of one My Programs row: where it is and whether it is filed away. */
@Composable
fun programRowSummary(row: ProgramRowUi): String {
    val lifecycle = lifecycleLabel(row.lifecycleStatus)
    return if (row.isArchived) {
        stringResource(R.string.programs_row_archived_summary, lifecycle)
    } else {
        lifecycle
    }
}

/** Runs a suspend action without blocking the composition, the one way these screens drive the holder. */
fun runAction(scope: CoroutineScope, action: suspend () -> Unit) {
    scope.launch { action() }
}

/** The instant the Material date picker addresses a date by: midnight UTC, the picker's own convention. */
fun LocalDate.toPickerMillis(): Long = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

/** The date the Material date picker's answer means, read back in the same convention. */
fun pickerMillisToDate(millis: Long): LocalDate =
    java.time.Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
