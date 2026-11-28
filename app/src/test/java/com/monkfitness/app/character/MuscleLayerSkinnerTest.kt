package com.monkfitness.app.character

import com.monkfitness.app.anatomy.AnatomyDefinition
import com.monkfitness.app.anatomy.AnatomyFrame
import com.monkfitness.app.anatomy.AnatomyPoseFixture
import com.monkfitness.app.anatomy.AnatomySolver
import com.monkfitness.app.anatomy.MuscleHeadId
import com.monkfitness.app.anatomy.MuscleLayer
import com.monkfitness.app.anatomy.PathStatus
import com.monkfitness.app.animation.SkeletonDefinition
import com.monkfitness.app.animation.Vector3
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The skinner is the only bridge from the solved path to a skinned mesh, so it is checked against the
 * exported bind on both ends: at the rest stance every helper matrix must be the identity, and on any
 * other path the helper must carry its own bind point onto its station and turn its bind axes onto
 * the path's own direction.
 *
 * Both partitions are skinned from the same frame, so every case runs over both of them: a skinner
 * that wrote a head the other partition binds would corrupt a skin it does not own.
 */
class MuscleLayerSkinnerTest {

    // The skinned mesh can only draw sections its own GLB declares, so the fixture is the exported
    // atlas rather than the authored library; see MuscleExportFixture.
    private val definition: AnatomyDefinition = MuscleExportFixture.definition()
    private val skeleton = SkeletonDefinition.DEFAULT_ADULT
    private val unitsPerMetre = X1Bind.unitsPerMetre(skeleton)
    private val frame = AnatomyFrame.of(definition)
    private val binds: Map<MuscleLayer, HelperBinds> = MuscleExportFixture.LAYERS.associateWith {
        MuscleExportFixture.binds(it)
    }
    private val skinner: Map<MuscleLayer, MuscleLayerSkinner> = MuscleExportFixture.LAYERS.associateWith { layer ->
        MuscleLayerSkinner(
            rig = skinningRig(binds.getValue(layer)),
            binds = binds.getValue(layer),
            heads = frame.heads,
            unitsPerMetre = unitsPerMetre,
            pointCapacity = definition.sampling.maxPointsPerHead
        )
    }

    /** A base palette whose entries are all distinguishable, so a copy cannot pass for a computation. */
    private val body = FloatArray(binds.getValue(MuscleLayer.SUPERFICIAL).skinJoints * 16) { index -> index.toFloat() }

    private fun out(layer: MuscleLayer): FloatArray = FloatArray(binds.getValue(layer).palette * 16)

    @Test
    fun eachPartitionIsTheBaseRangePlusOneMatrixPerItsOwnHelpers() {
        assertEquals(34 + 208, skinner.getValue(MuscleLayer.SUPERFICIAL).paletteCount)
        assertEquals(34 + 80, skinner.getValue(MuscleLayer.DEEP).paletteCount)
        for ((layer, own) in skinner) {
            assertEquals(binds.getValue(layer).palette, own.paletteCount)
            assertEquals(binds.getValue(layer).helperCount, own.helperCount)
            assertEquals(binds.getValue(layer).skinJoints, own.skinJoints)
            assertTrue(
                "$layer must fit the renderable's bindable limit",
                own.paletteCount <= HelperBindManifest.PALETTE_LIMIT
            )
        }
        assertEquals(
            "the two palettes must cover the whole atlas without sharing a helper",
            288,
            binds.values.sumOf { it.helperCount }
        )
    }

    @Test
    fun eachSkinBindsOnlyTheHeadsItsOwnPartitionCarries() {
        for ((layer, own) in binds) {
            val bound = frame.heads.count { own.groupOf(it) >= 0 }
            assertEquals(
                "$layer binds ${own.helperCount / own.stationCount} heads, not $bound",
                own.helperCount / own.stationCount,
                bound
            )
            assertEquals(
                "${frame.headCount} frame heads must split across the partitions",
                frame.headCount,
                binds.values.sumOf { other -> frame.heads.count { other.groupOf(it) >= 0 } }
            )
        }
    }

    @Test
    fun theBaseRangeIsTheBodyPaletteUnchanged() {
        solveRest()
        for ((layer, own) in skinner) {
            val palette = out(layer)
            own.write(frame, body, palette)
            assertArrayEquals(
                "$layer must not touch the rig's own range",
                body.copyOfRange(0, own.skinJoints * 16),
                palette.copyOfRange(0, own.skinJoints * 16),
                0f
            )
        }
    }

