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
     * Forecasts for [points]. When [source] is [Store.WeatherSource.MET_NORWAY] each place
     * is fetched from api.met.no; [model] is ignored in that case. Open-Meteo is the default.
     */
    fun fetchForecasts(
        points: List<Pair<Double, Double>>,
        model: Store.ModelChoice = Store.ModelChoice.ALADIN,
        source: Store.WeatherSource = Store.WeatherSource.OPEN_METEO,
    ): List<String> {
        require(points.isNotEmpty())
        if (source == Store.WeatherSource.MET_NORWAY) {
            return points.map { (lat, lon) ->
                val body = get(MetNorway.url(lat, lon))
                MetNorway.parse(body, lat, lon)
                JSONObject(body).put("_source", "met_norway").put("_lat", lat).put("_lon", lon).toString()
            }
        }
        val out = arrayOfNulls<String>(points.size)
        // ALADIN only applies inside its geographic domain; other models apply globally.
        val (special, other) = when (model) {
            Store.ModelChoice.ALADIN -> points.indices.partition { Aladin.covers(points[it].first, points[it].second) }
            else -> Pair(points.indices.toList(), emptyList())
        }
        val specialModel = when (model) {
            Store.ModelChoice.ALADIN -> Aladin.MODEL
            Store.ModelChoice.ECMWF  -> "ecmwf_ifs04"
            Store.ModelChoice.ICON   -> "icon_seamless"
            Store.ModelChoice.AUTO   -> null
        }
        if (special.isNotEmpty()) {
            val group = special.map { points[it] }
            val jsons = if (specialModel != null) {
                // Fall back to automatic if the model request fails or comes back incomplete.
                runCatching { requestForecasts(group, specialModel).also { check(it.all(::complete)) } }
                    .getOrElse { requestForecasts(group, null) }
            } else {
                requestForecasts(group, null)
            }
            special.forEachIndexed { k, i -> out[i] = jsons[k] }
        }
        if (other.isNotEmpty()) {
            val jsons = requestForecasts(other.map { points[it] }, null)
            other.forEachIndexed { k, i -> out[i] = jsons[k] }
        }
        return out.map { it!! }
    }

    private fun requestForecasts(points: List<Pair<Double, Double>>, model: String?): List<String> {
        val lats = points.joinToString(",") { String.format(Locale.US, "%.4f", it.first) }
        val lons = points.joinToString(",") { String.format(Locale.US, "%.4f", it.second) }
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lats&longitude=$lons" +
            "&current=temperature_2m,apparent_temperature,weather_code,is_day,wind_speed_10m," +
            "cloud_cover_low,cloud_cover_mid,cloud_cover_high,relative_humidity_2m,uv_index" +
            "&hourly=temperature_2m,weather_code,is_day,cloud_cover_low,cloud_cover_mid,cloud_cover_high" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max," +
            "precipitation_sum,sunrise,sunset,sunshine_duration,daylight_duration,cloud_cover_mean" +
            "&timezone=auto&forecast_days=14&forecast_hours=48" +
            (model?.let { "&models=$it" } ?: "")
        val body = get(url).trim()
        // One place → a JSON object; several → a JSON array of objects.
        val jsons = if (body.startsWith("[")) {
            val arr = JSONArray(body)
            (0 until arr.length()).map { arr.getJSONObject(it).toString() }
        } else {
            listOf(body)
        }
        if (jsons.size != points.size) throw IOException("Expected ${points.size} forecasts, got ${jsons.size}")
        return jsons
    }

    /** Has everything the app can't do without (a model could leave gaps). */
    internal fun complete(json: String): Boolean = runCatching {
        val root = JSONObject(json)
        val c = root.getJSONObject("current")
        val h = root.getJSONObject("hourly")
        val d = root.getJSONObject("daily")
        fun JSONObject.full(key: String): Boolean {
            val a = optJSONArray(key) ?: return false
            return a.length() > 0 && (0 until a.length()).none { a.isNull(it) }
        }
        !c.isNull("temperature_2m") && !c.isNull("weather_code") &&
            h.full("temperature_2m") && h.full("weather_code") &&
            d.full("weather_code") && d.full("temperature_2m_max") && d.full("temperature_2m_min")
    }.getOrDefault(false)

    /**
     * Air quality (Open-Meteo / Copernicus CAMS) for several places in one request,
     * one JSON object string per place, in the same order. Hourly, 4 days.
     */
    fun fetchAirQuality(points: List<Pair<Double, Double>>): List<String> {
        require(points.isNotEmpty())
        val lats = points.joinToString(",") { String.format(Locale.US, "%.4f", it.first) }
        val lons = points.joinToString(",") { String.format(Locale.US, "%.4f", it.second) }
        val url = "https://air-quality-api.open-meteo.com/v1/air-quality?latitude=$lats&longitude=$lons" +
            "&hourly=" + AirQuality.HOURLY.joinToString(",") + "&timezone=auto&forecast_days=4"
        val body = get(url).trim()
        val jsons = if (body.startsWith("[")) {
            val arr = JSONArray(body)
            (0 until arr.length()).map { arr.getJSONObject(it).toString() }
        } else {
            listOf(body)
        }
        if (jsons.size != points.size) throw IOException("Expected ${points.size} results, got ${jsons.size}")
        return jsons
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
