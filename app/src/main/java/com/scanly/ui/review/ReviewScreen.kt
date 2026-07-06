package com.scanly.ui.review

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.scanly.R
import com.scanly.common.Filter
import com.scanly.ui.common.rememberHaptics

/**
 * Adobe-style review: one page at a time in a pager, a thumbnail strip (tap to jump,
 * long-press and drag to reorder), and a bottom edit toolbar (filter / rotate / crop /
 * delete / add pages) acting on the page in view.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(
    documentId: Long,
    onDone: () -> Unit,
    onAddMorePages: () -> Unit,
    onAdjustCrop: (Long) -> Unit,
    onRetake: (Long) -> Unit,
    onCleanup: (Long) -> Unit,
    onMarkup: (Long) -> Unit,
    vm: ReviewViewModel = hiltViewModel(),
) {
    val doc by vm.document.collectAsState()
    val pages = remember(doc) { doc?.pages.orEmpty().sortedBy { it.orderIndex } }
    val haptics = rememberHaptics()

    val pagerState = rememberPagerState(pageCount = { pages.size })
    val current = pages.getOrNull(pagerState.currentPage)
    var showFilters by remember { mutableStateOf(false) }
    var confirmDeletePage by remember { mutableStateOf(false) }
    // Live-preview edit panels: (brightness, contrast) and straighten degrees. While a
    // panel is open the pager shows the SAME transform that Apply will bake.
    var adjust by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    var adjustAll by remember { mutableStateOf(false) }
    var straighten by remember { mutableStateOf<Float?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (pages.isEmpty()) stringResource(R.string.title_review)
                        else "${stringResource(R.string.title_review)}  ·  " +
                            "${pagerState.currentPage + 1}/${pages.size}",
                    )
                },
                actions = {
                    Button(
                        onClick = onDone,
                        enabled = pages.isNotEmpty(),
                        modifier = Modifier.padding(end = 12.dp),
                    ) { Text("Save") }
                },
            )
        },
        bottomBar = {
            Column {
                val adj = adjust
                val str = straighten
                if (adj != null && current != null) {
                    AdjustPanel(
                        brightness = adj.first,
                        contrast = adj.second,
                        applyAll = adjustAll,
                        onChange = { b, c -> adjust = b to c },
                        onApplyAllChange = { adjustAll = it },
                        onCancel = { adjust = null },
                        onApply = {
                            if (adjustAll) vm.adjustAllPages(adj.first, adj.second)
                            else vm.adjustPage(current.id, adj.first, adj.second)
                            adjust = null
                        },
                    )
                } else if (str != null && current != null) {
                    StraightenPanel(
                        degrees = str,
                        onChange = { straighten = it },
                        onCancel = { straighten = null },
                        onApply = {
                            if (kotlin.math.abs(str) > 0.05f) vm.straightenPage(current.id, str)
                            straighten = null
                        },
                    )
                } else {
                if (showFilters && current != null) {
                    // Per-filter previews of the actual page, built off the UI thread.
                    val previews by produceState<Map<Filter, Bitmap>?>(
                        initialValue = null,
                        current.id, current.originalPath, current.cropQuad,
                    ) {
                        value = vm.filterPreviews(current)
                    }
                    FilterPreviewRow(
                        selected = current.filter,
                        previews = previews,
                        onSelect = { filter ->
                            haptics.tick()
                            vm.setFilter(current.id, filter)
                        },
                    )
                }
                // Thumbnail strip: tap to jump, long-press + drag to reorder.
                if (pages.size > 1) {
                    val latestPages by rememberUpdatedState(pages)
                    val cellPx = with(LocalDensity.current) { 52.dp.toPx() } // 44 + spacing
                    var dragPageId by remember { mutableStateOf<Long?>(null) }
                    var dragOffsetX by remember { mutableStateOf(0f) }
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    ) {
                        items(pages, key = { it.id }) { page ->
                            val selected = page.id == current?.id
                            val dragging = dragPageId == page.id
                            AsyncImage(
                                model = page.imagePath,
                                contentDescription = "Page ${page.orderIndex + 1}",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .then(
                                        if (dragging) Modifier.zIndex(1f)
                                        else Modifier.animateItem(),
                                    )
                                    .graphicsLayer {
                                        if (dragging) {
                                            translationX = dragOffsetX
                                            scaleX = 1.12f
                                            scaleY = 1.12f
                                        }
                                    }
                                    .size(44.dp, 58.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .border(
                                        if (selected || dragging) 2.dp else 1.dp,
                                        if (selected || dragging) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.outlineVariant
                                        },
                                        RoundedCornerShape(6.dp),
                                    )
                                    .pointerInput(page.id) {
                                        detectTapGestures {
                                            val i = latestPages.indexOfFirst { it.id == page.id }
                                            if (i >= 0) vm.requestScroll(i)
                                        }
                                    }
                                    .pointerInput(page.id) {
                                        detectDragGesturesAfterLongPress(
                                            onDragStart = {
                                                haptics.longPress()
                                                dragPageId = page.id
                                                dragOffsetX = 0f
                                            },
                                            onDrag = { change, amount ->
                                                change.consume()
                                                dragOffsetX += amount.x
                                                // Live neighbor swap once the thumb is
                                                // carried most of a cell width.
                                                if (dragOffsetX > cellPx * 0.75f) {
                                                    haptics.tick()
                                                    vm.movePage(page.id, up = false)
                                                    dragOffsetX -= cellPx
                                                } else if (dragOffsetX < -cellPx * 0.75f) {
                                                    haptics.tick()
                                                    vm.movePage(page.id, up = true)
                                                    dragOffsetX += cellPx
                                                }
                                            },
                                            onDragEnd = { dragPageId = null; dragOffsetX = 0f },
                                            onDragCancel = { dragPageId = null; dragOffsetX = 0f },
                                        )
                                    },
                            )
                        }
                    }
                }
                // Adobe-style edit toolbar; scrollable so tools can grow without cramping.
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .horizontalScroll(rememberScrollState())
                        .navigationBarsPadding()
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    ToolbarItem(Icons.Default.Palette, "Filter",
                        enabled = current?.originalPath != null) {
                        showFilters = !showFilters
                    }
                    ToolbarItem(Icons.Default.Crop, "Crop",
                        enabled = current?.originalPath != null) {
                        current?.let { onAdjustCrop(it.id) }
                    }
                    ToolbarItem(Icons.Default.Replay, stringResource(R.string.retake),
                        enabled = current != null) {
                        current?.let { onRetake(it.id) }
                    }
                    ToolbarItem(Icons.Default.Tune, "Adjust", enabled = current != null) {
                        adjustAll = false
                        adjust = 0f to 1f
                    }
                    ToolbarItem(Icons.Default.AutoFixHigh, "Cleanup", enabled = current != null) {
                        current?.let { onCleanup(it.id) }
                    }
                    ToolbarItem(Icons.Default.Brush, "Markup", enabled = current != null) {
                        current?.let { onMarkup(it.id) }
                    }
                    ToolbarItem(Icons.Default.Straighten, "Straighten", enabled = current != null) {
                        straighten = 0f
                    }
                    ToolbarItem(Icons.Default.RotateRight, "Rotate", enabled = current != null) {
                        current?.let { vm.rotatePage(it.id) }
                    }
                    ToolbarItem(Icons.Default.AddAPhoto, "Add", enabled = true) {
                        onAddMorePages()
                    }
                    ToolbarItem(Icons.Default.Delete, "Delete", enabled = current != null) {
                        confirmDeletePage = true
                    }
                }
                } // end: no edit panel open
            }
        },
    ) { padding ->
        // Sync external scroll requests (thumbnail taps) into the pager.
        val scrollTo by vm.scrollTo.collectAsState()
        LaunchedEffect(scrollTo) {
            scrollTo?.let {
                pagerState.animateScrollToPage(it.coerceIn(0, (pages.size - 1).coerceAtLeast(0)))
                vm.consumeScroll()
            }
        }

        if (pages.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "No pages yet.\nTap Add to capture.",
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceContainer),
            key = { i -> pages.getOrNull(i)?.id ?: i },
        ) { index ->
            Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
                Surface(
                    shadowElevation = 6.dp,
                    shape = RoundedCornerShape(4.dp),
                ) {
                    // Live edit preview on the page in view: the identical math that
                    // Apply bakes (see ImageProcessing.adjust / rotateFine).
                    val isCurrent = index == pagerState.currentPage
                    val previewFilter = if (isCurrent) {
                        adjust?.let { (b, c) ->
                            androidx.compose.ui.graphics.ColorFilter.colorMatrix(adjustMatrix(b, c))
                        }
                    } else null
                    val previewRotation = if (isCurrent) straighten ?: 0f else 0f
                    AsyncImage(
                        model = pages[index].imagePath,
                        contentDescription = "Page ${index + 1}",
                        contentScale = ContentScale.Fit,
                        colorFilter = previewFilter,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { rotationZ = previewRotation },
                    )
                }
                // Alignment grid while straightening, like a camera level.
                if (index == pagerState.currentPage && straighten != null) {
                    StraightenGrid(Modifier.matchParentSize())
                }
            }
        }
    }

    if (confirmDeletePage) {
        AlertDialog(
            onDismissRequest = { confirmDeletePage = false },
            title = { Text("Delete this page?") },
            text = { Text("The page is removed from this device. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDeletePage = false
                    current?.let { vm.deletePage(it.id) }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeletePage = false }) { Text("Cancel") }
            },
        )
    }
}

/** Same transfer function as ImageProcessing.adjust: out = c·in + (b + 128(1−c)). */
private fun adjustMatrix(brightness: Float, contrast: Float): androidx.compose.ui.graphics.ColorMatrix {
    val t = brightness + 128f * (1f - contrast)
    return androidx.compose.ui.graphics.ColorMatrix(
        floatArrayOf(
            contrast, 0f, 0f, 0f, t,
            0f, contrast, 0f, 0f, t,
            0f, 0f, contrast, 0f, t,
            0f, 0f, 0f, 1f, 0f,
        ),
    )
}

