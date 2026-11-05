package com.gaslog.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * Gas-log rules for a spreadsheet. Every car is a tab whose columns start:
 *   A date | B trip / odometer | C trip a | D place | E octane | F $/G | G gallons | H cost | I notes
 * Columns after I are the owner's formulas (calc cost, mpg, $/mile, ...). The app never overwrites them;
 * it only copies the formula block down into a new row when that row has none yet.
 */
object GasSheet {
    private val SUMMARY = setOf("total", "average", "maximum", "minimum")
    private const val NOTES_COL = 9 // column I, 1-based
    private const val MAX_COLS = 30

    /** Headers J–N of logs this app creates (lower case). */
    private val TEMPLATE_FORMULA_HEADERS = listOf("calc cost", "mpg", "\$/mile", "gal/100 mile", "diff (cost)")

    /** Header + summary rows (Total/Average/Max/Min) for a new car tab. Data starts on row 6. */
    private val TEMPLATE_ROWS: List<List<Any?>> = listOf(
        listOf("date", "odometer", "trip a", "place", "octane", "\$/G", "gallons", "cost", "notes",
            "calc cost", "mpg", "\$/mile", "Gal/100 Mile", "diff (cost)"),
        listOf("Total", null, "=SUM(C6:C)", null, null, null, "=SUM(G6:G)", "=SUM(H6:H)", null,
            "=SUM(J6:J)", "=IFERROR(SUM(C6:C)/SUM(G6:G),\"\")", "=IFERROR(SUM(H6:H)/SUM(C6:C),\"\")",
            "=IFERROR(SUM(G6:G)/(SUM(C6:C)/100),\"\")", "=SUM(N6:N)"),
        listOf("Average", null, "=IFERROR(AVERAGE(C6:C),\"\")", null, null, "=IFERROR(AVERAGE(F6:F),\"\")",
            "=IFERROR(AVERAGE(G6:G),\"\")", "=IFERROR(AVERAGE(H6:H),\"\")", null, null,
            "=IFERROR(AVERAGE(K6:K),\"\")", "=IFERROR(AVERAGE(L6:L),\"\")", "=IFERROR(AVERAGE(M6:M),\"\")", null),
        listOf("Maximum", null, "=MAX(C6:C)", null, null, "=MAX(F6:F)", "=MAX(G6:G)", "=MAX(H6:H)", null, null,
            "=MAX(K6:K)", "=MAX(L6:L)", "=MAX(M6:M)", null),
        listOf("Minimum", null, "=MIN(C6:C)", null, null, "=MIN(F6:F)", "=MIN(G6:G)", "=MIN(H6:H)", null, null,
            "=MIN(K6:K)", "=MIN(L6:L)", "=MIN(M6:M)", null),
    )

    private fun templateFormulas(r: Int): List<Any?> = listOf(
        "=G$r*F$r", "=C$r/G$r", "=H$r/C$r", "=G$r/(C$r/100)", "=H$r-J$r",
    )

    /* ───────────── reading ───────────── */

    fun load(sc: SheetsClient, id: String): LogConfig {
        val (title, tabs) = sc.spreadsheet(id)
        val grid = tabs.filter { it.isGrid && !it.title.contains("template", ignoreCase = true) }
        val heads = sc.batchGet(id, grid.map { quote(it.title) + "!A1:C1" })
        val carTabs = grid.filterIndexed { i, _ -> isCarHeader(heads.getOrNull(i)?.firstOrNull()) }
        val data = sc.batchGet(id, carTabs.map { quote(it.title) + "!A1:H" })

        val placeCounts = LinkedHashMap<String, Pair<String, Int>>()
        val cars = carTabs.mapIndexed { i, tab ->
            val s = scan(data.getOrElse(i) { emptyList() })
            for (p in s.places) {
                val key = p.lowercase()
                val n = placeCounts[key]?.second ?: 0
                placeCounts[key] = p to (n + 1) // keeps the most recent spelling
            }
            Car(tab.title, tab.id, s.nextRow, s.lastOdo, s.lastDate, s.lastOctane)
        }
        val places = placeCounts.values.sortedByDescending { it.second }.take(80).map { it.first }
        return LogConfig(title, cars, places)
    }

