package com.jawa.app

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Gets the location, downloads the forecast, stores it and redraws the widgets. */
class WeatherWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val ctx = applicationContext
        Store.migrate(ctx)
        try {
            // Everything to fetch, keyed like the cache: "cur" for current location, else place id.
            val keys = ArrayList<String>()
            val points = ArrayList<Pair<Double, Double>>()

            var current: Pair<Double, Double>? = null
            if (Places.useCurrent(ctx)) {
                val interactive = inputData.getBoolean(KEY_INTERACTIVE, false)
                val loc = LocationHelper.currentLocation(
                    ctx,
                    if (interactive) LocationHelper.MAX_AGE_INTERACTIVE_MS else LocationHelper.MAX_AGE_BACKGROUND_MS,
                )
                current = if (loc != null) loc.latitude to loc.longitude else Store.lastLatLon(ctx)
                current?.let { keys += Places.CURRENT_KEY; points += it }
            }
            // Home, only while it's shown (you're away from it)
            Places.home(ctx)?.let { h ->
                if (Places.homeVisible(ctx, current)) { keys += Places.HOME_KEY; points += h.lat to h.lon }
            }
            Places.saved(ctx).forEach { keys += it.id; points += it.lat to it.lon }

            if (points.isEmpty()) {
                Store.saveError(
                    ctx,
                    if (Places.useCurrent(ctx)) "No location yet – open the app" else "No places – add one in Settings",
                )
                return@withContext Result.success()
            }

            val jsons = WeatherApi.fetchForecasts(points)
            jsons.forEach { Forecast.parse(it) } // make sure they're usable before replacing the old ones
            current?.let { (lat, lon) ->
                val name = resolvePlace(ctx, lat, lon)
                Store.saveCurrentPlace(ctx, name, lat, lon)
            }
            Store.saveForecasts(ctx, keys.zip(jsons).toMap())
            Result.success()
        } catch (e: Exception) {
            Store.saveError(ctx, "Update failed")
            if (runAttemptCount < 2) Result.retry() else Result.success()
        } finally {
            Store.setUpdating(ctx, false)
            WidgetUpdates.all(ctx)
            armShortInterval(ctx) // next refresh for the 10-minute interval
        }
    }

    /**
     * Reuse the previous name unless we've moved more than ~2 km (or it was an
     * untidy name from an older version). If the lookup fails, keep the old name
     * when still nearby rather than showing coordinates.
     */
    private fun resolvePlace(ctx: Context, lat: Double, lon: Double): String {
        val prev = Store.lastLatLon(ctx)
        val prevName = Store.currentPlaceName(ctx)
        val km = prev?.let { WeatherApi.distanceKm(it.first, it.second, lat, lon) } ?: Float.MAX_VALUE
        if (prevName != null && km < 2f && WeatherApi.tidy(prevName) == prevName) return prevName
        return WeatherApi.placeName(ctx, lat, lon)
            ?: prevName?.takeIf { km < 15f }?.let(WeatherApi::tidy)
            ?: String.format(Locale.US, "%.2f, %.2f", lat, lon)
    }

    companion object {
        private const val PERIODIC = "weather-periodic"
        private const val NOW = "weather-now"
        private const val AUTO = "weather-auto"
        private const val KEY_INTERACTIVE = "interactive"

        /** Choices offered in Settings, in minutes. */
        val INTERVALS = listOf(10, 15, 30, 60, 90)

        /** Android's background scheduler won't run periodic work more often than this. */
        private const val MIN_PERIODIC = 15

        private val network = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        /**
         * Periodic background refresh at the chosen interval (at least 15 min: Android's limit).
         * 10 minutes is handled by [RefreshAlarmReceiver] on top of this, which the
         * worker re-arms after every run. [changed] = true when the user picked a new interval.
         */
        fun schedulePeriodic(ctx: Context, changed: Boolean = false) {
            val minutes = maxOf(Store.refreshMinutes(ctx), MIN_PERIODIC).toLong()
            val req = PeriodicWorkRequestBuilder<WeatherWorker>(minutes, TimeUnit.MINUTES)
                .setConstraints(network)
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                PERIODIC,
                if (changed) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP,
                req,
            )
            if (changed) armShortInterval(ctx)
        }

        /** For the 10-minute interval: set an alarm for the next refresh (or clear it). */
        fun armShortInterval(ctx: Context) {
            val minutes = Store.refreshMinutes(ctx)
            if (minutes < MIN_PERIODIC) {
                RefreshAlarmReceiver.arm(ctx, minutes)
            } else {
                RefreshAlarmReceiver.cancel(ctx)
            }
        }

        /** You asked for it (tap, app opened, Refresh): gets a fresher location. */
        fun refreshNow(ctx: Context) {
            Store.setUpdating(ctx, true)
            val req = OneTimeWorkRequestBuilder<WeatherWorker>()
                .setConstraints(network)
                .setInputData(Data.Builder().putBoolean(KEY_INTERACTIVE, true).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, req)
        }

        /** A quiet scheduled refresh (from the alarm); skipped if one is already queued. */
        fun refreshAuto(ctx: Context) {
            val req = OneTimeWorkRequestBuilder<WeatherWorker>()
                .setConstraints(network)
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(AUTO, ExistingWorkPolicy.KEEP, req)
        }

        fun cancelAll(ctx: Context) {
            WorkManager.getInstance(ctx).cancelUniqueWork(PERIODIC)
            RefreshAlarmReceiver.cancel(ctx)
        }
    }
}
