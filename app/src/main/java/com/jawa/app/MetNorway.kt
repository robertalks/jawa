package com.jawa.app

import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Fetches and parses MET Norway Locationforecast 2.0 (compact) for one place.
 * The API is free, no key required, just a User-Agent. Returns a [Forecast]
 * in exactly the same shape as Open-Meteo so the rest of the app is unaffected.
 *
 * Times in the response are UTC ISO-8601; they are converted to the device's local
 * zone so the hourly/daily display matches what Open-Meteo returns.
 */
object MetNorway {
    private const val BASE = "https://api.met.no/weatherapi/locationforecast/2.0/compact"

    fun url(lat: Double, lon: Double): String =
        "$BASE?lat=${String.format(Locale.US, "%.4f", lat)}&lon=${String.format(Locale.US, "%.4f", lon)}"

    /**
     * Converts a MET Norway symbol_code to the WMO weather code subset used by
     * [WeatherCodes]. "clearsky_day" → 0, "partlycloudy_night" → 2, "rain" → 63, etc.
     * Only the base name (before the first '_') is used for the mapping; the day/night
     * suffix is handled separately via [isDay].
     */
    fun symbolToCode(symbol: String): Int {
        val base = symbol.substringBefore('_')
        return when (base) {
            "clearsky" -> 0
            "fair" -> 1
            "partlycloudy" -> 2
            "cloudy" -> 3
            "fog" -> 45
            "lightdrizzle", "drizzle" -> 51
            "heavydrizzle" -> 55
            "lightsleet", "lightsleetshowers" -> 66
            "sleet", "sleetshowers" -> 67
            "lightrain", "lightrainshowers" -> 61
            "rain", "rainshowers" -> 63
            "heavyrain", "heavyrainshowers" -> 65
            "lightsnow", "lightsnowshowers" -> 71
            "snow", "snowshowers" -> 73
            "heavysnow", "heavysnowshowers" -> 75
            "thunder", "rainandthunder", "heavyrainandthunder",
            "lightrainandthunder", "sleetandthunder" -> 95
            else -> 3
        }
    }

    fun symbolIsDay(symbol: String): Boolean = !symbol.endsWith("_night")

    /**
     * Parse a MET Norway compact JSON body into a [Forecast].
     * [nowMs] defaults to now; override in tests.
     */
    fun parse(json: String, nowMs: Long = System.currentTimeMillis()): Forecast {
        val root = JSONObject(json)
        val timeseries = root.getJSONObject("properties").getJSONArray("timeseries")

        val zone = ZoneId.systemDefault()
        val localFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm", Locale.US)
        val dateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)

        fun utcToLocal(isoUtc: String): ZonedDateTime =
            Instant.parse(isoUtc).atZone(zone)

        val nowZdt = Instant.ofEpochMilli(nowMs).atZone(zone)
        val nowLocalStr = nowZdt.format(localFmt)

        // Index all entries by their local-time string for quick lookup
        data class Entry(
            val localTime: String,
            val date: String,
            val hour: Int,
            val temp: Double,
            val windMs: Double,
            val cloudPct: Double,
            val symbol1h: String?,  // next_1_hours symbol
            val symbol6h: String?,  // next_6_hours symbol
            val rain1h: Double,
            val rain6h: Double,
        )

        val entries = ArrayList<Entry>(timeseries.length())
        for (i in 0 until timeseries.length()) {
            val ts = timeseries.getJSONObject(i)
            val zdt = utcToLocal(ts.getString("time"))
            val data = ts.getJSONObject("data")
            val instant = data.getJSONObject("instant").getJSONObject("details")
            val next1 = data.optJSONObject("next_1_hours")
            val next6 = data.optJSONObject("next_6_hours")
            entries += Entry(
                localTime = zdt.format(localFmt),
                date = zdt.format(dateFmt),
                hour = zdt.hour,
                temp = instant.getDouble("air_temperature"),
                windMs = instant.optDouble("wind_speed", 0.0),
                cloudPct = instant.optDouble("cloud_area_fraction", 0.0),
                symbol1h = next1?.getJSONObject("summary")?.optString("symbol_code"),
                symbol6h = next6?.getJSONObject("summary")?.optString("symbol_code"),
                rain1h = next1?.getJSONObject("details")?.optDouble("precipitation_amount", 0.0) ?: 0.0,
                rain6h = next6?.getJSONObject("details")?.optDouble("precipitation_amount", 0.0) ?: 0.0,
            )
        }

