package com.jawa.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

/** Builds the widget's views from the cached forecast. */
object WidgetRenderer {
    // The widget shows current weather + next days; the header grows to fill the
    // widget by itself (auto-sizing text in the layout). Launchers often under-report
    // the widget size, so thresholds are kept low: only a really narrow widget drops to
    // 4 days, and only a 1-row widget drops the days.
    private const val HEIGHT_FOR_DAYS = 110   // ~2 rows
    private const val WIDTH_FOR_5 = 230

    // Sizes offered to the launcher on Android 12+ (it uses the largest that fits).
    private val RESPONSIVE_WIDTHS = listOf(150, WIDTH_FOR_5)
    private val RESPONSIVE_HEIGHTS = listOf(60, HEIGHT_FOR_DAYS)

    private val DAY_SLOTS = listOf(R.id.day_slot_0, R.id.day_slot_1, R.id.day_slot_2, R.id.day_slot_3, R.id.day_slot_4)
    private val DAY_LABELS = listOf(R.id.day_label_0, R.id.day_label_1, R.id.day_label_2, R.id.day_label_3, R.id.day_label_4)
    private val DAY_ICONS = listOf(R.id.day_icon_0, R.id.day_icon_1, R.id.day_icon_2, R.id.day_icon_3, R.id.day_icon_4)
    private val DAY_TEMPS = listOf(R.id.day_temp_0, R.id.day_temp_1, R.id.day_temp_2, R.id.day_temp_3, R.id.day_temp_4)

    /** What one widget should show, and where its taps go. */
    class Target(
        val place: PlaceRef?,
        val index: Int,
        val count: Int,
        val onTap: PendingIntent,
        val onPrev: PendingIntent,
        val onNext: PendingIntent,
    )

    /**
     * On Android 12+ we hand the launcher one layout per size and it picks the one
     * that fits, so nothing depends on guessing the size. Older Android: guess from options.
     */
    fun build(ctx: Context, options: Bundle, target: Target, layout: Int): RemoteViews {
        val snap = Store.snapshot(ctx)
        val fc = target.place?.let { Store.forecast(ctx, it.key) }
        if (Build.VERSION.SDK_INT >= 31) {
            val map = LinkedHashMap<SizeF, RemoteViews>()
            for (w in RESPONSIVE_WIDTHS) for (h in RESPONSIVE_HEIGHTS) {
                map[SizeF(w.toFloat(), h.toFloat())] = buildFor(ctx, w, h, target, snap, fc, layout)
            }
            return RemoteViews(map)
        }
        // In portrait the launcher reports width as MIN_WIDTH and height as MAX_HEIGHT.
        val heightDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0)
        val widthDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
        return buildFor(ctx, widthDp, heightDp, target, snap, fc, layout)
    }

    private fun buildFor(
        ctx: Context,
        widthDp: Int,
        heightDp: Int,
        target: Target,
        snap: Store.Snapshot,
        fc: Forecast?,
        layout: Int,
    ): RemoteViews {
        val v = RemoteViews(ctx.packageName, layout)
        v.setOnClickPendingIntent(R.id.widget_root, target.onTap)

        // ‹ › and dots only when there's more than one place
        val many = target.count > 1
        v.setViewVisibility(R.id.nav_row, if (many) View.VISIBLE else View.GONE)
        if (many) {
            v.setOnClickPendingIntent(R.id.prev, target.onPrev)
            v.setOnClickPendingIntent(R.id.next, target.onNext)
            v.setTextViewText(R.id.dots, dots(target.count, target.index))
        }

        val showDays = heightDp == 0 || heightDp >= HEIGHT_FOR_DAYS
        val slots = if (widthDp in 1 until WIDTH_FOR_5) 4 else 5

        val placeLabel = target.place?.let { (if (it.isCurrent && many) "📍 " else "") + it.name }
        val marker = when {
            snap.updating -> " ⟳"
            snap.error != null -> " ⚠"
            else -> ""
        }

        if (fc == null) {
            v.setImageViewResource(R.id.current_icon, R.drawable.wx_partly_day)
            v.setTextViewText(R.id.current_temp, "--°")
            v.setTextViewText(R.id.place, (placeLabel ?: ctx.getString(R.string.widget_name)) + marker)
            v.setTextViewText(R.id.description, if (snap.updating) "Updating…" else snap.error ?: "Tap to load")
            v.setTextViewText(R.id.details, "")
            v.setViewVisibility(R.id.days_row, View.GONE)
            return v
        }

        val c = fc.current
        v.setImageViewResource(R.id.current_icon, WeatherCodes.icon(c.code, c.isDay))
        v.setTextViewText(R.id.current_temp, deg(c.temp))
        v.setTextViewText(R.id.place, (placeLabel ?: "—") + marker)
        v.setTextViewText(R.id.description, WeatherCodes.text(c.code))
        val today = fc.days.firstOrNull()
        v.setTextViewText(R.id.details, if (today != null) "${deg(today.max)} / ${deg(today.min)}" else "")

        // Next days
        v.setViewVisibility(R.id.days_row, if (showDays) View.VISIBLE else View.GONE)
        for (i in DAY_SLOTS.indices) {
            val day = fc.days.getOrNull(i)
            if (i >= slots || day == null) {
                v.setViewVisibility(DAY_SLOTS[i], View.GONE)
                continue
            }
            v.setViewVisibility(DAY_SLOTS[i], View.VISIBLE)
            v.setTextViewText(DAY_LABELS[i], dayLabel(day.date, i))
            v.setImageViewResource(DAY_ICONS[i], WeatherCodes.icon(day.code, true))
            v.setTextViewText(DAY_TEMPS[i], "${deg(day.max)} ${deg(day.min)}")
        }

        return v
    }

    /** "━ • • •" with the current place highlighted. */
    fun dots(count: Int, active: Int): CharSequence {
        val sb = SpannableStringBuilder()
        for (i in 0 until count) {
            if (i > 0) sb.append("  ")
            val start = sb.length
            sb.append(if (i == active) "━" else "•")
            if (i != active) {
                sb.setSpan(ForegroundColorSpan(0x73FFFFFF), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return sb
    }

    fun deg(t: Double): String {
        val r = t.roundToInt()
        return "${if (r == 0) 0 else r}°" // avoid "-0°"
    }

    fun dayLabel(date: String, index: Int): String {
        if (index == 0) return "Today"
        return runCatching {
            LocalDate.parse(date).dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
        }.getOrDefault(date.takeLast(5))
    }
}
