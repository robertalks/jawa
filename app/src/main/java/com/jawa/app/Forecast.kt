package com.jawa.app

import org.json.JSONArray
import org.json.JSONObject

/** The parts of the Open-Meteo response the widget uses. */
data class Forecast(
    val current: Current,
    val hours: List<Hour>,
    val days: List<Day>,
) {
    data class Current(
        val time: String,
        val temp: Double,
        val feelsLike: Double,
        val code: Int,
        val isDay: Boolean,
        val windKmh: Double,
    )

    data class Hour(val time: String, val temp: Double, val code: Int, val isDay: Boolean, val rainChance: Int)

    data class Day(
        val date: String,
        val code: Int,
        val max: Double,
        val min: Double,
        val rainChance: Int,
        val rainMm: Double,
        val sunrise: String?, // "HH:mm"
        val sunset: String?,
    )

    companion object {
        fun parse(json: String): Forecast {
            val root = JSONObject(json)

            val c = root.getJSONObject("current")
            val currentIsDay = c.optInt("is_day", 1) == 1
            val current = Current(
                time = c.getString("time"),
                temp = c.getDouble("temperature_2m"),
                feelsLike = c.optDouble("apparent_temperature", c.getDouble("temperature_2m")),
                // Sunny vs cloudy judged from cloud layers, see Sky
                code = Sky.hour(
                    c.getInt("weather_code"),
                    c.numOrNull("cloud_cover_low"), c.numOrNull("cloud_cover_mid"), c.numOrNull("cloud_cover_high"),
                    currentIsDay,
                ),
                isDay = currentIsDay,
                windKmh = c.optDouble("wind_speed_10m", 0.0),
            )

            // Times are local to the forecast location, "yyyy-MM-ddTHH:mm", so
            // plain string comparison orders them correctly.
            val h = root.getJSONObject("hourly")
            val hTime = h.getJSONArray("time")
            val hours = ArrayList<Hour>()
            for (i in 0 until hTime.length()) {
                val t = hTime.getString(i)
                if (t <= current.time) continue
                val isDay = h.getJSONArray("is_day").optInt(i, 1) == 1
                hours += Hour(
                    time = t,
                    temp = h.getJSONArray("temperature_2m").getDouble(i),
                    code = Sky.hour(
                        h.getJSONArray("weather_code").getInt(i),
                        h.optJSONArray("cloud_cover_low").numOrNull(i),
                        h.optJSONArray("cloud_cover_mid").numOrNull(i),
                        h.optJSONArray("cloud_cover_high").numOrNull(i),
                        isDay,
                        h.optJSONArray("sunshine_duration").numOrNull(i),
                    ),
                    isDay = isDay,
                    rainChance = h.optJSONArray("precipitation_probability").intAt(i),
                )
            }

            val d = root.getJSONObject("daily")
            val dTime = d.getJSONArray("time")
            val days = (0 until dTime.length()).map { i ->
                val rainChance = d.optJSONArray("precipitation_probability_max").intAt(i)
                val rainMm = d.optJSONArray("precipitation_sum").doubleAt(i)
                Day(
                    date = dTime.getString(i),
                    // The raw daily code is the day's worst hour; judge the day as a whole instead.
                    code = Sky.day(
                        d.getJSONArray("weather_code").getInt(i),
                        d.optJSONArray("sunshine_duration").numOrNull(i),
                        d.optJSONArray("daylight_duration").numOrNull(i),
                        rainMm,
                        rainChance,
                    ),
                    max = d.getJSONArray("temperature_2m_max").getDouble(i),
                    min = d.getJSONArray("temperature_2m_min").getDouble(i),
                    rainChance = rainChance,
                    rainMm = rainMm,
                    sunrise = d.optJSONArray("sunrise").timeAt(i),
                    sunset = d.optJSONArray("sunset").timeAt(i),
                )
            }
            return Forecast(current, hours, days)
        }

        private fun JSONArray?.intAt(i: Int): Int =
            if (this == null || isNull(i)) 0 else optInt(i, 0)

        /** A number, or null if missing (e.g. data saved by an older version). */
        private fun JSONArray?.numOrNull(i: Int): Double? =
            if (this == null || i >= length() || isNull(i)) null else optDouble(i).takeIf { !it.isNaN() }

        private fun JSONObject.numOrNull(key: String): Double? =
            if (!has(key) || isNull(key)) null else optDouble(key).takeIf { !it.isNaN() }

        private fun JSONArray?.doubleAt(i: Int): Double =
            if (this == null || isNull(i)) 0.0 else optDouble(i, 0.0)

        /** "2026-09-24T06:52" → "06:52" */
        private fun JSONArray?.timeAt(i: Int): String? =
            if (this == null || isNull(i)) null else optString(i).takeIf { it.length >= 16 }?.substring(11, 16)
    }
}
