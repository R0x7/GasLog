package com.gaslog.app

import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale
import kotlin.math.roundToInt

/** One full tank's worth of driving (partial fills before it are folded in). */
data class Tank(val date: LocalDate?, val row: Int, val mpg: Double, val miles: Double, val gallons: Double)

data class StationPrice(val place: String, val avgPpg: Double, val fills: Int)

data class MonthSpend(val month: YearMonth, val cost: Double)

data class CarStats(
    val tanks: List<Tank>,
    /** Miles ÷ gallons over the last 10 full tanks. */
    val recentMpg: Double?,
    /** Last 12 calendar months, oldest first; months with no fill-ups are 0. */
    val months: List<MonthSpend>,
    val yearCost: Double,
    val yearMiles: Double,
    val costPerMile: Double?,
    /** Stations with 2+ fill-ups in the window, cheapest first. */
    val stations: List<StationPrice>,
    val stationWindow: String,
    val stationHeadline: String?,
    val fillCount: Int,
)

object Stats {
    /** Turns fill-ups into tanks. A partial fill's miles and gallons roll into the next full fill. */
    fun tanks(fills: List<Fill>): List<Tank> {
        val out = mutableListOf<Tank>()
        var miles = 0.0
        var gal = 0.0
        var prevOdo: Double? = null
        for (f in fills) {
            val odo = f.odo
            val trip = f.tripA ?: if (odo != null && prevOdo != null && odo > prevOdo) odo - prevOdo else null
            if (odo != null) prevOdo = odo
            val g = f.gallons
            if (trip == null || g == null || g <= 0.0) {
                miles = 0.0
                gal = 0.0
                continue
            }
            miles += trip
            gal += g
            if (f.partial) continue
            val mpg = miles / gal
            if (gal >= 1.0 && mpg in 5.0..80.0) out += Tank(f.date, f.row, mpg, miles, gal)
            miles = 0.0
            gal = 0.0
        }
        return out
    }

    fun recentMpg(tanks: List<Tank>, count: Int = 10): Double? {
        val last = tanks.takeLast(count)
        val gal = last.sumOf { it.gallons }
        return if (last.isEmpty() || gal <= 0.0) null else last.sumOf { it.miles } / gal
    }

    fun forCar(car: Car, today: LocalDate = LocalDate.now()): CarStats {
        val fills = car.fills
        val tanks = tanks(fills)
        val yearAgo = today.minusYears(1)
        val recent = fills.filter { it.date != null && !it.date.isBefore(yearAgo) }

        val thisMonth = YearMonth.from(today)
        val months = (11 downTo 0).map { back ->
            val m = thisMonth.minusMonths(back.toLong())
            MonthSpend(m, fills.filter { it.date != null && YearMonth.from(it.date) == m }.sumOf { it.cost ?: 0.0 })
        }

        val yearCost = recent.sumOf { it.cost ?: 0.0 }
        val yearMiles = recent.sumOf { it.tripA ?: 0.0 }

        var window = "last 12 months"
        var stations = stationPrices(recent)
        if (stations.size < 2) {
            window = "all time"
            stations = stationPrices(fills)
        }

        return CarStats(
            tanks = tanks,
            recentMpg = recentMpg(tanks),
            months = months,
            yearCost = yearCost,
            yearMiles = yearMiles,
            costPerMile = if (yearMiles > 0) yearCost / yearMiles else null,
            stations = stations,
            stationWindow = window,
            stationHeadline = headline(stations, window),
            fillCount = fills.count { it.gallons != null },
        )
    }

    fun stationPrices(fills: List<Fill>): List<StationPrice> =
        fills.filter { it.place.isNotBlank() && it.ppg != null && it.ppg > 0 }
            .groupBy { it.place.trim().lowercase() }
            .filter { it.value.size >= 2 }
            .map { (_, group) ->
                val name = group.groupingBy { it.place.trim() }.eachCount().maxByOrNull { it.value }!!.key
                StationPrice(name, group.mapNotNull { it.ppg }.average(), group.size)
            }
            .sortedBy { it.avgPpg }

    private fun headline(stations: List<StationPrice>, window: String): String? {
        if (stations.size < 2) return null
        val cheap = stations.first()
        val pricey = stations.last()
        val cents = ((pricey.avgPpg - cheap.avgPpg) * 100).roundToInt()
        if (cents <= 0) return null
        return "${cheap.place} averages ${cents}¢/gal less than ${pricey.place} ($window)."
    }

    /** A warning when this tank is well under the usual mpg, or null. */
    fun mpgDrop(mpg: Double, usual: Double?): String? {
        if (usual == null || usual <= 0) return null
        val drop = (usual - mpg) / usual
        if (drop < 0.15) return null
        return String.format(
            Locale.US,
            "This tank is %d%% below your usual %.1f mpg — worth checking tire pressure, or a sign something's due.",
            (drop * 100).roundToInt(), usual,
        )
    }
}
