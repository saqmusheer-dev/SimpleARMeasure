package com.saqmusheer.simplearmeasure

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.Image
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.ar.core.Anchor
import com.google.ar.core.Config
import com.google.ar.core.Coordinates2d
import com.google.ar.core.DepthPoint
import com.google.ar.core.Frame
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.NotYetAvailableException
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenter
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import io.github.sceneview.ar.ARSceneView
import java.nio.FloatBuffer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class MainActivity : AppCompatActivity() {

    private enum class MeasureMode(val label: String) {
        FLOOR("Floor"),
        KITCHEN_TOP("Kitchen Top"),
        HEIGHT("Person Height"),
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
    private var arSession: Session? = null
    private var firstAnchor: Anchor? = null
    private var secondAnchor: Anchor? = null
    private var measureMode = MeasureMode.FLOOR
    private val areaAnchors = mutableListOf<Anchor>()

    private val prefs by lazy { getSharedPreferences("measure_settings", MODE_PRIVATE) }
    private var autoPersonOutline = true
    private var autoFloorOutline = true
    private var wallReference = true
    private var showRuler = true
    private var arStarted = false

    private var subjectSegmenter: SubjectSegmenter? = null
    private var segmentationBusy = false
    private var lastSegmentationMs = 0L
    private var personMeasured = false
    private var kitchenTopPlane: Plane? = null

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startAr()
            else {
                statusText.text = "Camera permission is required."
                Toast.makeText(this, "Please allow camera access in Settings.", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        licenseManager = LicenseManager(this)
        if (!licenseManager.isLicensed()) {
            showLicenseScreen()
            return
        }
        loadSettings()
        setupMainUi()
    }

    private fun loadSettings() {
        autoPersonOutline = prefs.getBoolean("auto_person", true)
        autoFloorOutline = prefs.getBoolean("auto_floor", true)
        wallReference = prefs.getBoolean("wall_reference", true)
        showRuler = prefs.getBoolean("show_ruler", true)
    }

    private fun setupMainUi() {
        setContentView(R.layout.activity_main)

        arSceneView = findViewById(R.id.arSceneView)
        statusText = findViewById(R.id.statusText)
        distanceText = findViewById(R.id.distanceText)
        modeText = findViewById(R.id.modeText)
        measurementOverlay = findViewById(R.id.measurementOverlay)

        findViewById<Button>(R.id.floorButton).setOnClickListener { selectMode(MeasureMode.FLOOR) }
        findViewById<Button>(R.id.kitchenTopButton).setOnClickListener { selectMode(MeasureMode.KITCHEN_TOP) }
        findViewById<Button>(R.id.heightButton).setOnClickListener { selectMode(MeasureMode.HEIGHT) }
        findViewById<Button>(R.id.directButton).setOnClickListener { selectMode(MeasureMode.DIRECT) }
        findViewById<Button>(R.id.areaButton).setOnClickListener { selectMode(MeasureMode.AREA) }
        findViewById<Button>(R.id.finishAreaButton).setOnClickListener { finishArea() }
        findViewById<Button>(R.id.resetButton).setOnClickListener { resetMeasurement() }
        findViewById<Button>(R.id.settingsButton).setOnClickListener { showSettings() }

        measurementOverlay.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                measureAt(event.x, event.y)
            }
            true
        }

        findViewById<View>(R.id.rulerView).visibility = if (showRuler) View.VISIBLE else View.GONE
        selectMode(MeasureMode.FLOOR)

        // Android does not display runtime permissions during APK installation.
        // We request CAMERA immediately on the first launch before starting AR.
        ensureCameraPermission()
    }

    private fun ensureCameraPermission() {
        if (!::statusText.isInitialized) return
        if (hasCameraPermission()) {
            if (!arStarted) startAr()
        } else {
            statusText.text = "Camera permission is required for AR measuring."
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onResume() {
        super.onResume()
        if (::statusText.isInitialized) {
            if (hasCameraPermission()) {
                if (!arStarted) startAr()
            } else {
                statusText.text = "Camera permission is required for AR measuring."
            }
        }
    }

    private fun showLicenseScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(42, 24, 42, 24)
            setBackgroundColor(0xFF151122.toInt())
        }
        val title = TextView(this).apply {
            text = "Simple AR Measure"
            textSize = 30f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        val subtitle = TextView(this).apply {
            text = "Enter your activation key"
            textSize = 17f
            setTextColor(0xFFD8D1E8.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 22)
        }
        val input = android.widget.EditText(this).apply {
            hint = "Activation key"
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(0xFFBDB7CC.toInt())
            setBackgroundColor(0xFF000000.toInt())
            setPadding(18, 14, 18, 14)
        }
        val activate = Button(this).apply { text = "ACTIVATE" }
        activate.setOnClickListener {
            val result = licenseManager.activate(input.text.toString())
            if (result.first) setupMainUi()
            else Toast.makeText(this, result.second, Toast.LENGTH_LONG).show()
        }
        root.addView(title)
        root.addView(subtitle)
        root.addView(input, LinearLayout.LayoutParams(-1, -2))
        root.addView(activate, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 18 })
        setContentView(root)
    }

    private fun showSettings() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 4, 24, 4)
        }

        fun addSwitch(title: String, checked: Boolean, onChanged: (Boolean) -> Unit) {
            val sw = Switch(this).apply {
                text = title
                textSize = 16f
                isChecked = checked
                setPadding(0, 10, 0, 10)
                setOnCheckedChangeListener { _, value -> onChanged(value) }
            }
            box.addView(sw)
        }

        addSwitch("Automatic person outline", autoPersonOutline) {
            autoPersonOutline = it
            prefs.edit().putBoolean("auto_person", it).apply()
            if (!it) measurementOverlay.clearPersonOutline()
        }
        addSwitch("Automatic floor boundary", autoFloorOutline) {
            autoFloorOutline = it
            prefs.edit().putBoolean("auto_floor", it).apply()
        }
        addSwitch("Use wall as height reference", wallReference) {
            wallReference = it
            prefs.edit().putBoolean("wall_reference", it).apply()
        }
        addSwitch("Show side ruler", showRuler) {
            showRuler = it
            prefs.edit().putBoolean("show_ruler", it).apply()
            findViewById<View>(R.id.rulerView)?.visibility = if (it) View.VISIBLE else View.GONE
        }

        val info = TextView(this).apply {
            text = "License: active\nAutomatic detection works on-device. Good lighting and a clear view improve results."
            textSize = 14f
            setTextColor(0xFF555555.toInt())
            setPadding(0, 14, 0, 4)
        }
        box.addView(info)

        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setView(box)
            .setPositiveButton("DONE", null)
            .show()
    }

    private fun startAr() {
        if (arStarted || !hasCameraPermission()) return
        arStarted = true
        statusText.text = "Floor mode • Move slowly until the floor is detected."
        arSceneView.lifecycle = lifecycle

        arSceneView.configureSession { session, config ->
            arSession = session
            arSceneView.planeRenderer.isVisible = false
            config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
            config.lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
            config.depthMode =
                if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                    Config.DepthMode.AUTOMATIC
                } else {
                    Config.DepthMode.DISABLED
                }
        }

        subjectSegmenter = SubjectSegmentation.getClient(
            SubjectSegmenterOptions.Builder()
                .enableForegroundConfidenceMask()
                .build()
        )

        arSceneView.onSessionUpdated = { _, frame ->
            latestFrame = frame

            if (autoFloorOutline && (measureMode == MeasureMode.FLOOR || measureMode == MeasureMode.AREA)) {
                updateFloorBoundary(frame)
            } else if (measureMode == MeasureMode.KITCHEN_TOP && kitchenTopPlane != null) {
                updateKitchenTopBoundary(frame, kitchenTopPlane!!)
            } else {
                measurementOverlay.clearAutoFloorOutline()
            }

            if (measureMode == MeasureMode.HEIGHT && autoPersonOutline && !personMeasured) {
                maybeSegmentPerson(frame)
            }

            if (firstAnchor == null && measureMode != MeasureMode.AREA) {
                statusText.text = when (measureMode) {
                    MeasureMode.HEIGHT -> "Height mode • Stand clearly in view."
                    else -> measureMode.label + " mode • Tap the first point."
                }
            } else if (secondAnchor == null && measureMode != MeasureMode.AREA) {
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

    }

    private fun maybeSegmentPerson(frame: Frame) {
        if (segmentationBusy || SystemClock.elapsedRealtime() - lastSegmentationMs < 650L) return
        if (frame.camera.trackingState != TrackingState.TRACKING) return

        lastSegmentationMs = SystemClock.elapsedRealtime()
        var image: Image? = null
        try {
            image = frame.acquireCameraImage()
            val rotation = cameraRotationDegrees()
            val imageWidth = image.width
            val imageHeight = image.height
            val input = InputImage.fromMediaImage(image, rotation)
            segmentationBusy = true
            statusText.text = "Detecting person outline…"

            subjectSegmenter?.process(input)
                ?.addOnSuccessListener { result ->
                    val current = latestFrame
                    if (current != null && measureMode == MeasureMode.HEIGHT && autoPersonOutline) {
                        applyPersonMask(result.foregroundConfidenceMask, imageWidth, imageHeight, current)
                    }
                }
                ?.addOnFailureListener {
                    // The model may still be downloading on the first use.
                    statusText.text = "Person detection is preparing…"
                }
                ?.addOnCompleteListener {
                    segmentationBusy = false
                    image?.close()
                }
        } catch (_: NotYetAvailableException) {
            image?.close()
            segmentationBusy = false
        } catch (_: Exception) {
            image?.close()
            segmentationBusy = false
        }
    }

    private fun cameraRotationDegrees(): Int {
        return try {
            val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = manager.cameraIdList.firstOrNull { id ->
                manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_BACK
            } ?: manager.cameraIdList.first()
            val characteristics = manager.getCameraCharacteristics(cameraId)
            val sensorOrientation =
                characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            val displayRotation = when (windowManager.defaultDisplay.rotation) {
                Surface.ROTATION_90 -> 90
                Surface.ROTATION_180 -> 180
                Surface.ROTATION_270 -> 270
                else -> 0
            }
            (sensorOrientation - displayRotation + 360) % 360
        } catch (_: Exception) {
            90
        }
    }

    private fun applyPersonMask(mask: FloatBuffer?, imageWidth: Int, imageHeight: Int, frame: Frame) {
        if (mask == null || !mask.hasRemaining()) return
        val confidence = FloatArray(mask.remaining())
        mask.rewind()
        mask.get(confidence)

        val stepY = max(4, imageHeight / 160)
        val leftBoundary = ArrayList<Pair<Float, Float>>(160)
        val rightBoundary = ArrayList<Pair<Float, Float>>(160)
        val topSamples = ArrayList<Float>(12)
        val bottomSamples = ArrayList<Float>(12)
        var topY = Float.MAX_VALUE
        var bottomY = -1f
        var rows = 0

        for (y in 0 until imageHeight step stepY) {
            var left = imageWidth
            var right = -1
            for (x in 0 until imageWidth step stepY) {
                val index = y * imageWidth + x
                if (index < confidence.size && confidence[index] > 0.62f) {
                    left = min(left, x)
                    right = max(right, x)
                }
            }
            if (right >= left && right - left >= stepY * 2) {
                leftBoundary.add(left.toFloat() to y.toFloat())
                rightBoundary.add(right.toFloat() to y.toFloat())
                rows++
                if (y < topY) {
                    topY = y.toFloat()
                    topSamples.clear()
                }
                if (y == topY || y - topY <= stepY * 2) topSamples.add(((left + right) * 0.5f))
                if (y > bottomY) {
                    bottomY = y.toFloat()
                    bottomSamples.clear()
                }
                if (bottomY - y <= stepY * 2) bottomSamples.add(((left + right) * 0.5f))
            }
        }

        if (rows < 10 || bottomY < 0f) return

        val topX = if (topSamples.isNotEmpty()) topSamples.average().toFloat() else imageWidth / 2f
        val bottomX = if (bottomSamples.isNotEmpty()) bottomSamples.average().toFloat() else imageWidth / 2f
        val points = ArrayList<Float>((leftBoundary.size + rightBoundary.size) * 2)
        leftBoundary.forEach { points.add(it.first); points.add(it.second) }
        rightBoundary.forEach { points.add(it.first); points.add(it.second) }

        val screenPoints = FloatArray(points.size)
        try {
            frame.transformCoordinates2d(
                Coordinates2d.IMAGE_PIXELS,
                points.toFloatArray(),
                Coordinates2d.VIEW,
                screenPoints
            )
        } catch (_: Exception) {
            return
        }

        val outline = ArrayList<Pair<Float, Float>>(screenPoints.size / 2)
        val leftCount = leftBoundary.size
        for (i in 0 until leftCount) {
            outline.add(screenPoints[i * 2] to screenPoints[i * 2 + 1])
        }
        for (i in leftCount until leftCount + rightBoundary.size) {
            outline.add(screenPoints[i * 2] to screenPoints[i * 2 + 1])
        }
        measurementOverlay.setPersonOutline(outline)

        val topScreen = FloatArray(2)
        val bottomScreen = FloatArray(2)
        frame.transformCoordinates2d(
            Coordinates2d.IMAGE_PIXELS,
            floatArrayOf(topX, topY, bottomX, bottomY),
            Coordinates2d.VIEW,
            floatArrayOf(0f, 0f, 0f, 0f).also {
                topScreen[0] = it[0]
                topScreen[1] = it[1]
                bottomScreen[0] = it[2]
                bottomScreen[1] = it[3]
            }
        )

        val topHit = findDepthHit(frame, topScreen[0], topScreen[1])
        val bottomHit = findDepthHit(frame, bottomScreen[0], bottomScreen[1])
        if (topHit != null && bottomHit != null) {
            val topPose = topHit.hitPose
            val bottomPose = bottomHit.hitPose
            val height = abs(topPose.ty() - bottomPose.ty())
            if (height in 0.7f..2.8f) {
                firstAnchor?.detach()
                secondAnchor?.detach()
                firstAnchor = createAnchorSafely(bottomHit)
                secondAnchor = createAnchorSafely(topHit)
                if (firstAnchor != null && secondAnchor != null) {
                    personMeasured = true
                    measurementOverlay.setFirstPoint(bottomScreen[0], bottomScreen[1], false)
                    measurementOverlay.setSecondPoint(topScreen[0], topScreen[1])
                    showDistance(height)
                    val wall = if (wallReference) estimateWallDistance(bottomPose, frame) else null
                    statusText.text = if (wall != null) {
                        String.format(Locale.US, "Person detected • Wall distance %.0f cm", wall * 100f)
                    } else {
                        "Person detected • Height measured"
                    }
                }
            }
        }
    }

    private fun findDepthHit(frame: Frame, x: Float, y: Float): HitResult? {
        val offsets = floatArrayOf(0f, -18f, 18f, -32f, 32f)
        return offsets.asSequence()
            .flatMap { ox -> offsets.asSequence().map { oy -> frame.hitTest(x + ox, y + oy) } }
            .flatten()
            .firstOrNull {
                it.trackable?.trackingState == TrackingState.TRACKING &&
                    it.trackable is DepthPoint
            }
            ?: frame.hitTest(x, y).firstOrNull {
                it.trackable?.trackingState == TrackingState.TRACKING &&
                    it.trackable is Plane
            }
    }

    private fun estimateWallDistance(personFoot: Pose, frame: Frame): Float? {
        val session = arSession ?: return null
        val planes = session.getAllTrackables(Plane::class.java)
        var best: Float? = null
        for (plane in planes) {
            if (plane.trackingState != TrackingState.TRACKING ||
                plane.subsumedBy != null ||
                plane.type != Plane.Type.VERTICAL) continue

            val normal = plane.centerPose.getTransformedAxis(1, 1f)
            val distance = abs(
                (personFoot.tx() - plane.centerPose.tx()) * normal[0] +
                    (personFoot.ty() - plane.centerPose.ty()) * normal[1] +
                    (personFoot.tz() - plane.centerPose.tz()) * normal[2]
            )
            if (distance <= 2.5f && (best == null || distance < best)) best = distance
        }
        return best
    }

    private fun updateFloorBoundary(frame: Frame) {
        val session = arSession ?: run {
            measurementOverlay.clearAutoFloorOutline()
            return
        }

        val centerX = measurementOverlay.width / 2f
        val centerY = measurementOverlay.height / 2f

        // Prefer the horizontal surface currently under the camera centre.
        val targetPlane = frame.hitTest(centerX, centerY)
            .asSequence()
            .mapNotNull { it.trackable as? Plane }
            .firstOrNull {
                it.trackingState == TrackingState.TRACKING &&
                    it.subsumedBy == null &&
                    it.type == Plane.Type.HORIZONTAL_UPWARD_FACING
            }

        val plane = targetPlane ?: session.getAllTrackables(Plane::class.java)
            .filter {
                it.trackingState == TrackingState.TRACKING &&
                    it.subsumedBy == null &&
                    it.type == Plane.Type.HORIZONTAL_UPWARD_FACING
            }
            .maxByOrNull { it.extentX * it.extentZ }

        if (plane == null) {
            measurementOverlay.clearAutoFloorOutline()
            return
        }

        val points = projectPlanePolygon(frame, plane)
        if (points.size >= 3) measurementOverlay.setAutoFloorOutline(points)
    }

    private fun selectMode(mode: MeasureMode) {
        measureMode = mode
        modeText.text = mode.label.uppercase(Locale.US) + " MODE"
        findViewById<Button>(R.id.floorButton).alpha = if (mode == MeasureMode.FLOOR) 1f else 0.60f
        findViewById<Button>(R.id.kitchenTopButton).alpha = if (mode == MeasureMode.KITCHEN_TOP) 1f else 0.60f
        findViewById<Button>(R.id.heightButton).alpha = if (mode == MeasureMode.HEIGHT) 1f else 0.60f
        findViewById<Button>(R.id.directButton).alpha = if (mode == MeasureMode.DIRECT) 1f else 0.60f
        findViewById<Button>(R.id.areaButton).alpha = if (mode == MeasureMode.AREA) 1f else 0.60f
        resetMeasurement()
    }

    private fun measureAt(x: Float, y: Float) {
        if (measureMode == MeasureMode.AREA) {
            measureAreaAt(x, y)
            return
        }
        if (measureMode == MeasureMode.KITCHEN_TOP) {
            measureKitchenTopAt(x, y)
            return
        }

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
            MeasureMode.FLOOR, MeasureMode.KITCHEN_TOP -> sqrt(dx * dx + dz * dz)
            MeasureMode.HEIGHT -> abs(dy)
            MeasureMode.DIRECT -> sqrt(dx * dx + dy * dy + dz * dz)
            MeasureMode.AREA -> 0f
        }

        measurementOverlay.setSecondPoint(x, y)
        showDistance(meters)
    }

    private fun measureKitchenTopAt(x: Float, y: Float) {
        val frame = latestFrame ?: return
        val offsets = floatArrayOf(0f, -24f, 24f, -48f, 48f)
        val hits = offsets.flatMap { ox ->
            offsets.map { oy -> frame.hitTest(x + ox, y + oy) }
        }.flatten().filter {
            it.trackable?.trackingState == TrackingState.TRACKING
        }

        val hit = hits.firstOrNull {
            (it.trackable as? Plane)?.type == Plane.Type.HORIZONTAL_UPWARD_FACING
        }
        val plane = hit?.trackable as? Plane
        if (plane == null) {
            Toast.makeText(
                this,
                "Aim at the kitchen countertop and tap its surface.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        kitchenTopPlane = plane
        updateKitchenTopBoundary(frame, plane)

        val polygon = plane.polygon
        if (!polygon.hasRemaining()) return
        polygon.rewind()
        val local = FloatArray(polygon.remaining())
        polygon.get(local)
        if (local.size < 6) return

        var area = 0f
        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minZ = Float.MAX_VALUE
        var maxZ = -Float.MAX_VALUE
        for (i in local.indices step 2) {
            val j = (i + 2) % local.size
            area += local[i] * local[j + 1] - local[j] * local[i + 1]
            minX = min(minX, local[i])
            maxX = max(maxX, local[i])
            minZ = min(minZ, local[i + 1])
            maxZ = max(maxZ, local[i + 1])
        }
        area = abs(area) / 2f
        val width = maxX - minX
        val depth = maxZ - minZ
        distanceText.text = String.format(
            Locale.US,
            "%.2f m²\n%.1f ft²\n%.1f × %.1f ft",
            area,
            area * 10.7639104f,
            width * 3.28084f,
            depth * 3.28084f
        )
        statusText.text = "Kitchen top detected • Shape and area updated."
    }

    private fun updateKitchenTopBoundary(frame: Frame, plane: Plane) {
        if (plane.trackingState != TrackingState.TRACKING || plane.subsumedBy != null) {
            measurementOverlay.clearAutoFloorOutline()
            return
        }
        val points = projectPlanePolygon(frame, plane)
        if (points.size >= 3) {
            measurementOverlay.setAutoFloorOutline(points)
        } else {
            measurementOverlay.clearAutoFloorOutline()
        }
    }

    private fun projectPlanePolygon(frame: Frame, plane: Plane): List<Pair<Float, Float>> {
        val polygon = plane.polygon
        if (!polygon.hasRemaining()) return emptyList()
        polygon.rewind()
        val local = FloatArray(polygon.remaining())
        polygon.get(local)

        val view = FloatArray(16)
        val projection = FloatArray(16)
        val model = FloatArray(16)
        val pv = FloatArray(16)
        frame.camera.getViewMatrix(view, 0)
        frame.camera.getProjectionMatrix(projection, 0, 0.01f, 100f)
        plane.centerPose.toMatrix(model, 0)
        android.opengl.Matrix.multiplyMM(pv, 0, projection, 0, view, 0)

        val points = ArrayList<Pair<Float, Float>>(local.size / 2)
        for (i in local.indices step 2) {
            val modelPoint = floatArrayOf(local[i], 0f, local[i + 1], 1f)
            val worldPoint = FloatArray(4)
            android.opengl.Matrix.multiplyMV(worldPoint, 0, model, 0, modelPoint, 0)
            val clip = FloatArray(4)
            android.opengl.Matrix.multiplyMV(clip, 0, pv, 0, worldPoint, 0)
            if (clip[3] <= 0f) continue
            val nx = clip[0] / clip[3]
            val ny = clip[1] / clip[3]
            points.add(
                ((nx + 1f) * 0.5f * measurementOverlay.width) to
                    ((1f - ny) * 0.5f * measurementOverlay.height)
            )
        }
        return points
    }

    private fun measureAreaAt(x: Float, y: Float) {
        val frame = latestFrame ?: return
        val offsets = floatArrayOf(0f, -24f, 24f, -48f, 48f)
        val hits = offsets.flatMap { ox ->
            offsets.map { oy -> frame.hitTest(x + ox, y + oy) }
        }.flatten().filter {
            it.trackable?.trackingState == TrackingState.TRACKING
        }

        val hit = hits.firstOrNull {
            (it.trackable as? Plane)?.type == Plane.Type.HORIZONTAL_UPWARD_FACING
        } ?: hits.firstOrNull { it.trackable is DepthPoint }
            ?: hits.firstOrNull { it.trackable is com.google.ar.core.Point }

        if (hit == null) {
            Toast.makeText(
                this,
                "Move slowly until the surface is detected, then tap the corner.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        createAnchorSafely(hit)?.let {
            areaAnchors.add(it)
            measurementOverlay.addAreaPoint(x, y)
            distanceText.text = areaAnchors.size.toString() + " points"
            findViewById<Button>(R.id.finishAreaButton).visibility =
                if (areaAnchors.size >= 3) View.VISIBLE else View.GONE
            statusText.text = "Point " + areaAnchors.size + " locked • Tap the next corner."
        }
    }

    private fun finishArea() {
        if (areaAnchors.size < 3) return
        val p = areaAnchors.map { it.pose }
        var area = 0f
        for (i in p.indices) {
            val j = (i + 1) % p.size
            area += p[i].tx() * p[j].tz() - p[j].tx() * p[i].tz()
        }
        area = abs(area) / 2f
        measurementOverlay.closeArea()
        distanceText.text = String.format(Locale.US, "%.2f m²\n%.1f ft²", area, area * 10.7639104f)
        statusText.text = "Area measured • Tap Reset for a new outline."
    }

    private fun findBestHit(frame: Frame, x: Float, y: Float): HitResult? {
        val offsets = floatArrayOf(0f, -24f, 24f, -48f, 48f)
        val hits = offsets.flatMap { ox ->
            offsets.map { oy -> frame.hitTest(x + ox, y + oy) }
        }.flatten().filter {
            it.trackable?.trackingState == TrackingState.TRACKING
        }

        if (hits.isEmpty()) return null

        fun horizontal(hit: HitResult) =
            (hit.trackable as? Plane)?.type == Plane.Type.HORIZONTAL_UPWARD_FACING

        fun vertical(hit: HitResult) =
            (hit.trackable as? Plane)?.type == Plane.Type.VERTICAL

        return when (measureMode) {
            MeasureMode.FLOOR, MeasureMode.KITCHEN_TOP ->
                hits.firstOrNull { horizontal(it) }
                    ?: hits.firstOrNull { it.trackable is DepthPoint }
                    ?: hits.firstOrNull { it.trackable is com.google.ar.core.Point }
                    ?: hits.firstOrNull { vertical(it) }

            MeasureMode.HEIGHT ->
                hits.firstOrNull { it.trackable is DepthPoint }
                    ?: hits.firstOrNull { vertical(it) }
                    ?: hits.firstOrNull { horizontal(it) }
                    ?: hits.firstOrNull { it.trackable is com.google.ar.core.Point }

            MeasureMode.DIRECT ->
                hits.firstOrNull { it.trackable is DepthPoint }
                    ?: hits.firstOrNull { it.trackable is Plane }
                    ?: hits.firstOrNull { it.trackable is com.google.ar.core.Point }

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
        distanceText.text = String.format(Locale.US, "%.2f m\n%.1f cm\n%d ft %.1f in", meters, centimeters, feet, inches)
    }

    private fun resetMeasurement() {
        firstAnchor?.detach()
        secondAnchor?.detach()
        areaAnchors.forEach { it.detach() }
        areaAnchors.clear()
        firstAnchor = null
        secondAnchor = null
        personMeasured = false
        kitchenTopPlane = null
        measurementOverlay.clear()
        findViewById<Button>(R.id.finishAreaButton)?.visibility = View.GONE
        distanceText.text = "—"
        if (::statusText.isInitialized) statusText.text = measureMode.label + " mode • Tap the first point."
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    override fun onDestroy() {
        subjectSegmenter?.close()
        firstAnchor?.detach()
        secondAnchor?.detach()
        areaAnchors.forEach { it.detach() }
        super.onDestroy()
    }
}
