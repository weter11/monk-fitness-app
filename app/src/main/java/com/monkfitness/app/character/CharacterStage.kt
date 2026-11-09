package com.monkfitness.app.character

import android.view.TextureView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.monkfitness.app.animation.MotionCurves
import com.monkfitness.app.animation.PoseContext
import com.monkfitness.app.animation.SkeletonDefinition
import com.monkfitness.app.animation.rememberAnimationController
import com.monkfitness.app.poses.PoseRegistry

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

    val attachment = rememberCharacterAttachment(poseSource, backendRequest)

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

    if (attachment.session != null) {
        AndroidView(
            modifier = modifier.fillMaxSize(),
            factory = { context ->
                TextureView(context).also { view ->
                    view.isOpaque = true
                    attachment.session.attach(view)
                }
            }
        )
    }
}

@Composable
private fun rememberCharacterAttachment(
    poseSource: CharacterPoseSource,
    request: BackendRequest
): CharacterAttachment {
    val context = LocalContext.current.applicationContext
    return remember(request, poseSource) {
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
                        poseSource = poseSource
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

