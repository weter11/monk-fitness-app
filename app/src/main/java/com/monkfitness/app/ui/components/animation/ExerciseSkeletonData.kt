package com.monkfitness.app.ui.components.animation

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.lerp
import kotlin.math.abs
import kotlin.math.sqrt

@Immutable
enum class Joint {
    HEAD,
    NECK,
    STERNUM,
    SPINE_MID,
    SPINE_LOW,
    PELVIS,
    L_SHOULDER,
    R_SHOULDER,
    L_ELBOW,
    R_ELBOW,
    L_WRIST,
    R_WRIST,
    L_HAND,
    R_HAND,
    L_HIP,
    R_HIP,
    L_KNEE,
    R_KNEE,
    L_ANKLE,
    R_ANKLE,
    L_TOE,
    R_TOE
}

@Immutable
data class SkeletonPose(val joints: Map<Joint, Offset>) {
    init {
        require(joints.size == Joint.entries.size) { "Skeleton pose must define all 22 joints." }
        require(Joint.entries.all(joints::containsKey)) { "Skeleton pose is missing required joints." }
    }

    operator fun get(joint: Joint): Offset = joints.getValue(joint)
}

@Immutable
data class SkeletonKeyframe(
    val fraction: Float,
    val pose: SkeletonPose
) {
    init {
        require(fraction in 0f..1f) { "Keyframe fraction must be between 0 and 1." }
    }
}

enum class SkeletonOverlay {
    PULL_BAR,
    HAND_CONNECTION,
    HEAD_ORBIT
}

@Immutable
data class SkeletonAnimation(
    val durationMs: Int = 1600,
    val keyframes: List<SkeletonKeyframe>,
    val showGround: Boolean = true,
    val overlays: Set<SkeletonOverlay> = emptySet()
) {
    init {
        require(keyframes.size >= 2) { "Skeleton animation requires at least two keyframes." }
        val fractions = keyframes.map { it.fraction }
        require(fractions.firstOrNull() == 0f) { "Skeleton animation must start at 0f." }
        require(fractions.lastOrNull() == 1f) { "Skeleton animation must end at 1f." }
        require(fractions.zipWithNext().all { (a, b) -> b >= a }) { "Keyframes must be sorted." }
    }
}

fun SkeletonAnimation.poseAt(fraction: Float): SkeletonPose {
    val clamped = fraction.coerceIn(0f, 1f)
    val nextIndex = keyframes.indexOfFirst { it.fraction >= clamped }
    if (nextIndex == -1) return keyframes.last().pose
    if (nextIndex == 0) return keyframes.first().pose
    val start = keyframes[nextIndex - 1]
    val end = keyframes[nextIndex]
    val local = inverseLerp(start.fraction, end.fraction, clamped)
    return SkeletonPose(Joint.entries.associateWith { joint -> lerp(start.pose[joint], end.pose[joint], local) })
}

fun exerciseSkeletonAnimation(exerciseId: String): SkeletonAnimation? = exerciseSkeletonAnimations[exerciseId]

internal fun allExerciseSkeletonAnimations(): Set<SkeletonAnimation> = exerciseSkeletonAnimations.values.toSet()

