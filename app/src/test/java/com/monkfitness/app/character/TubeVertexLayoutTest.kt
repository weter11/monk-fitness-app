package com.monkfitness.app.character

import com.monkfitness.app.anatomy.AnatomyDefinition
import com.monkfitness.app.anatomy.AnatomyDefinitionLoader
import com.monkfitness.app.anatomy.AnatomyFrame
import com.monkfitness.app.anatomy.AnatomySolver
import com.monkfitness.app.anatomy.MuscleLayer
import com.monkfitness.app.animation.Joint
import com.monkfitness.app.animation.SkeletonDefinition
import com.monkfitness.app.animation.SkeletonPose
import com.monkfitness.app.animation.Vector3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Method
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer

/**
 * The upload seam, checked where it can be checked: every assertion here runs on a JVM with heap
 * buffers, because the only thing that needs a device is Filament itself.
 *
 * The four failures these tests exist for are all silent: a rotation dropped (the tubes sit 90° off
 * the body), a scale dropped (they land 247x out, past the far plane, and are culled), a stride off by
 * one (every tube shears), and a required attribute left unplanned (Filament logs a warning and shades
 * the mesh with driver defaults).
 */
class TubeVertexLayoutTest {

    private val definition: AnatomyDefinition = AnatomyDefinitionLoader.load(File(ASSET).readText())
    private val unitsPerMetre = X1Bind.unitsPerMetre(SkeletonDefinition.DEFAULT_ADULT)
    private val mesh = ProceduralMuscleMesh(definition, unitsPerMetre.toDouble())
    private val plan = TubeAttributePlanner.plan(TubeAttributePlanner.UBERSHADER_ATTRIBUTES) as TubeAttributePlan.Supported
    private val layout = TubeVertexLayout(
        plan,
        mesh.vertexCapacity,
        mesh.triangleCapacity * 3
    )

    private val positions = floatBuffer(mesh.vertexCapacity * 3)
    private val uvs = floatBuffer(mesh.vertexCapacity * 2)
    private val colors = ByteBuffer.allocate(mesh.vertexCapacity * 4).order(ByteOrder.nativeOrder())
    private val indices = IntBuffer.allocate(mesh.triangleCapacity * 3)
    private val bounds = MeshBounds()

    /** A field read of an allocated object cannot be optimised away; a local could be. */
    private var sink: Any? = null

    /** Every head carries a straight three-point path, so the layout is measured, not partly skipped. */
    private fun solvedFrame(): AnatomyFrame {
        val frame = AnatomyFrame.of(definition)
        for (slot in 0 until frame.headCount) {
            frame.writePoint(slot, 0f, 0f, 0f)
            frame.writePoint(slot, 0f, 10f, 0f)
            frame.writePoint(slot, 0f, 20f, 0f)
        }
        return frame
    }

    private fun shown() = MuscleSelection(showsTubes = true)

    @Test
    fun theAssetConversionPinsBothRotatedAxes() {
        // The layout's whole job on positions is the basis rotation: the mesh hands it metres and it
        // must not scale them a second time. `toAssetDirection` is that rotation without the scale.
        val binds = X1Bind.bindJoints(SkeletonDefinition.DEFAULT_ADULT)
        val joints = listOf(Joint.SHOULDER_A, Joint.HIP_F, Joint.TOE_B)
        val slot = DynamicGeometrySlot(joints.size, 3)
        for (joint in joints) {
            val engine = binds.getValue(joint)
            slot.pushVertex(engine.x, engine.y, engine.z, 0f, 1f, 0f, 0f, 0f, 1f, 1f, 1f)
        }

        layout.writePositions(slot, positions, bounds)

        joints.forEachIndexed { index, joint ->
            val expected = X1Bind.toAssetDirection(binds.getValue(joint))
            assertEquals("x of $joint", expected.x, positions.get(index * 3), TOLERANCE)
            assertEquals("y of $joint", expected.y, positions.get(index * 3 + 1), TOLERANCE)
            assertEquals("z of $joint", expected.z, positions.get(index * 3 + 2), TOLERANCE)
        }

        // The A side is the character's left, which the asset carries on +X; P is the right, on −X.
        assertTrue("side A must land on the asset's +X", positions.get(0) > 0f)
        assertTrue("side A must land on the asset's +X", positions.get(3) > 0f)
        assertTrue("side P must land on the asset's −X", positions.get(6) < 0f)

        // Control: dropping the rotation leaves engine +Z on the asset's z axis instead of −x.
        assertTrue(
            "the conversion must move engine +Z onto asset −X",
            kotlin.math.abs(positions.get(0) - binds.getValue(Joint.SHOULDER_A).z) > 1e-3f
        )
    }

