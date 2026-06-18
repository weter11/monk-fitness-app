package com.monkfitness.app.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.core.app.NotificationCompat
import com.monkfitness.app.R
import com.monkfitness.app.data.local.SettingsManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.util.Locale

class NotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val settingsManager = SettingsManager(context)
        val language = runBlocking { settingsManager.languageFlow.first() }

        val locale = Locale(language)
        Locale.setDefault(locale)
        val config = context.resources.configuration
        config.setLocale(locale)
        val localizedContext = context.createConfigurationContext(config)

        val notificationManager = localizedContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "daily_reminder"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                localizedContext.getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            )
            notificationManager.createNotificationChannel(channel)
        }

        val type = intent.getStringExtra(NotificationScheduler.EXTRA_NOTIFICATION_TYPE) ?: NotificationScheduler.TYPE_WORKOUT

        val title = if (type == NotificationScheduler.TYPE_NUTRITION) {
            localizedContext.getString(R.string.notification_nutrition_title)
        } else {
            localizedContext.getString(R.string.notification_title)
        }

        val text = if (type == NotificationScheduler.TYPE_NUTRITION) {
            localizedContext.getString(R.string.notification_nutrition_text)
        } else {
            localizedContext.getString(R.string.notification_text)
        }

        val contentIntent = Intent(context, com.monkfitness.app.MainActivity::class.java).apply {
            putExtra(NotificationScheduler.EXTRA_NOTIFICATION_TYPE, type)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }

        val requestCode = if (type == NotificationScheduler.TYPE_NUTRITION) NotificationScheduler.RC_NUTRITION else NotificationScheduler.RC_WORKOUT
        val notificationId = if (type == NotificationScheduler.TYPE_NUTRITION) NotificationScheduler.ID_NUTRITION else NotificationScheduler.ID_WORKOUT

        val pendingIntent = android.app.PendingIntent.getActivity(
            context,
            requestCode,
            contentIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(localizedContext, channelId)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(notificationId, notification)
    }
}
