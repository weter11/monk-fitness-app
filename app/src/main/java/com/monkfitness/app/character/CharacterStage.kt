package com.monkfitness.app.character

import android.content.Context
import android.view.TextureView
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.monkfitness.app.R
import com.monkfitness.app.anatomy.MuscleLayer
import com.monkfitness.app.animation.MotionCurves
import com.monkfitness.app.animation.PoseBuilder
import com.monkfitness.app.animation.PoseContext
import com.monkfitness.app.animation.SkeletonDefinition
import com.monkfitness.app.poses.PoseRegistry
import com.monkfitness.app.render.rememberAnimationController
import kotlinx.coroutines.delay
import java.util.Locale

/** Everything one stage's session is built from; a keyed exercise switch produces a new value. */
private class CharacterAttachSpec(
    val request: BackendRequest,
    val poseSource: CharacterPoseSource,
    val poseBuilder: PoseBuilder,
    val alternating: Boolean
)

/** What a claim built: a live session, or why there is none. */
private class CharacterLoad(val session: CharacterSession?, val failure: String?)

private class CharacterAttachment(
    val session: CharacterSession?,
    val failure: String?
) {
    companion object {
        /** The state of a stage whose effect has not claimed the slot yet. */
        val NONE = CharacterAttachment(null, null)
    }
}

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
    // The session arrives after the effect below commits, so the morphology follows the session and
    // not the first composition: keying on it alone would leave the initial preset unapplied.
    LaunchedEffect(attachment.session, morphology) {
        attachment.session?.setMorphology(morphology)
    }

    val session = attachment.session
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(session, lifecycleOwner) {
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
        }
    }

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
                // The stage itself is full-bleed, so the readout owns the status bar inset it sits under.
                MuscleDebugOverlay(
                    session = session,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .statusBarsPadding()
                )
            }
        }
    }
}

/**
 * Dev-only readout for the anatomy layers: the toggles that decide what the procedural pass draws, and
 * each head's modelled length change against the rest stance.
 */
@Composable
private fun MuscleDebugOverlay(
    session: CharacterSession,
    modifier: Modifier = Modifier
) {
    val controller = session.layers
    var diagnostics by remember(session) { mutableStateOf(session.diagnostics()) }
    var selection by remember(session) { mutableStateOf(controller.selection) }
    DisposableEffect(session) {
        val remove = controller.observeSelection {
            diagnostics = session.diagnostics()
            selection = controller.selection
        }
        onDispose(remove)
    }
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
                label = { Text(stringResource(R.string.character_debug_layer_tubes), fontSize = 11.sp) }
            )
            FilterChip(
                selected = selection.showsMeshes,
                onClick = {
                    controller.toggleMeshes()
                    selection = controller.selection
                },
                label = { Text(stringResource(R.string.character_debug_layer_meshes), fontSize = 11.sp) }
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
        val instances = session.muscleInstances
        if (selection.showsMeshes && instances.isNotEmpty()) {
            androidx.compose.foundation.layout.Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                instances.forEachIndexed { section, instance ->
                    val hidden = instance in selection.hidden
                    val picked = instance == selection.selected
                    FilterChip(
                        selected = picked,
                        // One control, three states: select, then hide, then back to plain.
                        onClick = {
                            when {
                                picked -> controller.setInstanceVisible(instance, false)
                                hidden -> controller.setInstanceVisible(instance, true)
                                else -> controller.selectInstance(instance)
                            }
                            selection = controller.selection
                        },
                        label = {
                            Text(
                                text = "$section ${instance.muscleId}·${instance.side.name}",
                                fontSize = 10.sp
                            )
                        }
                    )
                }
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
    if (!diagnostics.musclesSolved) {
        append(diagnostics.muscleHeads).append(" heads · layers hidden — solve skipped")
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
    if (diagnostics.muscleAssetSections > 0 || diagnostics.muscleAssetError != null) {
        append("\nmesh ").append(diagnostics.muscleAssetDrawn).append('/')
        append(diagnostics.muscleAssetSections).append(" sections · ")
        append(diagnostics.muscleAssetUploads).append(" palette uploads")
        if (diagnostics.muscleAssetSelected >= 0) {
            append(" · selected ").append(diagnostics.muscleAssetSelected)
            if (diagnostics.muscleHighlightApplied) append(" · material applied")
        }
        val undrawn = diagnostics.muscleAssetSections - diagnostics.muscleAssetDrawn
        if (undrawn > 0) append(" · not drawn ").append(undrawn)
    }
    diagnostics.muscleAssetError?.let { append("\nmesh: ").append(it) }
    if (diagnostics.muscleSelectedInstance != null) {
        append("\nselected ").append(diagnostics.muscleSelectedInstance)
    }
    if (diagnostics.muscleHiddenInstances.isNotEmpty()) {
        append("\nhidden ").append(diagnostics.muscleHiddenInstances.joinToString(","))
    }
    diagnostics.muscleHighlightError?.let { append("\nhighlight: ").append(it) }
    if (diagnostics.muscleAttributes.isNotBlank()) {
        append("\nattrs ").append(diagnostics.muscleAttributes)
        if (diagnostics.muscleMaterialCandidate >= 0) {
            append(" · candidate ").append(diagnostics.muscleMaterialCandidate)
        }
    }
    diagnostics.muscleMaterialError?.let { append("\ntubes: ").append(it) }
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
    val spec = remember(request, poseSource, poseBuilder, alternating) {
        CharacterAttachSpec(request, poseSource, poseBuilder, alternating)
    }
    var attachment by remember(spec) { mutableStateOf(CharacterAttachment.NONE) }

    // The claim is made here, in a disposal-scoped effect, and not in a remember: on a keyed switch
    // Compose disposes the outgoing stage's effect before this block runs, so the incoming claim can
    // never meet a slot its own predecessor still holds. A claim made during composition ran before
    // that dispose, was refused, and left the new exercise showing the 2D fallback for good.
    DisposableEffect(spec) {
        val claim = ActiveCharacter.claim { loadCharacter(context, spec) }
        val loaded = claim.session
        if (loaded?.session == null) claim.release()
        attachment = CharacterAttachment(
            session = loaded?.session,
            failure = when {
                loaded?.session != null -> null
                !claim.slotWasFree -> "another view already owns the character"
                else -> loaded?.failure ?: "no Filament backend available"
            }
        )
        onDispose {
            attachment = CharacterAttachment.NONE
            loaded?.session?.destroy()
            claim.release()
        }
    }
    return attachment
}

/** Builds the session this stage would draw with; runs inside the claim, so a failure frees nothing. */
private fun loadCharacter(context: Context, spec: CharacterAttachSpec): CharacterLoad {
    return try {
        val capabilities = GraphicsProbe.read(context)
        val selection = FilamentEngines.create(spec.request)
        val engine = selection.value
            ?: return CharacterLoad(null, selection.fallbackReason ?: "no Filament backend available")
        val session = CharacterSession(
            context = context,
            engine = engine,
            request = spec.request,
            selection = selection,
            capabilities = capabilities,
            assetPath = CharacterFeature.ASSET_PATH,
            poseSource = spec.poseSource,
            poseBuilder = spec.poseBuilder,
            alternating = spec.alternating
        )
        val failure = session.load()
        if (failure != null) {
            session.destroy()
            CharacterLoad(null, failure)
        } else {
            CharacterLoad(session, null)
        }
    } catch (error: Throwable) {
        CharacterLoad(null, "${error.javaClass.simpleName}: ${error.message.orEmpty()}")
    }
}
