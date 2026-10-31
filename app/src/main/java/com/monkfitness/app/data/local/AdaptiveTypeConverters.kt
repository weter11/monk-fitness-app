package com.monkfitness.app.data.local

import androidx.room.TypeConverter
import com.monkfitness.app.domain.adaptive.AdaptiveState
import com.monkfitness.app.domain.adaptive.decision.AdaptiveAction

/**
 * The stored representation of the adaptive vocabulary: enum names, nothing else.
 *
 * Names, not ordinals: the audit trail outlives the code that wrote it, and a name either resolves or
 * fails loudly on read, while inserting an enum member would silently re-point an ordinal.
 */
class AdaptiveTypeConverters {

    @TypeConverter
    fun adaptiveStateToName(state: AdaptiveState): String = state.name

    @TypeConverter
    fun adaptiveStateFromName(name: String): AdaptiveState = AdaptiveState.valueOf(name)

    @TypeConverter
    fun actionsToNames(actions: List<AdaptiveAction>): String = actions.joinToString(SEPARATOR) { it.name }

    @TypeConverter
    fun actionsFromNames(value: String): List<AdaptiveAction> =
        if (value.isEmpty()) emptyList() else value.split(SEPARATOR).map { AdaptiveAction.valueOf(it) }

    private companion object {
        /** The action list is an ordered list, so the separator may not appear inside a name. */
        const val SEPARATOR = ","
    }
}
