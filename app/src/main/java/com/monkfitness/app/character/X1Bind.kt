package com.monkfitness.app.character

import com.monkfitness.app.animation.Joint
import com.monkfitness.app.animation.SkeletonDefinition
import com.monkfitness.app.animation.Vector3

/**
 * The X1 bind stance: a straight-limbed A-pose computed from [SkeletonDefinition] alone, never read
 * from a published frame. Rig assets are generated against these positions and aligned by them.
 */
object X1Bind {

    /** Trochanter height the pelvis registration uses; the scale is `restPelvisHeight / this`. */
    const val pelvisHeightMetres: Float = 0.95f

    /** Bind-landmark tolerance in millimetres, converted to engine units by [engineUnits]. */
    const val TAU_BIND_MM: Float = 0.5f

    /** Posed-residual tolerance in millimetres, converted to engine units by [engineUnits]. */
    const val TAU_RESIDUAL_MM: Float = 5.0f

    /** Millimetres → engine units at the default definition's scale. */
    fun engineUnits(millimetres: Float, unitsPerMetre: Float = unitsPerMetre()): Float =
        millimetres / 1000f * unitsPerMetre

    fun unitsPerMetre(definition: SkeletonDefinition = SkeletonDefinition.DEFAULT_ADULT): Float =
        MonkJointResolver.restPelvisHeight(definition) / pelvisHeightMetres

    /** [TAU_BIND_MM] at the default definition's scale: 0.12368 engine units. */
    val TAU_BIND: Float = engineUnits(TAU_BIND_MM)

    /** [TAU_RESIDUAL_MM] at the default definition's scale: 1.23684 engine units. */
    val TAU_RESIDUAL: Float = engineUnits(TAU_RESIDUAL_MM)

    /** `B⁻¹` as a matrix: the asset-space axes, computed once instead of per converted joint. */
    private val ENGINE_TO_ASSET: FloatArray = BoneMap.basis.conjugate().toMatrix3()

    /**
     * The canonical A-pose of every engine joint: `+Y` up, `+X` forward, `+Z` lateral, side `A` on
     * `−Z`. Limbs are straightened, so a side's shoulder, elbow and hand share one lateral offset.
     */
    fun bindJoints(definition: SkeletonDefinition = SkeletonDefinition.DEFAULT_ADULT): Map<Joint, Vector3> {
        val pelvisY = MonkJointResolver.restPelvisHeight(definition)
        val chestY = pelvisY + definition.torsoLength
        val neckY = chestY + definition.neckLength
        val headY = neckY + definition.neckLength
        val out = LinkedHashMap<Joint, Vector3>()

        fun put(joint: Joint, x: Float, y: Float, z: Float) {
            out[joint] = Vector3(x, y, z)
        }

        put(Joint.PELVIS, 0f, pelvisY, 0f)
        put(Joint.LUMBAR, 0f, pelvisY, 0f)
        put(Joint.CHEST, 0f, chestY, 0f)
        put(Joint.NECK_END, 0f, neckY, 0f)
        put(Joint.HEAD_POS, 0f, headY, 0f)

        for (arm in ARMS) {
            val lateral = if (arm.aSide) -1f else 1f
            val elbowY = chestY - definition.upperArmLength
            val handY = elbowY - definition.forearmLength
            val palmMidY = handY - definition.hand.palmLength * 0.5f
            val knucklesY = handY - definition.hand.palmLength
            val fingertipsY = knucklesY - definition.hand.fingerLength
            put(arm.clavicle, 0f, chestY, 0f)
            put(arm.scapula, 0f, chestY, 0f)
            put(arm.shoulder, 0f, chestY, lateral * definition.shoulderWidth)
            put(arm.elbow, 0f, elbowY, lateral * definition.shoulderWidth)
            put(arm.hand, 0f, handY, lateral * definition.shoulderWidth)
            put(arm.wrist, 0f, handY, lateral * definition.shoulderWidth)
            put(arm.palm, 0f, palmMidY, lateral * definition.shoulderWidth)
            put(arm.knuckles, 0f, knucklesY, lateral * definition.shoulderWidth)
            put(arm.fingertips, 0f, fingertipsY, lateral * definition.shoulderWidth)
        }

        for (leg in LEGS) {
            val lateral = if (leg.frontSide) -1f else 1f
            val offset = lateral * definition.hipWidth
            val kneeY = pelvisY - definition.thighLength
            val ankleY = kneeY - definition.shinLength
            // Heel and toe are both level with the ankle; only the lateral axis mirrors between
            // sides, so both heels sit the same distance posterior to it.
            put(leg.hip, 0f, pelvisY, offset)
            put(leg.knee, 0f, kneeY, offset)
            put(leg.ankle, 0f, ankleY, offset)
            put(leg.heel, -definition.foot.footLength * definition.foot.heelRatio, ankleY, offset)
            put(leg.toe, definition.foot.footLength * definition.foot.toeRatio, ankleY, offset)
        }
        return out
    }

