package com.monkfitness.app.data.local

import androidx.room.TypeConverter
import com.monkfitness.app.domain.adaptive.AdaptiveState
import com.monkfitness.app.domain.adaptive.decision.AdaptiveAction

/**
 * The stored representation of the adaptive vocabulary: enum names, nothing else.
 *
 * Names are the stable form because the audit trail outlives the code that wrote it — an ordinal
 * would silently re-point at a different constant the moment an enum member is inserted, while a
 * name either resolves or fails loudly on read. Nothing here maps a name to different text: the
 * domain enums are the vocabulary, and these converters only spell it into a column.
 *
 * §30 step 15 removed the Stage-1 pair (`AdaptiveReasonCode`): the token it spelled belonged to the
 * retired decision rows, which `MIGRATION_11_12` drops. [AdaptiveState] and [AdaptiveAction] stay
 * because the target engine and the target decision rows use them (§30 step 11).
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
