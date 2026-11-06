package com.gaslog.app

import android.content.Context

/** Small settings stored privately on this phone. */
class Prefs(context: Context) {
    private val p = context.getSharedPreferences("gaslog", Context.MODE_PRIVATE)

    private fun str(key: String): String? = p.getString(key, null)
    private fun put(key: String, v: String?) = p.edit().putString(key, v).apply()

    var sheetId: String?
        get() = str("sheetId")
        set(v) = put("sheetId", v)

    var lastCar: String?
        get() = str("lastCar")
        set(v) = put("lastCar", v)

    var geminiKey: String
        get() = str("geminiKey").orEmpty()
        set(v) = put("geminiKey", v)

    /** Miles between oil changes / tire rotations, for reminders. */
    var oilInterval: Int
        get() = p.getInt("oilInterval", 5000)
        set(v) = p.edit().putInt("oilInterval", v).apply()

    var tireInterval: Int
        get() = p.getInt("tireInterval", 5000)
        set(v) = p.edit().putInt("tireInterval", v).apply()

    /** True once the "remember stations by location?" question has been answered. */
    var locationAsked: Boolean
        get() = p.getBoolean("locationAsked", false)
        set(v) = p.edit().putBoolean("locationAsked", v).apply()

    /** JSON list of remembered station spots. */
    var stationsJson: String
        get() = str("stations") ?: "[]"
        set(v) = put("stations", v)

    /** JSON list of fill-ups saved while offline. */
    var queueJson: String
        get() = str("queue") ?: "[]"
        set(v) = put("queue", v)

    var lastUpdateCheck: Long
        get() = p.getLong("lastUpdateCheck", 0L)
        set(v) = p.edit().putLong("lastUpdateCheck", v).apply()

    var latestBuild: Int
        get() = p.getInt("latestBuild", 0)
        set(v) = p.edit().putInt("latestBuild", v).apply()

    /** Quick launch (tile, widget, shortcut) opens the live scan instead of the camera app. */
    var quickLive: Boolean
        get() = p.getBoolean("quickLive", true)
        set(v) = p.edit().putBoolean("quickLive", v).apply()
}