private val exerciseSkeletonAnimations: Map<String, SkeletonAnimation> = buildMap {
    register(
        ids = setOf("dips", "pike_pushups"),
        animation = animation(
            2000,
            true,
            emptySet(),
            frame(0f, pronePose(
                head = xy(0.26f, 0.43f),
                neck = xy(0.34f, 0.50f),
                sternum = xy(0.40f, 0.54f),
                spineMid = xy(0.48f, 0.56f),
                spineLow = xy(0.56f, 0.57f),
                pelvis = xy(0.62f, 0.58f),
                leftHand = xy(0.24f, 0.80f),
                rightHand = xy(0.39f, 0.80f),
                leftToe = xy(0.78f, 0.82f),
                rightToe = xy(0.87f, 0.82f),
                leftArmBend = 0.09f,
                rightArmBend = 0.07f,
                leftLegBend = 0.02f,
                rightLegBend = 0.02f
            )),
            frame(1f, pronePose(
                head = xy(0.27f, 0.47f),
                neck = xy(0.35f, 0.56f),
                sternum = xy(0.41f, 0.60f),
                spineMid = xy(0.49f, 0.62f),
                spineLow = xy(0.57f, 0.63f),
                pelvis = xy(0.63f, 0.64f),
                leftHand = xy(0.24f, 0.81f),
                rightHand = xy(0.39f, 0.81f),
                leftToe = xy(0.79f, 0.82f),
                rightToe = xy(0.88f, 0.82f),
                leftArmBend = 0.04f,
                rightArmBend = 0.03f,
                leftLegBend = 0.03f,
                rightLegBend = 0.03f
            ))
        )
    )

    register(
        ids = setOf("pushups"),
        animation = animation(
            1900,
            true,
            emptySet(),
            frame(0f, pronePose(
                head = xy(0.24f, 0.40f),
                neck = xy(0.32f, 0.48f),
                sternum = xy(0.39f, 0.52f),
                spineMid = xy(0.48f, 0.54f),
                spineLow = xy(0.56f, 0.55f),
                pelvis = xy(0.62f, 0.56f),
                leftHand = xy(0.22f, 0.79f),
                rightHand = xy(0.40f, 0.79f),
                leftToe = xy(0.82f, 0.81f),
                rightToe = xy(0.90f, 0.81f),
                leftArmBend = 0.05f,
                rightArmBend = 0.04f,
                leftLegBend = 0.01f,
                rightLegBend = 0.01f
            )),
            frame(0.5f, pronePose(
                head = xy(0.24f, 0.50f),
                neck = xy(0.32f, 0.58f),
                sternum = xy(0.39f, 0.62f),
                spineMid = xy(0.48f, 0.64f),
                spineLow = xy(0.56f, 0.65f),
                pelvis = xy(0.62f, 0.66f),
                leftHand = xy(0.22f, 0.79f),
                rightHand = xy(0.40f, 0.79f),
                leftToe = xy(0.82f, 0.81f),
                rightToe = xy(0.90f, 0.81f),
                leftArmBend = 0.09f,
                rightArmBend = 0.08f,
                leftLegBend = 0.01f,
                rightLegBend = 0.01f
            )),
            frame(1f, pronePose(
                head = xy(0.24f, 0.60f),
                neck = xy(0.32f, 0.68f),
                sternum = xy(0.39f, 0.72f),
                spineMid = xy(0.48f, 0.74f),
                spineLow = xy(0.56f, 0.75f),
                pelvis = xy(0.62f, 0.76f),
                leftHand = xy(0.22f, 0.79f),
                rightHand = xy(0.40f, 0.79f),
                leftToe = xy(0.82f, 0.81f),
                rightToe = xy(0.90f, 0.81f),
                leftArmBend = 0.16f,
                rightArmBend = 0.14f,
                leftLegBend = 0.01f,
                rightLegBend = 0.01f
            ))
        )
    )

    register(
        ids = setOf("decline_pushups"),
        animation = animation(
            1900,
            true,
            emptySet(),
            frame(0f, pronePose(
                head = xy(0.23f, 0.72f),
                neck = xy(0.31f, 0.65f),
                sternum = xy(0.38f, 0.58f),
                spineMid = xy(0.47f, 0.52f),
                spineLow = xy(0.55f, 0.48f),
                pelvis = xy(0.60f, 0.44f),
                leftHand = xy(0.20f, 0.79f),
                rightHand = xy(0.38f, 0.79f),
                leftToe = xy(0.78f, 0.60f),
                rightToe = xy(0.86f, 0.60f),
                leftArmBend = 0.06f,
                rightArmBend = 0.04f,
                leftLegBend = 0.02f,
                rightLegBend = 0.02f
            )),
            frame(0.5f, pronePose(
                head = xy(0.23f, 0.81f),
                neck = xy(0.31f, 0.74f),
                sternum = xy(0.38f, 0.67f),
                spineMid = xy(0.47f, 0.61f),
                spineLow = xy(0.55f, 0.57f),
                pelvis = xy(0.60f, 0.53f),
                leftHand = xy(0.20f, 0.79f),
                rightHand = xy(0.38f, 0.79f),
                leftToe = xy(0.78f, 0.60f),
                rightToe = xy(0.86f, 0.60f),
                leftArmBend = 0.10f,
                rightArmBend = 0.09f,
                leftLegBend = 0.02f,
                rightLegBend = 0.02f
            )),
            frame(1f, pronePose(
                head = xy(0.23f, 0.84f),
                neck = xy(0.31f, 0.82f),
                sternum = xy(0.38f, 0.75f),
                spineMid = xy(0.47f, 0.69f),
                spineLow = xy(0.55f, 0.65f),
                pelvis = xy(0.60f, 0.61f),
                leftHand = xy(0.20f, 0.79f),
                rightHand = xy(0.38f, 0.79f),
                leftToe = xy(0.78f, 0.60f),
                rightToe = xy(0.86f, 0.60f),
                leftArmBend = 0.17f,
                rightArmBend = 0.15f,
                leftLegBend = 0.02f,
                rightLegBend = 0.02f
            ))
        )
    )

    register(
        ids = setOf("diamond_pushups"),
        animation = animation(
            1900,
            true,
            emptySet(),
            frame(0f, pronePose(
                head = xy(0.24f, 0.40f),
                neck = xy(0.32f, 0.48f),
                sternum = xy(0.39f, 0.52f),
                spineMid = xy(0.48f, 0.54f),
                spineLow = xy(0.56f, 0.55f),
                pelvis = xy(0.62f, 0.56f),
                leftHand = xy(0.31f, 0.79f),
                rightHand = xy(0.44f, 0.79f),
                leftToe = xy(0.82f, 0.81f),
                rightToe = xy(0.90f, 0.81f),
                leftArmBend = 0.05f,
                rightArmBend = 0.04f,
                leftLegBend = 0.01f,
                rightLegBend = 0.01f
            )),
            frame(0.5f, pronePose(
                head = xy(0.24f, 0.50f),
                neck = xy(0.32f, 0.58f),
                sternum = xy(0.39f, 0.62f),
                spineMid = xy(0.48f, 0.64f),
                spineLow = xy(0.56f, 0.65f),
                pelvis = xy(0.62f, 0.66f),
                leftHand = xy(0.31f, 0.79f),
                rightHand = xy(0.44f, 0.79f),
                leftToe = xy(0.82f, 0.81f),
                rightToe = xy(0.90f, 0.81f),
                leftArmBend = 0.11f,
                rightArmBend = 0.10f,
                leftLegBend = 0.01f,
                rightLegBend = 0.01f
            )),
            frame(1f, pronePose(
                head = xy(0.24f, 0.60f),
                neck = xy(0.32f, 0.68f),
                sternum = xy(0.39f, 0.72f),
                spineMid = xy(0.48f, 0.74f),
                spineLow = xy(0.56f, 0.75f),
                pelvis = xy(0.62f, 0.76f),
                leftHand = xy(0.31f, 0.79f),
                rightHand = xy(0.44f, 0.79f),
                leftToe = xy(0.82f, 0.81f),
                rightToe = xy(0.90f, 0.81f),
                leftArmBend = 0.19f,
                rightArmBend = 0.17f,
                leftLegBend = 0.01f,
                rightLegBend = 0.01f
            ))
        )
    )

    register(
        ids = setOf("squats", "lunges", "step_ups", "wall_sit", "deep_squat", "ankle_mobility", "hamstring_stretch", "calf_stretch"),
        animation = animation(
            1800,
            true,
            emptySet(),
            frame(0f, uprightPose(
                head = xy(0.50f, 0.20f),
                neck = xy(0.50f, 0.28f),
                sternum = xy(0.50f, 0.36f),
                spineMid = xy(0.50f, 0.44f),
                spineLow = xy(0.50f, 0.51f),
                pelvis = xy(0.50f, 0.58f),
                leftHand = xy(0.37f, 0.53f),
                rightHand = xy(0.63f, 0.53f),
                leftToe = xy(0.37f, 0.92f),
                rightToe = xy(0.63f, 0.92f),
                leftLegBend = 0.04f,
                rightLegBend = 0.04f
            )),
            frame(0.5f, uprightPose(
                head = xy(0.50f, 0.31f),
                neck = xy(0.50f, 0.40f),
                sternum = xy(0.50f, 0.50f),
                spineMid = xy(0.50f, 0.57f),
                spineLow = xy(0.50f, 0.65f),
                pelvis = xy(0.50f, 0.72f),
                leftHand = xy(0.37f, 0.67f),
                rightHand = xy(0.63f, 0.67f),
                leftToe = xy(0.26f, 0.92f),
                rightToe = xy(0.74f, 0.92f),
                leftLegBend = 0.18f,
                rightLegBend = 0.18f
            )),
            frame(1f, uprightPose(
                head = xy(0.50f, 0.23f),
                neck = xy(0.50f, 0.32f),
                sternum = xy(0.50f, 0.41f),
                spineMid = xy(0.50f, 0.49f),
                spineLow = xy(0.50f, 0.57f),
                pelvis = xy(0.50f, 0.64f),
                leftHand = xy(0.37f, 0.59f),
                rightHand = xy(0.63f, 0.59f),
                leftToe = xy(0.31f, 0.92f),
                rightToe = xy(0.69f, 0.92f),
                leftLegBend = 0.12f,
                rightLegBend = 0.12f
            ))
        )
    )

    register(
        ids = setOf("pullups", "rows", "scapular_pullups", "hang"),
        animation = animation(
            2200,
            false,
            setOf(SkeletonOverlay.PULL_BAR),
            frame(0f, uprightPose(
                head = xy(0.50f, 0.29f),
                neck = xy(0.50f, 0.38f),
                sternum = xy(0.50f, 0.46f),
                spineMid = xy(0.50f, 0.54f),
                spineLow = xy(0.50f, 0.62f),
                pelvis = xy(0.50f, 0.68f),
                leftHand = xy(0.38f, 0.12f),
                rightHand = xy(0.62f, 0.12f),
                leftToe = xy(0.43f, 0.94f),
                rightToe = xy(0.57f, 0.94f),
                leftArmBend = 0.05f,
                rightArmBend = 0.05f,
                leftLegBend = 0.02f,
                rightLegBend = 0.02f
            )),
            frame(1f, uprightPose(
                head = xy(0.50f, 0.22f),
                neck = xy(0.50f, 0.30f),
                sternum = xy(0.50f, 0.38f),
                spineMid = xy(0.50f, 0.47f),
                spineLow = xy(0.50f, 0.56f),
                pelvis = xy(0.50f, 0.63f),
                leftHand = xy(0.38f, 0.12f),
                rightHand = xy(0.62f, 0.12f),
                leftToe = xy(0.43f, 0.89f),
                rightToe = xy(0.57f, 0.89f),
                leftArmBend = 0.12f,
                rightArmBend = 0.12f,
                leftLegBend = 0.03f,
                rightLegBend = 0.03f
            ))
        )
    )

    register(
        ids = setOf("plank", "mountain_climbers"),
        animation = animation(
            1400,
            true,
            emptySet(),
            frame(0f, pronePose(
                head = xy(0.29f, 0.47f),
                neck = xy(0.37f, 0.53f),
                sternum = xy(0.44f, 0.56f),
                spineMid = xy(0.53f, 0.57f),
                spineLow = xy(0.61f, 0.57f),
                pelvis = xy(0.66f, 0.57f),
                leftHand = xy(0.24f, 0.81f),
                rightHand = xy(0.40f, 0.81f),
                leftToe = xy(0.83f, 0.82f),
                rightToe = xy(0.90f, 0.82f),
                leftArmBend = 0.03f,
                rightArmBend = 0.03f,
                leftLegBend = 0.04f,
                rightLegBend = 0.04f
            )),
            frame(0.5f, pronePose(
                head = xy(0.29f, 0.47f),
                neck = xy(0.37f, 0.53f),
                sternum = xy(0.44f, 0.56f),
                spineMid = xy(0.53f, 0.57f),
                spineLow = xy(0.61f, 0.57f),
                pelvis = xy(0.66f, 0.57f),
                leftHand = xy(0.24f, 0.81f),
                rightHand = xy(0.40f, 0.81f),
                leftToe = xy(0.83f, 0.82f),
                rightToe = xy(0.76f, 0.66f),
                leftArmBend = 0.03f,
                rightArmBend = 0.03f,
                leftLegBend = 0.04f,
                rightLegBend = 0.10f
            )),
            frame(1f, pronePose(
                head = xy(0.30f, 0.48f),
                neck = xy(0.38f, 0.54f),
                sternum = xy(0.45f, 0.57f),
                spineMid = xy(0.53f, 0.57f),
                spineLow = xy(0.60f, 0.56f),
                pelvis = xy(0.64f, 0.54f),
                leftHand = xy(0.24f, 0.81f),
                rightHand = xy(0.40f, 0.81f),
                leftToe = xy(0.83f, 0.82f),
                rightToe = xy(0.88f, 0.74f),
                leftArmBend = 0.03f,
                rightArmBend = 0.03f,
                leftLegBend = 0.04f,
                rightLegBend = 0.15f
            ))
        )
    )

    register(
        ids = setOf("glute_bridge"),
        animation = animation(
            1600,
            true,
            emptySet(),
            frame(0f, supinePose(
                head = xy(0.22f, 0.64f),
                neck = xy(0.29f, 0.64f),
                sternum = xy(0.35f, 0.64f),
                spineMid = xy(0.42f, 0.65f),
                spineLow = xy(0.49f, 0.66f),
                pelvis = xy(0.55f, 0.67f),
                leftHand = xy(0.22f, 0.82f),
                rightHand = xy(0.36f, 0.82f),
                leftToe = xy(0.69f, 0.84f),
                rightToe = xy(0.81f, 0.84f),
                leftLegBend = 0.12f,
                rightLegBend = 0.12f
            )),
            frame(0.5f, supinePose(
                head = xy(0.22f, 0.63f),
                neck = xy(0.30f, 0.62f),
                sternum = xy(0.37f, 0.56f),
                spineMid = xy(0.45f, 0.52f),
                spineLow = xy(0.52f, 0.48f),
                pelvis = xy(0.57f, 0.44f),
                leftHand = xy(0.22f, 0.82f),
                rightHand = xy(0.36f, 0.82f),
                leftToe = xy(0.69f, 0.84f),
                rightToe = xy(0.81f, 0.84f),
                leftLegBend = 0.06f,
                rightLegBend = 0.06f
            )),
            frame(1f, supinePose(
                head = xy(0.22f, 0.63f),
                neck = xy(0.30f, 0.62f),
                sternum = xy(0.37f, 0.60f),
                spineMid = xy(0.45f, 0.57f),
                spineLow = xy(0.52f, 0.54f),
                pelvis = xy(0.57f, 0.52f),
                leftHand = xy(0.22f, 0.82f),
                rightHand = xy(0.36f, 0.82f),
                leftToe = xy(0.69f, 0.84f),
                rightToe = xy(0.81f, 0.84f),
                leftLegBend = 0.09f,
                rightLegBend = 0.09f
            ))
        )
    )

    register(
        ids = setOf("cat_cow", "bird_dog", "thoracic_rotations", "thoracic_extension"),
        animation = animation(
            1800,
            true,
            emptySet(),
            frame(0f, quadrupedPose(
                head = xy(0.28f, 0.41f),
                neck = xy(0.34f, 0.46f),
                sternum = xy(0.41f, 0.50f),
                spineMid = xy(0.49f, 0.54f),
                spineLow = xy(0.56f, 0.58f),
                pelvis = xy(0.61f, 0.60f),
                leftHand = xy(0.24f, 0.50f),
                rightHand = xy(0.48f, 0.77f),
                leftToe = xy(0.62f, 0.88f),
                rightToe = xy(0.84f, 0.69f),
                leftArmBend = 0.15f,
                rightArmBend = 0.05f,
                leftLegBend = 0.04f,
                rightLegBend = 0.14f
            )),
            frame(1f, quadrupedPose(
                head = xy(0.29f, 0.42f),
                neck = xy(0.35f, 0.47f),
                sternum = xy(0.42f, 0.51f),
                spineMid = xy(0.49f, 0.54f),
                spineLow = xy(0.56f, 0.55f),
                pelvis = xy(0.60f, 0.56f),
                leftHand = xy(0.25f, 0.77f),
                rightHand = xy(0.47f, 0.77f),
                leftToe = xy(0.62f, 0.88f),
                rightToe = xy(0.84f, 0.88f),
                leftArmBend = 0.05f,
                rightArmBend = 0.05f,
                leftLegBend = 0.04f,
                rightLegBend = 0.04f
            ))
        )
    )

    register(
        ids = setOf("cobra_stretch", "child_pose", "superman"),
        animation = animation(
            2400,
            true,
            emptySet(),
            frame(0f, pronePose(
                head = xy(0.30f, 0.42f),
                neck = xy(0.36f, 0.48f),
                sternum = xy(0.41f, 0.54f),
                spineMid = xy(0.48f, 0.61f),
                spineLow = xy(0.55f, 0.67f),
                pelvis = xy(0.60f, 0.70f),
                leftHand = xy(0.22f, 0.76f),
                rightHand = xy(0.40f, 0.76f),
                leftToe = xy(0.71f, 0.88f),
                rightToe = xy(0.82f, 0.88f),
                leftArmBend = 0.10f,
                rightArmBend = 0.08f,
                leftLegBend = 0.05f,
                rightLegBend = 0.05f
            )),
            frame(1f, pronePose(
                head = xy(0.28f, 0.50f),
                neck = xy(0.35f, 0.56f),
                sternum = xy(0.42f, 0.61f),
                spineMid = xy(0.49f, 0.66f),
                spineLow = xy(0.55f, 0.70f),
                pelvis = xy(0.60f, 0.72f),
                leftHand = xy(0.20f, 0.81f),
                rightHand = xy(0.42f, 0.80f),
                leftToe = xy(0.70f, 0.91f),
                rightToe = xy(0.81f, 0.91f),
                leftArmBend = 0.06f,
                rightArmBend = 0.05f,
                leftLegBend = 0.04f,
                rightLegBend = 0.04f
            ))
        )
    )

    register(
        ids = setOf("world_greatest_stretch", "burpees", "kettlebell_swing"),
        animation = animation(
            1600,
            true,
            emptySet(),
            frame(0f, uprightPose(
                head = xy(0.52f, 0.21f),
                neck = xy(0.52f, 0.29f),
                sternum = xy(0.52f, 0.37f),
                spineMid = xy(0.52f, 0.45f),
                spineLow = xy(0.52f, 0.52f),
                pelvis = xy(0.52f, 0.58f),
                leftHand = xy(0.32f, 0.52f),
                rightHand = xy(0.72f, 0.52f),
                leftToe = xy(0.36f, 0.92f),
                rightToe = xy(0.68f, 0.92f),
                leftArmBend = 0.06f,
                rightArmBend = 0.06f,
                leftLegBend = 0.05f,
                rightLegBend = 0.05f
            )),
            frame(0.5f, uprightPose(
                head = xy(0.48f, 0.36f),
                neck = xy(0.49f, 0.43f),
                sternum = xy(0.50f, 0.50f),
                spineMid = xy(0.51f, 0.57f),
                spineLow = xy(0.52f, 0.64f),
                pelvis = xy(0.52f, 0.72f),
                leftHand = xy(0.36f, 0.78f),
                rightHand = xy(0.64f, 0.78f),
                leftToe = xy(0.36f, 0.92f),
                rightToe = xy(0.68f, 0.92f),
                leftArmBend = 0.05f,
                rightArmBend = 0.05f,
                leftLegBend = 0.10f,
                rightLegBend = 0.10f
            )),
            frame(1f, uprightPose(
                head = xy(0.52f, 0.24f),
                neck = xy(0.52f, 0.33f),
                sternum = xy(0.52f, 0.42f),
                spineMid = xy(0.52f, 0.50f),
                spineLow = xy(0.52f, 0.58f),
                pelvis = xy(0.52f, 0.64f),
                leftHand = xy(0.33f, 0.19f),
                rightHand = xy(0.71f, 0.19f),
                leftToe = xy(0.36f, 0.92f),
                rightToe = xy(0.68f, 0.92f),
                leftArmBend = 0.10f,
                rightArmBend = 0.10f,
                leftLegBend = 0.08f,
                rightLegBend = 0.08f
            ))
        )
    )

    register(
        ids = setOf("face_pull", "wall_slides", "reverse_snow_angels", "band_pull_aparts", "shoulder_cars", "y_t_raises", "scapular_retraction_hold", "lat_stretch", "arm_circles"),
        animation = animation(
            1600,
            true,
            setOf(SkeletonOverlay.HAND_CONNECTION),
            frame(0f, uprightPose(
                head = xy(0.50f, 0.20f),
                neck = xy(0.50f, 0.29f),
                sternum = xy(0.50f, 0.38f),
                spineMid = xy(0.50f, 0.46f),
                spineLow = xy(0.50f, 0.53f),
                pelvis = xy(0.50f, 0.58f),
                leftHand = xy(0.30f, 0.20f),
                rightHand = xy(0.70f, 0.20f),
                leftToe = xy(0.42f, 0.92f),
                rightToe = xy(0.58f, 0.92f),
                leftArmBend = 0.08f,
                rightArmBend = 0.08f,
                leftLegBend = 0.03f,
                rightLegBend = 0.03f
            )),
            frame(1f, uprightPose(
                head = xy(0.50f, 0.20f),
                neck = xy(0.50f, 0.29f),
                sternum = xy(0.50f, 0.38f),
                spineMid = xy(0.50f, 0.46f),
                spineLow = xy(0.50f, 0.53f),
                pelvis = xy(0.50f, 0.58f),
                leftHand = xy(0.30f, 0.62f),
                rightHand = xy(0.70f, 0.62f),
                leftToe = xy(0.42f, 0.92f),
                rightToe = xy(0.58f, 0.92f),
                leftArmBend = 0.06f,
                rightArmBend = 0.06f,
                leftLegBend = 0.03f,
                rightLegBend = 0.03f
            ))
        )
    )

    register(
        ids = setOf("hip_flexor_stretch", "hip_cars", "ninety_ninety_hips", "piriformis_stretch", "hip_circles", "leg_swings"),
        animation = animation(
            1800,
            true,
            emptySet(),
            frame(0f, uprightPose(
                head = xy(0.48f, 0.20f),
                neck = xy(0.48f, 0.29f),
                sternum = xy(0.48f, 0.38f),
                spineMid = xy(0.48f, 0.46f),
                spineLow = xy(0.48f, 0.53f),
                pelvis = xy(0.48f, 0.58f),
                leftHand = xy(0.35f, 0.53f),
                rightHand = xy(0.61f, 0.53f),
                leftToe = xy(0.37f, 0.92f),
                rightToe = xy(0.76f, 0.74f),
                leftLegBend = 0.04f,
                rightLegBend = 0.10f
            )),
            frame(1f, uprightPose(
                head = xy(0.48f, 0.20f),
                neck = xy(0.48f, 0.29f),
                sternum = xy(0.48f, 0.38f),
                spineMid = xy(0.48f, 0.46f),
                spineLow = xy(0.48f, 0.53f),
                pelvis = xy(0.48f, 0.58f),
                leftHand = xy(0.35f, 0.53f),
                rightHand = xy(0.61f, 0.53f),
                leftToe = xy(0.37f, 0.92f),
                rightToe = xy(0.71f, 0.50f),
                leftLegBend = 0.04f,
                rightLegBend = 0.16f
            ))
        )
    )

    register(
        ids = setOf("horse_stance"),
        animation = animation(
            2000,
            true,
            emptySet(),
            frame(0f, uprightPose(
                head = xy(0.50f, 0.21f),
                neck = xy(0.50f, 0.30f),
                sternum = xy(0.50f, 0.39f),
                spineMid = xy(0.50f, 0.47f),
                spineLow = xy(0.50f, 0.55f),
                pelvis = xy(0.50f, 0.61f),
                leftHand = xy(0.33f, 0.54f),
                rightHand = xy(0.67f, 0.54f),
                leftToe = xy(0.21f, 0.92f),
                rightToe = xy(0.79f, 0.92f),
                leftLegBend = 0.14f,
                rightLegBend = 0.14f
            )),
            frame(1f, uprightPose(
                head = xy(0.50f, 0.22f),
                neck = xy(0.50f, 0.32f),
                sternum = xy(0.50f, 0.41f),
                spineMid = xy(0.50f, 0.50f),
                spineLow = xy(0.50f, 0.58f),
                pelvis = xy(0.50f, 0.65f),
                leftHand = xy(0.33f, 0.56f),
                rightHand = xy(0.67f, 0.56f),
                leftToe = xy(0.20f, 0.92f),
                rightToe = xy(0.80f, 0.92f),
                leftLegBend = 0.17f,
                rightLegBend = 0.17f
            ))
        )
    )

    register(
        ids = setOf("chin_tucks", "neck_circles"),
        animation = animation(
            1600,
            true,
            setOf(SkeletonOverlay.HEAD_ORBIT),
            frame(0f, uprightPose(
                head = xy(0.47f, 0.20f),
                neck = xy(0.50f, 0.29f),
                sternum = xy(0.50f, 0.38f),
                spineMid = xy(0.50f, 0.46f),
                spineLow = xy(0.50f, 0.53f),
                pelvis = xy(0.50f, 0.58f),
                leftHand = xy(0.40f, 0.51f),
                rightHand = xy(0.60f, 0.51f),
                leftToe = xy(0.42f, 0.92f),
                rightToe = xy(0.58f, 0.92f)
            )),
            frame(0.5f, uprightPose(
                head = xy(0.50f, 0.18f),
                neck = xy(0.50f, 0.29f),
                sternum = xy(0.50f, 0.38f),
                spineMid = xy(0.50f, 0.46f),
                spineLow = xy(0.50f, 0.53f),
                pelvis = xy(0.50f, 0.58f),
                leftHand = xy(0.40f, 0.51f),
                rightHand = xy(0.60f, 0.51f),
                leftToe = xy(0.42f, 0.92f),
                rightToe = xy(0.58f, 0.92f)
            )),
            frame(1f, uprightPose(
                head = xy(0.53f, 0.20f),
                neck = xy(0.50f, 0.29f),
                sternum = xy(0.50f, 0.38f),
                spineMid = xy(0.50f, 0.46f),
                spineLow = xy(0.50f, 0.53f),
                pelvis = xy(0.50f, 0.58f),
                leftHand = xy(0.40f, 0.51f),
                rightHand = xy(0.60f, 0.51f),
                leftToe = xy(0.42f, 0.92f),
                rightToe = xy(0.58f, 0.92f)
            ))
        )
    )

    register(
        ids = setOf("jumping_jacks"),
        animation = animation(
            1400,
            true,
            emptySet(),
            frame(0f, uprightPose(
                head = xy(0.50f, 0.20f),
                neck = xy(0.50f, 0.29f),
                sternum = xy(0.50f, 0.38f),
                spineMid = xy(0.50f, 0.46f),
                spineLow = xy(0.50f, 0.53f),
                pelvis = xy(0.50f, 0.58f),
                leftHand = xy(0.34f, 0.58f),
                rightHand = xy(0.66f, 0.58f),
                leftToe = xy(0.42f, 0.92f),
                rightToe = xy(0.58f, 0.92f),
                leftLegBend = 0.03f,
                rightLegBend = 0.03f
            )),
            frame(1f, uprightPose(
                head = xy(0.50f, 0.20f),
                neck = xy(0.50f, 0.29f),
                sternum = xy(0.50f, 0.38f),
                spineMid = xy(0.50f, 0.46f),
                spineLow = xy(0.50f, 0.53f),
                pelvis = xy(0.50f, 0.58f),
                leftHand = xy(0.23f, 0.18f),
                rightHand = xy(0.77f, 0.18f),
                leftToe = xy(0.26f, 0.92f),
                rightToe = xy(0.74f, 0.92f),
                leftLegBend = 0.06f,
                rightLegBend = 0.06f
            ))
        )
    )
}

