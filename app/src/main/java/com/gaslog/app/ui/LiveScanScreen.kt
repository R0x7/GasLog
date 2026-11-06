package com.gaslog.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Paint
import android.util.Size
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.mlkit.vision.MlKitAnalyzer
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.gaslog.app.GasViewModel
import com.gaslog.app.LiveField
import com.gaslog.app.LiveReader
import com.gaslog.app.LiveVotes
import com.gaslog.app.OcrLine
import com.gaslog.app.Pick
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.delay

private val LOCKED = Color(0xFF40C057)
private val SEEN = Color(0xFFFFD43B)

/** The fields that have to be found before the scan finishes by itself (octane is a bonus). */
private val CORE = listOf(LiveField.PPG, LiveField.GALLONS, LiveField.COST, LiveField.ODO, LiveField.TRIP_A)

/**
 * Full-screen camera view. Point it at the pump, then at the dash: numbers are recognized on the phone
 * as you move, locked in once they've been read the same way a few times, and sent to the form.
 */
@Composable
fun LiveScanScreen(vm: GasViewModel) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var denied by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        denied = !ok
    }
    LaunchedEffect(Unit) { if (!granted) ask.launch(Manifest.permission.CAMERA) }
    BackHandler { vm.closeLiveScan() }

    Surface(Modifier.fillMaxSize(), color = Color.Black) {
        when {
            granted -> Scanner(vm)
            denied -> Column(
                Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "Live scan needs the camera. You can allow it in Android settings, or take photos instead.",
                    color = Color.White,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = { ask.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
                TextButton(onClick = { vm.closeLiveScan() }) { Text("Back", color = Color.White) }
            }
        }
    }
}

