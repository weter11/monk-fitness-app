package com.monkfitness.app.animation

class LegChain private constructor(
    val hip: SkeletonNode,
    val knee: SkeletonNode,
    val ankle: SkeletonNode,
    val heel: SkeletonNode?,
    val toe: SkeletonNode?
) {
    companion object {
        fun create(
            parent: SkeletonNode,
            hipJoint: Joint,
            kneeJoint: Joint,
            ankleJoint: Joint,
            heelJoint: Joint? = null,
            toeJoint: Joint? = null
        ): LegChain {
            val isFront = hipJoint == Joint.HIP_F
            if (isFront) {
                // Bottom-up hierarchy: ankle is root (parent parameter ignored)
                val ankle = SkeletonNode(ankleJoint)
                val knee = ankle.addChild(SkeletonNode(kneeJoint))
                val hip = knee.addChild(SkeletonNode(hipJoint))

                var heel: SkeletonNode? = null
                var toe: SkeletonNode? = null

                if (heelJoint != null) {
                    heel = ankle.addChild(SkeletonNode(heelJoint))
                }
                if (toeJoint != null) {
                    toe = ankle.addChild(SkeletonNode(toeJoint))
                }

                return LegChain(hip, knee, ankle, heel, toe)
            } else {
                // Top-down hierarchy: hip is child of parent
                val hip = parent.addChild(SkeletonNode(hipJoint))
                val knee = hip.addChild(SkeletonNode(kneeJoint))
                val ankle = knee.addChild(SkeletonNode(ankleJoint))

                var heel: SkeletonNode? = null
                var toe: SkeletonNode? = null

                if (heelJoint != null) {
                    heel = ankle.addChild(SkeletonNode(heelJoint))
                }
                if (toeJoint != null) {
                    toe = ankle.addChild(SkeletonNode(toeJoint))
                }

                return LegChain(hip, knee, ankle, heel, toe)
            }
        }
    }
}
