package com.monkfitness.app.animation

import androidx.compose.animation.core.*
import androidx.compose.runtime.*
import kotlin.math.*
import com.monkfitness.app.poses.*
import androidx.compose.foundation.Canvas
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp

// ----- AnimationController.kt -----
@Deprecated("Use LoopMode", ReplaceWith("LoopMode"))
enum class AnimationMode {
    LOOP, // Repeats progress 0 -> 1 -> 0
    HOLD  // Progress driven by breathing cycle 0 -> 1 -> 0
}

@Stable
interface AnimationController {
    val progress: Float
    val side: Side
    val cameraYawOffset: Float
    fun onRotate(delta: Float)
}

private class BaseAnimationController : AnimationController {
    override var progress: Float by mutableFloatStateOf(0f)
    override var side: Side by mutableStateOf(Side.RIGHT)
    override var cameraYawOffset: Float by mutableFloatStateOf(0f)

    override fun onRotate(delta: Float) {
        cameraYawOffset = (cameraYawOffset + delta).coerceIn(-1.5708f, 1.5708f)
    }
}

@Composable
fun rememberAnimationController(
    metadata: PoseMetadata,
    alternating: Boolean = false // Still needed for side-switching exercises
): AnimationController {
    val controller = remember { BaseAnimationController() }
    val transition = rememberInfiniteTransition(label = "SkeletonAnimation")
    val durationMs = (metadata.durationSeconds * 1000).toInt()

    when (metadata.loopMode) {
        LoopMode.LOOP -> {
            if (alternating) {
                val totalProgress by transition.animateFloat(
                    initialValue = 0f,
                    targetValue = 2f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = durationMs * 2, easing = LinearEasing),
                        repeatMode = RepeatMode.Restart
                    ),
                    label = "AlternatingLoop"
                )

                LaunchedEffect(totalProgress) {
                    controller.side = if (totalProgress < 1f) Side.RIGHT else Side.LEFT
                    val p = if (totalProgress < 1f) totalProgress else totalProgress - 1f
                    controller.progress = if (p < 0.5f) {
                        val t = p * 2f
                        t * t * (3 - 2 * t)
                    } else {
                        val t = (1f - p) * 2f
                        t * t * (3 - 2 * t)
                    }
                }
            } else {
                val p by transition.animateFloat(
                    initialValue = 0f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = durationMs / 2, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "SimpleLoop"
                )
                LaunchedEffect(p) {
                    controller.progress = p
                    controller.side = Side.RIGHT
                }
            }
        }
        LoopMode.HOLD -> {
            val breathTime by transition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 8000, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart
                ),
                label = "BreathingCycle"
            )
            LaunchedEffect(breathTime) {
                controller.side = Side.RIGHT
                controller.progress = when {
                    breathTime < 0.40f -> smoothstep(breathTime / 0.40f)
                    breathTime < 0.52f -> 1f
                    breathTime < 0.92f -> 1f - smoothstep((breathTime - 0.52f) / 0.40f)
                    else -> 0f
                }
            }
        }
        LoopMode.PING_PONG -> {
            val p by transition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = durationMs, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "PingPongLoop"
            )
            LaunchedEffect(p) {
                controller.progress = p
                controller.side = Side.RIGHT
            }
        }
        LoopMode.ONCE -> {
            val p by transition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable( // Keep it infinite but clamped for now or use Animatable
                    animation = tween(durationMillis = durationMs, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Restart
                ),
                label = "Once"
            )
            LaunchedEffect(p) {
                controller.progress = p
                controller.side = Side.RIGHT
            }
        }
    }
    return controller
}

@Deprecated("Use PoseMetadata version", ReplaceWith("rememberAnimationController(PoseMetadata(loopMode = mode, durationSeconds = durationMs / 1000f), alternating)"))
@Composable
fun rememberAnimationController(
    mode: AnimationMode,
    durationMs: Int = 3000,
    alternating: Boolean = false
): AnimationController {
    val loopMode = when(mode) {
        AnimationMode.LOOP -> LoopMode.LOOP
        AnimationMode.HOLD -> LoopMode.HOLD
    }
    return rememberAnimationController(
        PoseMetadata(loopMode = loopMode, durationSeconds = durationMs / 1000f),
        alternating
    )
}

private fun smoothstep(x: Float): Float {
    val t = x.coerceIn(0f, 1f)
    return t * t * (3 - 2 * t)
}

// ----- AnimationRegistry.kt -----
object AnimationRegistry {
    private val registry = mutableMapOf<String, PoseBuilder>()

    init {
        // Core registrations
        register("world_greatest_stretch", WorldGreatestStretchPose())
        register("birddog_hold", BirdDogPose())
        register("birddog_reps", BirdDogPose())
        register("cat_cow_reps", CatCowPose())
        register("superman_prone", SupermanPose())
        register("pushup_standard", PushUpPose())
        register("pushup_wide", PushUpPose())
        register("pushup_military", PushUpPose())
        register("pushup_knee", PushUpPose())
        register("pushup_diamond", PushUpPose())
        register("pushup_decline", PushUpPose())
        register("pike_pushup_standard", PushUpPose())
        register("squat_standard", SquatPose())
        register("squat_sumo", SquatPose())
        register("squat_jump", SquatPose())
        register("deep_squat_hold", SquatPose())
    }

    fun register(animationId: String, builder: PoseBuilder) {
        registry[animationId] = builder
    }

    fun get(animationId: String): PoseBuilder? = registry[animationId]
}

// ----- AnimationState.kt -----
data class AnimationState(
    val progress: Float,
    val side: Side,
    val phase: Float = 0f,
    val playbackSpeed: Float = 1.0f,
    val loopIndex: Int = 0,
    val deltaTime: Float = 0f,
    val mirrored: Boolean = false
)

// ----- Bone.kt -----
data class Bone(
    val parentJoint: Joint,
    val childJoint: Joint,
    val thickness: Float,
    val colorMultiplier: Float = 1.0f
)

// ----- Camera.kt -----
/**
 * Camera is a generic mathematical projection utility.
 */
class Camera(
    var yaw: Float = 1.19f,
    var pitch: Float = 0.22f,
    var zoom: Float = 1.3f,
    var focalLength: Float = 1000f,
    var centerX: Float = 0.5f,
    var centerY: Float = 0.7f
) {
    constructor(definition: CameraDefinition) : this(
        yaw = definition.defaultYaw,
        pitch = definition.defaultPitch,
        zoom = definition.defaultZoom
    )

    /**
     * Projects a 3D world-space vector into the provided ProjectedPoint buffer.
     */
    fun project(v: Vector3, width: Float, height: Float, buffer: ProjectedPoint) {
        val cy = cos(yaw)
        val sy = sin(yaw)
        val xr = v.x * cy + v.z * sy
        val zr = -v.x * sy + v.z * cy

        val cp = cos(pitch)
        val sp = sin(pitch)
        val y2 = v.y * cp + zr * sp
        val z2 = zr * cp - v.y * sp

        val sc = focalLength / (focalLength + z2)

        buffer.update(
            x = width * centerX + xr * sc * zoom,
            y = height * centerY - y2 * sc * zoom,
            depth = z2,
            scale = sc
        )
    }
}

// ----- CameraDefinition.kt -----
data class CameraDefinition(
    val defaultYaw: Float = 1.19f,
    val defaultPitch: Float = 0.22f,
    val defaultZoom: Float = 1.3f,
    val minYaw: Float = -1.5708f, // -90 degrees
    val maxYaw: Float = 1.5708f,  // +90 degrees
    val minZoom: Float = 0.5f,
    val maxZoom: Float = 3.0f,
    val allowRotation: Boolean = true,
    val allowZoom: Boolean = true
) {
    companion object {
        val DEFAULT = CameraDefinition()
    }
}

// ----- FootDefinition.kt -----
data class FootDefinition(
    val footLength: Float,
    val heelRatio: Float = 0.29f,
    val toeRatio: Float = 0.71f,
    val ankleHeight: Float = 15f,
    val minPitch: Float = -45f * PI.toFloat() / 180f,
    val maxPitch: Float = 45f * PI.toFloat() / 180f
) {
    /**
     * Procedurally computes Heel and Toe positions from Ankle and a forward direction.
     * The ankle remains the rotational pivot (origin in this context).
     */
    fun computeHeelToe(ankle: Vector3, forward: Vector3, outHeel: Vector3, outToe: Vector3) {
        val dir = forward.normalizedCopy()
        outHeel.set(dir).multiply(-(footLength * heelRatio)).add(ankle)
        outToe.set(dir).multiply(footLength * toeRatio).add(ankle)
    }
}

// ----- HandDefinition.kt -----
/**
 * HandDefinition describes the anatomical structure of the hand.
 * All positions are offsets relative to the wrist (0,0,0).
 */
