package com.saqmusheer.simplearmeasure

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.LocationManager
import android.media.Image
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.widget.Button
import android.widget.EditText
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

    private enum class EditTool { NONE, PEN, ERASER }

    private enum class MeasureMode(val label: String) {
        FLOOR("Floor"),
        KITCHEN_TOP("Kitchen Top"),
        HEIGHT("Person Height"),
        DIRECT("3D"),
        AREA("Auto Area"),
        CUSTOM_AREA("Custom Area")
    }

    private lateinit var arSceneView: ARSceneView
    private lateinit var statusText: TextView
    private lateinit var distanceText: TextView
    private lateinit var segmentText: TextView
    private lateinit var scanNowButton: Button
    private lateinit var modeText: TextView
    private lateinit var measurementOverlay: MeasurementOverlayView
    private lateinit var licenseManager: LicenseManager
    private lateinit var localStore: LocalProjectStore

    private var projects = mutableListOf<LocalProject>()
    private var currentProjectId: String? = null
    private var pendingDxf: String? = null
    private var lastAutoPolygonWorld = emptyList<LocalPoint>()
    private var lastAutoAreaM2 = 0f
    private var lastAutoAreaUpdateMs = 0L

    private var latestFrame: Frame? = null
    private var arSession: Session? = null
    private var firstAnchor: Anchor? = null
    private var secondAnchor: Anchor? = null
    private var measureMode = MeasureMode.FLOOR
    private val areaAnchors = mutableListOf<Anchor>()
    private val editableAreaWorld = mutableListOf<LocalPoint>()
    private val editableAreaScreen = mutableListOf<Pair<Float, Float>>()
    private var editTool = EditTool.NONE
    private var editingAutoArea = false

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

    private val photoPickerLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                try {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: Exception) { }
                val project = currentProject()
                if (project != null) {
                    localStore.addPhoto(project, uri.toString(), projects)
                    Toast.makeText(this, "Photo added to " + project.name, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Create/select a project first.", Toast.LENGTH_SHORT).show()
                }
            }
        }

    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            if (granted) saveCurrentProjectLocation()
            else Toast.makeText(this, "Location permission was not granted.", Toast.LENGTH_SHORT).show()
        }

    private val dxfCreatorLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/dxf")) { uri ->
            val content = pendingDxf
            pendingDxf = null
            if (uri != null && content != null) {
                try {
                    contentResolver.openOutputStream(uri)?.use {
                        it.write(content.toByteArray(Charsets.UTF_8))
                    }
                    Toast.makeText(this, "DXF exported successfully.", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "DXF export failed: " + e.message, Toast.LENGTH_LONG).show()
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        licenseManager = LicenseManager(this)
        localStore = LocalProjectStore(this)
        projects = localStore.loadProjects()
        currentProjectId = getSharedPreferences("local_projects_ui", MODE_PRIVATE).getString("current_project_id", null)
        if (currentProjectId != null && projects.none { it.id == currentProjectId }) currentProjectId = null
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
        segmentText = findViewById(R.id.segmentText)
        scanNowButton = findViewById(R.id.scanNowButton)
        modeText = findViewById(R.id.modeText)
        measurementOverlay = findViewById(R.id.measurementOverlay)

        findViewById<Button>(R.id.floorButton).setOnClickListener { selectMode(MeasureMode.FLOOR) }
        findViewById<Button>(R.id.kitchenTopButton).setOnClickListener { selectMode(MeasureMode.KITCHEN_TOP) }
        findViewById<Button>(R.id.heightButton).setOnClickListener { selectMode(MeasureMode.HEIGHT) }
        findViewById<Button>(R.id.directButton).setOnClickListener { selectMode(MeasureMode.DIRECT) }
        findViewById<Button>(R.id.areaButton).setOnClickListener { selectMode(MeasureMode.AREA) }
        findViewById<Button>(R.id.customAreaButton).setOnClickListener { selectMode(MeasureMode.CUSTOM_AREA) }
        findViewById<Button>(R.id.finishAreaButton).setOnClickListener { finishArea() }
        findViewById<Button>(R.id.scanNowButton).setOnClickListener { scanNow() }
        findViewById<Button>(R.id.penButton).setOnClickListener { setEditTool(EditTool.PEN) }
        findViewById<Button>(R.id.eraserButton).setOnClickListener { setEditTool(EditTool.ERASER) }
        findViewById<Button>(R.id.undoAreaButton).setOnClickListener { undoAreaEdit() }
        findViewById<Button>(R.id.doneAreaButton).setOnClickListener { finishAreaEdit() }
        findViewById<Button>(R.id.resetButton).setOnClickListener { resetMeasurement() }
        findViewById<Button>(R.id.settingsButton).setOnClickListener { showSettings() }
        findViewById<Button>(R.id.projectsButton).setOnClickListener { showProjects() }
        findViewById<Button>(R.id.saveButton).setOnClickListener { saveCurrentMeasurement() }

        measurementOverlay.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                measureAt(event.x, event.y)
            }
            true
        }

        findViewById<View>(R.id.rulerView).visibility = if (showRuler) View.VISIBLE else View.GONE
        selectMode(MeasureMode.FLOOR)
        updateProjectStatus()

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

            if (autoFloorOutline && (measureMode == MeasureMode.FLOOR || measureMode == MeasureMode.AREA || measureMode == MeasureMode.CUSTOM_AREA)) {
                updateFloorBoundary(frame)
            } else if (measureMode == MeasureMode.KITCHEN_TOP && kitchenTopPlane != null) {
                updateKitchenTopBoundary(frame, kitchenTopPlane!!)
            } else {
                measurementOverlay.clearAutoFloorOutline()
            }

            if (measureMode == MeasureMode.HEIGHT && autoPersonOutline && !personMeasured) {
                maybeSegmentPerson(frame)
            }

            if (firstAnchor == null && measureMode != MeasureMode.AREA && measureMode != MeasureMode.CUSTOM_AREA) {
                statusText.text = when (measureMode) {
                    MeasureMode.HEIGHT -> "Height mode • Stand clearly in view."
                    else -> measureMode.label + " mode • Tap the first point."
                }
            } else if (secondAnchor == null && measureMode != MeasureMode.AREA && measureMode != MeasureMode.CUSTOM_AREA) {
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

        // ML Kit can return a mask at a different resolution than the camera image.
        // Map the mask back to IMAGE_PIXELS instead of assuming identical dimensions.
        val aspect = imageWidth.toFloat() / imageHeight.toFloat()
        var maskWidth = imageWidth
        var maskHeight = imageHeight
        if (confidence.size != imageWidth * imageHeight) {
            maskWidth = max(1, sqrt(confidence.size.toFloat() * aspect).toInt())
            maskHeight = max(1, confidence.size / maskWidth)
            if (maskWidth * maskHeight != confidence.size) {
                maskHeight = max(1, sqrt(confidence.size.toFloat() / aspect).toInt())
                maskWidth = max(1, confidence.size / maskHeight)
            }
        }
        if (maskWidth * maskHeight > confidence.size) {
            maskHeight = max(1, confidence.size / maskWidth)
        }
        if (maskWidth * maskHeight < confidence.size) {
            maskWidth = max(1, confidence.size / maskHeight)
        }

        val yStep = max(2, maskHeight / 160)
        val xStep = max(2, maskWidth / 220)
        val leftBoundary = ArrayList<Pair<Float, Float>>(160)
        val rightBoundary = ArrayList<Pair<Float, Float>>(160)
        val topSamples = ArrayList<Float>(12)
        val bottomSamples = ArrayList<Float>(12)
        var topY = Float.MAX_VALUE
        var bottomY = -1f
        var rows = 0

        for (my in 0 until maskHeight step yStep) {
            var left = maskWidth
            var right = -1
            for (mx in 0 until maskWidth step xStep) {
                val index = my * maskWidth + mx
                if (index < confidence.size && confidence[index] > 0.62f) {
                    left = min(left, mx)
                    right = max(right, mx)
                }
            }

            if (right < left || right - left < max(2, maskWidth / 80)) continue

            val imageY = my.toFloat() / max(1, maskHeight - 1) * (imageHeight - 1)
            val leftX = left.toFloat() / max(1, maskWidth - 1) * (imageWidth - 1)
            val rightX = right.toFloat() / max(1, maskWidth - 1) * (imageWidth - 1)

            leftBoundary.add(leftX to imageY)
            rightBoundary.add(rightX to imageY)
            rows++

            if (imageY < topY) {
                topY = imageY
                topSamples.clear()
            }
            if (imageY - topY <= yStep * imageHeight.toFloat() / max(1, maskHeight - 1) * 2f) {
                topSamples.add((leftX + rightX) * 0.5f)
            }

            if (imageY > bottomY) {
                bottomY = imageY
                bottomSamples.clear()
            }
            if (bottomY - imageY <= yStep * imageHeight.toFloat() / max(1, maskHeight - 1) * 2f) {
                bottomSamples.add((leftX + rightX) * 0.5f)
            }
        }

        if (rows < 10 || bottomY < 0f) return

        val topX = if (topSamples.isNotEmpty()) topSamples.average().toFloat() else imageWidth / 2f
        val bottomX = if (bottomSamples.isNotEmpty()) bottomSamples.average().toFloat() else imageWidth / 2f

        val points = ArrayList<Float>((leftBoundary.size + rightBoundary.size) * 2)
        leftBoundary.forEach {
            points.add(it.first)
            points.add(it.second)
        }
        rightBoundary.forEach {
            points.add(it.first)
            points.add(it.second)
        }

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

        val endpoints = FloatArray(4)
        try {
            frame.transformCoordinates2d(
                Coordinates2d.IMAGE_PIXELS,
                floatArrayOf(topX, topY, bottomX, bottomY),
                Coordinates2d.VIEW,
                endpoints
            )
        } catch (_: Exception) {
            return
        }

        val topScreenX = endpoints[0]
        val topScreenY = endpoints[1]
        val bottomScreenX = endpoints[2]
        val bottomScreenY = endpoints[3]

        val topHit = findDepthHit(frame, topScreenX, topScreenY)
        val bottomHit = findDepthHit(frame, bottomScreenX, bottomScreenY)
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
                    measurementOverlay.setFirstPoint(bottomScreenX, bottomScreenY, false)
                    measurementOverlay.setSecondPoint(topScreenX, topScreenY)
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
        val targetPlane = frame.hitTest(centerX, centerY).asSequence()
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
        val now = SystemClock.elapsedRealtime()
        if ((measureMode == MeasureMode.FLOOR || measureMode == MeasureMode.AREA || measureMode == MeasureMode.CUSTOM_AREA) &&
            now - lastAutoAreaUpdateMs > 500L) {
            lastAutoAreaUpdateMs = now
            updateAutoPlaneMeasurement(plane)
        }
    }

    private fun scanNow() {
        if (measureMode == MeasureMode.AREA || measureMode == MeasureMode.FLOOR) {
            val frame = latestFrame
            val session = arSession
            val plane = if (frame != null && session != null) {
                val cx = measurementOverlay.width / 2f
                val cy = measurementOverlay.height / 2f
                frame.hitTest(cx, cy).asSequence()
                    .mapNotNull { it.trackable as? Plane }
                    .firstOrNull { it.trackingState == TrackingState.TRACKING && it.type == Plane.Type.HORIZONTAL_UPWARD_FACING }
                    ?: session.getAllTrackables(Plane::class.java)
                        .filter { it.trackingState == TrackingState.TRACKING && it.type == Plane.Type.HORIZONTAL_UPWARD_FACING }
                        .maxByOrNull { it.extentX * it.extentZ }
            } else null
            if (plane != null) {
                updateAutoPlaneMeasurement(plane)
                statusText.text = "Scan complete • Review the green outline. Use Pen or Eraser to edit."
            } else {
                statusText.text = "Scanning… move slowly over the floor and try again."
            }
        } else if (measureMode == MeasureMode.KITCHEN_TOP) {
            statusText.text = "Kitchen Top • Tap the countertop to scan its outline."
        } else {
            resetMeasurement()
        }
    }

    private fun setEditTool(tool: EditTool) {
        editTool = if (editTool == tool) EditTool.NONE else tool
        if (measureMode == MeasureMode.AREA || measureMode == MeasureMode.FLOOR || measureMode == MeasureMode.KITCHEN_TOP) {
            if (editableAreaWorld.isEmpty() && lastAutoPolygonWorld.size >= 3) {
                editableAreaWorld.clear()
                editableAreaWorld.addAll(lastAutoPolygonWorld)
                editableAreaScreen.clear()
                val frame = latestFrame
                val plane = kitchenTopPlane
                if (frame != null && plane != null && measureMode == MeasureMode.KITCHEN_TOP) {
                    editableAreaScreen.addAll(projectPlanePolygon(frame, plane))
                } else {
                    editableAreaScreen.addAll(projectCurrentAutoPolygon(frame))
                }
                measurementOverlay.setAreaPoints(editableAreaScreen)
                editingAutoArea = true
            }
        }
        statusText.text = when (editTool) {
            EditTool.PEN -> "Pen active • Tap the surface to add a corner."
            EditTool.ERASER -> "Eraser active • Tap a point to remove it."
            EditTool.NONE -> "Edit complete • Review the outline or tap Scan Now."
        }
    }

    private fun projectCurrentAutoPolygon(frame: Frame?): List<Pair<Float, Float>> {
        if (frame == null || lastAutoPolygonWorld.size < 3) return emptyList()
        val view = FloatArray(16)
        val projection = FloatArray(16)
        val pv = FloatArray(16)
        frame.camera.getViewMatrix(view, 0)
        frame.camera.getProjectionMatrix(projection, 0, 0.01f, 100f)
        android.opengl.Matrix.multiplyMM(pv, 0, projection, 0, view, 0)
        return lastAutoPolygonWorld.mapNotNull { p ->
            val clip = FloatArray(4)
            android.opengl.Matrix.multiplyMV(clip, 0, pv, 0, floatArrayOf(p.x, p.y, p.z, 1f), 0)
            if (clip[3] <= 0f) null else {
                val nx = clip[0] / clip[3]
                val ny = clip[1] / clip[3]
                ((nx + 1f) * 0.5f * measurementOverlay.width) to
                    ((1f - ny) * 0.5f * measurementOverlay.height)
            }
        }
    }

    private fun handleAreaEditTap(x: Float, y: Float): Boolean {
        if (editTool == EditTool.NONE) return false
        if (editTool == EditTool.ERASER) {
            if (editableAreaScreen.size <= 3) return true
            val index = editableAreaScreen.indices.minByOrNull { i ->
                val dx = editableAreaScreen[i].first - x
                val dy = editableAreaScreen[i].second - y
                dx * dx + dy * dy
            } ?: return true
            val p = editableAreaScreen[index]
            val dx = p.first - x
            val dy = p.second - y
            if (dx * dx + dy * dy <= 70f * 70f) {
                editableAreaScreen.removeAt(index)
                editableAreaWorld.removeAt(index)
                measurementOverlay.setAreaPoints(editableAreaScreen)
                statusText.text = "Point removed • Eraser active."
            } else {
                statusText.text = "Tap closer to a corner to erase it."
            }
            return true
        }

        val frame = latestFrame ?: return true
        val hit = findSurfaceHit(frame, x, y) ?: return true
        val pose = hit.hitPose
        editableAreaWorld.add(LocalPoint(pose.tx(), pose.ty(), pose.tz()))
        editableAreaScreen.add(x to y)
        measurementOverlay.setAreaPoints(editableAreaScreen)
        statusText.text = "Corner added • Pen active."
        return true
    }

    private fun findSurfaceHit(frame: Frame, x: Float, y: Float): HitResult? {
        val offsets = floatArrayOf(0f, -12f, 12f, -24f, 24f)
        val hits = offsets.flatMap { ox -> offsets.map { oy -> frame.hitTest(x + ox, y + oy) } }
            .flatten().filter { it.trackable?.trackingState == TrackingState.TRACKING }
        return hits.firstOrNull { (it.trackable as? Plane)?.type == Plane.Type.HORIZONTAL_UPWARD_FACING }
            ?: hits.firstOrNull { it.trackable is DepthPoint }
            ?: hits.firstOrNull { it.trackable is com.google.ar.core.Point }
    }

    private fun undoAreaEdit() {
        if (editableAreaWorld.isNotEmpty()) {
            editableAreaWorld.removeAt(editableAreaWorld.lastIndex)
            if (editableAreaScreen.isNotEmpty()) editableAreaScreen.removeAt(editableAreaScreen.lastIndex)
            measurementOverlay.setAreaPoints(editableAreaScreen)
            statusText.text = "Last area point removed."
        }
    }

    private fun finishAreaEdit() {
        if (editableAreaWorld.size < 3) {
            Toast.makeText(this, "Keep at least 3 points in the area.", Toast.LENGTH_SHORT).show()
            return
        }
        lastAutoPolygonWorld = editableAreaWorld.toList()
        lastAutoAreaM2 = polygonArea(lastAutoPolygonWorld)
        measurementOverlay.setAreaPoints(editableAreaScreen, true)
        measurementOverlay.setAutoFloorOutline(emptyList())
        distanceText.visibility = View.VISIBLE
        scanNowButton.visibility = View.GONE
        distanceText.text = String.format(Locale.US, "%.2f m²\n%.1f ft²", lastAutoAreaM2, lastAutoAreaM2 * 10.7639104f)
        statusText.text = "Area edited • Pen adds, Eraser removes. Tap Save to store it."
        editTool = EditTool.NONE
    }

    private fun updateAutoPlaneMeasurement(plane: Plane) {
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
            val x1 = local[i]
            val z1 = local[i + 1]
            val x2 = local[j]
            val z2 = local[j + 1]
            area += x1 * z2 - x2 * z1
            minX = min(minX, x1)
            maxX = max(maxX, x1)
            minZ = min(minZ, z1)
            maxZ = max(maxZ, z1)
        }
        area = abs(area) / 2f
        val length = maxX - minX
        val width = maxZ - minZ
        lastAutoAreaM2 = area
        lastAutoPolygonWorld = planeWorldPoints(plane)
        if (area > 0.01f) {
            distanceText.visibility = View.VISIBLE
            scanNowButton.visibility = View.GONE
            segmentText.visibility = View.GONE
            distanceText.text = String.format(
                Locale.US,
                "%.2f m²\n%.1f ft²\nL %.1f ft × W %.1f ft",
                area,
                area * 10.7639104f,
                length * 3.28084f,
                width * 3.28084f
            )
            statusText.text = if (measureMode == MeasureMode.FLOOR) {
                "Floor detected • Green outline is the measured area."
            } else {
                "Auto area detected • Green outline is the measured area."
            }
        }
    }

    private fun selectMode(mode: MeasureMode) {
        measureMode = mode
        modeText.text = mode.label.uppercase(Locale.US) + " MODE"
        findViewById<Button>(R.id.floorButton).alpha = if (mode == MeasureMode.FLOOR) 1f else 0.60f
        findViewById<Button>(R.id.kitchenTopButton).alpha = if (mode == MeasureMode.KITCHEN_TOP) 1f else 0.60f
        findViewById<Button>(R.id.heightButton).alpha = if (mode == MeasureMode.HEIGHT) 1f else 0.60f
        findViewById<Button>(R.id.directButton).alpha = if (mode == MeasureMode.DIRECT) 1f else 0.60f
        findViewById<Button>(R.id.areaButton).alpha = if (mode == MeasureMode.AREA) 1f else 0.60f
        findViewById<Button>(R.id.customAreaButton).alpha = if (mode == MeasureMode.CUSTOM_AREA) 1f else 0.60f
        findViewById<View>(R.id.areaTools).visibility =
            if (mode == MeasureMode.AREA || mode == MeasureMode.CUSTOM_AREA || mode == MeasureMode.KITCHEN_TOP) View.VISIBLE else View.GONE
        resetMeasurement()
    }

    private fun measureAt(x: Float, y: Float) {
        if (handleAreaEditTap(x, y)) return
        if (measureMode == MeasureMode.CUSTOM_AREA) {
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
            distanceText.visibility = View.VISIBLE
            scanNowButton.visibility = View.GONE
            distanceText.text = "A"
            segmentText.visibility = View.VISIBLE
            segmentText.text = "A • tap the second point"
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
            MeasureMode.AREA, MeasureMode.CUSTOM_AREA -> 0f
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
        lastAutoAreaM2 = area
        lastAutoPolygonWorld = planeWorldPoints(plane)
        distanceText.visibility = View.VISIBLE
        scanNowButton.visibility = View.GONE
        segmentText.visibility = View.GONE
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

    private fun planeWorldPoints(plane: Plane): List<LocalPoint> {
        val polygon = plane.polygon
        if (!polygon.hasRemaining()) return emptyList()
        polygon.rewind()
        val local = FloatArray(polygon.remaining())
        polygon.get(local)
        val model = FloatArray(16)
        plane.centerPose.toMatrix(model, 0)
        val result = mutableListOf<LocalPoint>()
        for (i in local.indices step 2) {
            val world = FloatArray(4)
            android.opengl.Matrix.multiplyMV(
                world, 0, model, 0,
                floatArrayOf(local[i], 0f, local[i + 1], 1f), 0
            )
            result.add(LocalPoint(world[0], world[1], world[2]))
        }
        return result
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

        val exactHits = frame.hitTest(x, y).filter { it.trackable?.trackingState == TrackingState.TRACKING }
        val hit = exactHits.firstOrNull {
            (it.trackable as? Plane)?.type == Plane.Type.HORIZONTAL_UPWARD_FACING
        } ?: exactHits.firstOrNull { it.trackable is DepthPoint }
            ?: hits.firstOrNull {
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

            MeasureMode.AREA, MeasureMode.CUSTOM_AREA -> null
        }
    }

    private fun createAnchorSafely(hit: HitResult): Anchor? =
        try { hit.createAnchor() } catch (_: Exception) { null }

    private fun showDistance(meters: Float) {
        val centimeters = meters * 100f
        val totalInches = meters * 39.3700787f
        val feet = (totalInches / 12f).toInt()
        val inches = totalInches - feet * 12f
        statusText.text = measureMode.label + " measured • Tap Scan Now for a new measurement."
        distanceText.visibility = View.VISIBLE
        scanNowButton.visibility = View.GONE
        distanceText.text = String.format(Locale.US, "%.2f m\n%.1f cm\n%d ft %.1f in", meters, centimeters, feet, inches)
        segmentText.visibility = View.VISIBLE
        segmentText.text = String.format(Locale.US, "A ↔ B  %.2f m  •  %.1f cm  •  %d ft %.1f in", meters, centimeters, feet, inches)
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
        lastAutoAreaUpdateMs = 0L
        lastAutoPolygonWorld = emptyList()
        lastAutoAreaM2 = 0f
        editableAreaWorld.clear()
        editableAreaScreen.clear()
        editingAutoArea = false
        editTool = EditTool.NONE
        measurementOverlay.clear()
        findViewById<Button>(R.id.finishAreaButton)?.visibility = View.GONE
        findViewById<View>(R.id.areaTools)?.visibility = if (
            measureMode == MeasureMode.AREA || measureMode == MeasureMode.CUSTOM_AREA || measureMode == MeasureMode.KITCHEN_TOP
        ) View.VISIBLE else View.GONE
        scanNowButton.visibility = View.VISIBLE
        distanceText.visibility = View.GONE
        segmentText.visibility = View.GONE
        if (::statusText.isInitialized) statusText.text = measureMode.label + " mode • Tap Scan Now or the first point."
    }

    private fun currentProject(): LocalProject? =
        projects.firstOrNull { it.id == currentProjectId }

    private fun updateProjectStatus() {
        val project = currentProject()
        if (::statusText.isInitialized) {
            statusText.text = if (project != null) {
                "${measureMode.label} mode • Project: ${project.name}"
            } else {
                "${measureMode.label} mode • No project selected"
            }
        }
    }

    private fun showProjects() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8, 4, 8, 4)
        }

        val current = TextView(this).apply {
            text = currentProject()?.let { "Current project: ${it.name}" } ?: "No project selected"
            textSize = 16f
            setPadding(8, 8, 8, 12)
        }
        box.addView(current)

        projects.forEach { project ->
            val button = Button(this).apply {
                text = "${project.name}  •  ${project.measurements.size} measurements  •  ${project.photos.size} photos"
                isAllCaps = false
                setOnClickListener {
                    currentProjectId = project.id
                    getSharedPreferences("local_projects_ui", MODE_PRIVATE).edit().putString("current_project_id", project.id).apply()
                    updateProjectStatus()
                    Toast.makeText(this@MainActivity, "Project selected: ${project.name}", Toast.LENGTH_SHORT).show()
                }
            }
            box.addView(button)
        }

        val newButton = Button(this).apply {
            text = "＋ NEW PROJECT"
            setOnClickListener { showCreateProjectDialog() }
        }
        val photoButton = Button(this).apply {
            text = "＋ ADD SITE PHOTO"
            setOnClickListener {
                if (currentProject() == null) Toast.makeText(this@MainActivity, "Select a project first.", Toast.LENGTH_SHORT).show()
                else photoPickerLauncher.launch(arrayOf("image/*"))
            }
        }
        val locationButton = Button(this).apply {
            text = "📍 SAVE CURRENT LOCATION"
            setOnClickListener { saveCurrentProjectLocation() }
        }
        val exportButton = Button(this).apply {
            text = "EXPORT DXF"
            setOnClickListener { exportCurrentProjectDxf() }
        }
        box.addView(newButton)
        box.addView(photoButton)
        box.addView(locationButton)
        box.addView(exportButton)

        AlertDialog.Builder(this)
            .setTitle("Local Projects")
            .setView(box)
            .setPositiveButton("DONE", null)
            .show()
    }

    private fun showCreateProjectDialog() {
        val input = EditText(this).apply {
            hint = "Project name"
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle("New Project")
            .setView(input)
            .setNegativeButton("CANCEL", null)
            .setPositiveButton("CREATE") { _, _ ->
                val project = localStore.addProject(projects, input.text.toString())
                currentProjectId = project.id
                getSharedPreferences("local_projects_ui", MODE_PRIVATE).edit().putString("current_project_id", project.id).apply()
                updateProjectStatus()
                Toast.makeText(this, "Project created: ${project.name}", Toast.LENGTH_SHORT).show()
                saveCurrentProjectLocation()
            }
            .show()
    }

    private fun saveCurrentProjectLocation() {
        val project = currentProject() ?: run {
            Toast.makeText(this, "Create/select a project first.", Toast.LENGTH_SHORT).show()
            return
        }
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) {
            locationPermissionLauncher.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ))
            return
        }
        try {
            val manager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val location = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .asSequence()
                .mapNotNull { provider -> try { manager.getLastKnownLocation(provider) } catch (_: SecurityException) { null } }
                .maxByOrNull { it.time }
            if (location != null) {
                localStore.setLocation(project, location.latitude, location.longitude, projects)
                Toast.makeText(this, "Location saved to ${project.name}.", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "No recent location available. Turn on Location and try again.", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Could not save location: " + e.message, Toast.LENGTH_LONG).show()
        }
    }

    private fun saveCurrentMeasurement() {
        val project = currentProject() ?: run {
            showProjects()
            Toast.makeText(this, "Create/select a project, then save the measurement.", Toast.LENGTH_LONG).show()
            return
        }

        val points = mutableListOf<LocalPoint>()
        var area: Float? = null
        when {
            measureMode == MeasureMode.CUSTOM_AREA && areaAnchors.size >= 3 -> {
                points.addAll(areaAnchors.map { p -> LocalPoint(p.pose.tx(), p.pose.ty(), p.pose.tz()) })
                area = polygonArea(points)
            }
            (measureMode == MeasureMode.AREA || measureMode == MeasureMode.FLOOR || measureMode == MeasureMode.KITCHEN_TOP) &&
                lastAutoPolygonWorld.size >= 3 -> {
                points.addAll(lastAutoPolygonWorld)
                area = lastAutoAreaM2.takeIf { it > 0f } ?: polygonArea(points)
            }
            firstAnchor != null && secondAnchor != null -> {
                val a = firstAnchor!!.pose
                val b = secondAnchor!!.pose
                points.add(LocalPoint(a.tx(), a.ty(), a.tz()))
                points.add(LocalPoint(b.tx(), b.ty(), b.tz()))
            }
            else -> {
                Toast.makeText(this, "Complete a measurement first.", Toast.LENGTH_SHORT).show()
                return
            }
        }

        val defaultTitle = "${measureMode.label} ${project.measurements.size + 1}"
        val input = EditText(this).apply {
            setText(defaultTitle)
            setSelectAllOnFocus(true)
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle("Save Measurement")
            .setMessage("Saved locally on this phone.")
            .setView(input)
            .setNegativeButton("CANCEL", null)
            .setPositiveButton("SAVE") { _, _ ->
                localStore.addMeasurement(
                    project,
                    LocalMeasurement(
                        title = input.text.toString().ifBlank { defaultTitle },
                        mode = measureMode.label,
                        summary = distanceText.text?.toString() ?: "",
                        points = points,
                        areaM2 = area
                    ),
                    projects
                )
                Toast.makeText(this, "Measurement saved locally.", Toast.LENGTH_SHORT).show()
                updateProjectStatus()
            }
            .show()
    }

    private fun polygonArea(points: List<LocalPoint>): Float {
        if (points.size < 3) return 0f
        var area = 0f
        for (i in points.indices) {
            val j = (i + 1) % points.size
            area += points[i].x * points[j].z - points[j].x * points[i].z
        }
        return abs(area) / 2f
    }

    private fun exportCurrentProjectDxf() {
        val project = currentProject() ?: run {
            Toast.makeText(this, "Create/select a project first.", Toast.LENGTH_SHORT).show()
            return
        }
        if (project.measurements.isEmpty()) {
            Toast.makeText(this, "Save at least one measurement first.", Toast.LENGTH_SHORT).show()
            return
        }
        pendingDxf = DxfExporter.export(project)
        dxfCreatorLauncher.launch(project.name.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".dxf")
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
