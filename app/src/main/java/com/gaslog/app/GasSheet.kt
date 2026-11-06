package com.gaslog.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.LocalDate
import kotlin.math.abs

/**
 * Gas-log rules for a spreadsheet. Every car is a tab whose columns start:
 *   A date | B trip / odometer | C trip a | D place | E octane | F $/G | G gallons | H cost | I notes
 * Columns after I are the owner's formulas (calc cost, mpg, $/mile, ...). The app never overwrites them;
 * it only copies the formula block down into a new row when that row has none yet.
 * Oil changes / tire rotations live in columns headed "OIL CHANGES" / "Tire Rotation" (+ "Mileage").
 */
object GasSheet {
    private val SUMMARY = setOf("total", "average", "maximum", "minimum")
    private const val NOTES_COL = 9 // column I, 1-based
    private const val MAX_COLS = 30
    private const val HEADER_SCAN = "AZ"

    /** Headers J–N of logs this app creates (lower case). */
    private val TEMPLATE_FORMULA_HEADERS = listOf("calc cost", "mpg", "\$/mile", "gal/100 mile", "diff (cost)")

    /** Header + summary rows (Total/Average/Max/Min) for a new car tab. Data starts on row 6. */
    private val TEMPLATE_ROWS: List<List<Any?>> = listOf(
        listOf("date", "odometer", "trip a", "place", "octane", "\$/G", "gallons", "cost", "notes",
            "calc cost", "mpg", "\$/mile", "Gal/100 Mile", "diff (cost)",
            "OIL CHANGES", "Mileage", "Tire Rotation", "Mileage"),
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
        val heads = sc.batchGet(id, grid.map { quote(it.title) + "!A1:${HEADER_SCAN}1" })
        val carIdx = grid.indices.filter { isCarHeader(heads.getOrNull(it)?.firstOrNull()) }
        val carTabs = carIdx.map { grid[it] }
        val carHeads = carIdx.map { heads.getOrNull(it)?.firstOrNull().orEmpty() }
        val data = sc.batchGet(id, carTabs.map { quote(it.title) + "!A1:I" })

        // Maintenance columns: one range per (car, service) that exists.
        val svcCols = carHeads.map { serviceColumns(it) }
        val svcRanges = mutableListOf<String>()
        val svcKeys = mutableListOf<Pair<Int, Service>>()
        carTabs.forEachIndexed { i, tab ->
            for ((s, cols) in svcCols[i]) {
                svcKeys += i to s
                svcRanges += quote(tab.title) + "!${col(cols.first)}2:${col(cols.second ?: cols.first)}"
            }
        }
        val svcData = sc.batchGet(id, svcRanges)

        val placeCounts = LinkedHashMap<String, Pair<String, Int>>()
        val cars = carTabs.mapIndexed { i, tab ->
            val s = scan(data.getOrElse(i) { emptyList() })
            for (p in s.places) {
                val key = p.lowercase()
                val n = placeCounts[key]?.second ?: 0
                placeCounts[key] = p to (n + 1) // keeps the most recent spelling
            }
            val services = mutableMapOf<Service, ServiceInfo>()
            svcKeys.forEachIndexed { k, (carI, service) ->
                if (carI != i) return@forEachIndexed
                val cols = svcCols[i].getValue(service)
                services[service] = serviceInfo(service, cols, svcData.getOrElse(k) { emptyList() }, s.fills)
            }
            Car(tab.title, tab.id, s.nextRow, s.lastOdo, s.lastDate, s.lastOctane, s.fills, services)
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
        val fills: List<Fill>,
    )

    /** rows = columns A–I from row 1. Next row = first row after the last one with anything in A–H. */
    private fun scan(rows: List<List<Any?>>): Scan {
        var lastFilled = 1
        var lastOdo: Double? = null
        var lastDate = ""
        var runningDate = ""
        var runningLocal: LocalDate? = null
        var lastOctane: Double? = null
        val places = mutableListOf<String>()
        val fills = mutableListOf<Fill>()
        for (i in 1 until rows.size) {
            val r = rows[i]
            if ((0 until 8).any { text(r.getOrNull(it)).isNotEmpty() }) lastFilled = i + 1
            if (text(r.getOrNull(0)).lowercase() in SUMMARY) continue

            val d = text(r.getOrNull(0))
            if (d.isNotEmpty()) {
                runningDate = d
                runningLocal = parseSheetDate(r.getOrNull(0), runningLocal) ?: runningLocal
            }
            val odo = num(r.getOrNull(1))
            if (odo != null && odo > 0) {
                lastOdo = odo
                lastDate = runningDate
            }
            val oct = num(r.getOrNull(4))
            if (oct != null && oct >= 80 && oct <= 100) lastOctane = oct
            val place = text(r.getOrNull(3))
            if (place.isNotEmpty() && odo != null) places += place

            val gallons = num(r.getOrNull(6))
            val cost = num(r.getOrNull(7))
            if (odo != null || gallons != null || cost != null) {
                fills += Fill(
                    row = i + 1, date = runningLocal, odo = odo, tripA = num(r.getOrNull(2)), place = place,
                    octane = oct, ppg = num(r.getOrNull(5)), gallons = gallons, cost = cost,
                    notes = text(r.getOrNull(8)),
                )
            }
        }
        return Scan(lastFilled + 1, lastOdo, lastDate, lastOctane, places, fills)
    }

    /** "9/25/26", "1/19/2025", "11/10" (year from the row before), or a date serial number. */
    fun parseSheetDate(v: Any?, previous: LocalDate?): LocalDate? {
        if (v is Number) {
            val n = v.toLong()
            return if (n in 20_000..80_000) LocalDate.of(1899, 12, 30).plusDays(n) else null
        }
        val s = text(v)
        Regex("^(\\d{1,2})/(\\d{1,2})/(\\d{2}|\\d{4})$").find(s)?.let { m ->
            val (mo, d, y) = m.destructured
            val year = if (y.length == 2) 2000 + y.toInt() else y.toInt()
            return runCatching { LocalDate.of(year, mo.toInt(), d.toInt()) }.getOrNull()
        }
        Regex("^(\\d{1,2})/(\\d{1,2})$").find(s)?.let { m ->
            val (mo, d) = m.destructured
            val base = previous ?: return null
            var date = runCatching { LocalDate.of(base.year, mo.toInt(), d.toInt()) }.getOrNull() ?: return null
            if (date.isBefore(base.minusDays(45))) date = date.plusYears(1)
            return date
        }
        return null
    }

    /** Finds service header columns (after the notes column). Value = (header col, mileage col?) 1-based. */
    private fun serviceColumns(header: List<Any?>): Map<Service, Pair<Int, Int?>> {
        val h = header.map { text(it).lowercase() }
        val out = mutableMapOf<Service, Pair<Int, Int?>>()
        for (s in Service.entries) {
            val idx = h.indices.firstOrNull { it >= NOTES_COL && h[it].contains(s.headerMatch) } ?: continue
            val mileage = if (h.getOrNull(idx + 1) == "mileage") idx + 2 else null
            out[s] = (idx + 1) to mileage
        }
        return out
    }

    private val NUMBER = Regex("\\d{1,3}(?:,\\d{3})+(?:\\.\\d+)?|\\d+(?:\\.\\d+)?")

    /** Last service mileage = the biggest odometer-looking number in those columns. */
    private fun serviceInfo(service: Service, cols: Pair<Int, Int?>, rows: List<List<Any?>>, fills: List<Fill>): ServiceInfo {
        val odos = fills.mapNotNull { it.odo }
        val low = odos.minOrNull()?.let { it - 1000 } ?: 1000.0
        val high = (odos.maxOrNull() ?: Double.MAX_VALUE / 2) + 1000
        var last: Double? = null
        var lastUsed = 0
        rows.forEachIndexed { i, r ->
            if (r.any { text(it).isNotEmpty() }) lastUsed = i + 2
            for (cell in r) {
                val candidates = if (cell is Number) listOf(cell.toDouble())
                else NUMBER.findAll(text(cell)).mapNotNull { it.value.replace(",", "").toDoubleOrNull() }.toList()
                for (n in candidates) if (n in low..high && (last == null || n > last!!)) last = n
            }
        }
        return ServiceInfo(service, cols.first, cols.second, last, lastUsed)
    }

    /* ───────────── writing ───────────── */

    /**
     * Writes a fill-up. New entries go on the next empty row (formulas copied down if missing);
     * with [editRow], that row's A–I are replaced (blank fields are cleared).
     */
    fun save(sc: SheetsClient, id: String, car: Car, e: Entry, editRow: Int? = null): SaveResult {
        val tab = findTab(sc, id, car)
        val q = quote(tab.title)
        val row = editRow ?: scan(sc.batchGet(id, listOf("$q!A1:I")).first()).nextRow

        if (row > tab.rowCount) {
            sc.batchUpdate(id, JSONArray().put(JSONObject().put("appendDimension",
                JSONObject().put("sheetId", tab.id).put("dimension", "ROWS").put("length", 200))))
        }

        // New rows skip blanks (null); edits clear them ("").
        fun cell(v: Any?): Any? = v ?: if (editRow != null) "" else null
        sc.update(id, "$q!A$row:I$row", listOf(listOf(
            e.dateSerial, cell(e.odo), cell(e.tripA), cell(e.place.ifBlank { null }), cell(e.octane),
            cell(e.ppg), cell(e.gallons), cell(e.cost), cell(e.notes.ifBlank { null }),
        )))

        val requests = JSONArray()
        requests.put(JSONObject().put("repeatCell", JSONObject()
            .put("range", grid(tab.id, row - 1, row, 0, 1))
            .put("cell", JSONObject().put("userEnteredFormat", JSONObject()
                .put("numberFormat", JSONObject().put("type", "DATE").put("pattern", "M/d/yy"))))
            .put("fields", "userEnteredFormat.numberFormat")))
        if (editRow == null) addFormulas(sc, id, tab, q, row, requests)
        sc.batchUpdate(id, requests)

        val res = sc.batchGet(id, listOf("$q!J1:O1", "$q!J$row:O$row"))
        val head = res.getOrElse(0) { emptyList() }.firstOrNull().orEmpty().map { text(it).lowercase() }
        val vals = res.getOrElse(1) { emptyList() }.firstOrNull().orEmpty()
        fun pick(name: String): Double? = head.indexOf(name).takeIf { it >= 0 }?.let { num(vals.getOrNull(it)) }
        return SaveResult(tab.title, row, pick("mpg"), pick("\$/mile"), edited = editRow != null)
    }

    /** Clears A–I of [row] (an entry just saved), after checking it still holds that odometer reading. */
    fun clearRow(sc: SheetsClient, id: String, car: Car, row: Int, expectOdo: Double?) {
        val tab = findTab(sc, id, car)
        val q = quote(tab.title)
        val current = sc.batchGet(id, listOf("$q!A$row:I$row")).first().firstOrNull().orEmpty()
        val odo = num(current.getOrNull(1))
        if (expectOdo != null && (odo == null || abs(odo - expectOdo) > 0.5)) {
            throw IOException("Row $row changed in the sheet since saving, so it wasn't touched.")
        }
        sc.batchClear(id, listOf("$q!A$row:I$row"))
    }

    /**
     * Logs an oil change / tire rotation: a note ("oil change 11/5/26") plus the odometer, on the next free
     * row of that service's columns. Adds the columns to the right of the existing headers if missing.
     */
    fun logService(sc: SheetsClient, id: String, car: Car, service: Service, odo: Double, dateText: String) {
        val tab = findTab(sc, id, car)
        val q = quote(tab.title)
        val header = sc.batchGet(id, listOf("$q!A1:${HEADER_SCAN}1")).first().firstOrNull().orEmpty()
        val cols: Pair<Int, Int?> = serviceColumns(header)[service] ?: run {
            val lastHeader = header.indexOfLast { text(it).isNotEmpty() } + 1
            val start = maxOf(lastHeader + 1, NOTES_COL + 6)
            val block = sc.batchGet(id, listOf("$q!${col(start)}1:${col(start + 13)}")).first()
            fun empty(x: Int) = block.all { text(it.getOrNull(x - start)).isEmpty() }
            val free = (start until start + 13).firstOrNull { empty(it) && empty(it + 1) }
                ?: throw IOException("Couldn't find two empty columns for ${service.title.lowercase()}s.")
            if (free + 1 > tab.colCount) {
                sc.batchUpdate(id, JSONArray().put(JSONObject().put("appendDimension",
                    JSONObject().put("sheetId", tab.id).put("dimension", "COLUMNS").put("length", free + 1 - tab.colCount))))
            }
            sc.update(id, "$q!${col(free)}1:${col(free + 1)}1", listOf(listOf(service.header, "Mileage")))
            free to (free + 1)
        }

        val (c, mc) = cols
        val used = sc.batchGet(id, listOf("$q!${col(c)}2:${col(mc ?: c)}")).first()
        val lastUsed = used.indexOfLast { r -> r.any { text(it).isNotEmpty() } }.let { if (it < 0) 0 else it + 2 }
        val row = maxOf(6, lastUsed + 1)
        if (mc != null) {
            sc.update(id, "$q!${col(c)}$row:${col(mc)}$row", listOf(listOf("${service.noteWord} $dateText", odo)))
        } else {
            sc.update(id, "$q!${col(c)}$row", listOf(listOf(odo)))
        }
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
        sc.update(id, quote(carName) + "!A1:R5", TEMPLATE_ROWS)
        return id
    }

    fun addCar(sc: SheetsClient, id: String, carName: String) {
        val props = JSONObject()
            .put("title", carName)
            .put("gridProperties", JSONObject().put("frozenRowCount", 1))
        sc.batchUpdate(id, JSONArray().put(JSONObject().put("addSheet", JSONObject().put("properties", props))))
        sc.update(id, quote(carName) + "!A1:R5", TEMPLATE_ROWS)
    }

    /* ───────────── helpers ───────────── */

    private fun findTab(sc: SheetsClient, id: String, car: Car): Tab =
        sc.spreadsheet(id).second.firstOrNull { it.id == car.tabId }
            ?: throw IOException("The tab \"${car.name}\" isn't in the sheet anymore. Reload the sheet in Settings.")

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

    fun col(n: Int): String {
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
