package com.scanly.ui.edit

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.scanly.cv.ImageProcessing
import com.scanly.data.repo.DocumentRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** The page's processed image, as shown and painted on. Coordinates are image pixels. */
data class CleanupState(val imagePath: String, val imageWidth: Int, val imageHeight: Int)

/** One eraser stroke in image-pixel coordinates. */
data class CleanStroke(val points: List<Offset>, val radius: Float)

@HiltViewModel
class CleanupViewModel @Inject constructor(
    private val repository: DocumentRepository,
    savedState: SavedStateHandle,
) : ViewModel() {

    private val pageId: Long = savedState.get<Long>("pageId") ?: -1L

    private val _state = MutableStateFlow<CleanupState?>(null)
    val state = _state.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val page = repository.getPage(pageId) ?: return@launch
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(page.imagePath, bounds)
            if (bounds.outWidth > 0) {
                _state.value = CleanupState(page.imagePath, bounds.outWidth, bounds.outHeight)
            }
        }
    }

    /** Rasterize the strokes into a mask and inpaint the masked regions away. */
    fun apply(strokes: List<CleanStroke>, onDone: () -> Unit) {
        val s = _state.value ?: return
        if (strokes.isEmpty()) { onDone(); return }
        viewModelScope.launch {
            _busy.value = true
            withContext(Dispatchers.Default) {
                val mask = Bitmap.createBitmap(s.imageWidth, s.imageHeight, Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(mask)
                val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    color = android.graphics.Color.WHITE
                    style = android.graphics.Paint.Style.STROKE
                    strokeCap = android.graphics.Paint.Cap.ROUND
                    strokeJoin = android.graphics.Paint.Join.ROUND
                }
                for (stroke in strokes) {
                    paint.strokeWidth = stroke.radius * 2f
                    if (stroke.points.size == 1) {
                        val p = stroke.points.first()
                        canvas.drawCircle(p.x, p.y, stroke.radius, paint.apply { style = android.graphics.Paint.Style.FILL })
                        paint.style = android.graphics.Paint.Style.STROKE
                    } else {
                        val path = android.graphics.Path()
                        stroke.points.forEachIndexed { i, p ->
                            if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                        }
                        canvas.drawPath(path, paint)
                    }
                }
                repository.updateProcessedImage(pageId) { ImageProcessing.inpaint(it, mask) }
                mask.recycle()
            }
            _busy.value = false
            onDone()
        }
    }
}

/**
 * Cleanup eraser (Adobe's premium "Cleanup", free here): brush over fingers, stains or
 * handwriting; the region is inpainted from its surroundings on Apply.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CleanupScreen(
    onDone: () -> Unit,
    vm: CleanupViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsState()
    val busy by vm.busy.collectAsState()
    var strokes by remember { mutableStateOf(listOf<CleanStroke>()) }
    var brushDp by remember { mutableStateOf(18f) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Cleanup") },
                navigationIcon = {
                    IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                actions = {
                    IconButton(
                        onClick = { strokes = strokes.dropLast(1) },
                        enabled = strokes.isNotEmpty() && !busy,
                    ) { Icon(Icons.AutoMirrored.Filled.Undo, "Undo") }
                    if (busy) {
                        CircularProgressIndicator(Modifier.size(22.dp))
                    }
                    IconButton(
                        onClick = { vm.apply(strokes, onDone) },
                        enabled = state != null && !busy && strokes.isNotEmpty(),
                    ) { Icon(Icons.Default.Check, "Apply cleanup") }
                },
            )
        },
        bottomBar = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
            ) {
                Text("Brush size", style = MaterialTheme.typography.labelMedium)
                Slider(value = brushDp, onValueChange = { brushDp = it }, valueRange = 8f..48f)
                Text(
                    "Brush over what you want removed — fingers, stains, handwriting.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    ) { padding ->
        val s = state
        if (s == null) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        var canvasSize by remember { mutableStateOf(IntSize.Zero) }
        // ContentScale.Fit letterbox mapping between canvas and image pixels.
        val scale = if (canvasSize == IntSize.Zero) 1f
        else minOf(
            canvasSize.width / s.imageWidth.toFloat(),
            canvasSize.height / s.imageHeight.toFloat(),
        )
        val offsetX = (canvasSize.width - s.imageWidth * scale) / 2f
        val offsetY = (canvasSize.height - s.imageHeight * scale) / 2f
        val density = androidx.compose.ui.platform.LocalDensity.current
        val brushRadiusImage = with(density) { (brushDp / 2f).dp.toPx() } / scale

        Box(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .onSizeChanged { canvasSize = it },
        ) {
            AsyncImage(
                model = s.imagePath,
                contentDescription = "Page",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
            Canvas(
                Modifier
                    .fillMaxSize()
                    .pointerInput(s.imagePath, canvasSize, brushRadiusImage) {
                        detectDragGestures(
                            onDragStart = { pos ->
                                val p = Offset((pos.x - offsetX) / scale, (pos.y - offsetY) / scale)
                                strokes = strokes + CleanStroke(listOf(p), brushRadiusImage)
                            },
                            onDrag = { change, _ ->
                                val p = Offset(
                                    (change.position.x - offsetX) / scale,
                                    (change.position.y - offsetY) / scale,
                                )
                                val last = strokes.lastOrNull() ?: return@detectDragGestures
                                strokes = strokes.dropLast(1) +
                                    last.copy(points = last.points + p)
                            },
                        )
                    },
            ) {
                // Painted regions shown in translucent red, in canvas coordinates.
                for (stroke in strokes) {
                    val pts = stroke.points.map {
                        Offset(it.x * scale + offsetX, it.y * scale + offsetY)
                    }
                    val widthPx = stroke.radius * 2f * scale
                    if (pts.size == 1) {
                        drawCircle(Color(0xAAE53935), radius = widthPx / 2f, center = pts[0])
                    } else {
                        val path = Path().apply {
                            moveTo(pts[0].x, pts[0].y)
                            pts.drop(1).forEach { lineTo(it.x, it.y) }
                        }
                        drawPath(
                            path, Color(0xAAE53935),
                            style = Stroke(width = widthPx, cap = StrokeCap.Round, join = StrokeJoin.Round),
                        )
                    }
                }
            }
        }
    }
}
