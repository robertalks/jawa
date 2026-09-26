package com.jawa.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.DateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Full forecast for one place at a time: current conditions, next 24 hours and 14 days. */
class DetailActivity : Activity() {

    private lateinit var hoursContainer: LinearLayout
    private lateinit var daysContainer: LinearLayout
    private lateinit var pillsContainer: LinearLayout
    private lateinit var pillsScroll: HorizontalScrollView
    private lateinit var hoursScroll: View
    private lateinit var swipe: GestureDetector

    /** Key of the place on screen ("cur" or a saved place id). */
    private var selectedKey: String? = null
    private var touchStartedInHours = false

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_detail)
        hoursContainer = findViewById(R.id.hours_container)
        daysContainer = findViewById(R.id.days_container)
        pillsContainer = findViewById(R.id.pills_container)
        pillsScroll = findViewById(R.id.pills_scroll)
        hoursScroll = hoursContainer.parent as View

        findViewById<View>(R.id.place_title).setOnClickListener { finish() }
        findViewById<View>(R.id.updated).setOnClickListener { refresh() }
        findViewById<View>(R.id.settings).setOnClickListener { openSettings() }

        swipe = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                val dx = e2.x - (e1?.x ?: e2.x)
                val dy = e2.y - (e1?.y ?: e2.y)
                if (abs(dx) < dp(80) || abs(dx) < abs(dy) * 1.5f || abs(vx) < 400) return false
                step(if (dx < 0) 1 else -1) // swipe left → next place
                return true
            }
        })

        selectedKey = savedInstanceState?.getString(STATE_PLACE) ?: intent.getStringExtra(EXTRA_PLACE)
        WeatherWorker.schedulePeriodic(this)
        val nothingYet = Places.all(this).none { Store.hasForecast(this, it.key) }
        if (!LocationHelper.hasLocation(this) && Places.saved(this).isEmpty() && nothingYet) {
            // First run: location permission and places live in settings.
            openSettings()
        } else {
            maybeRefresh(intent)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_PLACE, selectedKey)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(EXTRA_PLACE)?.let { selectedKey = it }
        render()
        maybeRefresh(intent)
    }

    override fun onResume() {
        super.onResume()
        Store.prefs(this).registerOnSharedPreferenceChangeListener(prefsListener)
        render()
        WidgetUpdates.all(this) // keeps the widget in step with the app
    }

    override fun onPause() {
        Store.prefs(this).unregisterOnSharedPreferenceChangeListener(prefsListener)
        super.onPause()
    }

    /** Horizontal swipes anywhere change place (except on the sideways-scrolling strips). */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            val r = Rect()
            val x = ev.rawX.toInt()
            val y = ev.rawY.toInt()
            touchStartedInHours = (hoursScroll.getGlobalVisibleRect(r) && r.contains(x, y)) ||
                (pillsScroll.getGlobalVisibleRect(r) && r.contains(x, y))
        }
        if (!touchStartedInHours) swipe.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    private fun openSettings() = startActivity(Intent(this, MainActivity::class.java))

    /** Refresh when opened from the widget, or when the data is more than 10 minutes old. */
    private fun maybeRefresh(intent: Intent?) {
        val snap = Store.snapshot(this)
        val stale = System.currentTimeMillis() - snap.updatedMillis > 10 * 60 * 1000L
        if (intent?.getBooleanExtra(EXTRA_REFRESH, false) == true || stale) refresh()
    }

    private fun refresh() {
        WeatherWorker.refreshNow(this)
        WidgetUpdates.all(this)
    }

    private fun step(delta: Int) {
        val places = Places.all(this)
        if (places.size < 2) return
        val i = places.indexOfFirst { it.key == selectedKey }.coerceAtLeast(0)
        select(places[(i + delta + places.size) % places.size].key)
    }

    private fun select(key: String) {
        selectedKey = key
        render()
        findViewById<ScrollView>(R.id.detail_scroll).smoothScrollTo(0, 0)
    }

    private fun render() {
        val places = Places.all(this)
        val place = places.firstOrNull { it.key == selectedKey } ?: places.firstOrNull()
        selectedKey = place?.key
        val index = places.indexOf(place)
        val snap = Store.snapshot(this)
        val fc = place?.let { Store.forecast(this, it.key) }

        renderPills(places, place)
        renderHomeAction(place)
        renderMoonCard()
        findViewById<TextView>(R.id.detail_dots).apply {
            visibility = if (places.size > 1) View.VISIBLE else View.GONE
            text = WidgetRenderer.dots(places.size, index)
        }

        findViewById<TextView>(R.id.place_title).text = "←  ${place?.name ?: getString(R.string.app_name)}"
        findViewById<TextView>(R.id.updated).text = when {
            snap.updating -> "Updating…"
            snap.updatedMillis == 0L -> "Tap to update ⟳"
            else -> {
                val t = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(snap.updatedMillis))
                (if (snap.error != null) "⚠ " else "") + "Updated $t ⟳"
            }
        }

        if (fc == null) {
            // No data for this place yet (e.g. just added): show an empty state.
            findViewById<ImageView>(R.id.cur_icon).setImageResource(R.drawable.wx_cloudy)
            findViewById<TextView>(R.id.cur_temp).text = "--°"
            findViewById<TextView>(R.id.cur_desc).text = if (snap.updating) "Updating…" else "No data yet"
            findViewById<TextView>(R.id.cur_feels).text = if (snap.updating) "" else "Tap Updated to refresh"
            findViewById<TextView>(R.id.cur_hl).text = ""
            listOf(R.id.chip_wind, R.id.chip_rain, R.id.chip_sunrise, R.id.chip_sunset)
                .forEach { findViewById<TextView>(it).text = "–" }
            hoursContainer.removeAllViews()
            daysContainer.removeAllViews()
            return
        }

        val c = fc.current
        val today = fc.days.firstOrNull()
        findViewById<ImageView>(R.id.cur_icon).setImageResource(WeatherCodes.icon(c.code, c.isDay))
        findViewById<TextView>(R.id.cur_temp).text = WidgetRenderer.deg(c.temp)
        findViewById<TextView>(R.id.cur_desc).text = WeatherCodes.text(c.code, c.isDay)
        findViewById<TextView>(R.id.cur_feels).text = "Feels ${WidgetRenderer.deg(c.feelsLike)}"
        findViewById<TextView>(R.id.cur_hl).text =
            if (today != null) "${WidgetRenderer.deg(today.max)} / ${WidgetRenderer.deg(today.min)}" else ""

        findViewById<TextView>(R.id.chip_wind).text = "${c.windKmh.roundToInt()} km/h"
        findViewById<TextView>(R.id.chip_rain).text = if (today != null) mm(today.rainMm) else "–"
        findViewById<TextView>(R.id.chip_sunrise).text = today?.sunrise ?: "–"
        findViewById<TextView>(R.id.chip_sunset).text = today?.sunset ?: "–"

        renderHours(fc)
        renderDays(fc)
    }

    /** Tonight's moon: phase, how much is lit, and the next new or full moon. */
    private fun renderMoonCard() {
        val now = System.currentTimeMillis()
        val age = Moon.age(now)
        findViewById<ImageView>(R.id.moon_card_icon).setImageResource(MoonIcons.forAge(age))
        findViewById<TextView>(R.id.moon_card_name).text = Moon.name(age)
        findViewById<TextView>(R.id.moon_card_lit).text = "${(Moon.illumination(age) * 100).roundToInt()}% lit"
        val (label, at) = Moon.nextMainPhase(now)
        val whenText = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale.getDefault()))
        findViewById<TextView>(R.id.moon_card_next).text = "Next ${label.lowercase(Locale.getDefault())}: $whenText"
    }

    /**
     * On your current location: "🏠 Set as home" (or "🏠 Your home" if you're there).
     * On the home page: "🏠 Home · 118 km away". Nothing on saved places.
     */
    private fun renderHomeAction(place: PlaceRef?) {
        val v = findViewById<TextView>(R.id.home_action)
        val home = Places.home(this)
        val km = Places.kmFromHome(this)
        when {
            place == null -> v.visibility = View.GONE
            place.isHome -> homeBadge(
                v,
                if (km != null && Places.useCurrent(this)) "🏠 Home · ${km.roundToInt()} km away" else "🏠 Home",
            )
            place.isCurrent && home != null && km != null && km <= Places.HOME_RADIUS_KM -> homeBadge(v, "🏠 Your home")
            place.isCurrent && Store.lastLatLon(this) != null -> {
                v.visibility = View.VISIBLE
                v.text = "🏠 Set as home"
                v.setTextColor(Color.WHITE)
                v.setBackgroundResource(R.drawable.home_button_bg)
                v.setOnClickListener { setHomeHere() }
            }
            else -> v.visibility = View.GONE
        }
    }

    private fun homeBadge(v: TextView, text: String) {
        v.visibility = View.VISIBLE
        v.text = text
        v.setTextColor(Color.rgb(0xFF, 0xD2, 0x7A))
        v.setBackgroundResource(R.drawable.home_badge_bg)
        v.setOnClickListener(null)
        v.isClickable = false
    }

    /** Makes your current location home (asks first if it replaces another home). */
    private fun setHomeHere() {
        val here = Store.lastLatLon(this) ?: return
        val name = Store.currentPlaceName(this) ?: "Home"
        val apply = {
            Places.setHome(this, name, here.first, here.second)
            Toast.makeText(this, "Home set to $name", Toast.LENGTH_SHORT).show()
            render()
            WidgetUpdates.all(this)
        }
        val old = Places.home(this)
        if (old == null) {
            apply()
        } else {
            AlertDialog.Builder(this)
                .setTitle("Change home?")
                .setMessage("Your home is ${old.name}. Make $name your home instead?")
                .setPositiveButton("Change") { _, _ -> apply() }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    /** "📍 Here · Nepomuk", "🏠 Praha", "Wien" … "＋" */
    private fun renderPills(places: List<PlaceRef>, selected: PlaceRef?) {
        pillsContainer.removeAllViews()
        var selectedView: View? = null
        places.forEach { p ->
            val on = p.key == selected?.key
            val label = when {
                p.isCurrent -> "📍 Here · ${p.name}"
                p.isHome -> "🏠 ${p.name}"
                else -> p.name
            }
            val pill = pill(label, on).apply { setOnClickListener { select(p.key) } }
            if (on) selectedView = pill
            pillsContainer.addView(pill)
        }
        pillsContainer.addView(pill("＋", false).apply { setOnClickListener { openSettings() } })
        selectedView?.let { v -> pillsScroll.post { pillsScroll.smoothScrollTo((v.left - dp(16)).coerceAtLeast(0), 0) } }
    }

    private fun pill(label: String, on: Boolean): TextView = TextView(this).apply {
        text = label
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setTextColor(if (on) Color.rgb(0x14, 0x1A, 0x26) else Color.WHITE)
        if (on) setTypeface(typeface, Typeface.BOLD)
        setBackgroundResource(if (on) R.drawable.pill_on_bg else R.drawable.pill_bg)
        setPadding(dp(14), dp(7), dp(14), dp(7))
        maxLines = 1
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { marginEnd = dp(8) }
    }

    private fun renderHours(fc: Forecast) {
        hoursContainer.removeAllViews()
        val c = fc.current
        hoursContainer.addView(hourColumn("Now", WeatherCodes.icon(c.code, c.isDay), c.temp))
        fc.hours.take(24).forEach { h ->
            hoursContainer.addView(hourColumn(h.time.substring(11, 16), WeatherCodes.icon(h.code, h.isDay), h.temp))
        }
    }

    private fun hourColumn(label: String, icon: Int, temp: Double): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(dp(50), LinearLayout.LayoutParams.WRAP_CONTENT)
            addView(text(label, 11f, 0.7f))
            addView(ImageView(context).apply {
                setImageResource(icon)
                layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply { topMargin = dp(4); bottomMargin = dp(4) }
            })
            addView(text(WidgetRenderer.deg(temp), 14f))
        }

    private fun renderDays(fc: Forecast) {
        daysContainer.removeAllViews()
        val days = fc.days.take(14)
        if (days.isEmpty()) return
        val rangeMin = days.minOf { it.min }
        val rangeMax = days.maxOf { it.max }
        val dateFmt = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())

        days.forEachIndexed { i, d ->
            if (i == 7) {
                daysContainer.addView(divider())
                daysContainer.addView(text("Days 8–14 · less certain", 11f, 0.5f).apply {
                    setPadding(dp(4), dp(8), 0, dp(4))
                })
            } else if (i > 0) {
                daysContainer.addView(divider())
            }
            daysContainer.addView(dayRow(d, i, rangeMin, rangeMax, dateFmt).apply {
                if (i >= 7) alpha = 0.8f
            })
        }
    }

    private fun dayRow(d: Forecast.Day, index: Int, rangeMin: Double, rangeMax: Double, dateFmt: DateTimeFormatter): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(54))

            // Day name + date
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(dp(60), LinearLayout.LayoutParams.WRAP_CONTENT)
                addView(text(WidgetRenderer.dayLabel(d.date, index), 15f).apply { setTypeface(typeface, Typeface.BOLD) })
                val date = runCatching { LocalDate.parse(d.date).format(dateFmt) }.getOrDefault("")
                addView(text(date, 11f, 0.55f))
            })

            // That evening's moon
            addView(ImageView(context).apply {
                val evening = runCatching {
                    LocalDate.parse(d.date).atTime(21, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                }.getOrNull()
                if (evening != null) setImageResource(MoonIcons.forAge(Moon.age(evening)))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(8) }
            })

            addView(ImageView(context).apply {
                setImageResource(WeatherCodes.icon(d.code, true))
                layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginEnd = dp(8) }
            })

            // Rain chance + amount, only when rain is reasonably likely
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(dp(46), LinearLayout.LayoutParams.WRAP_CONTENT)
                if (d.rainChance >= 20) {
                    addView(text("${d.rainChance}%", 12f, color = RAIN))
                    if (d.rainMm > 0) addView(text(mm(d.rainMm), 10f, 0.8f, RAIN))
                }
            })

            addView(text(WidgetRenderer.deg(d.min), 14f, 0.6f).apply {
                gravity = Gravity.END
                layoutParams = LinearLayout.LayoutParams(dp(32), LinearLayout.LayoutParams.WRAP_CONTENT)
            })
            addView(TempBarView(context).apply {
                set(d.min, d.max, rangeMin, rangeMax)
                layoutParams = LinearLayout.LayoutParams(0, dp(6), 1f).apply {
                    marginStart = dp(8)
                    marginEnd = dp(8)
                }
            })
            addView(text(WidgetRenderer.deg(d.max), 14f).apply {
                layoutParams = LinearLayout.LayoutParams(dp(32), LinearLayout.LayoutParams.WRAP_CONTENT)
            })
        }

    private fun divider(): View = View(this).apply {
        setBackgroundColor(Color.argb(16, 255, 255, 255))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
    }

    private fun text(s: String, sp: Float, alpha: Float = 1f, color: Int = Color.WHITE): TextView =
        TextView(this).apply {
            text = s
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            setTextColor(color)
            this.alpha = alpha
            maxLines = 1
        }

    private fun mm(v: Double): String =
        if (v < 10) String.format(Locale.getDefault(), "%.1f mm", v).replace(Regex("[.,]0 "), " ")
        else "${v.roundToInt()} mm"

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

    companion object {
        const val EXTRA_REFRESH = "refresh"
        const val EXTRA_PLACE = "place"
        private const val STATE_PLACE = "place"
        private val RAIN = Color.rgb(0x7C, 0xC6, 0xFF)
    }
}
