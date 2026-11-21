package com.monkfitness.app.character

import android.view.TextureView
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.monkfitness.app.anatomy.MuscleLayer
import com.monkfitness.app.animation.MotionCurves
import com.monkfitness.app.animation.PoseBuilder
import com.monkfitness.app.animation.PoseContext
import com.monkfitness.app.animation.SkeletonDefinition
import com.monkfitness.app.poses.PoseRegistry
import com.monkfitness.app.render.rememberAnimationController
import kotlinx.coroutines.delay
import java.util.Locale

private class CharacterAttachment(
    val token: Any,
    val session: CharacterSession?,
    val failure: String?
)

/** The live 3D character for one exercise, or the 2D fallback when it cannot start. */
@Composable
fun CharacterStage(
    animationId: String,
    modifier: Modifier = Modifier,
    backendRequest: BackendRequest = BackendRequest.AUTO,
    morphology: MorphologyPreset = MorphologyPreset.NEUTRAL,
    debugLayers: Boolean = false,
    onSession: (CharacterSession?) -> Unit = {},
    onUnavailable: (String) -> Unit = {}
) {
    val poseConfig = remember(animationId) { PoseRegistry.getPoseConfig(animationId) }
    if (poseConfig == null) {
        LaunchedEffect(animationId) { onUnavailable("no pose registered for $animationId") }
        return
    }

    val controller = rememberAnimationController(
        metadata = poseConfig.builder.metadata,
        alternating = poseConfig.alternating
    )
    val definition = SkeletonDefinition.DEFAULT_ADULT
    val poseSource = remember(poseConfig, controller, definition) {
        CharacterPoseSource {
            val progress = MotionCurves.transform(poseConfig.builder.metadata.motionCurve, controller.progress)
            poseConfig.builder.build(PoseContext(progress = progress, side = controller.side, definition = definition))
        }
    }

    val attachment = rememberCharacterAttachment(
        poseSource = poseSource,
        request = backendRequest,
        poseBuilder = poseConfig.builder,
        alternating = poseConfig.alternating
    )

    LaunchedEffect(attachment) {
        attachment.failure?.let(onUnavailable)
    }
    LaunchedEffect(morphology) {
        attachment.session?.setMorphology(morphology)
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(attachment, lifecycleOwner) {
        val session = attachment.session
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> session?.start()
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> session?.pause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) session?.start()
        onSession(session)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            onSession(null)
            session?.destroy()
            ActiveCharacter.release(attachment.token)
        }
    }

    val session = attachment.session
    if (session != null) {
        Box(modifier = modifier.fillMaxSize()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    TextureView(context).also { view ->
                        view.isOpaque = true
                        // UiHelper needs a live SurfaceTexture, which does not exist until the view is in
                        // a window; attaching from the factory hands it a surface that is not there yet.
                        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                            override fun onViewAttachedToWindow(v: View) = session.attach(view)
                            override fun onViewDetachedFromWindow(v: View) = session.detach()
                        })
                    }
                }
            )
            if (debugLayers && CharacterFeature.isEnabled) {
                MuscleDebugOverlay(session = session, modifier = Modifier.align(Alignment.TopStart))
            }
        }
    }
}

/**
 * Dev-only readout for the anatomy layers: the toggles that decide what the procedural pass draws, and
 * each head's modelled length change against the rest stance.
 */
@Composable
private fun MuscleDebugOverlay(session: CharacterSession, modifier: Modifier = Modifier) {
    val controller = session.layers
    var diagnostics by remember(session) { mutableStateOf(session.diagnostics()) }
    var selection by remember(session) { mutableStateOf(controller.selection) }
    LaunchedEffect(session) {
        while (true) {
            diagnostics = session.diagnostics()
            selection = controller.selection
            delay(DEBUG_REFRESH_MILLIS)
        }
    }

    Column(
        modifier = modifier
            .padding(8.dp)
            .background(Color(0xB30B0F14))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(
                selected = selection.showsTubes,
                onClick = {
                    controller.toggleTubes()
                    selection = controller.selection
                },
                label = { Text("tubes", fontSize = 11.sp) }
            )
            MuscleLayer.entries.forEach { layer ->
                FilterChip(
                    selected = selection.showsTubes && layer in selection.layers,
                    onClick = {
                        controller.toggleLayer(layerId(layer))
                        selection = controller.selection
                    },
                    label = { Text(layer.name.lowercase(Locale.US), fontSize = 11.sp) }
                )
            }
        }
        SelectionContainer {
            Text(
                text = describeMuscles(diagnostics),
                color = Color(0xFFB4C8DC),
                fontSize = 10.sp
            )
        }
    }
}

private fun layerId(layer: MuscleLayer): CharacterLayerId = when (layer) {
    MuscleLayer.SUPERFICIAL -> CharacterLayerId.SUPERFICIAL_MUSCLE
    MuscleLayer.DEEP -> CharacterLayerId.DEEP_MUSCLE
}

private fun describeMuscles(diagnostics: CharacterDiagnostics): String = buildString {
    if (diagnostics.muscleHeads == 0) {
        append("anatomy solve not built")
        return@buildString
    }
    append(diagnostics.muscleHeads).append(" heads · ")
    append(diagnostics.muscleHeadsRefused).append(" refused · ")
    append(diagnostics.muscleInstancesDrawn).append(" drawn · ")
    append(diagnostics.muscleTriangles).append(" triangles")
    for (reading in diagnostics.muscleReadings) {
        append('\n').append(reading.head).append(": modelled ")
        append(String.format(Locale.US, "%+.1f%%", reading.relativePathLengthChange * 100f))
        if (!reading.drawn) append(" · not drawn")
    }
}

private const val DEBUG_REFRESH_MILLIS = 500L

@Composable
private fun rememberCharacterAttachment(
    poseSource: CharacterPoseSource,
    request: BackendRequest,
    poseBuilder: PoseBuilder,
    alternating: Boolean
): CharacterAttachment {
    val context = LocalContext.current.applicationContext
    return remember(request, poseSource, poseBuilder, alternating) {
        val token = Any()
        if (!ActiveCharacter.acquire(token)) {
            CharacterAttachment(token, null, "another view already owns the character")
        } else {
            try {
                val capabilities = GraphicsProbe.read(context)
                val selection = FilamentEngines.create(request)
                val engine = selection.value
                if (engine == null) {
                    ActiveCharacter.release(token)
                    CharacterAttachment(token, null, selection.fallbackReason ?: "no Filament backend available")
                } else {
                    val session = CharacterSession(
                        context = context,
                        engine = engine,
                        request = request,
                        selection = selection,
                        capabilities = capabilities,
                        assetPath = CharacterFeature.ASSET_PATH,
                        poseSource = poseSource,
                        poseBuilder = poseBuilder,
                        alternating = alternating
                    )
                    val failure = session.load()
                    if (failure != null) {
                        session.destroy()
                        ActiveCharacter.release(token)
                        CharacterAttachment(token, null, failure)
                    } else {
                        CharacterAttachment(token, session, null)
                    }
                }
            } catch (error: Throwable) {
                ActiveCharacter.release(token)
                CharacterAttachment(token, null, "${error.javaClass.simpleName}: ${error.message.orEmpty()}")
            }
        }
    }
}