@Composable
private fun filterLabel(filter: Filter): String = when (filter) {
    Filter.COLOR -> stringResource(R.string.filter_color)
    Filter.GREYSCALE -> stringResource(R.string.filter_grayscale)
    Filter.BW -> stringResource(R.string.filter_bw)
    Filter.MAGIC -> stringResource(R.string.filter_magic)
    Filter.WHITEBOARD -> stringResource(R.string.filter_whiteboard)
}

/**
 * Filter picker with real previews: the page itself (cropped, downscaled) rendered
 * through every filter, instead of five text chips the user has to try one by one.
 */
@Composable
private fun FilterPreviewRow(
    selected: Filter,
    previews: Map<Filter, Bitmap>?,
    onSelect: (Filter) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Filter.entries.forEach { filter ->
            val isSelected = selected == filter
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onSelect(filter) }
                    .padding(4.dp),
            ) {
                Box(
                    Modifier
                        .size(56.dp, 72.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .border(
                            if (isSelected) 2.dp else 1.dp,
                            if (isSelected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant,
                            RoundedCornerShape(8.dp),
                        ),
                ) {
                    val bmp = previews?.get(filter)
                    if (bmp != null) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    filterLabel(filter),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isSelected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Rule-of-thirds-style alignment grid shown while the straighten slider is open. */
@Composable
private fun StraightenGrid(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val cols = 6
        val rows = 8
        val line = Color.White.copy(alpha = 0.55f)
        val shadow = Color.Black.copy(alpha = 0.35f)
        for (i in 1 until cols) {
            val x = size.width * i / cols
            drawLine(shadow, Offset(x + 1f, 0f), Offset(x + 1f, size.height), 2f)
            drawLine(line, Offset(x, 0f), Offset(x, size.height), 2f)
        }
        for (j in 1 until rows) {
            val y = size.height * j / rows
            drawLine(shadow, Offset(0f, y + 1f), Offset(size.width, y + 1f), 2f)
            drawLine(line, Offset(0f, y), Offset(size.width, y), 2f)
        }
    }
}

@Composable
private fun AdjustPanel(
    brightness: Float,
    contrast: Float,
    applyAll: Boolean,
    onChange: (Float, Float) -> Unit,
    onApplyAllChange: (Boolean) -> Unit,
    onCancel: () -> Unit,
    onApply: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        Text("Brightness  ${brightness.toInt()}", style = MaterialTheme.typography.labelMedium)
        Slider(
            value = brightness,
            onValueChange = { onChange(it, contrast) },
            valueRange = -100f..100f,
        )
        Text("Contrast  ${"%.2f".format(contrast)}", style = MaterialTheme.typography.labelMedium)
        Slider(
            value = contrast,
            onValueChange = { onChange(brightness, it) },
            valueRange = 0.5f..1.8f,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = applyAll, onCheckedChange = onApplyAllChange)
            Text("All pages", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onCancel) { Text("Cancel") }
            Button(onClick = onApply) { Text("Apply") }
        }
    }
}

@Composable
private fun StraightenPanel(
    degrees: Float,
    onChange: (Float) -> Unit,
    onCancel: () -> Unit,
    onApply: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        Text(
            "Straighten  ${"%.1f".format(degrees)}°",
            style = MaterialTheme.typography.labelMedium,
        )
        Slider(value = degrees, onValueChange = onChange, valueRange = -10f..10f)
        Row {
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onCancel) { Text("Cancel") }
            Button(onClick = onApply) { Text("Apply") }
        }
    }
}

@Composable
private fun ToolbarItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val tint = if (enabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Icon(icon, label, tint = tint)
        Spacer(Modifier.height(2.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = tint)
    }
}
