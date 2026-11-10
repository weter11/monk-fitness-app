package com.monkfitness.app.ui.programs

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * A load or a kilogram volume as a screen prints it: at most two decimals, no trailing zeros, with the
 * locale's own decimal separator.
 *
 * `60.0` prints as `60` and `62.5` as `62.5`. A fixed `%.2f` would show a precision the user never
 * entered ("60.00 kg") beside a field that accepts `60`, and the screen and the field would disagree
 * about what was recorded.
 */
fun formatKg(value: Double, locale: Locale = Locale.getDefault()): String =
    DecimalFormat("0.##", DecimalFormatSymbols.getInstance(locale)).format(value)
