package com.jawa.app

/**
 * Decides how sunny or cloudy it looks, instead of taking the forecast's weather code at
 * face value. Two problems with the raw codes:
 *
 *  - The daily code is the *most severe* hour of the day: one overcast hour marks the
 *    whole day "Overcast", even if it's sunny for the rest of it.
 *  - Codes 0–3 (clear … overcast) come from total cloud cover, which counts thin, high
 *    cirrus the sun shines through the same as thick low cloud.
 *
 * So: days are judged by how much of the daylight is sunny; hours and "now" by cloud cover
 * weighted by height (and sunshine, in daytime). Rain, snow, fog and thunder are kept, but
 * for a day only when they're meaningful. Plain Kotlin, no Android, so it can be tested.
 */
object Sky {
    const val CLEAR = 0
    const val MOSTLY_CLEAR = 1
    const val PARTLY_CLOUDY = 2
    const val OVERCAST = 3
    private const val SHOWERS = 80

    /** Cloud cover (0–100) as it looks from the ground: high cloud counts much less than low. */
    fun effectiveCover(low: Double, mid: Double, high: Double): Double {
        val l = low.coerceIn(0.0, 100.0) / 100
        val m = mid.coerceIn(0.0, 100.0) / 100
        val h = high.coerceIn(0.0, 100.0) / 100
        val clearSky = (1 - l) * (1 - 0.75 * m) * (1 - 0.35 * h) // layers overlap
        return (1 - clearSky) * 100
    }

    fun fromCover(cover: Double): Int = when {
        cover < 20 -> CLEAR
        cover < 45 -> MOSTLY_CLEAR
        cover < 75 -> PARTLY_CLOUDY
        else -> OVERCAST
    }

    /** Share of daylight with sunshine (0–1) → sky code. */
    fun fromSunshine(ratio: Double): Int = when {
        ratio >= 0.75 -> CLEAR
        ratio >= 0.5 -> MOSTLY_CLEAR
        ratio >= 0.25 -> PARTLY_CLOUDY
        else -> OVERCAST
    }

    /**
     * Now or one hour. Only codes 0–3 are re-judged; anything with rain, snow, fog or
     * thunder is kept. [sunshineSeconds] is the sunshine in that hour (daytime only).
     */
    fun hour(code: Int, low: Double?, mid: Double?, high: Double?, isDay: Boolean, sunshineSeconds: Double? = null): Int {
        if (code !in CLEAR..OVERCAST || low == null || mid == null || high == null) return code
        var sky = fromCover(effectiveCover(low, mid, high))
        // Mostly sunny hour? Then it can't look more than "mostly clear".
        if (isDay && sunshineSeconds != null && sunshineSeconds >= 0.6 * 3600) sky = minOf(sky, MOSTLY_CLEAR)
        return sky
    }

    /** A whole day, from its sunshine vs daylight and how much rain is expected. */
    fun day(code: Int, sunshineSeconds: Double?, daylightSeconds: Double?, rainMm: Double, rainChance: Int): Int {
        if (sunshineSeconds == null || daylightSeconds == null || daylightSeconds <= 0) return code
        val ratio = (sunshineSeconds / daylightSeconds).coerceIn(0.0, 1.0)
        val sky = fromSunshine(ratio)
        val wet = rainMm >= 1.0 || rainChance >= 50
        return when (code) {
            in CLEAR..OVERCAST -> sky
            45, 48 -> if (ratio >= 0.25) sky else code                     // morning fog, sunny later
            in 95..99 -> if (rainChance >= 40) code else sky                 // thunder only if likely
            in 71..77, 85, 86 -> if (wet) code else sky                      // snow
            in 51..67, in 80..82 ->                                          // drizzle / rain / showers
                when {
                    !wet -> sky
                    ratio >= 0.4 && code !in 66..67 -> SHOWERS                   // rain, but lots of sun too
                    else -> code
                }
            else -> code
        }
    }
}