private fun uprightPose(
    head: Offset,
    neck: Offset,
    sternum: Offset,
    spineMid: Offset,
    spineLow: Offset,
    pelvis: Offset,
    leftHand: Offset,
    rightHand: Offset,
    leftToe: Offset,
    rightToe: Offset,
    leftArmBend: Float = 0.08f,
    rightArmBend: Float = 0.08f,
    leftLegBend: Float = 0.05f,
    rightLegBend: Float = 0.05f
): SkeletonPose = buildPose(
    head = head,
    neck = neck,
    sternum = sternum,
    spineMid = spineMid,
    spineLow = spineLow,
    pelvis = pelvis,
    leftHand = leftHand,
    rightHand = rightHand,
    leftToe = leftToe,
    rightToe = rightToe,
    leftArmBend = leftArmBend,
    rightArmBend = rightArmBend,
    leftLegBend = leftLegBend,
    rightLegBend = rightLegBend,
    wristInset = 0.14f,
    ankleInset = 0.18f
)

private fun pronePose(
    head: Offset,
    neck: Offset,
    sternum: Offset,
    spineMid: Offset,
    spineLow: Offset,
    pelvis: Offset,
    leftHand: Offset,
    rightHand: Offset,
    leftToe: Offset,
    rightToe: Offset,
    leftArmBend: Float = 0.06f,
    rightArmBend: Float = 0.06f,
    leftLegBend: Float = 0.04f,
    rightLegBend: Float = 0.04f
): SkeletonPose = buildPose(
    head = head,
    neck = neck,
    sternum = sternum,
    spineMid = spineMid,
    spineLow = spineLow,
    pelvis = pelvis,
    leftHand = leftHand,
    rightHand = rightHand,
    leftToe = leftToe,
    rightToe = rightToe,
    leftArmBend = leftArmBend,
    rightArmBend = rightArmBend,
    leftLegBend = leftLegBend,
    rightLegBend = rightLegBend,
    wristInset = 0.12f,
    ankleInset = 0.14f
)

