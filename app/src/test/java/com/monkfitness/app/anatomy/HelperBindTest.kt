package com.monkfitness.app.anatomy

import com.monkfitness.app.animation.SkeletonDefinition
import com.monkfitness.app.animation.Vector3
import com.monkfitness.app.character.HelperBindManifest
import com.monkfitness.app.character.X1Bind
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The exported helper binds are a claim about the solver's rest path: four per head at 0/30/70/100 % of
 * arc, exactly on the polyline, in the same asset metres and axes as `humanoid_v2.glb`'s skeleton. Both
 * are re-derived here, so a solver change that moves a rest path, or an export that leaves in engine
 * units, fails instead of shipping a layer bound off the body.
 */
class HelperBindTest {

    private val definition: AnatomyDefinition = AnatomyDefinitionLoader.load(File(ASSET).readText())
    private val skeleton = SkeletonDefinition.DEFAULT_ADULT
    private val scale: Float = X1Bind.unitsPerMetre(skeleton)
    private val restPose = AnatomyPoseFixture.bindPose(skeleton)

    @Test
    fun everyHelperBindsOnTheSolverRestPathAtItsStation() {
        val frame = restFrame()
        val helpers = manifest().getJSONArray("helpers")
        assertEquals("four helpers per head-side instance", definition.headCount * STATIONS, helpers.length())

        var worstPosition = 0.0
        var worstAxis = 0.0
        for (index in 0 until helpers.length()) {
            val helper = helpers.getJSONObject(index)
            val name = helper.getString("name")
            val station = helper.getDouble("station")
            val (point, tangent) = stationFrame(frame, slotOf(frame, helper), station)

            val expectedPosition = toAsset(point)
            val positionResidual = distance(expectedPosition, helper.getJSONArray("position"))
            worstPosition = maxOf(worstPosition, positionResidual)
            assertTrue("$name binds $positionResidual m off the rest path at station $station", positionResidual <= TOLERANCE_METRES)

            // The basis' +Y is the path's own direction, carried onto the rig's axes.
            val expectedAxis = toAssetDirection(tangent)
            val axis = helper.getJSONArray("basis").getJSONArray(1)
            val axisResidual = maxOf(
                maxOf(abs(axis.getDouble(0) - expectedAxis[0]), abs(axis.getDouble(1) - expectedAxis[1])),
                abs(axis.getDouble(2) - expectedAxis[2])
            )
            worstAxis = maxOf(worstAxis, axisResidual)
            assertTrue("$name basis points $axisResidual off the rest path tangent at station $station", axisResidual <= TOLERANCE_METRES)
        }
        println("worst helper bind residual = $worstPosition m, worst basis-axis residual = $worstAxis")
    }

    @Test
    fun everyHelperCarriesRotationOnly() {
        val helpers = manifest().getJSONArray("helpers")
        for (index in 0 until helpers.length()) {
            val helper = helpers.getJSONObject(index)
            val name = helper.getString("name")
            val basis = helper.getJSONArray("basis")
            val rows = (0 until 3).map { row ->
                (0 until 3).map { column -> basis.getJSONArray(row).getDouble(column) }
            }
            for (row in 0 until 3) {
                assertEquals("$name row $row is not unit length", 1.0, magnitude(rows[row]), 1e-6)
            }
            for (first in 0 until 3) for (second in first + 1 until 3) {
                assertEquals("$name rows $first/$second are not orthogonal", 0.0, dot(rows[first], rows[second]), 1e-6)
            }
            assertEquals("$name basis is not a right-handed rotation", 1.0, determinant(rows), 1e-6)
        }
    }

