package com.monkfitness.app.language

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * Android's **own** per-app language screen, offered next to the app's picker rather than instead of it
 * (§7).
 *
 * The system screen is not a second mechanism: it edits the same application locale this app's picker
 * edits, so a language chosen there is the language the app shows after it comes back or restarts. The
 * only thing that differs is who draws the list, and the app does not take that choice away from its
 * user.
 */
object AppLanguageSettings {

    /**
     * Whether this platform has the per-app language screen at all (Android 13 / API 33).
     *
     * The SDK level is a parameter so the rule can be stated — and tested — for versions the machine
     * running the tests is not.
     */
    fun isSystemPickerAvailable(sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
        sdkInt >= Build.VERSION_CODES.TIRAMISU

    /**
     * The intent that opens **this app's** language screen in system settings, or `null` on a platform
     * without one. Callers must offer the entry only when this returns an intent.
     */
    fun intentFor(context: Context): Intent? {
        if (!isSystemPickerAvailable()) return null
        val packageName = context.packageName
        return Intent(Settings.ACTION_APP_LOCALE_SETTINGS)
            .setData(Uri.fromParts("package", packageName, null))
            .putExtra(Intent.EXTRA_PACKAGE_NAME, packageName)
    }
}
