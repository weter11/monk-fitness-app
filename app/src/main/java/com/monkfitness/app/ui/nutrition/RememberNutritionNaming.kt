package com.monkfitness.app.ui.nutrition

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import com.monkfitness.app.data.model.NutritionNaming
import java.util.Locale

/**
 * The market and language in force on this screen: the user's choice first, then the phone's region,
 * then the interface's own language.
 *
 * Resolved while composing rather than stored, so the rows always agree with the language actually
 * being displayed — a stale flow would keep offering the previous interface's fallback.
 */
@Composable
fun rememberNutritionNaming(storedMarket: String?, storedLanguage: String?): NutritionNaming {
    val configuration = LocalConfiguration.current
    return remember(storedMarket, storedLanguage, configuration) {
        NutritionNaming.resolve(
            storedMarket = storedMarket,
            storedLanguage = storedLanguage,
            region = Locale.getDefault().country,
            interfaceLanguage = configuration.locales[0].language
        )
    }
}
