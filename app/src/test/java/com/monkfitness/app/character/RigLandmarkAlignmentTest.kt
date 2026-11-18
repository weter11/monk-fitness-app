package com.monkfitness.app.character

import com.monkfitness.app.animation.Joint
import com.monkfitness.app.animation.SkeletonDefinition
import com.monkfitness.app.animation.Vector3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.acos

/**
 * A1: every mapped bone head coincides with its engine joint at the X1 bind frame, within
 * `TAU_BIND_MM`. The receptor is the asset itself, converted with its own basis and scale, so a
 * generator that writes the rig from the definition passes by construction and a hand-authored rig
 * does not pass by accident.
 */
class RigLandmarkAlignmentTest {

    private val definition: SkeletonDefinition = SkeletonDefinition.DEFAULT_ADULT
    private val unitsPerMetre = X1Bind.unitsPerMetre(definition)
    private val tolerance = X1Bind.engineUnits(X1Bind.TAU_BIND_MM, unitsPerMetre)

    private fun rig(): GltfRig = GltfRig.parse(
        File("src/main/assets/characters/humanoid_v2.glb").readBytes()
    )

    private class Row(
        val joint: Joint,
        val bone: String,
        val engine: Vector3,
        val expected: Vector3,
        val residual: Float
    )

    private fun rows(rig: GltfRig, unitsPerMetre: Float = this.unitsPerMetre): List<Row> {
        val world = rig.restWorldTranslations()
        val bind = X1Bind.bindJoints(definition)
        return BoneMap.byJoint.map { (joint, bone) ->
            val node = rig.nodeByName(bone) ?: error("rig has no bone $bone")
            val p = world[node]
            val engine = X1Bind.toEngine(Vector3(p[0], p[1], p[2]), unitsPerMetre)
            val expected = bind.getValue(joint)
            Row(joint, bone, engine, expected, magnitude(engine, expected))
        }
    }

    private fun magnitude(a: Vector3, b: Vector3): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        val dz = a.z - b.z
        return kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
    }

    @Test
    fun everyMappedBoneHeadSitsOnItsEngineJoint() {
        val measured = rows(rig())
        val worst = measured.maxByOrNull { it.residual }!!
        assertEquals("A1 covers exactly the mapped joints", BoneMap.byJoint.size, measured.size)
        assertTrue(
            "A1: ${measured.count { it.residual <= tolerance }}/${measured.size} within " +
                "${X1Bind.TAU_BIND_MM} mm; worst ${worst.joint} at ${worst.residual} u (${worst.residual * 1000f / unitsPerMetre} mm)",
            measured.all { it.residual <= tolerance }
        )
    }

    @Test
    fun thePositionRowIsWhatDiscriminatesAndAWrongBasisFailsIt() {
        val rig = rig()
        // Same rig, same joints, but the conversion the runtime would apply without de basis: the
        // asset's +Z remains forward, so every lateral joint lands 90 degrees away from its joint.
        val unrotated = rows(rig, unitsPerMetre).map { row ->
            val node = rig.nodeByName(row.bone)!!
            val p = rig.restWorldTranslations()[node]
            val engine = Vector3(p[0] * unitsPerMetre, p[1] * unitsPerMetre, p[2] * unitsPerMetre)
            magnitude(engine, row.expected)
        }
        assertTrue(
            "a missing basis must fail A1, not pass it: worst ${unrotated.max()} u",
            unrotated.max() > 100f * tolerance
        )
    }

    @Test
    fun theRestOrientationRowIsByConstructionOnAGeneratedRig() {
        val rig = rig()
        val rotations = rig.restWorldRotations()
        val worstDegrees = rig.jointNodes.maxOf { node ->
            val q = rotations[node]
            Math.toDegrees(2.0 * acos(abs(q.w).coerceIn(0f, 1f).toDouble())).toFloat()
        }
        // Every joint's author rest is identity, so A1's orientation half cannot fail here: the row
        // is a formality and the position row above is the one carrying the discrimination. It is
        // asserted anyway because the inverse binds below are computed against it.
        assertTrue("rest rotations must be identity, worst $worstDegrees deg", worstDegrees < 1e-3f)
        assertTrue("no node may be authored as a raw matrix", rig.nodes.none { it.usesMatrix })
    }

    @Test
    fun everyInverseBindInvertsItsRestWorld() {
        val rig = rig()
        val world = rig.restWorldTransforms()
        var worst = 0f
        var at = ""
        for (i in rig.jointNodes.indices) {
            val product = Mat4.multiply(rig.inverseBinds[i], world[rig.jointNodes[i]])
            val identity = Mat4.identity()
            for (k in 0 until 16) {
                val residual = abs(product[k] - identity[k])
                if (residual > worst) {
                    worst = residual
                    at = rig.nodes[rig.jointNodes[i]].name + "[$k]"
                }
            }
        }
        // Asset-space tolerance: 1 mm at the pelvis registration is ~2.5e-4 of a unit matrix entry.
        assertTrue("IBM·rest_world deviates at $at by $worst", worst < 1e-4f)
    }

    @Test
    fun theCoincidentGirdlePivotsAreDistinctSkinJoints() {
        val rig = rig()
        val world = rig.restWorldTranslations()
        val shoulder = world[rig.nodeByName("LeftShoulder")!!]
        val scapula = world[rig.nodeByName("LeftScapula")!!]
        val chest = world[rig.nodeByName("Chest")!!]
        assertEquals("shoulder and chest coincide in the X1 frame", 0f, magnitude(v(shoulder), v(chest)), 1e-6f)
        assertEquals("scapula and shoulder coincide in the X1 frame", 0f, magnitude(v(scapula), v(shoulder)), 1e-6f)
        assertTrue(
            "both coincident girdle pivots must still be skin joints",
            rig.jointNodes.toSet().containsAll(
                listOf(rig.nodeByName("LeftShoulder")!!, rig.nodeByName("LeftScapula")!!)
            )
        )
    }

    private fun v(p: FloatArray): Vector3 = Vector3(p[0], p[1], p[2])
}