private fun supinePose(
    head: Offset,
    neck: Offset,
    sternum: Offset,
    spineMid: Offset,
    spineLow: Offset,
    pelvis: Offset,
    leftHand: Offset,
    rightHand: Offset,
    leftToe: Offset,
    rightToe: Offset,
    leftLegBend: Float,
    rightLegBend: Float
): SkeletonPose = buildPose(
    head = head,
    neck = neck,
    sternum = sternum,
    spineMid = spineMid,
    spineLow = spineLow,
    pelvis = pelvis,
    leftHand = leftHand,
    rightHand = rightHand,
    leftToe = leftToe,
    rightToe = rightToe,
    leftArmBend = 0.04f,
    rightArmBend = 0.04f,
    leftLegBend = leftLegBend,
    rightLegBend = rightLegBend,
    wristInset = 0.18f,
    ankleInset = 0.20f
)

private fun quadrupedPose(
    head: Offset,
    neck: Offset,
    sternum: Offset,
    spineMid: Offset,
    spineLow: Offset,
    pelvis: Offset,
    leftHand: Offset,
    rightHand: Offset,
    leftToe: Offset,
    rightToe: Offset,
    leftArmBend: Float,
    rightArmBend: Float,
    leftLegBend: Float,
    rightLegBend: Float
): SkeletonPose = buildPose(
    head = head,
    neck = neck,
    sternum = sternum,
    spineMid = spineMid,
    spineLow = spineLow,
    pelvis = pelvis,
    leftHand = leftHand,
    rightHand = rightHand,
    leftToe = leftToe,
    rightToe = rightToe,
    leftArmBend = leftArmBend,
    rightArmBend = rightArmBend,
    leftLegBend = leftLegBend,
    rightLegBend = rightLegBend,
    wristInset = 0.12f,
    ankleInset = 0.10f
)

