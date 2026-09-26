package com.jawa.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** A place the user added by name. */
data class Place(
    val id: String,
    val name: String,
    val subtitle: String,
    val lat: Double,
    val lon: Double,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("subtitle", subtitle).put("lat", lat).put("lon", lon)

    companion object {
        fun fromJson(o: JSONObject) = Place(
            id = o.getString("id"),
            name = o.getString("name"),
            subtitle = o.optString("subtitle"),
            lat = o.getDouble("lat"),
            lon = o.getDouble("lon"),
        )
    }
}

/** One entry in the list of places shown in the app and widget. */
data class PlaceRef(val key: String, val name: String, val isCurrent: Boolean, val isHome: Boolean = false)

/**
 * The places to show: current location (if on) first, then home (only while you're away
 * from it), then up to 10 saved places. Home doesn't count toward the 10.
 */
object Places {
    const val MAX_SAVED = 10
    const val CURRENT_KEY = "cur"
    const val HOME_KEY = "home"

    /** Further than this from home counts as "away", and the home page appears. */
    const val HOME_RADIUS_KM = 15f

    private const val KEY_SAVED = "places"
    private const val KEY_USE_CURRENT = "use_current"
    private const val KEY_HOME = "home_place"

    fun saved(ctx: Context): List<Place> {
        val raw = Store.prefs(ctx).getString(KEY_SAVED, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { Place.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    fun setSaved(ctx: Context, places: List<Place>) {
        val arr = JSONArray()
        places.take(MAX_SAVED).forEach { arr.put(it.toJson()) }
        Store.prefs(ctx).edit().putString(KEY_SAVED, arr.toString()).apply()
    }

    /** Adds a place; returns false if the list is full or it's already there. */
    fun add(ctx: Context, place: Place): Boolean {
        val list = saved(ctx)
        if (list.size >= MAX_SAVED || list.any { it.id == place.id }) return false
        setSaved(ctx, list + place)
        return true
    }

    fun remove(ctx: Context, id: String) {
        setSaved(ctx, saved(ctx).filterNot { it.id == id })
        Store.deleteForecast(ctx, id)
    }

    fun useCurrent(ctx: Context): Boolean = Store.prefs(ctx).getBoolean(KEY_USE_CURRENT, true)

    fun setUseCurrent(ctx: Context, on: Boolean) {
        Store.prefs(ctx).edit().putBoolean(KEY_USE_CURRENT, on).apply()
    }

    // --- home ---

    fun home(ctx: Context): Place? =
        Store.prefs(ctx).getString(KEY_HOME, null)?.let { runCatching { Place.fromJson(JSONObject(it)) }.getOrNull() }

    fun setHome(ctx: Context, name: String, lat: Double, lon: Double) {
        val home = Place(HOME_KEY, name, "", lat, lon)
        Store.prefs(ctx).edit().putString(KEY_HOME, home.toJson().toString()).apply()
        Store.deleteForecast(ctx, HOME_KEY) // belongs to the previous home
    }

    fun clearHome(ctx: Context) {
        Store.prefs(ctx).edit().remove(KEY_HOME).apply()
        Store.deleteForecast(ctx, HOME_KEY)
    }

    /** Distance from [current] (or the last known current location) to home, if both are known. */
    fun kmFromHome(ctx: Context, current: Pair<Double, Double>? = Store.lastLatLon(ctx)): Float? {
        val home = home(ctx) ?: return null
        val cur = current ?: return null
        return WeatherApi.distanceKm(home.lat, home.lon, cur.first, cur.second)
    }

    /**
     * Should the home page be shown? Yes when you're away from it, or when there's no
     * current location to compare with (current location off, or not known yet).
     */
    fun homeVisible(ctx: Context, current: Pair<Double, Double>? = Store.lastLatLon(ctx)): Boolean {
        if (home(ctx) == null) return false
        if (!useCurrent(ctx)) return true
        val km = kmFromHome(ctx, current) ?: return true
        return km > HOME_RADIUS_KM
    }

    /** Everything to show, in order: current location (if on), home (while away), saved places. */
    fun all(ctx: Context): List<PlaceRef> {
        val out = ArrayList<PlaceRef>()
        if (useCurrent(ctx)) {
            out += PlaceRef(CURRENT_KEY, Store.currentPlaceName(ctx) ?: "Current location", true)
        }
        home(ctx)?.let { h -> if (homeVisible(ctx)) out += PlaceRef(HOME_KEY, h.name, false, isHome = true) }
        saved(ctx).forEach { out += PlaceRef(it.id, it.name, false) }
        return out
    }
}
