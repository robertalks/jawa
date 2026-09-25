package com.jawa.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock

/**
 * Drives the 10-minute refresh interval, which Android's periodic scheduler doesn't
 * allow (its minimum is 15). Uses an inexact alarm (no special permission needed). When
 * the phone is idle with the screen off, Android may stretch it to about 15 minutes.
 */
class RefreshAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        WeatherWorker.refreshAuto(ctx) // the worker sets the next alarm when it finishes
    }

    companion object {
        private fun pending(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
            ctx, 0, Intent(ctx, RefreshAlarmReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        /** (Re)sets the single alarm; setting it again replaces the previous one. */
        fun arm(ctx: Context, minutes: Int) {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + minutes * 60_000L,
                pending(ctx),
            )
        }

        fun cancel(ctx: Context) {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(pending(ctx))
        }
    }
}
