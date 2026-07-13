package com.monkfitness.app.animation

class SkeletonNodes(
    val roots: List<SkeletonNode>,
    val pelvis: SkeletonNode,
    val chest: SkeletonNode,
    val neck: SkeletonNode,
    val head: SkeletonNode,
    val shoulderA: SkeletonNode,
    val elbowA: SkeletonNode,
    val handA: SkeletonNode,
    val palmA: SkeletonNode,
    val knucklesA: SkeletonNode,
    val fingertipsA: SkeletonNode,
    val shoulderP: SkeletonNode,
    val elbowP: SkeletonNode,
    val handP: SkeletonNode,
    val palmP: SkeletonNode,
    val knucklesP: SkeletonNode,
    val fingertipsP: SkeletonNode,
    val hipF: SkeletonNode,
    val kneeF: SkeletonNode,
    val ankleF: SkeletonNode,
    val heelF: SkeletonNode,
    val toeF: SkeletonNode,
    val hipB: SkeletonNode,
    val kneeB: SkeletonNode,
    val ankleB: SkeletonNode,
    val heelB: SkeletonNode,
    val toeB: SkeletonNode
) {
    // Aliases to support standard humanoid conventions exactly as described in the PR
    val spine: SkeletonNode get() = chest
    val shoulderB: SkeletonNode get() = shoulderP
    val elbowB: SkeletonNode get() = elbowP
    val handB: SkeletonNode get() = handP
    val palmB: SkeletonNode get() = palmP
    val knucklesB: SkeletonNode get() = knucklesP
    val fingertipsB: SkeletonNode get() = fingertipsP

    val hipA: SkeletonNode get() = hipF
    val kneeA: SkeletonNode get() = kneeF
    val ankleA: SkeletonNode get() = ankleF
    val heelA: SkeletonNode get() = heelF
    val toeA: SkeletonNode get() = toeF

    val wristA: SkeletonNode get() = handA
    val wristB: SkeletonNode get() = handP
    val wristP: SkeletonNode get() = handP
}

object SkeletonFactory {

    fun createStandardSkeleton(): SkeletonNodes {
        val pelvis = SkeletonNode(Joint.PELVIS)
        val chest = pelvis.addChild(SkeletonNode(Joint.CHEST))
        val neck = chest.addChild(SkeletonNode(Joint.NECK_END))
        val head = neck.addChild(SkeletonNode(Joint.HEAD_POS))

        val shoulderA = chest.addChild(SkeletonNode(Joint.SHOULDER_A))
        val elbowA = shoulderA.addChild(SkeletonNode(Joint.ELBOW_A))
        val handA = elbowA.addChild(SkeletonNode(Joint.HAND_A))
        val palmA = handA.addChild(SkeletonNode(Joint.PALM_A))
        val knucklesA = palmA.addChild(SkeletonNode(Joint.KNUCKLES_A))
        val fingertipsA = knucklesA.addChild(SkeletonNode(Joint.FINGERTIPS_A))

        val shoulderP = chest.addChild(SkeletonNode(Joint.SHOULDER_P))
        val elbowP = shoulderP.addChild(SkeletonNode(Joint.ELBOW_P))
        val handP = elbowP.addChild(SkeletonNode(Joint.HAND_P))
        val palmP = handP.addChild(SkeletonNode(Joint.PALM_P))
        val knucklesP = palmP.addChild(SkeletonNode(Joint.KNUCKLES_P))
        val fingertipsP = knucklesP.addChild(SkeletonNode(Joint.FINGERTIPS_P))

        val hipF = pelvis.addChild(SkeletonNode(Joint.HIP_F))
        val kneeF = hipF.addChild(SkeletonNode(Joint.KNEE_F))
        val ankleF = kneeF.addChild(SkeletonNode(Joint.ANKLE_F))
        val heelF = ankleF.addChild(SkeletonNode(Joint.HEEL_F))
        val toeF = ankleF.addChild(SkeletonNode(Joint.TOE_F))

        val hipB = pelvis.addChild(SkeletonNode(Joint.HIP_B))
        val kneeB = hipB.addChild(SkeletonNode(Joint.KNEE_B))
        val ankleB = kneeB.addChild(SkeletonNode(Joint.ANKLE_B))
        val heelB = ankleB.addChild(SkeletonNode(Joint.HEEL_B))
        val toeB = ankleB.addChild(SkeletonNode(Joint.TOE_B))

        return SkeletonNodes(
            roots = listOf(pelvis),
            pelvis = pelvis,
            chest = chest,
            neck = neck,
            head = head,
            shoulderA = shoulderA,
            elbowA = elbowA,
            handA = handA,
            palmA = palmA,
            knucklesA = knucklesA,
            fingertipsA = fingertipsA,
            shoulderP = shoulderP,
            elbowP = elbowP,
            handP = handP,
            palmP = palmP,
            knucklesP = knucklesP,
            fingertipsP = fingertipsP,
            hipF = hipF,
            kneeF = kneeF,
            ankleF = ankleF,
            heelF = heelF,
            toeF = toeF,
            hipB = hipB,
            kneeB = kneeB,
            ankleB = ankleB,
            heelB = heelB,
            toeB = toeB
        )
    }

