package com.monkfitness.app.character

import com.monkfitness.app.BuildConfig

/**
 * Build-time switch for the Filament character. Off in every checked-in configuration; a developer
 * or device-validation build turns it on with `-Pmonk.filamentCharacter=true`.
 */
object CharacterFeature {

    const val ASSET_PATH = "characters/humanoid_v1.glb"
    const val CHARACTER_ID = "humanoid_v1"

    val isEnabled: Boolean get() = BuildConfig.FILAMENT_CHARACTER_ENABLED
}

/** Exactly one live animated character may exist per process; every other view renders 2D. */
object ActiveCharacter {
    private val slot = CharacterSlot()

    fun acquire(token: Any): Boolean = slot.acquire(token)
    fun release(token: Any): Boolean = slot.release(token)
    val isOccupied: Boolean get() = slot.isOccupied
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
}
