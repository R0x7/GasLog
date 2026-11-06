package com.gaslog.app

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToLong

/**
 * Live scan: picks the fill-up numbers out of the text the on-device recognizer sees in each camera frame.
 * Plain Kotlin (no Android), so it can be unit-tested.
 *
 * Nothing is trusted from a single frame. Each frame "votes", and a value is locked in only after it has
 * been seen several times. Strong clues — the pump math adding up (price × gallons = total), a mileage
 * just above the last fill-up, a Trip A that matches the odometer change — count double.
 */
enum class LiveField(val title: String, val decimals: Int, val pump: Boolean) {
    PPG("\$/gal", 3, true),
    GALLONS("Gallons", 3, true),
    COST("Cost \$", 2, true),
    OCTANE("Octane", 0, true),
    ODO("Odometer", 0, false),
    TRIP_A("Trip A", 1, false),
}

/** One recognized line of text and its box (any coordinate space, y pointing down). */
data class OcrLine(val text: String, val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val cx get() = (left + right) / 2f
    val cy get() = (top + bottom) / 2f
    val height get() = (bottom - top).coerceAtLeast(1f)
}

/** What we already know that helps tell the numbers apart. */
data class LiveHint(
    val lastOdo: Double? = null,  // odometer at the previous fill-up
    val lastPpg: Double? = null,  // price per gallon last time
    val odoNow: Double? = null,   // odometer already locked in this scan
)

/** A value one frame suggests for one field. [weight] 2 = strong clue, 1 = weak. */
data class Pick(val field: LiveField, val value: Double, val weight: Int, val line: OcrLine)

internal enum class Label { PPG, GALLONS, COST, OCTANE, ODO, TRIP_A, DIST, IGNORE }

internal data class NumToken(
    val value: Double,
    val decimals: Int,
    val digits: Int,
    val dollar: Boolean,
    val label: Label?,
    val line: OcrLine,
)

object LiveReader {
    // 123,456 · 5.879 · 8,021 (decimal comma) · $45.20 · 192.6
    private val NUMBER = Regex("(\\$\\s?)?(\\d{1,3}(?:,\\d{3})+|\\d+)(?:[.,](\\d{1,3}))?(?!\\d)")
    private val LOOKALIKE = mapOf('O' to '0', 'o' to '0', 'D' to '0', 'I' to '1', 'l' to '1', '|' to '1', 'S' to '5', 'B' to '8', 'Z' to '2')

    fun analyze(lines: List<OcrLine>, hint: LiveHint): List<Pick> {
        val tokens = tokens(lines)
        val out = mutableListOf<Pick>()
        out += pump(tokens, hint)
        octane(tokens)?.let { out += it }
        val odo = odometer(tokens, hint)
        odo?.let { out += it }
        trip(tokens, hint.copy(odoNow = hint.odoNow ?: odo?.value))?.let { out += it }
        return out
    }

    /* ───────── reading the text ───────── */

    internal fun tokens(lines: List<OcrLine>): List<NumToken> {
        val labelLines = lines.filter { !it.text.any(Char::isDigit) }.mapNotNull { l -> labelOf(l.text)?.let { l to it } }
        val out = mutableListOf<NumToken>()
        for (line in lines) {
            val text = fixDigits(line.text)
            val matches = NUMBER.findAll(text).toList()
            matches.forEachIndexed { i, m ->
                val whole = m.groupValues[2]
                val frac = m.groupValues[3]
                // "123,456" is thousands; "5,879" reads as thousands too, but a pump price that way fails the
                // pump math and is simply ignored.
                val value = (whole.replace(",", "") + if (frac.isNotEmpty()) ".$frac" else "").toDoubleOrNull() ?: return@forEachIndexed
                val before = text.substring(if (i == 0) 0 else matches[i - 1].range.last + 1, m.range.first)
                val after = text.substring(m.range.last + 1, if (i == matches.lastIndex) text.length else matches[i + 1].range.first)
                val label = unitOf(after) ?: labelOf(before) ?: labelOf(after) ?: nearestLabel(line, labelLines)
                out += NumToken(
                    value = value,
                    decimals = frac.length,
                    digits = whole.count(Char::isDigit) + frac.length,
                    dollar = m.groupValues[1].isNotEmpty() || before.trimEnd().endsWith("$"),
                    label = label,
                    line = line,
                )
            }
        }
        return out
    }

