package com.scanly.ui.capture

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Grid3x3
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.scanly.R
import com.scanly.platform.DocumentQuad
import java.util.concurrent.Executors

@Composable
fun CaptureScreen(
    appendToDocumentId: Long?,
    onFinished: (Long) -> Unit,
    onCancel: () -> Unit,
    vm: CaptureViewModel = hiltViewModel(),
) {
    val ui by vm.ui.collectAsState()
    LaunchedEffect(Unit) { vm.init(appendToDocumentId) }
    LaunchedEffect(ui.finishedDocumentId) { ui.finishedDocumentId?.let(onFinished) }

    val context = LocalContext.current
    var hasCamera by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasCamera = granted }

    LaunchedEffect(Unit) { if (!hasCamera) permLauncher.launch(Manifest.permission.CAMERA) }

    if (!hasCamera) {
        CameraRationale(onGrant = { permLauncher.launch(Manifest.permission.CAMERA) })
        return
    }

    CameraContent(ui = ui, vm = vm, onCancel = onCancel)
}

@Composable
private fun CameraContent(ui: CaptureUiState, vm: CaptureViewModel, onCancel: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val frameConverter = remember { PreviewFrameConverter() }
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .build()
    }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var flashMode by remember { mutableStateOf(ImageCapture.FLASH_MODE_OFF) }
    var showGrid by remember { mutableStateOf(false) }
    var capturing by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose { analysisExecutor.shutdown() }
    }

    fun capture() {
        if (capturing) return
        capturing = true
        imageCapture.takePicture(
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val bmp = image.toBitmapCompat()
                    image.close()
                    if (bmp != null) vm.onCaptured(bmp)
                    capturing = false
                }
                override fun onError(exc: ImageCaptureException) { capturing = false }
            },
        )
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                val providerFuture = ProcessCameraProvider.getInstance(ctx)
                providerFuture.addListener({
                    val provider = providerFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .build()
                    analysis.setAnalyzer(analysisExecutor) { proxy ->
                        // Detection runs synchronously on this single-threaded executor,
                        // so the converter can reuse its bitmaps frame-to-frame.
                        val frame = frameConverter.convert(proxy, targetWidth = 480)
                        proxy.close()
                        if (frame != null) {
                            val fire = vm.onPreviewFrame(frame)
                            if (fire) ContextCompat.getMainExecutor(ctx).execute { capture() }
                        }
                    }
                    provider.unbindAll()
                    camera = provider.bindToLifecycle(
                        lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA,
                        preview, analysis, imageCapture,
                    )
                }, ContextCompat.getMainExecutor(ctx))
                previewView
            },
            modifier = Modifier.fillMaxSize(),
        )

        // Live boundary overlay (preview coords are normalized in the converter).
        QuadOverlay(ui.liveQuad, stable = ui.state == CaptureState.STABLE)
        if (showGrid) GridOverlay()

        // ---- Top bar on a scrim ----
        Row(
            Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent),
                    ),
                )
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onCancel) {
                Icon(Icons.Default.Close, "Close", tint = Color.White)
            }
            StatusChip(ui)
            Row {
                IconButton(onClick = {
                    flashMode = when (flashMode) {
                        ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_AUTO
                        ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
                        else -> ImageCapture.FLASH_MODE_OFF
                    }
                    imageCapture.flashMode = flashMode
                }) {
                    Icon(
                        when (flashMode) {
                            ImageCapture.FLASH_MODE_ON -> Icons.Default.FlashOn
                            ImageCapture.FLASH_MODE_AUTO -> Icons.Default.FlashAuto
                            else -> Icons.Default.FlashOff
                        },
                        stringResource(R.string.flash), tint = Color.White,
                    )
                }
                IconButton(onClick = { showGrid = !showGrid }) {
                    Icon(
                        Icons.Default.Grid3x3, stringResource(R.string.grid),
                        tint = if (showGrid) Color(0xFFA7E8BD) else Color.White,
                    )
                }
            }
        }

        // ---- Bottom controls on a scrim ----
        Column(
            Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.65f)),
                    ),
                )
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Mode chips.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModeChip(stringResource(R.string.batch_mode), ui.batchMode, vm::toggleBatch)
                ModeChip(stringResource(R.string.auto_capture), ui.autoCapture && !ui.idCardMode,
                    vm::toggleAuto, enabled = !ui.idCardMode)
                ModeChip("ID card", ui.idCardMode, vm::toggleIdCard, icon = Icons.Default.Badge)
            }
            Spacer(Modifier.height(14.dp))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 36.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Page counter.
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "${ui.pageCount}",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Shutter(enabled = !capturing, onClick = ::capture)
                FilledIconButton(
                    onClick = vm::finish,
                    enabled = ui.pageCount > 0,
                    modifier = Modifier.size(44.dp),
                ) {
                    Icon(Icons.Default.Check, "Done")
                }
            }
        }
    }
}

