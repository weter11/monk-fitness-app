package com.monkfitness.app.animation

class ArmChain private constructor(
    val shoulder: SkeletonNode,
    val elbow: SkeletonNode,
    val hand: SkeletonNode,
    val palm: SkeletonNode?,
    val knuckles: SkeletonNode?,
    val fingertips: SkeletonNode?
) {
    companion object {
        fun create(
            parent: SkeletonNode,
            shoulderJoint: Joint,
            elbowJoint: Joint,
            handJoint: Joint,
            palmJoint: Joint? = null,
            knucklesJoint: Joint? = null,
            fingertipsJoint: Joint? = null
        ): ArmChain {
            val shoulder = parent.addChild(SkeletonNode(shoulderJoint))
            val elbow = shoulder.addChild(SkeletonNode(elbowJoint))
            val hand = elbow.addChild(SkeletonNode(handJoint))

            var palm: SkeletonNode? = null
            var knuckles: SkeletonNode? = null
            var fingertips: SkeletonNode? = null

            if (palmJoint != null) {
                palm = hand.addChild(SkeletonNode(palmJoint))
                if (knucklesJoint != null) {
                    knuckles = palm.addChild(SkeletonNode(knucklesJoint))
                    if (fingertipsJoint != null) {
                        fingertips = knuckles.addChild(SkeletonNode(fingertipsJoint))
                    }
                }
            }

            return ArmChain(shoulder, elbow, hand, palm, knuckles, fingertips)
        }
    }
}
