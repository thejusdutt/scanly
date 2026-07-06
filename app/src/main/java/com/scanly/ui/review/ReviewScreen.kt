package com.scanly.ui.review

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.scanly.R
import com.scanly.common.Filter

/**
 * Adobe-style review: one page at a time in a pager, a thumbnail strip, and a bottom
 * edit toolbar (filter / rotate / crop / reorder / delete / add pages) acting on the
 * page in view.
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

    val pagerState = rememberPagerState(pageCount = { pages.size })
    val current = pages.getOrNull(pagerState.currentPage)
    var showFilters by remember { mutableStateOf(false) }
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
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        FilterChoice(stringResource(R.string.filter_color),
                            current.filter == Filter.COLOR) { vm.setFilter(current.id, Filter.COLOR) }
                        FilterChoice(stringResource(R.string.filter_grayscale),
                            current.filter == Filter.GREYSCALE) { vm.setFilter(current.id, Filter.GREYSCALE) }
                        FilterChoice(stringResource(R.string.filter_bw),
                            current.filter == Filter.BW) { vm.setFilter(current.id, Filter.BW) }
                        FilterChoice(stringResource(R.string.filter_magic),
                            current.filter == Filter.MAGIC) { vm.setFilter(current.id, Filter.MAGIC) }
                        FilterChoice(stringResource(R.string.filter_whiteboard),
                            current.filter == Filter.WHITEBOARD) { vm.setFilter(current.id, Filter.WHITEBOARD) }
                    }
                }
                // Thumbnail strip for quick navigation and visible ordering.
                if (pages.size > 1) {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    ) {
                        items(pages, key = { it.id }) { page ->
                            val selected = page.id == current?.id
                            AsyncImage(
                                model = page.imagePath,
                                contentDescription = "Page ${page.orderIndex + 1}",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(44.dp, 58.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .border(
                                        if (selected) 2.dp else 1.dp,
                                        if (selected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.outlineVariant,
                                        RoundedCornerShape(6.dp),
                                    )
                                    .clickable {
                                        val i = pages.indexOfFirst { it.id == page.id }
                                        if (i >= 0) vm.requestScroll(i)
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
                    ToolbarItem(Icons.Default.Palette, "Filter", enabled = current != null) {
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
                    ToolbarItem(Icons.Default.SwapHoriz, "Move",
                        enabled = current != null && pages.size > 1) {
                        current?.let {
                            vm.movePage(it.id, up = pagerState.currentPage == pages.lastIndex)
                        }
                    }
                    ToolbarItem(Icons.Default.AddAPhoto, "Add", enabled = true) {
                        onAddMorePages()
                    }
                    ToolbarItem(Icons.Default.Delete, "Delete", enabled = current != null) {
                        current?.let { vm.deletePage(it.id) }
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
            }
        }
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

@Composable
private fun FilterChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
}
