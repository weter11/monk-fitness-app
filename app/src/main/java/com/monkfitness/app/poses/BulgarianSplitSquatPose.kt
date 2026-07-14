package com.monkfitness.app.poses

import com.monkfitness.app.animation.*

/**
 * Bulgarian Split Squat — the rear foot is elevated on a bench behind the body. The front (support)
 * foot stays planted on the ground; the pelvis dips via a PING_PONG pulse. Because the rear foot is
 * fixed on the bench, the support foot is genuinely stationary. Arms counter-swing gently.
 */
class BulgarianSplitSquatPose : BaseLungePose() {

    override val metadata = PoseMetadata(
        camera = lungeCamera,
        durationSeconds = 5.0f,
        loopMode = LoopMode.PING_PONG,
        motionCurve = MotionCurve.EASE_IN_OUT,
        environment = EnvironmentDefinition(
            ground = GroundDefinition(visible = true, level = 0f),
            props = listOf(
                BenchProp(
                    center = Vector3(0f, 40f, -110f),
                    width = 40f,
                    height = 80f,
                    depth = 40f
                )
            )
        )
    )

    override fun build(context: PoseContext): SkeletonPose {
        val def = context.definition
        ensureHierarchy(def)

        val depth = MotionDrivers.Pulse(context.progress)
        val stride = 70f

        // Front (support) foot planted on the ground; rear foot fixed on the bench behind.
        targetB.set(stride, ANKLE_Y, def.hipWidth)
        targetF.set(0f, 80f, -100f)

        val pelvisX = (stride + 0f) * 0.5f + 10f * depth
        val pelvisZ = (def.hipWidth - 100f) * 0.5f
        val pelvisY = STAND_PELVIS_Y - 55f * depth
        val leanAngle = 0.16f * depth

        anchorSpine(def, pelvisX, pelvisY, pelvisZ, leanAngle)
        bakeLegs(def, leanAngle, targetF, targetB)

        val armSwing = MotionDrivers.FullSine(context.progress) * 0.3f
        bakeArms(def, leanAngle, armSwing, pelvisX, pelvisY)

        // Both feet flat (rear foot rests on the bench).
        applyExtremities(def, leanAngle, FOOT_PITCH_FLAT * depth, FOOT_PITCH_FLAT * depth)

        return finalizeLunge()
    }
}
