package com.monkfitness.spike.humanoid

import android.os.Bundle
import android.view.TextureView
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.delay

/**
 * Spike host: a Filament TextureView in Compose, engine frames driven from the real pose pipeline,
 * and just enough controls to prove the character is live (exercise, body shape, pause).
 */
class SpikeActivity : ComponentActivity() {

    private lateinit var scene: HumanoidScene

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A workout screen must not dim mid-set; this is the behaviour the app will need too.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        scene = HumanoidScene(this)
        scene.loadCharacter()
        scene.calibrateScale()

        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) = scene.start()
            override fun onPause(owner: LifecycleOwner) = scene.pause()
        })

        setContent {
            MaterialTheme {
                SpikeUi(scene)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scene.destroy()
    }
}

@Composable
private fun SpikeUi(scene: HumanoidScene) {
    var status by remember { mutableStateOf("loading") }
    var exercise by remember { mutableStateOf(scene.motion.exercise) }
    var shape by remember { mutableStateOf("default") }
    var paused by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            status = "${scene.status} · ${scene.motion.exerciseLabel} · ${scene.deviceCapabilities()}"
            delay(400)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                TextureView(context).also { view ->
                    view.isOpaque = true
                    scene.attach(view)
                }
            }
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color(0xCC0B0F14))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = status,
                color = Color(0xFFB4C8DC),
                fontSize = 11.sp
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MonkMotionDriver.Exercise.entries.forEach { option ->
                    FilterChip(
                        selected = option == exercise,
                        onClick = {
                            exercise = option
                            scene.motion.select(option)
                        },
                        label = { Text(option.label, fontSize = 12.sp) }
                    )
                }
                FilterChip(
                    selected = paused,
                    onClick = {
                        paused = !paused
                        if (paused) scene.pause() else scene.start()
                    },
                    label = { Text(if (paused) "Resume" else "Pause", fontSize = 12.sp) }
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("default", "slim", "heavy", "muscular").forEach { option ->
                    FilterChip(
                        selected = option == shape,
                        onClick = {
                            shape = option
                            scene.applyMorphPreset(scene.morphPreset(option))
                        },
                        label = {
                            Text(
                                option.replaceFirstChar { it.uppercase() },
                                fontSize = 12.sp,
                                fontWeight = if (option == shape) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    )
                }
            }
        }
    }
}
