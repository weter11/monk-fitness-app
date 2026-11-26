package com.monkfitness.app.anatomy

import com.monkfitness.app.animation.SkeletonDefinition
import com.monkfitness.app.animation.Vector3
import com.monkfitness.app.character.HelperBindManifest
import com.monkfitness.app.character.HelperBinds
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
        val binds = HelperBinds.read(File(MANIFEST).readText())
        val hairpins = hairpinIntervals()
        val intervals = hairpins.bySection.mapValues { (section, ranges) ->
            val helperOffset = binds.binds.indexOfFirst { "${it.head.muscleId}_${it.head.side.name}" == section }
            require(helperOffset >= 0) { "$section has no helpers" }
            ranges.map { range ->
                val start = hairpins.stationIndexOf(range.start)
                val end = hairpins.stationIndexOf(range.endInclusive)
                (helperOffset + start..helperOffset + end).toSet()
            }
        }
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
                    // muscle's own radius: only a vertex influenced by that interval may fold.
                    val exempt = intervals[primitive.name].orEmpty().any { helpers ->
                        influenced(primitive, a, helpers, binds.skinJoints) ||
                            influenced(primitive, b, helpers, binds.skinJoints) ||
                            influenced(primitive, c, helpers, binds.skinJoints)
                    }
                    assertTrue(
                        "${primitive.name} folds a ${area} m2 triangle outside a hairpin station interval",
                        exempt
                    )
                }
                triangle += 3
            }
        }
        assertTrue("the layer has no triangles", triangles > 0)
        println("muscle surface: $triangles triangles, $folded folded in ${hairpins.bySection}, $foldedArea m2")
        assertTrue(
            "the folded area $foldedArea m2 is over the $FOLDED_AREA_SQUARE_METRES m2 a hairpin may cost",
            foldedArea <= FOLDED_AREA_SQUARE_METRES
        )
    }

    // ---- L5: the mesh itself, read from the GLB rather than from the exporter's claims ----------

    @Test
    fun noMuscleSectionCarriesADegenerateTriangle() {
        val asset = MuscleAsset(File(MUSCLES))
        var triangles = 0
        var smallest = Double.MAX_VALUE
        var smallestWhere = ""
        for (primitive in asset.primitives) {
            var triangle = 0
            while (triangle < primitive.indices.size) {
                val a = primitive.positions[primitive.indices[triangle]]
                val b = primitive.positions[primitive.indices[triangle + 1]]
                val c = primitive.positions[primitive.indices[triangle + 2]]
                val area = 0.5 * magnitude(cross(a, b, c))
                assertTrue(
                    "${primitive.name} triangle ${triangle / 3} has an area of $area m2, which no draw can shade",
                    area.isFinite() && area > 0.0
                )
                if (area < smallest) {
                    smallest = area
                    smallestWhere = "${primitive.name} triangle ${triangle / 3}"
                }
                triangles++
                triangle += 3
            }
        }
        assertEquals(
            "every head is ${RING_COUNT - 1} bands of $RING_SEGMENTS quads plus two capped ends",
            definition.headCount * ((RING_COUNT - 1) * RING_SEGMENTS * 2 + 2 * RING_SEGMENTS),
            triangles
        )
        println("L5 degenerate triangles: none of $triangles; smallest area $smallest m2 at $smallestWhere")
    }

    @Test
    fun everyMuscleSectionEnclosesAPositiveVolume() {
        val asset = MuscleAsset(File(MUSCLES))
        var smallest = Double.MAX_VALUE
        var smallestWhere = ""
        val volumes = LinkedHashMap<String, Double>()
        for (primitive in asset.primitives) {
            var section = 0.0
            for (head in 0 until headsIn(primitive)) {
                val volume = volumeOf(primitive, head)
                val where = if (headsIn(primitive) > 1) "${primitive.name} head $head" else primitive.name
                // A negative volume is outward winding reversed: the section would draw inside out.
                assertTrue("$where encloses $volume m3, so its surface faces inward or is open", volume > 0.0)
                if (volume < smallest) {
                    smallest = volume
                    smallestWhere = where
                }
                section += volume
            }
            volumes[primitive.name] = section
        }
        assertEquals("every superficial muscle is a section on both sides", 16, volumes.size)
        println(
            "L5 section volumes (m3): " + volumes.entries.joinToString(", ") { "${it.key} ${"%.3e".format(it.value)}" } +
                "; smallest $smallest at $smallestWhere"
        )
    }

    @Test
    fun everyMuscleSectionNormalisesItsSkinWeights() {
        val asset = MuscleAsset(File(MUSCLES))
        var worst = 0.0
        var worstWhere = ""
        for (primitive in asset.primitives) {
            for (vertex in primitive.weights.indices) {
                val sum = primitive.weights[vertex].sumOf { it.toDouble() }
                val residual = abs(sum - 1.0)
                assertTrue(
                    "${primitive.name} vertex $vertex weighs $sum, which drifts the surface it skins",
                    residual <= WEIGHT_SUM_TOLERANCE
                )
                if (residual > worst) {
                    worst = residual
                    worstWhere = "${primitive.name} vertex $vertex"
                }
            }
        }
        println("L5 weight sum: worst residual $worst at $worstWhere")
    }

    /**
     * Two muscles must never occupy one space. Inside one section the stand-in's uniform cylinder may
     * cross itself only where its own path doubles back, or where two heads of that muscle share a
     * tendon — the two conditions the geometry itself declares.
     */
    @Test
    fun noMuscleSectionCrossesAnotherAndFoldsOnlyWhereItsGeometryAllowsIt() {
        val asset = MuscleAsset(File(MUSCLES))
        val binds = HelperBinds.read(File(MANIFEST).readText())
        val hairpins = hairpinIntervals()
        val geometry = asset.primitives.map { it to trianglesOf(it) }

        var acrossSections = 0
        var acrossSectionsWhere = ""
        for (first in geometry.indices) {
            for (second in first + 1 until geometry.size) {
                val left = geometry[first]
                val right = geometry[second]
                for (a in left.second) {
                    for (b in right.second) {
                        if (!a.overlaps(b) || !crosses(a, b)) continue
                        acrossSections++
                        acrossSectionsWhere = "${left.first.name} x ${right.first.name}"
                    }
                }
            }
        }
        assertEquals(
            "two muscle sections interpenetrate at $acrossSectionsWhere; no pose can separate geometry that already overlaps",
            0,
            acrossSections
        )

        var withinOneHead = 0
        var betweenHeads = 0
        var sectionCount = 0
        for ((primitive, triangles) in geometry) {
            val exempt = hairpinHelperIndices(primitive.name, binds, hairpins)
            var folds = 0
            var merges = 0
            for (i in triangles.indices) {
                for (j in i + 1 until triangles.size) {
                    val left = triangles[i]
                    val right = triangles[j]
                    if (left.sharesVertexWith(right) || !left.overlaps(right) || !crosses(left, right)) continue
                    if (left.indices[0] / VERTICES_PER_HEAD != right.indices[0] / VERTICES_PER_HEAD) {
                        merges++
                        continue
                    }
                    folds++
                    // A fold away from a hairpin means the surface passes through the belly it draws.
                    assertTrue(
                        "${primitive.name} crosses itself at ${left.indices.joinToString()} x ${right.indices.joinToString()}, " +
                            "away from any station interval whose path turns through a right angle",
                        left.indices.any { influenced(primitive, it, exempt, binds.skinJoints) }
                    )
                }
            }
            if (folds > 0) sectionCount++
            withinOneHead += folds
            betweenHeads += merges
            if (folds > 0 || merges > 0) println("L5 ${primitive.name}: $folds fold(s) inside a head, $merges between its heads")
        }
        println(
            "L5 self-intersection: 0 across sections; $withinOneHead inside a head on $sectionCount section(s), " +
                "all at a hairpin; $betweenHeads between the heads of one muscle"
        )
    }

    /**
     * Between two adjacent helper stations the skinning interpolates two rigid frames, so it reproduces
     * the straight chord and not the path. This measures how far the authored surface leaves that chord.
     */
    @Test
    fun theSurfaceStaysWithinOneMuscleRadiusOfTheHelperChord() {
        val asset = MuscleAsset(File(MUSCLES))
        val binds = HelperBinds.read(File(MANIFEST).readText())
        val frame = restFrame()
        // The budget is the widest radius the definition authors: a chord that cuts deeper than a belly
        // is wide would put the skinning's error outside the muscle it draws.
        val budget = definition.muscles.maxOf { muscle -> muscle.radiusKnots.maxOf { knot -> knot.second.toDouble() } }
        var worst = 0.0
        var worstWhere = ""
        for (primitive in asset.primitives) {
            val side = MuscleSide.entries.first { primitive.name.endsWith("_${it.name}") }
            val muscleId = primitive.name.removeSuffix("_${side.name}")
            for (head in 0 until headsIn(primitive)) {
                val slot = frame.heads.indexOf(MuscleHeadId(muscleId, side, head))
                require(slot >= 0) { "${primitive.name} head $head is not a slot of this definition" }
                val stations = binds.stations.map { station -> toAsset(stationFrame(frame, slot, station.toDouble()).first) }
                for (index in 0 until stations.size - 1) {
                    val from = binds.stations[index].toDouble()
                    val to = binds.stations[index + 1].toDouble()
                    for (ring in 0 until RING_COUNT) {
                        val u = ring.toDouble() / (RING_COUNT - 1)
                        if (u < from - 1e-9 || u > to + 1e-9) continue
                        val centre = centreOf(primitive, head, ring)
                        for (segment in 0 until RING_SEGMENTS) {
                            val vertex = primitive.positions[vertexOf(head, ring, segment)]
                            val radius = distanceTo(vertex, centre)
                            val deviation = abs(distanceToSegment(vertex, stations[index], stations[index + 1]) - radius)
                            if (deviation > worst) {
                                worst = deviation
                                worstWhere = "${primitive.name} head $head, stations $from..$to, ring $ring"
                            }
                        }
                    }
                }
            }
        }
        println(
            "L5 between-helper surface deviation = ${"%.6f".format(worst)} m " +
                "(${"%.2f".format(worst * 1000)} mm), budget ${"%.2f".format(budget * 1000)} mm, at $worstWhere"
        )
        assertTrue(
            "the surface leaves the straight line between adjacent helper stations by $worst m, over the $budget m budget",
            worst <= budget
        )
    }

    /** Whether at least one vertex in the triangle has an influence from a helper in [indices]. */
    private fun influenced(
        primitive: MuscleAsset.Primitive,
        vertex: Int,
        indices: Set<Int>,
        skinJoints: Int
    ): Boolean = primitive.joints[vertex].indices.any { slot ->
        primitive.weights[vertex][slot] > 0f && primitive.joints[vertex][slot] - skinJoints in indices
    }

    /** The helpers whose station interval is a hairpin, as indices within the section's own group. */
    private fun hairpinHelperIndices(section: String, binds: HelperBinds, hairpins: HairpinIntervals): Set<Int> {
        val offset = binds.binds.indexOfFirst { "${it.head.muscleId}_${it.head.side.name}" == section }
        require(offset >= 0) { "$section has no helpers" }
        val out = HashSet<Int>()
        for (range in hairpins.bySection[section].orEmpty()) {
            for (station in hairpins.stationIndexOf(range.start)..hairpins.stationIndexOf(range.endInclusive)) {
                out += offset + station
            }
        }
        return out
    }

    /** How many heads the section's vertices are divided into, each a closed surface of its own. */
    private fun headsIn(primitive: MuscleAsset.Primitive): Int {
        require(primitive.positions.size % VERTICES_PER_HEAD == 0) {
            "${primitive.name} carries ${primitive.positions.size} vertices, not a whole number of heads"
        }
        return primitive.positions.size / VERTICES_PER_HEAD
    }

    private fun vertexOf(head: Int, ring: Int, segment: Int): Int =
        head * VERTICES_PER_HEAD + ring * RING_SEGMENTS + segment

    /** A ring's centre recovered from its own vertices: the eight radial directions cancel. */
    private fun centreOf(primitive: MuscleAsset.Primitive, head: Int, ring: Int): DoubleArray {
        val out = DoubleArray(3)
        for (segment in 0 until RING_SEGMENTS) {
            val vertex = primitive.positions[vertexOf(head, ring, segment)]
            for (component in 0..2) out[component] += vertex[component] / RING_SEGMENTS
        }
        return out
    }

    /** Signed volume by the divergence theorem, over one head's closed surface. */
    private fun volumeOf(primitive: MuscleAsset.Primitive, head: Int): Double {
        val first = head * VERTICES_PER_HEAD
        val last = first + VERTICES_PER_HEAD - 1
        var volume = 0.0
        var triangle = 0
        while (triangle < primitive.indices.size) {
            val a = primitive.indices[triangle]
            if (a !in first..last) {
                triangle += 3
                continue
            }
            val p = primitive.positions[a]
            val q = primitive.positions[primitive.indices[triangle + 1]]
            val r = primitive.positions[primitive.indices[triangle + 2]]
            volume += (
                p[0].toDouble() * (q[1].toDouble() * r[2] - q[2].toDouble() * r[1]) -
                    p[1].toDouble() * (q[0].toDouble() * r[2] - q[2].toDouble() * r[0]) +
                    p[2].toDouble() * (q[0].toDouble() * r[1] - q[1].toDouble() * r[0])
                ) / 6.0
            triangle += 3
        }
        return volume
    }

    private fun trianglesOf(primitive: MuscleAsset.Primitive): List<Triangle> {
        val out = ArrayList<Triangle>(primitive.indices.size / 3)
        var triangle = 0
        while (triangle < primitive.indices.size) {
            val indices = intArrayOf(
                primitive.indices[triangle],
                primitive.indices[triangle + 1],
                primitive.indices[triangle + 2]
            )
            out += Triangle(indices, indices.map { primitive.positions[it] })
            triangle += 3
        }
        return out
    }

    private fun distanceTo(point: FloatArray, centre: DoubleArray): Double {
        var sum = 0.0
        for (component in 0..2) {
            val delta = point[component] - centre[component]
            sum += delta * delta
        }
        return sqrt(sum)
    }

    private fun distanceToSegment(point: FloatArray, from: DoubleArray, to: DoubleArray): Double {
        var lengthSquared = 0.0
        var along = 0.0
        for (component in 0..2) {
            val edge = to[component] - from[component]
            lengthSquared += edge * edge
            along += (point[component] - from[component]) * edge
        }
        val t = if (lengthSquared > 0.0) (along / lengthSquared).coerceIn(0.0, 1.0) else 0.0
        var sum = 0.0
        for (component in 0..2) {
            val delta = point[component] - (from[component] + (to[component] - from[component]) * t)
            sum += delta * delta
        }
        return sqrt(sum)
    }

    private fun magnitude(v: FloatArray): Double = sqrt(v[0].toDouble() * v[0] + v[1].toDouble() * v[1] + v[2].toDouble() * v[2])

    private fun crosses(first: Triangle, second: Triangle): Boolean = first.crosses(second)

    /**
     * One triangle of a section's surface, with the box and the plane-interval test the overlap check
     * needs. Two triangles that only touch along a face or a corner are not a crossing, which is why
     * the shared chord must be longer than [INTERSECTION_EPSILON].
     */
    private class Triangle(val indices: IntArray, val corners: List<FloatArray>) {

        private val lo = DoubleArray(3) { axis -> corners.minOf { it[axis].toDouble() } }
        private val hi = DoubleArray(3) { axis -> corners.maxOf { it[axis].toDouble() } }

        fun overlaps(other: Triangle): Boolean {
            for (axis in 0..2) if (lo[axis] > other.hi[axis] || other.lo[axis] > hi[axis]) return false
            return true
        }

        fun sharesVertexWith(other: Triangle): Boolean = indices.any { other.indices.contains(it) }

        /** Möller's plane interval: both planes are straddled and the two chords on their line overlap. */
        fun crosses(other: Triangle): Boolean {
            val normalOfOther = unitNormalOf(other) ?: return false
            val firstSide = side(corners, other.corners[0], normalOfOther)
            if (firstSide.all { it > INTERSECTION_EPSILON } || firstSide.all { it < -INTERSECTION_EPSILON }) return false
            val normalOfThis = unitNormalOf(this) ?: return false
            val secondSide = side(other.corners, corners[0], normalOfThis)
            if (secondSide.all { it > INTERSECTION_EPSILON } || secondSide.all { it < -INTERSECTION_EPSILON }) return false
            val axis = normalOfThis cross normalOfOther ?: return false
            val mine = chordOn(corners, firstSide, axis) ?: return false
            val theirs = chordOn(other.corners, secondSide, axis) ?: return false
            return minOf(mine[1], theirs[1]) - maxOf(mine[0], theirs[0]) > INTERSECTION_EPSILON
        }

        private fun unitNormalOf(triangle: Triangle): DoubleArray? {
            val a = triangle.corners[0]
            val b = triangle.corners[1]
            val c = triangle.corners[2]
            val u = DoubleArray(3) { b[it].toDouble() - a[it] }
            val v = DoubleArray(3) { c[it].toDouble() - a[it] }
            val normal = u cross v ?: return null
            val length = sqrt(normal[0] * normal[0] + normal[1] * normal[1] + normal[2] * normal[2])
            if (!(length > INTERSECTION_EPSILON)) return null
            return DoubleArray(3) { normal[it] / length }
        }

        private fun side(points: List<FloatArray>, origin: FloatArray, normal: DoubleArray): DoubleArray =
            DoubleArray(3) { index ->
                (0..2).sumOf { (points[index][it] - origin[it]) * normal[it] }
            }

        /** Where the plane cuts the triangle, projected on the line both planes share. */
        private fun chordOn(points: List<FloatArray>, distances: DoubleArray, axis: DoubleArray): DoubleArray? {
            val crossings = ArrayList<Double>(2)
            for (index in 0..2) {
                val next = (index + 1) % 3
                if (distances[index] * distances[next] >= 0.0) continue
                val t = distances[index] / (distances[index] - distances[next])
                crossings += (0..2).sumOf { component ->
                    (points[index][component] + (points[next][component] - points[index][component]) * t) * axis[component]
                }
            }
            if (crossings.size < 2) return null
            return doubleArrayOf(crossings.minOrNull()!!, crossings.maxOrNull()!!)
        }

        private infix fun DoubleArray.cross(other: DoubleArray): DoubleArray? {
            val out = doubleArrayOf(
                this[1] * other[2] - this[2] * other[1],
                this[2] * other[0] - this[0] * other[2],
                this[0] * other[1] - this[1] * other[0]
            )
            val length = sqrt(out[0] * out[0] + out[1] * out[1] + out[2] * out[2])
            if (!(length > INTERSECTION_EPSILON)) return null
            return DoubleArray(3) { out[it] / length }
        }
    }

    private data class HairpinIntervals(
        val bySection: Map<String, List<ClosedFloatingPointRange<Double>>>,
        val stations: JSONArray
    ) {
        fun stationIndexOf(station: Double): Int =
            (0 until stations.length()).first { stations.getDouble(it) == station }
    }

    /**
     * The section and helper-station intervals whose rest path turns by more than a right angle — the
     * only places a swept tube can fold. Read from the manifest's own basis, so the exemption is a
     * property of the anatomy and not a list of names.
     */
    private fun hairpinIntervals(): HairpinIntervals {
        val document = manifest()
        val helpers = document.getJSONArray("helpers")
        val groups = LinkedHashMap<String, MutableList<JSONObject>>()
        for (index in 0 until helpers.length()) {
            val helper = helpers.getJSONObject(index)
            val key = helper.getString("muscleId") + "_" + helper.getString("side")
            groups.getOrPut(key) { ArrayList() } += helper
        }
        val out = LinkedHashMap<String, MutableList<ClosedFloatingPointRange<Double>>>()
        for ((key, list) in groups) {
            for (index in 0 until list.size - 1) {
                val first = list[index].getJSONArray("basis").getJSONArray(1)
                val second = list[index + 1].getJSONArray("basis").getJSONArray(1)
                val dot = (0 until 3).sumOf { component -> first.getDouble(component) * second.getDouble(component) }
                if (Math.toDegrees(Math.acos(dot.coerceIn(-1.0, 1.0))) > HAIRPIN_DEGREES) {
                    out.getOrPut(key) { ArrayList() } +=
                        list[index].getDouble("station")..list[index + 1].getDouble("station")
                }
            }
        }
        return HairpinIntervals(out, document.getJSONArray("stations"))
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

        /** A turn this sharp between two helper stations folds any tube of the muscle's own radius. */
        const val HAIRPIN_DEGREES = 90.0

        /** Total folded area the stand-in's hairpin may cost: it disappears with the authored asset. */
        const val FOLDED_AREA_SQUARE_METRES = 5e-5
        const val TOLERANCE_METRES = 1e-3
        const val STATIONS = 4

        /** Vertices around the path; the exporter's own ring, mirrored here as the section's contract. */
        const val RING_SEGMENTS = 8

        /** Rings per head, so a bend between two helper stations is interpolated rather than cut. */
        const val RING_COUNT = 13

        /** The two capped ends a head adds to its rings. */
        const val VERTICES_PER_HEAD = RING_COUNT * RING_SEGMENTS + 2

        /** A weight sum may drift by this much before the skin starts pulling the surface. */
        const val WEIGHT_SUM_TOLERANCE = 1e-4

        /** Two triangles must share this much chord before they count as crossing, not touching. */
        const val INTERSECTION_EPSILON = 1e-9
    }
}
