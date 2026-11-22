package com.monkfitness.app.character

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which material the tube layer can draw with, decided by what a shader requires rather than by a
 * name.
 *
 * Filament adds `TANGENTS` to the required attributes of every lit shading model
 * (`MaterialBuilder` sets it for anything that is not unlit, whatever textures the material
 * declares), so a lit ubershader permutation can never be declared by a mesh that carries no tangent
 * quaternions. The unlit permutation reports the same `requires` list without that frame, and the
 * planner is still the authority on whether a candidate may be used.
 */
class TubeMaterialSelectionTest {

    /** What the lit permutation reports: the ubershader's `requires` plus the lit tangent frame. */
    private val litPermutation = listOf("POSITION", "UV0", "UV1", "COLOR", "TANGENTS")

    /** What the unlit permutation reports: the same `requires`, and no tangent frame. */
    private val unlitPermutation = listOf("POSITION", "UV0", "UV1", "COLOR")

    @Test
    fun theLitPermutationIsRefusedByNameAndTheUnlitOnePlans() {
        val lit = TubeAttributePlanner.planFromNames(litPermutation)
        assertTrue("a tangent-quaternion material must be refused", lit is TubeAttributePlan.Refused)
        assertTrue((lit as TubeAttributePlan.Refused).reason.contains("TANGENTS"))

        val unlit = TubeAttributePlanner.planFromNames(unlitPermutation)
        assertTrue("the unlit permutation asks only for what a rigid tube fills", unlit is TubeAttributePlan.Supported)
        assertFalse(TubeAttribute.TANGENTS in (unlit as TubeAttributePlan.Supported).attributes)
    }

    @Test
    fun theSelectionSkipsAPermutationThePlannerRefuses() {
        val choice = TubeAttributePlanner.choose(listOf(litPermutation, unlitPermutation))

        assertTrue("a plannable candidate must be found", choice != null)
        assertEquals("the lit permutation must lose the selection", 1, choice!!.candidate)
        assertEquals(
            listOf(TubeAttribute.POSITION, TubeAttribute.UV0, TubeAttribute.UV1, TubeAttribute.COLOR),
            choice.plan.attributes
        )
        assertNull("the selected material declares no tangent slot", choice.plan.bufferIndexOfOrNull(TubeAttribute.TANGENTS))
    }

    @Test
    fun aCandidateListWithNothingFillableSelectsNothing() {
        assertNull(TubeAttributePlanner.choose(listOf(litPermutation)))
        assertNull(TubeAttributePlanner.choose(emptyList()))
        // A material that asks for skinning is refused the same way, and does not win by being first.
        val skinned = listOf("POSITION", "BONE_INDICES", "BONE_WEIGHTS")
        assertNull(TubeAttributePlanner.choose(listOf(skinned, litPermutation)))
    }

    @Test
    fun theSelectedPlanDeclaresTheSlotsItsShaderReadsAndNothingElse() {
        val choice = TubeAttributePlanner.choose(listOf(litPermutation, unlitPermutation))!!
        val layout = TubeVertexLayout(choice.plan, vertexCapacity = 4, indexCapacity = 6)

        assertEquals("one buffer per declared attribute", 4, layout.bufferCount)
        assertEquals(0, layout.bufferIndexOf(TubeAttribute.POSITION))
        assertEquals(1, layout.bufferIndexOf(TubeAttribute.UV0))
        assertEquals(2, layout.bufferIndexOf(TubeAttribute.UV1))
        assertEquals(3, layout.bufferIndexOf(TubeAttribute.COLOR))
        assertEquals("position is three floats per vertex", 12, choice.plan.bytesFor(TubeAttribute.POSITION, 1))
        assertEquals("the dummy UV set is four bytes per vertex", 4, choice.plan.bytesFor(TubeAttribute.UV1, 1))
        assertNull(layout.bufferIndexOfOrNull(TubeAttribute.TANGENTS))
    }
}