data class HandDefinition(
    val palmLength: Float = 12f,
    val fingerLength: Float = 10f,
    val handWidth: Float = 10f
) {
    // Relative to wrist (0,0,0) in hand-local space (X is forward)
    val wristLocal = Vector3(0f, 0f, 0f)
    val palmLocal = Vector3(palmLength * 0.5f, 0f, 0f)
    val knucklesLocal = Vector3(palmLength, 0f, 0f)
    val fingertipsLocal = Vector3(palmLength + fingerLength, 0f, 0f)

    /**
     * Computes the positions of the hand joints in world space.
     * @param wrist The world position of the wrist joint.
     * @param direction The forward direction of the hand.
     */
    fun computeHandJoints(wrist: Vector3, direction: Vector3, result: HandJoints) {
        val dir = direction.normalizedCopy()
        result.wrist.set(wrist)
        result.palm.set(dir).multiply(palmLength * 0.5f).add(wrist)
        result.knuckles.set(dir).multiply(palmLength).add(wrist)
        result.fingertips.set(dir).multiply(palmLength + fingerLength).add(wrist)
    }
}

class HandJoints(
    val wrist: Vector3 = Vector3(),
    val palm: Vector3 = Vector3(),
    val knuckles: Vector3 = Vector3(),
    val fingertips: Vector3 = Vector3()
)

// ----- Joint.kt -----
enum class Joint(val index: Int) {
    PELVIS(0),
    HIP_F(1),
    HIP_B(2),
    KNEE_F(3),
    ANKLE_F(4),
    HEEL_F(5),
    TOE_F(6),
    KNEE_B(7),
    ANKLE_B(8),
    HEEL_B(9),
    TOE_B(10),
    CHEST(11),
    SHOULDER_A(12),
    SHOULDER_P(13),
    ELBOW_A(14),
    HAND_A(15),
    WRIST_A(16),
    PALM_A(17),
    KNUCKLES_A(18),
    FINGERTIPS_A(19),
    ELBOW_P(20),
    HAND_P(21),
    WRIST_P(22),
    PALM_P(23),
    KNUCKLES_P(24),
    FINGERTIPS_P(25),
    NECK_END(26),
    HEAD_POS(27)
}

// ----- PoseBuilder.kt -----
interface PoseBuilder {
    @Deprecated("Use metadata.camera", ReplaceWith("metadata.camera"))
    val defaultCamera: CameraDefinition get() = metadata.camera

    val metadata: PoseMetadata get() = PoseMetadata(camera = CameraDefinition.DEFAULT)

    fun build(context: PoseContext): SkeletonPose

    // Kept for backward compatibility if needed, but build(context) is now preferred
    @Deprecated("Use build(context: PoseContext)", ReplaceWith("build(context)"))
    fun evaluate(progress: Float, side: Side, definition: SkeletonDefinition): SkeletonPose {
        return build(PoseContext(progress, side, definition))
    }
}

enum class Side {
    LEFT, RIGHT
}

// ----- PoseContext.kt -----
data class PoseContext(
    val state: AnimationState,
    val definition: SkeletonDefinition,
    val cycleDuration: Float = 3000f
) {
    val progress: Float get() = state.progress
    val side: Side get() = state.side
    val deltaTime: Float get() = state.deltaTime
    val playbackSpeed: Float get() = state.playbackSpeed
    val mirrored: Boolean get() = state.mirrored
    val phase: Float get() = state.phase
    val loopIndex: Int get() = state.loopIndex

    constructor(
        progress: Float,
        side: Side,
        definition: SkeletonDefinition,
        deltaTime: Float = 0f,
        cycleDuration: Float = 3000f,
        playbackSpeed: Float = 1.0f,
        mirrored: Boolean = false,
        phase: Float = 0f,
        loopIndex: Int = 0
    ) : this(
        state = AnimationState(
            progress = progress,
            side = side,
            phase = phase,
            playbackSpeed = playbackSpeed,
            loopIndex = loopIndex,
            deltaTime = deltaTime,
            mirrored = mirrored
        ),
        definition = definition,
        cycleDuration = cycleDuration
    )
}

// ----- PoseDefinition.kt -----
/**
 * Encapsulates the joint positions and rotations for a specific frame.
 * Now owns a Scene Graph hierarchy and provides backward compatibility.
 */
class SkeletonPose(
    val joints: Array<Vector3> = Array(Joint.entries.size) { Vector3() },
    val rotations: Array<JointRotation> = Array(Joint.entries.size) { JointRotation() },
    var roots: List<SkeletonNode> = emptyList()
) {
    fun getJoint(id: Joint): Vector3 = joints[id.index]

    fun setJoint(id: Joint, v: Vector3) {
        joints[id.index].set(v)
    }

    fun getJointRotation(id: Joint): JointRotation = rotations[id.index]

    fun setJointRotation(id: Joint, r: JointRotation) {
        rotations[id.index].copyFrom(r)
    }

    fun copyFrom(other: SkeletonPose) {
        for (i in joints.indices) {
            joints[i].set(other.joints[i])
            rotations[i].copyFrom(other.rotations[i])
        }
        this.roots = other.roots
    }

    companion object {
        // Cached identity rotation to avoid allocations
        private val IDENTITY_ROTATION = JointRotation()
        private val ZERO_VECTOR = Vector3(0f, 0f, 0f)

        /**
         * Factory method to build a pose from a Scene Graph hierarchy.
         * Updates transforms and flattens into the compatible joint map.
         */
        fun fromHierarchy(
            roots: List<SkeletonNode>,
            targetPose: SkeletonPose
        ): SkeletonPose {
            for (root in roots) {
                root.updateWorldTransforms(ZERO_VECTOR, IDENTITY_ROTATION)
                root.flatten(targetPose)
            }
            targetPose.roots = roots
            return targetPose
        }
    }
}

// ----- PoseMetadata.kt -----
enum class LoopMode {
    LOOP,
    HOLD,
    PING_PONG,
    ONCE
}

enum class FacingDirection {
    FRONT,
    LEFT,
    RIGHT
}

data class PoseMetadata(
    val camera: CameraDefinition = CameraDefinition.DEFAULT,
    val durationSeconds: Float = 3.0f,
    val loopMode: LoopMode = LoopMode.LOOP,
    val supportsMirroring: Boolean = false,
    val groundHeight: Float = 0f,
    val initialFacing: FacingDirection = FacingDirection.FRONT
)

// ----- ProjectedSkeleton.kt -----
class ProjectedPoint {
    var x: Float = 0f
    var y: Float = 0f
    var depth: Float = 0f
    var perspectiveScale: Float = 1f

    fun update(x: Float, y: Float, depth: Float, scale: Float) {
        this.x = x
        this.y = y
        this.depth = depth
        this.perspectiveScale = scale
    }

    fun copyFrom(other: ProjectedPoint) {
        this.x = other.x
        this.y = other.y
        this.depth = other.depth
        this.perspectiveScale = other.perspectiveScale
    }
}

class ProjectedJoint {
    var id: Joint = Joint.PELVIS
    val point = ProjectedPoint()
    var isIndicator: Boolean = false

    fun update(source: ProjectedPoint, jointId: Joint, indicator: Boolean = false) {
        id = jointId
        point.copyFrom(source)
        isIndicator = indicator
    }
}

class ProjectedBone {
    val p1 = ProjectedPoint()
    val p2 = ProjectedPoint()
    var thickness: Float = 0f
    var colorMultiplier: Float = 1.0f
    var avgDepth: Float = 0f
    var isForeground: Boolean = false

    fun update(src1: ProjectedPoint, src2: ProjectedPoint, thick: Float, colorM: Float) {
        p1.copyFrom(src1)
        p2.copyFrom(src2)
        thickness = thick
        colorMultiplier = colorM
        avgDepth = (src1.depth + src2.depth) / 2f
        isForeground = colorM >= 1.0f
    }
}

class ProjectedFace {
    val points = Array(4) { ProjectedPoint() }
    var avgDepth: Float = 0f

    fun update(p1: ProjectedPoint, p2: ProjectedPoint, p3: ProjectedPoint, p4: ProjectedPoint) {
        points[0].copyFrom(p1)
        points[1].copyFrom(p2)
        points[2].copyFrom(p3)
        points[3].copyFrom(p4)
        avgDepth = (p1.depth + p2.depth + p3.depth + p4.depth) / 4f
    }
}

class ProjectedGridLine {
    val p1 = ProjectedPoint()
    val p2 = ProjectedPoint()
}

/**
 * Encapsulates a skeleton after it has been projected into screen space.
 * Designed for buffer reuse to eliminate heap allocations in the hot path.
 */
class ProjectedSkeleton(
    val joints: Array<ProjectedPoint> = Array(Joint.entries.size) { ProjectedPoint() },
    val bones: Array<ProjectedBone> = Array(30) { ProjectedBone() },
    val faces: Array<ProjectedFace> = Array(6) { ProjectedFace() }
) {
    val indicators = Array(2) { ProjectedJoint() }
    val shadowPoints = Array(5) { ProjectedPoint() }
    val gridLines = Array(20) { ProjectedGridLine() }

    var boneCount: Int = 0
    var faceCount: Int = 0
    var gridLineCount: Int = 0
    var depthMin: Float = 0f
    var depthMax: Float = 0f
}