private fun buildPose(
    head: Offset,
    neck: Offset,
    sternum: Offset,
    spineMid: Offset,
    spineLow: Offset,
    pelvis: Offset,
    leftHand: Offset,
    rightHand: Offset,
    leftToe: Offset,
    rightToe: Offset,
    leftArmBend: Float,
    rightArmBend: Float,
    leftLegBend: Float,
    rightLegBend: Float,
    wristInset: Float,
    ankleInset: Float
): SkeletonPose {
    val spineDirection = (pelvis - neck).safeNormalized(Offset(0f, 1f))
    val normal = perpendicular(spineDirection)
    val leftShoulder = sternum + normal.scaled(0.06f)
    val rightShoulder = sternum - normal.scaled(0.06f)
    val leftHip = pelvis + normal.scaled(0.04f)
    val rightHip = pelvis - normal.scaled(0.04f)
    val leftWrist = lerp(leftHand, leftShoulder, wristInset)
    val rightWrist = lerp(rightHand, rightShoulder, wristInset)
    val leftAnkle = lerp(leftToe, leftHip, ankleInset)
    val rightAnkle = lerp(rightToe, rightHip, ankleInset)
    val leftElbow = solveTwoBoneJoint(leftShoulder, leftWrist, leftArmBend, normal)
    val rightElbow = solveTwoBoneJoint(rightShoulder, rightWrist, -rightArmBend, normal)
    val leftKnee = solveTwoBoneJoint(leftHip, leftAnkle, leftLegBend, normal)
    val rightKnee = solveTwoBoneJoint(rightHip, rightAnkle, -rightLegBend, normal)

    return SkeletonPose(
        mapOf(
            Joint.HEAD to head,
            Joint.NECK to neck,
            Joint.STERNUM to sternum,
            Joint.SPINE_MID to spineMid,
            Joint.SPINE_LOW to spineLow,
            Joint.PELVIS to pelvis,
            Joint.L_SHOULDER to leftShoulder,
            Joint.R_SHOULDER to rightShoulder,
            Joint.L_ELBOW to leftElbow,
            Joint.R_ELBOW to rightElbow,
            Joint.L_WRIST to leftWrist,
            Joint.R_WRIST to rightWrist,
            Joint.L_HAND to leftHand,
            Joint.R_HAND to rightHand,
            Joint.L_HIP to leftHip,
            Joint.R_HIP to rightHip,
            Joint.L_KNEE to leftKnee,
            Joint.R_KNEE to rightKnee,
            Joint.L_ANKLE to leftAnkle,
            Joint.R_ANKLE to rightAnkle,
            Joint.L_TOE to leftToe,
            Joint.R_TOE to rightToe
        )
    )
}