        // Current: closest entry at or just before now
        val curEntry = entries.lastOrNull { it.localTime <= nowLocalStr } ?: entries.first()
        val curSymbol = curEntry.symbol1h ?: curEntry.symbol6h ?: "cloudy"
        val curCode = symbolToCode(curSymbol)
        val curIsDay = symbolIsDay(curSymbol)
        val current = Forecast.Current(
            time = curEntry.localTime,
            temp = curEntry.temp,
            feelsLike = curEntry.temp,   // MET Norway compact doesn't include feels-like
            code = curCode,
            isDay = curIsDay,
            windKmh = curEntry.windMs * 3.6,
        )

        // Hours: up to 48 entries after now, 1-hour resolution where available
        val hours = ArrayList<Forecast.Hour>()
        for (e in entries) {
            if (e.localTime <= curEntry.localTime) continue
            if (hours.size >= 48) break
            val sym = e.symbol1h ?: e.symbol6h ?: continue   // skip if no forecast at this step
            hours += Forecast.Hour(
                time = e.localTime,
                temp = e.temp,
                code = symbolToCode(sym),
                isDay = symbolIsDay(sym),
                rainChance = 0,   // MET Norway compact has no precipitation probability
            )
        }

        // Days: group entries by date, derive daily max/min/code/rain
        val today = nowZdt.format(dateFmt)
        val byDate = LinkedHashMap<String, MutableList<Entry>>()
        for (e in entries) {
            if (e.date < today) continue
            byDate.getOrPut(e.date) { mutableListOf() } += e
        }

        val days = byDate.entries.take(10).map { (date, es) ->
            // Representative code: prefer daytime (06–20) symbol, pick worst precipitation
            val daytime = es.filter { it.hour in 6..20 }
            val pool = (daytime.ifEmpty { es })
            val repSymbol = pool.mapNotNull { it.symbol1h ?: it.symbol6h }
                .maxByOrNull { symbolSeverity(it) } ?: "cloudy"
            // Rain: sum of all 1h amounts; if 1h not available fall back to 6h buckets (de-dup by rounding hour to 6h)
            val rain = if (es.any { it.symbol1h != null }) {
                es.sumOf { it.rain1h }
            } else {
                // 6-hourly: entries are at 00,06,12,18 — sum them without double-counting
                es.filter { it.hour % 6 == 0 && it.symbol6h != null }.sumOf { it.rain6h }
            }
            // Sunrise/sunset: not in MET Norway compact — leave null (shown as "–" in the UI)
            Forecast.Day(
                date = date,
                code = symbolToCode(repSymbol),
                max = es.maxOf { it.temp },
                min = es.minOf { it.temp },
                rainChance = 0,
                rainMm = rain,
                sunrise = null,
                sunset = null,
            )
        }

        return Forecast(current, hours, days)
    }

    /** Higher = more severe, used to pick the representative symbol for a day. */
    private fun symbolSeverity(symbol: String): Int {
        val base = symbol.substringBefore('_')
        return when (base) {
            "thunder", "rainandthunder", "heavyrainandthunder",
            "lightrainandthunder", "sleetandthunder" -> 10
            "heavyrain", "heavyrainshowers", "heavysnow", "heavysnowshowers" -> 9
            "rain", "rainshowers", "snow", "snowshowers" -> 8
            "sleet", "sleetshowers", "heavysleet", "heavysleetshowers" -> 7
            "lightrain", "lightrainshowers", "lightsleet", "lightsleetshowers",
            "lightsnow", "lightsnowshowers" -> 6
            "lightdrizzle", "drizzle", "heavydrizzle" -> 5
            "fog" -> 4
            "cloudy" -> 3
            "partlycloudy" -> 2
            "fair" -> 1
            "clearsky" -> 0
            else -> 3
        }
    }
}
