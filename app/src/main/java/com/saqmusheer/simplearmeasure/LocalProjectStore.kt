package com.saqmusheer.simplearmeasure

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class LocalPoint(val x: Float, val y: Float, val z: Float)

data class LocalMeasurement(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val mode: String,
    val summary: String,
    val points: List<LocalPoint> = emptyList(),
    val areaM2: Float? = null,
    val createdAt: Long = System.currentTimeMillis()
)

data class LocalProject(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    var latitude: Double? = null,
    var longitude: Double? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val measurements: MutableList<LocalMeasurement> = mutableListOf(),
    val photos: MutableList<String> = mutableListOf()
)

class LocalProjectStore(context: Context) {
    private val prefs = context.getSharedPreferences("local_projects", Context.MODE_PRIVATE)
    private val key = "projects"

    fun loadProjects(): MutableList<LocalProject> {
        val result = mutableListOf<LocalProject>()
        val array = JSONArray(prefs.getString(key, "[]") ?: "[]")
        for (i in 0 until array.length()) {
            val p = array.optJSONObject(i) ?: continue
            val project = LocalProject(
                id = p.optString("id", UUID.randomUUID().toString()),
                name = p.optString("name", "Project"),
                latitude = if (p.has("latitude")) p.optDouble("latitude") else null,
                longitude = if (p.has("longitude")) p.optDouble("longitude") else null,
                createdAt = p.optLong("createdAt", System.currentTimeMillis())
            )
            val measurements = p.optJSONArray("measurements") ?: JSONArray()
            for (j in 0 until measurements.length()) {
                val m = measurements.optJSONObject(j) ?: continue
                val points = mutableListOf<LocalPoint>()
                val pa = m.optJSONArray("points") ?: JSONArray()
                for (k in 0 until pa.length()) {
                    val point = pa.optJSONObject(k) ?: continue
                    points.add(LocalPoint(
                        point.optDouble("x").toFloat(),
                        point.optDouble("y").toFloat(),
                        point.optDouble("z").toFloat()
                    ))
                }
                project.measurements.add(LocalMeasurement(
                    id = m.optString("id", UUID.randomUUID().toString()),
                    title = m.optString("title", "Measurement"),
                    mode = m.optString("mode", "Unknown"),
                    summary = m.optString("summary", ""),
                    points = points,
                    areaM2 = if (m.has("areaM2")) m.optDouble("areaM2").toFloat() else null,
                    createdAt = m.optLong("createdAt", System.currentTimeMillis())
                ))
            }
            val photos = p.optJSONArray("photos") ?: JSONArray()
            for (j in 0 until photos.length()) photos.optString(j).takeIf { it.isNotBlank() }?.let(project.photos::add)
            result.add(project)
        }
        return result
    }

    fun saveProjects(projects: List<LocalProject>) {
        val array = JSONArray()
        projects.forEach { project ->
            val p = JSONObject()
                .put("id", project.id)
                .put("name", project.name)
                .put("createdAt", project.createdAt)
            project.latitude?.let { p.put("latitude", it) }
            project.longitude?.let { p.put("longitude", it) }

            val measurements = JSONArray()
            project.measurements.forEach { m ->
                val mo = JSONObject()
                    .put("id", m.id)
                    .put("title", m.title)
                    .put("mode", m.mode)
                    .put("summary", m.summary)
                    .put("createdAt", m.createdAt)
                m.areaM2?.let { mo.put("areaM2", it) }
                val points = JSONArray()
                m.points.forEach { point ->
                    points.put(JSONObject().put("x", point.x).put("y", point.y).put("z", point.z))
                }
                mo.put("points", points)
                measurements.put(mo)
            }
            p.put("measurements", measurements)
            val photos = JSONArray()
            project.photos.forEach(photos::put)
            p.put("photos", photos)
            array.put(p)
        }
        prefs.edit().putString(key, array.toString()).apply()
    }

    fun addProject(projects: MutableList<LocalProject>, name: String): LocalProject {
        val project = LocalProject(name = name.trim().ifBlank { "Untitled Project" })
        projects.add(project)
        saveProjects(projects)
        return project
    }

    fun addMeasurement(project: LocalProject, measurement: LocalMeasurement, projects: List<LocalProject>) {
        project.measurements.add(measurement)
        saveProjects(projects)
    }

    fun addPhoto(project: LocalProject, uri: String, projects: List<LocalProject>) {
        if (!project.photos.contains(uri)) project.photos.add(uri)
        saveProjects(projects)
    }

    fun setLocation(project: LocalProject, latitude: Double, longitude: Double, projects: List<LocalProject>) {
        project.latitude = latitude
        project.longitude = longitude
        saveProjects(projects)
    }
}
