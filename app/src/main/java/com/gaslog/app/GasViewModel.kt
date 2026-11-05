package com.gaslog.app

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.UnknownHostException
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs

enum class Src { NONE, PHOTO, CALC }

/** One form field: its text and where the value came from (photo / calculated / typed). */
class FieldState(initial: String = "") {
    var text by mutableStateOf(initial)
    var src by mutableStateOf(Src.NONE)
}

data class Check(val warn: Boolean, val text: String)

data class Values(
    val odo: Double?,
    val tripA: Double?,
    val octane: Double?,
    val ppg: Double?,
    val gallons: Double?,
    val cost: Double?,
)

class GasViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = Prefs(app)

    /** Set by MainActivity; asks Google for Sheets access. */
    var tokens: TokenSource? = null

    var sheetId by mutableStateOf(prefs.sheetId)
        private set
    var hasKey by mutableStateOf(prefs.geminiKey.isNotBlank())
        private set
    var config by mutableStateOf<LogConfig?>(null)
        private set
    var carName by mutableStateOf(prefs.lastCar)
        private set

    var loading by mutableStateOf(false)
        private set
    var reading by mutableStateOf(false)
        private set
    var saving by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
    var readStatus by mutableStateOf<String?>(null)
        private set
    var saveStatus by mutableStateOf<String?>(null)
        private set
    var saveArmed by mutableStateOf(false)
        private set
    var saved by mutableStateOf<SaveResult?>(null)
        private set
    var showSettings by mutableStateOf(false)

    val photos = mutableStateMapOf<Slot, PreparedPhoto>()

    val date = FieldState(today())
    val odo = FieldState()
    val tripA = FieldState()
    val place = FieldState()
    val octane = FieldState()
    val ppg = FieldState()
    val gallons = FieldState()
    val cost = FieldState()
    val notes = FieldState()
    private val allFields get() = listOf(date, odo, tripA, place, octane, ppg, gallons, cost, notes)

    /** The selected car (falls back to the first one). */
    val car: Car?
        get() = config?.cars?.let { cars -> cars.firstOrNull { it.name == carName } ?: cars.firstOrNull() }

    /* ───────────── sheet ───────────── */

    fun refresh() {
        val id = sheetId ?: return
        if (loading) return
        launchLoading {
            config = sheets { GasSheet.load(it, id) }
        }
    }

    fun connect(link: String) {
        val id = parseSheetId(link)
        if (id == null) {
            message = "That doesn't look like a Google Sheets link."
            return
        }
        launchLoading {
            val cfg = sheets { GasSheet.load(it, id) }
            config = cfg
            prefs.sheetId = id
            sheetId = id
            if (cfg.cars.isEmpty()) {
                message = "Connected, but no tab looks like a gas log yet. Tap “+ Add car” to make one."
            }
        }
    }

    fun createLog(firstCar: String) {
        val name = firstCar.trim().ifEmpty { "My car" }
        launchLoading {
            val (id, cfg) = sheets { sc ->
                val newId = GasSheet.createLog(sc, name)
                newId to GasSheet.load(sc, newId)
            }
            config = cfg
            prefs.sheetId = id
            sheetId = id
            selectCar(name)
        }
    }

    fun addCar(name: String) {
        val id = sheetId ?: return
        val clean = name.trim()
        if (clean.isEmpty()) return
        if (config?.cars?.any { it.name.equals(clean, ignoreCase = true) } == true) {
            message = "There's already a car called $clean."
            return
        }
        launchLoading {
            config = sheets { sc ->
                GasSheet.addCar(sc, id, clean)
                GasSheet.load(sc, id)
            }
            selectCar(clean)
        }
    }

    fun disconnect() {
        prefs.sheetId = null
        sheetId = null
        config = null
        message = null
    }

    fun selectCar(name: String) {
        carName = name
        prefs.lastCar = name
        saveArmed = false
    }

    fun saveKey(key: String) {
        prefs.geminiKey = key.trim()
        hasKey = prefs.geminiKey.isNotBlank()
    }

    /* ───────────── photos ───────────── */

    fun addPhoto(slot: Slot, uri: Uri) {
        viewModelScope.launch {
            readStatus = "Preparing photo…"
            try {
                photos[slot] = withContext(Dispatchers.IO) { PhotoUtils.prepare(getApplication<Application>(), uri) }
                readStatus = null
            } catch (e: Exception) {
                readStatus = "Couldn't open that photo."
            }
        }
    }

    fun removePhoto(slot: Slot) {
        photos.remove(slot)
    }

    fun readPhotos() {
        val key = prefs.geminiKey
        if (key.isBlank()) {
            readStatus = "Add your Gemini API key first (Settings)."
            showSettings = true
            return
        }
        val list = Slot.entries.mapNotNull { s -> photos[s]?.let { s.label to it.base64 } }
        if (list.isEmpty() || reading) return
        viewModelScope.launch {
            reading = true
            readStatus = "Reading the numbers…"
            try {
                val r = withContext(Dispatchers.IO) { GeminiClient.read(key, list) }
                applyReading(r)
                readStatus = if (r.notes.isNotBlank()) "⚠️ ${r.notes}" else "Done — check the numbers below."
            } catch (e: Exception) {
                readStatus = friendly(e)
            } finally {
                reading = false
            }
        }
    }

    private fun applyReading(r: Reading) {
        r.ppg?.let { setNum(ppg, it, 3, Src.PHOTO) }
        r.gallons?.let { setNum(gallons, it, 3, Src.PHOTO) }
        r.cost?.let { setNum(cost, it, 2, Src.PHOTO) }
        r.odo?.let { setNum(odo, it, 0, Src.PHOTO) }
        r.tripA?.let { setNum(tripA, it, 1, Src.PHOTO) }
        r.octane?.let { setNum(octane, it, 0, Src.PHOTO) }
        if (r.station.isNotBlank()) {
            val match = config?.places?.firstOrNull { it.equals(r.station, ignoreCase = true) }
            place.text = match ?: r.station
            place.src = Src.PHOTO
        }

        // Fill gaps from what we know; these are marked "calc" so you can tell.
        val v = values()
        val last = car?.lastOdo
        if (v.tripA == null && v.odo != null && last != null && v.odo > last) setNum(tripA, v.odo - last, 1, Src.CALC)
        if (v.odo == null && v.tripA != null && last != null) setNum(odo, last + v.tripA, 0, Src.CALC)
        if (v.cost == null && v.ppg != null && v.gallons != null) setNum(cost, v.ppg * v.gallons, 2, Src.CALC)
        if (v.gallons == null && v.ppg != null && v.cost != null && v.ppg > 0) setNum(gallons, v.cost / v.ppg, 3, Src.CALC)
        if (v.ppg == null && v.gallons != null && v.cost != null && v.gallons > 0) setNum(ppg, v.cost / v.gallons, 3, Src.CALC)
        if (v.octane == null) car?.lastOctane?.let { setNum(octane, it, 0, Src.CALC) }
        saveArmed = false
    }

    private fun setNum(f: FieldState, value: Double, decimals: Int, src: Src) {
        f.text = String.format(Locale.US, "%.${decimals}f", value)
        f.src = src
    }

    /* ───────────── form ───────────── */

    fun edit(f: FieldState, text: String) {
        f.text = text
        f.src = Src.NONE
        saveArmed = false
        saveStatus = null
    }

    fun values() = Values(
        odo = GasSheet.num(odo.text),
        tripA = GasSheet.num(tripA.text),
        octane = GasSheet.num(octane.text),
        ppg = GasSheet.num(ppg.text),
        gallons = GasSheet.num(gallons.text),
        cost = GasSheet.num(cost.text),
    )

    /** Sanity checks shown above the Save button. */
    fun checks(): List<Check> {
        val v = values()
        val out = mutableListOf<Check>()
        if (v.ppg != null && v.gallons != null && v.cost != null) {
            val calc = v.ppg * v.gallons
            val diff = v.cost - calc
            out += if (abs(diff) > 0.05) {
                Check(true, "Pump math is off by \$${money(abs(diff))}: ${v.ppg} × ${v.gallons} = \$${money(calc)}. Check the digits (or it was a card fee).")
            } else {
                Check(false, "Pump math checks out.")
            }
        }
        val lastOdo = car?.lastOdo
        if (lastOdo != null && v.odo != null) {
            val driven = v.odo - lastOdo
            if (driven <= 0) {
                out += Check(true, "Odometer isn't higher than the last entry (${fmtInt(lastOdo)}).")
            } else if (v.tripA != null && abs(v.tripA - driven) > 3) {
                out += Check(true, "Trip A (${v.tripA}) doesn't match the odometer change (${fmtInt(driven)} mi since last fill). Wrong digit, or Trip A wasn't reset?")
            }
        }
        if (v.tripA != null && v.gallons != null && v.gallons > 0) {
            val mpg = v.tripA / v.gallons
            val off = mpg < 10 || mpg > 60
            out += Check(off, "≈ ${String.format(Locale.US, "%.1f", mpg)} mpg" + if (off) " — looks off" else "")
        }
        val missing = buildList {
            if (v.odo == null) add("odometer")
            if (v.gallons == null) add("gallons")
            if (v.cost == null) add("cost")
        }
        val anything = listOf(v.ppg, v.gallons, v.cost, v.odo, v.tripA).any { it != null }
        if (missing.isNotEmpty() && anything) out += Check(true, "Missing: ${missing.joinToString(", ")}.")
        return out
    }

    fun save() {
        val c = car ?: return
        val id = sheetId ?: return
        if (saving) return
        val v = values()
        if (v.odo == null && v.tripA == null && v.gallons == null) {
            saveStatus = "Nothing to save yet."
            return
        }
        val day = parseDate(date.text)
        if (day == null) {
            saveStatus = "Date should look like 11/5/2026."
            return
        }
        if (checks().any { it.warn } && !saveArmed) {
            saveArmed = true
            saveStatus = "There's a warning above — tap again to save anyway."
            return
        }
        val entry = Entry(
            dateSerial = ChronoUnit.DAYS.between(LocalDate.of(1899, 12, 30), day).toDouble(),
            odo = v.odo, tripA = v.tripA, place = place.text.trim(), octane = v.octane,
            ppg = v.ppg, gallons = v.gallons, cost = v.cost, notes = notes.text.trim(),
        )
        viewModelScope.launch {
            saving = true
            saveStatus = "Saving…"
            try {
                val res = sheets { GasSheet.save(it, id, c, entry) }
                val fallbackMpg = if (v.tripA != null && v.gallons != null && v.gallons > 0) v.tripA / v.gallons else null
                saved = res.copy(mpg = res.mpg ?: fallbackMpg)
                prefs.lastCar = res.car
                saveStatus = null
            } catch (e: Exception) {
                saveStatus = "Not saved: " + friendly(e)
            } finally {
                saving = false
                saveArmed = false
            }
        }
    }

    fun newEntry() {
        photos.clear()
        allFields.forEach { it.text = ""; it.src = Src.NONE }
        date.text = today()
        saved = null
        readStatus = null
        saveStatus = null
        saveArmed = false
        refresh()
    }

    /* ───────────── helpers ───────────── */

    private fun launchLoading(block: suspend () -> Unit) {
        viewModelScope.launch {
            loading = true
            message = null
            try {
                block()
            } catch (e: Exception) {
                message = friendly(e)
            } finally {
                loading = false
            }
        }
    }

    /** Gets a Google token, runs [block] off the main thread, and retries once if the token expired. */
    private suspend fun <T> sheets(block: (SheetsClient) -> T): T {
        val source = tokens ?: throw IOException("The app isn't ready yet — try again.")
        val token = source.token()
        return try {
            withContext(Dispatchers.IO) { block(SheetsClient(token)) }
        } catch (e: ApiException) {
            if (e.code != 401) throw e
            source.invalidate(token)
            val fresh = source.token()
            withContext(Dispatchers.IO) { block(SheetsClient(fresh)) }
        }
    }

    private fun friendly(e: Throwable): String = when (e) {
        is ApiException -> when (e.code) {
            403 -> "Google said no: ${e.message}"
            404 -> "Couldn't find that sheet — check the link and that this Google account can open it."
            else -> e.message ?: "Google error ${e.code}"
        }
        is UnknownHostException -> "No internet connection."
        else -> e.message ?: e.toString()
    }

    companion object {
        fun parseSheetId(link: String): String? {
            val t = link.trim()
            Regex("/spreadsheets/d/([a-zA-Z0-9_-]+)").find(t)?.let { return it.groupValues[1] }
            return if (Regex("^[a-zA-Z0-9_-]{25,}$").matches(t)) t else null
        }

        fun today(): String = LocalDate.now().let { "${it.monthValue}/${it.dayOfMonth}/${it.year}" }

        fun parseDate(s: String): LocalDate? {
            val m = Regex("^(\\d{1,2})/(\\d{1,2})/(\\d{2}|\\d{4})$").find(s.trim()) ?: return null
            val (mo, d, y) = m.destructured
            val year = if (y.length == 2) 2000 + y.toInt() else y.toInt()
            return runCatching { LocalDate.of(year, mo.toInt(), d.toInt()) }.getOrNull()
        }

        fun fmtInt(d: Double): String = String.format(Locale.US, "%,d", Math.round(d))

        private fun money(d: Double): String = String.format(Locale.US, "%.2f", d)
    }
}
