package com.monkfitness.app.platform

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * A short buzz — the app's vibration setting, applied at the one boundary that can apply it.
 *
 * It lives beside the other platform objects (`ProgramShareSheet`, `ProgramDocumentImport`) and not in a
 * screen, for the reason §11 and §12 keep the Android boundary out of the layers that decide things: a
 * screen renders what it is given, and "how a vibration is realised on this device" is a device question.
 * The SDK split is the platform's own business too — the modern manager on API 31+, the deprecated
 * vibrator below it — which is why the suppression below is here and not in the UI.
 */
object VibrationFeedback {

    /**
     * Buzzes once, for [durationMillis].
     *
     * It does nothing when the platform lends no vibrator, rather than throwing: a device without one is a
     * device, not a failure, and a workout must not break because a phone cannot buzz.
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
