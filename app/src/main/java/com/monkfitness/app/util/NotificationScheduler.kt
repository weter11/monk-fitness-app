package com.monkfitness.app.util

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar

enum class NotificationType {
    WORKOUT,
    NUTRITION
}

object NotificationScheduler {
    const val EXTRA_NOTIFICATION_TYPE = "notification_type"

    const val TYPE_WORKOUT = "workout"
    const val TYPE_NUTRITION = "nutrition"

    const val RC_WORKOUT = 100
    const val RC_NUTRITION = 101

    const val ID_WORKOUT = 1
    const val ID_NUTRITION = 2

    fun scheduleDailyReminder(context: Context, hour: Int, minute: Int, type: NotificationType = NotificationType.WORKOUT) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val typeStr = when (type) {
            NotificationType.NUTRITION -> TYPE_NUTRITION
            NotificationType.WORKOUT -> TYPE_WORKOUT
        }
        val intent = Intent(context, NotificationReceiver::class.java).apply {
            putExtra(EXTRA_NOTIFICATION_TYPE, typeStr)
        }
        val requestCode = if (type == NotificationType.NUTRITION) RC_NUTRITION else RC_WORKOUT
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            if (before(Calendar.getInstance())) {
                add(Calendar.DATE, 1)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    calendar.timeInMillis,
                    pendingIntent
                )
            } else {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    calendar.timeInMillis,
                    pendingIntent
                )
            }
        } else {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                calendar.timeInMillis,
                pendingIntent
            )
        }
    }
}