    @Test
    fun everyHelperLandsInsideTheRigInMetres() {
        val manifest = manifest()
        assertEquals("helpers are written in the rig's own unit", HelperBindManifest.UNITS, manifest.getString("units"))

        val envelope = bodyEnvelope()
        // The rig is a metre-scale body: a manifest left in engine units is ~247x this tall.
        assertTrue("the rig's body is ${envelope[4]} m tall, not a metre-scale human", envelope[4] in 1.4..2.0)

        val helpers = manifest.getJSONArray("helpers")
        var lowest = Double.MAX_VALUE
        var highest = -Double.MAX_VALUE
        for (index in 0 until helpers.length()) {
            val helper = helpers.getJSONObject(index)
            val name = helper.getString("name")
            val position = helper.getJSONArray("position")
            val point = doubleArrayOf(position.getDouble(0), position.getDouble(1), position.getDouble(2))
            lowest = minOf(lowest, point[1])
            highest = maxOf(highest, point[1])
            assertTrue("$name sits at y=${point[1]} m, outside the body's 0.0..1.75 m", point[1] >= 0.0 && point[1] <= 1.75)
            assertTrue(
                "$name at (${point[0]}, ${point[1]}, ${point[2]}) m leaves the rig's body envelope " +
                    "x ${envelope[0]}..${envelope[3]}, y ${envelope[1]}..${envelope[4]}, z ${envelope[2]}..${envelope[5]}",
                point[0] >= envelope[0] && point[0] <= envelope[3] &&
                    point[1] >= envelope[1] && point[1] <= envelope[4] &&
                    point[2] >= envelope[2] && point[2] <= envelope[5]
            )
        }
        println("helper y bounds = $lowest..$highest m, inside the rig's envelope")
    }

    @Test
    fun theMuscleAssetCarriesTheWholePaletteAndOneSectionPerInstance() {
        val asset = MuscleAsset(File(MUSCLES))
        val manifest = manifest()
        assertEquals(
            "the layer's palette is the manifest's, not a second count",
            manifest.getInt("palette"),
            asset.joints.size
        )
        assertEquals("one inverse bind per palette joint", asset.joints.size, asset.inverseBinds.size)

        val expected = ArrayList<String>()
        for (muscle in definition.muscles) {
            for (side in MuscleSide.entries) expected += "${muscle.id}_${side.name}"
        }
        assertEquals("one section per muscle instance", expected, asset.sectionNames.toList())
        val skinJoints = manifest.getInt("skinJoints")
        assertEquals(
            "the rig's own range is the first $skinJoints joints",
            skinJoints,
            asset.joints.take(skinJoints).size
        )
    }

    @Test
    fun everyMuscleSectionBindsItsHelpersWhereTheManifestSays() {
        val asset = MuscleAsset(File(MUSCLES))
        val skinJoints = manifest().getInt("skinJoints")
        val helpers = manifest().getJSONArray("helpers")
        var worstPosition = 0.0
        var worstAxis = 0.0
        for (index in 0 until helpers.length()) {
            val helper = helpers.getJSONObject(index)
            val matrix = asset.inverseBinds[skinJoints + index]
            val bind = asset.bindOf(matrix)
            val expectedPosition = helper.getJSONArray("position")
            val residual = sqrt(
                (0 until 3).sumOf { component ->
                    val delta = bind[component] - expectedPosition.getDouble(component)
                    delta * delta
                }
            )
            worstPosition = maxOf(worstPosition, residual)
            assertTrue(
                "${helper.getString("name")} binds $residual m off the manifest",
                residual <= TOLERANCE_METRES
            )
            val basis = helper.getJSONArray("basis")
            for (row in 0 until 3) {
                for (column in 0 until 3) {
                    val expected = basis.getJSONArray(row).getDouble(column)
                    // The asset's bind rotation is the manifest basis' transpose, by construction.
                    val actual = matrix[column * 4 + row]
                    worstAxis = maxOf(worstAxis, abs(actual - expected))
                }
            }
        }
        assertTrue("a helper's bind rotation is $worstAxis off the manifest", worstAxis <= TOLERANCE_METRES)
        println("worst helper bind residual in muscles_sup.glb = $worstPosition m")
    }

