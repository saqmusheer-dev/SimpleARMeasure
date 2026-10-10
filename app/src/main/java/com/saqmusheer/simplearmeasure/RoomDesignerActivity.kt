package com.saqmusheer.simplearmeasure

import android.app.AlertDialog
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/**
 * Lightweight, offline room-design demo workspace.
 * This is a 2.5D design prototype, not a physically accurate 3D renderer.
 * Room dimensions and furniture positions are editable and saved locally.
 */
class RoomDesignerActivity : AppCompatActivity() {
    private lateinit var roomView: RoomCanvasView
    private lateinit var info: TextView
    private val prefs by lazy { getSharedPreferences("room_designer_demo", MODE_PRIVATE) }
    private var roomWidth = 4.0f
    private var roomLength = 5.0f
    private var roomHeight = 2.7f
    private var wallColor = Color.rgb(232, 238, 242)
    private var floorColor = Color.rgb(190, 170, 142)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        restoreDesign()
        buildUi()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(12, 28, 43))
            setPadding(dp(14), dp(12), dp(14), dp(10))
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val heading = TextView(this).apply {
            text = "Room Studio\nBUILD • DECORATE • PREVIEW"
            textSize = 19f
            setTextColor(Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        header.addView(heading, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(makeButton("SAVE") { saveDesign(); toast("Design saved on this device") })
        root.addView(header)
        info = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.rgb(180, 205, 222))
            setPadding(0, dp(8), 0, dp(8))
        }
        root.addView(info)
        roomView = RoomCanvasView()
        root.addView(roomView, LinearLayout.LayoutParams(-1, 0, 1f))
        val scroll = HorizontalScrollViewCompat(this)
        val tools = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        listOf("Room size", "Bed", "Sofa", "Wardrobe", "Table").forEach { label ->
            row1.addView(makeButton(label) {
                when (label) {
                    "Room size" -> editRoomSize()
                    else -> { roomView.addFurniture(label); refreshInfo() }
                }
            }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) })
        }
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        listOf("Chair", "Plant", "Rug", "Wall colour", "Floor colour").forEach { label ->
            row2.addView(makeButton(label) {
                when (label) {
                    "Wall colour" -> cycleWall()
                    "Floor colour" -> cycleFloor()
                    else -> { roomView.addFurniture(label); refreshInfo() }
                }
            }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) })
        }
        val row3 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        listOf("Rotate", "Resize", "Delete", "Undo", "Reset").forEach { label ->
            row3.addView(makeButton(label) {
                when (label) {
                    "Rotate" -> roomView.transformSelected(rotate = true)
                    "Resize" -> roomView.transformSelected(rotate = false)
                    "Delete" -> roomView.deleteSelected()
                    "Undo" -> roomView.undo()
                    "Reset" -> confirmReset()
                }
                refreshInfo()
            }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) })
        }
        tools.addView(row1); tools.addView(row2); tools.addView(row3)
        scroll.addView(tools)
        root.addView(scroll)
        val hint = TextView(this).apply {
            text = "Tap an item to select • Drag to move • Rotate/Resize selected item • Demo preview (2.5D)"
            textSize = 11f
            setTextColor(Color.rgb(164, 190, 208))
            setPadding(dp(3), dp(7), dp(3), 0)
        }
        root.addView(hint)
        setContentView(root)
        refreshInfo()
    }

    private fun makeButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        textSize = 10f
        isAllCaps = false
        setTextColor(Color.WHITE)
        backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(37, 99, 235))
        setOnClickListener { action() }
    }

    private fun editRoomSize() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val w = EditText(this).apply { hint = "Width in metres"; setText(roomWidth.toString()); inputType = 8194 }
        val l = EditText(this).apply { hint = "Length in metres"; setText(roomLength.toString()); inputType = 8194 }
        val h = EditText(this).apply { hint = "Height in metres"; setText(roomHeight.toString()); inputType = 8194 }
        box.addView(w); box.addView(l); box.addView(h)
        AlertDialog.Builder(this).setTitle("Room dimensions (metres)").setView(box)
            .setPositiveButton("Apply") { _, _ ->
                roomWidth = w.text.toString().toFloatOrNull()?.coerceIn(1.5f, 20f) ?: roomWidth
                roomLength = l.text.toString().toFloatOrNull()?.coerceIn(1.5f, 30f) ?: roomLength
                roomHeight = h.text.toString().toFloatOrNull()?.coerceIn(2f, 6f) ?: roomHeight
                roomView.invalidate(); refreshInfo()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun cycleWall() {
        wallColor = when (wallColor) {
            Color.rgb(232, 238, 242) -> Color.rgb(222, 205, 184)
            Color.rgb(222, 205, 184) -> Color.rgb(202, 220, 207)
            else -> Color.rgb(232, 238, 242)
        }
        roomView.invalidate()
    }

    private fun cycleFloor() {
        floorColor = when (floorColor) {
            Color.rgb(190, 170, 142) -> Color.rgb(125, 137, 145)
            Color.rgb(125, 137, 145) -> Color.rgb(180, 190, 150)
            else -> Color.rgb(190, 170, 142)
        }
        roomView.invalidate()
    }

    private fun confirmReset() {
        AlertDialog.Builder(this).setMessage("Clear the furniture and start this room again?")
            .setNegativeButton("Cancel", null).setPositiveButton("Reset") { _, _ ->
                roomView.clearFurniture(); refreshInfo()
            }.show()
    }

    private fun refreshInfo() {
        info.text = String.format(java.util.Locale.US, "ROOM  %.1f × %.1f m  •  HEIGHT %.1f m  •  %d ITEMS",
            roomWidth, roomLength, roomHeight, if (::roomView.isInitialized) roomView.items.size else 0)
    }

    private fun saveDesign() {
        val json = JSONArray()
        roomView.items.forEach { json.put(JSONObject().put("name", it.name).put("x", it.x).put("y", it.y).put("scale", it.scale).put("rotation", it.rotation)) }
        prefs.edit().putString("items", json.toString())
            .putFloat("width", roomWidth).putFloat("length", roomLength).putFloat("height", roomHeight)
            .putInt("wall", wallColor).putInt("floor", floorColor).apply()
    }

    private fun restoreDesign() {
        roomWidth = prefs.getFloat("width", roomWidth); roomLength = prefs.getFloat("length", roomLength)
        roomHeight = prefs.getFloat("height", roomHeight); wallColor = prefs.getInt("wall", wallColor)
        floorColor = prefs.getInt("floor", floorColor)
    }

    private fun toast(message: String) = android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private inner class RoomCanvasView : View(this@RoomDesignerActivity) {
        data class Furniture(val name: String, var x: Float, var y: Float, var scale: Float = 1f, var rotation: Float = 0f)
        val items = mutableListOf<Furniture>()
        private val history = mutableListOf<List<Furniture>>()
        private var selected = -1
        private var lastX = 0f
        private var lastY = 0f
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        init {
            val raw = prefs.getString("items", null)
            if (raw != null) try {
                val a = JSONArray(raw)
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i)
                    items.add(Furniture(o.optString("name", "Sofa"), o.optDouble("x", .5).toFloat(),
                        o.optDouble("y", .5).toFloat(), o.optDouble("scale", 1.0).toFloat(), o.optDouble("rotation", 0.0).toFloat()))
                }
            } catch (_: Exception) { }
        }

        fun addFurniture(name: String) {
            snapshot()
            items.add(Furniture(name, .5f + (items.size % 3 - 1) * .12f, .48f + (items.size % 4 - 1.5f) * .08f))
            selected = items.lastIndex; invalidate()
        }
        fun transformSelected(rotate: Boolean) {
            if (selected !in items.indices) { toast("Select a furniture item first"); return }
            snapshot()
            if (rotate) items[selected].rotation = (items[selected].rotation + 15f) % 360f
            else items[selected].scale = (items[selected].scale + .15f).coerceAtMost(1.8f)
            invalidate()
        }
        fun deleteSelected() { if (selected in items.indices) { snapshot(); items.removeAt(selected); selected = -1; invalidate() } }
        fun clearFurniture() { snapshot(); items.clear(); selected = -1; invalidate() }
        fun undo() { if (history.isNotEmpty()) { items.clear(); items.addAll(history.removeAt(history.lastIndex)); selected = -1; invalidate() } }
        private fun snapshot() { history.add(items.map { it.copy() }); if (history.size > 20) history.removeAt(0) }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawColor(Color.rgb(17, 37, 54))
            val s = resources.displayMetrics.density
            val roomW = width * .78f
            val roomH = height * .68f
            val left = (width - roomW) / 2f
            val top = height * .12f
            val dx = roomW * .18f
            val dy = roomH * .16f
            val backLeft = left + dx
            val backTop = top
            val backRight = left + roomW
            val floorFront = top + roomH
            val wall = Path().apply { moveTo(backLeft, backTop); lineTo(backRight, backTop); lineTo(backRight, floorFront - dy); lineTo(backLeft, floorFront); close() }
            paint.color = wallColor; canvas.drawPath(wall, paint)
            val side = Path().apply { moveTo(backRight, backTop); lineTo(backRight + dx, backTop + dy); lineTo(backRight + dx, floorFront); lineTo(backRight, floorFront - dy); close() }
            paint.color = shade(wallColor, .78f); canvas.drawPath(side, paint)
            val floor = Path().apply { moveTo(backLeft, floorFront); lineTo(backRight, floorFront - dy); lineTo(backRight + dx, floorFront); lineTo(backLeft + dx, floorFront + dy); close() }
            paint.color = floorColor; canvas.drawPath(floor, paint)
            paint.color = Color.argb(70, 30, 50, 60); paint.strokeWidth = 1f * s; paint.style = Paint.Style.STROKE
            for (i in 1..5) {
                val t = i / 6f
                canvas.drawLine(backLeft + dx * t, floorFront + dy * t, backRight + dx * t, floorFront - dy + dy * t, paint)
            }
            paint.style = Paint.Style.FILL
            items.forEachIndexed { i, item ->
                val px = backLeft + roomW * item.x
                val py = floorFront - roomH * item.y
                canvas.save()
                canvas.rotate(item.rotation, px, py)
                val rw = roomW * .13f * item.scale
                val rh = roomH * .09f * item.scale
                paint.color = furnitureColor(item.name)
                val rect = RectF(px - rw, py - rh, px + rw, py + rh * .55f)
                canvas.drawRoundRect(rect, 10f * s, 10f * s, paint)
                paint.color = shade(furnitureColor(item.name), .72f)
                canvas.drawRect(px - rw, py + rh * .55f, px + rw, py + rh, paint)
                paint.color = Color.WHITE; paint.textSize = 11f * s; paint.textAlign = Paint.Align.CENTER
                canvas.drawText(item.name, px, py + rh + 14f * s, paint)
                if (i == selected) {
                    paint.color = Color.rgb(52, 211, 153); paint.style = Paint.Style.STROKE; paint.strokeWidth = 2f * s
                    canvas.drawRoundRect(RectF(px - rw - 4*s, py - rh - 4*s, px + rw + 4*s, py + rh + 4*s), 10*s, 10*s, paint)
                    paint.style = Paint.Style.FILL
                }
                canvas.restore()
            }
            paint.color = Color.argb(220, 255, 255, 255); paint.textSize = 12f * s; paint.textAlign = Paint.Align.CENTER
            canvas.drawText(String.format(java.util.Locale.US, "%.1f m × %.1f m", roomWidth, roomLength), width / 2f, height - 12f * s, paint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val roomW = width * .78f
            val roomH = height * .68f
            val left = (width - roomW) / 2f + roomW * .18f
            val top = height * .12f
            val floorFront = top + roomH
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.x; lastY = event.y
                    selected = items.indices.minByOrNull { i ->
                        val px = left + roomW * items[i].x; val py = floorFront - roomH * items[i].y
                        abs(event.x - px) + abs(event.y - py)
                    }?.takeIf { i ->
                        val px = left + roomW * items[i].x; val py = floorFront - roomH * items[i].y
                        abs(event.x - px) < roomW * .17f && abs(event.y - py) < roomH * .16f
                    } ?: -1
                    if (selected >= 0) snapshot()
                    invalidate(); return true
                }
                MotionEvent.ACTION_MOVE -> if (selected in items.indices) {
                    items[selected].x = (items[selected].x + (event.x - lastX) / roomW).coerceIn(.08f, .9f)
                    items[selected].y = (items[selected].y - (event.y - lastY) / roomH).coerceIn(.08f, .9f)
                    lastX = event.x; lastY = event.y; invalidate(); return true
                }
                MotionEvent.ACTION_UP -> { refreshInfo(); return true }
            }
            return true
        }

        private fun furnitureColor(name: String) = when (name) {
            "Bed" -> Color.rgb(78, 125, 180)
            "Sofa" -> Color.rgb(43, 143, 125)
            "Wardrobe" -> Color.rgb(143, 103, 73)
            "Table" -> Color.rgb(176, 125, 72)
            "Chair" -> Color.rgb(200, 130, 70)
            "Plant" -> Color.rgb(70, 155, 93)
            else -> Color.rgb(184, 120, 142)
        }
        private fun shade(color: Int, factor: Float): Int = Color.rgb(
            (Color.red(color) * factor).toInt(), (Color.green(color) * factor).toInt(), (Color.blue(color) * factor).toInt())
    }

    private class HorizontalScrollViewCompat(context: android.content.Context) : android.widget.HorizontalScrollView(context) {
        init { isHorizontalScrollBarEnabled = false }
    }
}
