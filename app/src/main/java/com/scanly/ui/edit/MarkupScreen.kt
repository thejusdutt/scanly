package com.scanly.ui.edit

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
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
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.hypot
import javax.inject.Inject

/** Annotation items in image-pixel coordinates, baked onto the page on Apply. */
sealed class MarkupItem {
    data class PenStroke(
        val points: List<Offset>,
        val width: Float,
        val color: Int,
        val highlighter: Boolean,
    ) : MarkupItem()

    data class Label(
        val text: String,
        val x: Float,
        val y: Float,
        val size: Float,
        val color: Int,
    ) : MarkupItem()
}

enum class MarkupTool { PEN, HIGHLIGHT, TEXT }

data class MarkupPageState(val imagePath: String, val imageWidth: Int, val imageHeight: Int)

@HiltViewModel
class MarkupViewModel @Inject constructor(
    private val repository: DocumentRepository,
    savedState: SavedStateHandle,
) : ViewModel() {

    private val pageId: Long = savedState.get<Long>("pageId") ?: -1L

    private val _state = MutableStateFlow<MarkupPageState?>(null)
    val state = _state.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val page = repository.getPage(pageId) ?: return@launch
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(page.imagePath, bounds)
            if (bounds.outWidth > 0) {
                _state.value = MarkupPageState(page.imagePath, bounds.outWidth, bounds.outHeight)
            }
        }
    }

    fun apply(items: List<MarkupItem>, onDone: () -> Unit) {
        if (items.isEmpty()) { onDone(); return }
        viewModelScope.launch {
            _busy.value = true
            repository.updateProcessedImage(pageId) { src ->
                val out = src.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
                drawItems(android.graphics.Canvas(out), items)
                out
            }
            _busy.value = false
            onDone()
        }
    }

    companion object {
        /** Shared by bake and (via nativeCanvas) preview, so they render identically. */
        fun drawItems(canvas: android.graphics.Canvas, items: List<MarkupItem>) {
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            for (item in items) {
                when (item) {
                    is MarkupItem.PenStroke -> {
                        paint.reset()
                        paint.isAntiAlias = true
                        paint.style = android.graphics.Paint.Style.STROKE
                        paint.strokeCap = android.graphics.Paint.Cap.ROUND
                        paint.strokeJoin = android.graphics.Paint.Join.ROUND
                        paint.strokeWidth = item.width
                        paint.color = item.color
                        paint.alpha = if (item.highlighter) 90 else 255
                        val path = android.graphics.Path()
                        item.points.forEachIndexed { i, p ->
                            if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                        }
                        canvas.drawPath(path, paint)
                    }
                    is MarkupItem.Label -> {
                        paint.reset()
                        paint.isAntiAlias = true
                        paint.style = android.graphics.Paint.Style.FILL
                        paint.textSize = item.size
                        paint.color = item.color
                        paint.isFakeBoldText = true
                        canvas.drawText(item.text, item.x, item.y, paint)
                    }
                }
            }
        }
    }
}

