package com.monkfitness.app

import com.monkfitness.app.animation.*
import org.junit.Assert.*
import org.junit.Test

/**
 * UNI-7 — the clavicle was a dead node: CHEST -> CLAVICLE -> SCAPULA -> SHOULDER existed but
 * no code ever rotated the clavicle, so all girdle motion was carried by the scapula. This
 * test proves the clavicle now has a real, driven degree of freedom:
 *  1. [SkeletonMath.buildClavicularRotation] produces a genuine (non-identity) rotation for
 *     non-zero activation and is a no-op at zero activation.
 *  2. Applying a clavicular elevation to the clavicle node actually moves the shoulder (glenoid)
 *     through FK — i.e. the node is no longer a rigid pass-through.
 */
class ClavicleBehaviourTest {

    private val def = SkeletonDefinition.DEFAULT_ADULT
    private val zero = Vector3(0f, 0f, 0f)
    private val identity = JointRotation()

    @Test
    fun testBuildClavicularRotationProducesRealRotation() {
        val rot = JointRotation()
        SkeletonMath.buildClavicularRotation(1f, 0f, 0f, -1f, rot)
        assertTrue("non-zero clavicular activation must produce a real rotation", rot.angle > 1e-3f)
    }

    @Test
    fun testBuildClavicularRotationIsNoOpAtZeroActivation() {
        val rot = JointRotation()
        SkeletonMath.buildClavicularRotation(0f, 0f, 0f, -1f, rot)
        assertEquals("zero clavicular activation must be identity", 0f, rot.angle, 1e-5f)
    }

    @Test
    fun testClavicleElevationRaisesShoulderViaFK() {
        val nodes = SkeletonFactory.createStandardSkeleton()
        // Give the shoulder a realistic offset from the clavicle (through the scapula) so a
        // clavicular rotation actually displaces it via FK.
        nodes.chest.localPosition.set(0f, def.torsoLength, 0f)
        nodes.shoulderA.localPosition.set(0f, 0f, -def.shoulderWidth)

        // Neutral-clavicle baseline.
        nodes.roots.forEach { it.updateWorldTransforms(zero, identity) }
        val shoulderNeutralY = nodes.shoulderA.worldPosition.y

        // Drive a clavicular elevation and re-run FK.
        SkeletonMath.buildClavicularRotation(1f, 0f, 0f, -1f, nodes.clavicleA.localRotation)
        nodes.roots.forEach { it.updateWorldTransforms(zero, identity) }
        val shoulderElevatedY = nodes.shoulderA.worldPosition.y

        val dy = shoulderElevatedY - shoulderNeutralY
        assertTrue("clavicular elevation must raise the shoulder (glenoid) via FK", dy > 1f)
    }

    @Test
    fun testClavicleElevationIsSymmetricAcrossGirdles() {
        val nodes = SkeletonFactory.createStandardSkeleton()
        nodes.chest.localPosition.set(0f, def.torsoLength, 0f)
        nodes.shoulderA.localPosition.set(0f, 0f, -def.shoulderWidth)
        nodes.shoulderP.localPosition.set(0f, 0f, def.shoulderWidth)

        SkeletonMath.buildClavicularRotation(1f, 0f, 0f, -1f, nodes.clavicleA.localRotation)
        SkeletonMath.buildClavicularRotation(1f, 0f, 0f, 1f, nodes.clavicleP.localRotation)
        nodes.roots.forEach { it.updateWorldTransforms(zero, identity) }

        val dyA = nodes.shoulderA.worldPosition.y - def.torsoLength
        val dyP = nodes.shoulderP.worldPosition.y - def.torsoLength
        assertTrue("left clavicle elevation must raise the left shoulder, got dyA=$dyA", dyA > 1f)
        assertTrue("right clavicle elevation must raise the right shoulder, got dyP=$dyP", dyP > 1f)
    }
}
