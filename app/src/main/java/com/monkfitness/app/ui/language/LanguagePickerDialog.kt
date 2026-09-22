package com.monkfitness.app.ui.language

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.monkfitness.app.R
import com.monkfitness.app.language.AppLanguage

/**
 * The language picker (§6): one single-choice list with one selected value, instead of the row of
 * per-language buttons that could not grow past three.
 *
 * The list is [AppLanguage] itself — the same declaration that defines the supported languages and their
 * order — so a language can never appear here without also existing as resources, as a locale-config
 * entry and in every localization test. `System language` is a row like the others because it is a
 * choice like the others; it is simply the choice that means "no application locale".
 *
 * Choosing a language hands the decision to the platform, which recreates the activity — so this dialog
 * does not close itself gracefully, it disappears with the screen it belongs to.
 */
@Composable
fun LanguagePickerDialog(
    current: AppLanguage,
    onSelect: (AppLanguage) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
        title = { Text(stringResource(R.string.language)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                AppLanguage.entries.forEach { language ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = language == current,
                                role = Role.RadioButton,
                                onClick = { onSelect(language) }
                            )
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = language == current, onClick = null)
                        Text(
                            text = stringResource(language.labelRes),
                            modifier = Modifier.padding(start = 8.dp),
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }
        }
    )
}
