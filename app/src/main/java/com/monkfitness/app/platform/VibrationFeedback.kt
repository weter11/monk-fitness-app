package com.monkfitness.app.platform

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * A short buzz — the app's vibration setting, applied at the one boundary that can apply it. The SDK
 * split (the manager on API 31+, the deprecated vibrator below it) is the platform's own business,
 * which is why the suppression lives here and not in the UI.
 */
object VibrationFeedback {

    /**
     * Buzzes once, for [durationMillis]. Does nothing when the platform lends no vibrator, rather than
     * throwing: a device without one is a device, not a failure, and a workout must not break because a
     * phone cannot buzz.
     */
    @Suppress("DEPRECATION")
    fun buzz(context: Context, durationMillis: Long = 200L) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            context.getSystemService(Vibrator::class.java)
        } ?: return
        vibrator.vibrate(VibrationEffect.createOneShot(durationMillis, VibrationEffect.DEFAULT_AMPLITUDE))
    }
}