    @Test
    fun everyMuscleVertexCarriesAtMostFourFiniteInfluences() {
        val asset = MuscleAsset(File(MUSCLES))
        var vertices = 0
        var worstWeight = 0.0
        val skinJoints = manifest().getInt("skinJoints")
        for (primitive in asset.primitives) {
            for (vertex in primitive.joints.indices) {
                vertices++
                val weights = primitive.weights[vertex]
                var influences = 0
                for (slot in 0 until 4) {
                    val joint = primitive.joints[vertex][slot]
                    assertTrue("vertex $vertex names joint $joint", joint in asset.joints.indices)
                    if (weights[slot] == 0f) continue
                    influences++
                    // A vertex weighted to the body would not follow the path, which is the whole layer.
                    assertTrue("vertex $vertex is weighted to body joint $joint", joint >= skinJoints)
                }
                assertTrue("a vertex carries $influences influences, over the 4 a JOINTS_0 holds", influences <= 4)
                worstWeight = maxOf(worstWeight, abs(weights.sumOf { it.toDouble() } - 1.0))
                for (component in 0 until 3) {
                    assertTrue("positions must be finite", primitive.positions[vertex][component].isFinite())
                    assertTrue("normals must be finite", primitive.normals[vertex][component].isFinite())
                    assertEquals(
                        "a normal is a unit vector",
                        1.0,
                        sqrt(primitive.normals[vertex].sumOf { (it * it).toDouble() }),
                        1e-4
                    )
                }
                for (component in 0 until 4) {
                    assertTrue("tangents must be finite", primitive.tangents[vertex][component].isFinite())
                }
            }
        }
        assertEquals(
            "13 ring vertices of 8 plus two cap centres for each of the ${definition.headCount} heads",
            definition.headCount * (13 * 8 + 2),
            vertices
        )
        assertTrue("a vertex's weights must sum to 1, worst $worstWeight", worstWeight <= 1e-4)
    }

    @Test
    fun everyHelperInTheMuscleAssetScalesByExactlyOne() {
        val asset = MuscleAsset(File(MUSCLES))
        val skinJoints = manifest().getInt("skinJoints")
        for (index in skinJoints until asset.inverseBinds.size) {
            val matrix = asset.inverseBinds[index]
            for (column in 0 until 3) {
                val length = sqrt(
                    (0 until 3).sumOf { row -> (matrix[column * 4 + row] * matrix[column * 4 + row]).toDouble() }
                )
                // §7.6: path following only. A helper that carried scale would stretch its muscle.
                assertEquals("helper ${index - skinJoints} column $column", 1.0, length, 1e-5)
            }
        }
    }

    @Test
    fun theMuscleSurfaceFacesTheWayItsNormalsClaim() {
        val asset = MuscleAsset(File(MUSCLES))
        val hairpins = hairpinSections()
        var triangles = 0
        var folded = 0
        var foldedArea = 0.0
        for (primitive in asset.primitives) {
            var triangle = 0
            while (triangle < primitive.indices.size) {
                val a = primitive.indices[triangle]
                val b = primitive.indices[triangle + 1]
                val c = primitive.indices[triangle + 2]
                val normal = cross(primitive.positions[a], primitive.positions[b], primitive.positions[c])
                val area = 0.5 * sqrt(normal.sumOf { (it * it).toDouble() })
                val facing = normal.indices.sumOf { component ->
                    (normal[component] * (primitive.normals[a][component] +
                        primitive.normals[b][component] + primitive.normals[c][component]) / 3.0).toDouble()
                }
                triangles++
                if (facing < 0.0) {
                    folded++
                    foldedArea += area
                    // A rest path that doubles back inside one ring step cannot carry a tube of the
                    // muscle's own radius: the surface folds there whatever the frame rule, and the
                    // mesh is a stand-in until muscle art arrives. Everywhere else the winding must hold.
                    assertTrue(
                        "${primitive.name} folds a ${area} m2 triangle, and its path does not double back",
                        primitive.name in hairpins
                    )
                }
                triangle += 3
            }
        }
        assertTrue("the layer has no triangles", triangles > 0)
        println("muscle surface: $triangles triangles, $folded folded in $hairpins, $foldedArea m2")
        assertTrue(
            "the folded area $foldedArea m2 is over the $FOLDED_AREA_SQUARE_METRES m2 a hairpin may cost",
            foldedArea <= FOLDED_AREA_SQUARE_METRES
        )
    }

