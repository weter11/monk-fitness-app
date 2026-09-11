package com.monkfitness.app

import com.monkfitness.app.animation.*
import com.monkfitness.app.poses.*
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * P5 — `SupermanPose` prone-layout invariant (the invariant the P12 WP-D conversion violated).
 *
 * The BPS (`docs/Biomechanical Pose Specification (BPS)/Superman (Prone).md` §1/§3) specifies a
 * **prone (face-down)** posterior-chain exercise whose floor fulcrum is the anterior body
 * (pelvis/abdomen/anterior thighs), with the chest, head, arms and legs lifting OFF the floor.
 * The WP-D conversion reproduced the legacy world-position layout as "trunk toward −X with a +90°
 * root tilt" — the **supine** basis this engine uses for DeadBug/LegRaise — so the produced frame
 * rendered the body face-UP and buried the head 24.5 units below the pose's own declared ground.
 *
 * These assertions are read from the produced frame (never from the authoring code), and they are
 * deliberately blind to HOW the pose is authored: any future re-authoring that keeps the body
 * prone, floor-respecting and spine-articulated stays green.
 */
class SupermanPoseProneInvariantTest {

    private val def = SkeletonDefinition.DEFAULT_ADULT

    private fun frame(progress: Float): SkeletonPose {
        val pose = SupermanPose()
        val pipe = SkeletonPipeline(def)
        for (k in 0..10) {
            pipe.produceFrame(pose, PoseContext(progress = 0.3f, side = Side.RIGHT, definition = def,
                deltaTime = 0.0166f, cycleDuration = 2500f))
        }
        return pipe.produceFrame(pose, PoseContext(progress = progress, side = Side.RIGHT, definition = def,
            deltaTime = 0.0166f, cycleDuration = 2500f)).pose
    }

    private fun node(pose: SkeletonPose, j: Joint): SkeletonNode {
        fun find(n: SkeletonNode): SkeletonNode? {
            if (n.joint == j) return n
            for (c in n.children) find(c)?.let { return it }
            return null
        }
        for (r in pose.roots) find(r)?.let { return it }
        error("produced frame carries no $j node")
    }

    /** The pelvis basis in world space, derived from child world offsets (no rotation API). */
    private class Basis(val facing: Vector3, val spine: Vector3, val lateral: Vector3)

    private fun pelvisBasis(pose: SkeletonPose): Basis {
        val pel = node(pose, Joint.PELVIS).worldPosition
        val spine = Vector3().set(node(pose, Joint.CHEST).worldPosition).subtract(pel).normalize()
        // hipF carries the local offset (0, 0, -hipWidth) => pelvis - hipF is rot * +Z.
        val lateral = Vector3().set(pel).subtract(node(pose, Joint.HIP_F).worldPosition).normalize()
        val facing = spine.cross(lateral).normalize()
        return Basis(facing, spine, lateral)
    }

    // ---------------------------------------------------------------------------------------
    // 1. The orientation invariant (previously violated: the body rendered SUPINE)
    // ---------------------------------------------------------------------------------------

