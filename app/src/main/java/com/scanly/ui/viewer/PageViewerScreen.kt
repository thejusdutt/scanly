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
                    IconButton(onClick = { current?.let { vm.delete(it.id) } }) {
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
}

@Composable
private fun ZoomablePage(imagePath: String) {
    var scale by remember(imagePath) { mutableStateOf(1f) }
    var offset by remember(imagePath) { mutableStateOf(Offset.Zero) }

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(imagePath) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 6f)
                    offset = if (scale > 1f) offset + pan else Offset.Zero
                }
            }
            .pointerInput(imagePath) {
                detectTapGestures(
                    onDoubleTap = {
                        scale = if (scale > 1f) 1f else 2.5f
                        offset = Offset.Zero
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
