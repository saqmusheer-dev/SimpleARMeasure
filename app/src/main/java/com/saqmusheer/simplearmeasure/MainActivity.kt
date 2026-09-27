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
        DIRECT("3D")
    }

    private lateinit var arSceneView: ARSceneView
    private lateinit var statusText: TextView
    private lateinit var distanceText: TextView
    private lateinit var modeText: TextView

    private var latestFrame: Frame? = null
    private var firstAnchor: Anchor? = null
    private var secondAnchor: Anchor? = null
    private var measureMode = MeasureMode.FLOOR

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startAr()
            } else {
                statusText.text = "Camera permission is required."
                Toast.makeText(this, "Please allow camera access.", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        arSceneView = findViewById(R.id.arSceneView)
        statusText = findViewById(R.id.statusText)
        distanceText = findViewById(R.id.distanceText)
        modeText = findViewById(R.id.modeText)

        findViewById<Button>(R.id.floorButton).setOnClickListener {
            selectMode(MeasureMode.FLOOR)
        }
        findViewById<Button>(R.id.heightButton).setOnClickListener {
            selectMode(MeasureMode.HEIGHT)
        }
        findViewById<Button>(R.id.directButton).setOnClickListener {
            selectMode(MeasureMode.DIRECT)
        }
        findViewById<Button>(R.id.resetButton).setOnClickListener {
            resetMeasurement()
        }

        selectMode(MeasureMode.FLOOR)

        if (hasCameraPermission()) {
            startAr()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startAr() {
        statusText.text = "Tap any point to start."
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
                statusText.text = "Point A locked • Move to the second point and tap."
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

        floor.isSelected = mode == MeasureMode.FLOOR
        height.isSelected = mode == MeasureMode.HEIGHT
        direct.isSelected = mode == MeasureMode.DIRECT

        floor.alpha = if (mode == MeasureMode.FLOOR) 1f else 0.60f
        height.alpha = if (mode == MeasureMode.HEIGHT) 1f else 0.60f
        direct.alpha = if (mode == MeasureMode.DIRECT) 1f else 0.60f

        resetMeasurement()
    }

    private fun measureAt(x: Float, y: Float) {
        val frame = latestFrame ?: run {
            Toast.makeText(this, "AR is still starting. Try again.", Toast.LENGTH_SHORT).show()
            return
        }

        val hit = findBestHit(frame, x, y)
        if (hit == null) {
            Toast.makeText(
                this,
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

            distanceText.text = "A"
            statusText.text = "Point A locked • Move to the second point and tap."
            return
        }

        secondAnchor?.detach()
        secondAnchor = createAnchorSafely(hit)

        if (secondAnchor == null) {
            Toast.makeText(this, "Could not lock the second point. Try again.", Toast.LENGTH_SHORT).show()
            return
        }

        val a = firstAnchor?.pose ?: run {
            resetMeasurement()
            return
        }
        val b = secondAnchor?.pose ?: run {
            resetMeasurement()
            return
        }

        val dx = b.tx() - a.tx()
        val dy = b.ty() - a.ty()
        val dz = b.tz() - a.tz()

        val meters = when (measureMode) {
            MeasureMode.FLOOR -> sqrt(dx * dx + dz * dz)
            MeasureMode.HEIGHT -> abs(dy)
            MeasureMode.DIRECT -> sqrt(dx * dx + dy * dy + dz * dz)
        }

        showDistance(meters)
    }

    private fun findBestHit(frame: Frame, x: Float, y: Float): HitResult? {
        val hits = frame.hitTest(x, y)
            .filter { it.trackable?.trackingState == TrackingState.TRACKING }

        if (hits.isEmpty()) return null

        fun isHorizontalPlane(hit: HitResult): Boolean =
            (hit.trackable as? Plane)?.type == Plane.Type.HORIZONTAL_UPWARD_FACING

        fun isVerticalPlane(hit: HitResult): Boolean =
            (hit.trackable as? Plane)?.type == Plane.Type.VERTICAL

        return when (measureMode) {
            MeasureMode.FLOOR ->
                hits.firstOrNull { isHorizontalPlane(it) }
                    ?: hits.firstOrNull { isVerticalPlane(it) }
                    ?: hits.firstOrNull { it.trackable is DepthPoint }
                    ?: hits.firstOrNull { it.trackable is Point }

            MeasureMode.HEIGHT ->
                hits.firstOrNull { it.trackable is DepthPoint }
                    ?: hits.firstOrNull { isVerticalPlane(it) }
                    ?: hits.firstOrNull { isHorizontalPlane(it) }
                    ?: hits.firstOrNull { it.trackable is Point }

            MeasureMode.DIRECT ->
                hits.firstOrNull { it.trackable is DepthPoint }
                    ?: hits.firstOrNull { it.trackable is Plane }
                    ?: hits.firstOrNull { it.trackable is Point }
        }
    }

    private fun createAnchorSafely(hit: HitResult): Anchor? =
        try {
            hit.createAnchor()
        } catch (_: Exception) {
            null
        }

    private fun showDistance(meters: Float) {
        val centimeters = meters * 100f
        val totalInches = meters * 39.3700787f
        val feet = (totalInches / 12f).toInt()
        val inches = totalInches - feet * 12f

        statusText.text = measureMode.label + " measured • Tap Reset for a new measurement."
        distanceText.text = String.format(
            Locale.US,
            "%.2f m\n%.1f cm\n%d ft %.1f in",
            meters,
            centimeters,
            feet,
            inches
        )
    }

    private fun resetMeasurement() {
        firstAnchor?.detach()
        secondAnchor?.detach()
        firstAnchor = null
        secondAnchor = null
        distanceText.text = "—"

        if (::statusText.isInitialized) {
            statusText.text = measureMode.label + " mode • Tap the first point."
        }
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

    override fun onDestroy() {
        firstAnchor?.detach()
        secondAnchor?.detach()
        super.onDestroy()
    }
}