    /**
     * The sections whose rest path turns by more than a right angle inside one helper interval — the
     * only places a swept tube can fold. Read from the manifest's own basis, so the exemption is a
     * property of the anatomy and not a list of names.
     */
    private fun hairpinSections(): Set<String> {
        val helpers = manifest().getJSONArray("helpers")
        val axes = LinkedHashMap<String, MutableList<DoubleArray>>()
        for (index in 0 until helpers.length()) {
            val helper = helpers.getJSONObject(index)
            val key = helper.getString("muscleId") + "_" + helper.getString("side")
            val y = helper.getJSONArray("basis").getJSONArray(1)
            axes.getOrPut(key) { ArrayList() } += doubleArrayOf(y.getDouble(0), y.getDouble(1), y.getDouble(2))
        }
        val out = LinkedHashSet<String>()
        for ((key, list) in axes) {
            for (index in 0 until list.size - 1) {
                val dot = list[index].indices.sumOf { component -> list[index][component] * list[index + 1][component] }
                if (Math.toDegrees(Math.acos(dot.coerceIn(-1.0, 1.0))) > HAIRPIN_DEGREES) out += key
            }
        }
        return out
    }

    @Test
    fun thePaletteIsTheWritersOwnCountAndTheGateRefusesOneOver() {
        val manifest = manifest()
        val skin = manifest.getInt("skinJoints")
        val helpers = manifest.getInt("helperCount")
        assertEquals("one helper per head and station", definition.headCount * STATIONS, helpers)
        assertEquals(
            "the manifest's palette is the writer's own count",
            manifest.getInt("palette"),
            HelperBindManifest.requireWithinPalette(skin, helpers)
        )

        // The budget exactly is bindable and one joint over is not, so an off-by-one gate cannot ship.
        val budget = HelperBindManifest.PALETTE_LIMIT
        assertEquals(budget, HelperBindManifest.requireWithinPalette(skin, budget - skin))
        assertThrows(IllegalArgumentException::class.java) {
            HelperBindManifest.requireWithinPalette(skin, budget - skin + 1)
        }
    }

    @Test
    fun aNonFiniteRestLengthIsRefusedBeforeItReachesTheJson() {
        for (bad in doubleArrayOf(Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NaN)) {
            assertThrows("$bad passes a bare `> 0` rest length", IllegalArgumentException::class.java) {
                HelperBindManifest.requireRestLength("rectus_abdominis_A_0", bad)
            }
            assertThrows("$bad would be written as JSON no reader accepts", IllegalArgumentException::class.java) {
                HelperBindManifest.number(bad)
            }
        }
        val zero = assertThrows(IllegalArgumentException::class.java) {
            HelperBindManifest.requireRestLength("rectus_abdominis_A_0", 0.0)
        }
        assertTrue("a zero-length head must be refused, not sampled: ${zero.message}", zero.message?.contains("zero-length") == true)
    }

    private fun restFrame(): AnatomyFrame {
        val solver = AnatomySolver(definition, skeleton, scale.toDouble(), restPose)
        val frame = AnatomyFrame.of(definition)
        return solver.solve(restPose, frame)
    }

    private fun slotOf(frame: AnatomyFrame, helper: JSONObject): Int {
        val id = MuscleHeadId(
            helper.getString("muscleId"),
            MuscleSide.valueOf(helper.getString("side")),
            helper.getInt("headIndex")
        )
        val slot = frame.heads.indexOf(id)
        assertTrue("$id is not a slot of this definition", slot >= 0)
        return slot
    }

