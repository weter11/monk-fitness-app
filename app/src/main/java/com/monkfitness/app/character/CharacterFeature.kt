package com.monkfitness.app.character

import com.monkfitness.app.BuildConfig
import com.monkfitness.app.anatomy.MuscleLayer

/**
 * Build-time switch for the Filament character. Off in every checked-in configuration; a developer
 * or device-validation build turns it on with `-Pmonk.filamentCharacter=true`.
 */
object CharacterFeature {

    const val ASSET_PATH = "characters/humanoid_v2.glb"
    const val ANATOMY_PATH = "anatomy/anatomy_v1.json"

    /** The helper-bound muscle assets, one skin per layer; their binds come from [HelperBindSet.ASSET_PATH]. */
    const val MUSCLE_ASSET_PATH = "characters/muscles_sup.glb"
    const val MUSCLE_DEEP_ASSET_PATH = "characters/muscles_deep.glb"
    const val CHARACTER_ID = "humanoid_v2"

    /** The GLB that skins [layer]: each partition is exported under the 255-bone limit on its own. */
    fun muscleAssetPath(layer: MuscleLayer): String = when (layer) {
        MuscleLayer.SUPERFICIAL -> MUSCLE_ASSET_PATH
        MuscleLayer.DEEP -> MUSCLE_DEEP_ASSET_PATH
    }

    /** The label a partition's failures carry in the readout. */
    fun muscleAssetLabel(layer: MuscleLayer): String = layer.name.lowercase(java.util.Locale.US)

    val isEnabled: Boolean get() = BuildConfig.FILAMENT_CHARACTER_ENABLED
}

/** Exactly one live animated character may exist per process; every other view renders 2D. */
object ActiveCharacter {
    private val slot = CharacterSlot()

    fun acquire(token: Any): Boolean = slot.acquire(token)
    fun release(token: Any): Boolean = slot.release(token)
    val isOccupied: Boolean get() = slot.isOccupied

    /** Claims the process-wide slot and builds inside it; see [CharacterSlot.claim]. */
    fun <T> claim(build: () -> T?): CharacterClaim<T> = slot.claim(build)
}

/**
 * Single-owner guard. Ownership is by identity, so a released token can never release a slot that
 * has since been handed to another view.
 */
class CharacterSlot {

    private var owner: Any? = null

    @Synchronized
    fun acquire(token: Any): Boolean {
        val current = owner
        if (current != null && current !== token) return false
        owner = token
        return true
    }

    @Synchronized
    fun release(token: Any): Boolean {
        if (owner !== token) return false
        owner = null
        return true
    }

    @Synchronized
    fun isHeldBy(token: Any): Boolean = owner === token

    @get:Synchronized
    val isOccupied: Boolean get() = owner != null

    /**
     * Claims the slot and runs [build] inside it, releasing again when the build yields nothing.
     * Stages call this after the composition commit, so an outgoing stage has already released.
     */
    fun <T> claim(build: () -> T?): CharacterClaim<T> {
        val token = Any()
        if (!acquire(token)) return CharacterClaim(this, token, null, slotWasFree = false)
        val built = try {
            build()
        } catch (error: Throwable) {
            release(token)
            throw error
        }
        if (built == null) {
            release(token)
            return CharacterClaim(this, token, null, slotWasFree = true)
        }
        return CharacterClaim(this, token, built, slotWasFree = true)
    }
}

/** A claim on the single-owner slot: what was built inside it and the token that holds it. */
class CharacterClaim<T> internal constructor(
    private val slot: CharacterSlot,
    /** The token that owns the slot; no other token can release it. */
    val token: Any,
    /** What [CharacterSlot.claim]'s build returned, or null when nothing was built. */
    val session: T?,
    /** False when another live stage still owned the slot, so nothing was built. */
    val slotWasFree: Boolean
) {

    /** Frees the slot. A claim the slot has already moved past frees nothing. */
    fun release(): Boolean = slot.release(token)
}

