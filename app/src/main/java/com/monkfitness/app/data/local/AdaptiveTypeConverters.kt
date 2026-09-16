package com.monkfitness.app.data.local

import androidx.room.TypeConverter
import com.monkfitness.app.domain.adaptive.AdaptiveAction
import com.monkfitness.app.domain.adaptive.AdaptiveReasonCode
import com.monkfitness.app.domain.adaptive.AdaptiveState

/**
 * The stored representation of the adaptive vocabulary: enum names, nothing else.
 *
 * Names are the stable form because the audit trail outlives the code that wrote it — an ordinal
 * would silently re-point at a different constant the moment an enum member is inserted, while a
 * name either resolves or fails loudly on read. Nothing here maps a name to different text: the
 * domain enums are the vocabulary, and these converters only spell it into a column.
 */
class AdaptiveTypeConverters {

    @TypeConverter
    fun adaptiveStateToName(state: AdaptiveState): String = state.name

    @TypeConverter
    fun adaptiveStateFromName(name: String): AdaptiveState = AdaptiveState.valueOf(name)

    @TypeConverter
    fun reasonCodeToName(code: AdaptiveReasonCode): String = code.name

    @TypeConverter
    fun reasonCodeFromName(name: String): AdaptiveReasonCode = AdaptiveReasonCode.valueOf(name)

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