// ----- ScreenSpaceCompensation.kt -----
data class ScreenSpaceSettings(
    val thicknessStrength: Float = 0.15f,
    val radiusStrength: Float = 0.12f,
    val outlineStrength: Float = 0.05f,
    val shadowStrength: Float = 0.10f,
    val alphaStrength: Float = 0.0f
) {
    companion object {
        val DEFAULT = ScreenSpaceSettings()
    }
}

class ScreenSpaceScale {
    var radiusScale: Float = 1f
    var thicknessScale: Float = 1f
    var outlineScale: Float = 1f
    var shadowScale: Float = 1f
    var alphaScale: Float = 1f

    fun update(p: Float, zoom: Float, settings: ScreenSpaceSettings) {
        radiusScale = (1.0f + (p - 1.0f) * settings.radiusStrength) * zoom
        thicknessScale = (1.0f + (p - 1.0f) * settings.thicknessStrength) * zoom
        outlineScale = 1.0f + (p - 1.0f) * settings.outlineStrength
        shadowScale = (1.0f + (p - 1.0f) * settings.shadowStrength) * zoom
        alphaScale = 1.0f + (p - 1.0f) * settings.alphaStrength
    }

    // Legacy fallback for backward-compatibility with 1.0f default zoom
    fun update(p: Float, settings: ScreenSpaceSettings) {
        update(p, 1.0f, settings)
    }
}

/**
 * ScreenSpaceCompensation is a pure post-processing stage.
 * It is now the single source of truth for all camera-zoom and perspective-based visual scaling,
 * keeping Camera a pure mathematical coordinate projector.
 */
class ScreenSpaceCompensation(
    private val settings: ScreenSpaceSettings = ScreenSpaceSettings.DEFAULT
) {
    /**
     * Updates the provided ScreenSpaceScale buffer using the perspectiveScale and dynamic zoom.
     */
    fun computeScale(point: ProjectedPoint, zoom: Float, buffer: ScreenSpaceScale) {
        buffer.update(point.perspectiveScale, zoom, settings)
    }

    /**
     * Legacy overloaded signature for backward compatibility.
     */
    fun computeScale(point: ProjectedPoint, buffer: ScreenSpaceScale) {
        computeScale(point, 1.0f, buffer)
    }

    /**
     * Batch process joints directly on indexed arrays in-place with dynamic zoom.
     */
    fun computeScales(joints: Array<ProjectedPoint>, zoom: Float, scales: Array<ScreenSpaceScale>) {
        for (i in joints.indices) {
            scales[i].update(joints[i].perspectiveScale, zoom, settings)
        }
    }

    /**
     * Legacy overloaded signature for batch processing with default zoom.
     */
    fun computeScales(joints: Array<ProjectedPoint>, scales: Array<ScreenSpaceScale>) {
        computeScales(joints, 1.0f, scales)
    }
}

// ----- SkeletonDefinition.kt -----
/**
 * Generic interface for skeleton body proportions and anatomical metadata.
 */
interface SkeletonDefinition {
    val torsoLength: Float
    val neckLength: Float
    val thighLength: Float
    val shinLength: Float
    val footLength: Float
    val foot: FootDefinition
    val upperArmLength: Float
    val forearmLength: Float
    val hand: HandDefinition
    val shoulderWidth: Float
    val hipWidth: Float
    val defaultCamera: CameraDefinition

    // Biomechanical constraints
    val armIKConstraint: IKConstraint
    val legIKConstraint: IKConstraint

    companion object {
        val DEFAULT_ADULT: SkeletonDefinition = HumanSkeletonDefinition()
    }
}

/**
 * Default implementation for the Monk Fitness human model.
 */
data class HumanSkeletonDefinition(
    override val torsoLength: Float = 120f,
    override val neckLength: Float = 18f,
    override val thighLength: Float = 112f,
    override val shinLength: Float = 98f,
    override val footLength: Float = 35f,
    override val foot: FootDefinition = FootDefinition(footLength),
    override val upperArmLength: Float = 64f,
    override val forearmLength: Float = 82f,
    override val hand: HandDefinition = HandDefinition(),
    override val shoulderWidth: Float = 42f,
    override val hipWidth: Float = 22f,
    override val defaultCamera: CameraDefinition = CameraDefinition.DEFAULT,

    override val armIKConstraint: IKConstraint = IKConstraint.ArmConstraint,
    override val legIKConstraint: IKConstraint = IKConstraint.LegConstraint
) : SkeletonDefinition

// For backward compatibility during migration
typealias LegacySkeletonDefinition = HumanSkeletonDefinition

// ----- SkeletonEngine.kt -----
class SkeletonEngine(
    val definition: SkeletonDefinition,
    val style: SkeletonStyle
) {
    // Bone hierarchy for rendering
    val bones = listOf(
        // Background limbs (Right side)
        Bone(Joint.HIP_B, Joint.KNEE_B, style.thighThickness * 0.8f, 0.62f),
        Bone(Joint.KNEE_B, Joint.ANKLE_B, style.shinThickness * 0.8f, 0.62f),
        // Triangulated foot background
        Bone(Joint.ANKLE_B, Joint.HEEL_B, style.shinThickness * 0.7f, 0.62f), // Calcaneus
        Bone(Joint.ANKLE_B, Joint.TOE_B, style.shinThickness * 0.7f, 0.62f),  // Instep
        Bone(Joint.HEEL_B, Joint.TOE_B, 10f, 0.62f),                          // Sole

        Bone(Joint.SHOULDER_P, Joint.ELBOW_P, style.upperArmThickness * 0.85f, 0.8f),
        Bone(Joint.ELBOW_P, Joint.WRIST_P, style.forearmThickness * 0.85f, 0.8f),
        Bone(Joint.WRIST_P, Joint.PALM_P, style.handThickness * 0.85f, 0.8f),
        Bone(Joint.PALM_P, Joint.FINGERTIPS_P, style.handThickness * 0.8f * 0.85f, 0.8f),

        // Foreground limbs (Left side)
        Bone(Joint.HIP_F, Joint.KNEE_F, style.thighThickness, 1.0f),
        Bone(Joint.KNEE_F, Joint.ANKLE_F, style.shinThickness, 1.0f),
        // Triangulated foot foreground
        Bone(Joint.ANKLE_F, Joint.HEEL_F, style.shinThickness * 0.8f, 1.0f), // Calcaneus
        Bone(Joint.ANKLE_F, Joint.TOE_F, style.shinThickness * 0.8f, 1.0f),  // Instep
        Bone(Joint.HEEL_F, Joint.TOE_F, 12f, 1.0f),                          // Sole

        Bone(Joint.SHOULDER_A, Joint.ELBOW_A, style.upperArmThickness, 1.05f),
        Bone(Joint.ELBOW_A, Joint.WRIST_A, style.forearmThickness, 1.05f),
        Bone(Joint.WRIST_A, Joint.PALM_A, style.handThickness, 1.05f),
        Bone(Joint.PALM_A, Joint.FINGERTIPS_A, style.handThickness * 0.8f, 1.05f),

        // Spine/Neck
        Bone(Joint.PELVIS, Joint.CHEST, 17f, 0.95f),
        Bone(Joint.CHEST, Joint.NECK_END, style.neckThickness, 0.95f)
    )
}

// ----- SkeletonMath.kt -----
class Vector3(var x: Float = 0f, var y: Float = 0f, var z: Float = 0f) {
    fun set(x: Float, y: Float, z: Float): Vector3 {
        this.x = x
        this.y = y
        this.z = z
        return this
    }

    fun set(v: Vector3): Vector3 {
        this.x = v.x
        this.y = v.y
        this.z = v.z
        return this
    }

    operator fun plus(v: Vector3) = Vector3(x + v.x, y + v.y, z + v.z)
    operator fun minus(v: Vector3) = Vector3(x - v.x, y - v.y, z - v.z)
    operator fun times(s: Float) = Vector3(x * s, y * s, z * s)
    operator fun div(s: Float) = Vector3(x / s, y / s, z / s)

    fun add(v: Vector3): Vector3 {
        x += v.x
        y += v.y
        z += v.z
        return this
    }

    fun subtract(v: Vector3): Vector3 {
        x -= v.x
        y -= v.y
        z -= v.z
        return this
    }

    fun multiply(s: Float): Vector3 {
        x *= s
        y *= s
        z *= s
        return this
    }

    fun divide(s: Float): Vector3 {
        x /= s
        y /= s
        z /= s
        return this
    }

    fun mag() = sqrt(x * x + y * y + z * z)

    fun normalize(): Vector3 {
        val m = mag()
        return if (m > 1e-6) this.divide(m) else this.set(0f, 0f, 0f)
    }

