package com.saqmusheer.simplearmeasure

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.MotionEvent
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.ar.core.Anchor
import com.google.ar.core.Config
import com.google.ar.core.DepthPoint
import com.google.ar.core.Frame
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import com.google.ar.core.Point
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.ARSceneView
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

class MainActivity : AppCompatActivity() {

    private enum class MeasureMode(val label: String) {
        FLOOR("Floor"),
        HEIGHT("Height"),
        DIRECT("3D"),
        AREA("Area")
    }

    private lateinit var arSceneView: ARSceneView
    private lateinit var statusText: TextView
    private lateinit var distanceText: TextView
    private lateinit var modeText: TextView
    private lateinit var measurementOverlay: MeasurementOverlayView
    private lateinit var licenseManager: LicenseManager

    private var latestFrame: Frame? = null
    private var firstAnchor: Anchor? = null
    private var secondAnchor: Anchor? = null
    private var measureMode = MeasureMode.FLOOR
    private val areaAnchors = mutableListOf<Anchor>()

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startAr()
            else {
                statusText.text = "Camera permission is required."
                Toast.makeText(this, "Please allow camera access.", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        licenseManager = LicenseManager(this)
        if (!licenseManager.isLicensed()) { showLicenseScreen(); return }
        setupMainUi()
    }

    private fun setupMainUi() {
        setContentView(R.layout.activity_main)

        arSceneView = findViewById(R.id.arSceneView)
        statusText = findViewById(R.id.statusText)
        distanceText = findViewById(R.id.distanceText)
        modeText = findViewById(R.id.modeText)
        measurementOverlay = findViewById(R.id.measurementOverlay)

        findViewById<Button>(R.id.floorButton).setOnClickListener { selectMode(MeasureMode.FLOOR) }
        findViewById<Button>(R.id.heightButton).setOnClickListener { selectMode(MeasureMode.HEIGHT) }
        findViewById<Button>(R.id.directButton).setOnClickListener { selectMode(MeasureMode.DIRECT) }
        findViewById<Button>(R.id.areaButton).setOnClickListener { selectMode(MeasureMode.AREA) }
        findViewById<Button>(R.id.finishAreaButton).setOnClickListener { finishArea() }
        findViewById<Button>(R.id.resetButton).setOnClickListener { resetMeasurement() }

        selectMode(MeasureMode.FLOOR)

        if (hasCameraPermission()) startAr()
        else cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun showLicenseScreen() {
        val root = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL; gravity = android.view.Gravity.CENTER; setPadding(42,24,42,24); setBackgroundColor(0xFF151122.toInt()) }
        val title = TextView(this).apply { text = "Simple AR Measure"; textSize = 30f; setTextColor(0xFFFFFFFF.toInt()); typeface = android.graphics.Typeface.DEFAULT_BOLD; gravity = android.view.Gravity.CENTER }
        val subtitle = TextView(this).apply { text = "Enter your activation key"; textSize = 17f; setTextColor(0xFFD8D1E8.toInt()); gravity = android.view.Gravity.CENTER; setPadding(0,16,0,22) }
        val input = android.widget.EditText(this).apply { hint = "Activation key"; setSingleLine(true) }
        val activate = Button(this).apply { text = "ACTIVATE" }
        activate.setOnClickListener { val result = licenseManager.activate(input.text.toString()); if (result.first) setupMainUi() else Toast.makeText(this,result.second,Toast.LENGTH_LONG).show() }
        root.addView(title); root.addView(subtitle); root.addView(input,android.widget.LinearLayout.LayoutParams(-1,-2)); root.addView(activate,android.widget.LinearLayout.LayoutParams(-1,-2).apply{topMargin=18})
        setContentView(root)
    }

    private fun startAr() {
        statusText.text = "Floor mode • Tap the first point."
        arSceneView.lifecycle = lifecycle

        arSceneView.configureSession { session, config ->
            config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
            config.lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
            config.depthMode =
                if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                    Config.DepthMode.AUTOMATIC
                } else {
                    Config.DepthMode.DISABLED
                }
        }

        arSceneView.onSessionUpdated = { _, frame ->
            latestFrame = frame
            if (firstAnchor == null) {
                statusText.text = measureMode.label + " mode • Tap the first point."
            } else if (secondAnchor == null) {
                statusText.text = if (measureMode == MeasureMode.HEIGHT) {
                    "Point A = feet • Aim at the top of the head and tap."
                } else {
                    "Point A locked • Move to the second point and tap."
                }
            }
        }

        arSceneView.onSessionFailed = { exception ->
            statusText.text = "AR failed: " + (exception.message ?: "Unknown error")
        }

        arSceneView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                measureAt(event.x, event.y)
            }
            true
        }
    }

    private fun selectMode(mode: MeasureMode) {
        measureMode = mode
        modeText.text = mode.label.uppercase(Locale.US) + " MODE"

        val floor = findViewById<Button>(R.id.floorButton)
        val height = findViewById<Button>(R.id.heightButton)
        val direct = findViewById<Button>(R.id.directButton)

        floor.alpha = if (mode == MeasureMode.FLOOR) 1f else 0.60f
        height.alpha = if (mode == MeasureMode.HEIGHT) 1f else 0.60f
        direct.alpha = if (mode == MeasureMode.DIRECT) 1f else 0.60f
        findViewById<Button>(R.id.areaButton).alpha = if (mode == MeasureMode.AREA) 1f else 0.60f

        resetMeasurement()
    }

    private fun measureAt(x: Float, y: Float) {
        if (measureMode == MeasureMode.AREA) { measureAreaAt(x, y); return }

        if (measureMode == MeasureMode.AREA) { measureAreaAt(x, y); return }
        val frame = latestFrame ?: run {
            Toast.makeText(this, "AR is still starting. Try again.", Toast.LENGTH_SHORT).show()
            return
        }

        val hit = findBestHit(frame, x, y)
        if (hit == null) {
            Toast.makeText(
                this,
                if (measureMode == MeasureMode.HEIGHT)
                    "Aim at the feet or body and try again."
                else
                    "No measurable point here. Aim at the floor, wall or object and try again.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        if (firstAnchor == null) {
            firstAnchor = createAnchorSafely(hit)
            if (firstAnchor == null) {
                Toast.makeText(this, "Could not lock this point. Try again.", Toast.LENGTH_SHORT).show()
                return
            }

            measurementOverlay.setFirstPoint(x, y, measureMode == MeasureMode.HEIGHT)
            distanceText.text = "A"
            statusText.text = if (measureMode == MeasureMode.HEIGHT) {
                "Point A = feet • Aim at the top of the head and tap."
            } else {
                "Point A locked • Move to the second point and tap."
            }
            return
        }

        secondAnchor?.detach()
        secondAnchor = createAnchorSafely(hit)
        if (secondAnchor == null) {
            Toast.makeText(this, "Could not lock the second point. Try again.", Toast.LENGTH_SHORT).show()
            return
        }

        val a = firstAnchor?.pose ?: run { resetMeasurement(); return }
        val b = secondAnchor?.pose ?: run { resetMeasurement(); return }

        val dx = b.tx() - a.tx()
        val dy = b.ty() - a.ty()
        val dz = b.tz() - a.tz()

        val meters = when (measureMode) {
            MeasureMode.FLOOR -> sqrt(dx * dx + dz * dz)
            MeasureMode.HEIGHT -> abs(dy)
            MeasureMode.DIRECT -> sqrt(dx * dx + dy * dy + dz * dz)
        }

        measurementOverlay.setSecondPoint(x, y)
        showDistance(meters)
    }

    private fun measureAreaAt(x: Float, y: Float) {
        val frame = latestFrame ?: return
        val hit = frame.hitTest(x,y).firstOrNull { it.trackable?.trackingState == TrackingState.TRACKING && ((it.trackable as? Plane)?.type == Plane.Type.HORIZONTAL_UPWARD_FACING || it.trackable is DepthPoint || it.trackable is Point) } ?: run { Toast.makeText(this,"Aim at a kitchen/floor corner and tap.",Toast.LENGTH_SHORT).show(); return }
        createAnchorSafely(hit)?.let { areaAnchors.add(it); measurementOverlay.addAreaPoint(x,y); distanceText.text = areaAnchors.size.toString()+" points"; findViewById<Button>(R.id.finishAreaButton).visibility = if(areaAnchors.size>=3) android.view.View.VISIBLE else android.view.View.GONE; statusText.text = "Area outline • tap the next corner or Finish." }
    }

    private fun finishArea() {
        if (areaAnchors.size < 3) return
        val p = areaAnchors.map { it.pose }
        var area = 0f
        for (i in p.indices) { val j=(i+1)%p.size; area += p[i].tx()*p[j].tz()-p[j].tx()*p[i].tz() }
        area = abs(area)/2f
        measurementOverlay.closeArea()
        distanceText.text = String.format(Locale.US,"%.2f m²\\n%.1f ft²",area,area*10.7639104f)
        statusText.text = "Area measured • Tap Reset for a new outline."
    }

    private fun measureAreaAt(x: Float, y: Float) {
        val frame = latestFrame ?: return
        val hit = frame.hitTest(x,y).firstOrNull { it.trackable?.trackingState == TrackingState.TRACKING && ((it.trackable as? Plane)?.type == Plane.Type.HORIZONTAL_UPWARD_FACING || it.trackable is DepthPoint || it.trackable is Point) } ?: run { Toast.makeText(this,"Aim at a kitchen/floor corner and tap.",Toast.LENGTH_SHORT).show(); return }
        createAnchorSafely(hit)?.let { areaAnchors.add(it); measurementOverlay.addAreaPoint(x,y); distanceText.text = areaAnchors.size.toString()+" points"; findViewById<Button>(R.id.finishAreaButton).visibility = if(areaAnchors.size>=3) android.view.View.VISIBLE else android.view.View.GONE; statusText.text = "Area outline • tap the next corner or Finish." }
    }

    private fun finishArea() {
        if (areaAnchors.size < 3) return
        val p = areaAnchors.map { it.pose }
        var area = 0f
        for (i in p.indices) { val j=(i+1)%p.size; area += p[i].tx()*p[j].tz()-p[j].tx()*p[i].tz() }
        area = abs(area)/2f
        measurementOverlay.closeArea()
        distanceText.text = String.format(Locale.US,"%.2f m²\\n%.1f ft²",area,area*10.7639104f)
        statusText.text = "Area measured • Tap Reset for a new outline."
    }

    private fun findBestHit(frame: Frame, x: Float, y: Float): HitResult? {
        val directHits = frame.hitTest(x, y)
            .filter { it.trackable?.trackingState == TrackingState.TRACKING }

        val hits = if (measureMode == MeasureMode.HEIGHT && directHits.none { it.trackable is DepthPoint }) {
            val offsets = floatArrayOf(-28f, -14f, 14f, 28f)
            offsets.flatMap { ox ->
                offsets.map { oy ->
                    frame.hitTest(x + ox, y + oy)
                }
            }.flatten().filter { it.trackable?.trackingState == TrackingState.TRACKING }
        } else {
            directHits
        }

        if (hits.isEmpty()) return null

        fun horizontal(hit: HitResult) =
            (hit.trackable as? Plane)?.type == Plane.Type.HORIZONTAL_UPWARD_FACING

        fun vertical(hit: HitResult) =
            (hit.trackable as? Plane)?.type == Plane.Type.VERTICAL

        return when (measureMode) {
            MeasureMode.FLOOR ->
                hits.firstOrNull { horizontal(it) }
                    ?: hits.firstOrNull { it.trackable is DepthPoint }
                    ?: hits.firstOrNull { vertical(it) }
                    ?: hits.firstOrNull { it.trackable is Point }

            MeasureMode.HEIGHT ->
                hits.firstOrNull { it.trackable is DepthPoint }
                    ?: hits.firstOrNull { vertical(it) }
                    ?: hits.firstOrNull { horizontal(it) }
                    ?: hits.firstOrNull { it.trackable is Point }

            MeasureMode.DIRECT ->
                hits.firstOrNull { it.trackable is DepthPoint }
                    ?: hits.firstOrNull { it.trackable is Plane }
                    ?: hits.firstOrNull { it.trackable is Point }

            MeasureMode.AREA -> null
        }
    }

    private fun createAnchorSafely(hit: HitResult): Anchor? =
        try { hit.createAnchor() } catch (_: Exception) { null }

    private fun showDistance(meters: Float) {
        val centimeters = meters * 100f
        val totalInches = meters * 39.3700787f
        val feet = (totalInches / 12f).toInt()
        val inches = totalInches - feet * 12f

        statusText.text = measureMode.label + " measured • Tap Reset for a new measurement."
        distanceText.text = String.format(
            Locale.US,
            "%.2f m\n%.1f cm\n%d ft %.1f in",
            meters, centimeters, feet, inches
        )
    }

    private fun resetMeasurement() {
        firstAnchor?.detach()
        secondAnchor?.detach()
        areaAnchors.forEach { it.detach() }
        areaAnchors.clear()
        firstAnchor = null
        secondAnchor = null
        measurementOverlay.clear()
        findViewById<Button>(R.id.finishAreaButton)?.visibility = android.view.View.GONE
        distanceText.text = "—"

        if (::statusText.isInitialized) {
            statusText.text = measureMode.label + " mode • Tap the first point."
        }
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    override fun onDestroy() {
        firstAnchor?.detach()
        secondAnchor?.detach()
        areaAnchors.forEach { it.detach() }
        super.onDestroy()
    }
}
