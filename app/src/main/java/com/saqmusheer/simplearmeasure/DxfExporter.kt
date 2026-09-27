package com.saqmusheer.simplearmeasure

import java.util.Locale

object DxfExporter {
    fun export(project: LocalProject): String {
        val sb = StringBuilder()
        sb.append("0\nSECTION\n2\nHEADER\n")
        sb.append("9\n\$ACADVER\n1\nAC1009\n")
        sb.append("9\n\$INSUNITS\n70\n4\n")
        sb.append("0\nENDSEC\n0\nSECTION\n2\nENTITIES\n")
        project.measurements.forEachIndexed { index, measurement ->
            val layer = layerFor(measurement.mode)
            val points = measurement.points
            if (points.size >= 3) {
                for (i in points.indices) {
                    val a = points[i]
                    val b = points[(i + 1) % points.size]
                    line(sb, layer, a.x, a.z, b.x, b.z)
                }
            } else if (points.size == 2) {
                val a = points[0]
                val b = points[1]
                line(sb, layer, a.x, a.z, b.x, b.z)
            }
            if (points.isNotEmpty()) {
                val p = points.first()
                text(sb, "SMA-TEXT", p.x, p.z, measurement.title)
                text(sb, "SMA-TEXT", p.x, p.z + 0.12f, measurement.summary.replace("\n", " / "))
            } else {
                text(sb, "SMA-TEXT", index.toFloat(), 0f, measurement.title)
                text(sb, "SMA-TEXT", index.toFloat(), 0.12f, measurement.summary.replace("\n", " / "))
            }
        }
        text(sb, "SMA-TEXT", 0f, -0.5f, "SimpleARMeasure - ${project.name}")
        sb.append("0\nENDSEC\n0\nEOF\n")
        return sb.toString()
    }

    private fun layerFor(mode: String): String = when {
        mode.contains("Wall", true) -> "SMA-WALL"
        mode.contains("Door", true) -> "SMA-DOOR"
        mode.contains("Window", true) -> "SMA-WINDOW"
        mode.contains("Electrical", true) -> "SMA-ELECTRICAL"
        mode.contains("Plumbing", true) -> "SMA-PLUMBING"
        mode.contains("Furniture", true) -> "SMA-FURNITURE"
        mode.contains("Kitchen", true) -> "SMA-CABINET"
        mode.contains("Area", true) || mode.contains("Floor", true) -> "SMA-FLOOR"
        else -> "SMA-MEASUREMENT"
    }

    private fun line(sb: StringBuilder, layer: String, x1m: Float, z1m: Float, x2m: Float, z2m: Float) {
        sb.append("0\nLINE\n8\n").append(layer).append("\n")
        sb.append("10\n").append(mm(x1m)).append("\n20\n").append(mm(z1m)).append("\n30\n0\n")
        sb.append("11\n").append(mm(x2m)).append("\n21\n").append(mm(z2m)).append("\n31\n0\n")
    }

    private fun text(sb: StringBuilder, layer: String, xm: Float, zm: Float, value: String) {
        sb.append("0\nTEXT\n8\n").append(layer).append("\n")
        sb.append("10\n").append(mm(xm)).append("\n20\n").append(mm(zm)).append("\n30\n0\n")
        sb.append("40\n100\n1\n").append(sanitize(value)).append("\n")
    }

    private fun mm(m: Float): String = String.format(Locale.US, "%.2f", m * 1000f)
    private fun sanitize(value: String): String = value.replace("\n", " ").replace("\r", " ").take(180)
}