    fun normalizedCopy(): Vector3 {
        val m = mag()
        return if (m > 1e-6) this / m else Vector3(0f, 0f, 0f)
    }

    fun dot(v: Vector3) = x * v.x + y * v.y + z * v.z

    fun cross(v: Vector3, result: Vector3): Vector3 {
        val rx = y * v.z - z * v.y
        val ry = z * v.x - x * v.z
        val rz = x * v.y - y * v.x
        return result.set(rx, ry, rz)
    }

    fun cross(v: Vector3) = Vector3(
        y * v.z - z * v.y,
        z * v.x - x * v.z,
        x * v.y - y * v.x
    )

    fun copy() = Vector3(x, y, z)

    override fun toString(): String = "Vector3(x=$x, y=$y, z=$z)"
}

data class IKConstraint(
    val minimumFlexionAngle: Float,
    val maximumExtensionRatio: Float
) {
    companion object {
        val ArmConstraint = IKConstraint(30f, 0.95f)
        val LegConstraint = IKConstraint(5f, 0.98f)
    }
}

object SkeletonMath {
    /**
     * Standard Ease-In-Out Quintic
     */
    fun easeIO(x: Float): Float {
        val cx = x.coerceIn(0f, 1f)
        return cx * cx * cx * (cx * (cx * 6 - 15) + 10)
    }

    /**
     * Reference Implementation Ease-In-Out (Quadric)
     */
    fun easeInOut(x: Float): Float {
        return if (x < 0.5f) {
            2f * x * x
        } else {
            1f - (-2f * x + 2f).pow(2) / 2f
        }
    }

    fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    fun lerp(a: Vector3, b: Vector3, t: Float, result: Vector3): Vector3 {
        result.set(
            a.x + (b.x - a.x) * t,
            a.y + (b.y - a.y) * t,
            a.z + (b.z - a.z) * t
        )
        return result
    }

    fun lerp(a: Vector3, b: Vector3, t: Float): Vector3 = lerp(a, b, t, Vector3())

    // Rodrigues rotation: rotate v around unit axis k by ang
    fun rotAround(v: Vector3, axis: Vector3, ang: Float, result: Vector3): Vector3 {
        val kx: Float; val ky: Float; val kz: Float
        val m = sqrt(axis.x * axis.x + axis.y * axis.y + axis.z * axis.z)
        if (m > 1e-6f) {
            kx = axis.x / m; ky = axis.y / m; kz = axis.z / m
        } else {
            kx = 0f; ky = 0f; kz = 1f
        }

        val c = cos(ang)
        val s = sin(ang)
        val dot = v.x * kx + v.y * ky + v.z * kz

        val cx = ky * v.z - kz * v.y
        val cy = kz * v.x - kx * v.z
        val cz = kx * v.y - ky * v.x

        val omc = 1f - c
        result.set(
            v.x * c + cx * s + kx * dot * omc,
            v.y * c + cy * s + ky * dot * omc,
            v.z * c + cz * s + kz * dot * omc
        )
        return result
    }

    fun rotAround(v: Vector3, axis: Vector3, ang: Float): Vector3 {
        return rotAround(v, axis, ang, Vector3())
    }

    // Hard-locked 2-bone IK solver
    class IKResult(val joint: Vector3 = Vector3(), val end: Vector3 = Vector3())

    /**
     * Analytical IK with strict Biological Clamps
     */
    fun solveIK(
        root: Vector3,
        target: Vector3,
        L1: Float,
        L2: Float,
        pole: Vector3,
        constraint: IKConstraint,
        result: IKResult = IKResult()
    ): IKResult {
        val dx = target.x - root.x
        val dy = target.y - root.y
        val dz = target.z - root.z
        val dMag = sqrt(dx * dx + dy * dy + dz * dz)

        val maxDist = (L1 + L2) * constraint.maximumExtensionRatio

        val minCos = cos(constraint.minimumFlexionAngle * PI.toFloat() / 180f)
        val minDist = sqrt(L1 * L1 + L2 * L2 - 2f * L1 * L2 * minCos)

        val dist = dMag.coerceIn(minDist, maxDist)

        val dirX: Float; val dirY: Float; val dirZ: Float
        if (dMag > 1e-6f) {
            dirX = dx / dMag; dirY = dy / dMag; dirZ = dz / dMag
        } else {
            dirX = 1f; dirY = 0f; dirZ = 0f
        }

        result.end.set(root.x + dirX * dist, root.y + dirY * dist, root.z + dirZ * dist)

        val a = (dist * dist + L1 * L1 - L2 * L2) / (2 * dist)
        val h = sqrt(max(L1 * L1 - a * a, 0f))

        val pDotDir = pole.x * dirX + pole.y * dirY + pole.z * dirZ
        var px = pole.x - dirX * pDotDir
        var py = pole.y - dirY * pDotDir
        var pz = pole.z - dirZ * pDotDir

        val pMag = sqrt(px * px + py * py + pz * pz)
        if (pMag < 1e-4f) {
            val wdDotDir = dirY
            px = 0f - dirX * wdDotDir
            py = 1f - dirY * wdDotDir
            pz = 0f - dirZ * wdDotDir
            val pMag2 = sqrt(px * px + py * py + pz * pz)
            if (pMag2 > 1e-6f) {
                px /= pMag2; py /= pMag2; pz /= pMag2
            } else {
                px = 0f; py = 0f; pz = 1f
            }
        } else {
            px /= pMag; py /= pMag; pz /= pMag
        }

        result.joint.set(
            root.x + dirX * a + px * h,
            root.y + dirY * a + py * h,
            root.z + dirZ * a + pz * h
        )

        return result
    }

    // High-fidelity 3D Rotation Matrix utilities for zero-allocation FK propagation

    fun rotationToMatrix(rot: JointRotation, colX: Vector3, colY: Vector3, colZ: Vector3) {
        val c = cos(rot.angle)
        val s = sin(rot.angle)
        val omc = 1f - c
        val x = rot.axis.x
        val y = rot.axis.y
        val z = rot.axis.z

        colX.set(
            c + x * x * omc,
            y * x * omc + z * s,
            z * x * omc - y * s
        )
        colY.set(
            x * y * omc - z * s,
            c + y * y * omc,
            z * y * omc + x * s
        )
        colZ.set(
            x * z * omc + y * s,
            y * z * omc - x * s,
            c + z * z * omc
        )
    }

    fun getRotationFromMatrix(colX: Vector3, colY: Vector3, colZ: Vector3, result: JointRotation) {
        val tr = colX.x + colY.y + colZ.z
        val angle = acos(((tr - 1f) / 2f).coerceIn(-1f, 1f))
        if (angle < 1e-4f) {
            result.set(0f, 0f, 1f, 0f)
            return
        }
        val x = colY.z - colZ.y
        val y = colZ.x - colX.z
        val z = colX.y - colY.x
        val mag = sqrt(x * x + y * y + z * z)
        if (mag < 1e-4f) {
            result.set(0f, 0f, 1f, angle)
        } else {
            result.set(x / mag, y / mag, z / mag, angle)
        }
    }

    fun matrixMultiplyVector(pX: Vector3, pY: Vector3, pZ: Vector3, v: Vector3, result: Vector3) {
        val rx = pX.x * v.x + pY.x * v.y + pZ.x * v.z
        val ry = pX.y * v.x + pY.y * v.y + pZ.y * v.z
        val rz = pX.z * v.x + pY.z * v.y + pZ.z * v.z
        result.set(rx, ry, rz)
    }

    fun multiplyMatrices(
        pX: Vector3, pY: Vector3, pZ: Vector3,
        lX: Vector3, lY: Vector3, lZ: Vector3,
        rX: Vector3, rY: Vector3, rZ: Vector3
    ) {
        matrixMultiplyVector(pX, pY, pZ, lX, rX)
        matrixMultiplyVector(pX, pY, pZ, lY, rY)
        matrixMultiplyVector(pX, pY, pZ, lZ, rZ)
    }

    fun transposeMultiply(
        pX: Vector3, pY: Vector3, pZ: Vector3,
        wX: Vector3, wY: Vector3, wZ: Vector3,
        rX: Vector3, rY: Vector3, rZ: Vector3
    ) {
        val rxX = pX.dot(wX)
        val rxY = pY.dot(wX)
        val rxZ = pZ.dot(wX)

        val ryX = pX.dot(wY)
        val ryY = pY.dot(wY)
        val ryZ = pZ.dot(wY)

        val rzX = pX.dot(wZ)
        val rzY = pY.dot(wZ)
        val rzZ = pZ.dot(wZ)

        rX.set(rxX, rxY, rxZ)
        rY.set(ryX, ryY, ryZ)
        rZ.set(rzX, rzY, rzZ)
    }