    @Test
    fun theSolvedMeshUploadsInHumanMetres() {
        // The renderer's world is the asset's, in metres: the pelvis sits at 0.95 m and the camera's far
        // plane is 100 m, so a centre line still in engine units lands ~247x out and is culled away
        // entirely — a failure no count or winding assertion can see.
        val bind = SkeletonPose()
        for ((joint, position) in X1Bind.bindJoints(SkeletonDefinition.DEFAULT_ADULT)) {
            bind.setJoint(joint, Vector3(position.x, position.y, position.z))
        }
        val frame = AnatomyFrame.of(definition)
        AnatomySolver(definition, SkeletonDefinition.DEFAULT_ADULT, unitsPerMetre.toDouble(), bind).solve(bind, frame)

        val slot = mesh.slot()
        assertTrue("the bind stance must solve every head", mesh.write(frame, slot, shown()) > 0)
        layout.writePositions(slot, positions, bounds)

        assertFalse("a solved build must produce a box", bounds.isEmpty)
        assertTrue(
            "uploaded y is ${bounds.minY}..${bounds.maxY} m, which is not a body",
            bounds.minY >= 0f && bounds.maxY <= 1.9f
        )
        assertTrue("uploaded x half extent is ${bounds.halfX} m", bounds.halfX <= 0.5f)
        assertTrue("uploaded z half extent is ${bounds.halfZ} m", bounds.halfZ <= 0.5f)
    }

