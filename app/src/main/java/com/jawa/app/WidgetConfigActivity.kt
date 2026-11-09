package com.jawa.app

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Shown by the launcher when the user places a big widget. Lets them pick which
 * place the widget should show. If dismissed without picking, the widget is not
 * placed (standard Android behaviour: must call setResult(RESULT_OK) to confirm).
 */
class WidgetConfigActivity : Activity() {

    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Always cancel by default — only confirmed on explicit place pick.
        setResult(RESULT_CANCELED)

        widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }

        val places = Places.all(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(16)
            setPadding(pad, dp(24), pad, pad)
        }

        root.addView(TextView(this).apply {
            text = "Show weather for"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(16))
        })

        if (places.isEmpty()) {
            root.addView(TextView(this).apply {
                text = "No places yet — add some in Settings first."
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                alpha = 0.7f
            })
        } else {
            places.forEach { place ->
                root.addView(placeRow(place))
            }
        }

        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)
    }

    private fun placeRow(place: PlaceRef): View = TextView(this).apply {
        val label = when {
            place.isCurrent -> "📍 Here · ${place.name}"
            place.isHome -> "🏠 ${place.name}"
            else -> place.name
        }
        text = label
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        gravity = Gravity.CENTER_VERTICAL
        val v = dp(16); val h = dp(4)
        setPadding(h, v, h, v)
        isClickable = true
        setBackgroundResource(TypedValue().also {
            theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
        }.resourceId)
        setOnClickListener { confirm(place.key) }
    }

    private fun confirm(key: String) {
        Store.setWidgetPlace(this, widgetId, key)
        val mgr = AppWidgetManager.getInstance(this)
        // Render immediately so the widget isn't blank when the launcher shows it.
        WeatherWidgetProvider.renderWidget(this, mgr, widgetId)
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        finish()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