    /**
     * The engine-space point and unit tangent at [station] of [slot]'s polyline, by arc length: the
     * exporter's own sample, before it converts the pair into the rig's space.
     */
    private fun stationFrame(frame: AnatomyFrame, slot: Int, station: Double): Pair<DoubleArray, DoubleArray> {
        val count = frame.pointCounts[slot]
        val cumulative = DoubleArray(count)
        for (index in 1 until count) cumulative[index] = cumulative[index - 1] + distance(frame, slot, index - 1, index)
        val target = cumulative[count - 1] * station
        var segment = 0
        while (segment < count - 2 && cumulative[segment + 1] < target) segment++
        val span = cumulative[segment + 1] - cumulative[segment]
        val u = if (span > 0.0) ((target - cumulative[segment]) / span).coerceIn(0.0, 1.0) else 0.0
        val point = DoubleArray(3) { component ->
            val from = frame.pointComponent(slot, segment, component).toDouble()
            val to = frame.pointComponent(slot, segment + 1, component).toDouble()
            from + (to - from) * u
        }
        var tangent = DoubleArray(3) { component ->
            (frame.pointComponent(slot, segment + 1, component) - frame.pointComponent(slot, segment, component)).toDouble()
        }
        if (!(magnitude(tangent) > 0.0)) {
            tangent = DoubleArray(3) { component ->
                (frame.pointComponent(slot, count - 1, component) - frame.pointComponent(slot, 0, component)).toDouble()
            }
        }
        val length = magnitude(tangent)
        if (length > 0.0) for (component in 0..2) tangent[component] /= length
        return point to tangent
    }

    /** Engine units → the rig's asset metres, `B⁻¹ · p / unitsPerMetre`. */
    private fun toAsset(engine: DoubleArray): DoubleArray {
        val out = FloatArray(3)
        X1Bind.toAssetInto(Vector3(engine[0].toFloat(), engine[1].toFloat(), engine[2].toFloat()), scale, out)
        return doubleArrayOf(out[0].toDouble(), out[1].toDouble(), out[2].toDouble())
    }

    /** A direction on the rig's axes, unscaled: how the exporter rotates a basis axis. */
    private fun toAssetDirection(engine: DoubleArray): DoubleArray {
        val direction = X1Bind.toAssetDirection(Vector3(engine[0].toFloat(), engine[1].toFloat(), engine[2].toFloat()))
        return doubleArrayOf(direction.x.toDouble(), direction.y.toDouble(), direction.z.toDouble())
    }

