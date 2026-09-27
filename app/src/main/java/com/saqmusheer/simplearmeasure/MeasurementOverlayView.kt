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

    private val greenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF39FF88.toInt()
        strokeWidth = 5f
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

    private val areaPoints = mutableListOf<Pair<Float, Float>>()
    private val autoFloorPoints = mutableListOf<Pair<Float, Float>>()
    private val personOutline = mutableListOf<Pair<Float, Float>>()

    private var areaClosed = false
    private var firstX = -1f
    private var firstY = -1f
    private var secondX = -1f
    private var secondY = -1f
    private var showHeightGuide = false

    fun addAreaPoint(x: Float, y: Float) {
        areaPoints.add(x to y)
        areaClosed = false
        invalidate()
    }

    fun setAreaPoints(points: List<Pair<Float, Float>>, closed: Boolean = false) {
        areaPoints.clear()
        areaPoints.addAll(points)
        areaClosed = closed
        invalidate()
    }

    fun removeAreaPoint(index: Int) {
        if (index in areaPoints.indices) {
            areaPoints.removeAt(index)
            areaClosed = false
            invalidate()
        }
    }

    fun areaPointCount(): Int = areaPoints.size

    fun closeArea() {
        areaClosed = true
        invalidate()
    }

    fun setAutoFloorOutline(points: List<Pair<Float, Float>>) {
        autoFloorPoints.clear()
        autoFloorPoints.addAll(points)
        invalidate()
    }

    fun clearAutoFloorOutline() {
        if (autoFloorPoints.isNotEmpty()) {
            autoFloorPoints.clear()
            invalidate()
        }
    }

    fun setPersonOutline(points: List<Pair<Float, Float>>) {
        personOutline.clear()
        personOutline.addAll(points)
        invalidate()
    }

    fun clearPersonOutline() {
        personOutline.clear()
        invalidate()
    }

    fun setFirstPoint(x: Float, y: Float, heightMode: Boolean) {
        firstX = x
        firstY = y
        secondX = -1f
        secondY = -1f
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
        areaPoints.clear()
        areaClosed = false
        personOutline.clear()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (autoFloorPoints.size > 2) {
            val path = Path()
            autoFloorPoints.forEachIndexed { index, point ->
                if (index == 0) path.moveTo(point.first, point.second)
                else path.lineTo(point.first, point.second)
            }
            path.close()
            canvas.drawPath(path, greenPaint)
        }

        if (personOutline.size > 4) {
            val path = Path()
            val half = personOutline.size / 2
            personOutline.take(half).forEachIndexed { index, point ->
                if (index == 0) path.moveTo(point.first, point.second)
                else path.lineTo(point.first, point.second)
            }
            personOutline.drop(half).asReversed().forEach { point ->
                path.lineTo(point.first, point.second)
            }
            path.close()
            canvas.drawPath(path, greenPaint)
        }

        if (areaPoints.isNotEmpty()) {
            for (i in 0 until areaPoints.lastIndex) {
                val a = areaPoints[i]
                val b = areaPoints[i + 1]
                canvas.drawLine(a.first, a.second, b.first, b.second, linePaint)
            }
            if (areaClosed && areaPoints.size > 2) {
                val a = areaPoints.last()
                val b = areaPoints.first()
                canvas.drawLine(a.first, a.second, b.first, b.second, linePaint)
            }
            areaPoints.forEachIndexed { i, p ->
                canvas.drawCircle(p.first, p.second, 10f, pointPaint)
                canvas.drawText((i + 1).toString(), p.first + 13f, p.second - 10f, labelPaint)
            }
        }

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
            val path = Path().apply {
                moveTo(firstX, bottom)
                lineTo(firstX, top)
            }
            canvas.drawPath(path, guidePaint)
            canvas.drawLine(firstX - 20f, bottom, firstX + 20f, bottom, guidePaint)
            canvas.drawLine(firstX - 20f, top, firstX + 20f, top, guidePaint)
            canvas.drawText("ALIGN TOP", firstX + 26f, top + 8f, labelPaint)
            canvas.drawText("FEET", firstX + 26f, bottom - 8f, labelPaint)
        }
    }
}
