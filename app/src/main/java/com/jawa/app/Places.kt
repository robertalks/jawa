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
data class PlaceRef(val key: String, val name: String, val isCurrent: Boolean)

/** Saved places (max 10) plus the optional "current location" entry, which is always first. */
object Places {
    const val MAX_SAVED = 10
    const val CURRENT_KEY = "cur"

    private const val KEY_SAVED = "places"
    private const val KEY_USE_CURRENT = "use_current"

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

    /** Everything to show, in order: current location (if on) then saved places. */
    fun all(ctx: Context): List<PlaceRef> {
        val out = ArrayList<PlaceRef>()
        if (useCurrent(ctx)) {
            out += PlaceRef(CURRENT_KEY, Store.currentPlaceName(ctx) ?: "Current location", true)
        }
        saved(ctx).forEach { out += PlaceRef(it.id, it.name, false) }
        return out
    }
}