    fun getRotationToAlign(from: Vector3, to: Vector3, tempScratch: Vector3, result: JointRotation) {
        val fromMag = from.mag()
        val toMag = to.mag()
        if (fromMag < 1e-4f || toMag < 1e-4f) {
            result.set(0f, 0f, 1f, 0f)
            return
        }
        val dot = (from.dot(to) / (fromMag * toMag)).coerceIn(-1f, 1f)
        val angle = acos(dot)
        if (angle < 1e-4f) {
            result.set(0f, 0f, 1f, 0f)
            return
        }
        if (angle > PI.toFloat() - 1e-4f) {
            val perp = if (abs(from.x) < 0.9f) Vector3(1f, 0f, 0f) else Vector3(0f, 1f, 0f)
            val axis = from.cross(perp, tempScratch).normalize()
            result.set(axis, angle)
            return
        }
        val axis = from.cross(to, tempScratch).normalize()
        result.set(axis, angle)
    }

    fun getRotationToAlign(to: Vector3, result: JointRotation) {
        val toMag = to.mag()
        if (toMag < 1e-4f) {
            result.set(0f, 0f, 1f, 0f)
            return
        }
        val dx = to.x / toMag
        val dy = to.y / toMag
        val dz = to.z / toMag

        val angle = acos(dx.coerceIn(-1f, 1f))
        if (angle < 1e-4f) {
            result.set(0f, 0f, 1f, 0f)
            return
        }
        val ax = 0f
        val ay = -dz
        val az = dy
        val amag = sqrt(ay * ay + az * az)
        if (amag < 1e-4f) {
            result.set(0f, 0f, 1f, angle)
        } else {
            result.set(ax, ay / amag, az / amag, angle)
        }
    }
}

// ----- SkeletonNode.kt -----
/**
 * Mutable axis-angle rotation to avoid allocations.
 */
class JointRotation(
    val axis: Vector3 = Vector3(0f, 0f, 1f),
    var angle: Float = 0f // radians
) {
    fun set(axis: Vector3, angle: Float) {
        this.axis.set(axis)
        this.angle = angle
    }

    fun set(x: Float, y: Float, z: Float, angle: Float) {
        this.axis.set(x, y, z)
        this.angle = angle
    }

    fun copyFrom(other: JointRotation) {
        this.axis.set(other.axis)
        this.angle = other.angle
    }
}

/**
 * Lightweight node for hierarchical transforms (Forward Kinematics).
 * Optimized for zero-allocation per frame updates.
 */
class SkeletonNode(
    val joint: Joint,
    var parent: SkeletonNode? = null,
    var localPosition: Vector3 = Vector3(0f, 0f, 0f),
    val localRotation: JointRotation = JointRotation(),
    val children: MutableList<SkeletonNode> = mutableListOf()
) {
    val worldPosition: Vector3 = Vector3(0f, 0f, 0f)

    val worldRotation: JointRotation = JointRotation()

    // Persistent scratch buffers to completely avoid allocations during FK traversal
    private val pX = Vector3()
    private val pY = Vector3()
    private val pZ = Vector3()
    private val lX = Vector3()
    private val lY = Vector3()
    private val lZ = Vector3()
    private val wX = Vector3()
    private val wY = Vector3()
    private val wZ = Vector3()

    fun addChild(node: SkeletonNode): SkeletonNode {
        node.parent = this
        children.add(node)
        return node
    }

    /**
     * Compute world transform inheriting from parent via full 3D matrix concatenation.
     */
    fun updateWorldTransforms(parentWorldPos: Vector3, parentWorldRotation: JointRotation) {
        // Position propagation: Rotate local offset by parent's world rotation
        if (parentWorldRotation.angle != 0f) {
            SkeletonMath.rotAround(localPosition, parentWorldRotation.axis, parentWorldRotation.angle, worldPosition)
        } else {
            worldPosition.set(localPosition)
        }
        worldPosition.add(parentWorldPos)

        // Rotation propagation: Concatenate parent's world rotation with local rotation
        SkeletonMath.rotationToMatrix(parentWorldRotation, pX, pY, pZ)
        SkeletonMath.rotationToMatrix(localRotation, lX, lY, lZ)
        SkeletonMath.multiplyMatrices(pX, pY, pZ, lX, lY, lZ, wX, wY, wZ)
        SkeletonMath.getRotationFromMatrix(wX, wY, wZ, worldRotation)

        for (child in children) {
            child.updateWorldTransforms(worldPosition, worldRotation)
        }
    }

    /**
     * Flatten the hierarchy into the target joint map.
     */
    fun flatten(target: SkeletonPose) {
        target.setJoint(joint, worldPosition)
        target.setJointRotation(joint, worldRotation)
        for (child in children) {
            child.flatten(target)
        }
    }
}

// ----- SkeletonPoseFinalizer.kt -----
/**
 * SkeletonPoseFinalizer is responsible for completing the 3D pose before it is projected to screen space.
 * It adds biomechanical details like Heel/Toe and Hand segments that are not part of the core PoseBuilder logic.
 * This stage ensures the 3D skeleton is anatomically complete and that all world positions and rotations are derived by FK traversal.
 *
 * Under the progressive rotation-driven migration, SkeletonPoseFinalizer serves as a compatibility layer:
 * - If a custom hierarchy (pose.roots) is supplied, it bypasses legacy position-to-rotation reconstruction.
 * - If pose.roots is empty, it runs the legacy reconstruction bridge to derive 3D transforms with zero regressions.
 */