    @Test
    fun theRestPoseReproducesEveryHelperBindAsTheIdentity() {
        solveRest()
        for ((layer, own) in skinner) {
            val palette = out(layer)
            own.write(frame, body, palette)
            var worst = 0f
            for (helper in 0 until own.helperCount) {
                val offset = binds.getValue(layer).paletteIndex(helper) * 16
                for (entry in 0 until 16) {
                    val delta = abs(palette[offset + entry] - IDENTITY[entry])
                    worst = maxOf(worst, delta)
                }
            }
            assertTrue("$layer's worst rest-pose deviation is $worst", worst <= 1e-3f)
        }
    }

    @Test
    fun eachHelperCarriesItsBindPointAndAxesOntoItsStation() {
        val posed = syntheticFrame()
        for ((layer, own) in skinner) {
            val palette = out(layer)
            own.write(posed, body, palette)
            val ownBinds = binds.getValue(layer)
            var worstPoint = 0f
            var worstAxis = 0f
            for (helper in 0 until ownBinds.helperCount) {
                val bind = ownBinds.binds[helper]
                val slot = slotOf(posed, bind.head)
                val (point, tangent) = stationFrame(posed, slot, bind.station)
                val expected = toAsset(point)
                val offset = ownBinds.paletteIndex(helper) * 16

                val carried = apply(palette, offset, bind.position)
                worstPoint = maxOf(worstPoint, distance(expected, carried))

                val expectedAxes = toAssetBasis(basisRows(tangent))
                for (axis in 0 until 3) {
                    val bindAxis = floatArrayOf(
                        bind.basis[axis * 3], bind.basis[axis * 3 + 1], bind.basis[axis * 3 + 2]
                    )
                    val actual = rotate(palette, offset, bindAxis)
                    val expectedAxis = floatArrayOf(
                        expectedAxes[axis * 3], expectedAxes[axis * 3 + 1], expectedAxes[axis * 3 + 2]
                    )
                    worstAxis = maxOf(worstAxis, distance(expectedAxis, actual))
                }
            }
            assertTrue("$layer carried a bind $worstPoint m off its station", worstPoint <= 1e-4f)
            assertTrue("$layer's axes ended up $worstAxis off the path direction", worstAxis <= 1e-4f)
        }
    }

    @Test
    fun everyHelperMatrixIsARigidTransform() {
        solveRest()
        for ((layer, own) in skinner) {
            val palette = out(layer)
            own.write(frame, body, palette)
            for (helper in 0 until own.helperCount) {
                val offset = binds.getValue(layer).paletteIndex(helper) * 16
                for (column in 0 until 3) {
                    val length = sqrt(
                        palette[offset + column * 4] * palette[offset + column * 4] +
                            palette[offset + column * 4 + 1] * palette[offset + column * 4 + 1] +
                            palette[offset + column * 4 + 2] * palette[offset + column * 4 + 2]
                    )
                    // §7.6: a helper scales by exactly 1, so a muscle can move but never stretch.
                    assertEquals("$layer helper $helper column $column is not unit", 1f, length, 1e-5f)
                }
                assertEquals("$layer helper $helper is not affine", 1f, palette[offset + 15], 0f)
                assertEquals("$layer helper $helper is mirrored or scaled", 1f, determinant(palette, offset), 1e-4f)
            }
        }
    }

    @Test
    fun aHeadWithoutAPathGetsTheIdentityRatherThanAStaleFrame() {
        val posed = syntheticFrame()
        val before = MuscleExportFixture.LAYERS.associateWith { layer ->
            out(layer).also { skinner.getValue(layer).write(posed, body, it) }
        }

        // Refused: no path this frame, so every helper must stop carrying last frame's pose.
        posed.clear()
        for ((layer, own) in skinner) {
            val palette = out(layer)
            own.write(posed, body, palette)
            var moved = 0
            for (helper in 0 until own.helperCount) {
                val offset = binds.getValue(layer).paletteIndex(helper) * 16
                for (entry in 0 until 16) {
                    assertEquals("$layer helper $helper entry $entry is stale", IDENTITY[entry], palette[offset + entry], 0f)
                    assertTrue("$layer helper $helper entry $entry is not finite", palette[offset + entry].isFinite())
                    if (before.getValue(layer)[offset + entry] != palette[offset + entry]) moved++
                }
            }
            assertTrue("$layer must move every helper off the pose it had", moved > 0)
        }
    }