    @Test
    fun theUploadLayoutMatchesTheDeclaredStrides() {
        val slot = DynamicGeometrySlot(2, 3)
        slot.pushVertex(0f, 0f, 0f, 0f, 1f, 0f, 0.25f, 0.75f, 0.5f, 0.25f, 0f)
        slot.pushVertex(1f, 2f, 3f, 0f, 1f, 0f, 0.5f, 1f, 1f, 1f, 1f)
        slot.pushTriangle(0, 1, 0)

        assertEquals("positions are three floats per vertex", 12, plan.bytesFor(TubeAttribute.POSITION, 1))
        assertEquals(24, plan.bytesFor(TubeAttribute.POSITION, 2))
        assertEquals("indices are four bytes each", 12, layout.indexBytes(3))

        layout.writePositions(slot, positions, bounds)
        layout.writeUvs(slot, uvs)
        layout.writeColors(slot, colors)
        layout.writeIndices(slot, indices)

        assertEquals(0.25f, uvs.get(0), 0f)
        assertEquals(0.75f, uvs.get(1), 0f)
        assertEquals(0.5f, uvs.get(2), 0f)

        assertEquals("half a channel is 128 of 255", 128.toByte(), colors.get(0))
        assertEquals(64.toByte(), colors.get(1))
        assertEquals(0.toByte(), colors.get(2))
        assertEquals("every vertex is opaque", -1, colors.get(3).toInt())

        assertEquals(0, indices.get(0))
        assertEquals(1, indices.get(1))

        val uv1 = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder())
        layout.writeUv1Dummy(uv1, 2)
        assertEquals("the dummy UV set is the 0xFF fill gltfio uses", -1, uv1.get(0).toInt())
    }

    @Test
    fun aRequiredAttributeAlwaysHasABuffer() {
        val supported = TubeAttributePlanner.plan(TubeAttributePlanner.UBERSHADER_ATTRIBUTES)
        assertTrue("the ubershader's own set must be plannable", supported is TubeAttributePlan.Supported)
        supported as TubeAttributePlan.Supported
        assertEquals(
            listOf(TubeAttribute.POSITION, TubeAttribute.UV0, TubeAttribute.UV1, TubeAttribute.COLOR),
            supported.attributes
        )
        assertEquals(listOf(0, 1, 2, 3), supported.slots.map { it.bufferIndex })
        assertEquals(120, supported.bytesFor(TubeAttribute.POSITION, 10))

        val tangents = TubeAttributePlanner.planFromNames(listOf("POSITION", "UV0", "TANGENTS"))
        assertTrue("a tangent-quaternion material is refused, not under-filled", tangents is TubeAttributePlan.Refused)
        assertTrue((tangents as TubeAttributePlan.Refused).reason.contains("TANGENTS"))

        val bones = TubeAttributePlanner.plan(listOf(TubeAttribute.POSITION, TubeAttribute.BONE_INDICES))
        assertTrue(bones is TubeAttributePlan.Refused)
        assertTrue((bones as TubeAttributePlan.Refused).reason.contains("BONE_INDICES"))

        val unknown = TubeAttributePlanner.planFromNames(listOf("POSITION", "CUSTOM0"))
        assertTrue(unknown is TubeAttributePlan.Refused)
        assertTrue((unknown as TubeAttributePlan.Refused).reason.contains("CUSTOM0"))

        val minimal = TubeAttributePlanner.plan(emptyList()) as TubeAttributePlan.Supported
        assertEquals("position is never dropped", listOf(TubeAttribute.POSITION), minimal.attributes)
    }

    @Test
    fun theIndexCountIsWholeTrianglesWithinCapacity() {
        val frame = solvedFrame()
        val slot = mesh.slot()
        val selections = listOf(
            shown(),
            MuscleSelection(showsTubes = true, layers = setOf(MuscleLayer.DEEP)),
            MuscleSelection()
        )
        for (selection in selections) {
            val triangles = mesh.write(frame, slot, selection)
            assertEquals("an index count that is not whole triangles aborts the renderable", 0, slot.indexCount % 3)
            assertTrue(slot.indexCount <= layout.indexCapacity)
            assertTrue(slot.vertexCount <= layout.vertexCapacity)
            assertEquals(slot.triangleCount, triangles)
        }
    }

    @Test
    fun theAabbContainsEveryWrittenVertexAndIsNonEmpty() {
        val frame = solvedFrame()
        val slot = mesh.slot()
        mesh.write(frame, slot, shown())

        layout.writePositions(slot, positions, bounds)

        assertFalse("an empty box aborts a culled renderable", bounds.isEmpty)
        for (vertex in 0 until slot.vertexCount) {
            assertTrue(
                "vertex $vertex escaped its own bounding box",
                bounds.contains(positions.get(vertex * 3), positions.get(vertex * 3 + 1), positions.get(vertex * 3 + 2))
            )
        }

        val nothing = MeshBounds()
        nothing.fallback()
        assertFalse("a build that wrote nothing still needs a box", nothing.isEmpty)
        assertEquals(0f, nothing.centreX, 0f)
        assertTrue(nothing.halfX > 0f && nothing.halfY > 0f && nothing.halfZ > 0f)
    }

    @Test
    fun aNonFiniteVertexCannotPoisonTheBox() {
        // A NaN component compares false against everything, so an unguarded box keeps it as min and
        // max for every later vertex: the AABB then reads as non-empty with NaN extents and culling
        // fails on a box Filament was told was valid.
        val slot = DynamicGeometrySlot(2, 3)
        slot.pushVertex(Float.NaN, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 1f, 1f)
        slot.pushVertex(1f, 2f, 3f, 0f, 1f, 0f, 0f, 0f, 1f, 1f, 1f)

        layout.writePositions(slot, positions, bounds)

        assertFalse(bounds.isEmpty)
        for (component in floatArrayOf(bounds.minX, bounds.minY, bounds.minZ, bounds.maxX, bounds.maxY, bounds.maxZ)) {
            assertTrue("the box carries a non-finite extent $component", component.isFinite())
        }
        assertEquals("the finite vertex alone defines the box", -3f, bounds.minX, 0f)
        assertEquals(1f, bounds.maxZ, 0f)
    }

    @Test
    fun aMinimalPlanExposesNoOptionalSlot() {
        // A material that asks only for position is a supported plan, so the upload path must be able
        // to skip UV0, UV1 and colour instead of asking for a slot the plan never declared.
        val minimal = TubeAttributePlanner.plan(emptyList()) as TubeAttributePlan.Supported
        val minimalLayout = TubeVertexLayout(minimal, 2, 3)

        assertEquals(0, minimalLayout.bufferIndexOf(TubeAttribute.POSITION))
        for (optional in listOf(TubeAttribute.UV0, TubeAttribute.UV1, TubeAttribute.COLOR)) {
            assertNull("$optional must not be declared", minimalLayout.bufferIndexOfOrNull(optional))
        }

        val slot = DynamicGeometrySlot(2, 3)
        slot.pushVertex(0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 1f, 1f)
        slot.pushVertex(1f, 2f, 3f, 0f, 1f, 0f, 0f, 0f, 1f, 1f, 1f)
        slot.pushTriangle(0, 1, 0)

        val box = MeshBounds()
        minimalLayout.writePositions(slot, floatBuffer(slot.vertexCapacity * 3), box)
        assertFalse("a position-only build still needs a box", box.isEmpty)
        assertTrue(box.halfX > 0f && box.halfY > 0f && box.halfZ > 0f)
    }

    @Test
    fun theTendonEndsAreTintedAndTheBellyIsNot() {
        val frame = solvedFrame()
        val slot = mesh.slot()
        mesh.write(frame, slot, shown())

        val palette = SkinPalette.DEFAULT
        val origin = 0
        val middle = (mesh.samples / 2) * mesh.radialSegments
        val insertion = (mesh.samples - 1) * mesh.radialSegments

        assertTrue(
            "the origin end must shade toward the tendon colour",
            slot.colors[origin * 3] > palette.muscleRed
        )
        assertTrue(
            "the insertion end must shade toward the tendon colour",
            slot.colors[insertion * 3] > palette.muscleRed
        )
        assertEquals(
            "the belly keeps the muscle colour",
            palette.muscleRed,
            slot.colors[middle * 3],
            1e-4f
        )
        for (vertex in 0 until slot.vertexCount) {
            for (component in 0..2) {
                val channel = slot.colors[vertex * 3 + component]
                assertTrue("channel $component of vertex $vertex is outside 0..1", channel in 0f..1f)
            }
        }
    }

    @Test
    fun theUploadPathAllocatesNothing() {
        val frame = solvedFrame()
        val slot = mesh.slot()
        mesh.write(frame, slot, shown())
        val counter = allocationCounter()
        repeat(WARMUP) { fillOnce(slot) }

        val baseline = steadyAllocation(counter) { }
        val measured = steadyAllocation(counter) { fillOnce(slot) }

        assertEquals(
            "a frame's upload must not allocate beyond an empty loop: baseline=$baseline measured=$measured",
            baseline,
            measured
        )
        assertTrue("the measured build must have written a mesh", slot.vertexCount > 0)
    }

    @Test
    fun theCounterSeesARealAllocationSoTheComparisonIsNotVacuous() {
        val counter = allocationCounter()
        repeat(WARMUP) { allocateControl() }

        val baseline = steadyAllocation(counter) { }
        val measured = steadyAllocation(counter) { allocateControl() }

        assertTrue("the instrument must see a known allocation", measured > baseline)
    }

    private fun fillOnce(slot: DynamicGeometrySlot) {
        layout.writePositions(slot, positions, bounds)
        layout.writeUvs(slot, uvs)
        layout.writeColors(slot, colors)
        layout.writeIndices(slot, indices)
    }

    private fun allocateControl() {
        sink = IntArray(ARRAY_INTS)
    }

    private inline fun steadyAllocation(counter: AllocationCounter, body: () -> Unit): Long {
        var best = Long.MAX_VALUE
        repeat(WINDOWS) {
            val before = counter.bytes()
            repeat(MEASURED) { body() }
            best = minOf(best, counter.bytes() - before)
        }
        return best
    }

    private fun allocationCounter(): AllocationCounter {
        val factory = runCatching { Class.forName("java.lang.management.ManagementFactory") }.getOrNull()
        val mxType = runCatching { Class.forName("com.sun.management.ThreadMXBean") }.getOrNull()
        assumeTrue("this gate needs HotSpot's thread management bean", factory != null && mxType != null)

        val bean = factory!!.getMethod("getThreadMXBean").invoke(null)
        val mx = mxType!!
        assumeTrue("the thread bean is not a com.sun.management.ThreadMXBean", mx.isInstance(bean))
        val supported = mx.getMethod("isThreadAllocatedMemorySupported").invoke(bean) as Boolean
        assumeTrue("the JVM does not support thread allocation counting", supported)

        val enabled = mx.getMethod("isThreadAllocatedMemoryEnabled").invoke(bean) as Boolean
        if (!enabled) mx.getMethod("setThreadAllocatedMemoryEnabled", java.lang.Boolean.TYPE).invoke(bean, true)
        assumeTrue(
            "thread allocation counting could not be enabled",
            mx.getMethod("isThreadAllocatedMemoryEnabled").invoke(bean) as Boolean
        )
        return AllocationCounter(bean, mx.getMethod("getThreadAllocatedBytes", java.lang.Long.TYPE), Thread.currentThread().id)
    }

    private class AllocationCounter(private val bean: Any, private val getter: Method, private val threadId: Long) {
        fun bytes(): Long = getter.invoke(bean, threadId) as Long
    }

    private fun floatBuffer(floats: Int): FloatBuffer =
        ByteBuffer.allocate(floats * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    private companion object {
        const val ASSET = "src/main/assets/anatomy/anatomy_v1.json"
        const val TOLERANCE = 1e-4f
        const val WARMUP = 200
        const val MEASURED = 300
        const val WINDOWS = 3
        const val ARRAY_INTS = 1024
    }
}