    fun createPushUpSkeleton(): SkeletonNodes {
        val ankleF = SkeletonNode(Joint.ANKLE_F)
        val heelF = ankleF.addChild(SkeletonNode(Joint.HEEL_F))
        val toeF = ankleF.addChild(SkeletonNode(Joint.TOE_F))
        val kneeF = ankleF.addChild(SkeletonNode(Joint.KNEE_F))
        val hipF = kneeF.addChild(SkeletonNode(Joint.HIP_F))
        val pelvis = hipF.addChild(SkeletonNode(Joint.PELVIS))
        val chest = pelvis.addChild(SkeletonNode(Joint.CHEST))
        val neck = chest.addChild(SkeletonNode(Joint.NECK_END))
        val head = neck.addChild(SkeletonNode(Joint.HEAD_POS))

        val shoulderA = chest.addChild(SkeletonNode(Joint.SHOULDER_A))
        val elbowA = shoulderA.addChild(SkeletonNode(Joint.ELBOW_A))
        val handA = elbowA.addChild(SkeletonNode(Joint.HAND_A))
        val palmA = handA.addChild(SkeletonNode(Joint.PALM_A))
        val knucklesA = palmA.addChild(SkeletonNode(Joint.KNUCKLES_A))
        val fingertipsA = knucklesA.addChild(SkeletonNode(Joint.FINGERTIPS_A))

        val shoulderP = chest.addChild(SkeletonNode(Joint.SHOULDER_P))
        val elbowP = shoulderP.addChild(SkeletonNode(Joint.ELBOW_P))
        val handP = elbowP.addChild(SkeletonNode(Joint.HAND_P))
        val palmP = handP.addChild(SkeletonNode(Joint.PALM_P))
        val knucklesP = palmP.addChild(SkeletonNode(Joint.KNUCKLES_P))
        val fingertipsP = knucklesP.addChild(SkeletonNode(Joint.FINGERTIPS_P))

        val hipB = pelvis.addChild(SkeletonNode(Joint.HIP_B))
        val kneeB = hipB.addChild(SkeletonNode(Joint.KNEE_B))
        val ankleB = kneeB.addChild(SkeletonNode(Joint.ANKLE_B))
        val heelB = ankleB.addChild(SkeletonNode(Joint.HEEL_B))
        val toeB = ankleB.addChild(SkeletonNode(Joint.TOE_B))

        return SkeletonNodes(
            roots = listOf(ankleF),
            pelvis = pelvis,
            chest = chest,
            neck = neck,
            head = head,
            shoulderA = shoulderA,
            elbowA = elbowA,
            handA = handA,
            palmA = palmA,
            knucklesA = knucklesA,
            fingertipsA = fingertipsA,
            shoulderP = shoulderP,
            elbowP = elbowP,
            handP = handP,
            palmP = palmP,
            knucklesP = knucklesP,
            fingertipsP = fingertipsP,
            hipF = hipF,
            kneeF = kneeF,
            ankleF = ankleF,
            heelF = heelF,
            toeF = toeF,
            hipB = hipB,
            kneeB = kneeB,
            ankleB = ankleB,
            heelB = heelB,
            toeB = toeB
        )
    }
}
