package com.monkfitness.app.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.monkfitness.app.R
import com.monkfitness.app.data.model.Exercise
import com.monkfitness.app.data.model.animationProfile
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.animateLottieCompositionAsState
import com.airbnb.lottie.compose.rememberLottieComposition

@Composable
fun MonkButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.primary,
    enabled: Boolean = true
) {
    Button(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp),
        shape = RoundedCornerShape(16.dp),
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            disabledContainerColor = containerColor.copy(alpha = 0.5f)
        ),
        elevation = ButtonDefaults.buttonElevation(
            defaultElevation = 4.dp,
            pressedElevation = 8.dp
        )
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            color = if (enabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.5f),
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun ExerciseVisualContent(
    exercise: Exercise,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    imagePadding: Dp = 16.dp
) {
    val animationProfile = exercise.animationProfile

    if (animationProfile != null) {
        ExerciseAnimatedVisual(
            profile = animationProfile,
            modifier = modifier.padding(imagePadding)
        )
    } else if (exercise.lottieRes != null) {
        val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(exercise.lottieRes))
        val progress by animateLottieCompositionAsState(
            composition = composition,
            isPlaying = composition != null,
            iterations = LottieConstants.IterateForever
        )

        if (composition != null) {
            LottieAnimation(
                composition = composition,
                progress = { progress },
                modifier = modifier.padding(imagePadding)
            )
        } else {
            androidx.compose.foundation.Image(
                painter = painterResource(exercise.imageRes ?: R.drawable.ic_exercise_placeholder),
                contentDescription = null,
                modifier = modifier.padding(imagePadding),
                contentScale = contentScale
            )
        }
    } else {
        androidx.compose.foundation.Image(
            painter = painterResource(exercise.imageRes ?: R.drawable.ic_exercise_placeholder),
            contentDescription = null,
            modifier = modifier.padding(imagePadding),
            contentScale = contentScale
        )
    }
}

@Composable
fun ExerciseThumbnail(
    exercise: Exercise,
    modifier: Modifier = Modifier,
    size: Dp = 64.dp
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        ExerciseVisualContent(
            exercise = exercise,
            modifier = Modifier.fillMaxSize(),
            imagePadding = 8.dp
        )
    }
}

@Composable
fun ExerciseHeroMedia(
    exercise: Exercise,
    modifier: Modifier = Modifier,
    height: Dp = 180.dp
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        ExerciseVisualContent(
            exercise = exercise,
            modifier = Modifier.fillMaxSize(),
            imagePadding = 16.dp
        )
    }
}

@Composable
fun exerciseSummaryText(exercise: Exercise): String {
    return if (exercise.isTimerBased) {
        stringResource(R.string.seconds_format, exercise.durationSeconds)
    } else if (exercise.minReps > 0 && exercise.maxReps > 0 && exercise.minReps != exercise.maxReps) {
        stringResource(R.string.sets_reps_range_format, exercise.sets, exercise.minReps, exercise.maxReps)
    } else {
        stringResource(R.string.sets_reps_format, exercise.sets, exercise.maxReps.coerceAtLeast(exercise.reps))
    }
}

@Composable
fun exerciseRepTargetText(exercise: Exercise): String {
    return when {
        exercise.minReps > 0 && exercise.maxReps > 0 && exercise.minReps != exercise.maxReps ->
            stringResource(R.string.reps_range_format, exercise.minReps, exercise.maxReps)
        exercise.maxReps > 0 ->
            exercise.maxReps.toString()
        else ->
            exercise.reps.toString()
    }
}

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun ExerciseItem(
    exercise: Exercise,
    isCompleted: Boolean,
    onInfo: () -> Unit,
    isCurrent: Boolean = false
) {
    val backgroundColor by animateColorAsState(
        targetValue = when {
            isCompleted -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
            isCurrent -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)
            else -> MaterialTheme.colorScheme.surface
        },
        label = "backgroundColor"
    )

    val borderStroke = if (isCurrent && !isCompleted) {
        BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
    } else null

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        shape = RoundedCornerShape(20.dp),
        color = backgroundColor,
        border = borderStroke,
        shadowElevation = 6.dp
    ) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ExerciseThumbnail(exercise = exercise)

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = stringResource(exercise.nameRes),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = exerciseSummaryText(exercise),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary
                )
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(onClick = onInfo) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = stringResource(R.string.label_description),
                        tint = MaterialTheme.colorScheme.outline
                    )
                }

                Box(
                    modifier = Modifier.size(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (isCompleted) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun MonkProgressIndicator(
    progress: Float,
    modifier: Modifier = Modifier
) {
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = ProgressIndicatorDefaults.ProgressAnimationSpec,
        label = "progress"
    )

    Column(modifier = modifier) {
        LinearProgressIndicator(
            progress = { animatedProgress },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp)),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
    }
}
