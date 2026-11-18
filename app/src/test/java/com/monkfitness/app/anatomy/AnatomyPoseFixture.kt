package com.monkfitness.app.anatomy

import com.monkfitness.app.PoseFrameSweep
import com.monkfitness.app.animation.Joint
import com.monkfitness.app.animation.PoseContext
import com.monkfitness.app.animation.Side
import com.monkfitness.app.animation.SkeletonDefinition
import com.monkfitness.app.animation.SkeletonPipeline
import com.monkfitness.app.animation.SkeletonPose
import com.monkfitness.app.animation.Vector3
import com.monkfitness.app.character.X1Bind
import com.monkfitness.app.poses.PoseRegistry
import kotlin.math.sqrt

/**
 * The X1 bind pose and the published-frame samples the anatomy tests measure, both computed from
 * [SkeletonDefinition] rather than transcribed.
 *
 * The bind frame itself is [X1Bind]: one Kotlin source for the canonical A-pose, shared with the rig
 * generator's contract, so an anatomy test cannot measure a frame the character does not ship.
 */
object AnatomyPoseFixture {

    val DEFINITION: SkeletonDefinition = SkeletonDefinition.DEFAULT_ADULT

    /** The 594-frame sweep's stations: `0, 0.125 … 0.875, 0.999`. */
    val STATIONS = floatArrayOf(0f, 0.125f, 0.25f, 0.375f, 0.5f, 0.625f, 0.75f, 0.875f, 0.999f)

    /** The four exercises the roll question is asked about. */
    val PILOTS = listOf("squat_standard", "pushup_standard", "birddog_hold", "row_standard")

    /** Every registered builder, in a stable order. */
    val BUILDERS: List<String> = PoseRegistry.animationIds.sorted()

    /**
     * Direction agreement the tests assert of a computed rotation, in radians. A verification
     * tolerance rather than a runtime threshold, kept below [SegmentRest.TAU_ROT].
     */
    const val TAU_DIR = 1e-5f

    /**
     * The canonical straight-limbed A-pose, computed from the definition by [X1Bind]: `+Y` up, `+X`
     * forward, `+Z` lateral, side `A` on `−Z`.
     */
    fun bindJoints(definition: SkeletonDefinition = DEFINITION): Map<Joint, Vector3> =
        X1Bind.bindJoints(definition)

    fun bindPose(definition: SkeletonDefinition = DEFINITION): SkeletonPose = poseOf(bindJoints(definition))

    fun poseOf(joints: Map<Joint, Vector3>): SkeletonPose {
        val pose = SkeletonPose()
        for ((joint, v) in joints) pose.setJoint(joint, Vector3(v.x, v.y, v.z))
        return pose
    }

    /** A published frame copied by value out of the shared sweep, as a pose the derivation can read. */
    fun poseOf(frame: PoseFrameSweep.Frame): SkeletonPose = poseOf(frame.joints)

    /** The published frames of one builder at [STATIONS], through the production pipeline. */
    fun swept(builderId: String, definition: SkeletonDefinition = DEFINITION): List<SkeletonPose> =
        PoseFrameSweep.sweep(
            PoseRegistry.getPoseConfig(builderId)?.builder ?: error("no builder $builderId"),
            STATIONS,
            definition
        ).map { poseOf(it) }

    /** The pipeline's own frame, rotations and roots included — the purity test's input. */
    fun livePose(builderId: String, progress: Float, definition: SkeletonDefinition = DEFINITION): SkeletonPose {
        val config = PoseRegistry.getPoseConfig(builderId) ?: error("no builder $builderId")
        val pipeline = SkeletonPipeline(definition)
        val environment = config.builder.metadata.environment
        val support = config.builder.metadata.support.supportPoints
        repeat(11) {
            pipeline.produceFrame(
                config.builder.build(PoseContext(progress = 0.3f, side = Side.RIGHT, definition = definition, deltaTime = 0.0166f, cycleDuration = 2500f)),
                environment,
                support
            )
        }
        return pipeline.produceFrame(
            config.builder.build(PoseContext(progress = progress, side = Side.RIGHT, definition = definition, deltaTime = 0.0166f, cycleDuration = 2500f)),
            environment,
            support
        ).pose
    }