class SkeletonPoseFinalizer(
    private val definition: SkeletonDefinition
) {
    private val outputPose = SkeletonPose()
    private val tempDir = Vector3()
    private val tempForwardHint = Vector3()
    private val tempFootDir = Vector3()
    private val tempHorizontalDir = Vector3()
    private val handJointsBuffer = HandJoints()
    private val tempV1 = Vector3()

    // Pre-allocated standard SkeletonNode hierarchy (for legacy compat path)
    private var roots: List<SkeletonNode>? = null
    private val nodesMap = Array<SkeletonNode?>(Joint.entries.size) { null }

    private val IDENTITY_ROTATION = JointRotation()
    private val ZERO_VECTOR = Vector3(0f, 0f, 0f)

    // Scratch buffers for 3D rotation math to achieve zero allocations in the hot path
    private val tempColX = Vector3()
    private val tempColY = Vector3()
    private val tempColZ = Vector3()
    private val tempBoneVec = Vector3()
    private val parentMatX = Vector3()
    private val parentMatY = Vector3()
    private val parentMatZ = Vector3()
    private val worldMatX = Vector3()
    private val worldMatY = Vector3()
    private val worldMatZ = Vector3()
    private val localMatX = Vector3()
    private val localMatY = Vector3()
    private val localMatZ = Vector3()

    private fun ensureHierarchy() {
        if (roots != null) return

        // Create all nodes
        for (joint in Joint.entries) {
            nodesMap[joint.index] = SkeletonNode(joint)
        }

        fun getNode(joint: Joint): SkeletonNode = nodesMap[joint.index]!!

        // Parent-child connections
        // Spine
        getNode(Joint.PELVIS).addChild(getNode(Joint.CHEST))
        getNode(Joint.CHEST).addChild(getNode(Joint.NECK_END))
        getNode(Joint.NECK_END).addChild(getNode(Joint.HEAD_POS))

        // Left Arm (Active)
        getNode(Joint.CHEST).addChild(getNode(Joint.SHOULDER_A))
        getNode(Joint.SHOULDER_A).addChild(getNode(Joint.ELBOW_A))
        getNode(Joint.ELBOW_A).addChild(getNode(Joint.HAND_A))
        getNode(Joint.HAND_A).addChild(getNode(Joint.WRIST_A))
        getNode(Joint.WRIST_A).addChild(getNode(Joint.PALM_A))
        getNode(Joint.PALM_A).addChild(getNode(Joint.KNUCKLES_A))
        getNode(Joint.KNUCKLES_A).addChild(getNode(Joint.FINGERTIPS_A))

        // Right Arm (Passive)
        getNode(Joint.CHEST).addChild(getNode(Joint.SHOULDER_P))
        getNode(Joint.SHOULDER_P).addChild(getNode(Joint.ELBOW_P))
        getNode(Joint.ELBOW_P).addChild(getNode(Joint.HAND_P))
        getNode(Joint.HAND_P).addChild(getNode(Joint.WRIST_P))
        getNode(Joint.WRIST_P).addChild(getNode(Joint.PALM_P))
        getNode(Joint.PALM_P).addChild(getNode(Joint.KNUCKLES_P))
        getNode(Joint.KNUCKLES_P).addChild(getNode(Joint.FINGERTIPS_P))

        // Left Leg (Foreground)
        getNode(Joint.PELVIS).addChild(getNode(Joint.HIP_F))
        getNode(Joint.HIP_F).addChild(getNode(Joint.KNEE_F))
        getNode(Joint.KNEE_F).addChild(getNode(Joint.ANKLE_F))
        getNode(Joint.ANKLE_F).addChild(getNode(Joint.HEEL_F))
        getNode(Joint.ANKLE_F).addChild(getNode(Joint.TOE_F))

        // Right Leg (Background)
        getNode(Joint.PELVIS).addChild(getNode(Joint.HIP_B))
        getNode(Joint.HIP_B).addChild(getNode(Joint.KNEE_B))
        getNode(Joint.KNEE_B).addChild(getNode(Joint.ANKLE_B))
        getNode(Joint.ANKLE_B).addChild(getNode(Joint.HEEL_B))
        getNode(Joint.ANKLE_B).addChild(getNode(Joint.TOE_B))

        roots = listOf(getNode(Joint.PELVIS))
    }

    private fun setupTransforms(node: SkeletonNode, parentWorldRot: JointRotation, pose: SkeletonPose) {
        val parentNode = node.parent
        if (parentNode == null) {
            // Root node (PELVIS)
            node.localPosition.set(pose.getJoint(node.joint))

            // Set pelvis rotation based on Chest-Pelvis direction (spine)
            val chestPos = pose.getJoint(Joint.CHEST)
            val pelvisPos = pose.getJoint(Joint.PELVIS)
            tempBoneVec.set(chestPos).subtract(pelvisPos)
            SkeletonMath.getRotationToAlign(Vector3(0f, 1f, 0f), tempBoneVec, tempV1, node.localRotation)

            node.worldPosition.set(node.localPosition)
            node.worldRotation.copyFrom(node.localRotation)
        } else {
            // Child node
            val parentPos = parentNode.worldPosition
            val childPos = pose.getJoint(node.joint)

            // Apply anatomical offsets in local space as defined by the parent's segment
            when (node.joint) {
                Joint.HIP_F -> {
                    node.localPosition.set(0f, 0f, -definition.hipWidth)
                }
                Joint.HIP_B -> {
                    node.localPosition.set(0f, 0f, definition.hipWidth)
                }
                Joint.SHOULDER_A -> {
                    node.localPosition.set(0f, 0f, -definition.shoulderWidth)
                }
                Joint.SHOULDER_P -> {
                    node.localPosition.set(0f, 0f, definition.shoulderWidth)
                }
                Joint.NECK_END -> {
                    node.localPosition.set(0f, definition.neckLength, 0f)
                }
                Joint.HEAD_POS -> {
                    node.localPosition.set(0f, 18f, 0f)
                }
                else -> {
                    // Standard joint: calculate local position rotated backward by parent's world rotation
                    tempBoneVec.set(childPos).subtract(parentPos)
                    SkeletonMath.rotAround(tempBoneVec, parentWorldRot.axis, -parentWorldRot.angle, node.localPosition)
                }
            }

            // Compute the world rotation of this joint
            if (node.joint == Joint.CHEST) {
                // Chest is the torso, compute its 3D rotation matrix to avoid position subtractions in projector/renderer
                val chestPos = pose.getJoint(Joint.CHEST)
                val pelvisPos = pose.getJoint(Joint.PELVIS)
                val shoulderA = pose.getJoint(Joint.SHOULDER_A)
                val shoulderP = pose.getJoint(Joint.SHOULDER_P)

                val lean = tempColY.set(chestPos).subtract(pelvisPos).normalize()
                val shVec = tempColZ.set(shoulderA).subtract(shoulderP).normalize()
                val chestNorm = lean.cross(shVec, tempColX).normalize()

                // colZ should be -shVec
                tempColZ.multiply(-1f)

                SkeletonMath.getRotationFromMatrix(tempColX, tempColY, tempColZ, node.worldRotation)
            } else {
                // Shortest arc rotation aligning Vector3(1f, 0f, 0f) with bone direction
                tempBoneVec.set(childPos).subtract(parentPos)
                SkeletonMath.getRotationToAlign(tempBoneVec, node.worldRotation)
            }

            // localRotation is the relative rotation: R_local = R_parent.inverse * R_world
            // Compute relative rotation via matrix transpose multiplication:
            SkeletonMath.rotationToMatrix(parentWorldRot, parentMatX, parentMatY, parentMatZ)
            SkeletonMath.rotationToMatrix(node.worldRotation, worldMatX, worldMatY, worldMatZ)
            SkeletonMath.transposeMultiply(parentMatX, parentMatY, parentMatZ, worldMatX, worldMatY, worldMatZ, localMatX, localMatY, localMatZ)
            SkeletonMath.getRotationFromMatrix(localMatX, localMatY, localMatZ, node.localRotation)

            // Set temporary world transforms during setup pass
            node.worldPosition.set(childPos)
        }

        // Setup children
        for (child in node.children) {
            setupTransforms(child, node.worldRotation, pose)
        }
    }

    /**
     * Finalizes the 3D pose. Supports both modern rotation-driven custom hierarchies and legacy position-driven poses.
     */
    fun finalize(pose: SkeletonPose): SkeletonPose {
        outputPose.copyFrom(pose)

        if (pose.roots.isNotEmpty()) {
            // Modern rotation-driven path: Execute Forward Kinematics traversal directly using direct local joint rotations/offsets
            pose.roots.forEach { it.updateWorldTransforms(ZERO_VECTOR, IDENTITY_ROTATION) }
            pose.roots.forEach { it.flatten(outputPose) }
            outputPose.roots = pose.roots

            // Complete missing elements from the hierarchy if they are absent
            if (!containsJoint(pose.roots, Joint.HEEL_F) || !containsJoint(pose.roots, Joint.TOE_F)) {
                adjustFootOrientation(outputPose, Joint.KNEE_F, Joint.ANKLE_F, Joint.HEEL_F, Joint.TOE_F)
            }
            if (!containsJoint(pose.roots, Joint.HEEL_B) || !containsJoint(pose.roots, Joint.TOE_B)) {
                adjustFootOrientation(outputPose, Joint.KNEE_B, Joint.ANKLE_B, Joint.HEEL_B, Joint.TOE_B)
            }
            if (!containsJoint(pose.roots, Joint.PALM_A) || !containsJoint(pose.roots, Joint.FINGERTIPS_A)) {
                adjustHandOrientation(outputPose, Joint.ELBOW_A, Joint.HAND_A, Joint.WRIST_A, Joint.PALM_A, Joint.KNUCKLES_A, Joint.FINGERTIPS_A)
            }
            if (!containsJoint(pose.roots, Joint.PALM_P) || !containsJoint(pose.roots, Joint.FINGERTIPS_P)) {
                adjustHandOrientation(outputPose, Joint.ELBOW_P, Joint.HAND_P, Joint.WRIST_P, Joint.PALM_P, Joint.KNUCKLES_P, Joint.FINGERTIPS_P)
            }
        } else {
            // Legacy position-driven compatibility bridge: Compute anatomical foot & hand extensions procedurally
            adjustFootOrientation(outputPose, Joint.KNEE_F, Joint.ANKLE_F, Joint.HEEL_F, Joint.TOE_F)
            adjustFootOrientation(outputPose, Joint.KNEE_B, Joint.ANKLE_B, Joint.HEEL_B, Joint.TOE_B)

            adjustHandOrientation(outputPose, Joint.ELBOW_A, Joint.HAND_A, Joint.WRIST_A, Joint.PALM_A, Joint.KNUCKLES_A, Joint.FINGERTIPS_A)
            adjustHandOrientation(outputPose, Joint.ELBOW_P, Joint.HAND_P, Joint.WRIST_P, Joint.PALM_P, Joint.KNUCKLES_P, Joint.FINGERTIPS_P)

            // Ensure standard compatibility hierarchy is created
            ensureHierarchy()

            // Run setupTransforms to reconstruct orientation parameters and set proper local anatomical offsets
            setupTransforms(nodesMap[Joint.PELVIS.index]!!, IDENTITY_ROTATION, outputPose)

            // Propagate world positions and world rotations via Forward Kinematics traversal
            roots!!.forEach { it.updateWorldTransforms(ZERO_VECTOR, IDENTITY_ROTATION) }

            // Flatten standard compatibility hierarchy back into outputPose
            roots!!.forEach { it.flatten(outputPose) }

            outputPose.roots = roots!!
        }

        return outputPose
    }

    private fun containsJoint(roots: List<SkeletonNode>, joint: Joint): Boolean {
        for (root in roots) {
            if (containsJointNode(root, joint)) return true
        }
        return false
    }

    private fun containsJointNode(node: SkeletonNode, joint: Joint): Boolean {
        if (node.joint == joint) return true
        for (child in node.children) {
            if (containsJointNode(child, joint)) return true
        }
        return false
    }

    private fun adjustHandOrientation(
        pose: SkeletonPose,
        elbowId: Joint,
        handId: Joint,
        wristId: Joint,
        palmId: Joint,
        knucklesId: Joint,
        fingertipsId: Joint
    ) {
        val elbow = pose.getJoint(elbowId)
        val hand = pose.getJoint(handId)

        val wrist = pose.getJoint(wristId)
        wrist.set(hand)

        tempDir.set(wrist).subtract(elbow).normalize()

        val handDef = definition.hand
        handDef.computeHandJoints(wrist, tempDir, handJointsBuffer)

        pose.getJoint(palmId).set(handJointsBuffer.palm)
        pose.getJoint(knucklesId).set(handJointsBuffer.knuckles)
        pose.getJoint(fingertipsId).set(handJointsBuffer.fingertips)
    }

    private fun adjustFootOrientation(
        pose: SkeletonPose,
        kneeId: Joint,
        ankleId: Joint,
        heelId: Joint,
        toeId: Joint
    ) {
        val knee = pose.getJoint(kneeId)
        val ankle = pose.getJoint(ankleId)
        val providedToe = pose.getJoint(toeId)

        val shank = (ankle - knee).normalize()

        if ((providedToe - ankle).mag() > 1e-3) {
            tempForwardHint.set(providedToe).subtract(ankle).normalize()
        } else {
            tempForwardHint.set(1f, 0f, 0f)
        }

        tempFootDir.set(shank).multiply(tempForwardHint.dot(shank))
        tempFootDir.set(tempForwardHint.x - tempFootDir.x, tempForwardHint.y - tempFootDir.y, tempForwardHint.z - tempFootDir.z)

        if (tempFootDir.mag() < 1e-3) {
            val worldDown = Vector3(0f, -1f, 0f)
            tempFootDir.set(shank).multiply(worldDown.dot(shank))
            tempFootDir.set(worldDown.x - tempFootDir.x, worldDown.y - tempFootDir.y, worldDown.z - tempFootDir.z)
        }
        tempFootDir.normalize()

        val pitch = atan2(tempFootDir.y, sqrt(tempFootDir.x * tempFootDir.x + tempFootDir.z * tempFootDir.z))
        val clampedPitch = pitch.coerceIn(definition.foot.minPitch, definition.foot.maxPitch)

        if (abs(pitch - clampedPitch) > 1e-3) {
            tempHorizontalDir.set(tempFootDir.x, 0f, tempFootDir.z).normalize()
            tempFootDir.set(
                tempHorizontalDir.x * cos(clampedPitch),
                sin(clampedPitch),
                tempHorizontalDir.z * cos(clampedPitch)
            )
        }

        val foot = definition.foot
        foot.computeHeelToe(ankle, tempFootDir, pose.getJoint(heelId), pose.getJoint(toeId))
    }
}