    private class Scan(
        val nextRow: Int,
        val lastOdo: Double?,
        val lastDate: String,
        val lastOctane: Double?,
        val places: List<String>,
    )

    /** rows = columns A–H from row 1. Next row = first row after the last one with anything in A–H. */
    private fun scan(rows: List<List<Any?>>): Scan {
        var lastFilled = 1
        var lastOdo: Double? = null
        var lastDate = ""
        var runningDate = ""
        var lastOctane: Double? = null
        val places = mutableListOf<String>()
        for (i in 1 until rows.size) {
            val r = rows[i]
            if (r.any { text(it).isNotEmpty() }) lastFilled = i + 1
            if (text(r.getOrNull(0)).lowercase() in SUMMARY) continue

            val d = text(r.getOrNull(0))
            if (d.isNotEmpty()) runningDate = d
            val odo = num(r.getOrNull(1))
            if (odo != null && odo > 0) {
                lastOdo = odo
                lastDate = runningDate
            }
            val oct = num(r.getOrNull(4))
            if (oct != null && oct >= 80 && oct <= 100) lastOctane = oct
            val place = text(r.getOrNull(3))
            if (place.isNotEmpty() && odo != null) places += place
        }
        return Scan(lastFilled + 1, lastOdo, lastDate, lastOctane, places)
    }

    /* ───────────── writing ───────────── */

    fun save(sc: SheetsClient, id: String, car: Car, e: Entry): SaveResult {
        val tab = sc.spreadsheet(id).second.firstOrNull { it.id == car.tabId }
            ?: throw IOException("The tab \"${car.name}\" isn't in the sheet anymore. Close and reopen the app to refresh.")
        val q = quote(tab.title)
        val row = scan(sc.batchGet(id, listOf("$q!A1:H")).first()).nextRow

        if (row > tab.rowCount) {
            sc.batchUpdate(id, JSONArray().put(JSONObject().put("appendDimension",
                JSONObject().put("sheetId", tab.id).put("dimension", "ROWS").put("length", 200))))
        }

        sc.update(id, "$q!A$row:I$row", listOf(listOf(
            e.dateSerial, e.odo, e.tripA, e.place.ifBlank { null }, e.octane, e.ppg, e.gallons, e.cost,
            e.notes.ifBlank { null },
        )))

        val requests = JSONArray()
        requests.put(JSONObject().put("repeatCell", JSONObject()
            .put("range", grid(tab.id, row - 1, row, 0, 1))
            .put("cell", JSONObject().put("userEnteredFormat", JSONObject()
                .put("numberFormat", JSONObject().put("type", "DATE").put("pattern", "M/d/yy"))))
            .put("fields", "userEnteredFormat.numberFormat")))

        addFormulas(sc, id, tab, q, row, requests)
        sc.batchUpdate(id, requests)

        val res = sc.batchGet(id, listOf("$q!J1:O1", "$q!J$row:O$row"))
        val head = res.getOrElse(0) { emptyList() }.firstOrNull().orEmpty().map { text(it).lowercase() }
        val vals = res.getOrElse(1) { emptyList() }.firstOrNull().orEmpty()
        fun pick(name: String): Double? = head.indexOf(name).takeIf { it >= 0 }?.let { num(vals.getOrNull(it)) }
        return SaveResult(tab.title, row, pick("mpg"), pick("\$/mile"))
    }

