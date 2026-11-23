package com.jawa.app

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.SessionInfo
import androidx.car.app.model.Action
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.validation.HostValidator
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * JaWa in Android Auto (IOT category car app).
 *
 * Main screen: today, tomorrow, moon phase pane with actions for next days and place picker.
 * Uses the forecasts the phone app already downloaded; asks for a refresh when they're old.
 *
 * To see it in the car with an app that isn't from the Play Store, enable Android Auto's
 * developer settings and turn on "Unknown sources" (see README).
 */
class JawaCarAppService : CarAppService() {
    // Personal app: accept any Android Auto host. (A Play Store app would restrict this.)
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(sessionInfo: SessionInfo): Session = JawaCarSession()
}

class JawaCarSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen = CarMainScreen(carContext)
}

/** Which place the car shows (picked on the Places screen; first place by default). */
object CarPlace {
    private const val KEY = "car_place"

    fun selected(ctx: Context): PlaceRef? {
        val places = Places.all(ctx)
        val key = Store.prefs(ctx).getString(KEY, null)
        return places.firstOrNull { it.key == key } ?: places.firstOrNull()
    }

    fun select(ctx: Context, key: String) {
        Store.prefs(ctx).edit().putString(KEY, key).apply()
    }

    fun label(p: PlaceRef): String = when {
        p.isCurrent -> "📍 ${p.name}"
        p.isHome -> "🏠 ${p.name}"
        p.isFavorite -> "⭐ ${p.name}"
        else -> p.name
    }
}

/** Weather icons as bitmaps for Android Auto rows (drawn from JaWa's own vector icons). */
object CarIcons {
    fun of(ctx: Context, res: Int, sizePx: Int = 96): CarIcon {
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        ContextCompat.getDrawable(ctx, res)?.let { d ->
            d.setBounds(0, 0, sizePx, sizePx)
            d.draw(Canvas(bmp))
        }
        return CarIcon.Builder(IconCompat.createWithBitmap(bmp)).build()
    }
}

/** Redraws the car screens whenever new weather arrives. */
private fun Screen.refreshOnChanges(onChange: () -> Unit) {
    val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> onChange() }
    lifecycle.addObserver(object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            Store.prefs(carContext).registerOnSharedPreferenceChangeListener(listener)
            val age = System.currentTimeMillis() - Store.snapshot(carContext).updatedMillis
            if (age > 10 * 60 * 1000L) WeatherWorker.refreshNow(carContext)
        }

        override fun onStop(owner: LifecycleOwner) {
            Store.prefs(carContext).unregisterOnSharedPreferenceChangeListener(listener)
        }
    })
}

// --------------------------------------------------------------------------- main screen

class CarMainScreen(ctx: CarContext) : Screen(ctx) {
    init {
        refreshOnChanges { invalidate() }
    }

    override fun onGetTemplate(): Template {
        val place = CarPlace.selected(carContext)
        val fc = place?.let { Store.forecast(carContext, it.key) }
        val pane = Pane.Builder()

        if (fc == null) {
            pane.addRow(
                Row.Builder()
                    .setTitle(if (place == null) "No places yet" else "No weather yet")
                    .addText(if (place == null) "Set up JaWa on your phone" else "Updating…")
                    .build(),
            )
        } else {
            fc.days.getOrNull(0)?.let { pane.addRow(dayRow("Today", it)) }
            fc.days.getOrNull(1)?.let { pane.addRow(dayRow("Tomorrow", it)) }
            pane.addRow(moonRow())
        }
        pane.addAction(
            Action.Builder()
                .setTitle("Next days")
                .setFlags(Action.FLAG_PRIMARY)
                .setOnClickListener { screenManager.push(CarDaysScreen(carContext)) }
                .build(),
        )
        pane.addAction(
            Action.Builder()
                .setTitle("Places")
                .setOnClickListener { screenManager.push(CarPlacesScreen(carContext)) }
                .build(),
        )

        return PaneTemplate.Builder(pane.build())
            .setHeader(
                Header.Builder()
                    .setStartHeaderAction(Action.APP_ICON)
                    .setTitle(place?.let { CarPlace.label(it) } ?: "JaWa")
                    .build(),
            )
            .build()
    }

