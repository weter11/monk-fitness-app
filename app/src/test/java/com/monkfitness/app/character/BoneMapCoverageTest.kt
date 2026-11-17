package com.monkfitness.app.character

import com.monkfitness.app.animation.Joint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage of the declared map against the engine's own skeleton: every driven joint resolves from
 * the map's bone names, and the driven, undrivable and visual-only sets are named and disjoint.
 */
class BoneMapCoverageTest {

    @Test
    fun everyDeclaredJointResolvesFromTheMapsOwnBoneNames() {
        val resolved = BoneMap.resolve(BoneMap.byJoint.values)
        val missing = BoneMap.byJoint.keys - resolved.keys
        assertTrue("the map declares joints its own bone names cannot resolve: $missing", missing.isEmpty())
    }

    @Test
    fun theScapulaPairIsMappedByName() {
        assertEquals("LeftScapula", BoneMap.byJoint[Joint.SCAPULA_A])
        assertEquals("RightScapula", BoneMap.byJoint[Joint.SCAPULA_P])
        val resolved = BoneMap.resolve(BoneMap.byJoint.values)
        assertEquals("leftscapula", resolved[Joint.SCAPULA_A])
        assertEquals("rightscapula", resolved[Joint.SCAPULA_P])
    }

    @Test
    fun everyAliasResolvesItsOwnJointAndNoAliasIsShared() {
        val owner = HashMap<String, Joint>()
        BoneMap.aliases.forEach { (joint, names) ->
            assertTrue("$joint declares no alias", names.isNotEmpty())
            names.forEach { name ->
                val previous = owner.put(name, joint)
                assertNull("alias '$name' is claimed by both $previous and $joint", previous)
            }
        }
        BoneMap.aliases.forEach { (joint, names) ->
            names.forEach { name ->
                assertEquals("alias '$name' must resolve $joint", mapOf(joint to name), BoneMap.resolve(listOf(name)))
            }
        }
    }

    @Test
    fun theUndrivableJointsAreDeclaredAndCompleteTheEngineJointSet() {
        val engine = Joint.entries.toSet()
        assertEquals(10, BoneMap.UNMAPPED.size)
        assertEquals(
            "the engine declares ${engine.size} joints; the map declares " +
                "${BoneMap.byJoint.size} driven and ${BoneMap.UNMAPPED.size} undrivable",
            engine.size,
            BoneMap.byJoint.size + BoneMap.UNMAPPED.size
        )
        assertEquals("driven and undrivable joints overlap", emptySet<Joint>(), BoneMap.byJoint.keys intersect BoneMap.UNMAPPED.toSet())
        assertEquals(
            "engine joints the map neither drives nor declares undrivable",
            emptySet<Joint>(),
            engine - BoneMap.byJoint.keys - BoneMap.UNMAPPED.toSet()
        )
        assertEquals(
            "declared-undrivable joints the engine skeleton does not carry",
            emptySet<Joint>(),
            BoneMap.UNMAPPED.toSet() - engine
        )
    }

    @Test
    fun theVisualOnlyBonesAreListedAndDriveNoEngineJoint() {
        assertEquals("a crown and five single-segment fingers per hand", 11, BoneMap.VISUAL_ONLY.size)
        assertEquals("34 skin joints = 23 driven + visual-only", 34, BoneMap.byJoint.size + BoneMap.VISUAL_ONLY.size)
        val mapped = BoneMap.byJoint.values.map { GltfRig.normalizeName(it) }.toSet()
        val alsoMapped = BoneMap.VISUAL_ONLY.filter { GltfRig.normalizeName(it) in mapped }
        assertTrue("visual-only bones are also mapped: $alsoMapped", alsoMapped.isEmpty())
        assertEquals(
            "visual-only bones resolved to an engine joint",
            emptyMap<Joint, String>(),
            BoneMap.resolve(BoneMap.VISUAL_ONLY)
        )
    }
}
