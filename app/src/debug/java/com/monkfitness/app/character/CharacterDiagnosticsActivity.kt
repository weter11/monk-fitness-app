package com.monkfitness.app.character

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.monkfitness.app.anatomy.MuscleLayer
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * Debug-only host for device validation: backend selection, exercise and morphology switches, and
 * the diagnostics the checklist asks the tester to record.
 *
 * Changing the backend recreates the activity (and therefore the engine) because a Filament engine's
 * backend is fixed at creation; that recreation is itself one of the lifecycle cases under test.
 */
class CharacterDiagnosticsActivity : ComponentActivity() {

    /** The last action `am start --activity-single-top` delivered, applied live to the running session. */
    private val command = mutableStateOf<DebugCommand?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val requested = intent.getStringExtra(EXTRA_BACKEND)
            ?.let { name -> BackendRequest.entries.firstOrNull { it.name == name } }
            ?: BackendRequest.AUTO
        command.value = DebugCommand.parse(intent, 0)
        setContent {
            MaterialTheme {
                DiagnosticsScreen(requested, command)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        command.value = DebugCommand.parse(intent, (command.value?.id ?: 0) + 1)
    }

    companion object {
        const val EXTRA_BACKEND = "backend"

        fun intent(context: Context, request: BackendRequest): Intent =
            Intent(context, CharacterDiagnosticsActivity::class.java)
                .putExtra(EXTRA_BACKEND, request.name)
    }
}

/**
 * One device-run instruction, so a selection or a hide can be driven from `am` without a UI dump
 * while frames are being drawn. The activity must be started with `--activity-single-top`, otherwise
 * the extras recreate it and the process under test is not the one that receives the action.
 */
private data class DebugCommand(val id: Int, val action: String, val instance: String?) {

    companion object {
        const val EXTRA_ACTION = "action"
        const val EXTRA_INSTANCE = "instance"

        fun parse(intent: Intent, id: Int): DebugCommand? {
            val action = intent.getStringExtra(EXTRA_ACTION) ?: return null
            return DebugCommand(id, action, intent.getStringExtra(EXTRA_INSTANCE))
        }
    }
}

private data class ExerciseOption(val animationId: String, val label: String)

/** A layer name accepted by the `layer` debug action, or null when the token names no layer. */
private fun muscleLayer(name: String?): MuscleLayer? =
    MuscleLayer.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }

private val exercises = listOf(
    ExerciseOption("squat_standard", "Squat"),
    ExerciseOption("pushup_standard", "Push-up"),
    ExerciseOption("plank_standard", "Plank")
)

