package com.gaslog.app

/** One tab in the spreadsheet. */
data class Tab(
    val id: Int,
    val title: String,
    val rowCount: Int,
    val colCount: Int,
    val isGrid: Boolean,
)

/** A tab that looks like a gas log (date | trip/odometer | trip a | ...). */
data class Car(
    val name: String,
    val tabId: Int,
    val nextRow: Int,
    val lastOdo: Double?,
    val lastDate: String,
    val lastOctane: Double?,
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
)

enum class Slot(val title: String, val icon: String, val label: String) {
    PUMP("Pump", "⛽", "Pump display"),
    TRIP("Trip A", "🅰️", "Dashboard showing Trip A"),
    ODO("Odometer", "🧭", "Dashboard showing the odometer"),
}
