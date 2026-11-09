package com.jawa.app

import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

/**
 * Sunrise and sunset times for a given date and location, calculated offline
 * using the NOAA solar calculator algorithm. Accurate to within a minute for
 * latitudes between 70°S and 70°N. Returns "HH:mm" strings in local time,
 * or null at extreme latitudes where the sun doesn't rise or set.
 * Plain Kotlin, no Android, testable.
 */
object SunCalc {

    /** "HH:mm" in local time, or null (polar day/night). */
    fun sunrise(lat: Double, lon: Double, dateIso: String, offsetSeconds: Int): String? =
        calc(lat, lon, dateIso, offsetSeconds, rising = true)

    fun sunset(lat: Double, lon: Double, dateIso: String, offsetSeconds: Int): String? =
        calc(lat, lon, dateIso, offsetSeconds, rising = false)

    private fun calc(lat: Double, lon: Double, dateIso: String, offsetSeconds: Int, rising: Boolean): String? {
        val parts = dateIso.split("-")
        val year = parts[0].toInt(); val month = parts[1].toInt(); val day = parts[2].toInt()

        // Julian day
        val jd = julianDay(year, month, day)
        val t = (jd - 2451545.0) / 36525.0

        // Sun's mean longitude and anomaly
        val l0 = (280.46646 + t * (36000.76983 + t * 0.0003032)) % 360
        val m = rad(357.52911 + t * (35999.05029 - t * 0.0001537))
        val c = sin(m) * (1.914602 - t * (0.004817 + 0.000014 * t)) +
                sin(2 * m) * (0.019993 - 0.000101 * t) + sin(3 * m) * 0.000289
        val sunLon = rad(l0 + c)

        // Apparent right ascension and declination
        val omega = rad(125.04 - 1934.136 * t)
        val lambda = sunLon - rad(0.00569 + 0.00478 * sin(omega))
        val epsilon = rad(23.0 + (26.0 + (21.448 - t * (46.8150 + t * (0.00059 - t * 0.001813))) / 60) / 60
                + 0.00256 * cos(omega))
        val decl = asin(sin(epsilon) * sin(lambda))

        // Hour angle at sunrise/sunset (zenith = 90.833° including refraction)
        val cosHA = (cos(rad(90.833)) / (cos(rad(lat)) * cos(decl)) - tan(rad(lat)) * tan(decl))
        if (cosHA < -1.0 || cosHA > 1.0) return null  // polar day or night
        val ha = deg(acos(cosHA))

        // Equation of time
        val y = tan(epsilon / 2).let { it * it }
        val eot = deg(
            y * sin(2 * rad(l0)) - 2 * eccentricity(t) * sin(m) +
            4 * eccentricity(t) * y * sin(m) * cos(2 * rad(l0)) -
            0.5 * y * y * sin(4 * rad(l0)) - 1.25 * eccentricity(t) * eccentricity(t) * sin(2 * m)
        ) * 4  // minutes

        // Solar noon in minutes from midnight UTC
        val solarNoonUtc = 720.0 - 4.0 * lon - eot
        val eventUtcMin = solarNoonUtc + (if (rising) -ha else ha) * 4

        // Convert to local time using offset
        val localMin = eventUtcMin + offsetSeconds / 60.0
        val totalMin = ((localMin % 1440) + 1440).toInt() % 1440
        return "%02d:%02d".format(totalMin / 60, totalMin % 60)
    }

    private fun julianDay(y: Int, m: Int, d: Int): Double {
        val a = floor((14 - m) / 12.0).toInt()
        val yr = y + 4800 - a
        val mo = m + 12 * a - 3
        return d + floor((153 * mo + 2) / 5.0) + 365 * yr + floor(yr / 4.0) - floor(yr / 100.0) + floor(yr / 400.0) - 32045.0
    }

    private fun eccentricity(t: Double) = 0.016708634 - t * (0.000042037 + 0.0000001267 * t)

    private fun rad(d: Double) = d * Math.PI / 180
    private fun deg(r: Double) = r * 180 / Math.PI
}