    /**
     * If the new row has no formula in column J, copy the run of formula cells from the nearest
     * data row above (skipping Total/Average/Max/Min). Only fills cells that are empty.
     * Logs this app created get their formulas written directly.
     */
    private fun addFormulas(sc: SheetsClient, id: String, tab: Tab, q: String, row: Int, requests: JSONArray) {
        val lastCol = minOf(tab.colCount, MAX_COLS)
        if (lastCol <= NOTES_COL) return
        val width = lastCol - NOTES_COL
        val top = maxOf(2, row - 60)
        val got = sc.batchGet(
            id,
            listOf("$q!J$top:${col(lastCol)}$row", "$q!A$top:A$row", "$q!J1:N1"),
            "FORMULA",
        )
        val f = got.getOrElse(0) { emptyList() }
        val labels = got.getOrElse(1) { emptyList() }
        val head = got.getOrElse(2) { emptyList() }.firstOrNull().orEmpty().map { text(it).lowercase() }
        fun cell(r: Int, c: Int): String = text(f.getOrNull(r - top)?.getOrNull(c))
        fun isSummary(r: Int): Boolean = text(labels.getOrNull(r - top)?.getOrNull(0)).lowercase() in SUMMARY

        if (cell(row, 0).isNotEmpty()) return // formulas already there

        val src = (row - 1 downTo top).firstOrNull { !isSummary(it) && cell(it, 0).startsWith("=") }
        if (src != null) {
            var k = 0
            while (k < width && cell(src, k).startsWith("=")) k++
            if ((0 until k).all { cell(row, it).isEmpty() }) {
                requests.put(JSONObject().put("copyPaste", JSONObject()
                    .put("source", grid(tab.id, src - 1, src, NOTES_COL, NOTES_COL + k))
                    .put("destination", grid(tab.id, row - 1, row, NOTES_COL, NOTES_COL + k))
                    .put("pasteType", "PASTE_NORMAL")))
            }
        } else if (head == TEMPLATE_FORMULA_HEADERS && width >= 5) {
            sc.update(id, "$q!J$row:N$row", listOf(templateFormulas(row)))
        }
    }

    /** Makes a new "Gas Log" spreadsheet in the user's Drive with one car tab. Returns its id. */
    fun createLog(sc: SheetsClient, carName: String): String {
        val (id, _) = sc.create("Gas Log", carName)
        sc.update(id, quote(carName) + "!A1:N5", TEMPLATE_ROWS)
        return id
    }

    fun addCar(sc: SheetsClient, id: String, carName: String) {
        val props = JSONObject()
            .put("title", carName)
            .put("gridProperties", JSONObject().put("frozenRowCount", 1))
        sc.batchUpdate(id, JSONArray().put(JSONObject().put("addSheet", JSONObject().put("properties", props))))
        sc.update(id, quote(carName) + "!A1:N5", TEMPLATE_ROWS)
    }

    /* ───────────── helpers ───────────── */

    fun quote(tab: String): String = "'" + tab.replace("'", "''") + "'"

    private fun isCarHeader(r: List<Any?>?): Boolean {
        if (r == null) return false
        val h = (0..2).map { text(r.getOrNull(it)).lowercase() }
        return h[0] == "date" && h[2] == "trip a" && (h[1].startsWith("trip") || h[1].startsWith("odo"))
    }

    fun num(v: Any?): Double? = when (v) {
        null -> null
        is Number -> v.toDouble().takeIf { it.isFinite() }
        else -> v.toString().replace(Regex("[\$,\\s]"), "").takeIf { it.isNotEmpty() }?.toDoubleOrNull()
    }

    fun text(v: Any?): String = v?.toString()?.trim().orEmpty()

    private fun col(n: Int): String {
        var x = n
        val sb = StringBuilder()
        while (x > 0) {
            val m = (x - 1) % 26
            sb.insert(0, 'A' + m)
            x = (x - 1) / 26
        }
        return sb.toString()
    }

    private fun grid(tabId: Int, r0: Int, r1: Int, c0: Int, c1: Int): JSONObject = JSONObject()
        .put("sheetId", tabId)
        .put("startRowIndex", r0)
        .put("endRowIndex", r1)
        .put("startColumnIndex", c0)
        .put("endColumnIndex", c1)
}