    /** `[minX, minY, minZ, maxX, maxY, maxZ]` of the rig's skinned body, read from the GLB's accessors. */
    private fun bodyEnvelope(): DoubleArray {
        val buffer = ByteBuffer.wrap(File(RIG).readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        buffer.int // magic
        buffer.int // version
        buffer.int // total length
        val jsonLength = buffer.int
        buffer.int // chunk type: JSON
        val json = ByteArray(jsonLength).also { buffer.get(it) }
        val root = JSONObject(String(json, Charsets.UTF_8))
        val accessors = root.getJSONArray("accessors")

        val min = doubleArrayOf(Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE)
        val max = doubleArrayOf(-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE)
        var skinnedPrimitives = 0
        val meshes = root.getJSONArray("meshes")
        for (mesh in 0 until meshes.length()) {
            val primitives = meshes.getJSONObject(mesh).getJSONArray("primitives")
            for (index in 0 until primitives.length()) {
                val attributes = primitives.getJSONObject(index).getJSONObject("attributes")
                if (!attributes.has("JOINTS_0")) continue
                val bounds = accessors.getJSONObject(attributes.getInt("POSITION"))
                for (component in 0..2) {
                    min[component] = minOf(min[component], bounds.getJSONArray("min").getDouble(component))
                    max[component] = maxOf(max[component], bounds.getJSONArray("max").getDouble(component))
                }
                skinnedPrimitives++
            }
        }
        assertTrue("the rig has no skinned primitive to bound", skinnedPrimitives > 0)
        return doubleArrayOf(min[0], min[1], min[2], max[0], max[1], max[2])
    }

    private fun distance(expected: DoubleArray, actual: JSONArray): Double {
        var sum = 0.0
        for (component in 0..2) {
            val delta = expected[component] - actual.getDouble(component)
            sum += delta * delta
        }
        return sqrt(sum)
    }

    private fun distance(frame: AnatomyFrame, slot: Int, from: Int, to: Int): Double {
        var sum = 0.0
        for (component in 0..2) {
            val delta = (frame.pointComponent(slot, to, component) - frame.pointComponent(slot, from, component)).toDouble()
            sum += delta * delta
        }
        return sqrt(sum)
    }

    private fun magnitude(v: DoubleArray): Double = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])

    private fun magnitude(v: List<Double>): Double = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])

    private fun dot(a: List<Double>, b: List<Double>): Double = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    private fun determinant(m: List<List<Double>>): Double =
        m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1]) -
            m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0]) +
            m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0])

    private fun manifest(): JSONObject = JSONObject(File(MANIFEST).readText())

    /** `cross(b - a, c - a)`: twice the area normal of a triangle, in the winding order given. */
    private fun cross(a: FloatArray, b: FloatArray, c: FloatArray): FloatArray = floatArrayOf(
        (b[1] - a[1]) * (c[2] - a[2]) - (b[2] - a[2]) * (c[1] - a[1]),
        (b[2] - a[2]) * (c[0] - a[0]) - (b[0] - a[0]) * (c[2] - a[2]),
        (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0])
    )

    /**
     * The skinned muscle asset read straight from its GLB chunks: joint order, inverse binds, and one
     * primitive's attributes per section. Nothing here trusts the generator's own claims.
     */
    private class MuscleAsset(file: File) {

        private val root: JSONObject
        private val bin: ByteBuffer
        private val accessors: JSONArray
        private val views: JSONArray

        val joints: List<Int>
        val inverseBinds: List<FloatArray>
        val primitives: List<Primitive>

        init {
            val buffer = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            buffer.int // magic
            buffer.int // version
            buffer.int // total length
            val jsonLength = buffer.int
            buffer.int // chunk type: JSON
            root = JSONObject(String(ByteArray(jsonLength).also { buffer.get(it) }, Charsets.UTF_8))
            var binChunk = ByteBuffer.allocate(0)
            while (buffer.remaining() >= 8) {
                val length = buffer.int
                val type = buffer.int
                if (length < 0 || length > buffer.remaining()) break
                val chunk = ByteArray(length).also { buffer.get(it) }
                if (type == 0x004E4942) binChunk = ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN)
            }
            bin = binChunk
            accessors = root.getJSONArray("accessors")
            views = root.getJSONArray("bufferViews")
            val skin = root.getJSONArray("skins").getJSONObject(0)
            joints = skin.getJSONArray("joints").let { array -> (0 until array.length()).map { array.getInt(it) } }
            inverseBinds = matrices(skin.getInt("inverseBindMatrices"))

            val meshes = root.getJSONArray("meshes")
            primitives = (0 until meshes.length()).map { index ->
                val mesh = meshes.getJSONObject(index)
                val attributes = mesh.getJSONArray("primitives").getJSONObject(0).getJSONObject("attributes")
                Primitive(
                    name = mesh.getString("name"),
                    positions = vectors(attributes.getInt("POSITION"), 3),
                    normals = vectors(attributes.getInt("NORMAL"), 3),
                    tangents = vectors(attributes.getInt("TANGENT"), 4),
                    joints = shorts(attributes.getInt("JOINTS_0"), 4),
                    weights = vectors(attributes.getInt("WEIGHTS_0"), 4),
                    indices = ints(mesh.getJSONArray("primitives").getJSONObject(0).getInt("indices"))
                )
            }
        }

        val sectionNames: List<String> get() = primitives.map { it.name }

        /** A helper's bind position, recovered from its inverse bind rather than from the file's claims. */
        fun bindOf(inverseBind: FloatArray): DoubleArray = doubleArrayOf(
            -(inverseBind[0] * inverseBind[12] + inverseBind[1] * inverseBind[13] + inverseBind[2] * inverseBind[14]).toDouble(),
            -(inverseBind[4] * inverseBind[12] + inverseBind[5] * inverseBind[13] + inverseBind[6] * inverseBind[14]).toDouble(),
            -(inverseBind[8] * inverseBind[12] + inverseBind[9] * inverseBind[13] + inverseBind[10] * inverseBind[14]).toDouble()
        )

        private fun layout(accessorIndex: Int): Triple<Int, Int, Int> {
            val accessor = accessors.getJSONObject(accessorIndex)
            val view = views.getJSONObject(accessor.getInt("bufferView"))
            val components = when (accessor.getString("type")) {
                "SCALAR" -> 1
                "VEC2" -> 2
                "VEC3" -> 3
                "VEC4" -> 4
                "MAT4" -> 16
                else -> error("unexpected accessor type")
            }
            val width = when (accessor.getInt("componentType")) {
                5126 -> 4
                5123 -> 2
                5125 -> 4
                else -> error("unexpected component type")
            }
            val base = view.optInt("byteOffset", 0) + accessor.optInt("byteOffset", 0)
            return Triple(base, accessor.getInt("count"), components * width)
        }

        private fun matrices(accessorIndex: Int): List<FloatArray> {
            val (base, count, stride) = layout(accessorIndex)
            return (0 until count).map { index ->
                FloatArray(16) { entry -> bin.getFloat(base + index * stride + entry * 4) }
            }
        }

        private fun vectors(accessorIndex: Int, components: Int): List<FloatArray> {
            val (base, count, stride) = layout(accessorIndex)
            return (0 until count).map { index ->
                FloatArray(components) { entry -> bin.getFloat(base + index * stride + entry * 4) }
            }
        }

        private fun shorts(accessorIndex: Int, components: Int): List<IntArray> {
            val (base, count, stride) = layout(accessorIndex)
            return (0 until count).map { index ->
                IntArray(components) { entry -> bin.getShort(base + index * stride + entry * 2).toInt() and 0xFFFF }
            }
        }

        private fun ints(accessorIndex: Int): IntArray {
            val (base, count, stride) = layout(accessorIndex)
            return IntArray(count) { index -> bin.getInt(base + index * stride) }
        }

        class Primitive(
            val name: String,
            val positions: List<FloatArray>,
            val normals: List<FloatArray>,
            val tangents: List<FloatArray>,
            val joints: List<IntArray>,
            val weights: List<FloatArray>,
            val indices: IntArray
        )
    }

    private companion object {
        const val ASSET = "src/main/assets/anatomy/anatomy_v1.json"
        const val MANIFEST = "src/main/assets/anatomy/helpers_v1.json"
        const val MUSCLES = "src/main/assets/characters/muscles_sup.glb"
        const val RIG = "src/main/assets/characters/humanoid_v2.glb"
        /** A triangle under a square millimetre has no readable facing. */
        const val SLIVER_SQUARE_METRES = 1e-6

        /** A turn this sharp between two helper stations folds any tube of the muscle's own radius. */
        const val HAIRPIN_DEGREES = 90.0

        /** Total folded area the stand-in's hairpin may cost: it disappears with the authored asset. */
        const val FOLDED_AREA_SQUARE_METRES = 5e-5
        const val TOLERANCE_METRES = 1e-3
        const val STATIONS = 4
    }
}
