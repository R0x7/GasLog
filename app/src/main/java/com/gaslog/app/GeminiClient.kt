package com.gaslog.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** Reads pump and dashboard photos with the Gemini API, using the person's own key. */
object GeminiClient {
    private const val BASE = "https://generativelanguage.googleapis.com/v1beta/models/"

    // "-latest" aliases follow Google's newest Flash models; the lite one is a fallback on quota errors.
    private val MODELS = listOf("gemini-flash-latest", "gemini-flash-lite-latest")
    private val TRY_NEXT_ON = setOf(403, 404, 429, 500, 503)

    /**
     * photos: base64 JPEGs in any order (pump, dash, ...). [lastOdo]: the odometer at the previous fill-up,
     * which helps tell which counter is Trip A. Blocking; call off the main thread.
     */
    fun read(apiKey: String, photos: List<String>, lastOdo: Double? = null): Reading {
        if (photos.isEmpty()) throw IOException("Add at least one photo.")
        val context = if (lastOdo != null) {
            "\n\nFor checking only: the odometer read ${lastOdo.toLong()} at the previous fill-up and Trip A is " +
                "normally reset then, so Trip A should be close to (odometer now − ${lastOdo.toLong()}). Use this " +
                "to tell the counters apart and to place a missing decimal point — still report what's displayed."
        } else ""
        val parts = JSONArray().put(JSONObject().put("text", PROMPT + context))
        photos.forEachIndexed { i, data ->
            parts.put(JSONObject().put("text", "Photo ${i + 1} of ${photos.size}:"))
            parts.put(JSONObject().put("inlineData", JSONObject().put("mimeType", "image/jpeg").put("data", data)))
        }
        val contents = JSONArray().put(JSONObject().put("role", "user").put("parts", parts))
        // Flash models "think" before answering by default, which is most of the wait and isn't needed
        // to copy numbers off a display. Ask for no thinking; fall back if a model won't accept that.
        fun body(noThinking: Boolean): String {
            val gen = JSONObject().put("responseMimeType", "application/json")
            if (noThinking) gen.put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
            return JSONObject().put("contents", contents).put("generationConfig", gen).toString()
        }
        fun call(model: String, noThinking: Boolean): Reading = parse(
            Http.request(
                "POST", "$BASE$model:generateContent",
                headers = mapOf("x-goog-api-key" to apiKey),
                body = body(noThinking),
                readTimeoutMs = 120_000,
            )
        )

        var lastError = ""
        for (model in MODELS) {
            try {
                return try {
                    call(model, noThinking = true)
                } catch (e: ApiException) {
                    val aboutThinking = e.code == 400 && e.message.orEmpty().contains("think", ignoreCase = true)
                    if (!aboutThinking) throw e
                    call(model, noThinking = false)
                }
            } catch (e: ApiException) {
                val msg = e.message.orEmpty()
                if (msg.contains("API key not valid", ignoreCase = true) || msg.contains("API_KEY_INVALID")) {
                    throw IOException("Gemini rejected the API key. Open Settings and paste it again.")
                }
                lastError = "$model: $msg"
                if (e.code !in TRY_NEXT_ON) break
            }
        }
        throw IOException("Couldn't read the photos ($lastError). You can still type the numbers in.")
    }

    private fun parse(text: String): Reading {
        val data = JSONObject(text)
        val cand = data.optJSONArray("candidates")?.optJSONObject(0)
        val parts = cand?.optJSONObject("content")?.optJSONArray("parts")
        if (parts == null) {
            val why = data.optJSONObject("promptFeedback")?.optString("blockReason")?.takeIf { it.isNotEmpty() }
                ?: cand?.optString("finishReason")?.takeIf { it.isNotEmpty() }
                ?: "empty reply"
            throw IOException("Gemini returned no reading ($why).")
        }
        val raw = StringBuilder()
        for (i in 0 until parts.length()) {
            val p = parts.optJSONObject(i) ?: continue
            if (p.optBoolean("thought")) continue
            raw.append(p.optString("text"))
        }
        val json = Regex("\\{[\\s\\S]*\\}").find(raw)?.value
            ?: throw IOException("Couldn't understand Gemini's reply.")
        val j = JSONObject(json)
        return Reading(
            ppg = num(j, "price_per_gallon"),
            gallons = num(j, "gallons"),
            cost = num(j, "total_cost"),
            octane = num(j, "octane"),
            station = str(j, "station"),
            odo = num(j, "odometer"),
            tripA = num(j, "trip_a"),
            notes = str(j, "notes"),
        )
    }

    private fun num(j: JSONObject, key: String): Double? = if (j.isNull(key)) null else GasSheet.num(j.opt(key))
    private fun str(j: JSONObject, key: String): String = if (j.isNull(key)) "" else j.optString(key).trim()

    private val PROMPT = """
        You are reading photos for a car fuel log. Report only numbers you can actually see.
        Never estimate, calculate or fill in a value that is not displayed — use null instead.

        The photos can include:
        - A gas pump display: price per gallon (usually 3 decimals, e.g. 5.879), gallons pumped
          (usually 3 decimals, e.g. 8.021) and the total sale in dollars (2 decimals). It may also
          show the station brand and the selected grade / octane (87, 89, 91 ...).
        - A car instrument cluster: the odometer (total miles, often "ODO") and Trip A
          (labeled "TRIP A" or "A", usually with one decimal).
          Digital dashes often show "TRIP" with a small boxed "A" next to a number with a unit such as
          "140.4 mile" — that number is Trip A.
          Older cars have mechanical rolling-drum counters and often no labels at all: the long counter
          is the odometer (it may show a leading zero: "054444" is 54444), and the short counter (usually
          4 drums) next to it is Trip A. A drum trip meter has no decimal point: its last drum, often a
          different color (white or red), is tenths — drums reading 1 0 6 7 mean 106.7. Trip A is
          then never more than 999.9. If the needle hides the tenths drum, report the whole miles
          (e.g. 106) and say so in "notes".
        The photos come in any order. Work out for yourself which shows the pump and which the dashboard;
        one photo may show both, and several photos may show the same thing. Use everything visible.

        Ignore the printed scale numbers around a gauge (20, 40, 60 … on the speedometer, 1–8 on the
        tachometer, "x1000"): they are not readings.

        Pump displays often use seven-segment digits: check each digit carefully (8 vs 6 vs 9 vs 0,
        1 vs 7, 5 vs 6). Do not confuse the price per gallon with the total sale. Do not report
        Trip B as Trip A, and do not report the odometer as Trip A. Ignore "range", "miles to
        empty", average mpg and outside temperature.

        Reply with JSON only, exactly these keys:
        {"price_per_gallon": number|null, "gallons": number|null, "total_cost": number|null,
         "octane": number|null, "station": string|null, "odometer": number|null,
         "trip_a": number|null, "notes": string|null}
        "station" is the brand name shown on the pump (e.g. "Arco", "Costco"), else null.
        "notes" is a short warning if a digit was hard to read or a photo was blurry, else null.
    """.trimIndent()
}
