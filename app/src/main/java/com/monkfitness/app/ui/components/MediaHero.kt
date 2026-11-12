package com.monkfitness.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.monkfitness.app.R
import com.monkfitness.app.animation.AnimationController
import com.monkfitness.app.animation.rememberAnimationController
import com.monkfitness.app.data.model.Exercise
import com.monkfitness.app.data.model.skeletonAnimation
import com.monkfitness.app.poses.PoseRegistry

/**
 * The dominant media hero shared by the workout session and the posture extra workout.
 *
 * One gesture layer (the root [pointerInput]) owns the manual camera rotation: a drag that STARTS in
 * the free band rotates the camera, one that starts on an overlay text zone never does. Later
 * pinch-zoom belongs on this same layer. The rig is framed into the [HeroGeometry.safeBand] between
 * the two overlay zones, so it can never be drawn under the overlay text.
 */
@Composable
fun MediaHero(
    exercise: Exercise?,
    overlay: HeroOverlay,
    modifier: Modifier = Modifier
) {
    // A real engine pose owns a camera and can be rotated; the still-image fallback owns neither.
    val poseConfig = exercise?.let { PoseRegistry.getPoseConfig(it.animationId) }
    val controller: AnimationController? = poseConfig?.let { cfg ->
        rememberAnimationController(metadata = cfg.builder.metadata, alternating = cfg.alternating)
    }
    val heroLabel = stringResource(R.string.exercise_animation_hero)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .semantics { contentDescription = heroLabel }
    ) {
        val heroHeightDp = maxHeight.value
        val zones = HeroGeometry.overlayZones(heroHeightDp, overlay.topDp, overlay.bottomDp)
        val band = HeroGeometry.safeBand(heroHeightDp, zones)
        var rotationAllowed by remember { mutableStateOf(false) }

        // The single gesture layer is on the ROOT (hero-relative coordinates). onDragStart decides
        // once per gesture from the DOWN position; onDrag only rotates when that start was in the free
        // band. A touch on overlay text therefore never rotates, even if the drag continues upward.
        val gesture = Modifier
            .fillMaxSize()
            .pointerInput(controller, heroHeightDp, zones) {
                detectDragGestures(
                    onDragStart = { down -> rotationAllowed = HeroGeometry.isRotationArea(down.y.toDp().value, heroHeightDp, zones) },
                    onDrag = { change, dragAmount ->
                        if (rotationAllowed && controller != null) {
                            change.consume()
                            controller.onRotate(dragAmount.x * HeroGeometry.ROTATION_SENSITIVITY)
                        }
                    }
                )
            }

        // The rig occupies only the safe band: CameraFraming derives the frame for this surface, so
        // the athlete (with its ground contact) is contained within it.
        Box(
            modifier = Modifier
                .offset(y = zones.topDp.dp)
                .height(band.height.dp)
        ) {
            if (exercise != null) {
                val skeletonAnimation = exercise.skeletonAnimation
                if (skeletonAnimation != null) {
                    ExerciseAnimatedVisual(
                        exerciseId = exercise.id,
                        animationId = exercise.animationId,
                        animation = skeletonAnimation,
                        controller = controller,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    HeroStillImage(imageRes = exercise.imageRes)
                }
            } else {
                HeroStillImage(imageRes = null)
            }
        }

        // Overlay zones on scrims; the gesture layer sits above them so a drag starting on text is
        // seen (and rejected) by the same layer, not swallowed by a separate clickable.
        Box(modifier = gesture)

        if (zones.topDp > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(zones.topDp.dp)
                    .align(Alignment.TopCenter)
                    .background(topScrim())
            ) {
                overlay.topContent()
            }
        }
        if (zones.bottomDp > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(zones.bottomDp.dp)
                    .align(Alignment.BottomCenter)
                    .background(bottomScrim())
            ) {
                overlay.bottomContent()
            }
        }
    }
}

@Composable
private fun HeroStillImage(imageRes: Int?) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Image(
            painter = painterResource(imageRes ?: R.drawable.ic_exercise_placeholder),
            contentDescription = null,
            modifier = Modifier.fillMaxSize().padding(16.dp)
        )
    }
}

/** Dark gradient scrims so overlay text keeps adequate contrast over the figure and the surface. */
@Composable
private fun topScrim(): Brush = Brush.verticalGradient(
    colors = listOf(Color(0xCC0A0F14), Color(0x000A0F14))
)

@Composable
private fun bottomScrim(): Brush = Brush.verticalGradient(
    colors = listOf(Color(0x000A0F14), Color(0xCC0A0F14))
)

/**
 * The overlay's content, measured in dp so [HeroGeometry.overlayZones] can cap it. Both composables
 * render inside the capped zone; text beyond the cap is truncated, never grown into the safe band.
 */
data class HeroOverlay(
    val topDp: Float,
    val bottomDp: Float,
    val topContent: @Composable () -> Unit,
    val bottomContent: @Composable () -> Unit
)
