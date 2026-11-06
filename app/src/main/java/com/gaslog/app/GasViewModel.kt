package com.gaslog.app

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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

enum class Src { NONE, PHOTO, LIVE, CALC, NEARBY }

enum class Screen { LOG, STATS }

/** One form field: its text and where the value came from (photo / calculated / nearby / typed). */
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

/** "Oil change due in 1,200 mi" for one service on one car. */
data class ServiceStatus(val service: Service, val text: String, val warn: Boolean, val known: Boolean)

class GasViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = Prefs(app)
    private val stations = StationMemory(prefs)
    private val queue = OfflineQueue(prefs)

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
    var screen by mutableStateOf(Screen.LOG)

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

    /** MPG-drop warning for the entry just saved, shown on the done screen. */
    var doneAlert by mutableStateOf<String?>(null)
        private set

    /** Row being edited ("Edit last fill-up"), or null for a new entry. */
    var editingRow by mutableStateOf<Int?>(null)
        private set
    var partial by mutableStateOf(false)

    var queuedCount by mutableStateOf(queue.all().size)
        private set
    var flushing by mutableStateOf(false)
        private set
    var update by mutableStateOf<Updates.Release?>(null)
        private set
    var oilInterval by mutableStateOf(prefs.oilInterval)
        private set
    var tireInterval by mutableStateOf(prefs.tireInterval)
        private set
    var stationCount by mutableStateOf(stations.count())
        private set
    var locationAsked by mutableStateOf(prefs.locationAsked)
        private set
    var quickPhoto by mutableStateOf(false)
        private set

    /** The full-screen live camera scan is open. */
    var liveScan by mutableStateOf(false)
        private set
    /** Whether the tile / widget / shortcut open the live scan (else the camera app). */
    var quickLive by mutableStateOf(prefs.quickLive)
        private set

    /** Photos in any order; Gemini works out which is the pump and which the dash. */
    val photos = mutableStateListOf<PreparedPhoto>()

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

    private var here: android.location.Location? = null
    private var lastSavedEntry: Entry? = null
    private var lastQueuedId: Long? = null

    /** The selected car (falls back to the first one). */
    val car: Car?
        get() = config?.cars?.let { cars -> cars.firstOrNull { it.name == carName } ?: cars.firstOrNull() }

    private val connectivity = app.getSystemService(ConnectivityManager::class.java)
    private val netCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            viewModelScope.launch(Dispatchers.Main) { if (queuedCount > 0) flushQueue() }
        }
    }

    init {
        runCatching { connectivity?.registerDefaultNetworkCallback(netCallback) }
        checkForUpdate(force = false)
    }

    override fun onCleared() {
        runCatching { connectivity?.unregisterNetworkCallback(netCallback) }
    }

    /* ───────────── sheet ───────────── */

    fun refresh() {
        val id = sheetId ?: return
        if (loading) return
        launchLoading {
            config = sheets { GasSheet.load(it, id) }
            flushQueue()
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

    /** The button on the message banner: retry loading, or just dismiss. */
    fun onMessageAction() {
        if (sheetId != null && config == null) refresh() else message = null
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

    fun addPhoto(uri: Uri) {
        if (photos.size >= MAX_PHOTOS) {
            readStatus = "Up to $MAX_PHOTOS photos — remove one first."
            return
        }
        viewModelScope.launch {
            readStatus = "Preparing photo…"
            try {
                photos += withContext(Dispatchers.IO) { PhotoUtils.prepare(getApplication<Application>(), uri) }
                readStatus = null
            } catch (e: Exception) {
                readStatus = "Couldn't open that photo."
            }
        }
    }

    fun removePhoto(index: Int) {
        if (index in photos.indices) photos.removeAt(index)
    }

    /** Reads the photos with Gemini. [onlyEmpty]: keep what's already filled in (after a live scan). */
    fun readPhotos(onlyEmpty: Boolean = false) {
        val key = prefs.geminiKey
        if (key.isBlank()) {
            readStatus = "Add your Gemini API key first (Settings)."
            showSettings = true
            return
        }
        val list = photos.map { it.base64 }
        if (list.isEmpty() || reading) return
        viewModelScope.launch {
            reading = true
            readStatus = "Reading the numbers…"
            try {
                val r = withContext(Dispatchers.IO) { GeminiClient.read(key, list) }
                applyReading(r, onlyEmpty)
                readStatus = if (r.notes.isNotBlank()) "⚠️ ${r.notes}" else "Done — check the numbers below."
            } catch (e: Exception) {
                readStatus = if (Connectivity.isNetworkError(e)) {
                    "No connection, so the photos can't be read right now. Type the numbers in — the fill-up will be sent when you're back online."
                } else friendly(e)
            } finally {
                reading = false
            }
        }
    }

    private fun applyReading(r: Reading, onlyEmpty: Boolean = false) {
        fun put(f: FieldState, value: Double?, decimals: Int) {
            if (value != null && (!onlyEmpty || f.text.isBlank() || f.src == Src.CALC)) setNum(f, value, decimals, Src.PHOTO)
        }
        put(ppg, r.ppg, 3)
        put(gallons, r.gallons, 3)
        put(cost, r.cost, 2)
        put(odo, r.odo, 0)
        put(tripA, r.tripA, 1)
        put(octane, r.octane, 0)
        // A remembered station near here beats the brand on the pump ("Valero Big Bear" vs "Valero").
        if (r.station.isNotBlank() && place.src != Src.NEARBY && (!onlyEmpty || place.text.isBlank())) {
            val match = config?.places?.firstOrNull { it.equals(r.station, ignoreCase = true) }
            place.text = match ?: r.station
            place.src = Src.PHOTO
        }
        fillGaps()
    }

    /** Fill gaps from what we know; these are marked "calc" so you can tell. */
    private fun fillGaps() {
        val v = values()
        val last = previousOdo()
        if (v.tripA == null && v.odo != null && last != null && v.odo > last) setNum(tripA, v.odo - last, 1, Src.CALC)
        if (v.odo == null && v.tripA != null && last != null) setNum(odo, last + v.tripA, 0, Src.CALC)
        if (v.cost == null && v.ppg != null && v.gallons != null) setNum(cost, v.ppg * v.gallons, 2, Src.CALC)
        if (v.gallons == null && v.ppg != null && v.cost != null && v.ppg > 0) setNum(gallons, v.cost / v.ppg, 3, Src.CALC)
        if (v.ppg == null && v.gallons != null && v.cost != null && v.gallons > 0) setNum(ppg, v.cost / v.gallons, 3, Src.CALC)
        if (v.octane == null) car?.lastOctane?.let { setNum(octane, it, 0, Src.CALC) }
        saveArmed = false
    }

    /* ───────────── live scan ───────────── */

    fun openLiveScan() {
        screen = Screen.LOG
        if (saved != null) newEntry()
        liveScan = true
    }

    fun closeLiveScan() {
        liveScan = false
    }

    /** What the live scan needs to tell the numbers apart: last fill-up's odometer and price. */
    fun liveHint(odoNow: Double?): LiveHint = LiveHint(
        lastOdo = previousOdo(),
        lastPpg = car?.fills?.lastOrNull { it.ppg != null }?.ppg,
        odoNow = odoNow,
    )

    /**
     * Puts the live-scanned numbers in the form. [snapshots] (frames grabbed while scanning) become photos,
     * and if something is still missing, Gemini reads those frames to fill only the gaps.
     */
    fun applyLive(values: Map<LiveField, Double>, snapshots: List<android.graphics.Bitmap>) {
        liveScan = false
        for ((f, v) in values) setNum(fieldFor(f), v, f.decimals, Src.LIVE)
        fillGaps()
        val got = values.size
        readStatus = if (got == 0) null else "Scanned $got number${if (got == 1) "" else "s"} live — check them below."
        if (snapshots.isEmpty()) return
        viewModelScope.launch {
            val room = (MAX_PHOTOS - photos.size).coerceAtLeast(0)
            val prepared = runCatching {
                withContext(Dispatchers.Default) { snapshots.take(room).map { PhotoUtils.fromBitmap(it) } }
            }.getOrDefault(emptyList())
            photos += prepared
            val v = values()
            val missing = listOf(v.ppg, v.gallons, v.cost, v.odo, v.tripA).any { it == null }
            if (missing && prepared.isNotEmpty() && prefs.geminiKey.isNotBlank() &&
                Connectivity.isOnline(getApplication<Application>())
            ) readPhotos(onlyEmpty = true)
        }
    }

    private fun fieldFor(f: LiveField): FieldState = when (f) {
        LiveField.PPG -> ppg
        LiveField.GALLONS -> gallons
        LiveField.COST -> cost
        LiveField.OCTANE -> octane
        LiveField.ODO -> odo
        LiveField.TRIP_A -> tripA
    }

    fun changeQuickLive(on: Boolean) {
        prefs.quickLive = on
        quickLive = on
    }

    private fun setNum(f: FieldState, value: Double, decimals: Int, src: Src) {
        f.text = String.format(Locale.US, "%.${decimals}f", value)
        f.src = src
    }

    /** Odometer of the fill-up before this one (for an edit, the one before the edited row). */
    private fun previousOdo(): Double? {
        val c = car ?: return null
        val row = editingRow ?: return c.lastOdo
        return c.fills.lastOrNull { it.row < row && it.odo != null }?.odo
    }

    /* ───────────── location ───────────── */

    fun onLocationAnswer(granted: Boolean) {
        prefs.locationAsked = true
        locationAsked = true
        if (granted) locate()
    }

    /** Gets the phone's position and pre-fills a remembered station if we're at one. */
    fun locate() {
        val ctx = getApplication<Application>()
        if (!LocationHelper.hasPermission(ctx)) return
        viewModelScope.launch {
            val loc = runCatching { LocationHelper.current(ctx) }.getOrNull() ?: return@launch
            here = loc
            if (loc.hasAccuracy() && loc.accuracy > 250f) return@launch
            val spot = stations.nearest(loc.latitude, loc.longitude) ?: return@launch
            if (editingRow == null && (place.text.isBlank() || place.src == Src.PHOTO)) {
                place.text = spot.place
                place.src = Src.NEARBY
            }
        }
    }

    fun forgetStations() {
        stations.clear()
        stationCount = 0
    }

    private fun rememberStation(placeName: String) {
        val loc = here ?: return
        if (loc.hasAccuracy() && loc.accuracy > 250f) return
        stations.remember(loc.latitude, loc.longitude, placeName)
        stationCount = stations.count()
    }

    /* ───────────── form ───────────── */

    fun edit(f: FieldState, text: String) {
        f.text = text
        f.src = Src.NONE
        saveArmed = false
        saveStatus = null
    }

    fun setPartialFill(value: Boolean) {
        partial = value
        saveArmed = false
    }

    fun values() = Values(
        odo = GasSheet.num(odo.text),
        tripA = GasSheet.num(tripA.text),
        octane = GasSheet.num(octane.text),
        ppg = GasSheet.num(ppg.text),
        gallons = GasSheet.num(gallons.text),
        cost = GasSheet.num(cost.text),
    )

    /** Usual mpg for the selected car: miles ÷ gallons over its last 10 full tanks. */
    fun usualMpg(): Double? = car?.let { Stats.recentMpg(Stats.tanks(it.fills.filter { f -> f.row != editingRow })) }

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
        val lastOdo = previousOdo()
        if (lastOdo != null && v.odo != null) {
            val driven = v.odo - lastOdo
            if (driven <= 0) {
                out += Check(true, "Odometer isn't higher than the fill-up before (${fmtInt(lastOdo)}).")
            } else if (v.tripA != null && abs(v.tripA - driven) > 3) {
                out += Check(true, "Trip A (${v.tripA}) doesn't match the odometer change (${fmtInt(driven)} mi since last fill). Wrong digit, or Trip A wasn't reset?")
            }
        }
        if (partial) {
            out += Check(false, "Partial fill — its miles and gallons count toward your next full tank's mpg.")
        } else if (v.tripA != null && v.gallons != null && v.gallons > 0) {
            val mpg = v.tripA / v.gallons
            val off = mpg < 10 || mpg > 60
            out += Check(off, "≈ ${String.format(Locale.US, "%.1f", mpg)} mpg" + if (off) " — looks off" else "")
            if (!off) Stats.mpgDrop(mpg, usualMpg())?.let { out += Check(true, it) }
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
            ppg = v.ppg, gallons = v.gallons, cost = v.cost, notes = Notes.compose(notes.text, partial),
        )
        val fullMpg = if (!partial && v.tripA != null && v.gallons != null && v.gallons > 0) v.tripA / v.gallons else null
        val editRow = editingRow
        val ctx = getApplication<Application>()
        doneAlert = fullMpg?.let { Stats.mpgDrop(it, usualMpg()) }

        if (editRow == null && !Connectivity.isOnline(ctx)) {
            enqueue(id, c, entry, fullMpg)
            return
        }
        viewModelScope.launch {
            saving = true
            saveStatus = "Saving…"
            try {
                val res = sheets { GasSheet.save(it, id, c, entry, editRow) }
                lastSavedEntry = entry
                lastQueuedId = null
                saved = res.copy(mpg = if (partial) null else (res.mpg ?: fullMpg))
                prefs.lastCar = res.car
                if (entry.place.isNotBlank()) rememberStation(entry.place)
                saveStatus = null
                reloadQuietly()
            } catch (e: Exception) {
                if (editRow == null && Connectivity.isNetworkError(e)) {
                    enqueue(id, c, entry, fullMpg)
                } else {
                    saveStatus = "Not saved: " + friendly(e)
                }
            } finally {
                saving = false
                saveArmed = false
            }
        }
    }

    private fun enqueue(sheet: String, c: Car, entry: Entry, mpg: Double?) {
        val p = Pending(System.currentTimeMillis(), sheet, c.name, c.tabId, entry)
        queue.add(p)
        queuedCount = queue.all().size
        lastQueuedId = p.id
        lastSavedEntry = entry
        if (entry.place.isNotBlank()) rememberStation(entry.place)
        saved = SaveResult(c.name, 0, mpg, null, queued = true)
        saveStatus = null
        saveArmed = false
    }

    /** Sends fill-ups saved while offline. Stops at the first connection problem. */
    fun flushQueue() {
        if (flushing || tokens == null) return
        val items = queue.all()
        if (items.isEmpty()) return
        if (!Connectivity.isOnline(getApplication<Application>())) return
        viewModelScope.launch {
            flushing = true
            var sent = 0
            try {
                for (p in items) {
                    val target = Car(p.carName, p.tabId, 0, null, "", null)
                    sheets { GasSheet.save(it, p.sheetId, target, p.entry) }
                    queue.remove(p.id)
                    if (lastQueuedId == p.id) lastQueuedId = null
                    sent++
                }
            } catch (e: Exception) {
                if (!Connectivity.isNetworkError(e)) message = "Couldn't send a saved fill-up: " + friendly(e)
            } finally {
                queuedCount = queue.all().size
                flushing = false
            }
            if (sent > 0) {
                message = if (sent == 1) "Sent the fill-up you saved offline." else "Sent $sent fill-ups you saved offline."
                reloadQuietly()
            }
        }
    }

    /** Undo the entry just saved: clears its row (or drops it from the offline queue). Values stay in the form. */
    fun undoLast() {
        val res = saved ?: return
        val entry = lastSavedEntry ?: return
        if (res.queued) {
            lastQueuedId?.let { queue.remove(it) }
            queuedCount = queue.all().size
            saved = null
            saveStatus = "Removed from the offline queue. Fix anything and save again."
            return
        }
        val id = sheetId ?: return
        val c = config?.cars?.firstOrNull { it.name == res.car } ?: car ?: return
        viewModelScope.launch {
            saving = true
            try {
                sheets { GasSheet.clearRow(it, id, c, res.row, entry.odo) }
                saved = null
                saveStatus = "Row ${res.row} cleared. Fix anything and save again."
                reloadQuietly()
            } catch (e: Exception) {
                message = "Couldn't undo: " + friendly(e)
            } finally {
                saving = false
            }
        }
    }

    /** Loads the newest fill-up of the selected car into the form for editing. */
    fun startEditLast() {
        val c = car ?: return
        val f = c.fills.lastOrNull() ?: run {
            message = "No fill-ups on ${c.name} yet."
            return
        }
        clearForm()
        editingRow = f.row
        date.text = f.date?.let { "${it.monthValue}/${it.dayOfMonth}/${it.year}" } ?: today()
        f.odo?.let { setNum(odo, it, 0, Src.NONE) }
        f.tripA?.let { tripA.text = trimNum(it) }
        place.text = f.place
        f.octane?.let { setNum(octane, it, 0, Src.NONE) }
        f.ppg?.let { ppg.text = trimNum(it) }
        f.gallons?.let { gallons.text = trimNum(it) }
        f.cost?.let { setNum(cost, it, 2, Src.NONE) }
        partial = f.partial
        notes.text = Notes.strip(f.notes)
        saved = null
        screen = Screen.LOG
    }

    fun cancelEdit() {
        clearForm()
        date.text = today()
        locate()
    }

    fun newEntry() {
        clearForm()
        date.text = today()
        saved = null
        refresh()
        locate()
    }

    private fun clearForm() {
        photos.clear()
        allFields.forEach { it.text = ""; it.src = Src.NONE }
        partial = false
        editingRow = null
        readStatus = null
        saveStatus = null
        saveArmed = false
    }

    /* ───────────── maintenance ───────────── */

    fun serviceStatus(c: Car, s: Service): ServiceStatus {
        val info = c.services[s]
        val last = info?.lastMileage
        val odoNow = c.lastOdo
        val interval = if (s == Service.OIL) oilInterval else tireInterval
        if (last == null || odoNow == null) {
            return ServiceStatus(s, "${s.title}: none logged yet", false, false)
        }
        val left = last + interval - odoNow
        return when {
            left < 0 -> ServiceStatus(s, "${s.title} overdue by ${fmtInt(-left)} mi", true, true)
            left <= 500 -> ServiceStatus(s, "${s.title} due in ${fmtInt(left)} mi", true, true)
            else -> ServiceStatus(s, "${s.title} in ${fmtInt(left)} mi (last at ${fmtInt(last)})", false, true)
        }
    }

    fun logService(s: Service, odoText: String) {
        val id = sheetId ?: return
        val c = car ?: return
        val mileage = GasSheet.num(odoText)
        if (mileage == null) {
            message = "Enter the odometer reading for the ${s.title.lowercase()}."
            return
        }
        val stamp = LocalDate.now().let { "${it.monthValue}/${it.dayOfMonth}/${it.year % 100}" }
        launchLoading {
            config = sheets { sc ->
                GasSheet.logService(sc, id, c, s, mileage, stamp)
                GasSheet.load(sc, id)
            }
            message = "${s.title} logged on ${c.name} at ${fmtInt(mileage)} mi."
        }
    }

    fun setIntervals(oil: String, tires: String) {
        GasSheet.num(oil)?.toInt()?.takeIf { it in 500..50_000 }?.let { prefs.oilInterval = it; oilInterval = it }
        GasSheet.num(tires)?.toInt()?.takeIf { it in 500..50_000 }?.let { prefs.tireInterval = it; tireInterval = it }
    }

    /* ───────────── updates & quick launch ───────────── */

    fun installedVersion(): Int = installedBuild(getApplication())

    fun checkForUpdate(force: Boolean) {
        val installed = installedVersion()
        if (prefs.latestBuild > installed) update = Updates.Release(prefs.latestBuild, "Gas Log 1.0.${prefs.latestBuild}")
        if (!force && System.currentTimeMillis() - prefs.lastUpdateCheck < 6 * 3_600_000L) return
        viewModelScope.launch {
            val r = runCatching { withContext(Dispatchers.IO) { Updates.latest() } }.getOrNull()
            if (r == null) {
                if (force) message = "Couldn't check for updates right now."
                return@launch
            }
            prefs.lastUpdateCheck = System.currentTimeMillis()
            prefs.latestBuild = r.build
            update = if (r.build > installed) r else null
            if (force && update == null) message = "You're on the latest version (1.0.$installed)."
        }
    }

    fun dismissUpdate() {
        update = null
    }

    /** From the Quick Settings tile / widget / shortcut: go to a fresh entry and open the camera. */
    fun requestQuickPhoto() {
        screen = Screen.LOG
        if (saved != null || editingRow != null) newEntry()
        if (quickLive) liveScan = true else quickPhoto = true
    }

    fun consumeQuickPhoto() {
        quickPhoto = false
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

    /** Reloads the sheet without the spinner/message churn (after a save). */
    private fun reloadQuietly() {
        val id = sheetId ?: return
        viewModelScope.launch {
            runCatching { config = sheets { GasSheet.load(it, id) } }
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
        const val MAX_PHOTOS = 4

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

        fun money(d: Double): String = String.format(Locale.US, "%.2f", d)

        /** 192.6 → "192.6", 8.0 → "8", 5.879 → "5.879". */
        fun trimNum(d: Double): String =
            if (d == Math.rint(d)) d.toLong().toString() else d.toBigDecimal().stripTrailingZeros().toPlainString()
    }
}
