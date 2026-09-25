package com.jawa.app

/** Maps WMO weather codes (used by Open-Meteo) to icons and short descriptions. */
object WeatherCodes {
    fun icon(code: Int, isDay: Boolean): Int = when (code) {
        0 -> if (isDay) R.drawable.wx_clear_day else R.drawable.wx_clear_night
        1, 2 -> if (isDay) R.drawable.wx_partly_day else R.drawable.wx_partly_night
        3 -> R.drawable.wx_overcast
        45, 48 -> R.drawable.wx_fog
        51, 53, 55, 56, 57 -> R.drawable.wx_drizzle
        61, 63, 65, 66, 67 -> R.drawable.wx_rain
        80, 81, 82 -> if (isDay) R.drawable.wx_showers_day else R.drawable.wx_rain
        71, 73, 75, 77, 85, 86 -> R.drawable.wx_snow
        95, 96, 99 -> R.drawable.wx_thunder
        else -> R.drawable.wx_cloudy
    }

    fun text(code: Int): String = when (code) {
        0 -> "Clear"
        1 -> "Mostly clear"
        2 -> "Partly cloudy"
        3 -> "Overcast"
        45, 48 -> "Fog"
        51, 53, 55 -> "Drizzle"
        56, 57 -> "Freezing drizzle"
        61 -> "Light rain"
        63 -> "Rain"
        65 -> "Heavy rain"
        66, 67 -> "Freezing rain"
        71 -> "Light snow"
        73 -> "Snow"
        75 -> "Heavy snow"
        77 -> "Snow grains"
        80 -> "Light showers"
        81 -> "Showers"
        82 -> "Heavy showers"
        85, 86 -> "Snow showers"
        95 -> "Thunderstorm"
        96, 99 -> "Thunderstorm, hail"
        else -> "Cloudy"
    }
}