    /**
     * A pose whose pelvis frame is the triad `(Y × Z, Y, Z)` and whose femur points along [femur],
     * so the near-parallel and near-antipodal branches can be driven at a chosen pair of directions.
     */
    fun poseWithLegDirection(
        rootY: Vector3,
        rootZ: Vector3,
        femur: Vector3,
        definition: SkeletonDefinition = DEFINITION
    ): SkeletonPose {
        val joints = HashMap(bindJoints(definition))
        val pelvis = joints.getValue(Joint.PELVIS)
        joints[Joint.CHEST] = Vector3(
            pelvis.x + rootY.x * definition.torsoLength,
            pelvis.y + rootY.y * definition.torsoLength,
            pelvis.z + rootY.z * definition.torsoLength
        )
        val hip = Vector3(
            pelvis.x + rootZ.x * definition.hipWidth,
            pelvis.y + rootZ.y * definition.hipWidth,
            pelvis.z + rootZ.z * definition.hipWidth
        )
        joints[Joint.HIP_F] = hip
        joints[Joint.HIP_B] = Vector3(
            pelvis.x - rootZ.x * definition.hipWidth,
            pelvis.y - rootZ.y * definition.hipWidth,
            pelvis.z - rootZ.z * definition.hipWidth
        )
        joints[Joint.KNEE_F] = Vector3(
            hip.x + femur.x * definition.thighLength,
            hip.y + femur.y * definition.thighLength,
            hip.z + femur.z * definition.thighLength
        )
        return poseOf(joints)
    }

    /** The world direction the derivation is asked to place for [id] on [pose], or `null`. */
    fun publishedDirection(pose: SkeletonPose, id: SegmentId): Vector3? {
        val spec = SegmentSpec.of(id)
        val node = spec.node ?: return null
        val child = spec.child ?: return null
        return direction(pose.getJoint(node), pose.getJoint(child))
    }

    fun direction(from: Vector3, to: Vector3): Vector3? {
        val dx = to.x - from.x
        val dy = to.y - from.y
        val dz = to.z - from.z
        val length = sqrt(dx * dx + dy * dy + dz * dz)
        if (length <= 1e-6f) return null
        return Vector3(dx / length, dy / length, dz / length)
    }

    /** The frame of [id] applied to its own rest direction — the direction it claims to place. */
    fun placedDirection(set: SegmentFrameSet, id: SegmentId): Vector3? {
        val rest = SegmentRest.restDir(id) ?: return null
        return set.apply(id, rest, Vector3())
    }

    /**
     * `|a × b|` for unit vectors — the sine of the angle. A tolerance on a *direction* is compared
     * this way because `acos` of two nearly equal `Float` vectors cannot resolve better than ~3e-4
     * rad, so an `acos`-based check would test the carrier's quantisation, not the derivation.
     */
    fun directionError(a: Vector3, b: Vector3): Float =
        a.normalizedCopy().cross(b.normalizedCopy()).mag()

    fun angleBetween(a: Vector3, b: Vector3): Float {
        val dot = a.normalizedCopy().dot(b.normalizedCopy()).coerceIn(-1f, 1f)
        return kotlin.math.acos(dot)
    }

    /** Whole-frame deviation between [set] and the bind frame's columns. */
    fun deviationFromBindFrame(set: SegmentFrameSet, id: SegmentId): Float {
        var worst = 0f
        for (axis in 0..2) for (component in 0..2) {
            worst = maxOf(worst, kotlin.math.abs(set.axis(id, axis, component) - SegmentRest.BIND_FRAME[axis * 3 + component]))
        }
        return worst
    }
}
