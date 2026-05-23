package com.monkfitness.app.ui.screens

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.monkfitness.app.R
import com.monkfitness.app.data.model.NutritionQuantityUnit
import com.monkfitness.app.data.model.NutritionShoppingGroup
import com.monkfitness.app.data.model.NutritionShoppingListItem
import com.monkfitness.app.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NutritionShoppingListScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val plan by viewModel.nutritionPlan.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.nutrition_shopping_list)) },
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
                text = stringResource(R.string.nutrition_shopping_list_desc_advanced),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary
            )

            NutritionShoppingGroup.entries.forEach { group ->
                val items = plan.shoppingList[group].orEmpty()
                if (items.isNotEmpty()) {
                    NutritionShoppingGroupCard(group = group, items = items)
                }
            }
        }
    }
}

@Composable
private fun NutritionShoppingGroupCard(
    group: NutritionShoppingGroup,
    items: List<NutritionShoppingListItem>
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(group.titleRes),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            items.forEach { item ->
                Text(
                    text = "• ${stringResource(item.ingredient.nameRes)} — ${quantityText(item)}",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
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