/** Markup: pen, highlighter and text labels drawn over the page, baked on Apply. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarkupScreen(
    onDone: () -> Unit,
    vm: MarkupViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsState()
    val busy by vm.busy.collectAsState()

    var items by remember { mutableStateOf(listOf<MarkupItem>()) }
    var redo by remember { mutableStateOf(listOf<MarkupItem>()) }
    var tool by remember { mutableStateOf(MarkupTool.PEN) }
    var colorArgb by remember { mutableStateOf(0xFFE53935.toInt()) }
    var penWidthDp by remember { mutableStateOf(4f) }
    var showTextDialog by remember { mutableStateOf(false) }
    var draggingLabel by remember { mutableStateOf(-1) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Markup") },
                navigationIcon = {
                    IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                actions = {
                    IconButton(
                        onClick = {
                            items.lastOrNull()?.let { redo = redo + it }
                            items = items.dropLast(1)
                        },
                        enabled = items.isNotEmpty() && !busy,
                    ) { Icon(Icons.AutoMirrored.Filled.Undo, "Undo") }
                    IconButton(
                        onClick = {
                            redo.lastOrNull()?.let { items = items + it }
                            redo = redo.dropLast(1)
                        },
                        enabled = redo.isNotEmpty() && !busy,
                    ) { Icon(Icons.AutoMirrored.Filled.Redo, "Redo") }
                    if (busy) CircularProgressIndicator(Modifier.size(22.dp))
                    IconButton(
                        onClick = { vm.apply(items, onDone) },
                        enabled = state != null && !busy && items.isNotEmpty(),
                    ) { Icon(Icons.Default.Check, "Apply markup") }
                },
            )
        },
        bottomBar = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = tool == MarkupTool.PEN,
                        onClick = { tool = MarkupTool.PEN }, label = { Text("Pen") })
                    FilterChip(selected = tool == MarkupTool.HIGHLIGHT,
                        onClick = { tool = MarkupTool.HIGHLIGHT }, label = { Text("Highlight") })
                    FilterChip(selected = tool == MarkupTool.TEXT,
                        onClick = { tool = MarkupTool.TEXT }, label = { Text("Text") })
                    if (tool == MarkupTool.TEXT) {
                        TextButton(onClick = { showTextDialog = true }) { Text("Add text") }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    listOf(
                        0xFF000000, 0xFFE53935, 0xFF1E88E5, 0xFF43A047, 0xFFFDD835,
                    ).forEach { c ->
                        val ci = c.toInt()
                        Box(
                            Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(Color(c))
                                .border(
                                    if (colorArgb == ci) 3.dp else 1.dp,
                                    if (colorArgb == ci) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outlineVariant,
                                    CircleShape,
                                )
                                .clickable { colorArgb = ci },
                        )
                    }
                    Spacer(Modifier.weight(1f))
                }
                if (tool != MarkupTool.TEXT) {
                    Text("Stroke width", style = MaterialTheme.typography.labelMedium)
                    Slider(value = penWidthDp, onValueChange = { penWidthDp = it }, valueRange = 2f..24f)
                }
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
        val scale = if (canvasSize == IntSize.Zero) 1f
        else minOf(
            canvasSize.width / s.imageWidth.toFloat(),
            canvasSize.height / s.imageHeight.toFloat(),
        )
        val offsetX = (canvasSize.width - s.imageWidth * scale) / 2f
        val offsetY = (canvasSize.height - s.imageHeight * scale) / 2f
        val density = androidx.compose.ui.platform.LocalDensity.current

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
                    .pointerInput(s.imagePath, canvasSize, tool, colorArgb, penWidthDp) {
                        val grabRadius = 56.dp.toPx()
                        detectDragGestures(
                            onDragStart = { pos ->
                                if (tool == MarkupTool.TEXT) {
                                    // Grab the nearest label to move it.
                                    draggingLabel = items.withIndex()
                                        .filter { it.value is MarkupItem.Label }
                                        .map { (i, item) ->
                                            val l = item as MarkupItem.Label
                                            i to hypot(
                                                l.x * scale + offsetX - pos.x,
                                                l.y * scale + offsetY - pos.y,
                                            )
                                        }
                                        .filter { it.second < grabRadius }
                                        .minByOrNull { it.second }?.first ?: -1
                                } else {
                                    redo = emptyList()
                                    val p = Offset((pos.x - offsetX) / scale, (pos.y - offsetY) / scale)
                                    val widthImg = with(density) { penWidthDp.dp.toPx() } / scale *
                                        (if (tool == MarkupTool.HIGHLIGHT) 3.5f else 1f)
                                    items = items + MarkupItem.PenStroke(
                                        points = listOf(p),
                                        width = widthImg,
                                        color = colorArgb,
                                        highlighter = tool == MarkupTool.HIGHLIGHT,
                                    )
                                }
                            },
                            onDrag = { change, dragAmount ->
                                if (tool == MarkupTool.TEXT) {
                                    val i = draggingLabel
                                    if (i in items.indices) {
                                        val l = items[i] as MarkupItem.Label
                                        items = items.toMutableList().also {
                                            it[i] = l.copy(
                                                x = l.x + dragAmount.x / scale,
                                                y = l.y + dragAmount.y / scale,
                                            )
                                        }
                                    }
                                } else {
                                    val p = Offset(
                                        (change.position.x - offsetX) / scale,
                                        (change.position.y - offsetY) / scale,
                                    )
                                    val last = items.lastOrNull() as? MarkupItem.PenStroke
                                        ?: return@detectDragGestures
                                    items = items.dropLast(1) + last.copy(points = last.points + p)
                                }
                            },
                            onDragEnd = { draggingLabel = -1 },
                        )
                    },
            ) {
                // Preview: strokes via Compose, labels via the SAME android Paint used
                // at bake time (nativeCanvas), so what you see is what you get.
                for (item in items) {
                    when (item) {
                        is MarkupItem.PenStroke -> {
                            val pts = item.points.map {
                                Offset(it.x * scale + offsetX, it.y * scale + offsetY)
                            }
                            if (pts.isEmpty()) continue
                            val col = Color(item.color).copy(alpha = if (item.highlighter) 0.35f else 1f)
                            if (pts.size == 1) {
                                drawCircle(col, radius = item.width * scale / 2f, center = pts[0])
                            } else {
                                val path = Path().apply {
                                    moveTo(pts[0].x, pts[0].y)
                                    pts.drop(1).forEach { lineTo(it.x, it.y) }
                                }
                                drawPath(
                                    path, col,
                                    style = Stroke(
                                        width = item.width * scale,
                                        cap = StrokeCap.Round,
                                        join = StrokeJoin.Round,
                                    ),
                                )
                            }
                        }
                        is MarkupItem.Label -> {
                            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                                textSize = item.size * scale
                                color = item.color
                                isFakeBoldText = true
                            }
                            drawContext.canvas.nativeCanvas.drawText(
                                item.text,
                                item.x * scale + offsetX,
                                item.y * scale + offsetY,
                                paint,
                            )
                        }
                    }
                }
            }
        }

        if (showTextDialog) {
            var text by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { showTextDialog = false },
                title = { Text("Add text") },
                text = {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
                confirmButton = {
                    TextButton(
                        enabled = text.isNotBlank(),
                        onClick = {
                            redo = emptyList()
                            items = items + MarkupItem.Label(
                                text = text.trim(),
                                x = s.imageWidth * 0.2f,
                                y = s.imageHeight * 0.5f,
                                size = s.imageWidth * 0.05f,
                                color = colorArgb,
                            )
                            showTextDialog = false
                        },
                    ) { Text("Add") }
                },
                dismissButton = {
                    TextButton(onClick = { showTextDialog = false }) { Text("Cancel") }
                },
            )
        }
    }
}
