package com.monkfitness.app.ui.screens

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import com.monkfitness.app.R
import com.monkfitness.app.data.model.Exercise
import com.monkfitness.app.data.model.ExerciseCategory
import com.monkfitness.app.data.model.ExerciseSubCategory
import com.monkfitness.app.ui.components.ExerciseThumbnail
import com.monkfitness.app.ui.components.exerciseSummaryText
import com.monkfitness.app.viewmodel.MainViewModel

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun PostureScreen(
    viewModel: MainViewModel,
    onExerciseClick: (Exercise) -> Unit
) {
    val uiState by viewModel.postureUiState.collectAsState()
    val searchQuery by viewModel.postureSearchQuery.collectAsState()
    var isFilterSheetVisible by rememberSaveable { mutableStateOf(false) }
    val filterSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(
            text = stringResource(R.string.exercise_library),
            style = MaterialTheme.typography.titleLarge.copy(
                fontSize = 24.sp,
                lineHeight = 32.sp,
                fontWeight = FontWeight.SemiBold
            )
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.exercise_library_desc),
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = 15.sp,
                lineHeight = 22.sp
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(16.dp))

        val activeFilterCount = remember(uiState.selectedCategory, uiState.selectedSubCategory) {
            listOf(uiState.selectedCategory, uiState.selectedSubCategory).count { it != null }
        }

        ExerciseLibrarySearchBar(
            query = searchQuery,
            onQueryChange = viewModel::setPostureSearchQuery,
            onOpenFilters = { isFilterSheetVisible = true },
            activeFilterCount = activeFilterCount
        )

        Spacer(modifier = Modifier.height(12.dp))

        if (uiState.filteredExercises.isEmpty()) {
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.no_exercises_match),
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = 15.sp,
                        lineHeight = 22.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            PostureExerciseList(
                exercises = uiState.filteredExercises,
                onExerciseClick = onExerciseClick,
                modifier = Modifier.weight(1f)
            )
        }
    }

    if (isFilterSheetVisible) {
        ModalBottomSheet(
            onDismissRequest = { isFilterSheetVisible = false },
            sheetState = filterSheetState
        ) {
            PostureFiltersSheet(
                uiState = uiState,
                onCategorySelected = viewModel::setPostureSelectedCategory,
                onSubCategorySelected = viewModel::setPostureSelectedSubCategory,
                onClearFilters = viewModel::clearPostureFilters
            )
        }
    }
}

@Composable
private fun ExerciseLibrarySearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onOpenFilters: () -> Unit,
    activeFilterCount: Int
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text(stringResource(R.string.search_exercises_placeholder)) },
        leadingIcon = {
            Icon(Icons.Default.Search, contentDescription = null)
        },
        trailingIcon = {
            IconButton(onClick = onOpenFilters) {
                BadgedBox(
                    badge = {
                        if (activeFilterCount > 0) {
                            Badge {
                                Text(activeFilterCount.toString())
                            }
                        }
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = stringResource(R.string.filter_button)
                    )
                }
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(16.dp),
        textStyle = MaterialTheme.typography.bodyLarge.copy(
            fontSize = 15.sp,
            lineHeight = 22.sp
        )
    )
}

@Composable
private fun PostureExerciseList(
    exercises: List<Exercise>,
    onExerciseClick: (Exercise) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(exercises, key = { it.id }) { exercise ->
            CompactExerciseLibraryItem(
                exercise = exercise,
                onClick = { onExerciseClick(exercise) }
            )
        }
    }
}

@Composable
private fun CompactExerciseLibraryItem(
    exercise: Exercise,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ExerciseThumbnail(
                exercise = exercise,
                size = 56.dp
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = stringResource(exercise.nameRes),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = 16.sp,
                        lineHeight = 22.sp,
                        fontWeight = FontWeight.SemiBold
                    ),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = exerciseSummaryText(exercise),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 14.sp,
                        lineHeight = 20.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            FilledTonalIconButton(
                onClick = onClick,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = stringResource(R.string.label_description)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PostureFiltersSheet(
    uiState: com.monkfitness.app.viewmodel.PostureUiState,
    onCategorySelected: (ExerciseCategory?) -> Unit,
    onSubCategorySelected: (ExerciseSubCategory?) -> Unit,
    onClearFilters: () -> Unit
) {
    val hasActiveFilters = uiState.selectedCategory != null || uiState.selectedSubCategory != null

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .navigationBarsPadding()
    ) {
        Text(
            text = stringResource(R.string.filter_button),
            style = MaterialTheme.typography.titleMedium.copy(
                fontSize = 18.sp,
                lineHeight = 24.sp,
                fontWeight = FontWeight.SemiBold
            )
        )

        Spacer(modifier = Modifier.height(16.dp))

        FilterSection(
            title = stringResource(R.string.filter_category),
            allLabel = stringResource(R.string.filter_all_categories),
            selectedOption = uiState.selectedCategory,
            options = ExerciseCategory.entries.map { it.labelRes to it },
            onSelect = onCategorySelected
        )

        Spacer(modifier = Modifier.height(16.dp))

        FilterSection(
            title = stringResource(R.string.filter_body_part),
            allLabel = stringResource(R.string.filter_all_body_parts),
            selectedOption = uiState.selectedSubCategory,
            options = uiState.availableSubCategories.map { it.labelRes to it },
            onSelect = onSubCategorySelected
        )

        if (hasActiveFilters) {
            Spacer(modifier = Modifier.height(16.dp))

            TextButton(
                onClick = onClearFilters,
                modifier = Modifier.align(Alignment.End)
            ) {
                Text(
                    text = stringResource(R.string.clear_filters),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> FilterSection(
    title: String,
    allLabel: String,
    selectedOption: T?,
    options: List<Pair<Int, T>>,
    onSelect: (T?) -> Unit
) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium.copy(
                fontSize = 17.sp,
                lineHeight = 24.sp,
                fontWeight = FontWeight.SemiBold
            )
        )
        Spacer(modifier = Modifier.height(8.dp))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = selectedOption == null,
                onClick = { onSelect(null) },
                label = {
                    Text(
                        text = allLabel,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            )
            options.forEach { (labelRes, value) ->
                FilterChip(
                    selected = selectedOption == value,
                    onClick = { onSelect(value) },
                    label = {
                        Text(
                            text = stringResource(labelRes),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                )
            }
        }
    }
}
