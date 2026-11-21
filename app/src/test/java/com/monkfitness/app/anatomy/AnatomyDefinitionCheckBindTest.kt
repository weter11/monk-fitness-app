package com.monkfitness.app.anatomy

import com.monkfitness.app.animation.SkeletonDefinition
import com.monkfitness.app.animation.SkeletonPose
import com.monkfitness.app.animation.Vector3
import com.monkfitness.app.character.X1Bind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * §7.4.2 L3: the bind-stance obstacle rows are a property of the definition, so the shipped one must
 * produce none and a definition that swallows an attachment must name the head it swallows.
 */
class AnatomyDefinitionCheckBindTest {

    private val skeleton = SkeletonDefinition.DEFAULT_ADULT
    private val scale = X1Bind.unitsPerMetre(skeleton).toDouble()
    private val definition: AnatomyDefinition = AnatomyDefinitionLoader.load(File(ASSET).readText())

    private fun bindPose(): SkeletonPose {
        val pose = SkeletonPose()
        for ((joint, v) in X1Bind.bindJoints(skeleton)) pose.setJoint(joint, Vector3(v.x, v.y, v.z))
        return pose
    }

    @Test
    fun theShippedDefinitionLeavesNoAttachmentInsideItsOwnObstacles() {
        val rows = AnatomyDefinitionCheck.bindStance(definition, skeleton, bindPose(), scale)
        assertTrue("the tuned placeholders must clear their own attachments, was $rows", rows.isEmpty())
        assertTrue("and the combined report stays clean", AnatomyDefinitionCheck.check(definition, rows).isClean)
    }

    @Test
    fun anObstacleThatSwallowsAnAttachmentIsReportedWithTheHeadItSwallows() {
        val muscle = definition.muscles.first()
        val head = muscle.heads.first().origin
        // A sphere centred on the head's own attachment point: every station of every head that wraps
        // it is inside, and the row must name this muscle rather than "the definition".
        val swallowing = definition.copy(
            wrapSurfaces = definition.wrapSurfaces + WrapSurface(
                id = "swallowing",
                segment = head.segment,
                centreMetres = Triple(head.x, head.y, head.z),
                radiiMetres = Triple(0.20f, 0.20f, 0.20f),
                global = true
            )
        )
        val rows = AnatomyDefinitionCheck.bindStance(swallowing, skeleton, bindPose(), scale)
        assertTrue("an attachment at the centre is distance zero", rows.isNotEmpty())
        assertTrue(rows.all { it.kind == DefinitionIssueKind.ATTACHMENT_INSIDE_OBSTACLE })
        assertTrue(
            "every row names a head and the obstacle it is inside: $rows",
            rows.all { it.subject.endsWith("/A") || it.subject.endsWith("/P") } &&
                rows.all { it.detail.contains("swallowing") }
        )
        assertTrue(
            "the muscle whose attachment sits at the centre is named",
            rows.any { it.subject.startsWith("${muscle.id}/") }
        )
    }

    @Test
    fun theStaticOverloadStillReportsOnlyStructuralIssues() {
        val static = AnatomyDefinitionCheck.check(definition)
        assertTrue(static.isClean)
        assertEquals(
            "the pose-free overload must not invent bind rows",
            emptyList<DefinitionIssue>(),
            AnatomyDefinitionCheck.check(definition, emptyList()).issues
        )
    }

    private companion object {
        const val ASSET = "src/main/assets/anatomy/anatomy_v1.json"
    }
}