@Composable
private fun DiagnosticsScreen(request: BackendRequest, command: State<DebugCommand?>) {
    val context = LocalContext.current
    val activity = context as? CharacterDiagnosticsActivity
    var exercise by remember { mutableStateOf(exercises.first()) }
    var morphology by remember { mutableStateOf(MorphologyPreset.NEUTRAL) }
    var session by remember { mutableStateOf<CharacterSession?>(null) }
    var status by remember { mutableStateOf("starting") }
    var overlay by remember { mutableStateOf(true) }
    // A command is applied once; a session that is rebuilt later must not replay the last toggle.
    var appliedCommand by remember { mutableStateOf(-1) }

    LaunchedEffect(session) {
        while (true) {
            val current = session?.diagnostics()
            status = current?.describe(request) ?: "no character session"
            current?.let { Log.i(TAG, it.telemetry()) }
            delay(500)
        }
    }
    LaunchedEffect(session, command.value?.id) {
        val target = session ?: return@LaunchedEffect
        val instruction = command.value ?: return@LaunchedEffect
        if (instruction.id == appliedCommand) return@LaunchedEffect
        appliedCommand = instruction.id
        val instance = instruction.instance?.let { wanted ->
            target.muscleInstances.firstOrNull { "${it.muscleId}:${it.side.name}" == wanted }
        }
        val applied = when (instruction.action) {
            "select" -> instruction.instance == null || instance != null
            "hide" -> instance != null
            "show" -> instance != null
            "exercise" -> exercises.any { it.animationId == instruction.instance }
            "layer" -> muscleLayer(instruction.instance) != null
            "meshes", "tubes", "pause", "resume", "step", "overlay" -> true
            else -> false
        }
        when (instruction.action) {
            "select" -> target.layers.selectInstance(instance)
            "hide" -> instance?.let { target.layers.setInstanceVisible(it, false) }
            "show" -> instance?.let { target.layers.setInstanceVisible(it, true) }
            "exercise" -> exercises.firstOrNull { it.animationId == instruction.instance }
                ?.let { exercise = it }
            // Each partition has its own toggle, so a run can prove one skin draws without the other.
            "layer" -> muscleLayer(instruction.instance)
                ?.let { target.layers.toggleLayer(CharacterLayerId.of(it)) }
            "meshes" -> target.layers.toggleMeshes()
            "tubes" -> target.layers.toggleTubes()
            "overlay" -> overlay = !overlay
            // A paused session keeps its last frame on screen, so two screenshots can be taken of the
            // same pose and a material change is not confused with the pose moving. [step] runs a
            // fixed 120 ms window, so both screenshots of a pair advance the pose by the same span.
            "pause" -> target.pause()
            "resume" -> target.start()
            "step" -> {
                target.start()
                Handler(Looper.getMainLooper()).postDelayed({ target.pause() }, STEP_MILLIS)
            }
        }
        Log.i(TAG, "action=${instruction.action} instance=${instruction.instance ?: "-"} applied=$applied")
    }

    Box(modifier = Modifier.fillMaxSize()) {
        CharacterStage(
            animationId = exercise.animationId,
            modifier = Modifier.fillMaxSize(),
            backendRequest = request,
            morphology = morphology,
            debugLayers = overlay,
            onSession = { session = it },
            onUnavailable = { reason -> status = "unavailable: $reason" }
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                // The stage is deliberately full-bleed; this overlay is the part a gesture bar or a
                // landscape cutout would otherwise sit on top of.
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)
                )
                .background(Color(0xE60B0F14))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            SelectionContainer {
                Text(status, color = Color(0xFFB4C8DC), fontSize = 11.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BackendRequest.entries.forEach { option ->
                    FilterChip(
                        selected = option == request,
                        onClick = {
                            activity?.startActivity(CharacterDiagnosticsActivity.intent(activity, option))
                            activity?.finish()
                        },
                        label = { Text(option.label, fontSize = 12.sp) }
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                exercises.forEach { option ->
                    FilterChip(
                        selected = option == exercise,
                        onClick = { exercise = option },
                        label = { Text(option.label, fontSize = 12.sp) }
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MorphologyPreset.entries.forEach { option ->
                    FilterChip(
                        selected = option == morphology,
                        onClick = { morphology = option },
                        label = { Text(option.id, fontSize = 12.sp) }
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { activity?.recreate() }) {
                    Text("Recreate activity", fontSize = 12.sp)
                }
                Button(onClick = {
                    activity?.startActivity(CharacterDiagnosticsActivity.intent(activity, request))
                    activity?.finish()
                }) {
                    Text("Rebuild engine", fontSize = 12.sp)
                }
            }
        }
    }
}

private fun CharacterDiagnostics.describe(request: BackendRequest): String = buildString {
    append("requested ").append(request.label)
    append(" → active ").append(activeBackend)
    if (attempts.size > 1) {
        append(" (fallback: ")
        attempts.dropLast(1).forEach { append(it.backend).append(" failed: ").append(it.failure).append("; ") }
        append(")")
    }
    append('\n').append("device API ").append(capabilities.apiLevel)
    append(" · GLES ").append(capabilities.glEsVersion)
    append(" · Vulkan ").append(capabilities.vulkanSummary)
    append('\n').append(joints).append(" bones · ").append(morphTargets).append(" morphs · ").append(morphology)
    append('\n').append(frames).append(" frames · ").append(fps.toInt()).append(" fps · p95 ")
    append(String.format(Locale.US, "%.2f", frameTimeP95Ms)).append(" ms")
    append(" · surface ").append(hasSurface).append(" · running ").append(running)
    if (frameStages.isNotEmpty()) {
        append('\n').append("stage p50/p95 ms:")
        for (reading in frameStages) {
            append(' ').append(reading.stage).append(' ')
            append(String.format(Locale.US, "%.2f", reading.p50Ms)).append('/')
            append(String.format(Locale.US, "%.2f", reading.p95Ms))
        }
    }
    if (!musclesSolved) {
        append('\n').append("muscle layers hidden — solve skipped")
    } else if (anatomyPassCounts.isNotEmpty()) {
        append('\n').append("passes (cap ").append(anatomyPassCap).append("):")
        anatomyPassCounts.forEachIndexed { pass, count ->
            append(' ').append(pass).append(':').append(count)
        }
    }
    if (muscleTubeBuilds > 0) append(" · ").append(muscleTubeBuilds).append(" primitive build(s)")
    error?.let { append('\n').append("error: ").append(it) }
}

/** One machine-readable line per poll: the readings a device run transcribes into the record. */
private fun CharacterDiagnostics.telemetry(): String = buildString {
    append("frames=").append(frames)
    append(" fps=").append(fps.toInt())
    append(" p95=").append(fixed(frameTimeP95Ms))
    append(" mesh=").append(muscleAssetDrawn).append('/').append(muscleAssetSections)
    append(" layers=").append(muscleAssetLayers.joinToString(",").ifEmpty { "-" })
    append(" solved=").append(musclesSolved)
    append(" selected=").append(muscleSelectedInstance ?: "-")
    append(" highlight=").append(muscleHighlightApplied)
    append(" hidden=").append(muscleHiddenInstances.joinToString(",").ifEmpty { "-" })
    for (stage in TRACKED_STAGES) {
        val reading = frameStages.firstOrNull { it.stage == stage } ?: continue
        append(' ').append(stage).append('=').append(fixed(reading.p50Ms))
        append('/').append(fixed(reading.p95Ms))
    }
}

private fun fixed(value: Float): String = String.format(Locale.US, "%.2f", value)

private val TRACKED_STAGES = listOf("anatomyFrames", "anatomyPaths", "muscleSkin")

/** One screenshot pair advances the pose by this window, so both frames of the pair share it. */
private const val STEP_MILLIS = 120L

private const val TAG = "CharacterDevice"