    private fun dayRow(label: String, d: Forecast.Day): Row {
        val rain = if (d.rainChance >= 20) " · rain ${d.rainChance}%" else ""
        return Row.Builder()
            .setTitle("$label ${WidgetRenderer.deg(d.max)} / ${WidgetRenderer.deg(d.min)}")
            .addText(WeatherCodes.text(d.code) + rain)
            .setImage(CarIcons.of(carContext, WeatherCodes.icon(d.code, true)))
            .build()
    }

    private fun moonRow(): Row {
        val now = System.currentTimeMillis()
        val age = Moon.age(now)
        val (label, at) = Moon.nextMainPhase(now)
        val date = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()))
        return Row.Builder()
            .setTitle(Moon.name(age))
            .addText("Next ${label.lowercase(Locale.getDefault())}: $date")
            .setImage(CarIcons.of(carContext, MoonIcons.forAge(age)))
            .build()
    }
}

// --------------------------------------------------------------------------- next days

class CarDaysScreen(ctx: CarContext) : Screen(ctx) {
    init {
        refreshOnChanges { invalidate() }
    }

    override fun onGetTemplate(): Template {
        val place = CarPlace.selected(carContext)
        val fc = place?.let { Store.forecast(carContext, it.key) }
        val list = ItemList.Builder()
        fc?.days?.forEachIndexed { i, d ->
            val date = runCatching {
                java.time.LocalDate.parse(d.date).format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()))
            }.getOrDefault("")
            val rain = if (d.rainChance >= 20) " · ${d.rainChance}%" + (if (d.rainMm > 0) " · ${"%.0f".format(d.rainMm)} mm" else "") else ""
            list.addItem(
                Row.Builder()
                    .setTitle("${WidgetRenderer.dayLabel(d.date, i)} $date · ${WidgetRenderer.deg(d.max)} / ${WidgetRenderer.deg(d.min)}")
                    .addText(WeatherCodes.text(d.code) + rain)
                    .setImage(CarIcons.of(carContext, WeatherCodes.icon(d.code, true)))
                    .build(),
            )
        }
        if (fc == null) list.setNoItemsMessage("No weather yet")
        return ListTemplate.Builder()
            .setHeader(
                Header.Builder()
                    .setStartHeaderAction(Action.BACK)
                    .setTitle("Next days · " + (place?.name ?: ""))
                    .build(),
            )
            .setSingleList(list.build())
            .build()
    }
}

// --------------------------------------------------------------------------- places

class CarPlacesScreen(ctx: CarContext) : Screen(ctx) {
    init {
        refreshOnChanges { invalidate() }
    }

    override fun onGetTemplate(): Template {
        val list = ItemList.Builder()
        Places.all(carContext).forEach { p ->
            val fc = Store.forecast(carContext, p.key)
            val row = Row.Builder()
                .setTitle(CarPlace.label(p))
                .addText(
                    fc?.let { "${WidgetRenderer.deg(it.current.temp)} · ${WeatherCodes.text(it.current.code, it.current.isDay)}" }
                        ?: "No weather yet",
                )
                .setOnClickListener {
                    CarPlace.select(carContext, p.key)
                    screenManager.pop()
                }
            fc?.let { row.setImage(CarIcons.of(carContext, WeatherCodes.icon(it.current.code, it.current.isDay))) }
            list.addItem(row.build())
        }
        list.setNoItemsMessage("No places – set them up in JaWa on your phone")
        return ListTemplate.Builder()
            .setHeader(Header.Builder().setStartHeaderAction(Action.BACK).setTitle("Places").build())
            .setSingleList(list.build())
            .build()
    }
}