    /** Fixes letters the recognizer often reads instead of digits, only inside words that are mostly digits. */
    internal fun fixDigits(text: String): String = text.split(" ").joinToString(" ") { word ->
        val digits = word.count(Char::isDigit)
        if (digits >= 2 && word.all { it.isDigit() || it in ".,$" || it in LOOKALIKE }) {
            word.map { LOOKALIKE[it] ?: it }.joinToString("")
        } else word
    }

    private fun unitOf(after: String): Label? {
        val t = after.trim().uppercase()
        return when {
            t.startsWith("MPG") || t.startsWith("L/100") || t.startsWith("°") -> Label.IGNORE
            t.startsWith("/G") || t.startsWith("PER G") -> Label.PPG
            t.startsWith("GAL") || t == "G" -> Label.GALLONS
            t.startsWith("MI") || t.startsWith("KM") -> Label.DIST
            else -> null
        }
    }

    internal fun labelOf(text: String): Label? {
        val t = " " + text.uppercase().replace(Regex("[^A-Z$/ ]"), " ") + " "
        return when {
            Regex("RANGE|MPG|AVG|AVERAGE|EMPTY|\\bDTE\\b|TEMP|\\bECO\\b|TRIP\\s*B|\\sB\\s").containsMatchIn(t) -> Label.IGNORE
            "ODO" in t -> Label.ODO
            Regex("TRIP\\s*A|\\sA\\s|TRIP").containsMatchIn(t) -> Label.TRIP_A
            Regex("PRICE|PER\\s*GAL|/\\s*GAL|/\\s*G\\b|UNIT").containsMatchIn(t) -> Label.PPG
            Regex("GAL|VOLUME|LITRE|LITER").containsMatchIn(t) -> Label.GALLONS
            Regex("SALE|TOTAL|AMOUNT|DOLLARS").containsMatchIn(t) -> Label.COST
            Regex("OCTANE|UNLEADED|REGULAR|\\sPLUS\\s|PREMIUM|SUPER|\\sMID").containsMatchIn(t) -> Label.OCTANE
            else -> null
        }
    }

    /** A label on its own line ("PRICE PER GALLON") applies to the nearest number, within a few line heights. */
    private fun nearestLabel(line: OcrLine, labels: List<Pair<OcrLine, Label>>): Label? {
        val h = line.height
        return labels
            .map { (l, label) -> hypot((l.cx - line.cx) / (h * 2f), (l.cy - line.cy) / h) to label }
            .filter { it.first <= 3f }
            .minByOrNull { it.first }
            ?.second
    }

    /* ───────── finding each field ───────── */

    private val NOT_PUMP = setOf(Label.ODO, Label.TRIP_A, Label.DIST, Label.IGNORE, Label.OCTANE)

    /** Price × gallons = total is the best clue on a pump: find the three numbers that multiply out. */
    private fun pump(tokens: List<NumToken>, hint: LiveHint): List<Pick> {
        val nums = tokens.filter { it.decimals > 0 && it.label !in NOT_PUMP }
        var best: Triple<NumToken, NumToken, NumToken>? = null
        var bestScore = Double.NEGATIVE_INFINITY
        for (p in nums) {
            if (p.value !in 1.0..12.0 || p.label !in setOf(null, Label.PPG)) continue
            for (g in nums) {
                if (g === p || g.value !in 0.1..80.0 || g.label !in setOf(null, Label.GALLONS) || g.dollar) continue
                for (c in nums) {
                    if (c === p || c === g || c.value !in 0.5..500.0 || c.label !in setOf(null, Label.COST)) continue
                    val err = abs(p.value * g.value - c.value)
                    if (err > 0.02 + c.value * 0.0005) continue
                    var score = -err * 20
                    if (p.label == Label.PPG) score += 2
                    if (g.label == Label.GALLONS) score += 2
                    if (c.label == Label.COST) score += 2
                    if (c.dollar) score += 1
                    if (c.decimals == 2) score += 1
                    if (p.decimals == 3) score += 0.5
                    if (g.decimals == 3) score += 0.5
                    // Price and gallons multiply the same either way round: use last time's price, else assume the
                    // tank took more gallons than the price in dollars.
                    val lastPpg = hint.lastPpg
                    val pFirst = if (lastPpg != null) abs(p.value - lastPpg) <= abs(g.value - lastPpg) else p.value <= g.value
                    if (pFirst) score += 1
                    if (score > bestScore) {
                        bestScore = score
                        best = Triple(p, g, c)
                    }
                }
            }
        }
        best?.let { (p, g, c) ->
            return listOf(
                Pick(LiveField.PPG, p.value, 2, p.line),
                Pick(LiveField.GALLONS, g.value, 2, g.line),
                Pick(LiveField.COST, c.value, 2, c.line),
            )
        }
        // No complete set in view: fall back to labeled numbers (weak).
        val out = mutableListOf<Pick>()
        tokens.firstOrNull { it.label == Label.PPG && it.decimals >= 2 && it.value in 1.0..12.0 }
            ?.let { out += Pick(LiveField.PPG, it.value, 1, it.line) }
        tokens.firstOrNull { it.label == Label.GALLONS && it.decimals >= 2 && it.value in 0.1..80.0 && !it.dollar }
            ?.let { out += Pick(LiveField.GALLONS, it.value, 1, it.line) }
        tokens.firstOrNull { (it.label == Label.COST || it.dollar) && it.decimals == 2 && it.value in 0.5..500.0 && it.label !in NOT_PUMP }
            ?.let { out += Pick(LiveField.COST, it.value, 1, it.line) }
        return out
    }

