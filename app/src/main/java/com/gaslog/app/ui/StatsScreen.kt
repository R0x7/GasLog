package com.gaslog.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gaslog.app.GasViewModel
import com.gaslog.app.LogConfig
import com.gaslog.app.MonthSpend
import com.gaslog.app.Service
import com.gaslog.app.Stats
import com.gaslog.app.Tank
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

@Composable
fun StatsScreen(vm: GasViewModel, cfg: LogConfig) {
    Section("Car") { CarPicker(vm, cfg.cars, showAdd = false) }
    val car = vm.car ?: return
    val st = remember(car) { Stats.forCar(car) }

    if (st.fillCount == 0) {
        Section(null) { Text("No fill-ups on ${car.name} yet.", color = muted()) }
        return
    }

    Section("At a glance") {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile("Usual mpg", st.recentMpg?.let { fmt1(it) } ?: "—", "last 10 full tanks", Modifier.weight(1f))
            StatTile("Cost per mile", st.costPerMile?.let { "\$" + String.format(Locale.US, "%.3f", it) } ?: "—", "last 12 months", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile("Spent", "\$" + String.format(Locale.US, "%,.0f", st.yearCost), "last 12 months", Modifier.weight(1f))
            StatTile("Driven", GasViewModel.fmtInt(st.yearMiles) + " mi", "last 12 months", Modifier.weight(1f))
        }
    }

    Section("MPG per tank") {
        if (st.tanks.size < 2) {
            Text("Needs at least two full tanks with Trip A and gallons.", color = muted())
        } else {
            MpgChart(st.tanks.takeLast(30), st.recentMpg)
        }
    }

    Section("Spending by month") { SpendBars(st.months) }

    Section("Stations") {
        if (st.stations.isEmpty()) {
            Text("Fill up at the same station twice to see it ranked here.", color = muted())
        } else {
            st.stationHeadline?.let { Text(it, fontWeight = FontWeight.SemiBold) }
            Text("Average price per gallon, ${st.stationWindow} (2+ fill-ups)", style = MaterialTheme.typography.bodySmall, color = muted())
            st.stations.take(10).forEachIndexed { i, s ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}.", color = muted(), modifier = Modifier.padding(end = 8.dp))
                    Text(s.place, modifier = Modifier.weight(1f))
                    Text("${s.fills}×", color = muted(), modifier = Modifier.padding(end = 12.dp))
                    Text("\$" + String.format(Locale.US, "%.3f", s.avgPpg), fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }

    Section("Maintenance") {
        Service.entries.forEach { s ->
            val status = vm.serviceStatus(car, s)
            Text(
                (if (status.warn) "🔧 " else "") + status.text,
                fontWeight = if (status.warn) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
        Text("Log services from the Car card on the Log tab.", style = MaterialTheme.typography.bodySmall, color = muted())
    }
}

@Composable
private fun StatTile(label: String, value: String, sub: String, modifier: Modifier) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp), modifier = modifier) {
        Column(Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = muted())
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = muted())
        }
    }
}

private val DAY = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)
private val MONTH = DateTimeFormatter.ofPattern("MMM yyyy", Locale.US)

/** Line of mpg per tank with a dashed "usual" reference. Tap to read a point. */
@Composable
private fun MpgChart(tanks: List<Tank>, usual: Double?) {
    var sel by remember(tanks) { mutableIntStateOf(tanks.lastIndex) }
    val line = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val ref = MaterialTheme.colorScheme.onSurfaceVariant
    val surface = MaterialTheme.colorScheme.surface

    val values = tanks.map { it.mpg } + listOfNotNull(usual)
    val lo = floor(values.min() - 1)
    val hi = ceil(values.max() + 1)

    val picked = tanks[sel.coerceIn(tanks.indices)]
    Text(
        (picked.date?.format(DAY) ?: "Row ${picked.row}") + ": " + fmt1(picked.mpg) + " mpg" +
            (usual?.let { " · usual " + fmt1(it) } ?: ""),
        fontWeight = FontWeight.SemiBold,
    )
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(170.dp)
            .pointerInput(tanks) {
                detectTapGestures { pos ->
                    val pad = 10.dp.toPx()
                    val step = (size.width - 2 * pad) / (tanks.size - 1)
                    sel = tanks.indices.minByOrNull { abs(pad + it * step - pos.x) } ?: sel
                }
            },
    ) {
        val pad = 10.dp.toPx()
        val w = size.width - 2 * pad
        val h = size.height - 2 * pad
        fun x(i: Int) = pad + i * w / (tanks.size - 1)
        fun y(v: Double) = (pad + h - (v - lo) / (hi - lo) * h).toFloat()

        // Recessive grid: top, middle, bottom.
        for (k in 0..2) {
            val gy = pad + h * k / 2f
            drawLine(grid, Offset(pad, gy), Offset(pad + w, gy), strokeWidth = 1.dp.toPx())
        }
        if (usual != null) {
            drawLine(
                ref, Offset(pad, y(usual)), Offset(pad + w, y(usual)),
                strokeWidth = 1.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx())),
            )
        }
        val path = Path()
        tanks.forEachIndexed { i, t -> if (i == 0) path.moveTo(x(i), y(t.mpg)) else path.lineTo(x(i), y(t.mpg)) }
        drawPath(path, line, style = Stroke(width = 2.dp.toPx()))

        val p = Offset(x(sel.coerceIn(tanks.indices)), y(picked.mpg))
        drawLine(grid, Offset(p.x, pad), Offset(p.x, pad + h), strokeWidth = 1.dp.toPx())
        drawCircle(surface, radius = 6.dp.toPx(), center = p)
        drawCircle(line, radius = 4.dp.toPx(), center = p)
    }
    Row(Modifier.fillMaxWidth()) {
        Text("${fmt0(hi)} top · ${fmt0(lo)} bottom", style = MaterialTheme.typography.bodySmall, color = muted(), modifier = Modifier.weight(1f))
        Text("dashed = usual", style = MaterialTheme.typography.bodySmall, color = muted())
    }
}

/** Twelve monthly bars, rounded at the top, 2dp apart. Tap a bar to read it. */
@Composable
private fun SpendBars(months: List<MonthSpend>) {
    var sel by remember(months) { mutableIntStateOf(months.lastIndex) }
    val max = (months.maxOfOrNull { it.cost } ?: 0.0).coerceAtLeast(1.0)
    val bar = MaterialTheme.colorScheme.primary
    val picked = months[sel.coerceIn(months.indices)]
    Text(
        picked.month.format(MONTH) + ": \$" + String.format(Locale.US, "%,.2f", picked.cost),
        fontWeight = FontWeight.SemiBold,
    )
    Row(
        Modifier.fillMaxWidth().height(130.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        months.forEachIndexed { i, m ->
            val frac = (m.cost / max).toFloat().coerceIn(0f, 1f)
            Box(
                Modifier.weight(1f).fillMaxHeight().clickable { sel = i },
                contentAlignment = Alignment.BottomCenter,
            ) {
                if (m.cost > 0) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(frac.coerceAtLeast(0.03f))
                            .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                            .background(if (i == sel) bar else bar.copy(alpha = 0.5f)),
                    )
                }
            }
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        months.forEach { m ->
            Text(
                m.month.month.name.take(1),
                style = MaterialTheme.typography.bodySmall,
                color = muted(),
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

private fun fmt1(d: Double) = String.format(Locale.US, "%.1f", d)
private fun fmt0(d: Double) = String.format(Locale.US, "%.0f", d)

