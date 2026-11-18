package com.monkfitness.app.anatomy

import com.monkfitness.app.animation.Joint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The segment vocabulary's shape: one spec per instance, a topological declaration order, and a
 * pole chain for exactly the rows whose chain states a plane.
 */
class SegmentSpecCoverageTest {

    @Test
    fun theVocabularyIsFourteenSegmentsAndTwentyThreeInstances() {
        assertEquals("segment types", 14, Segment.entries.size)
        assertEquals("segment instances", 23, SegmentId.entries.size)
        assertEquals(SegmentId.entries.toSet(), SegmentSpec.ALL.map { it.id }.toSet())
        assertEquals("one spec per id", SegmentId.entries.size, SegmentSpec.ALL.size)
    }

    @Test
    fun everyInstanceDeclaresTheSegmentItBelongsToAndItsSideSuffix() {
        for (id in SegmentId.entries) {
            val spec = SegmentSpec.of(id)
            assertEquals("${id.name} spec names its segment", id.segment, spec.segment)
            val expectedSuffix = when (id.segment) {
                Segment.PELVIS, Segment.LUMBAR, Segment.THORAX, Segment.NECK, Segment.SKULL -> null
                else -> id.name.removePrefix(id.segment.name)
            }
            assertEquals("${id.name} suffix", expectedSuffix, id.suffix)
        }
        assertEquals(
            "the arms use _A/_P and the legs _F/_B; no instance borrows Side",
            setOf("_A", "_P", "_F", "_B"),
            SegmentId.entries.mapNotNull { it.suffix }.toSet()
        )
    }

    @Test
    fun aParentAlwaysPrecedesItsChildrenInDeclarationOrder() {
        val index = SegmentId.entries.withIndex().associate { (i, id) -> id to i }
        for (id in SegmentId.entries) {
            val parent = SegmentSpec.of(id).parent ?: continue
            assertTrue(
                "${id.name} follows its parent ${parent.name}: the derivation walks entries once, parent first",
                index.getValue(parent) < index.getValue(id)
            )
        }
        assertEquals(
            "the spec table is declared in the same topological order as the ids",
            SegmentId.entries.toList(),
            SegmentSpec.ALL.map { it.id }
        )
    }

    @Test
    fun aBilateralRowMirrorsItsParentItsJointsAndItsInstances() {
        for (id in SegmentId.entries) {
            val mirror = SegmentId.mirror(id) ?: continue
            assertEquals("${id.name} mirrors ${mirror.name}", id, SegmentId.mirror(mirror))
            assertEquals("${id.name} mirror is its own segment", id.segment, mirror.segment)
            val spec = SegmentSpec.of(id)
            val other = SegmentSpec.of(mirror)
            val mirroredParent = spec.parent?.let { SegmentId.mirror(it) ?: it }
            assertEquals("${id.name} parent mirrors", mirroredParent, other.parent)
            assertEquals("${id.name} derivation mirrors", spec.derives, other.derives)
        }
        assertEquals(
            "nine bilateral rows, eighteen instances",
            18,
            SegmentId.entries.count { SegmentId.mirror(it) != null }
        )
    }

    @Test
    fun onlyTheRowsWithoutASeparableDirectionInheritTheirParentFrame() {
        val inherited = SegmentId.entries.filter { SegmentSpec.of(it).inherits }.toSet()
        assertEquals(
            "the terminal rows and the coincident clavicle rows inherit; every other row derives",
            setOf(
                SegmentId.SKULL,
                SegmentId.CLAVICLE_A, SegmentId.CLAVICLE_P,
                SegmentId.TOES_F, SegmentId.TOES_B
            ),
            inherited
        )
        for (id in SegmentId.entries) {
            val spec = SegmentSpec.of(id)
            if (spec.inherits) {
                assertNull("${id.name} has no rest direction of its own", SegmentRest.restDir(id))
            } else {
                assertNotNull("${id.name} declares its own rest direction", SegmentRest.restDir(id))
                assertNotNull("${id.name} has the node its direction is measured from", spec.node)
                assertNotNull("${id.name} has the child its direction points at", spec.child)
            }
        }
    }

    @Test
    fun thePoleChainExistsForTheEightMeasurableTypesAndForNoStructuralRow() {
        val measured = SegmentId.entries.filter { PoleComparator.chainOf(it) != null }
        assertEquals("fourteen measurable instances", 14, measured.size)
        assertEquals(
            "eight measurable segment types",
            setOf(
                Segment.THORAX, Segment.NECK,
                Segment.HUMERUS, Segment.FOREARM, Segment.HAND,
                Segment.FEMUR, Segment.TIBIA, Segment.FOOT
            ),
            measured.map { it.segment }.toSet()
        )

        val unavailable = SegmentId.entries.filter { PoleComparator.chainOf(it) == null }
        assertEquals("nine structurally unmeasurable instances", 9, unavailable.size)
        assertEquals(
            "scapula is in the list, where the RFC's question-11 note omitted it",
            setOf(
                Segment.PELVIS, Segment.LUMBAR, Segment.SKULL,
                Segment.CLAVICLE, Segment.SCAPULA, Segment.TOES
            ),
            unavailable.map { it.segment }.toSet()
        )
    }

    @Test
    fun aPoleChainEndsInTheInstanceItMeasures() {
        for (id in SegmentId.entries) {
            val chain = PoleComparator.chainOf(id) ?: continue
            val spec = SegmentSpec.of(id)
            assertEquals("${id.name} chain has three joints", 3, chain.size)
            assertEquals("${id.name} chain's middle joint is the segment's node", spec.node, chain[1])
            assertEquals("${id.name} chain's last joint is the segment's child", spec.child, chain[2])
            assertTrue("${id.name} chain starts at another joint", chain[0] != chain[1])
            assertTrue(
                "${id.name} chain's first and last joints differ, so the plane is spanned",
                chain[0] != chain[2]
            )
        }
    }

    @Test
    fun aRowThatInheritsItsFrameHasNoChainToMeasure() {
        for (id in SegmentId.entries) {
            if (SegmentSpec.of(id).inherits) {
                assertNull("${id.name} inherits, so no bend plane is claimed for it", PoleComparator.chainOf(id))
            }
        }
    }

    @Test
    fun everyNameableJointOfASpecIsAnEngineJoint() {
        val declared = SegmentSpec.ALL.flatMap { listOfNotNull(it.node, it.child) }.toSet()
        assertTrue(
            "every spec joint is a Joint value: $declared",
            declared.all { joint -> Joint.entries.any { it == joint } }
        )
        assertTrue("the specs name more than one joint", declared.size > 5)
    }
}