    @Test
    fun oneSkinNeverWritesAHeadTheOtherPartitionBinds() {
        val posed = syntheticFrame()
        val deep = skinner.getValue(MuscleLayer.DEEP)
        val deepBinds = binds.getValue(MuscleLayer.DEEP)
        val supBinds = binds.getValue(MuscleLayer.SUPERFICIAL)
        val palette = out(MuscleLayer.DEEP)
        deep.write(posed, body, palette)
        val before = palette.copyOf()

        // A superficial head refused this frame: the deep palette owns none of its helpers, so not one
        // entry of it may move.
        val foreign = posed.heads.first { supBinds.groupOf(it) >= 0 && deepBinds.groupOf(it) < 0 }
        val slot = posed.heads.indexOf(foreign)
        posed.statuses[slot] = PathStatus.INVALID.ordinal
        posed.pointCounts[slot] = 0

        deep.write(posed, body, palette)
        assertArrayEquals("$foreign belongs to the other skin", before, palette, 0f)
    }

    @Test
    fun theAnatomyFrameIsReadOnlyToTheSkinner() {
        val posed = syntheticFrame()
        val paths = posed.paths.copyOf()
        val counts = posed.pointCounts.copyOf()
        val statuses = posed.statuses.copyOf()
        val lengths = posed.modelledPathLengths.copyOf()

        for ((layer, own) in skinner) own.write(posed, body, out(layer))

        assertArrayEquals("the solver owns the path buffer", paths, posed.paths, 0f)
        assertArrayEquals(counts, posed.pointCounts)
        assertArrayEquals(statuses, posed.statuses)
        assertArrayEquals(lengths, posed.modelledPathLengths, 0f)
    }

    @Test
    fun aFrameOfAnotherDefinitionIsRefusedRatherThanHalfWritten() {
        val shorter = AnatomyFrame(
            headIds = frame.heads.dropLast(1),
            muscles = definition.muscles.associateBy { it.id },
            pointCapacity = definition.sampling.maxPointsPerHead,
            landmarkCount = 0
        )
        for ((layer, own) in skinner) {
            try {
                own.write(shorter, body, out(layer))
                throw AssertionError("$layer accepted a frame with the wrong head count")
            } catch (expected: IllegalArgumentException) {
                assertTrue("${expected.message}", expected.message?.contains("heads") == true)
            }
        }
    }

    private fun solveRest() {
        val solver = AnatomySolver(definition, skeleton, unitsPerMetre.toDouble(), AnatomyPoseFixture.bindPose(skeleton))
        try {
            solver.solve(AnatomyPoseFixture.bindPose(skeleton), frame)
        } finally {
            solver.close()
        }
        assertEquals(
            "the rest stance must solve every head, or this is not the bind it claims",
            frame.headCount,
            (0 until frame.headCount).count { frame.statuses[it] == PathStatus.VALID.ordinal }
        )
    }

    private fun syntheticFrame(): AnatomyFrame {
        val posed = AnatomyFrame.of(definition)
        for (slot in 0 until posed.headCount) {
            posed.writePoint(slot, 0f, slot.toFloat(), 0f)
            posed.writePoint(slot, 1f, slot + 2f, 0.5f)
            posed.writePoint(slot, 3f, slot + 3f, 1f)
            posed.statuses[slot] = PathStatus.VALID.ordinal
        }
        return posed
    }

    private fun slotOf(posed: AnatomyFrame, head: MuscleHeadId): Int {
        val slot = posed.heads.indexOf(head)
        assertTrue("$head is not a slot of this definition", slot >= 0)
        return slot
    }

    /** Engine-space point and unit tangent at [station] by arc length: the exporter's own sample. */
    private fun stationFrame(posed: AnatomyFrame, slot: Int, station: Float): Pair<FloatArray, FloatArray> {
        val count = posed.pointCounts[slot]
        val cumulative = DoubleArray(count)
        for (index in 1 until count) {
            var sum = 0.0
            for (component in 0..2) {
                val delta = (
                    posed.pointComponent(slot, index, component) - posed.pointComponent(slot, index - 1, component)
                    ).toDouble()
                sum += delta * delta
            }
            cumulative[index] = cumulative[index - 1] + sqrt(sum)
        }
        val target = cumulative[count - 1] * station.toDouble()
        var segment = 0
        while (segment < count - 2 && cumulative[segment + 1] < target) segment++
        val span = cumulative[segment + 1] - cumulative[segment]
        val u = if (span > 0.0) ((target - cumulative[segment]) / span).coerceIn(0.0, 1.0) else 0.0
        val point = FloatArray(3) { component ->
            val from = posed.pointComponent(slot, segment, component).toDouble()
            val to = posed.pointComponent(slot, segment + 1, component).toDouble()
            (from + (to - from) * u).toFloat()
        }
        var tangent = FloatArray(3) { component ->
            posed.pointComponent(slot, segment + 1, component) - posed.pointComponent(slot, segment, component)
        }
        var length = sqrt(tangent[0] * tangent[0] + tangent[1] * tangent[1] + tangent[2] * tangent[2])
        if (!(length > 0f)) {
            tangent = FloatArray(3) { component ->
                posed.pointComponent(slot, count - 1, component) - posed.pointComponent(slot, 0, component)
            }
            length = sqrt(tangent[0] * tangent[0] + tangent[1] * tangent[1] + tangent[2] * tangent[2])
        }
        if (length > 0f) for (component in 0..2) tangent[component] /= length
        return point to tangent
    }

