package com.monkfitness.app.animation

class SpineChain private constructor(
    val pelvis: SkeletonNode,
    val chest: SkeletonNode,
    val neck: SkeletonNode,
    val head: SkeletonNode
) {
    companion object {
        fun create(
            root: SkeletonNode,
            pelvisJoint: Joint,
            chestJoint: Joint,
            neckJoint: Joint,
            headJoint: Joint
        ): SpineChain {
            val pelvis = root.addChild(SkeletonNode(pelvisJoint))
            val chest = pelvis.addChild(SkeletonNode(chestJoint))
            val neck = chest.addChild(SkeletonNode(neckJoint))
            val head = neck.addChild(SkeletonNode(headJoint))
            return SpineChain(pelvis, chest, neck, head)
        }
    }
}
