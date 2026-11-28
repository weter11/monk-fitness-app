package com.monkfitness.app.character

import com.google.android.filament.Engine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CharacterBackendTest {

    private fun selector(create: (Engine.Backend) -> Creation<String>) = BackendSelector(create)

    @Test
    fun automaticRequestsOneFilamentChoiceAndReportsWhateverItPicked() {
        val seen = mutableListOf<Engine.Backend>()
        val selection = selector { backend ->
            seen += backend
            Creation("engine:${backend.name}")
        }.select(BackendRequest.AUTO)

        assertEquals(listOf(Engine.Backend.DEFAULT), seen)
        assertEquals("engine:DEFAULT", selection.value)
        assertNull(selection.fallbackReason)
        assertEquals(listOf(BackendAttempt(Engine.Backend.DEFAULT, null)), selection.attempts)
    }

    @Test
    fun anExplicitVulkanAttemptFallsBackToOpenGlEsWhenCreationFails() {
        val selection = selector { backend ->
            if (backend == Engine.Backend.VULKAN) Creation(null, "Vulkan driver rejected the engine")
            else Creation("engine:${backend.name}")
        }.select(BackendRequest.VULKAN)

        assertEquals(listOf(Engine.Backend.VULKAN, Engine.Backend.OPENGL), selection.attempts.map { it.backend })
        assertEquals("engine:OPENGL", selection.value)
        assertEquals("Vulkan driver rejected the engine", selection.fallbackReason)
        assertTrue(selection.usedFallback)
    }

    @Test
    fun vulkanIsReportedOnlyWhenTheEngineItCreatedIsTheVulkanOne() {
        val selection = selector { backend -> Creation("engine:${backend.name}") }
            .select(BackendRequest.VULKAN)

        assertEquals("engine:VULKAN", selection.value)
        assertEquals(1, selection.attempts.size)
        assertNull(selection.fallbackReason)
    }

    @Test
    fun aThrownInitialisationIsAFallbackAndNotACrash() {
        val selection = selector { backend ->
            if (backend == Engine.Backend.VULKAN) throw UnsatisfiedLinkError("libfilament-jni missing")
            else Creation("engine:${backend.name}")
        }.select(BackendRequest.VULKAN)

        assertEquals("engine:OPENGL", selection.value)
        assertTrue(
            "the failure must be recorded verbatim: ${selection.fallbackReason}",
            selection.fallbackReason!!.contains("UnsatisfiedLinkError")
        )
    }

    @Test
    fun productionRequestsVulkanFirstAndPlansOpenGlEsAsItsOnlyFallback() {
        assertEquals(BackendRequest.VULKAN, BackendPolicy.production)
        assertEquals(
            "production must try Vulkan, then the OpenGL ES fallback",
            listOf(Engine.Backend.VULKAN, Engine.Backend.OPENGL),
            BackendPlanner.attempts(BackendPolicy.production)
        )
    }

    @Test
    fun aProductionEngineThatFellBackReportsTheBackendThatActuallyCameUp() {
        val selection = selector { backend ->
            if (backend == Engine.Backend.VULKAN) throw RuntimeException("vkCreateInstance failed")
            Creation(backend.name)
        }.select(BackendPolicy.production)

        assertEquals("OPENGL", selection.value)
        assertEquals("opengl", backendToken(selection.value))
        assertTrue(
            "the rejected Vulkan attempt stays in the report: $selection",
            selection.attempts.first().failure!!.contains("vkCreateInstance")
        )
        assertTrue(selection.usedFallback)
    }

    @Test
    fun aProductionEngineCreatedOnVulkanReportsVulkan() {
        val selection = selector { backend -> Creation(backend.name) }
            .select(BackendPolicy.production)

        assertEquals("VULKAN", selection.value)
        assertEquals("vulkan", backendToken(selection.value))
        assertNull(selection.fallbackReason)
    }

    @Test
    fun engineBackendNamesMapToTheTokenTheTelemetryLinePrints() {
        assertEquals("vulkan", backendToken("VULKAN"))
        assertEquals("opengl", backendToken("OPENGL"))
        assertEquals("unknown", backendToken(null))
    }

    @Test
    fun everyBackendFailingLeavesNoEngineAndTheLastReason() {
        val selection = selector { backend -> Creation(null, "$backend unavailable") }
            .select(BackendRequest.VULKAN)

        assertNull(selection.value)
        assertEquals(2, selection.attempts.size)
        assertEquals("OPENGL unavailable", selection.fallbackReason)
    }

    @Test
    fun packedVulkanVersionsAreDecodedTheWayThePlatformPacksThem() {
        val packed = (1 shl 22) or (1 shl 12)
        assertEquals("1.1.0", GraphicsCapabilities.formatVulkanVersion(packed))
    }
}
