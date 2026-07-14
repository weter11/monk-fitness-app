package com.monkfitness.app.validation

import com.monkfitness.app.animation.PoseBuilder
import com.monkfitness.app.validation.poses.DeadHangPose
import com.monkfitness.app.validation.poses.DeepOverheadSquatPose
import com.monkfitness.app.validation.poses.MiddleSplitPose
import com.monkfitness.app.validation.poses.PikeSitPose

/**
 * Separate, self-contained registry for Engineering Validation poses.
 *
 * This registry is intentionally independent of [com.monkfitness.app.poses.PoseRegistry],
 * [com.monkfitness.app.domain.usecase.WorkoutGenerator] and the exercise catalog. It only
 * shares the rendering engine ([com.monkfitness.app.animation.PoseBuilder],
 * [com.monkfitness.app.animation.SkeletonFactory], [com.monkfitness.app.animation.SkeletonRenderer]).
 *
 * It contains exactly four static reference poses and nothing else.
 */
object ValidationPoseRegistry {

    val poses: List<ValidationPose> = listOf(
        ValidationPose(
            id = "test_middle_split",
            nameRes = R.string.validation_middle_split_name,
            descriptionRes = R.string.validation_middle_split_desc,
            techniqueRes = R.string.validation_middle_split_tech,
            builder = MiddleSplitPose(),
            purposeRes = listOf(
                R.string.validation_middle_split_p1,
                R.string.validation_middle_split_p2,
                R.string.validation_middle_split_p3
            )
        ),
        ValidationPose(
            id = "test_pike_sit",
            nameRes = R.string.validation_pike_sit_name,
            descriptionRes = R.string.validation_pike_sit_desc,
            techniqueRes = R.string.validation_pike_sit_tech,
            builder = PikeSitPose(),
            purposeRes = listOf(
                R.string.validation_pike_sit_p1,
                R.string.validation_pike_sit_p2,
                R.string.validation_pike_sit_p3
            )
        ),
        ValidationPose(
            id = "test_deep_overhead_squat",
            nameRes = R.string.validation_deep_overhead_squat_name,
            descriptionRes = R.string.validation_deep_overhead_squat_desc,
            techniqueRes = R.string.validation_deep_overhead_squat_tech,
            builder = DeepOverheadSquatPose(),
            purposeRes = listOf(
                R.string.validation_deep_overhead_squat_p1,
                R.string.validation_deep_overhead_squat_p2,
                R.string.validation_deep_overhead_squat_p3
            )
        ),
        ValidationPose(
            id = "test_dead_hang",
            nameRes = R.string.validation_dead_hang_name,
            descriptionRes = R.string.validation_dead_hang_desc,
            techniqueRes = R.string.validation_dead_hang_tech,
            builder = DeadHangPose(),
            purposeRes = listOf(
                R.string.validation_dead_hang_p1,
                R.string.validation_dead_hang_p2,
                R.string.validation_dead_hang_p3
            )
        )
    )

    private val posesById = poses.associateBy { it.id }

    fun get(id: String): ValidationPose? = posesById[id]

    fun getBuilder(id: String): PoseBuilder? = posesById[id]?.builder

    val ids: Set<String> get() = posesById.keys
}
