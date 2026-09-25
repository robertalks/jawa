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
            val current = Current(
                time = c.getString("time"),
                temp = c.getDouble("temperature_2m"),
                feelsLike = c.optDouble("apparent_temperature", c.getDouble("temperature_2m")),
                code = c.getInt("weather_code"),
                isDay = c.optInt("is_day", 1) == 1,
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
                hours += Hour(
                    time = t,
                    temp = h.getJSONArray("temperature_2m").getDouble(i),
                    code = h.getJSONArray("weather_code").getInt(i),
                    isDay = h.getJSONArray("is_day").optInt(i, 1) == 1,
                    rainChance = h.optJSONArray("precipitation_probability").intAt(i),
                )
            }

            val d = root.getJSONObject("daily")
            val dTime = d.getJSONArray("time")
            val days = (0 until dTime.length()).map { i ->
                Day(
                    date = dTime.getString(i),
                    code = d.getJSONArray("weather_code").getInt(i),
                    max = d.getJSONArray("temperature_2m_max").getDouble(i),
                    min = d.getJSONArray("temperature_2m_min").getDouble(i),
                    rainChance = d.optJSONArray("precipitation_probability_max").intAt(i),
                    rainMm = d.optJSONArray("precipitation_sum").doubleAt(i),
                    sunrise = d.optJSONArray("sunrise").timeAt(i),
                    sunset = d.optJSONArray("sunset").timeAt(i),
                )
            }
            return Forecast(current, hours, days)
        }

        private fun JSONArray?.intAt(i: Int): Int =
            if (this == null || isNull(i)) 0 else optInt(i, 0)

        private fun JSONArray?.doubleAt(i: Int): Double =
            if (this == null || isNull(i)) 0.0 else optDouble(i, 0.0)

        /** "2026-09-24T06:52" → "06:52" */
        private fun JSONArray?.timeAt(i: Int): String? =
            if (this == null || isNull(i)) null else optString(i).takeIf { it.length >= 16 }?.substring(11, 16)
    }
}
