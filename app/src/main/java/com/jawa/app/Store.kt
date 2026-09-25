package com.jawa.app

import android.content.Context
import android.content.SharedPreferences
import java.io.File

/**
 * Small on-device cache: the last forecast for each place, the current-location
 * name, update state, and which place each widget is showing.
 */
object Store {
    private const val NAME = "weather"
    private const val KEY_PLACE = "place"
    private const val KEY_LAT = "lat"
    private const val KEY_LON = "lon"
    private const val KEY_UPDATED = "updated"
    private const val KEY_ERROR = "error"
    private const val KEY_UPDATING = "updating"
    private const val KEY_REFRESH_MIN = "refresh_minutes"
    private const val PREFIX_WIDGET = "widget_place_"

    data class Snapshot(
        val updatedMillis: Long,
        val error: String?,
        val updating: Boolean,
    )

    fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun snapshot(ctx: Context): Snapshot {
        val p = prefs(ctx)
        return Snapshot(
            updatedMillis = p.getLong(KEY_UPDATED, 0L),
            error = p.getString(KEY_ERROR, null),
            updating = p.getBoolean(KEY_UPDATING, false),
        )
    }

    // --- forecasts, one file per place key ---
    //
    // Kept out of SharedPreferences on purpose: that file is loaded whole into memory
    // and rewritten whole on every small change (like the "updating" flag), which would
    // mean rewriting ~30 KB per place each time. Separate files are only written when a
    // new forecast arrives.

    private fun forecastDir(ctx: Context): File = File(ctx.filesDir, "forecasts").apply { mkdirs() }

    private fun forecastFile(ctx: Context, key: String): File =
        File(forecastDir(ctx), key.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".json")

    fun hasForecast(ctx: Context, key: String): Boolean = forecastFile(ctx, key).exists()

    /** Parsed forecasts, reused until the file changes (a widget update reads them several times). */
    private val cache = HashMap<String, Pair<Long, Forecast>>()

    fun forecast(ctx: Context, key: String): Forecast? {
        val f = forecastFile(ctx, key)
        if (!f.exists()) return null
        val stamp = f.lastModified()
        synchronized(cache) { cache[key]?.let { if (it.first == stamp) return it.second } }
        val parsed = runCatching { Forecast.parse(f.readText()) }.getOrNull() ?: return null
        synchronized(cache) { cache[key] = stamp to parsed }
        return parsed
    }

    /** Saves all fetched forecasts at once and marks the update as done. */
    fun saveForecasts(ctx: Context, byKey: Map<String, String>) {
        byKey.forEach { (k, json) ->
            val target = forecastFile(ctx, k)
            val tmp = File(target.path + ".tmp")
            tmp.writeText(json)
            if (!tmp.renameTo(target)) { target.delete(); tmp.renameTo(target) }
        }
        synchronized(cache) { byKey.keys.forEach { cache.remove(it) } }
        // Written last: screens listening for changes redraw once the files are in place.
        prefs(ctx).edit().putLong(KEY_UPDATED, System.currentTimeMillis()).remove(KEY_ERROR).apply()
    }

    fun deleteForecast(ctx: Context, key: String) {
        forecastFile(ctx, key).delete()
        synchronized(cache) { cache.remove(key) }
    }

    /** One-time cleanup: older versions kept forecasts inside SharedPreferences. */
    fun migrate(ctx: Context) {
        val p = prefs(ctx)
        val old = p.all.keys.filter { it == "json" || it.startsWith("json_") }
        if (old.isEmpty()) return
        val e = p.edit()
        old.forEach { e.remove(it) }
        e.apply()
    }

    // --- current location ---

    fun saveCurrentPlace(ctx: Context, name: String, lat: Double, lon: Double) {
        prefs(ctx).edit()
            .putString(KEY_PLACE, name)
            .putString(KEY_LAT, lat.toString())
            .putString(KEY_LON, lon.toString())
            .apply()
    }

    fun currentPlaceName(ctx: Context): String? = prefs(ctx).getString(KEY_PLACE, null)

    /** Where the current-location forecast was last for, used when a fresh fix isn't available. */
    fun lastLatLon(ctx: Context): Pair<Double, Double>? {
        val p = prefs(ctx)
        val lat = p.getString(KEY_LAT, null)?.toDoubleOrNull() ?: return null
        val lon = p.getString(KEY_LON, null)?.toDoubleOrNull() ?: return null
        return lat to lon
    }

    // --- update state ---

    fun saveError(ctx: Context, message: String) {
        prefs(ctx).edit().putString(KEY_ERROR, message).apply()
    }

    fun setUpdating(ctx: Context, updating: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_UPDATING, updating).apply()
    }

    // --- refresh interval ---

    /** The chosen interval; an old or unknown value maps to the nearest allowed one. */
    fun refreshMinutes(ctx: Context): Int {
        val saved = prefs(ctx).getInt(KEY_REFRESH_MIN, 30)
        return WeatherWorker.INTERVALS.minByOrNull { kotlin.math.abs(it - saved) } ?: 30
    }

    fun setRefreshMinutes(ctx: Context, minutes: Int) {
        prefs(ctx).edit().putInt(KEY_REFRESH_MIN, minutes).apply()
    }

    // --- which place each widget shows ---

    fun widgetPlace(ctx: Context, widgetId: Int): String? = prefs(ctx).getString(PREFIX_WIDGET + widgetId, null)

    fun setWidgetPlace(ctx: Context, widgetId: Int, key: String) {
        prefs(ctx).edit().putString(PREFIX_WIDGET + widgetId, key).apply()
    }

    fun deleteWidget(ctx: Context, widgetId: Int) {
        prefs(ctx).edit().remove(PREFIX_WIDGET + widgetId).apply()
    }
}
