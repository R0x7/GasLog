package com.gaslog.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gaslog.app.Car
import com.gaslog.app.GasViewModel

@Composable
fun Section(title: String?, content: @Composable ColumnScope.() -> Unit) {
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

/** A tinted banner: warnings use the tertiary (amber) container, good news the secondary (green). */
@Composable
fun Banner(
    text: String,
    warn: Boolean = true,
    action: String? = null,
    onAction: (() -> Unit)? = null,
    secondAction: String? = null,
    onSecond: (() -> Unit)? = null,
) {
    val bg = if (warn) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.secondaryContainer
    val fg = if (warn) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSecondaryContainer
    Surface(color = bg, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(text, color = fg, style = MaterialTheme.typography.bodyMedium)
            if (action != null || secondAction != null) {
                Row {
                    if (action != null && onAction != null) TextButton(onClick = onAction) { Text(action) }
                    if (secondAction != null && onSecond != null) TextButton(onClick = onSecond) { Text(secondAction) }
                }
            }
        }
    }
}

@Composable
fun LoadingRow(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(10.dp))
        Text(text, color = muted())
    }
}

/** The row of car buttons (+ "Add car"), shared by the Log and Stats screens. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CarPicker(vm: GasViewModel, cars: List<Car>, showAdd: Boolean) {
    var adding by remember { mutableStateOf(false) }
    val current = vm.car
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        cars.forEach { c ->
            if (c.name == current?.name) {
                Button(onClick = {}) { Text(c.name) }
            } else {
                OutlinedButton(onClick = { vm.selectCar(c.name) }) { Text(c.name) }
            }
        }
        if (showAdd) TextButton(onClick = { adding = true }, enabled = !vm.loading) { Text("+ Add car") }
    }
    if (adding) {
        TextDialog(
            title = "Add a car",
            label = "Car name",
            confirm = "Add tab",
            onDismiss = { adding = false },
            onConfirm = { name -> adding = false; vm.addCar(name) },
        )
    }
}

/** A dialog with one text field. */
@Composable
fun TextDialog(
    title: String,
    label: String,
    confirm: String,
    initial: String = "",
    message: String? = null,
    numeric: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (message != null) Text(message, style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text(label) },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = if (numeric) androidx.compose.ui.text.input.KeyboardType.Number
                        else androidx.compose.ui.text.input.KeyboardType.Text,
                    ),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value.trim()) }, enabled = value.isNotBlank()) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Tappable pill, e.g. a station name suggestion. */
@Composable
fun Pill(text: String, selected: Boolean = false, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

@Composable
fun muted(): Color = MaterialTheme.colorScheme.onSurfaceVariant
