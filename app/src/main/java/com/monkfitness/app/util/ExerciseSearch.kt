package com.monkfitness.app.util

import android.content.Context
import android.content.res.Configuration
import androidx.annotation.StringRes
import com.monkfitness.app.R
import com.monkfitness.app.data.model.Exercise
import java.util.Locale

fun normalize(text: String): String {
    return text
        .lowercase()
        .replace("ё", "е")
        .trim()
}

fun matchesQuery(exercise: Exercise, query: String): Boolean {
    val q = normalize(query)
    if (q.isEmpty()) return true

    return normalize(exercise.nameRu).contains(q) ||
        normalize(exercise.nameEn).contains(q) ||
        normalize(exercise.descriptionRu).contains(q) ||
        normalize(exercise.descriptionEn).contains(q)
}

fun Exercise.withLocalizedSearchText(context: Context): Exercise {
    val descriptionId = if (descriptionRes != 0) descriptionRes else R.string.description

    return copy(
        nameRu = context.localizedString(nameRes, "ru"),
        nameEn = context.localizedString(nameRes, "en"),
        descriptionRu = context.localizedString(descriptionId, "ru"),
        descriptionEn = context.localizedString(descriptionId, "en")
    )
}

private fun Context.localizedString(@StringRes resId: Int, languageTag: String): String {
    val configuration = Configuration(resources.configuration)
    configuration.setLocale(Locale.forLanguageTag(languageTag))
    return createConfigurationContext(configuration).resources.getString(resId)
}
