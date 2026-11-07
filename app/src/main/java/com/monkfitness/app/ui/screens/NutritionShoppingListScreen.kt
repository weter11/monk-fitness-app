package com.monkfitness.app.ui.screens

import android.content.res.Configuration
import android.content.res.Resources
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.monkfitness.app.R
import com.monkfitness.app.data.model.NutritionNaming
import com.monkfitness.app.data.model.NutritionQuantityUnit
import com.monkfitness.app.data.model.NutritionShoppingGroup
import com.monkfitness.app.data.model.NutritionShoppingListItem
import com.monkfitness.app.ui.nutrition.NutritionIngredientLabel
import com.monkfitness.app.ui.nutrition.rememberNutritionNaming
import com.monkfitness.app.viewmodel.MainViewModel
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NutritionShoppingListScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val cycleLength by viewModel.nutritionCycleLength.collectAsState()
    val plan by viewModel.nutritionPlan.collectAsState()
    val storedMarket by viewModel.nutritionMarket.collectAsState()
    val storedLanguage by viewModel.nutritionMarketLanguage.collectAsState()

    val naming = rememberNutritionNaming(storedMarket, storedLanguage)
    val interfaceLanguage = LocalConfiguration.current.locales[0].language
    val english = rememberEnglishResources()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.nutrition_shopping_list)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.previous))
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
            if (cycleLength == 0) {
                Text(
                    text = stringResource(R.string.nutrition_disabled_message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary
                )
            } else {
                Text(
                    text = stringResource(R.string.nutrition_shopping_list_desc_cycle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary
                )
                NutritionShoppingGroup.entries.forEach { group ->
                    val items = plan.shoppingList[group].orEmpty()
                    if (items.isNotEmpty()) {
                        NutritionShoppingGroupCard(
                            group = group,
                            items = items,
                            naming = naming,
                            interfaceLanguage = interfaceLanguage,
                            english = english
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NutritionShoppingGroupCard(
    group: NutritionShoppingGroup,
    items: List<NutritionShoppingListItem>,
    naming: NutritionNaming,
    interfaceLanguage: String,
    english: Resources
) {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = stringResource(group.titleRes), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            items.forEach { item ->
                Text(text = "• ${ingredientLabel(item, naming, interfaceLanguage, english)} — ${quantityText(item)}", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun ingredientLabel(
    item: NutritionShoppingListItem,
    naming: NutritionNaming,
    interfaceLanguage: String,
    english: Resources
): String = NutritionIngredientLabel.of(
    localName = naming.nameOf(item.ingredient),
    interfaceName = stringResource(item.ingredient.nameRes),
    // The bracket exists to name the food, so it is read from the English table rather than from
    // the table the reader is already looking at.
    englishName = english.getString(item.ingredient.nameRes),
    interfaceLanguage = interfaceLanguage
)

/** The app's own English table, whatever language the interface is currently showing. */
@Composable
private fun rememberEnglishResources(): Resources {
    val context = LocalContext.current
    return remember(context) {
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocale(Locale.ENGLISH)
        context.createConfigurationContext(configuration).resources
    }
}

@Composable
private fun quantityText(item: NutritionShoppingListItem): String {
    return when (item.ingredient.unit) {
        NutritionQuantityUnit.GRAMS -> stringResource(R.string.nutrition_quantity_grams, item.totalAmount)
        NutritionQuantityUnit.MILLILITERS -> stringResource(R.string.nutrition_quantity_milliliters, item.totalAmount)
        NutritionQuantityUnit.PIECES -> stringResource(R.string.nutrition_quantity_pieces, item.totalAmount)
    }
}
