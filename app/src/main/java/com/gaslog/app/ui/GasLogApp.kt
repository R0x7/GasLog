@file:OptIn(ExperimentalLayoutApi::class)

package com.gaslog.app.ui

import android.Manifest
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gaslog.app.Car
import com.gaslog.app.FieldState
import com.gaslog.app.GasViewModel
import com.gaslog.app.LocationHelper
import com.gaslog.app.LogConfig
import com.gaslog.app.PhotoUtils
import com.gaslog.app.SaveResult
import com.gaslog.app.Screen
import com.gaslog.app.Service
import com.gaslog.app.Src
import com.gaslog.app.Updates
import java.util.Locale

private const val KEY_URL = "https://aistudio.google.com/apikey"

@Composable
fun GasLogApp(vm: GasViewModel) {
    LaunchedEffect(vm.sheetId) {
        if (vm.sheetId != null && vm.config == null) vm.refresh()
    }
    LaunchedEffect(Unit) { vm.locate() }
    val uri = LocalUriHandler.current

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Header(vm)
            val cfg = vm.config
            if (cfg != null && cfg.cars.isNotEmpty()) ScreenTabs(vm)

            vm.update?.let { r ->
                Banner(
                    "Update available: ${r.name}.",
                    warn = false,
                    action = "Install",
                    onAction = { uri.openUri(Updates.APK_URL) },
                    secondAction = "Later",
                    onSecond = { vm.dismissUpdate() },
                )
            }
            if (vm.queuedCount > 0) {
                Banner(
                    if (vm.queuedCount == 1) "1 fill-up is saved on this phone, waiting for a connection."
                    else "${vm.queuedCount} fill-ups are saved on this phone, waiting for a connection.",
                    action = if (vm.flushing) "Sending…" else "Send now",
                    onAction = { vm.flushQueue() },
                )
            }
            vm.message?.let { msg ->
                Banner(
                    msg,
                    action = if (vm.sheetId != null && vm.config == null) "Try again" else "OK",
                    onAction = { vm.onMessageAction() },
                )
            }
            if (!vm.hasKey) KeyCard(vm)

            when {
                vm.sheetId == null -> SetupCard(vm)
                cfg == null -> {
                    if (vm.loading) LoadingRow("Opening your sheet…")
                }
                vm.screen == Screen.STATS && cfg.cars.isNotEmpty() -> StatsScreen(vm, cfg)
                else -> LogScreen(vm, cfg)
            }
            Spacer(Modifier.height(24.dp))
        }
        if (vm.showSettings) SettingsDialog(vm)
        if (vm.liveScan) LiveScanScreen(vm)
    }
}

/* ───────────── shell ───────────── */

@Composable
private fun Header(vm: GasViewModel) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("⛽ Gas Log", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            vm.config?.title?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = muted()) }
        }
        if (vm.loading && vm.config != null) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
        TextButton(onClick = { vm.showSettings = true }) { Text("Settings") }
    }
}

@Composable
private fun ScreenTabs(vm: GasViewModel) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Pill("Log", selected = vm.screen == Screen.LOG) { vm.screen = Screen.LOG }
        Pill("Stats", selected = vm.screen == Screen.STATS) { vm.screen = Screen.STATS }
    }
}

/* ───────────── setup ───────────── */

