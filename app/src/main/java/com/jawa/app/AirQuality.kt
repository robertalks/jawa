package com.jawa.app

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Air quality for one place, from Open-Meteo's air-quality API (Copernicus CAMS, ~11 km in
 * Europe). Everything is read from the hourly data at the current hour, so a file that's a
 * few hours old still shows the right values. Plain Kotlin, testable.
 */
data class AirQuality(
    /** European Air Quality Index now (0 = clean; above 100 = extremely poor). */
    val index: Int,
    val pm25: Double?,
    val pm10: Double?,
    val no2: Double?,
    val o3: Double?,
    /** Next days (today first): date "yyyy-MM-dd" and the day's worst hourly index. */
    val days: List<Pair<String, Int>>,
    /** Pollen today (worst hour), only types that are in the air: name and grains/m³. */
    val pollen: List<Pair<String, Double>>,
) {
    val level: Int get() = levelOf(index)

    companion object {
        /** European AQI bands: Good, Fair, Moderate, Poor, Very poor, Extremely poor. */
        val NAMES = listOf("Good", "Fair", "Moderate", "Poor", "Very poor", "Extremely poor")

        /** Colours of the bands (close to the European Environment Agency's, readable on dark). */
        val COLORS = listOf(0xFF50F0E6, 0xFF50CCAA, 0xFFF0E641, 0xFFFF5050, 0xFFC0274F, 0xFF9A3CC9).map { it.toInt() }

        val ADVICE = listOf(
            "good for outdoor activities",
            "fine for outdoor activities",
            "sensitive people: take it easier outdoors",
            "consider less strenuous outdoor activity",
            "reduce outdoor activity",
            "avoid outdoor activity",
        )

        private val POLLEN = listOf(
            "alder_pollen" to "alder", "birch_pollen" to "birch", "grass_pollen" to "grass",
            "mugwort_pollen" to "mugwort", "olive_pollen" to "olive", "ragweed_pollen" to "ragweed",
        )

        /** Variables to ask for (hourly). */
        val HOURLY = listOf("european_aqi", "pm2_5", "pm10", "nitrogen_dioxide", "ozone") + POLLEN.map { it.first }

        fun levelOf(index: Int): Int = when {
            index <= 20 -> 0
            index <= 40 -> 1
            index <= 60 -> 2
            index <= 80 -> 3
            index <= 100 -> 4
            else -> 5
        }

        /**
         * The index bands for each pollutant (µg/m³; upper limits of Good … Very poor),
         * as used for the European AQI. Used to colour each pollutant's own bar.
         */
        val BANDS = mapOf(
            "pm25" to listOf(10.0, 20.0, 25.0, 50.0, 75.0),
            "pm10" to listOf(20.0, 40.0, 50.0, 100.0, 150.0),
            "no2" to listOf(40.0, 90.0, 120.0, 230.0, 340.0),
            "o3" to listOf(50.0, 100.0, 130.0, 240.0, 380.0),
        )

        fun pollutantLevel(kind: String, value: Double): Int {
            val bands = BANDS[kind] ?: return 0
            return bands.indexOfFirst { value <= it }.let { if (it < 0) 5 else it }
        }

        /** Rough pollen bands, grains per m³. */
        fun pollenLevel(grains: Double): String = when {
            grains < 20 -> "low"
            grains < 100 -> "moderate"
            else -> "high"
        }

        private val HOUR = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00")

        /** [nowMs] is passed in so it can be tested; null if there's no data for this hour. */
        fun parse(json: String, nowMs: Long = System.currentTimeMillis()): AirQuality? =
            runCatching { parseOrNull(json, nowMs) }.getOrNull()

        private fun parseOrNull(json: String, nowMs: Long): AirQuality? {
            val root = JSONObject(json)
            val offset = ZoneOffset.ofTotalSeconds(root.optInt("utc_offset_seconds", 0))
            val nowHour = Instant.ofEpochMilli(nowMs).atOffset(offset).format(HOUR)
            val h = root.getJSONObject("hourly")
            val time = h.getJSONArray("time")
            val i = (0 until time.length()).firstOrNull { time.getString(it) == nowHour } ?: return null
            val aqi = h.optJSONArray("european_aqi").num(i) ?: return null

            // Worst hour per day, today first, at most 4 days
            val today = nowHour.substring(0, 10)
            val byDay = LinkedHashMap<String, Int>()
            val aqiArr = h.optJSONArray("european_aqi")
            for (k in 0 until time.length()) {
                val date = time.getString(k).substring(0, 10)
                if (date < today) continue
                val v = aqiArr.num(k)?.toInt() ?: continue
                byDay[date] = maxOf(byDay[date] ?: 0, v)
            }

            val pollen = POLLEN.mapNotNull { (key, name) ->
                val arr = h.optJSONArray(key) ?: return@mapNotNull null
                val max = (0 until time.length())
                    .filter { time.getString(it).startsWith(today) }
                    .mapNotNull { arr.num(it) }
                    .maxOrNull() ?: return@mapNotNull null
                if (max >= 1.0) name to max else null
            }.sortedByDescending { it.second }

            return AirQuality(
                index = aqi.toInt(),
                pm25 = h.optJSONArray("pm2_5").num(i),
                pm10 = h.optJSONArray("pm10").num(i),
                no2 = h.optJSONArray("nitrogen_dioxide").num(i),
                o3 = h.optJSONArray("ozone").num(i),
                days = byDay.entries.take(4).map { it.key to it.value },
                pollen = pollen,
            )
        }

        private fun JSONArray?.num(i: Int): Double? =
            if (this == null || i >= length() || isNull(i)) null else optDouble(i).takeIf { !it.isNaN() }
    }
}
