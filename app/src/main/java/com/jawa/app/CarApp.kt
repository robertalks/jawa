package com.jawa.app

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.Typeface
import androidx.car.app.AppManager
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.SessionInfo
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.MapWithContentTemplate
import androidx.car.app.validation.HostValidator
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.min

/**
 * JaWa in Android Auto (a "weather" category car app).
 *
 * Main screen: JaWa draws the big area itself (weather icon, large temperature, place),
 * with a small Android Auto panel on the side for today, tomorrow and the moon.
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

class CarMainScreen(ctx: CarContext) : Screen(ctx), SurfaceCallback {
    private var surface: SurfaceContainer? = null
    private var visibleArea: Rect? = null

    init {
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(this)
        refreshOnChanges {
            invalidate()
            draw()
        }
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

        val content = PaneTemplate.Builder(pane.build())
            .setHeader(
                Header.Builder()
                    .setStartHeaderAction(Action.APP_ICON)
                    .setTitle(place?.let { CarPlace.label(it) } ?: "JaWa")
                    .build(),
            )
            .build()
        return MapWithContentTemplate.Builder().setContentTemplate(content).build()
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

    // --- the big area JaWa draws itself

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        surface = surfaceContainer
        draw()
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        this.visibleArea = visibleArea
        draw()
    }

    override fun onStableAreaChanged(stableArea: Rect) {
        if (visibleArea == null) visibleArea = stableArea
        draw()
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        surface = null
    }

    private fun draw() {
        val container = surface ?: return
        val s = container.surface ?: return
        if (!s.isValid) return
        val canvas = try {
            s.lockCanvas(null)
        } catch (e: Exception) {
            return
        } ?: return
        try {
            val area = visibleArea ?: Rect(0, 0, container.width, container.height)
            val place = CarPlace.selected(carContext)
            CarDashboard.draw(
                carContext, canvas, container.width, container.height, area,
                place, place?.let { Store.forecast(carContext, it.key) }, container.dpi,
            )
        } finally {
            s.unlockCanvasAndPost(canvas)
        }
    }
}

/** Draws the big weather area: icon, large bold temperature, place and sky, centred in [area]. */
object CarDashboard {
    fun draw(ctx: Context, c: Canvas, w: Int, h: Int, area: Rect, place: PlaceRef?, fc: Forecast?, dpi: Int) {
        // Background: the same night-blue gradient as the phone's full view
        val bg = Paint().apply {
            shader = LinearGradient(0f, 0f, w * 0.4f, h.toFloat(), 0xFF1D2A44.toInt(), 0xFF10141C.toInt(), Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bg)
        if (area.width() <= 0 || area.height() <= 0) return

        val density = (if (dpi > 0) dpi else 160) / 160f
        val pad = 16 * density
        val aw = area.width() - 2 * pad
        val ah = area.height() - 2 * pad

        val tempText = fc?.let { WidgetRenderer.deg(it.current.temp) } ?: "--°"
        val iconRes = fc?.let { WeatherCodes.icon(it.current.code, it.current.isDay) } ?: R.drawable.wx_partly_day
        val sky = fc?.let {
            WeatherCodes.text(it.current.code, it.current.isDay) + " · feels " + WidgetRenderer.deg(it.current.feelsLike)
        } ?: "Updating…"
        val name = place?.let { CarPlace.label(it) } ?: "JaWa"

        // Sizes follow the visible area, so it scales with bigger car screens
        val iconSize = min(ah * 0.8f, aw * 0.4f)
        val temp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = ah * 0.55f
        }
        val maxTextWidth = aw - iconSize - pad
        while (temp.measureText(tempText) > maxTextWidth && temp.textSize > 24 * density) temp.textSize *= 0.95f
        val placePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = (temp.textSize * 0.17f).coerceAtLeast(16 * density)
        }
        val skyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xBFFFFFFF.toInt()
            textSize = placePaint.textSize * 0.8f
        }

        val textWidth = maxOf(temp.measureText(tempText), placePaint.measureText(name), skyPaint.measureText(sky))
        val groupWidth = iconSize + pad + textWidth
        val left = area.left + pad + (aw - groupWidth).coerceAtLeast(0f) / 2
        val centerY = area.exactCenterY()

        ContextCompat.getDrawable(ctx, iconRes)?.let { d ->
            val top = (centerY - iconSize / 2).toInt()
            d.setBounds(left.toInt(), top, (left + iconSize).toInt(), (top + iconSize).toInt())
            d.draw(c)
        }

        val tx = left + iconSize + pad
        val tempAscent = -temp.fontMetrics.ascent * 0.75f // roughly the digits' height
        val blockHeight = tempAscent + placePaint.textSize * 1.4f + skyPaint.textSize * 1.3f
        var y = centerY - blockHeight / 2 + tempAscent
        c.drawText(tempText, tx, y, temp)
        y += placePaint.textSize * 1.4f
        c.drawText(name, tx, y, placePaint)
        y += skyPaint.textSize * 1.3f
        c.drawText(sky, tx, y, skyPaint)
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