private fun solveTwoBoneJoint(
    root: Offset,
    target: Offset,
    bendAmount: Float,
    normal: Offset
): Offset {
    val segment = target - root
    val distance = segment.magnitude().coerceAtLeast(0.0001f)
    val direction = segment.safeNormalized(Offset(1f, 0f))
    val bend = normal.safeNormalized(Offset(0f, 1f)).scaled(bendAmount)
    return root + direction.scaled(distance * 0.5f) + bend * distance
}

private fun animation(
    durationMs: Int = 1600,
    showGround: Boolean = true,
    overlays: Set<SkeletonOverlay> = emptySet(),
    vararg frames: SkeletonKeyframe
): SkeletonAnimation = SkeletonAnimation(
    durationMs = durationMs,
    keyframes = frames.toList(),
    showGround = showGround,
    overlays = overlays
)

private fun frame(fraction: Float, pose: SkeletonPose): SkeletonKeyframe = SkeletonKeyframe(fraction, pose)

private fun MutableMap<String, SkeletonAnimation>.register(
    ids: Set<String>,
    animation: SkeletonAnimation
) {
    ids.forEach { put(it, animation) }
}

private fun xy(x: Float, y: Float): Offset = Offset(x, y)

private fun perpendicular(vector: Offset): Offset = Offset(-vector.y, vector.x)

private fun inverseLerp(start: Float, end: Float, value: Float): Float {
    val span = end - start
    return if (abs(span) < 0.0001f) 0f else (value - start) / span
}

private fun Offset.magnitude(): Float = sqrt((x * x) + (y * y))

private fun Offset.safeNormalized(fallback: Offset): Offset {
    val length = magnitude()
    return if (length < 0.0001f) fallback else Offset(x / length, y / length)
}

private fun Offset.scaled(scale: Float): Offset = Offset(x * scale, y * scale)
