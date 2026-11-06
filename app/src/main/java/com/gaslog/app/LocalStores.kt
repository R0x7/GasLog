package com.gaslog.app

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Remembers where each station is, so the place can be pre-filled next time. Stays on this phone. */
class StationMemory(private val prefs: Prefs) {
    data class Spot(val lat: Double, val lng: Double, val place: String)

    fun all(): List<Spot> = runCatching {
        val arr = JSONArray(prefs.stationsJson)
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Spot(o.getDouble("lat"), o.getDouble("lng"), o.getString("place"))
        }
    }.getOrDefault(emptyList())

    fun nearest(lat: Double, lng: Double, maxMeters: Double = RADIUS_M): Spot? =
        all().map { it to distanceMeters(lat, lng, it.lat, it.lng) }
            .filter { it.second <= maxMeters }
            .minByOrNull { it.second }?.first

    fun remember(lat: Double, lng: Double, place: String) {
        val name = place.trim()
        if (name.isEmpty()) return
        val spots = all().filter { distanceMeters(lat, lng, it.lat, it.lng) > RADIUS_M }.toMutableList()
        spots += Spot(lat, lng, name)
        save(spots.takeLast(300))
    }

    fun count() = all().size

    fun clear() = save(emptyList())

    private fun save(spots: List<Spot>) {
        val arr = JSONArray()
        spots.forEach { arr.put(JSONObject().put("lat", it.lat).put("lng", it.lng).put("place", it.place)) }
        prefs.stationsJson = arr.toString()
    }

    companion object {
        const val RADIUS_M = 150.0

        fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
            val r = 6_371_000.0
            val dLat = Math.toRadians(lat2 - lat1)
            val dLng = Math.toRadians(lng2 - lng1)
            val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2) * sin(dLng / 2)
            return 2 * r * atan2(sqrt(a), sqrt(1 - a))
        }
    }
}

/** A fill-up saved on the phone while offline, sent when the connection is back. */
data class Pending(val id: Long, val sheetId: String, val carName: String, val tabId: Int, val entry: Entry)

class OfflineQueue(private val prefs: Prefs) {
    fun all(): List<Pending> = runCatching {
        val arr = JSONArray(prefs.queueJson)
        (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    fun add(p: Pending) = save(all() + p)

    fun remove(id: Long) = save(all().filter { it.id != id })

    private fun save(items: List<Pending>) {
        val arr = JSONArray()
        items.forEach { arr.put(toJson(it)) }
        prefs.queueJson = arr.toString()
    }

    private fun toJson(p: Pending): JSONObject {
        val e = p.entry
        return JSONObject()
            .put("id", p.id).put("sheetId", p.sheetId).put("carName", p.carName).put("tabId", p.tabId)
            .put("date", e.dateSerial).put("odo", e.odo ?: JSONObject.NULL).put("tripA", e.tripA ?: JSONObject.NULL)
            .put("place", e.place).put("octane", e.octane ?: JSONObject.NULL).put("ppg", e.ppg ?: JSONObject.NULL)
            .put("gallons", e.gallons ?: JSONObject.NULL).put("cost", e.cost ?: JSONObject.NULL).put("notes", e.notes)
    }

    private fun fromJson(o: JSONObject): Pending {
        fun d(k: String): Double? = if (o.isNull(k)) null else o.getDouble(k)
        return Pending(
            id = o.getLong("id"), sheetId = o.getString("sheetId"), carName = o.getString("carName"),
            tabId = o.getInt("tabId"),
            entry = Entry(
                dateSerial = o.getDouble("date"), odo = d("odo"), tripA = d("tripA"), place = o.optString("place"),
                octane = d("octane"), ppg = d("ppg"), gallons = d("gallons"), cost = d("cost"),
                notes = o.optString("notes"),
            ),
        )
    }
}

/** Checks GitHub for a newer release of the app (sideloaded apps don't auto-update). */
object Updates {
    /** The GitHub repo whose releases carry GasLog.apk. */
    const val REPO = "R0x7/GasLog"
    const val APK_URL = "https://github.com/$REPO/releases/latest/download/GasLog.apk"

    data class Release(val build: Int, val name: String)

    /** Blocking. Release tags look like v1.0.12, where 12 is the build number (= versionCode). */
    fun latest(): Release? {
        val text = Http.request(
            "GET", "https://api.github.com/repos/$REPO/releases/latest",
            headers = mapOf("Accept" to "application/vnd.github+json"),
        )
        val j = JSONObject(text)
        val tag = j.optString("tag_name")
        val build = Regex("(\\d+)$").find(tag)?.value?.toIntOrNull() ?: return null
        return Release(build, j.optString("name").ifBlank { tag })
    }
}