@Composable
private fun SetupCard(vm: GasViewModel) {
    var link by rememberSaveable { mutableStateOf("") }
    var firstCar by rememberSaveable { mutableStateOf("") }
    Section("Connect your gas log") {
        Text(
            "Paste the link to your Google Sheet. Every tab whose first columns are date · trip (or odometer) · trip a counts as a car.",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
            value = link,
            onValueChange = { link = it },
            label = { Text("Google Sheets link") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { vm.connect(link) },
            enabled = link.isNotBlank() && !vm.loading,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Use this sheet") }

        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        Text("No sheet yet? Make a new one in your Google Drive:", style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
            value = firstCar,
            onValueChange = { firstCar = it },
            label = { Text("Car name, e.g. Civic") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(
            onClick = { vm.createLog(firstCar) },
            enabled = !vm.loading,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Create a new gas log") }
        if (vm.loading) LoadingRow("Talking to Google…")
    }
}

@Composable
private fun KeyCard(vm: GasViewModel) {
    var key by rememberSaveable { mutableStateOf("") }
    val uri = LocalUriHandler.current
    Section("Gemini API key") {
        Text(
            "Gas Log reads your photos with your own Gemini key. It's free from Google AI Studio and stays on this phone.",
            style = MaterialTheme.typography.bodyMedium,
        )
        TextButton(onClick = { uri.openUri(KEY_URL) }) { Text("Get a free key") }
        OutlinedTextField(
            value = key,
            onValueChange = { key = it },
            label = { Text("Paste your key") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { vm.saveKey(key); key = "" },
            enabled = key.trim().length >= 20,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Save key") }
    }
}

/* ───────────── log screen ───────────── */

@Composable
private fun LogScreen(vm: GasViewModel, cfg: LogConfig) {
    CarCard(vm, cfg)
    LocationPrompt(vm)
    val done = vm.saved
    if (done != null) {
        DoneCard(vm, done)
    } else if (cfg.cars.isNotEmpty()) {
        PhotoCard(vm)
        FormCard(vm, cfg)
    }
}

@Composable
private fun CarCard(vm: GasViewModel, cfg: LogConfig) {
    var logging by remember { mutableStateOf<Service?>(null) }
    val current = vm.car
    Section("Car") {
        if (cfg.cars.isEmpty()) Text("No car tabs yet.", color = muted())
        CarPicker(vm, cfg.cars, showAdd = true)
        if (current != null) {
            val lastOdo = current.lastOdo
            Text(
                if (lastOdo != null) {
                    "Last fill-up ${current.lastDate} at ${GasViewModel.fmtInt(lastOdo)} mi · next row ${current.nextRow}"
                } else {
                    "Saves to row ${current.nextRow}"
                },
                style = MaterialTheme.typography.bodySmall,
                color = muted(),
            )
            Service.entries.forEach { s ->
                val st = vm.serviceStatus(current, s)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        (if (st.warn) "🔧 " else "") + st.text,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (st.warn) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (st.warn) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { logging = s }, enabled = !vm.loading) { Text("Log") }
                }
            }
            if (current.fills.isNotEmpty() && vm.editingRow == null) {
                TextButton(onClick = { vm.startEditLast() }) { Text("Edit last fill-up") }
            }
        }
    }
    val s = logging
    if (s != null && current != null) {
        TextDialog(
            title = "Log ${s.title.lowercase()}",
            label = "Odometer",
            confirm = "Log it",
            initial = current.lastOdo?.let { GasViewModel.trimNum(it) }.orEmpty(),
            message = "Adds today's ${s.title.lowercase()} to the ${current.name} tab.",
            numeric = true,
            onDismiss = { logging = null },
            onConfirm = { odo -> logging = null; vm.logService(s, odo) },
        )
    }
}

@Composable
private fun LocationPrompt(vm: GasViewModel) {
    val context = LocalContext.current
    if (vm.locationAsked || LocationHelper.hasPermission(context) || vm.saved != null) return
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        vm.onLocationAnswer(result.values.any { it })
    }
    Banner(
        "Remember stations? Gas Log can fill in the place when you're back at a station you've used. Your location stays on this phone.",
        warn = false,
        action = "Allow",
        onAction = {
            launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        },
        secondAction = "No thanks",
        onSecond = { vm.onLocationAnswer(false) },
    )
}

@Composable
private fun PhotoCard(vm: GasViewModel) {
    val context = LocalContext.current
    var pendingUri by rememberSaveable { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = pendingUri?.let { Uri.parse(it) }
        if (ok && uri != null) vm.addPhoto(uri)
    }
    val gallery = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(GasViewModel.MAX_PHOTOS),
    ) { uris -> uris.take((GasViewModel.MAX_PHOTOS - vm.photos.size).coerceAtLeast(0)).forEach { vm.addPhoto(it) } }

    fun takePhoto() {
        val uri = PhotoUtils.newCaptureUri(context)
        pendingUri = uri.toString()
        camera.launch(uri)
    }

    LaunchedEffect(vm.quickPhoto) {
        if (vm.quickPhoto) {
            vm.consumeQuickPhoto()
            takePhoto()
        }
    }

    Section("Photos") {
        Button(
            onClick = { vm.openLiveScan() },
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text("📷  Live scan — just point the camera", fontWeight = FontWeight.Bold) }
        Text(
            "Or add photos and let Gemini read them:",
            style = MaterialTheme.typography.bodySmall,
            color = muted(),
        )
        Text(
            "Snap the pump and the dash in any order — one dash photo is enough if it shows Trip A and the odometer.",
            style = MaterialTheme.typography.bodySmall,
            color = muted(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            vm.photos.forEachIndexed { i, photo ->
                Box(
                    Modifier
                        .weight(1f)
                        .height(110.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp)),
                ) {
                    Image(
                        bitmap = photo.thumb.asImageBitmap(),
                        contentDescription = "Photo ${i + 1}",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(4.dp)
                            .size(26.dp)
                            .clip(RoundedCornerShape(50))
                            .background(Color(0x99000000))
                            .clickable { vm.removePhoto(i) },
                        contentAlignment = Alignment.Center,
                    ) { Text("×", color = Color.White, fontSize = 16.sp) }
                }
            }
            if (vm.photos.size < GasViewModel.MAX_PHOTOS) {
                Box(Modifier.weight(1f)) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .height(110.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .border(2.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                            .clickable { menu = true },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text("📷", fontSize = 26.sp)
                        Text(if (vm.photos.isEmpty()) "Add photo" else "Add", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Take photo") }, onClick = { menu = false; takePhoto() })
                        DropdownMenuItem(
                            text = { Text("Choose from gallery") },
                            onClick = {
                                menu = false
                                gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            },
                        )
                    }
                }
            }
            // Keep tiles a sensible width when there are few photos.
            repeat((GasViewModel.MAX_PHOTOS - 1 - vm.photos.size).coerceAtLeast(0)) { Spacer(Modifier.weight(1f)) }
        }
        Button(
            onClick = { vm.readPhotos() },
            enabled = vm.photos.isNotEmpty() && !vm.reading,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            if (vm.reading) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                Spacer(Modifier.width(10.dp))
                Text("Reading…")
            } else {
                Text("Read photos", fontWeight = FontWeight.Bold)
            }
        }
        vm.readStatus?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = muted()) }
    }
}

@Composable
private fun FormCard(vm: GasViewModel, cfg: LogConfig) {
    val editing = vm.editingRow
    Section(if (editing != null) "Edit fill-up" else "Check & save") {
        if (editing != null) {
            Banner(
                "Editing row $editing of ${vm.car?.name.orEmpty()}. Saving replaces that row.",
                action = "Cancel edit",
                onAction = { vm.cancelEdit() },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FormField(vm, vm.date, "Date", KeyboardType.Text, Modifier.weight(1f))
            FormField(vm, vm.octane, "Octane", KeyboardType.Number, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FormField(vm, vm.odo, "Odometer", KeyboardType.Number, Modifier.weight(1f))
            FormField(vm, vm.tripA, "Trip A", KeyboardType.Decimal, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FormField(vm, vm.ppg, "\$/gal", KeyboardType.Decimal, Modifier.weight(1f))
            FormField(vm, vm.gallons, "Gallons", KeyboardType.Decimal, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FormField(vm, vm.cost, "Cost \$", KeyboardType.Decimal, Modifier.weight(1f))
            FormField(vm, vm.place, "Place", KeyboardType.Text, Modifier.weight(1f))
        }
        PlaceSuggestions(vm, cfg.places)
        FormField(vm, vm.notes, "Notes (optional)", KeyboardType.Text, Modifier.fillMaxWidth())

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Filled to full", fontWeight = FontWeight.SemiBold)
                Text(
                    if (vm.partial) "Partial fill — mpg will be counted with your next full tank."
                    else "Turn off if you didn't fill the tank all the way.",
                    style = MaterialTheme.typography.bodySmall,
                    color = muted(),
                )
            }
            Switch(checked = !vm.partial, onCheckedChange = { vm.setPartialFill(!it) })
        }

        vm.checks().forEach { c ->
            Surface(
                color = if (c.warn) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    c.text,
                    color = if (c.warn) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSecondaryContainer,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                )
            }
        }

        Button(
            onClick = { vm.save() },
            enabled = !vm.saving,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            val label = when {
                vm.saveArmed -> "Save anyway"
                editing != null -> "Save changes"
                else -> "Save to sheet"
            }
            Text(label, fontWeight = FontWeight.Bold)
        }
        vm.saveStatus?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = muted()) }
    }
}

