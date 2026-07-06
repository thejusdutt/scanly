package com.scanly.ui.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
import com.scanly.data.db.DocumentWithPages
import com.scanly.data.repo.DocumentRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PageViewerViewModel @Inject constructor(
    private val repository: DocumentRepository,
    savedState: SavedStateHandle,
) : ViewModel() {

    private val documentId: Long = savedState.get<Long>("documentId") ?: -1L
    val initialIndex: Int = savedState.get<Int>("index") ?: 0

    val document: StateFlow<DocumentWithPages?> =
        repository.observeDocument(documentId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun rotate(pageId: Long) = viewModelScope.launch { repository.rotatePage(pageId) }
    fun delete(pageId: Long) = viewModelScope.launch { repository.deletePage(pageId) }
}

/**
 * Full-screen page viewer: swipe between pages, pinch to zoom, double-tap to toggle
 * zoom. The viewer competitors have — here without a "Premium" ribbon over it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageViewerScreen(
    onBack: () -> Unit,
    onAdjustCrop: (Long) -> Unit,
    vm: PageViewerViewModel = hiltViewModel(),
) {
    val doc by vm.document.collectAsState()
    val pages = remember(doc) { doc?.pages.orEmpty().sortedBy { it.orderIndex } }

    if (pages.isEmpty()) {
        // Last page deleted (or still loading) — nothing to show.
        LaunchedEffect(doc) { if (doc != null) onBack() }
        return
    }

    val pagerState = rememberPagerState(
        initialPage = vm.initialIndex.coerceIn(0, pages.lastIndex),
        pageCount = { pages.size },
    )
    val current = pages.getOrNull(pagerState.currentPage)
    var confirmDelete by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black.copy(alpha = 0.6f),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                    actionIconContentColor = Color.White,
                ),
                title = { Text("${pagerState.currentPage + 1} / ${pages.size}") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                actions = {
                    IconButton(onClick = { current?.let { vm.rotate(it.id) } }) {
                        Icon(Icons.Default.RotateRight, "Rotate 90°")
                    }
                    IconButton(
                        onClick = { current?.let { onAdjustCrop(it.id) } },
                        enabled = current?.originalPath != null,
                    ) {
                        Icon(Icons.Default.Crop, "Adjust crop")
                    }
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Default.Delete, "Delete page")
                    }
                },
            )
        },
    ) { padding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.padding(padding).fillMaxSize().background(Color.Black),
            key = { i -> pages.getOrNull(i)?.id ?: i },
        ) { index ->
            ZoomablePage(imagePath = pages[index].imagePath)
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this page?") },
            text = { Text("The page is removed from this device. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    current?.let { vm.delete(it.id) }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun ZoomablePage(imagePath: String) {
    var scale by remember(imagePath) { mutableStateOf(1f) }
    var offset by remember(imagePath) { mutableStateOf(Offset.Zero) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }

    // Keep the zoomed content on screen: with center-origin scaling each edge can move
    // out by at most half the overflow, so the pan is clamped to that box.
    fun clampOffset(o: Offset, s: Float): Offset {
        if (s <= 1f || boxSize == IntSize.Zero) return Offset.Zero
        val maxX = boxSize.width * (s - 1f) / 2f
        val maxY = boxSize.height * (s - 1f) / 2f
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { boxSize = it }
            .pointerInput(imagePath) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val newScale = (scale * zoom).coerceIn(1f, 6f)
                    // Zoom about the pinch centroid: the content under the fingers
                    // stays under the fingers instead of sliding toward the center.
                    val center = Offset(boxSize.width / 2f, boxSize.height / 2f)
                    val d = centroid - center
                    val newOffset = d - (d - offset) * (newScale / scale) + pan
                    scale = newScale
                    offset = clampOffset(newOffset, newScale)
                }
            }
            .pointerInput(imagePath) {
                detectTapGestures(
                    onDoubleTap = { tap ->
                        if (scale > 1f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            // Zoom toward the tapped spot, not the page center.
                            val newScale = 2.5f
                            val center = Offset(boxSize.width / 2f, boxSize.height / 2f)
                            scale = newScale
                            offset = clampOffset((center - tap) * newScale, newScale)
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = imagePath,
            contentDescription = "Page",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
        )
    }
}
