package com.jawa.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.View

/**
 * Low–high temperature bar. Every row shares the same scale (rangeMin..rangeMax),
 * so warmer and colder days line up visually. Colour follows the temperature:
 * blue when cold, yellow when mild, orange when warm.
 */
class TempBarView(ctx: Context) : View(ctx) {
    var low = 0.0
    var high = 0.0
    var rangeMin = 0.0
    var rangeMax = 1.0

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(26, 255, 255, 255) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    fun set(low: Double, high: Double, rangeMin: Double, rangeMax: Double) {
        this.low = low
        this.high = high
        this.rangeMin = rangeMin
        this.rangeMax = if (rangeMax - rangeMin < 1) rangeMin + 1 else rangeMax
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = h / 2
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, r, r, track)

        val span = rangeMax - rangeMin
        val x0 = ((low - rangeMin) / span * w).toFloat()
        val x1 = ((high - rangeMin) / span * w).toFloat().coerceAtLeast(x0 + h)
        fill.shader = LinearGradient(0f, 0f, w, 0f, colorsAcross(), null, Shader.TileMode.CLAMP)
        rect.set(x0, 0f, x1, h)
        canvas.drawRoundRect(rect, r, r, fill)
    }

    /** Colours sampled across the whole scale so the gradient matches real temperatures. */
    private fun colorsAcross(): IntArray =
        IntArray(5) { i -> colorFor(rangeMin + (rangeMax - rangeMin) * i / 4.0) }

    companion object {
        private val STOPS = doubleArrayOf(-5.0, 5.0, 14.0, 22.0, 30.0)
        private val COLORS = intArrayOf(
            Color.rgb(0x8E, 0xA8, 0xFF), // freezing
            Color.rgb(0x6F, 0xB7, 0xFF), // cold
            Color.rgb(0xFF, 0xD1, 0x66), // mild
            Color.rgb(0xFF, 0x9F, 0x5A), // warm
            Color.rgb(0xFF, 0x6B, 0x4A), // hot
        )

        fun colorFor(t: Double): Int {
            if (t <= STOPS.first()) return COLORS.first()
            if (t >= STOPS.last()) return COLORS.last()
            val i = STOPS.indexOfFirst { it >= t }
            val f = ((t - STOPS[i - 1]) / (STOPS[i] - STOPS[i - 1])).toFloat()
            return blend(COLORS[i - 1], COLORS[i], f)
        }

        private fun blend(a: Int, b: Int, f: Float): Int = Color.rgb(
            (Color.red(a) + (Color.red(b) - Color.red(a)) * f).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * f).toInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * f).toInt(),
        )
    }
}
