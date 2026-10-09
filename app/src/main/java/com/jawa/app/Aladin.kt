package com.jawa.app

/**
 * The Czech weather service's (ČHMÚ) ALADIN model, used for places in and around the Czech
 * Republic. Its 1 km Czech domain covers 48.5–51.1 °N, 12.0–19.0 °E; Open-Meteo's
 * "seamless" version continues it with ECMWF after about 3 days and adds ECMWF's rain
 * chance. Outside its area that model has no data, so other places keep Open-Meteo's
 * automatic choice. Plain Kotlin, testable.
 */
object Aladin {
    const val MODEL = "chmi_aladin_seamless"

    // The domain edges, pulled in a little so places right at the edge aren't on the border.
    private const val SOUTH = 48.55
    private const val NORTH = 51.05
    private const val WEST = 12.05
    private const val EAST = 18.95

    fun covers(lat: Double, lon: Double): Boolean = lat in SOUTH..NORTH && lon in WEST..EAST
}
