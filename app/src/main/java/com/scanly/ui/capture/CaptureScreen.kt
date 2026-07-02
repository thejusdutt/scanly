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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Grid3x3
import androidx.compose.material.icons.filled.MotionPhotosAuto
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
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

        // Live boundary overlay, mapped with the same FILL_CENTER geometry PreviewView
        // uses, so the outline actually sits on the document edges.
        QuadOverlay(
            quad = ui.liveQuad,
            frameWidth = ui.frameWidth,
            frameHeight = ui.frameHeight,
            stable = ui.state == CaptureState.STABLE,
        )
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
                IconButton(onClick = vm::toggleAuto, enabled = !ui.idCardMode) {
                    Icon(
                        Icons.Default.MotionPhotosAuto, stringResource(R.string.auto_capture),
                        tint = if (ui.autoCapture && !ui.idCardMode) Color(0xFFA7E8BD)
                        else Color.White.copy(alpha = if (ui.idCardMode) 0.4f else 1f),
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
            // Camera-app style mode selector: the active mode sits highlighted.
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ModeLabel("DOCUMENT", selected = !ui.idCardMode) {
                    if (ui.idCardMode) vm.toggleIdCard()
                }
                ModeLabel("ID CARD", selected = ui.idCardMode) {
                    if (!ui.idCardMode) vm.toggleIdCard()
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 36.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Adobe-style capture stack: last page thumbnail + count badge;
                // tapping it finishes the scan and opens review.
                Box(Modifier.size(52.dp)) {
                    if (ui.lastPageThumb != null) {
                        AsyncImage(
                            model = ui.lastPageThumb,
                            contentDescription = "Captured pages — review",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(8.dp))
                                .border(2.dp, Color.White, RoundedCornerShape(8.dp))
                                .clickable(onClick = vm::finish),
                        )
                        Box(
                            Modifier
                                .align(Alignment.TopEnd)
                                .offset(x = 6.dp, y = (-6).dp)
                                .size(22.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "${ui.pageCount}",
                                color = Color.White,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
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
        ui.noDocumentHint -> "No document found — capture manually"
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
private fun ModeLabel(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (selected) Color.White.copy(alpha = 0.22f) else Color.Transparent,
        contentColor = if (selected) Color(0xFFFFD54F) else Color.White,
        shape = CircleShape,
        modifier = Modifier.clip(CircleShape).clickable(onClick = onClick),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
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
private fun QuadOverlay(
    quad: DocumentQuad?,
    frameWidth: Int,
    frameHeight: Int,
    stable: Boolean,
) {
    if (quad == null || frameWidth <= 0 || frameHeight <= 0) return
    val color = if (stable) Color(0xFF4CAF50) else Color(0xFFFFC107)
    Canvas(Modifier.fillMaxSize()) {
        // PreviewView default is FILL_CENTER: uniform scale to cover, center-cropped.
        // Map the analyzer-frame quad through the same transform.
        val scale = maxOf(size.width / frameWidth, size.height / frameHeight)
        val offX = (size.width - frameWidth * scale) / 2f
        val offY = (size.height - frameHeight * scale) / 2f
        fun pt(p: com.scanly.platform.QuadPoint) = Offset(p.x * scale + offX, p.y * scale + offY)

        val pts = quad.corners.map { pt(it) }
        val path = Path().apply {
            moveTo(pts[0].x, pts[0].y)
            pts.drop(1).forEach { lineTo(it.x, it.y) }
            close()
        }
        drawPath(
            path, color, alpha = 0.85f,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 5f),
        )
        // Adobe-style corner dots.
        pts.forEach { p ->
            drawCircle(Color.White, radius = 16f, center = p)
            drawCircle(color, radius = 11f, center = p)
        }
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
