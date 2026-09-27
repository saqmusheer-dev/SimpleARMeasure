package com.saqmusheer.simplearmeasure

import android.content.Context

class LicenseManager(context: Context) {
    private val prefs = context.getSharedPreferences("limra_license", Context.MODE_PRIVATE)
    private val dayMs = 86_400_000L

    fun isLicensed(): Boolean {
        val started = prefs.getLong("started", 0L)
        val days = prefs.getInt("days", 0)
        return started > 0L && days > 0 && System.currentTimeMillis() < started + days * dayMs
    }

    fun activate(key: String): Pair<Boolean,String> {
        val clean = key.trim()
        val days = when(clean) {
            "LimraDigital15" -> 15
            "LimraDigital365" -> 365
            else -> return false to "Invalid activation key."
        }
        if(isLicensed()) return true to "Already activated."
        prefs.edit().putString("key",clean).putLong("started",System.currentTimeMillis()).putInt("days",days).apply()
        return true to "Activated for $days days."
    }
}
