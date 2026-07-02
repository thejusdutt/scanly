package com.scanly.ui.crop

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
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
import com.scanly.data.repo.DocumentRepository
import com.scanly.platform.DocumentDetector
import com.scanly.platform.DocumentQuad
import com.scanly.platform.QuadPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.hypot

/** The original capture plus the quad being edited, all in image-pixel coordinates. */
data class CropState(
    val originalPath: String,
    val imageWidth: Int,
    val imageHeight: Int,
    val quad: DocumentQuad,
)

@HiltViewModel
class CropViewModel @Inject constructor(
    private val repository: DocumentRepository,
    private val detector: DocumentDetector,
    savedState: SavedStateHandle,
) : ViewModel() {

    private val pageId: Long = savedState.get<Long>("pageId") ?: -1L

    private val _state = MutableStateFlow<CropState?>(null)
    val state = _state.asStateFlow()

    private val _saving = MutableStateFlow(false)
    val saving = _saving.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.Default) {
            val page = repository.getPage(pageId) ?: return@launch
            val path = page.originalPath ?: return@launch
            val bmp = BitmapFactory.decodeFile(path) ?: return@launch
            val quad = detector.detect(bmp) ?: DocumentQuad.full(bmp.width, bmp.height)
            _state.value = CropState(path, bmp.width, bmp.height, quad)
            bmp.recycle()
        }
    }

    fun moveCorner(index: Int, x: Float, y: Float) {
        val s = _state.value ?: return
        val p = QuadPoint(
            x.coerceIn(0f, s.imageWidth.toFloat()),
            y.coerceIn(0f, s.imageHeight.toFloat()),
        )
        val q = s.quad
        _state.value = s.copy(
            quad = when (index) {
                0 -> q.copy(topLeft = p)
                1 -> q.copy(topRight = p)
                2 -> q.copy(bottomRight = p)
                else -> q.copy(bottomLeft = p)
            },
        )
    }

    fun save(onDone: () -> Unit) {
        val s = _state.value ?: return
        viewModelScope.launch {
            _saving.value = true
            repository.recrop(pageId, s.quad)
            _saving.value = false
            onDone()
        }
    }
}

/**
 * Manual corner adjustment: the original capture with four draggable handles. Fixes the
 * "auto-detection got it wrong and I can't do anything about it" pain point.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CropScreen(
    onDone: () -> Unit,
    vm: CropViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsState()
    val saving by vm.saving.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Adjust crop") },
                navigationIcon = {
                    IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                actions = {
                    if (saving) CircularProgressIndicator(Modifier.size(22.dp))
                    IconButton(onClick = { vm.save(onDone) }, enabled = state != null && !saving) {
                        Icon(Icons.Default.Check, "Apply crop")
                    }
                },
            )
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
        fun toCanvas(p: QuadPoint) = Offset(p.x * scale + offsetX, p.y * scale + offsetY)
        fun toImageX(cx: Float) = (cx - offsetX) / scale
        fun toImageY(cy: Float) = (cy - offsetY) / scale

        var dragIndex by remember { mutableStateOf(-1) }

        Box(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .onSizeChanged { canvasSize = it },
        ) {
            AsyncImage(
                model = s.originalPath,
                contentDescription = "Original capture",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
            Canvas(
                Modifier
                    .fillMaxSize()
                    // canvasSize is a key: the gesture block captures the letterbox
                    // mapping, which is only valid for the size it was computed at.
                    .pointerInput(s.imageWidth, s.imageHeight, canvasSize) {
                        val grabRadius = 64.dp.toPx()
                        detectDragGestures(
                            onDragStart = { pos ->
                                val corners = vmQuad(vm)?.corners.orEmpty()
                                dragIndex = corners
                                    .mapIndexed { i, p ->
                                        i to hypot(toCanvas(p).x - pos.x, toCanvas(p).y - pos.y)
                                    }
                                    .filter { it.second < grabRadius }
                                    .minByOrNull { it.second }?.first ?: -1
                            },
                            onDrag = { change, _ ->
                                if (dragIndex >= 0) {
                                    vm.moveCorner(
                                        dragIndex,
                                        toImageX(change.position.x),
                                        toImageY(change.position.y),
                                    )
                                }
                            },
                            onDragEnd = { dragIndex = -1 },
                        )
                    },
            ) {
                val quad = s.quad
                val pts = quad.corners.map { toCanvas(it) }
                val path = Path().apply {
                    moveTo(pts[0].x, pts[0].y)
                    pts.drop(1).forEach { lineTo(it.x, it.y) }
                    close()
                }
                drawPath(path, Color(0xFF4CAF50), style = Stroke(width = 5f))
                pts.forEachIndexed { i, p ->
                    drawCircle(Color.White, radius = 26f, center = p)
                    drawCircle(
                        if (i == dragIndex) Color(0xFF1E6F5C) else Color(0xFF4CAF50),
                        radius = 20f, center = p,
                    )
                }
            }
        }
    }
}

// Reads the freshest quad during a gesture (the pointerInput lambda captures stale state).
private fun vmQuad(vm: CropViewModel): DocumentQuad? = vm.state.value?.quad