    @Test
    fun bodyLiesProneNotSupine() {
        val failures = mutableListOf<String>()
        for (p in arrayOf(0.0f, 0.25f, 0.5f, 0.75f, 1.0f)) {
            val b = pelvisBasis(frame(p))
            // Prone = the body's facing axis points DOWN (a lying body's ventral side faces the floor).
            if (b.facing.y > -0.9f) {
                failures.add("p=$p: body is NOT prone — facing axis y=${"%.3f".format(b.facing.y)} " +
                    "(> -0.9 means face-up/supine; the sibling prone pose measures -1.00)")
            }
            // The skeleton lies along the world X axis with the head end at +X.
            if (b.spine.x < 0.9f || abs(b.spine.y) > 0.35f) {
                failures.add("p=$p: skeleton is not lying along +X — spine=(${"%.2f".format(b.spine.x)}," +
                    "${"%.2f".format(b.spine.y)},${"%.2f".format(b.spine.z)})")
            }
            // Medio-lateral axis stays world Z (no roll about the long axis).
            if (abs(b.lateral.z) < 0.9f) {
                failures.add("p=$p: medio-lateral axis is not world Z — lateral=(${"%.2f".format(b.lateral.x)}," +
                    "${"%.2f".format(b.lateral.y)},${"%.2f".format(b.lateral.z)})")
            }
        }
        assertTrue("SupermanPose must be prone (BPS §1/§3):\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    // ---------------------------------------------------------------------------------------
    // 2. The floor invariant (previously violated: head 24.5u below the declared ground)
    // ---------------------------------------------------------------------------------------

    @Test
    fun noBodyPointPassesBelowTheDeclaredGround() {
        val ground = SupermanPose().metadata.environment.ground.level
        val failures = mutableListOf<String>()
        var p = 0f
        while (p <= 1.0001f) {
            val pose = frame(p)
            for (j in Joint.entries) {
                val y = pose.getJoint(j).y
                if (y < ground) {
                    failures.add("p=${"%.2f".format(p)} $j y=${"%.2f".format(y)} < declared ground ${"%.1f".format(ground)}")
                }
            }
            p += 0.1f
        }
        assertTrue(
            "the pose declares a visible ground (level ${"%.1f".format(ground)}) so no body point may pass through it " +
                "(pre-fix: HEAD_POS -24.48, FINGERTIPS_A -9.8 at p=0):\n" + failures.take(20).joinToString("\n") +
                (if (failures.size > 20) "\n… ${failures.size - 20} more" else ""),
            failures.isEmpty()
        )
    }

    // ---------------------------------------------------------------------------------------
    // 3. M4: the extension is articulated at the spine; the pelvis stays neutral and grounded
    // ---------------------------------------------------------------------------------------

    @Test
    fun extensionIsArticulatedAtTheSpineAndThePelvisStaysGrounded() {
        val rest = frame(0f)
        val top = frame(1f)

        // The pelvis is the floor fulcrum: it neither moves nor rotates with the rep (BPS §5 —
        // "the pelvis remains neutral and stays on the floor; the extension originates from the
        // paraspinals, not from tilting the pelvis"). If the tempo were carried on the root, the
        // pelvis->hip offset (a constant local offset under the pelvis frame) would rotate.
        val restPelvis = rest.getJoint(Joint.PELVIS)
        val topPelvis = top.getJoint(Joint.PELVIS)
        assertTrue("the pelvis must stay grounded (it is the floor fulcrum): y " +
            "${"%.2f".format(restPelvis.y)} -> ${"%.2f".format(topPelvis.y)}",
            abs(topPelvis.y - restPelvis.y) < 0.5f)
        assertTrue("the pelvis must not translate across the rep: x " +
            "${"%.2f".format(restPelvis.x)} -> ${"%.2f".format(topPelvis.x)}",
            abs(topPelvis.x - restPelvis.x) < 0.5f)
        val restHipOffset = Vector3().set(rest.getJoint(Joint.HIP_F)).subtract(restPelvis).normalize()
        val topHipOffset = Vector3().set(top.getJoint(Joint.HIP_F)).subtract(topPelvis).normalize()
        assertTrue(
            "the pelvis must not carry the rep's extension (author a constant prone layout on the root " +
                "and articulate at the spine): pelvis->hip direction moved from " +
                "(${"%.3f".format(restHipOffset.x)},${"%.3f".format(restHipOffset.y)},${"%.3f".format(restHipOffset.z)}) to " +
                "(${"%.3f".format(topHipOffset.x)},${"%.3f".format(topHipOffset.y)},${"%.3f".format(topHipOffset.z)})",
            restHipOffset.subtract(topHipOffset).mag() < 0.02f
        )

        // The SPINE carries the extension: flat at rest, articulated at the top of the rep through
        // the two-segment chain (thoracolumbar junction first, thoracic follows), exactly the
        // `buildSpineCurve(lumbar, chest, lower, thoracic)` relationship the repaired thoracic
        // poses use. The single-segment pelvis->chest rotation is what M4 rejects.
        val restLumbar = abs(node(rest, Joint.LUMBAR).localRotation.angle)
        val topLumbar = abs(node(top, Joint.LUMBAR).localRotation.angle)
        val topChestAngle = abs(node(top, Joint.CHEST).localRotation.angle)
        assertTrue("the lumbar must start flat (angle=${"%.4f".format(restLumbar)})", restLumbar < 1e-3f)
        assertTrue("the lumbar (thoracolumbar junction) must carry the rep's extension " +
            "(angle=${"%.4f".format(topLumbar)})", topLumbar > 0.15f)
        assertTrue("the thoracic segment must follow the junction " +
            "(chest=${"%.4f".format(topChestAngle)} vs lumbar=${"%.4f".format(topLumbar)})",
            topChestAngle > 0.05f && topChestAngle < topLumbar)

        // Anti-vacuity: the arch must actually LIFT the chest and the head off the floor, and the
        // head must stay clear of the floor at all times (BPS §3/§4).
        assertTrue("the chest must lift with the arch (${"%.1f".format(rest.getJoint(Joint.CHEST).y)} -> " +
            "${"%.1f".format(top.getJoint(Joint.CHEST).y)})",
            top.getJoint(Joint.CHEST).y > rest.getJoint(Joint.CHEST).y + 5f)
        assertTrue("the head must lift with the arch (${"%.1f".format(rest.getJoint(Joint.HEAD_POS).y)} -> " +
            "${"%.1f".format(top.getJoint(Joint.HEAD_POS).y)})",
            top.getJoint(Joint.HEAD_POS).y > rest.getJoint(Joint.HEAD_POS).y + 5f)
        assertTrue("the head must be lifted off the floor at rest (y=${"%.1f".format(rest.getJoint(Joint.HEAD_POS).y)})",
            rest.getJoint(Joint.HEAD_POS).y > 0f)
    }

    // ---------------------------------------------------------------------------------------
    // 4. The lift choreography survives (the pose must not be a frozen plank)
    // ---------------------------------------------------------------------------------------

    @Test
    fun limbsLiftOffTheFloorAcrossTheRep() {
        val rest = frame(0f)
        val top = frame(1f)
        assertTrue("legs must lift (TOE_F ${"%.1f".format(rest.getJoint(Joint.TOE_F).y)} -> " +
            "${"%.1f".format(top.getJoint(Joint.TOE_F).y)})",
            top.getJoint(Joint.TOE_F).y > rest.getJoint(Joint.TOE_F).y + 20f)
        assertTrue("arms must lift (HAND_A ${"%.1f".format(rest.getJoint(Joint.HAND_A).y)} -> " +
            "${"%.1f".format(top.getJoint(Joint.HAND_A).y)})",
            top.getJoint(Joint.HAND_A).y > rest.getJoint(Joint.HAND_A).y + 20f)
        assertTrue("the resting arms must hover just above the floor, not inside it " +
            "(HAND_A y=${"%.1f".format(rest.getJoint(Joint.HAND_A).y)})",
            rest.getJoint(Joint.HAND_A).y > 0f)
        assertTrue("arms reach overhead toward the head end (+X): HAND_A.x=${"%.1f".format(rest.getJoint(Joint.HAND_A).x)} " +
            "chest.x=${"%.1f".format(rest.getJoint(Joint.CHEST).x)}",
            rest.getJoint(Joint.HAND_A).x > rest.getJoint(Joint.CHEST).x)
        assertTrue("legs extend away from the head end (-X): TOE_F.x=${"%.1f".format(rest.getJoint(Joint.TOE_F).x)} " +
            "pelvis.x=${"%.1f".format(rest.getJoint(Joint.PELVIS).x)}",
            rest.getJoint(Joint.TOE_F).x < rest.getJoint(Joint.PELVIS).x)
    }
}
