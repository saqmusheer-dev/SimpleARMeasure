package com.saqmusheer.simplearmeasure

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View

class MeasurementOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFC107.toInt()
        strokeWidth = 6f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        setShadowLayer(8f, 0f, 0f, 0xAA000000.toInt())
    }

    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.FILL
        setShadowLayer(8f, 0f, 0f, 0xCC000000.toInt())
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textSize = 18f * resources.displayMetrics.density
        typeface = Typeface.DEFAULT_BOLD
        setShadowLayer(5f, 0f, 0f, 0xFF000000.toInt())
    }

    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        strokeWidth = 3f
        style = Paint.Style.STROKE
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(12f, 10f), 0f)
    }

    private var firstX = -1f
    private var firstY = -1f
    private var secondX = -1f
    private var secondY = -1f
    private var heightMode = false
    private var showHeightGuide = false

    fun setFirstPoint(x: Float, y: Float, heightMode: Boolean) {
        firstX = x
        firstY = y
        secondX = -1f
        secondY = -1f
        this.heightMode = heightMode
        showHeightGuide = heightMode
        invalidate()
    }

    fun setSecondPoint(x: Float, y: Float) {
        secondX = x
        secondY = y
        showHeightGuide = false
        invalidate()
    }

    fun clear() {
        firstX = -1f
        firstY = -1f
        secondX = -1f
        secondY = -1f
        showHeightGuide = false
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (firstX < 0f) return

        canvas.drawCircle(firstX, firstY, 12f, pointPaint)
        canvas.drawText("A", firstX + 15f, firstY - 12f, labelPaint)

        if (secondX >= 0f) {
            canvas.drawCircle(secondX, secondY, 12f, pointPaint)
            canvas.drawText("B", secondX + 15f, secondY - 12f, labelPaint)

            canvas.drawLine(firstX, firstY, secondX, secondY, linePaint)
        } else if (showHeightGuide) {
            val bottom = firstY
            val top = (bottom - height * 0.38f).coerceAtLeast(80f)
            val path = Path()
            path.moveTo(firstX, bottom)
            path.lineTo(firstX, top)
            canvas.drawPath(path, guidePaint)

            canvas.drawLine(firstX - 20f, bottom, firstX + 20f, bottom, guidePaint)
            canvas.drawLine(firstX - 20f, top, firstX + 20f, top, guidePaint)
            canvas.drawText("ALIGN TOP", firstX + 26f, top + 8f, labelPaint)
            canvas.drawText("FEET", firstX + 26f, bottom - 8f, labelPaint)
        }
    }
}
