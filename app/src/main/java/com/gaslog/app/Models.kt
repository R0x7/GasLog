package com.gaslog.app

import java.time.LocalDate

/** One tab in the spreadsheet. */
data class Tab(
    val id: Int,
    val title: String,
    val rowCount: Int,
    val colCount: Int,
    val isGrid: Boolean,
)

/** One logged fill-up read back from a car tab. */
data class Fill(
    val row: Int,
    val date: LocalDate?,
    val odo: Double?,
    val tripA: Double?,
    val place: String,
    val octane: Double?,
    val ppg: Double?,
    val gallons: Double?,
    val cost: Double?,
    val notes: String,
) {
    val partial: Boolean get() = Notes.isPartial(notes)
}

enum class Service(val title: String, val headerMatch: String, val header: String, val noteWord: String) {
    OIL("Oil change", "oil change", "OIL CHANGES", "oil change"),
    TIRES("Tire rotation", "tire rotation", "Tire Rotation", "tire rotation"),
}

/** Where a service is recorded in a tab, and the last mileage found there. */
data class ServiceInfo(
    val service: Service,
    val col: Int,          // 1-based column of the header ("OIL CHANGES")
    val mileageCol: Int?,  // the "Mileage" column right after it, if any
    val lastMileage: Double?,
    val lastUsedRow: Int,  // last row with anything in these columns (0 if none)
)

/** A tab that looks like a gas log (date | trip/odometer | trip a | ...). */
data class Car(
    val name: String,
    val tabId: Int,
    val nextRow: Int,
    val lastOdo: Double?,
    val lastDate: String,
    val lastOctane: Double?,
    val fills: List<Fill> = emptyList(),
    val services: Map<Service, ServiceInfo> = emptyMap(),
)

data class LogConfig(
    val title: String,
    val cars: List<Car>,
    val places: List<String>,
)

/** What Gemini could see in the photos. Anything not visible is null. */
data class Reading(
    val ppg: Double?,
    val gallons: Double?,
    val cost: Double?,
    val octane: Double?,
    val station: String,
    val odo: Double?,
    val tripA: Double?,
    val notes: String,
)

/** One fill-up, ready to write. [dateSerial] is a spreadsheet date number. */
data class Entry(
    val dateSerial: Double,
    val odo: Double?,
    val tripA: Double?,
    val place: String,
    val octane: Double?,
    val ppg: Double?,
    val gallons: Double?,
    val cost: Double?,
    val notes: String,
)

data class SaveResult(
    val car: String,
    val row: Int,
    val mpg: Double?,
    val perMile: Double?,
    val queued: Boolean = false,
    val edited: Boolean = false,
)

/** The notes column doubles as the partial-fill flag ("partial fill; anything else"). */
object Notes {
    private const val TAG = "partial fill"
    fun isPartial(notes: String) = notes.contains("partial", ignoreCase = true)
    fun compose(notes: String, partial: Boolean): String {
        val rest = strip(notes)
        return when {
            partial && rest.isNotEmpty() -> "$TAG; $rest"
            partial -> TAG
            else -> rest
        }
    }
    fun strip(notes: String): String =
        notes.replace(Regex("(?i)partial( fill)?;?\\s*"), "").trim()
}
