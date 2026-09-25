package com.jawa.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle

class WeatherWidgetProvider : AppWidgetProvider() {

    override fun onEnabled(ctx: Context) {
        WeatherWorker.schedulePeriodic(ctx)
        WeatherWorker.refreshNow(ctx)
    }

    override fun onDisabled(ctx: Context) {
        if (WidgetUpdates.count(ctx) == 0) WeatherWorker.cancelAll(ctx) // no JaWa widgets left
    }

    override fun onDeleted(ctx: Context, ids: IntArray) {
        ids.forEach { Store.deleteWidget(ctx, it) }
    }

    // Only draws from the cache: starting one-off work here can make some
    // launchers call onUpdate again in a loop.
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        WeatherWorker.schedulePeriodic(ctx)
        ids.forEach { render(ctx, mgr, it) }
    }

    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, options: Bundle) {
        render(ctx, mgr, id)
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        when (intent.action) {
            ACTION_PREV, ACTION_NEXT -> {
                val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
                if (id != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    step(ctx, id, if (intent.action == ACTION_NEXT) 1 else -1)
                }
            }
            // After installing a new version, redraw so the widget uses the new layout.
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                WeatherWorker.schedulePeriodic(ctx)
                WeatherWorker.refreshNow(ctx) // the cache format may have changed
                WidgetUpdates.all(ctx)
            }
        }
    }

    /** Moves one widget to the previous/next place (wrapping round). */
    private fun step(ctx: Context, id: Int, delta: Int) {
        val places = Places.all(ctx)
        if (places.isEmpty()) return
        val cur = places.indexOfFirst { it.key == selectedKey(ctx, id, places) }.coerceAtLeast(0)
        val next = places[(cur + delta + places.size) % places.size]
        Store.setWidgetPlace(ctx, id, next.key)
        render(ctx, AppWidgetManager.getInstance(ctx), id)
        // Data older than 30 minutes (or missing)? Fetch fresh for all places.
        val age = System.currentTimeMillis() - Store.snapshot(ctx).updatedMillis
        if (age > 30 * 60 * 1000L || !Store.hasForecast(ctx, next.key)) WeatherWorker.refreshNow(ctx)
    }

    companion object {
        private const val ACTION_PREV = "com.jawa.app.PREV"
        private const val ACTION_NEXT = "com.jawa.app.NEXT"

        fun updateAll(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, WeatherWidgetProvider::class.java))
            ids.forEach { render(ctx, mgr, it) }
        }

        /** The place a widget shows: its own choice if still valid, else the first place. */
        private fun selectedKey(ctx: Context, id: Int, places: List<PlaceRef>): String? =
            Store.widgetPlace(ctx, id)?.takeIf { k -> places.any { it.key == k } } ?: places.firstOrNull()?.key

        private fun render(ctx: Context, mgr: AppWidgetManager, id: Int) {
            val places = Places.all(ctx)
            val key = selectedKey(ctx, id, places)
            val index = places.indexOfFirst { it.key == key }
            val target = WidgetRenderer.Target(
                place = places.getOrNull(index),
                index = index.coerceAtLeast(0),
                count = places.size,
                onTap = openDetailsIntent(ctx, id, key),
                onPrev = stepIntent(ctx, id, ACTION_PREV),
                onNext = stepIntent(ctx, id, ACTION_NEXT),
            )
            mgr.updateAppWidget(id, WidgetRenderer.build(ctx, mgr.getAppWidgetOptions(id), target))
        }

        /** Tapping the widget opens the full view on the same place, and refreshes. */
        private fun openDetailsIntent(ctx: Context, id: Int, key: String?): PendingIntent {
            val intent = Intent(ctx, DetailActivity::class.java)
                .putExtra(DetailActivity.EXTRA_REFRESH, true)
                .putExtra(DetailActivity.EXTRA_PLACE, key)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            return PendingIntent.getActivity(
                ctx, id, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        private fun stepIntent(ctx: Context, id: Int, action: String): PendingIntent {
            val intent = Intent(ctx, WeatherWidgetProvider::class.java)
                .setAction(action)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            return PendingIntent.getBroadcast(
                ctx, id, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
}