@Composable
private fun FormField(vm: GasViewModel, f: FieldState, label: String, keyboard: KeyboardType, modifier: Modifier) {
    val accent = when (f.src) {
        Src.PHOTO, Src.LIVE -> photoColor()
        Src.CALC, Src.NEARBY -> calcColor()
        Src.NONE -> null
    }
    val suffix = when (f.src) {
        Src.PHOTO -> " · photo"
        Src.LIVE -> " · live"
        Src.CALC -> " · calc"
        Src.NEARBY -> " · nearby"
        Src.NONE -> ""
    }
    OutlinedTextField(
        value = f.text,
        onValueChange = { vm.edit(f, it) },
        label = { Text(label + suffix, color = accent ?: Color.Unspecified) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        colors = if (accent != null) {
            OutlinedTextFieldDefaults.colors(focusedBorderColor = accent, unfocusedBorderColor = accent)
        } else {
            OutlinedTextFieldDefaults.colors()
        },
        modifier = modifier,
    )
}

/** Tappable names of stations you've used before. */
@Composable
private fun PlaceSuggestions(vm: GasViewModel, places: List<String>) {
    val typed = vm.place.text.trim().lowercase()
    if (typed.isNotEmpty() && places.any { it.lowercase() == typed }) return
    val matches = places.filter { typed.isEmpty() || it.lowercase().startsWith(typed) }.take(8)
    if (matches.isEmpty()) return
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        matches.forEach { p -> Pill(p) { vm.edit(vm.place, p) } }
    }
}

