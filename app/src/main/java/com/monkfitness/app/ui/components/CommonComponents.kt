package com.monkfitness.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.monkfitness.app.R
import com.monkfitness.app.data.model.Exercise

@Composable
fun MonkButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.primary
) {
    Button(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = containerColor)
    ) {
        Text(text = text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimary)
    }
}

@Composable
fun ExerciseItem(
    exercise: Exercise,
    isCompleted: Boolean,
    isCurrent: Boolean,
    onToggle: (Boolean) -> Unit,
    onInfo: () -> Unit
) {
    val checkScale = animateFloatAsState(
        targetValue = if (isCompleted) 1.15f else 1f,
        animationSpec = tween(260, easing = FastOutSlowInEasing),
        label = "checkScale"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = when {
                isCurrent -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.8f)
                isCompleted -> MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                else -> MaterialTheme.colorScheme.surface
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isCurrent) 8.dp else 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                painter = painterResource(id = imageForExercise(exercise.id)),
                contentDescription = null,
                modifier = Modifier.size(72.dp),
                contentScale = ContentScale.Crop
            )

            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text = stringResource(exercise.nameRes), style = MaterialTheme.typography.titleMedium)
                Text(
                    text = if (exercise.isTimerBased) "${exercise.durationSeconds}s" else "${exercise.sets} × ${exercise.reps}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary
                )
                AnimatedVisibility(visible = isCurrent && !isCompleted) {
                    Text("Current exercise", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
            }

            IconButton(onClick = onInfo) { Icon(Icons.Default.Info, contentDescription = "Info") }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Checkbox(
                    checked = isCompleted,
                    onCheckedChange = onToggle,
                    modifier = Modifier.scale(checkScale.value),
                    colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary)
                )
                AnimatedVisibility(isCompleted) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

private fun imageForExercise(exerciseId: String): Int {
    val key = exerciseId.lowercase()
    return when {
        "squat" in key -> R.drawable.exercise_squat
        "push" in key -> R.drawable.exercise_push_up
        "plank" in key -> R.drawable.exercise_plank
        "pull" in key -> R.drawable.exercise_pull_up
        "lunge" in key -> R.drawable.exercise_lunges
        "posture" in key || "hang" in key || "face_pull" in key -> R.drawable.exercise_posture
        else -> R.drawable.exercise_generic
    }
}
