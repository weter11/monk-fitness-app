package com.monkfitness.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.monkfitness.app.R
import com.monkfitness.app.animation.AnimationController
import com.monkfitness.app.animation.Camera
import com.monkfitness.app.animation.CameraFraming
import com.monkfitness.app.animation.FIT_ONLY_ZOOM_CEILING
import com.monkfitness.app.animation.Side
import com.monkfitness.app.animation.SkeletonDefinition
import com.monkfitness.app.animation.SkeletonEngine
import com.monkfitness.app.animation.SkeletonStyle
import com.monkfitness.app.animation.rememberAnimationController
import com.monkfitness.app.data.model.Exercise
import com.monkfitness.app.data.model.skeletonAnimation
import com.monkfitness.app.poses.PoseRegistry

/**
 * The media hero shared by the workout session and the posture extra workout: the exercise drawn
 * across the content width with nothing over it — no name, no timer, no scrim — so the rig is the
 * only thing on the surface and nothing can drift under text.
 *
 * The surface is sized from the fit rather than the other way round: [HeroGeometry.heightDp] gives it
 * the height at which the rig fills [HeroGeometry.RIG_HEIGHT_FRACTION] of it, and the frame is fitted
 * at the pose's authored camera with the fit itself as the ceiling, so the athlete grows with the
 * surface instead of being pinned at the pose's authored zoom. A drag rotates the camera until the
 * hero would crop the figure; a tap opens the expanded demo.
 */
@Composable
fun MediaHero(
    exercise: Exercise?,
    contentHeight: Dp,
    room: Dp,
    modifier: Modifier = Modifier,
    onExpand: (() -> Unit)? = null
) {
    val poseConfig = exercise?.let { PoseRegistry.getPoseConfig(it.animationId) }
    val controller: AnimationController? = poseConfig?.let { config ->
        rememberAnimationController(metadata = config.builder.metadata, alternating = config.alternating)
    }
    val density = LocalDensity.current
    val engine = remember { SkeletonEngine(SkeletonDefinition.DEFAULT_ADULT, SkeletonStyle.DEFAULT) }
    val framing = remember(engine) { CameraFraming(engine) }
    val heroLabel = stringResource(R.string.exercise_animation_hero)
    val expandLabel = stringResource(R.string.programs_session_show_demo)

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val widthPx = with(density) { maxWidth.toPx() }
        val sides = poseConfig?.let { config ->
            if (config.alternating) listOf(Side.RIGHT, Side.LEFT) else listOf(Side.RIGHT)
        }
        // The figure's drawn height when the surface is exactly as wide as the hero: the width fit the
        // frame will use (the box includes the ground contact) applied to the figure alone, which is
        // what fills the hero. Independent of the hero's own height, which is the point.
        val rigHeightPx = remember(poseConfig, widthPx) {
            if (poseConfig != null && sides != null) {
                val extent = framing.rigExtent(
                    camera = Camera(poseConfig.builder.metadata.camera),
                    builder = poseConfig.builder,
                    sides = sides,
                    fitReachableYaws = false,
                    // The margin belongs to the fit, not to the size the figure reads at.
                    withMargin = false
                )
                val widthFit = (widthPx - 2f * extent.stroke) / extent.width
                extent.figureHeight * widthFit + 2f * extent.stroke
            } else {
                0f
            }
        }
        val heroHeight = HeroGeometry.heightDp(
            rigHeightPx = rigHeightPx,
            density = density.density,
            contentHeightDp = contentHeight.value,
            roomDp = room.value
        ).dp
        val heightPx = with(density) { heroHeight.toPx() }

        val frame = remember(poseConfig, widthPx, heightPx) {
            if (poseConfig != null && sides != null) {
                framing.exerciseFrame(
                    camera = Camera(poseConfig.builder.metadata.camera),
                    builder = poseConfig.builder,
                    sides = sides,
                    width = widthPx,
                    height = heightPx,
                    ceiling = FIT_ONLY_ZOOM_CEILING,
                    // The hero stops the drag where the rig would be cropped, so it fits one camera.
                    fitReachableYaws = false
                )
            } else {
                null
            }
        }
        val containedYaw = remember(frame, widthPx, heightPx) {
            if (poseConfig != null && sides != null && frame != null) {
                framing.containedYawLimit(
                    camera = Camera(poseConfig.builder.metadata.camera),
                    builder = poseConfig.builder,
                    sides = sides,
                    frame = frame,
                    width = widthPx,
                    height = heightPx
                )
            } else {
                0f
            }
        }
        if (controller != null && poseConfig != null) {
            val declared = poseConfig.builder.metadata.camera
            val reach = minOf(-declared.minYaw, declared.maxYaw)
            // After layout: the limit describes this surface, and the drag runs on the frame after it.
            SideEffect { controller.yawLimit = HeroGeometry.yawLimit(containedYaw, reach) }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(heroHeight)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .semantics { contentDescription = heroLabel }
                .then(
                    if (onExpand != null) {
                        Modifier.clickable(onClickLabel = expandLabel, onClick = onExpand)
                    } else {
                        Modifier
                    }
                )
        ) {
            val skeletonAnimation = exercise?.skeletonAnimation
            when {
                exercise != null && skeletonAnimation != null && controller != null && frame != null ->
                    ExerciseAnimatedVisual(
                        exerciseId = exercise.id,
                        animationId = exercise.animationId,
                        animation = skeletonAnimation,
                        modifier = Modifier.fillMaxSize(),
                        controller = controller,
                        staticFrame = frame
                    )

                else -> StillImage(imageRes = exercise?.imageRes)
            }

            // The one gesture layer, above the canvas: a drag turns the camera, a tap never reaches it
            // (the root's clickable owns that), and there is no text here to collide with either.
            if (controller != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(controller) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                controller.onRotate(dragAmount.x * HeroGeometry.ROTATION_SENSITIVITY)
                            }
                        }
                )
            }
        }
    }
}

@Composable
private fun StillImage(imageRes: Int?) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Image(
            painter = painterResource(imageRes ?: R.drawable.ic_exercise_placeholder),
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        )
    }
}
