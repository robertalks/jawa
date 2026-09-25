package com.jawa.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Looper
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Town-level location using Android's own LocationManager (no Google Play services needed). */
object LocationHelper {
    /** When you're using the app, a location up to this old is good enough. */
    const val MAX_AGE_INTERACTIVE_MS = 10 * 60 * 1000L

    /**
     * For background refreshes a town-level forecast doesn't need a new fix every time:
     * reuse any location up to an hour old (often one another app already obtained).
     * This is what saves most battery, since a new fix wakes the phone's radios.
     */
    const val MAX_AGE_BACKGROUND_MS = 60 * 60 * 1000L

    private const val TIMEOUT_MS = 20_000L

    fun hasLocation(ctx: Context): Boolean =
        granted(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) ||
            granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION)

    fun hasBackground(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 29 || granted(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION)

    private fun granted(ctx: Context, p: String) =
        ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    /** A location no older than [maxAgeMs] if possible, else the best we can get, or null. */
    @SuppressLint("MissingPermission")
    suspend fun currentLocation(ctx: Context, maxAgeMs: Long): Location? {
        if (!hasLocation(ctx)) return null
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = lm.getProviders(true)

        val last = providers
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
        if (last != null && System.currentTimeMillis() - last.time < maxAgeMs) return last

        val provider = when {
            Build.VERSION.SDK_INT >= 31 && LocationManager.FUSED_PROVIDER in providers -> LocationManager.FUSED_PROVIDER
            LocationManager.NETWORK_PROVIDER in providers -> LocationManager.NETWORK_PROVIDER
            LocationManager.GPS_PROVIDER in providers -> LocationManager.GPS_PROVIDER
            else -> return last
        }
        val fresh = withTimeoutOrNull(TIMEOUT_MS) { requestSingle(ctx, lm, provider) }
        return fresh ?: last
    }

    @SuppressLint("MissingPermission")
    private suspend fun requestSingle(ctx: Context, lm: LocationManager, provider: String): Location? =
        suspendCancellableCoroutine { cont ->
            try {
                if (Build.VERSION.SDK_INT >= 30) {
                    val signal = CancellationSignal()
                    cont.invokeOnCancellation { signal.cancel() }
                    lm.getCurrentLocation(provider, signal, ctx.mainExecutor) { loc: Location? ->
                        if (cont.isActive) cont.resume(loc)
                    }
                } else {
                    val listener = object : LocationListener {
                        override fun onLocationChanged(location: Location) {
                            if (cont.isActive) cont.resume(location)
                        }
                    }
                    cont.invokeOnCancellation { lm.removeUpdates(listener) }
                    @Suppress("DEPRECATION")
                    lm.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                }
            } catch (e: Exception) {
                if (cont.isActive) cont.resume(null)
            }
        }
}