@Composable
private fun Scanner(vm: GasViewModel) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val votes = remember { LiveVotes() }
    val locked = remember { mutableStateMapOf<LiveField, Double>() }
    // Latest frame grabbed when pump / dash numbers locked in; handed on as photos so Gemini can fill gaps.
    val snaps = remember { mutableStateMapOf<Boolean, Bitmap>() }
    var picks by remember { mutableStateOf<List<Pick>>(emptyList()) }
    var torch by remember { mutableStateOf(false) }

    val preview = remember {
        PreviewView(context).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            scaleType = PreviewView.ScaleType.FILL_CENTER
            // TextureView: lets us grab the current frame as a bitmap.
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    val controller = remember { LifecycleCameraController(context) }

    fun finish(includeCurrent: Boolean) {
        val frames = buildList {
            snaps[true]?.let { add(it) }
            snaps[false]?.let { add(it) }
            if (includeCurrent && CORE.any { it !in locked }) preview.bitmap?.let { add(it) }
        }
        vm.applyLive(locked.toMap(), frames)
    }

    DisposableEffect(owner) {
        val main = ContextCompat.getMainExecutor(context)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        preview.controller = controller
        controller.setEnabledUseCases(CameraController.IMAGE_ANALYSIS)
        controller.imageAnalysisBackpressureStrategy = ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
        controller.imageAnalysisResolutionSelector = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(Size(1920, 1080), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER),
            )
            .build()
        controller.setImageAnalysisAnalyzer(
            main,
            // View-referenced coordinates: the boxes line up with the preview on screen.
            MlKitAnalyzer(listOf(recognizer), ImageAnalysis.COORDINATE_SYSTEM_VIEW_REFERENCED, main) { result ->
                val text = result.getValue(recognizer) ?: return@MlKitAnalyzer
                val lines = text.textBlocks.flatMap { it.lines }.mapNotNull { l ->
                    val b = l.boundingBox ?: return@mapNotNull null
                    OcrLine(l.text, b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat())
                }
                val frame = LiveReader.analyze(lines, vm.liveHint(votes.locked[LiveField.ODO]))
                picks = frame
                val newly = votes.add(frame)
                if (newly.isNotEmpty()) {
                    locked.putAll(votes.locked)
                    newly.map { it.pump }.toSet().forEach { pump -> preview.bitmap?.let { snaps[pump] = it } }
                }
            },
        )
        controller.bindToLifecycle(owner)
        onDispose {
            controller.clearImageAnalysisAnalyzer()
            controller.unbind()
            recognizer.close()
        }
    }

    // Everything found: show it all green for a moment, then go back to the form.
    val complete = CORE.all { it in locked }
    LaunchedEffect(complete) {
        if (complete) {
            delay(1200)
            finish(includeCurrent = false)
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { preview }, modifier = Modifier.fillMaxSize())
        BoxOverlay(picks, locked)

        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundButton("✕") { vm.closeLiveScan() }
            Spacer(Modifier.weight(1f))
            RoundButton(if (torch) "🔦 On" else "🔦") {
                torch = !torch
                controller.enableTorch(torch)
            }
        }

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color(0xCC000000))
                .navigationBarsPadding()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                when {
                    complete -> "Got everything ✓"
                    LiveField.entries.filter { it.pump && it != LiveField.OCTANE }.any { it !in locked } ->
                        "Point at the pump display — price, gallons and total."
                    else -> "Now the dashboard — odometer and Trip A."
                },
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
            )
            FieldGrid(locked) { f ->
                votes.reject(f)
                locked.remove(f)
            }
            Text(
                "Hold steady; numbers turn green when they're locked in. Tap a wrong one to read it again.",
                color = Color(0xFFCED4DA),
                fontSize = 12.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { vm.closeLiveScan() }, modifier = Modifier.weight(1f)) {
                    Text("Cancel", color = Color.White)
                }
                Button(onClick = { finish(includeCurrent = true) }, modifier = Modifier.weight(1f)) {
                    Text(if (locked.isEmpty()) "Use this frame" else "Done", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun FieldGrid(locked: Map<LiveField, Double>, onReject: (LiveField) -> Unit) {
    LiveField.entries.chunked(3).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { f ->
                val v = locked[f]
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (v != null) LOCKED.copy(alpha = 0.25f) else Color(0x33FFFFFF),
                    modifier = Modifier.weight(1f).clickable(enabled = v != null) { onReject(f) },
                ) {
                    Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                        Text(f.title, color = Color(0xFFCED4DA), fontSize = 11.sp)
                        Text(
                            if (v != null) "✓ " + format(f, v) else "…",
                            color = if (v != null) Color.White else Color(0xFF868E96),
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                        )
                    }
                }
            }
        }
    }
}

/** Outlines the numbers the current frame read: green when that value is locked, yellow while still checking. */
@Composable
private fun BoxOverlay(picks: List<Pick>, locked: Map<LiveField, Double>) {
    val paint = remember { Paint().apply { isAntiAlias = true; textSize = 34f; isFakeBoldText = true } }
    Canvas(Modifier.fillMaxSize()) {
        for (p in picks) {
            val l = p.line
            val color = if (locked[p.field] == p.value) LOCKED else SEEN
            drawRoundRect(
                color = color,
                topLeft = Offset(l.left - 6f, l.top - 6f),
                size = androidx.compose.ui.geometry.Size(l.right - l.left + 12f, l.bottom - l.top + 12f),
                cornerRadius = CornerRadius(12f, 12f),
                style = Stroke(width = 5f),
            )
            paint.color = color.toArgb()
            drawContext.canvas.nativeCanvas.drawText(p.field.title, l.left - 6f, l.top - 14f, paint)
        }
    }
}

@Composable
private fun RoundButton(text: String, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(50),
        color = Color(0x88000000),
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(text, color = Color.White, fontSize = 18.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
    }
}

private fun format(f: LiveField, v: Double): String = when (f) {
    LiveField.ODO -> GasViewModel.fmtInt(v)
    else -> String.format(java.util.Locale.US, "%.${f.decimals}f", v)
}
