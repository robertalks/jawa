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
 * So: a day is judged by two signals that must agree, its share of sunny daylight and its
 * average cloud cover (sunshine hours alone overstate the sun: hazy sun still counts).
 * Hours and "now" use cloud cover weighted by height. Rain, snow, fog and thunder are kept,
 * but for a day only when they're meaningful. Plain Kotlin, no Android, so it can be tested.
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
        val clearSky = (1 - l) * (1 - 0.85 * m) * (1 - 0.5 * h) // layers overlap
        return (1 - clearSky) * 100
    }

    fun fromCover(cover: Double): Int = when {
        cover < 15 -> CLEAR
        cover < 40 -> MOSTLY_CLEAR
        cover < 70 -> PARTLY_CLOUDY
        else -> OVERCAST
    }

    /**
     * A day's sky from its share of sunny daylight (0–1) and, when known, its average
     * cloud cover (0–100). Both have to point the same way for a sunnier result.
     */
    fun daySky(ratio: Double, cloudMean: Double?): Int {
        if (cloudMean == null) return when {   // older data: sunshine only, strict
            ratio >= 0.85 -> CLEAR
            ratio >= 0.65 -> MOSTLY_CLEAR
            ratio >= 0.35 -> PARTLY_CLOUDY
            else -> OVERCAST
        }
        return when {
            ratio >= 0.8 && cloudMean < 35 -> CLEAR
            ratio >= 0.6 && cloudMean < 60 -> MOSTLY_CLEAR
            ratio >= 0.3 && cloudMean < 85 -> PARTLY_CLOUDY
            else -> OVERCAST
        }
    }

    /**
     * Now or one hour. Only codes 0–3 are re-judged from the cloud layers; anything with
     * rain, snow, fog or thunder is kept.
     */
    fun hour(code: Int, low: Double?, mid: Double?, high: Double?): Int {
        if (code !in CLEAR..OVERCAST || low == null || mid == null || high == null) return code
        return fromCover(effectiveCover(low, mid, high))
    }

    /** A whole day, from its sunshine vs daylight and how much rain is expected. */
    fun day(
        code: Int,
        sunshineSeconds: Double?,
        daylightSeconds: Double?,
        cloudMean: Double?,
        rainMm: Double,
        rainChance: Int,
    ): Int {
        if (sunshineSeconds == null || daylightSeconds == null || daylightSeconds <= 0) return code
        val ratio = (sunshineSeconds / daylightSeconds).coerceIn(0.0, 1.0)
        val sky = daySky(ratio, cloudMean)
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
