@file:OptIn(ExperimentalLayoutApi::class)

package com.gaslog.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
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
import com.gaslog.app.FieldState
import com.gaslog.app.GasViewModel
import com.gaslog.app.LogConfig
import com.gaslog.app.PhotoUtils
import com.gaslog.app.PreparedPhoto
import com.gaslog.app.SaveResult
import com.gaslog.app.Slot
import com.gaslog.app.Src

private const val KEY_URL = "https://aistudio.google.com/apikey"

@Composable
fun GasLogApp(vm: GasViewModel) {
    LaunchedEffect(vm.sheetId) {
        if (vm.sheetId != null && vm.config == null) vm.refresh()
    }
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
            vm.message?.let { msg ->
                MessageCard(msg, onRetry = if (vm.sheetId != null && vm.config == null) ({ vm.refresh() }) else null)
            }
            if (!vm.hasKey) KeyCard(vm)
            when {
                vm.sheetId == null -> SetupCard(vm)
                vm.config == null -> {
                    if (vm.loading) LoadingRow("Opening your sheet…")
                }
                else -> MainContent(vm)
            }
            Spacer(Modifier.height(24.dp))
        }
        if (vm.showSettings) SettingsDialog(vm)
    }
}

/* ───────────── pieces ───────────── */

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
private fun Section(title: String?, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (title != null) {
                Text(
                    title.uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = muted(),
                    letterSpacing = 1.sp,
                )
            }
            content()
        }
    }
}

@Composable
private fun MessageCard(text: String, onRetry: (() -> Unit)?) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(text, color = MaterialTheme.colorScheme.onTertiaryContainer)
            if (onRetry != null) TextButton(onClick = onRetry) { Text("Try again") }
        }
    }
}

@Composable
private fun LoadingRow(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(10.dp))
        Text(text, color = muted())
    }
}

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

@Composable
private fun MainContent(vm: GasViewModel) {
    val cfg = vm.config ?: return
    CarCard(vm, cfg)
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
    var adding by remember { mutableStateOf(false) }
    val current = vm.car
    Section("Car") {
        if (cfg.cars.isEmpty()) Text("No car tabs yet.", color = muted())
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            cfg.cars.forEach { c ->
                if (c.name == current?.name) {
                    Button(onClick = {}) { Text(c.name) }
                } else {
                    OutlinedButton(onClick = { vm.selectCar(c.name) }) { Text(c.name) }
                }
            }
            TextButton(onClick = { adding = true }, enabled = !vm.loading) { Text("+ Add car") }
        }
        if (current != null) {
            val lastOdo = current.lastOdo
            Text(
                if (lastOdo != null) {
                    "Last fill-up ${current.lastDate} at ${GasViewModel.fmtInt(lastOdo)} mi · saves to row ${current.nextRow}"
                } else {
                    "Saves to row ${current.nextRow}"
                },
                style = MaterialTheme.typography.bodySmall,
                color = muted(),
            )
        }
    }
    if (adding) {
        NameDialog(
            title = "Add a car",
            confirm = "Add tab",
            onDismiss = { adding = false },
            onConfirm = { name -> adding = false; vm.addCar(name) },
        )
    }
}