    private fun basisRows(tangent: FloatArray): FloatArray {
        val up = if (abs(tangent[1]) < 0.9f) floatArrayOf(0f, 1f, 0f) else floatArrayOf(1f, 0f, 0f)
        var z = cross(tangent, up)
        val length = sqrt(z[0] * z[0] + z[1] * z[1] + z[2] * z[2])
        if (length > 0f) z = floatArrayOf(z[0] / length, z[1] / length, z[2] / length)
        val x = cross(tangent, z)
        return floatArrayOf(x[0], x[1], x[2], tangent[0], tangent[1], tangent[2], z[0], z[1], z[2])
    }

    private fun toAsset(engine: FloatArray): FloatArray {
        val out = FloatArray(3)
        X1Bind.toAssetInto(Vector3(engine[0], engine[1], engine[2]), unitsPerMetre, out)
        return out
    }

    private fun toAssetBasis(rows: FloatArray): FloatArray {
        val out = FloatArray(9)
        for (row in 0 until 3) {
            val axis = FloatArray(3)
            X1Bind.toAssetDirectionInto(
                Vector3(rows[row * 3], rows[row * 3 + 1], rows[row * 3 + 2]), axis
            )
            out[row * 3] = axis[0]
            out[row * 3 + 1] = axis[1]
            out[row * 3 + 2] = axis[2]
        }
        return out
    }

    /** A column-major matrix applied to a point. */
    private fun apply(matrix: FloatArray, offset: Int, point: FloatArray): FloatArray = floatArrayOf(
        matrix[offset] * point[0] + matrix[offset + 4] * point[1] + matrix[offset + 8] * point[2] + matrix[offset + 12],
        matrix[offset + 1] * point[0] + matrix[offset + 5] * point[1] + matrix[offset + 9] * point[2] + matrix[offset + 13],
        matrix[offset + 2] * point[0] + matrix[offset + 6] * point[1] + matrix[offset + 10] * point[2] + matrix[offset + 14]
    )

    private fun rotate(matrix: FloatArray, offset: Int, direction: FloatArray): FloatArray = floatArrayOf(
        matrix[offset] * direction[0] + matrix[offset + 4] * direction[1] + matrix[offset + 8] * direction[2],
        matrix[offset + 1] * direction[0] + matrix[offset + 5] * direction[1] + matrix[offset + 9] * direction[2],
        matrix[offset + 2] * direction[0] + matrix[offset + 6] * direction[1] + matrix[offset + 10] * direction[2]
    )

    private fun determinant(matrix: FloatArray, offset: Int): Float =
        matrix[offset] * (matrix[offset + 5] * matrix[offset + 10] - matrix[offset + 9] * matrix[offset + 6]) -
            matrix[offset + 4] * (matrix[offset + 1] * matrix[offset + 10] - matrix[offset + 9] * matrix[offset + 2]) +
            matrix[offset + 8] * (matrix[offset + 1] * matrix[offset + 6] - matrix[offset + 5] * matrix[offset + 2])

    private fun cross(a: FloatArray, b: FloatArray): FloatArray = floatArrayOf(
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0]
    )

    private fun distance(a: FloatArray, b: FloatArray): Float {
        val dx = a[0] - b[0]
        val dy = a[1] - b[1]
        val dz = a[2] - b[2]
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    /** A rig whose inverse binds are the manifest's own, so the skinner is tested apart from the GLB. */
    private fun skinningRig(binds: HelperBinds): GltfRig {
        val inverseBinds = List(binds.palette) { index ->
            if (index < binds.skinJoints) {
                Mat4.identity()
            } else {
                FloatArray(16).also { binds.inverseBindInto(index - binds.skinJoints, it) }
            }
        }
        return GltfRig(
            nodes = emptyList(),
            sceneRoots = IntArray(0),
            jointNodes = IntArray(binds.palette) { it },
            skinnedNodes = IntArray(0),
            inverseBinds = inverseBinds,
            triangleCount = 0,
            morphTargetCount = 0,
            animationNames = emptyList()
        )
    }

    private companion object {
        val IDENTITY = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f
        )
    }
}
