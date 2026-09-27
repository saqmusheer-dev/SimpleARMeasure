package com.saqmusheer.simplearmeasure

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View

class RulerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        strokeWidth = 2f
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textSize = 11f * resources.displayMetrics.scaledDensity
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val density = resources.displayMetrics.density
        val left = width * 0.30f
        val top = 10f * density
        val bottom = height - 10f * density
        val usable = bottom - top

        canvas.drawLine(left, top, left, bottom, linePaint)

        val steps = 20
        for (i in 0..steps) {
            val y = top + usable * i / steps
            val major = i % 5 == 0
            val length = if (major) 24f else 13f

            linePaint.strokeWidth = if (major) 3f else 2f
            canvas.drawLine(left, y, left + length * density, y, linePaint)

            if (major) {
                val meters = 2.0f * (steps - i) / steps
                val label = if (meters == 0f) "0" else String.format("%.1f", meters)
                canvas.drawText(
                    label,
                    left + 28f * density,
                    y + 4f * density,
                    textPaint
                )
            }
        }
    }
}