@Composable
private fun DoneCard(vm: GasViewModel, s: SaveResult) {
    val car: Car? = vm.config?.cars?.firstOrNull { it.name == s.car }
    Section(null) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (s.queued) "📶" else "✅", fontSize = 40.sp)
            Text(
                when {
                    s.queued -> "Saved on this phone. It goes to ${s.car} as soon as you're back online."
                    s.edited -> "Updated ${s.car}, row ${s.row}."
                    else -> "Saved to ${s.car}, row ${s.row}."
                },
                textAlign = TextAlign.Center,
            )
            val mpg = s.mpg
            if (mpg != null) {
                Text(
                    String.format(Locale.US, "%.1f mpg", mpg),
                    fontSize = 34.sp,
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
            } else if (vm.partial) {
                Text(
                    "Partial fill — this tank's mpg will show up with your next full fill-up.",
                    color = muted(),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
            }
            s.perMile?.let { Text(String.format(Locale.US, "\$%.3f per mile", it), color = muted()) }
        }
        vm.doneAlert?.let { Banner("📉 $it") }
        if (car != null) {
            Service.entries.map { vm.serviceStatus(car, it) }.filter { it.warn }.forEach { Banner("🔧 ${it.text}") }
        }
        Text(
            "Reset Trip A on the dash before you drive off.",
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = { vm.newEntry() }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text("Log another fill-up", fontWeight = FontWeight.Bold)
        }
        if (!s.edited) {
            OutlinedButton(
                onClick = { vm.undoLast() },
                enabled = !vm.saving,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (s.queued) "Undo (remove from queue)" else "Undo — clear this row") }
        }
    }
}

/* ───────────── settings ───────────── */

@Composable
private fun SettingsDialog(vm: GasViewModel) {
    var key by remember { mutableStateOf("") }
    var oil by remember { mutableStateOf(vm.oilInterval.toString()) }
    var tires by remember { mutableStateOf(vm.tireInterval.toString()) }
    val uri = LocalUriHandler.current
    AlertDialog(
        onDismissRequest = { vm.showSettings = false },
        title = { Text("Settings") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Sheet: " + (vm.config?.title ?: if (vm.sheetId == null) "not connected" else "…"), fontWeight = FontWeight.SemiBold)
                if (vm.sheetId != null) {
                    OutlinedButton(onClick = { vm.refresh(); vm.showSettings = false }, modifier = Modifier.fillMaxWidth()) {
                        Text("Reload sheet")
                    }
                    OutlinedButton(onClick = { vm.disconnect(); vm.showSettings = false }, modifier = Modifier.fillMaxWidth()) {
                        Text("Use a different sheet")
                    }
                }

                HorizontalDivider()
                Text("Gemini key", fontWeight = FontWeight.SemiBold)
                Text(if (vm.hasKey) "Saved on this phone." else "No key yet.", color = muted())
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text(if (vm.hasKey) "Replace key" else "Paste key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row {
                    TextButton(onClick = { uri.openUri(KEY_URL) }) { Text("Get a free key") }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { vm.saveKey(key); key = "" }, enabled = key.trim().length >= 20) { Text("Save key") }
                }

                HorizontalDivider()
                Text("Maintenance reminders (miles between)", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = oil, onValueChange = { oil = it }, label = { Text("Oil") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = tires, onValueChange = { tires = it }, label = { Text("Tires") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f),
                    )
                }
                TextButton(onClick = { vm.setIntervals(oil, tires) }) { Text("Save intervals") }

                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Quick launch opens live scan", fontWeight = FontWeight.SemiBold)
                        Text(
                            if (vm.quickLive) "Tile, widget and shortcut open the live camera scan."
                            else "Tile, widget and shortcut open the camera app for a photo.",
                            color = muted(),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(checked = vm.quickLive, onCheckedChange = { vm.changeQuickLive(it) })
                }

                HorizontalDivider()
                Text("Remembered stations", fontWeight = FontWeight.SemiBold)
                Text(
                    if (vm.stationCount == 0) "None yet. Stations are remembered when you save with location on."
                    else "${vm.stationCount} on this phone.",
                    color = muted(),
                )
                if (vm.stationCount > 0) TextButton(onClick = { vm.forgetStations() }) { Text("Forget them") }

                HorizontalDivider()
                Text("Version 1.0.${vm.installedVersion()}", fontWeight = FontWeight.SemiBold)
                TextButton(onClick = { vm.checkForUpdate(force = true) }) { Text("Check for updates") }
            }
        },
        confirmButton = { TextButton(onClick = { vm.showSettings = false }) { Text("Done") } },
    )
}
