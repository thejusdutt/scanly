package com.scanly.ui.review

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
    vm: ReviewViewModel = hiltViewModel(),
) {
    val doc by vm.document.collectAsState()
    val pages = remember(doc) { doc?.pages.orEmpty().sortedBy { it.orderIndex } }

    val pagerState = rememberPagerState(pageCount = { pages.size })
    val current = pages.getOrNull(pagerState.currentPage)
    var showFilters by remember { mutableStateOf(false) }

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
                if (showFilters && current != null) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
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
                // Adobe-style edit toolbar.
                NavigationBar {
                    ToolbarItem(Icons.Default.Palette, "Filter", enabled = current != null) {
                        showFilters = !showFilters
                    }
                    ToolbarItem(Icons.Default.Crop, "Crop",
                        enabled = current?.originalPath != null) {
                        current?.let { onAdjustCrop(it.id) }
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
                    AsyncImage(
                        model = pages[index].imagePath,
                        contentDescription = "Page ${index + 1}",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

@Composable
private fun RowScope.ToolbarItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    NavigationBarItem(
        selected = false,
        enabled = enabled,
        onClick = onClick,
        icon = { Icon(icon, label) },
        label = { Text(label) },
    )
}

@Composable
private fun FilterChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
}
