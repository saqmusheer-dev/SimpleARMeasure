package com.saqmusheer.simplearmeasure

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.ar.core.HitResult
import com.google.ar.sceneform.AnchorNode
import com.google.ar.sceneform.math.Vector3
import com.gorisse.thomas.sceneform.ux.ArFragment
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var arFragment: ArFragment
    private lateinit var statusText: TextView
    private lateinit var distanceText: TextView

    private var firstPoint: Vector3? = null
    private var firstAnchorNode: AnchorNode? = null
    private var secondAnchorNode: AnchorNode? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        distanceText = findViewById(R.id.distanceText)
        arFragment = supportFragmentManager
            .findFragmentById(R.id.ar_fragment) as ArFragment

        arFragment.setOnTapArPlaneListener { hitResult: HitResult, _, _ ->
            handlePoint(hitResult)
        }

        findViewById<Button>(R.id.resetButton).setOnClickListener {
            resetMeasurement()
        }
    }

    private fun handlePoint(hitResult: HitResult) {
        val pose = hitResult.hitPose
        val point = Vector3(pose.tx(), pose.ty(), pose.tz())

        if (firstPoint == null) {
            firstPoint = point
            firstAnchorNode = AnchorNode(hitResult.createAnchor()).also {
                it.setParent(arFragment.arSceneView.scene)
            }
            statusText.text = "Point A set. Tap the second point."
            distanceText.text = "Point A"
            return
        }

        if (secondAnchorNode != null) {
            resetMeasurement()
            firstPoint = point
            firstAnchorNode = AnchorNode(hitResult.createAnchor()).also {
                it.setParent(arFragment.arSceneView.scene)
            }
            statusText.text = "Point A set. Tap the second point."
            distanceText.text = "Point A"
            return
        }

        secondAnchorNode = AnchorNode(hitResult.createAnchor()).also {
            it.setParent(arFragment.arSceneView.scene)
        }

        val meters = Vector3.subtract(point, firstPoint!!).length()
        showDistance(meters)
    }

    private fun showDistance(meters: Float) {
        val centimeters = meters * 100f
        val totalInches = meters * 39.3700787f
        val feet = (totalInches / 12f).toInt()
        val inches = totalInches - feet * 12f

        statusText.text = "Measurement complete. Tap a new point or Reset."
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
        firstAnchorNode?.let {
            it.anchor?.detach()
            it.setParent(null)
        }

        secondAnchorNode?.let {
            it.anchor?.detach()
            it.setParent(null)
        }

        firstAnchorNode = null
        secondAnchorNode = null
        firstPoint = null

        statusText.text = "Move your phone slowly to detect a surface."
        distanceText.text = "—"
    }

    override fun onDestroy() {
        resetMeasurement()
        super.onDestroy()
    }
}