@Composable
private fun PhotoCard(vm: GasViewModel) {
    val context = LocalContext.current
    var pendingSlot by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingUri by rememberSaveable { mutableStateOf<String?>(null) }

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val slot = pendingSlot?.let { Slot.valueOf(it) }
        val uri = pendingUri?.let { Uri.parse(it) }
        if (ok && slot != null && uri != null) vm.addPhoto(slot, uri)
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val slot = pendingSlot?.let { Slot.valueOf(it) }
        if (uri != null && slot != null) vm.addPhoto(slot, uri)
    }

    Section("Photos") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Slot.entries.forEach { slot ->
                PhotoTile(
                    slot = slot,
                    photo = vm.photos[slot],
                    modifier = Modifier.weight(1f),
                    onCamera = {
                        val uri = PhotoUtils.newCaptureUri(context)
                        pendingSlot = slot.name
                        pendingUri = uri.toString()
                        camera.launch(uri)
                    },
                    onGallery = {
                        pendingSlot = slot.name
                        gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    onRemove = { vm.removePhoto(slot) },
                )
            }
        }
        Text(
            "One dash photo is enough if it shows both Trip A and the odometer.",
            style = MaterialTheme.typography.bodySmall,
            color = muted(),
        )
        Button(
            onClick = { vm.readPhotos() },
            enabled = vm.photos.isNotEmpty() && !vm.reading,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            if (vm.reading) {
                CircularProgressIndicator(
                    Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
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
private fun PhotoTile(
    slot: Slot,
    photo: PreparedPhoto?,
    modifier: Modifier,
    onCamera: () -> Unit,
    onGallery: () -> Unit,
    onRemove: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp)
    Box(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(
                    2.dp,
                    if (photo != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                    shape,
                )
                .clickable { menu = true },
            contentAlignment = Alignment.Center,
        ) {
            if (photo != null) {
                Image(
                    bitmap = photo.thumb.asImageBitmap(),
                    contentDescription = slot.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                Text(
                    slot.title,
                    color = Color.White,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(6.dp)
                        .background(Color(0x99000000), RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(slot.icon, fontSize = 28.sp)
                    Text(slot.title, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Text("tap to add", fontSize = 11.sp, color = muted())
                }
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Take photo") }, onClick = { menu = false; onCamera() })
            DropdownMenuItem(text = { Text("Choose from gallery") }, onClick = { menu = false; onGallery() })
            if (photo != null) {
                DropdownMenuItem(text = { Text("Remove") }, onClick = { menu = false; onRemove() })
            }
        }
    }
}

@Composable
private fun FormCard(vm: GasViewModel, cfg: LogConfig) {
    Section("Check & save") {
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
            Text(if (vm.saveArmed) "Save anyway" else "Save to sheet", fontWeight = FontWeight.Bold)
        }
        vm.saveStatus?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = muted()) }
    }
}

@Composable
private fun FormField(vm: GasViewModel, f: FieldState, label: String, keyboard: KeyboardType, modifier: Modifier) {
    val accent = when (f.src) {
        Src.PHOTO -> photoColor()
        Src.CALC -> calcColor()
        Src.NONE -> null
    }
    val suffix = when (f.src) {
        Src.PHOTO -> " · photo"
        Src.CALC -> " · calc"
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
    if (places.any { it.lowercase() == typed && typed.isNotEmpty() }) return
    val matches = places.filter { typed.isEmpty() || it.lowercase().startsWith(typed) }.take(8)
    if (matches.isEmpty()) return
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        matches.forEach { p ->
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.clickable { vm.edit(vm.place, p) },
            ) {
                Text(p, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
            }
        }
    }
}

@Composable
private fun DoneCard(vm: GasViewModel, s: SaveResult) {
    Section(null) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("✅", fontSize = 40.sp)
            Text("Saved to ${s.car}, row ${s.row}.", textAlign = TextAlign.Center)
            s.mpg?.let {
                Text(
                    String.format(java.util.Locale.US, "%.1f mpg", it),
                    fontSize = 34.sp,
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
            }
            s.perMile?.let {
                Text(String.format(java.util.Locale.US, "\$%.3f per mile", it), color = muted())
            }
            Spacer(Modifier.height(8.dp))
            Text("Reset Trip A on the dash before you drive off.", fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            Button(onClick = { vm.newEntry() }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text("Log another fill-up", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun SettingsDialog(vm: GasViewModel) {
    var key by remember { mutableStateOf("") }
    val uri = LocalUriHandler.current
    AlertDialog(
        onDismissRequest = { vm.showSettings = false },
        title = { Text("Settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Sheet: " + (vm.config?.title ?: if (vm.sheetId == null) "not connected" else "…"))
                if (vm.sheetId != null) {
                    OutlinedButton(
                        onClick = { vm.disconnect(); vm.showSettings = false },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Use a different sheet") }
                    OutlinedButton(
                        onClick = { vm.refresh(); vm.showSettings = false },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Reload sheet") }
                }
                HorizontalDivider()
                Text(if (vm.hasKey) "Gemini key is saved on this phone." else "No Gemini key yet.")
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
                    TextButton(
                        onClick = { vm.saveKey(key); key = "" },
                        enabled = key.trim().length >= 20,
                    ) { Text("Save key") }
                }
            }
        },
        confirmButton = { TextButton(onClick = { vm.showSettings = false }) { Text("Done") } },
    )
}

@Composable
private fun NameDialog(title: String, confirm: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Car name") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.trim()) }, enabled = name.isNotBlank()) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun muted(): Color = MaterialTheme.colorScheme.onSurfaceVariant