// ----- SkeletonProjector.kt -----
/**
 * SkeletonProjector is responsible for transforming a 3D SkeletonPose into a 2D ProjectedSkeleton.
 * Reuses internal buffers to eliminate per-frame allocations.
 */
class SkeletonProjector {
    private val torsoPoints = Array(8) { ProjectedPoint() }
    private val tempV = Vector3(0f, 0f, 0f)
    private val tempV2 = Vector3(0f, 0f, 0f)
    private val tempV3 = Vector3(0f, 0f, 0f)

    // Preallocated default unit vectors to completely prevent GC pressure/allocations in the hot path
    private val defaultSpineDir = Vector3(0f, 1f, 0f)
    private val defaultShoulderDir = Vector3(0f, 0f, -1f)

    private val shadowJoints = arrayOf(Joint.TOE_F, Joint.TOE_B, Joint.HEEL_F, Joint.HEEL_B, Joint.HAND_P)

    fun project(
        pose: SkeletonPose,
        camera: Camera,
        engine: SkeletonEngine,
        width: Float,
        height: Float,
        buffer: ProjectedSkeleton
    ) {
        val style = engine.style

        // 1. Project Joints
        val jointEntries = Joint.entries
        for (i in 0 until jointEntries.size) {
            val joint = jointEntries[i]
            camera.project(pose.getJoint(joint), width, height, buffer.joints[joint.index])
        }

        // 2. Indicators
        buffer.indicators[0].update(buffer.joints[Joint.HEAD_POS.index], Joint.HEAD_POS)
        buffer.indicators[1].update(buffer.joints[Joint.WRIST_A.index], Joint.WRIST_A, indicator = true)

        // 3. Bones
        val engineBones = engine.bones
        buffer.boneCount = engineBones.size
        for (i in 0 until engineBones.size) {
            val bone = engineBones[i]
            if (i < buffer.bones.size) {
                buffer.bones[i].update(
                    buffer.joints[bone.parentJoint.index],
                    buffer.joints[bone.childJoint.index],
                    bone.thickness,
                    bone.colorMultiplier
                )
            }
        }

        // 4. Torso faces
        updateTorsoFaces(pose, camera, style, width, height, buffer)

        // 5. Ground
        updateGround(pose, camera, width, height, buffer)

        // 6. Depth Range
        var dMin = Float.MAX_VALUE
        var dMax = Float.MIN_VALUE
        for (i in buffer.joints.indices) {
            val it = buffer.joints[i]
            dMin = min(dMin, it.depth)
            dMax = max(dMax, it.depth)
        }
        buffer.depthMin = dMin
        buffer.depthMax = dMax
    }

    private fun updateTorsoFaces(
        pose: SkeletonPose,
        camera: Camera,
        style: SkeletonStyle,
        width: Float,
        height: Float,
        buffer: ProjectedSkeleton
    ) {
        val hipF = pose.getJoint(Joint.HIP_F); val hipB = pose.getJoint(Joint.HIP_B)
        val shoulderA = pose.getJoint(Joint.SHOULDER_A); val shoulderP = pose.getJoint(Joint.SHOULDER_P)

        // Retrieve torso rotation from the CHEST joint world rotation (first-class source of truth)
        val chestRot = pose.getJointRotation(Joint.CHEST)

        // Derive lean (spine direction) and shVec (shoulder direction) from rotation instead of positions
        val lean = SkeletonMath.rotAround(defaultSpineDir, chestRot.axis, chestRot.angle, tempV).normalize()
        val shVec = SkeletonMath.rotAround(defaultShoulderDir, chestRot.axis, chestRot.angle, tempV2).normalize()
        val chestNorm = lean.cross(shVec, tempV3).normalize()

        val offC = tempV.set(chestNorm).multiply(style.torsoChestDepth)
        val offH = tempV2.set(chestNorm).multiply(style.torsoHipDepth)

        camera.project(tempV3.set(hipF).add(offH), width, height, torsoPoints[0])
        camera.project(tempV3.set(hipF).subtract(offH), width, height, torsoPoints[1])
        camera.project(tempV3.set(hipB).add(offH), width, height, torsoPoints[2])
        camera.project(tempV3.set(hipB).subtract(offH), width, height, torsoPoints[3])
        camera.project(tempV3.set(shoulderA).add(offC), width, height, torsoPoints[4])
        camera.project(tempV3.set(shoulderA).subtract(offC), width, height, torsoPoints[5])
        camera.project(tempV3.set(shoulderP).add(offC), width, height, torsoPoints[6])
        camera.project(tempV3.set(shoulderP).subtract(offC), width, height, torsoPoints[7])

        buffer.faceCount = 6
        buffer.faces[0].update(torsoPoints[4], torsoPoints[6], torsoPoints[2], torsoPoints[0]) // front
        buffer.faces[1].update(torsoPoints[7], torsoPoints[5], torsoPoints[1], torsoPoints[3]) // back
        buffer.faces[2].update(torsoPoints[5], torsoPoints[4], torsoPoints[0], torsoPoints[1]) // left
        buffer.faces[3].update(torsoPoints[6], torsoPoints[7], torsoPoints[3], torsoPoints[2]) // right
        buffer.faces[4].update(torsoPoints[5], torsoPoints[7], torsoPoints[6], torsoPoints[4]) // top
        buffer.faces[5].update(torsoPoints[0], torsoPoints[2], torsoPoints[3], torsoPoints[1]) // bottom
    }

    private fun updateGround(pose: SkeletonPose, camera: Camera, width: Float, height: Float, buffer: ProjectedSkeleton) {
        var lineIdx = 0
        for (x in -260..260 step 65) {
            if (lineIdx >= buffer.gridLines.size) break
            camera.project(tempV.set(x.toFloat(), 0f, -170f), width, height, buffer.gridLines[lineIdx].p1)
            camera.project(tempV.set(x.toFloat(), 0f, 170f), width, height, buffer.gridLines[lineIdx].p2)
            lineIdx++
        }
        for (z in -170..170 step 65) {
            if (lineIdx >= buffer.gridLines.size) break
            camera.project(tempV.set(-260f, 0f, z.toFloat()), width, height, buffer.gridLines[lineIdx].p1)
            camera.project(tempV.set(260f, 0f, z.toFloat()), width, height, buffer.gridLines[lineIdx].p2)
            lineIdx++
        }
        buffer.gridLineCount = lineIdx

        for (i in 0 until shadowJoints.size) {
            val id = shadowJoints[i]
            val pt = pose.getJoint(id)
            camera.project(tempV.set(pt.x, 0f, pt.z), width, height, buffer.shadowPoints[i])
        }
    }
}

