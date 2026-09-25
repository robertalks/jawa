package com.jawa.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/**
 * The two small widgets: "JaWa small" (2×1: icon, temperature, place) and
 * "JaWa mini" (1×1: temperature, place). They show the first place in your list
 * (your current location when that's on) and open the full view when tapped.
 * The temperature sizes itself in the layout, so nothing depends on the size the
 * launcher reports.
 */
abstract class CompactWidgetProvider(
    private val layout: Int,
    private val showIcon: Boolean,
) : AppWidgetProvider() {

    override fun onEnabled(ctx: Context) {
        WeatherWorker.schedulePeriodic(ctx)
        if (!Places.all(ctx).any { Store.hasForecast(ctx, it.key) }) WeatherWorker.refreshNow(ctx)
    }

    override fun onDisabled(ctx: Context) {
        if (WidgetUpdates.count(ctx) == 0) WeatherWorker.cancelAll(ctx)
    }

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        WeatherWorker.schedulePeriodic(ctx)
        ids.forEach { render(ctx, mgr, it) }
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        // After installing a new version, redraw with the new layout.
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            WeatherWorker.schedulePeriodic(ctx)
            updateAll(ctx)
        }
    }

    fun render(ctx: Context, mgr: AppWidgetManager, id: Int) {
        val place = Places.all(ctx).firstOrNull()
        val fc = place?.let { Store.forecast(ctx, it.key) }
        val snap = Store.snapshot(ctx)

        val v = RemoteViews(ctx.packageName, layout)
        v.setOnClickPendingIntent(R.id.widget_root, openDetails(ctx, id, place?.key))
        v.setTextViewText(R.id.place, place?.name ?: ctx.getString(R.string.app_name))
        if (fc == null) {
            v.setTextViewText(R.id.temp, if (snap.updating) "…" else "--°")
            if (showIcon) v.setImageViewResource(R.id.icon, R.drawable.wx_partly_day)
        } else {
            v.setTextViewText(R.id.temp, WidgetRenderer.deg(fc.current.temp))
            if (showIcon) v.setImageViewResource(R.id.icon, WeatherCodes.icon(fc.current.code, fc.current.isDay))
        }
        mgr.updateAppWidget(id, v)
    }

    private fun openDetails(ctx: Context, id: Int, key: String?): PendingIntent {
        val intent = Intent(ctx, DetailActivity::class.java)
            .putExtra(DetailActivity.EXTRA_REFRESH, true)
            .putExtra(DetailActivity.EXTRA_PLACE, key)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        // Request code offset keeps these apart from the big widget's intents.
        return PendingIntent.getActivity(
            ctx, 100_000 + id, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun updateAll(ctx: Context) {
        val mgr = AppWidgetManager.getInstance(ctx)
        mgr.getAppWidgetIds(ComponentName(ctx, javaClass)).forEach { render(ctx, mgr, it) }
    }
}

class SmallWidgetProvider : CompactWidgetProvider(R.layout.widget_small, showIcon = true)

class MiniWidgetProvider : CompactWidgetProvider(R.layout.widget_mini, showIcon = false)

/** Redraws every JaWa widget, of all three kinds. */
object WidgetUpdates {
    fun all(ctx: Context) {
        WeatherWidgetProvider.updateAll(ctx)
        SmallWidgetProvider().updateAll(ctx)
        MiniWidgetProvider().updateAll(ctx)
    }

    /** How many JaWa widgets are on the home screen(s). */
    fun count(ctx: Context): Int {
        val mgr = AppWidgetManager.getInstance(ctx)
        return listOf(WeatherWidgetProvider::class.java, SmallWidgetProvider::class.java, MiniWidgetProvider::class.java)
            .sumOf { mgr.getAppWidgetIds(ComponentName(ctx, it)).size }
    }
}