    /**
     * Engine pose → asset space: `B⁻¹ · p / unitsPerMetre` with `B = Ry(+90°)`, the asset's `+Z`
     * forward / `+X` left becoming engine `+X` forward / `−Z` left.
     */
    fun toAsset(engine: Vector3, unitsPerMetre: Float = unitsPerMetre()): Vector3 {
        val out = FloatArray(3)
        toAssetInto(engine, unitsPerMetre, out)
        return Vector3(out[0], out[1], out[2])
    }

    /** [toAsset] into a reusable `[x, y, z]` buffer: the framing sweep converts thousands of joints. */
    fun toAssetInto(engine: Vector3, unitsPerMetre: Float, out: FloatArray) {
        val m = ENGINE_TO_ASSET
        out[0] = (m[0] * engine.x + m[3] * engine.y + m[6] * engine.z) / unitsPerMetre
        out[1] = (m[1] * engine.x + m[4] * engine.y + m[7] * engine.z) / unitsPerMetre
        out[2] = (m[2] * engine.x + m[5] * engine.y + m[8] * engine.z) / unitsPerMetre
    }

    /** A direction on the same axes as [toAsset], unscaled: camera bases are unit vectors already. */
    fun toAssetDirection(engine: Vector3): Vector3 {
        val m = ENGINE_TO_ASSET
        return Vector3(
            m[0] * engine.x + m[3] * engine.y + m[6] * engine.z,
            m[1] * engine.x + m[4] * engine.y + m[7] * engine.z,
            m[2] * engine.x + m[5] * engine.y + m[8] * engine.z
        )
    }

    /** Asset metres → engine units: `unitsPerMetre · B · p_asset`, the inverse of [toAsset]. */
    fun toEngine(asset: Vector3, unitsPerMetre: Float = unitsPerMetre()): Vector3 {
        val rotated = rotate(BoneMap.basis, asset)
        return Vector3(rotated.x * unitsPerMetre, rotated.y * unitsPerMetre, rotated.z * unitsPerMetre)
    }

    private fun rotate(q: Quat, v: Vector3): Vector3 {
        val m = q.toMatrix3()
        return Vector3(
            m[0] * v.x + m[3] * v.y + m[6] * v.z,
            m[1] * v.x + m[4] * v.y + m[7] * v.z,
            m[2] * v.x + m[5] * v.y + m[8] * v.z
        )
    }

    private class ArmJoints(
        val aSide: Boolean,
        val clavicle: Joint,
        val scapula: Joint,
        val shoulder: Joint,
        val elbow: Joint,
        val hand: Joint,
        val wrist: Joint,
        val palm: Joint,
        val knuckles: Joint,
        val fingertips: Joint
    )

    private class LegJoints(
        val frontSide: Boolean,
        val hip: Joint,
        val knee: Joint,
        val ankle: Joint,
        val heel: Joint,
        val toe: Joint
    )

    private val ARMS = listOf(
        ArmJoints(true, Joint.CLAVICLE_A, Joint.SCAPULA_A, Joint.SHOULDER_A, Joint.ELBOW_A, Joint.HAND_A, Joint.WRIST_A, Joint.PALM_A, Joint.KNUCKLES_A, Joint.FINGERTIPS_A),
        ArmJoints(false, Joint.CLAVICLE_P, Joint.SCAPULA_P, Joint.SHOULDER_P, Joint.ELBOW_P, Joint.HAND_P, Joint.WRIST_P, Joint.PALM_P, Joint.KNUCKLES_P, Joint.FINGERTIPS_P)
    )

    private val LEGS = listOf(
        LegJoints(true, Joint.HIP_F, Joint.KNEE_F, Joint.ANKLE_F, Joint.HEEL_F, Joint.TOE_F),
        LegJoints(false, Joint.HIP_B, Joint.KNEE_B, Joint.ANKLE_B, Joint.HEEL_B, Joint.TOE_B)
    )
}
