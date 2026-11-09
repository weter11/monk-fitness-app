package com.monkfitness.app.character

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.google.android.filament.Engine
import com.google.android.filament.Filament

/** What the caller asks the runtime for. Production always uses [AUTO]. */
enum class BackendRequest(val label: String) {
    AUTO("Auto"),
    VULKAN("Vulkan"),
    OPENGL("OpenGL ES")
}

/**
 * Device graphics facts read from the platform. They inform the report and the diagnostic screen;
 * they never stand in for the backend Filament actually selected.
 */
data class GraphicsCapabilities(
    val apiLevel: Int,
    val glEsVersion: String,
    val vulkanAdvertised: Boolean,
    val vulkanHardwareLevel: Int?,
    val vulkanVersion: String?
) {
    val vulkanSummary: String
        get() = when {
            apiLevel < 29 && !vulkanAdvertised -> "not probeable below API 29"
            vulkanAdvertised -> buildString {
                append(vulkanVersion ?: "version unknown")
                vulkanHardwareLevel?.let { append(", level ").append(it) }
            }
            else -> "not advertised"
        }

    companion object {
        /** Packed Vulkan version: major << 22 | minor << 12 | patch, per the Android feature docs. */
        fun formatVulkanVersion(packed: Int): String {
            val major = packed shr 22
            val minor = (packed shr 12) and 0x3ff
            val patch = packed and 0xfff
            return "$major.$minor.$patch"
        }
    }
}

object GraphicsProbe {

    fun read(context: Context): GraphicsCapabilities {
        val activityManager = context.getSystemService(ActivityManager::class.java)
        val packedGles = activityManager?.deviceConfigurationInfo?.reqGlEsVersion ?: 0
        val glEs = "${packedGles shr 16}.${packedGles and 0xffff}"

        val packageManager = context.packageManager
        // FEATURE_VULKAN_HARDWARE_* only exist from API 29; below that the platform cannot tell us
        // anything, and an absent feature is not evidence of absence.
        if (Build.VERSION.SDK_INT < 29) {
            return GraphicsCapabilities(Build.VERSION.SDK_INT, glEs, false, null, null)
        }
        val advertised = packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION)
        val features = packageManager.systemAvailableFeatures
        val version = features.firstOrNull { it.name == PackageManager.FEATURE_VULKAN_HARDWARE_VERSION }?.version
        val level = features.firstOrNull { it.name == PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL }?.version
        return GraphicsCapabilities(
            apiLevel = Build.VERSION.SDK_INT,
            glEsVersion = glEs,
            vulkanAdvertised = advertised,
            vulkanHardwareLevel = level,
            vulkanVersion = version?.let { GraphicsCapabilities.formatVulkanVersion(it) }
        )
    }
}

data class BackendAttempt(val backend: Engine.Backend, val failure: String?)

/** Result of a backend selection: the engine or null, every attempt in order, and why it fell back. */
data class BackendSelection<T>(
    val value: T?,
    val attempts: List<BackendAttempt>,
    val fallbackReason: String?
) {
    val usedFallback: Boolean get() = attempts.size > 1
}

data class Creation<T>(val value: T?, val failure: String? = null)

/** Ordered backends per request; an explicit choice is always attempted, then the fallback. */
object BackendPlanner {

    fun attempts(request: BackendRequest): List<Engine.Backend> = when (request) {
        BackendRequest.AUTO -> listOf(Engine.Backend.DEFAULT)
        BackendRequest.VULKAN -> listOf(Engine.Backend.VULKAN, Engine.Backend.OPENGL)
        BackendRequest.OPENGL -> listOf(Engine.Backend.OPENGL, Engine.Backend.DEFAULT)
    }
}

class BackendSelector<T>(private val create: (Engine.Backend) -> Creation<T>) {

    fun select(request: BackendRequest): BackendSelection<T> {
        val attempts = mutableListOf<BackendAttempt>()
        for (backend in BackendPlanner.attempts(request)) {
            val creation = try {
                create(backend)
            } catch (error: Throwable) {
                // A missing native library or a rejected driver surfaces as an error here, and a
                // fallback is the correct response; a crash on the first render would not be.
                Creation(null, "${error.javaClass.simpleName}: ${error.message.orEmpty()}".trim())
            }
            attempts += BackendAttempt(backend, creation.failure)
            if (creation.value != null) {
                return BackendSelection(
                    value = creation.value,
                    attempts = attempts,
                    fallbackReason = if (attempts.size > 1) attempts.first().failure else null
                )
            }
        }
        return BackendSelection(null, attempts, attempts.lastOrNull()?.failure)
    }
}

object FilamentEngines {

    /** Creates the engine for [request]; a rejected backend throws and the selector falls back. */
    fun create(request: BackendRequest): BackendSelection<Engine> =
        BackendSelector { backend ->
            Filament.init()
            Creation(Engine.create(backend))
        }.select(request)
}