// ----- SkeletonRenderer.kt -----
/**
 * SkeletonRenderer is a passive rendering component in the animation engine v1.0 pipeline.
 * It applies pre-computed ScreenSpaceScale values (computed by ScreenSpaceCompensation)
 * to visual parameters without performing additional perspective math or geometry modification.
 *
 * Consistent with first-class joint rotations, SkeletonRenderer never infers rotations or orientations
 * from joint positions; all transformations are derived strictly via Forward Kinematics traversal.
 * ScreenSpaceCompensation is now the single source of truth for perspective zoom scaling.
 */
@Composable
fun SkeletonRenderer(
    pose: SkeletonPose,
    camera: Camera,
    engine: SkeletonEngine,
    modifier: Modifier = Modifier,
    showGround: Boolean = true,
    highlightedJoint: Joint? = null,
    screenSpaceSettings: ScreenSpaceSettings = ScreenSpaceSettings.DEFAULT
) {
    val style = engine.style
    val finalizer = remember(engine.definition) { SkeletonPoseFinalizer(engine.definition) }
    val projector = remember { SkeletonProjector() }
    val compensator = remember(screenSpaceSettings) { ScreenSpaceCompensation(screenSpaceSettings) }

    val skeletonBuffer = remember { ProjectedSkeleton() }
    val renderItems = remember { Array(100) { RenderItem() } }
    val indices = remember { IntArray(100) }
    val scaleBuffer = remember { ScreenSpaceScale() }
    val path = remember { Path() }

    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height

        val finalizedPose = finalizer.finalize(pose)
        projector.project(finalizedPose, camera, engine, width, height, skeletonBuffer)

        if (showGround) {
            drawGroundPassive(skeletonBuffer, style, compensator, camera.zoom, scaleBuffer)
        }

        var itemCount = 0

        for (i in 0 until skeletonBuffer.boneCount) {
            val b = skeletonBuffer.bones[i]
            compensator.computeScale(b.p1, camera.zoom, scaleBuffer)
            val thickness = b.thickness * scaleBuffer.thicknessScale
            renderItems[itemCount++].populateBone(b, thickness, scaleBuffer.outlineScale)
        }

        for (j in skeletonBuffer.indicators) {
            compensator.computeScale(j.point, camera.zoom, scaleBuffer)
            val radius = (if (j.id == Joint.HEAD_POS) style.headRadius else style.jointRadius) *
                        scaleBuffer.radiusScale
            renderItems[itemCount++].populateJoint(j, radius, scaleBuffer.outlineScale)
        }

        for (i in 0 until skeletonBuffer.faceCount) {
            renderItems[itemCount++].populateFace(skeletonBuffer.faces[i])
        }

        // Initialize indices
        for (i in 0 until itemCount) indices[i] = i

        // In-place Insertion Sort of indices based on renderItems[idx].depth
        for (i in 1 until itemCount) {
            val idx = indices[i]
            val depth = renderItems[idx].depth
            var j = i - 1
            while (j >= 0 && renderItems[indices[j]].depth < depth) {
                indices[j + 1] = indices[j]
                j--
            }
            indices[j + 1] = idx
        }

        if (highlightedJoint != null) {
            val p = skeletonBuffer.joints[highlightedJoint.index]
            compensator.computeScale(p, camera.zoom, scaleBuffer)
            drawCircle(Color.White.copy(alpha = 0.5f), 12f * scaleBuffer.radiusScale, Offset(p.x, p.y))
        }

        for (i in 0 until itemCount) {
            val item = renderItems[indices[i]]
            when (item.type) {
                RenderItem.Type.BONE -> {
                    val b = item.bone!!
                    val color = getZColor(item.depth, b.isForeground, style)
                    val outline = style.outlineWidth * item.outlineScale
                    drawLinearBone(Offset(b.p1.x, b.p1.y), Offset(b.p2.x, b.p2.y), item.thickness + (outline * 2f), Color(0xFF0A0F14))
                    drawLinearBone(Offset(b.p1.x, b.p1.y), Offset(b.p2.x, b.p2.y), item.thickness, color)
                }
                RenderItem.Type.JOINT -> {
                    val j = item.joint!!
                    val color = getZColor(item.depth, j.isIndicator, style)
                    val outline = 2f * item.outlineScale
                    drawCircle(Color(0xFF0A0F14), item.radius + outline, Offset(j.point.x, j.point.y))
                    drawCircle(color, item.radius, Offset(j.point.x, j.point.y))
                }
                RenderItem.Type.FACE -> {
                    val f = item.face!!
                    val color = getZColor(f.avgDepth, false, style)
                    val strokeC = Color(color.red * 0.6f, color.green * 0.6f, color.blue * 0.6f, 1.0f)
                    val fillC = Color(color.red * 0.9f, color.green * 0.9f, color.blue * 0.9f, 1.0f)
                    path.reset()
                    val facePoints = f.points
                    for (idx in 0 until facePoints.size) {
                        val p = facePoints[idx]
                        if (idx == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                    }
                    path.close()
                    drawPath(path, fillC)
                    drawPath(path, strokeC, style = androidx.compose.ui.graphics.drawscope.Stroke(width = style.outlineWidth))
                }
                else -> {}
            }
        }
    }
}

private class RenderItem {
    enum class Type { NONE, BONE, JOINT, FACE }
    var type = Type.NONE
    var depth = 0f

    var bone: ProjectedBone? = null
    var thickness = 0f
    var outlineScale = 0f

    var joint: ProjectedJoint? = null
    var radius = 0f

    var face: ProjectedFace? = null

    fun populateBone(b: ProjectedBone, thick: Float, outline: Float) {
        type = Type.BONE
        bone = b
        depth = b.avgDepth
        thickness = thick
        outlineScale = outline
    }

    fun populateJoint(j: ProjectedJoint, rad: Float, outline: Float) {
        type = Type.JOINT
        joint = j
        depth = j.point.depth
        radius = rad
        outlineScale = outline
    }

    fun populateFace(f: ProjectedFace) {
        type = Type.FACE
        face = f
        depth = f.avgDepth
    }
}

private fun getZColor(depth: Float, isForeground: Boolean, style: SkeletonStyle): Color {
    val t = ((170f - depth) / 340f).coerceIn(0f, 1f)
    val baseC = lerp(style.farColor, style.secondaryColor, t)
    return if (isForeground) lerp(baseC, style.primaryColor, 0.3f) else baseC
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawLinearBone(start: Offset, end: Offset, thickness: Float, color: Color) {
    drawLine(color = color, start = start, end = end, strokeWidth = thickness, cap = StrokeCap.Round)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawGroundPassive(
    skeleton: ProjectedSkeleton,
    style: SkeletonStyle,
    compensator: ScreenSpaceCompensation,
    zoom: Float,
    scaleBuffer: ScreenSpaceScale
) {
    val gridColor = Color(0x5A3A445C)
    for (i in 0 until skeleton.gridLineCount) {
        val l = skeleton.gridLines[i]
        drawLine(gridColor, Offset(l.p1.x, l.p1.y), Offset(l.p2.x, l.p2.y), strokeWidth = 1f)
    }
    val shadowColor = Color(0x9605080C)
    val shadowPoints = skeleton.shadowPoints
    for (i in 0 until shadowPoints.size) {
        val p = shadowPoints[i]
        compensator.computeScale(p, zoom, scaleBuffer)
        val sx = style.shadowRadiusX * scaleBuffer.shadowScale
        val sy = style.shadowRadiusY * scaleBuffer.shadowScale
        drawOval(shadowColor, Offset(p.x - sx, p.y - sy), androidx.compose.ui.geometry.Size(sx * 2f, sy * 2f))
    }
}

// ----- SkeletonStyle.kt -----
data class SkeletonStyle(
    val headRadius: Float,
    val jointRadius: Float,
    val upperArmThickness: Float,
    val forearmThickness: Float,
    val thighThickness: Float,
    val shinThickness: Float,
    val handThickness: Float,
    val neckThickness: Float,
    val torsoChestDepth: Float,
    val torsoHipDepth: Float,
    val outlineWidth: Float,
    val shadowRadiusX: Float,
    val shadowRadiusY: Float,
    val primaryColor: Color = Color(0xFF64F0DC),
    val secondaryColor: Color = Color(0xFFB4C8DC),
    val farColor: Color = Color(0xFF192337)
) {
    companion object {
        val DEFAULT = SkeletonStyle(
            headRadius = 18f,
            jointRadius = 9f,
            upperArmThickness = 16f,
            forearmThickness = 13f,
            thighThickness = 21f,
            shinThickness = 16f,
            handThickness = 12f,
            neckThickness = 12f,
            torsoChestDepth = 22f,
            torsoHipDepth = 12f,
            outlineWidth = 2f,
            shadowRadiusX = 30f,
            shadowRadiusY = 9f
        )
    }
}
