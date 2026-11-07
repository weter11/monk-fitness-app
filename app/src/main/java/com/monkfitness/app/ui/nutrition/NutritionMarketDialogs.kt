package com.monkfitness.app.ui.nutrition

import androidx.annotation.StringRes
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
import com.monkfitness.app.data.model.NutritionLanguage
import com.monkfitness.app.data.model.NutritionMarket

/** The market picker: a list, so a market absent from it can never be selected by accident. */
@Composable
fun NutritionMarketPickerDialog(
    current: NutritionMarket?,
    onSelect: (NutritionMarket) -> Unit,
    onDismiss: () -> Unit
) {
    ChoiceDialog(
        title = stringResource(R.string.nutrition_market),
        options = NutritionMarket.entries.map { market ->
            Choice(market.labelRes, market == current) to { onSelect(market) }
        },
        onDismiss = onDismiss
    )
}

/** Which of a market's own languages the names are written in. */
@Composable
fun NutritionLanguagePickerDialog(
    current: NutritionLanguage?,
    languages: List<NutritionLanguage>,
    onSelect: (NutritionLanguage) -> Unit,
    onDismiss: () -> Unit
) {
    ChoiceDialog(
        title = stringResource(R.string.nutrition_market_language),
        options = languages.map { language ->
            Choice(language.labelRes, language == current) to { onSelect(language) }
        },
        onDismiss = onDismiss
    )
}

private data class Choice(@StringRes val labelRes: Int, val selected: Boolean)

@Composable
private fun ChoiceDialog(
    title: String,
    options: List<Pair<Choice, () -> Unit>>,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                options.forEach { (option, onPick) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected = option.selected, role = Role.RadioButton, onClick = onPick)
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = option.selected, onClick = null)
                        Text(
                            text = stringResource(option.labelRes),
                            modifier = Modifier.padding(start = 8.dp),
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }
        }
    )
}
