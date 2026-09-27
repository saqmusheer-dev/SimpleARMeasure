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
import com.google.ar.core.Config
import com.google.ar.core.Plane
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.arcore.createAnchorOrNull
import io.github.sceneview.ar.arcore.getUpdatedPlanes
import io.github.sceneview.math.Position
import kotlin.math.sqrt
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var arSceneView: ARSceneView
    private lateinit var statusText: TextView
    private lateinit var distanceText: TextView

    private var firstPoint: Position? = null
    private var firstAnchor: com.google.ar.core.Anchor? = null
    private var secondAnchor: com.google.ar.core.Anchor? = null
    private var planeDetected = false

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

        findViewById<Button>(R.id.resetButton).setOnClickListener {
            resetMeasurement()
        }

        if (hasCameraPermission()) {
            startAr()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startAr() {
        statusText.text = "Starting AR camera…"
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
            planeDetected = frame.getUpdatedPlanes().any { plane ->
                plane.trackingState == TrackingState.TRACKING &&
                    (plane.type == Plane.Type.HORIZONTAL_UPWARD_FACING ||
                        plane.type == Plane.Type.VERTICAL)
            }

            if (firstPoint == null) {
                statusText.text =
                    if (planeDetected) "Surface detected. Tap the first point."
                    else "Move your phone slowly to scan a surface."
            }
        }

        arSceneView.onSessionFailed = { exception ->
            statusText.text = "AR failed: ${exception.message ?: "Unknown error"}"
        }

        arSceneView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                measureAt(event.x, event.y)
            }
            true
        }

        statusText.text = "Move your phone slowly to scan a surface."
    }

    private fun measureAt(x: Float, y: Float) {
        val hit = arSceneView.hitTestAR(
            xPx = x,
            yPx = y,
            planeTypes = setOf(
                Plane.Type.HORIZONTAL_UPWARD_FACING,
                Plane.Type.VERTICAL
            )
        )

        if (hit == null) {
            Toast.makeText(
                this,
                "No surface detected here. Move and try again.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val position = hit.worldPosition

        if (firstPoint == null) {
            firstPoint = position
            firstAnchor = hit.createAnchorOrNull()
            statusText.text = "Point A set. Tap the second point."
            distanceText.text = "A"
            return
        }

        secondAnchor?.detach()
        secondAnchor = hit.createAnchorOrNull()

        val a = firstPoint!!
        val dx = position.x - a.x
        val dy = position.y - a.y
        val dz = position.z - a.z
        val meters = sqrt(dx * dx + dy * dy + dz * dz)

        showDistance(meters)
    }

    private fun showDistance(meters: Float) {
        val centimeters = meters * 100f
        val totalInches = meters * 39.3700787f
        val feet = (totalInches / 12f).toInt()
        val inches = totalInches - feet * 12f

        statusText.text = "Measurement complete. Tap Reset to start again."
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
        firstPoint = null
        distanceText.text = "—"
        statusText.text =
            if (planeDetected) "Surface detected. Tap the first point."
            else "Move your phone slowly to scan a surface."
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
