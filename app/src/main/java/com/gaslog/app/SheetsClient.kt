package com.gaslog.app

import org.json.JSONArray
import org.json.JSONObject

/** Thin wrapper over the Google Sheets REST API (v4). Blocking; use off the main thread. */
open class SheetsClient(private val token: String) {
    private val base = "https://sheets.googleapis.com/v4/spreadsheets"

    /** Spreadsheet title and its tabs. */
    open fun spreadsheet(id: String): Pair<String, List<Tab>> {
        val fields = "properties.title,sheets.properties(sheetId,title,sheetType,gridProperties(rowCount,columnCount))"
        val json = JSONObject(Http.request("GET", "$base/${Http.enc(id)}?fields=${Http.enc(fields)}", token))
        val title = json.optJSONObject("properties")?.optString("title").orEmpty()
        val sheets = json.optJSONArray("sheets") ?: JSONArray()
        val tabs = (0 until sheets.length()).map { i ->
            val p = sheets.getJSONObject(i).getJSONObject("properties")
            val g = p.optJSONObject("gridProperties")
            Tab(
                id = p.optInt("sheetId"),
                title = p.optString("title"),
                rowCount = g?.optInt("rowCount") ?: 0,
                colCount = g?.optInt("columnCount") ?: 0,
                isGrid = p.optString("sheetType", "GRID") == "GRID",
            )
        }
        return title to tabs
    }

    /**
     * Reads several A1 ranges at once. Each result is rows of cells (trailing blanks trimmed by Google).
     * render: UNFORMATTED_VALUE (numbers as numbers, dates as display text) or FORMULA.
     */
    open fun batchGet(id: String, ranges: List<String>, render: String = "UNFORMATTED_VALUE"): List<List<List<Any?>>> {
        if (ranges.isEmpty()) return emptyList()
        val query = ranges.joinToString("&") { "ranges=" + Http.enc(it) } +
            "&valueRenderOption=$render&dateTimeRenderOption=FORMATTED_STRING"
        val json = JSONObject(Http.request("GET", "$base/${Http.enc(id)}/values:batchGet?$query", token))
        val valueRanges = json.optJSONArray("valueRanges") ?: JSONArray()
        return (0 until valueRanges.length()).map { rowsOf(valueRanges.getJSONObject(it)) }
    }

    /** Writes rows as if typed (formulas work). A null cell is skipped, leaving what's there. */
    open fun update(id: String, range: String, rows: List<List<Any?>>) {
        val body = JSONObject()
            .put("range", range)
            .put("majorDimension", "ROWS")
            .put("values", toJson(rows))
        Http.request(
            "PUT",
            "$base/${Http.enc(id)}/values/${Http.enc(range)}?valueInputOption=USER_ENTERED",
            token,
            body = body.toString(),
        )
    }

    open fun batchUpdate(id: String, requests: JSONArray): JSONObject {
        val body = JSONObject().put("requests", requests)
        return JSONObject(Http.request("POST", "$base/${Http.enc(id)}:batchUpdate", token, body = body.toString()))
    }

    /** Clears values (not formatting) in the given ranges. */
    open fun batchClear(id: String, ranges: List<String>) {
        val body = JSONObject().put("ranges", JSONArray().apply { ranges.forEach { put(it) } })
        Http.request("POST", "$base/${Http.enc(id)}/values:batchClear", token, body = body.toString())
    }

    /** Creates a new spreadsheet in the user's Drive. Returns (spreadsheetId, first tab id). */
    open fun create(title: String, firstTab: String): Pair<String, Int> {
        val tabProps = JSONObject()
            .put("title", firstTab)
            .put("gridProperties", JSONObject().put("frozenRowCount", 1))
        val body = JSONObject()
            .put("properties", JSONObject().put("title", title))
            .put("sheets", JSONArray().put(JSONObject().put("properties", tabProps)))
        val json = JSONObject(Http.request("POST", base, token, body = body.toString()))
        val tabId = json.getJSONArray("sheets").getJSONObject(0).getJSONObject("properties").getInt("sheetId")
        return json.getString("spreadsheetId") to tabId
    }

    private fun rowsOf(valueRange: JSONObject): List<List<Any?>> {
        val rows = valueRange.optJSONArray("values") ?: return emptyList()
        return (0 until rows.length()).map { i ->
            val r = rows.optJSONArray(i) ?: JSONArray()
            (0 until r.length()).map { j -> r.opt(j).takeUnless { it == JSONObject.NULL } }
        }
    }

    private fun toJson(rows: List<List<Any?>>): JSONArray {
        val out = JSONArray()
        for (r in rows) {
            val row = JSONArray()
            for (v in r) row.put(v ?: JSONObject.NULL)
            out.put(row)
        }
        return out
    }
}
