package com.jawa.app

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Moon phase, calculated from the date alone (no download, works offline).
 * Uses the average length of a lunar month counted from a known new moon; accurate to
 * within about half a day, which is plenty for showing the phase. Plain Kotlin, testable.
 */
object Moon {
    /** Average days from one new moon to the next. */
    const val SYNODIC_DAYS = 29.530588853

    /** A known new moon: 6 Jan 2000, 18:14 UTC. */
    private const val REF_NEW_MOON_MS = 947182440000L
    private const val DAY_MS = 86_400_000.0

    /** Number of phase icons (moon_00 … moon_15). */
    const val ICON_STEPS = 16

    /** Days since the last new moon, 0 until [SYNODIC_DAYS]. */
    fun age(epochMs: Long): Double {
        val days = (epochMs - REF_NEW_MOON_MS) / DAY_MS
        return ((days % SYNODIC_DAYS) + SYNODIC_DAYS) % SYNODIC_DAYS
    }

    /** How much of the moon is lit, 0.0 (new) to 1.0 (full). */
    fun illumination(age: Double): Double = (1 - cos(2 * PI * age / SYNODIC_DAYS)) / 2

    /** New, full and the quarters are only named within about a day of the exact moment. */
    fun name(age: Double): String {
        val p = SYNODIC_DAYS
        return when {
            age < 1 || age > p - 1 -> "New moon"
            abs(age - p / 4) < 1 -> "First quarter"
            abs(age - p / 2) < 1 -> "Full moon"
            abs(age - 3 * p / 4) < 1 -> "Last quarter"
            age < p / 4 -> "Waxing crescent"
            age < p / 2 -> "Waxing gibbous"
            age < 3 * p / 4 -> "Waning gibbous"
            else -> "Waning crescent"
        }
    }

    /** Which of the [ICON_STEPS] phase icons to show. */
    fun iconIndex(age: Double): Int = (age / SYNODIC_DAYS * ICON_STEPS).roundToInt() % ICON_STEPS

    /**
     * The next new or full moon after [epochMs] (skipping one within the next day that's
     * already named as the current phase). Exact time from Meeus, "Astronomical Algorithms"
     * ch. 49, with the main correction terms: accurate to a few minutes.
     */
    fun nextMainPhase(epochMs: Long): Pair<String, Long> {
        val current = name(age(epochMs))
        val jdNow = epochMs / DAY_MS + 2440587.5
        var k = floor((jdNow - 2451550.09766) / 29.530588861) - 1
        while (true) {
            for (half in listOf(0.0, 0.5)) {
                val kk = k + half
                val t = phaseTimeMs(kk)
                val label = if (half == 0.0) "New moon" else "Full moon"
                val soonAndCurrent = t - epochMs < DAY_MS && label == current
                if (t > epochMs && !soonAndCurrent) return label to t
            }
            k += 1
        }
    }

    /** Time of new moon (k whole) or full moon (k + 0.5), as epoch milliseconds. */
    fun phaseTimeMs(k: Double): Long {
        val t = k / 1236.85
        val jde = 2451550.09766 + 29.530588861 * k + 0.00015437 * t * t - 0.00000015 * t * t * t
        val e = 1 - 0.002516 * t - 0.0000074 * t * t
        val m = rad(2.5534 + 29.1053567 * k - 0.0000014 * t * t)
        val mp = rad(201.5643 + 385.81693528 * k + 0.0107582 * t * t + 0.00001238 * t * t * t)
        val f = rad(160.7108 + 390.67050284 * k - 0.0016118 * t * t - 0.00000227 * t * t * t)
        val full = k - floor(k) > 0.25
        val corr = if (!full) {
            -0.40720 * sin(mp) + 0.17241 * e * sin(m) + 0.01608 * sin(2 * mp) + 0.01039 * sin(2 * f) +
                0.00739 * e * sin(mp - m) - 0.00514 * e * sin(mp + m) + 0.00208 * e * e * sin(2 * m)
        } else {
            -0.40614 * sin(mp) + 0.17302 * e * sin(m) + 0.01614 * sin(2 * mp) + 0.01043 * sin(2 * f) +
                0.00734 * e * sin(mp - m) - 0.00515 * e * sin(mp + m) + 0.00209 * e * e * sin(2 * m)
        }
        return ((jde + corr - 2440587.5) * DAY_MS).toLong()
    }

    private fun rad(deg: Double) = deg * PI / 180
}