    private fun octane(tokens: List<NumToken>): Pick? =
        tokens.firstOrNull { it.label == Label.OCTANE && it.decimals == 0 && it.value in 85.0..100.0 }
            ?.let { Pick(LiveField.OCTANE, it.value, 1, it.line) }

    private val NOT_ODO = setOf(Label.IGNORE, Label.OCTANE, Label.PPG, Label.GALLONS, Label.COST, Label.TRIP_A)

    private fun odometer(tokens: List<NumToken>, hint: LiveHint): Pick? {
        val last = hint.lastOdo
        return tokens
            .filter { it.decimals == 0 && it.digits in 3..7 && it.label !in NOT_ODO && !it.dollar }
            .mapNotNull { t ->
                val nearLast = last != null && t.value > last && t.value <= last + 1500
                val weight = when {
                    t.label == Label.ODO && (last == null || nearLast) -> 2
                    t.label == Label.ODO -> 1
                    nearLast && t.label == Label.DIST -> 2
                    nearLast -> 1
                    last == null && t.label == Label.DIST && t.digits >= 4 -> 1
                    else -> 0
                }
                if (weight > 0) Pick(LiveField.ODO, t.value, weight, t.line) else null
            }
            .maxByOrNull { it.weight }
    }

    private val NOT_TRIP = setOf(Label.IGNORE, Label.OCTANE, Label.PPG, Label.GALLONS, Label.COST, Label.ODO)

    private fun trip(tokens: List<NumToken>, hint: LiveHint): Pick? {
        val expected = if (hint.odoNow != null && hint.lastOdo != null) hint.odoNow - hint.lastOdo else null
        return tokens
            .filter { it.decimals == 1 && it.label !in NOT_TRIP && it.value in 0.1..1999.9 && !it.dollar }
            .mapNotNull { t ->
                var w = 0
                if (t.label == Label.TRIP_A) w += 2
                if (expected != null && expected > 0 && abs(t.value - expected) <= 3) w += 2
                if (w >= 2) Pick(LiveField.TRIP_A, t.value, w.coerceAtMost(2), t.line) else null
            }
            .maxByOrNull { it.weight }
    }
}

/** Collects picks across frames and locks a value once it has been seen enough. */
class LiveVotes(private val window: Int = 10, private val needed: Int = 4) {
    private val history = ArrayDeque<List<Pick>>()
    private val rejected = mutableMapOf<LiveField, MutableSet<Long>>()
    private val _locked = linkedMapOf<LiveField, Double>()
    val locked: Map<LiveField, Double> get() = _locked

    /** Adds one frame; returns the fields that just locked. */
    fun add(picks: List<Pick>): Set<LiveField> {
        history.addLast(picks)
        while (history.size > window) history.removeFirst()
        val newly = mutableSetOf<LiveField>()
        for (field in LiveField.entries) {
            if (field in _locked) continue
            val no = rejected[field].orEmpty()
            val best = history.flatten()
                .filter { it.field == field && key(field, it.value) !in no }
                .groupBy { key(field, it.value) }
                .maxByOrNull { (_, ps) -> ps.sumOf { it.weight } }
                ?: continue
            if (best.value.sumOf { it.weight } >= needed) {
                _locked[field] = best.value.first().value
                newly += field
            }
        }
        return newly
    }

    /** "That's wrong": forget the value and don't lock it again. */
    fun reject(field: LiveField) {
        val v = _locked.remove(field) ?: return
        rejected.getOrPut(field) { mutableSetOf() } += key(field, v)
    }

    private fun key(field: LiveField, v: Double): Long {
        var scale = 1.0
        repeat(field.decimals) { scale *= 10 }
        return (v * scale).roundToLong()
    }
}
