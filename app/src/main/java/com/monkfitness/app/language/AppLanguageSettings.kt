package com.monkfitness.app.language

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * Android's **own** per-app language screen, offered next to the app's picker rather than instead of it.
 *
 * It edits the same application locale the picker edits, so a language chosen there is the language the
 * app shows after it comes back or restarts.
 */
object AppLanguageSettings {

    /**
     * Whether this platform has the per-app language screen at all (Android 13 / API 33). The SDK level is
     * a parameter so the rule can be stated — and tested — for versions the test machine is not.
     */
    fun isSystemPickerAvailable(sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
        sdkInt >= Build.VERSION_CODES.TIRAMISU

    /**
     * The intent that opens **this app's** language screen in system settings, or `null` on a platform
     * without one; callers must offer the entry only when this returns an intent.
     */
    fun intentFor(context: Context): Intent? {
        if (!isSystemPickerAvailable()) return null
        val packageName = context.packageName
        return Intent(Settings.ACTION_APP_LOCALE_SETTINGS)
            .setData(Uri.fromParts("package", packageName, null))
            .putExtra(Intent.EXTRA_PACKAGE_NAME, packageName)
    }
}
