package com.gaslog.app

import android.content.Context

/** Small settings stored privately on this phone. */
class Prefs(context: Context) {
    private val p = context.getSharedPreferences("gaslog", Context.MODE_PRIVATE)

    var sheetId: String?
        get() = p.getString("sheetId", null)
        set(v) {
            p.edit().putString("sheetId", v).apply()
        }

    var lastCar: String?
        get() = p.getString("lastCar", null)
        set(v) {
            p.edit().putString("lastCar", v).apply()
        }

    var geminiKey: String
        get() = p.getString("geminiKey", null).orEmpty()
        set(v) {
            p.edit().putString("geminiKey", v).apply()
        }
}
