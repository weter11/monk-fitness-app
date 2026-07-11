package com.monkfitness.app.animation

object GroundArmSupport {
    data class Settings(
        val handWidthMultiplier: Float,

        val poleFront: Vector3,
        val poleBack: Vector3,

        val handRotation: Float,

        val handDirectionFront: Vector3,
        val handDirectionBack: Vector3,

        val palmLength: Float = 6f,
        val fingerLength: Float = 10f
    )

    private class Scratch {
        val shoulderOffsetA = Vector3()
        val shoulderOffsetP = Vector3()
        val axisZ = Vector3(0f, 0f, 1f)
        val shoulderAW = Vector3()
        val shoulderPW = Vector3()
        val targetHandA = Vector3()
        val targetHandP = Vector3()
        val elbowDiffA = Vector3()
        val handDiffA = Vector3()
        val elbowDiffP = Vector3()
        val handDiffP = Vector3()
    }

    private val threadLocalScratch = object : ThreadLocal<Scratch>() {
        override fun initialValue(): Scratch = Scratch()
    }

    fun solve(
        definition: SkeletonDefinition,

        chest: SkeletonNode,

        shoulderA: SkeletonNode,
        elbowA: SkeletonNode,
        handA: SkeletonNode,

        shoulderP: SkeletonNode,
        elbowP: SkeletonNode,
        handP: SkeletonNode,

        palmA: SkeletonNode?,
        knucklesA: SkeletonNode?,
        fingertipsA: SkeletonNode?,

        palmP: SkeletonNode?,
        knucklesP: SkeletonNode?,
        fingertipsP: SkeletonNode?,

        targetX: Float,

        settings: Settings,

        ikFront: SkeletonMath.IKResult,
        ikBack: SkeletonMath.IKResult
    ) {
        val s = threadLocalScratch.get()!!

        s.shoulderOffsetA.set(0f, 0f, -definition.shoulderWidth)
        s.shoulderOffsetP.set(0f, 0f, definition.shoulderWidth)

        val chestW = chest.worldPosition
        val angle = chest.worldRotation.angle

        SkeletonMath.rotAround(s.shoulderOffsetA, s.axisZ, angle, s.shoulderAW)
        s.shoulderAW.add(chestW)

        SkeletonMath.rotAround(s.shoulderOffsetP, s.axisZ, angle, s.shoulderPW)
        s.shoulderPW.add(chestW)

        s.targetHandA.set(targetX, 0f, -definition.shoulderWidth * settings.handWidthMultiplier)
        s.targetHandP.set(targetX, 0f, definition.shoulderWidth * settings.handWidthMultiplier)

        SkeletonMath.solveIK(
            s.shoulderAW,
            s.targetHandA,
            definition.upperArmLength,
            definition.forearmLength,
            settings.poleFront,
            definition.armIKConstraint,
            ikFront
        )

        SkeletonMath.solveIK(
            s.shoulderPW,
            s.targetHandP,
            definition.upperArmLength,
            definition.forearmLength,
            settings.poleBack,
            definition.armIKConstraint,
            ikBack
        )

        shoulderA.localPosition.set(0f, 0f, -definition.shoulderWidth)
        s.elbowDiffA.set(ikFront.joint.x - s.shoulderAW.x, ikFront.joint.y - s.shoulderAW.y, ikFront.joint.z - s.shoulderAW.z)
        SkeletonMath.rotAround(s.elbowDiffA, s.axisZ, settings.handRotation, elbowA.localPosition)

        s.handDiffA.set(ikFront.end.x - ikFront.joint.x, ikFront.end.y - ikFront.joint.y, ikFront.end.z - ikFront.joint.z)
        SkeletonMath.rotAround(s.handDiffA, s.axisZ, settings.handRotation, handA.localPosition)

        shoulderP.localPosition.set(0f, 0f, definition.shoulderWidth)
        s.elbowDiffP.set(ikBack.joint.x - s.shoulderPW.x, ikBack.joint.y - s.shoulderPW.y, ikBack.joint.z - s.shoulderPW.z)
        SkeletonMath.rotAround(s.elbowDiffP, s.axisZ, settings.handRotation, elbowP.localPosition)

        s.handDiffP.set(ikBack.end.x - ikBack.joint.x, ikBack.end.y - ikBack.joint.y, ikBack.end.z - ikBack.joint.z)
        SkeletonMath.rotAround(s.handDiffP, s.axisZ, settings.handRotation, handP.localPosition)

        handA.localRotation.set(s.axisZ, settings.handRotation)
        handP.localRotation.set(s.axisZ, settings.handRotation)

        if (palmA != null) {
            palmA.localPosition.set(
                settings.handDirectionFront.x * settings.palmLength,
                settings.handDirectionFront.y * settings.palmLength,
                settings.handDirectionFront.z * settings.palmLength
            )
        }
        if (knucklesA != null) {
            knucklesA.localPosition.set(
                settings.handDirectionFront.x * settings.palmLength,
                settings.handDirectionFront.y * settings.palmLength,
                settings.handDirectionFront.z * settings.palmLength
            )
        }
        if (fingertipsA != null) {
            fingertipsA.localPosition.set(
                settings.handDirectionFront.x * settings.fingerLength,
                settings.handDirectionFront.y * settings.fingerLength,
                settings.handDirectionFront.z * settings.fingerLength
            )
        }

        if (palmP != null) {
            palmP.localPosition.set(
                settings.handDirectionBack.x * settings.palmLength,
                settings.handDirectionBack.y * settings.palmLength,
                settings.handDirectionBack.z * settings.palmLength
            )
        }
        if (knucklesP != null) {
            knucklesP.localPosition.set(
                settings.handDirectionBack.x * settings.palmLength,
                settings.handDirectionBack.y * settings.palmLength,
                settings.handDirectionBack.z * settings.palmLength
            )
        }
        if (fingertipsP != null) {
            fingertipsP.localPosition.set(
                settings.handDirectionBack.x * settings.fingerLength,
                settings.handDirectionBack.y * settings.fingerLength,
                settings.handDirectionBack.z * settings.fingerLength
            )
        }
    }
}
