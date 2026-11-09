package com.jawa.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Fires at most one notification per event type. "Rain incoming" fires when the
 * next 1–2 hours have rainChance >= 60 for the current location; it won't re-fire
 * until the rain ends and starts again. "Air quality" fires when the current AQI
 * level reaches Poor (3) or worse; it won't re-fire until the air quality improves
 * and degrades again.
 *
 * Both are gated by a user setting and the POST_NOTIFICATIONS permission (Android 13+).
 * Called from WeatherWorker after every successful fetch.
 */
object Notifier {
    private const val CHANNEL_ID = "jawa_alerts"
    private const val ID_RAIN = 1
    private const val ID_AIR = 2
    private const val PREF_RAIN_ACTIVE = "notif_rain_active"
    private const val PREF_AIR_LEVEL_NOTIFIED = "notif_air_level"

    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val mgr = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Weather alerts", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Rain incoming and air quality alerts"
            }
        )
    }

    fun checkRain(ctx: Context, forecast: Forecast?) {
        if (!Store.notifyRain(ctx)) return
        if (forecast == null) return
        if (!canPost(ctx)) return

        val soonRainy = forecast.hours.take(2).any { it.rainChance >= 60 }
        val wasActive = Store.prefs(ctx).getBoolean(PREF_RAIN_ACTIVE, false)

        if (soonRainy && !wasActive) {
            Store.prefs(ctx).edit().putBoolean(PREF_RAIN_ACTIVE, true).apply()
            val chance = forecast.hours.take(2).maxOf { it.rainChance }
            notify(ctx, ID_RAIN, "🌧 Rain on the way", "There's a $chance% chance of rain in the next hour or two.")
        } else if (!soonRainy && wasActive) {
            Store.prefs(ctx).edit().putBoolean(PREF_RAIN_ACTIVE, false).apply()
            cancel(ctx, ID_RAIN)
        }
    }

    fun checkAir(ctx: Context, air: AirQuality?) {
        if (!Store.notifyAir(ctx)) return
        if (air == null) return
        if (!canPost(ctx)) return

        val level = air.level
        val lastNotified = Store.prefs(ctx).getInt(PREF_AIR_LEVEL_NOTIFIED, -1)

        if (level >= 3 && lastNotified < 3) {
            Store.prefs(ctx).edit().putInt(PREF_AIR_LEVEL_NOTIFIED, level).apply()
            val name = AirQuality.NAMES[level]
            notify(ctx, ID_AIR, "💨 Air quality: $name", "${AirQuality.ADVICE[level].replaceFirstChar { it.uppercase() }}.")
        } else if (level < 3 && lastNotified >= 3) {
            Store.prefs(ctx).edit().putInt(PREF_AIR_LEVEL_NOTIFIED, level).apply()
            cancel(ctx, ID_AIR)
        }
    }

    private fun notify(ctx: Context, id: Int, title: String, body: String) {
        val intent = Intent(ctx, DetailActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(ctx, id, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(id, n)
        } catch (_: SecurityException) {
            // Permission revoked between the check and the post — safe to ignore.
        }
    }

    private fun cancel(ctx: Context, id: Int) =
        NotificationManagerCompat.from(ctx).cancel(id)

    private fun canPost(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33) {
            if (ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) return false
        }
        return NotificationManagerCompat.from(ctx).areNotificationsEnabled()
    }
}
