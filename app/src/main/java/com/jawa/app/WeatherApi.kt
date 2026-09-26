package com.jawa.app

import android.content.Context
import android.location.Geocoder
import android.location.Location
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

object WeatherApi {
    private const val USER_AGENT = "JaWa/0.8 (Just Another Weather App, personal Android app)"

    /**
     * Forecasts from Open-Meteo (free, no API key) for several places in ONE request.
     * Returns one JSON object string per place, in the same order.
     */
    fun fetchForecasts(points: List<Pair<Double, Double>>): List<String> {
        require(points.isNotEmpty())
        val lats = points.joinToString(",") { String.format(Locale.US, "%.4f", it.first) }
        val lons = points.joinToString(",") { String.format(Locale.US, "%.4f", it.second) }
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lats&longitude=$lons" +
            "&current=temperature_2m,apparent_temperature,weather_code,is_day,wind_speed_10m," +
            "cloud_cover_low,cloud_cover_mid,cloud_cover_high" +
            "&hourly=temperature_2m,weather_code,is_day,cloud_cover_low,cloud_cover_mid,cloud_cover_high" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max," +
            "precipitation_sum,sunrise,sunset,sunshine_duration,daylight_duration,cloud_cover_mean" +
            "&timezone=auto&forecast_days=14&forecast_hours=48"
        val body = get(url).trim()
        // One place → a JSON object; several → a JSON array of objects.
        return if (body.startsWith("[")) {
            val arr = JSONArray(body)
            (0 until arr.length()).map { arr.getJSONObject(it).toString() }
        } else {
            listOf(body)
        }
    }

    /** Place search by name (Open-Meteo geocoding). */
    fun searchPlaces(query: String): List<Place> {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val lang = URLEncoder.encode(Locale.getDefault().language.ifBlank { "en" }, "UTF-8")
        val json = JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?name=$q&count=8&language=$lang&format=json"))
        val results = json.optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).map { i ->
            val r = results.getJSONObject(i)
            val region = listOf(r.optString("admin1"), r.optString("country_code"))
                .filter { it.isNotBlank() }
                .joinToString(", ")
            Place(
                id = "p" + r.optLong("id", System.nanoTime()),
                name = r.getString("name"),
                subtitle = region,
                lat = r.getDouble("latitude"),
                lon = r.getDouble("longitude"),
            )
        }
    }

    /**
     * Town/village name for a location. Uses the phone's geocoder first (tried twice,
     * it sometimes fails in the background) and falls back to OpenStreetMap's Nominatim.
     * Both are made to give the same kind of name, e.g. "Praha 3", "Nepomuk".
     */
    fun placeName(ctx: Context, lat: Double, lon: Double): String? =
        (fromGeocoder(ctx, lat, lon) ?: run { Thread.sleep(1500); fromGeocoder(ctx, lat, lon) })
            ?.let(::tidy)
            ?: fromNominatim(lat, lon)?.let(::tidy)

    private fun fromGeocoder(ctx: Context, lat: Double, lon: Double): String? {
        if (!Geocoder.isPresent()) return null
        return try {
            @Suppress("DEPRECATION")
            val list = Geocoder(ctx, Locale.getDefault()).getFromLocation(lat, lon, 3).orEmpty()
            list.firstNotNullOfOrNull { a ->
                (a.locality ?: a.subLocality ?: a.subAdminArea)?.takeIf { it.isNotBlank() }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun fromNominatim(lat: Double, lon: Double): String? = try {
        val lang = URLEncoder.encode(Locale.getDefault().toLanguageTag(), "UTF-8")
        val url = String.format(
            Locale.US,
            "https://nominatim.openstreetmap.org/reverse?format=jsonv2&zoom=14&lat=%.4f&lon=%.4f&accept-language=%s",
            lat, lon, lang,
        )
        val addr = JSONObject(get(url)).optJSONObject("address")
        fun field(key: String) = addr?.optString(key)?.takeIf { it.isNotBlank() }
        // In a city with numbered districts (Praha 3, Wien 7 …) use the district, like postal
        // addresses do; otherwise the village/town/city name.
        val district = field("city_district")?.takeIf { d -> d.any { it.isDigit() } }
        district ?: listOf("village", "town", "city", "municipality", "suburb", "county")
            .firstNotNullOfOrNull { field(it) }
    } catch (e: Exception) {
        null
    }

    /** "Hlavní město Praha" → "Praha" (official long names aren't useful on a widget). */
    fun tidy(name: String): String =
        name.removePrefix("Hlavní město ").removePrefix("Capital City of ").trim()

    fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val out = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, out)
        return out[0] / 1000f
    }

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("User-Agent", USER_AGENT)
        try {
            if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