@Composable
private fun StatusChip(ui: CaptureUiState) {
    val text = when {
        ui.idCardMode && !ui.idFrontCaptured -> "ID: capture the FRONT"
        ui.idCardMode -> "ID: now the BACK"
        ui.state == CaptureState.STABLE -> stringResource(R.string.hold_steady)
        else -> stringResource(R.string.searching)
    }
    Surface(
        color = Color.Black.copy(alpha = 0.45f),
        contentColor = Color.White,
        shape = CircleShape,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun ModeChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        label = { Text(label) },
        leadingIcon = icon?.let { { Icon(it, null, Modifier.size(16.dp)) } },
        colors = FilterChipDefaults.filterChipColors(
            containerColor = Color.Black.copy(alpha = 0.35f),
            labelColor = Color.White,
            iconColor = Color.White,
        ),
    )
}

@Composable
private fun Shutter(enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(76.dp)
            .clip(CircleShape)
            .border(4.dp, Color.White, CircleShape)
            .padding(7.dp)
            .clip(CircleShape)
            .background(if (enabled) Color.White else Color.White.copy(alpha = 0.4f))
            .clickable(enabled = enabled, onClick = onClick),
    )
}

@Composable
private fun GridOverlay() {
    Canvas(Modifier.fillMaxSize()) {
        val c = Color.White.copy(alpha = 0.35f)
        for (i in 1..2) {
            drawLine(c, Offset(size.width * i / 3f, 0f), Offset(size.width * i / 3f, size.height), 2f)
            drawLine(c, Offset(0f, size.height * i / 3f), Offset(size.width, size.height * i / 3f), 2f)
        }
    }
}

@Composable
private fun QuadOverlay(quad: DocumentQuad?, stable: Boolean) {
    if (quad == null) return
    val color = if (stable) Color(0xFF4CAF50) else Color(0xFFFFC107)
    Canvas(Modifier.fillMaxSize()) {
        // Preview frame was 480px wide; scale normalized corners to canvas.
        val sx = size.width / 480f
        val sy = size.height / (480f * 4f / 3f) // assume 4:3 analysis aspect
        fun pt(p: com.scanly.platform.QuadPoint) = Offset(p.x * sx, p.y * sy)
        val path = Path().apply {
            moveTo(pt(quad.topLeft).x, pt(quad.topLeft).y)
            lineTo(pt(quad.topRight).x, pt(quad.topRight).y)
            lineTo(pt(quad.bottomRight).x, pt(quad.bottomRight).y)
            lineTo(pt(quad.bottomLeft).x, pt(quad.bottomLeft).y)
            close()
        }
        drawPath(path, color, alpha = 0.9f, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 6f))
    }
}

@Composable
private fun CameraRationale(onGrant: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.camera_permission_rationale))
        Spacer(Modifier.height(16.dp))
        Button(onClick = onGrant) { Text(stringResource(R.string.grant_camera)) }
    }
}

/**
 * Converts RGBA_8888 ImageProxy frames to downscaled Bitmaps for edge detection,
 * REUSING both the full-size and the scaled bitmap across frames. At 30 fps a
 * fresh full-res ARGB bitmap per frame is ~270 MB/s of garbage; this allocates
 * only when the frame geometry changes.
 *
 * Not thread-safe by design: it must only be used from the single-threaded
 * analysis executor, and the returned bitmap is only valid until the next call.
 */
private class PreviewFrameConverter {
    private var full: Bitmap? = null
    private var scaled: Bitmap? = null
    private val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
    private val dstRect = android.graphics.Rect()

    fun convert(proxy: ImageProxy, targetWidth: Int): Bitmap? = try {
        val plane = proxy.planes[0]
        val rowWidth = plane.rowStride / plane.pixelStride
        val height = proxy.height

        val f = full?.takeIf { it.width == rowWidth && it.height == height }
            ?: Bitmap.createBitmap(rowWidth, height, Bitmap.Config.ARGB_8888)
                .also { full?.recycle(); full = it }
        plane.buffer.rewind()
        f.copyPixelsFromBuffer(plane.buffer)

        val targetHeight = (height * targetWidth.toFloat() / rowWidth).toInt().coerceAtLeast(1)
        val s = scaled?.takeIf { it.width == targetWidth && it.height == targetHeight }
            ?: Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
                .also { scaled?.recycle(); scaled = it }
        dstRect.set(0, 0, targetWidth, targetHeight)
        android.graphics.Canvas(s).drawBitmap(f, null, dstRect, paint)
        s
    } catch (t: Throwable) {
        null
    }
}

private fun ImageProxy.toBitmapCompat(): Bitmap? = try {
    toBitmap()
} catch (t: Throwable) {
    null
}
