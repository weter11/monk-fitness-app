package com.monkfitness.app.validation

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.monkfitness.app.R
import com.monkfitness.app.animation.*

/**
 * The launch path for a validation pose.
 *
 * This reuses ONLY the shared rendering pipeline:
 * ValidationPoseRegistry -> PoseBuilder -> SkeletonFactory -> build() -> SkeletonPoseFinalizer
 * (inside [SkeletonRenderer]) -> renderer.
 *
 * It does NOT involve workouts, exercise execution, timers, statistics or any training logic.
 * The pose is a frozen snapshot: no animation loop, no interpolation, no MotionDriver.
 */
@Composable
fun ValidationPoseViewer(
    pose: ValidationPose,
    modifier: Modifier = Modifier
) {
    val metadata = pose.builder.metadata
    val definition = SkeletonDefinition.DEFAULT_ADULT

    // Static snapshot: a single frozen frame, no progress animation.
    val poseContext = PoseContext(
        progress = 0.5f,
        side = Side.LEFT,
        definition = definition
    )
    val skeletonPose = pose.builder.build(poseContext)

    val camera = remember(metadata.camera) { Camera(metadata.camera) }
    var yaw by remember(metadata.camera.defaultYaw) { mutableFloatStateOf(metadata.camera.defaultYaw) }
    camera.yaw = yaw

    val style = SkeletonStyle.DEFAULT
    val engine = remember(definition, style) { SkeletonEngine(definition, style) }

    val showGround = metadata.environment.ground.visible

    SkeletonRenderer(
        pose = skeletonPose,
        camera = camera,
        engine = engine,
        environment = metadata.environment,
        showGround = showGround,
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    val sensitivity = 3.14159f / 600f
                    yaw = (yaw + dragAmount.x * sensitivity).let { a ->
                        // keep yaw in a reasonable band
                        if (a > 6.28f) a - 6.28f else if (a < -6.28f) a + 6.28f else a
                    }
                }
            }
    )
}

/**
 * Full-screen viewer shown when a validation pose is opened from the Exercise library.
 *
 * It is a developer tool: it exposes the pose, its purpose bullets and a draggable 3D view.
 * It intentionally avoids the [com.monkfitness.app.ui.screens.ExerciseScreen] (timer,
 * difficulty, progression, etc.).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ValidationPoseScreen(
    pose: ValidationPose?,
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (pose != null) stringResource(pose.nameRes) else stringResource(R.string.validation_category_name),
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.previous)
                        )
                    }
                }
            )
        }
    ) { padding ->
        if (pose == null) {
            Box(modifier = Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.validation_pose_not_found))
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(320.dp),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            ) {
                ValidationPoseViewer(pose = pose)
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = stringResource(pose.descriptionRes),
                style = MaterialTheme.typography.bodyLarge
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.validation_purpose_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.secondary
            )

            Spacer(modifier = Modifier.height(8.dp))

            pose.purposeRes.forEach { resId ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Text("• ", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                    Text(
                        text = stringResource(resId),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.validation_technique_label),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.secondary
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = stringResource(pose.techniqueRes),
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
