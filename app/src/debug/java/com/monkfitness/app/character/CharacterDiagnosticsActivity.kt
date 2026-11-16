package com.monkfitness.app.character

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val requested = intent.getStringExtra(EXTRA_BACKEND)
            ?.let { name -> BackendRequest.entries.firstOrNull { it.name == name } }
            ?: BackendRequest.AUTO
        setContent {
            MaterialTheme {
                DiagnosticsScreen(requested)
            }
        }
    }

    companion object {
        const val EXTRA_BACKEND = "backend"

        fun intent(context: Context, request: BackendRequest): Intent =
            Intent(context, CharacterDiagnosticsActivity::class.java)
                .putExtra(EXTRA_BACKEND, request.name)
    }
}

private data class ExerciseOption(val animationId: String, val label: String)

private val exercises = listOf(
    ExerciseOption("squat_standard", "Squat"),
    ExerciseOption("pushup_standard", "Push-up"),
    ExerciseOption("plank_standard", "Plank")
)

@Composable
private fun DiagnosticsScreen(request: BackendRequest) {
    val context = LocalContext.current
    val activity = context as? CharacterDiagnosticsActivity
    var exercise by remember { mutableStateOf(exercises.first()) }
    var morphology by remember { mutableStateOf(MorphologyPreset.NEUTRAL) }
    var session by remember { mutableStateOf<CharacterSession?>(null) }
    var status by remember { mutableStateOf("starting") }

    LaunchedEffect(session) {
        while (true) {
            status = session?.diagnostics()?.describe(request) ?: "no character session"
            delay(500)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        CharacterStage(
            animationId = exercise.animationId,
            modifier = Modifier.fillMaxSize(),
            backendRequest = request,
            morphology = morphology,
            onSession = { session = it },
            onUnavailable = { reason -> status = "unavailable: $reason" }
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
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
    error?.let { append('\n').append("error: ").append(it) }
}
