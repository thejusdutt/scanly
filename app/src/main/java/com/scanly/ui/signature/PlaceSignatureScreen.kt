package com.scanly.ui.signature

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.scanly.data.db.DocumentWithPages
import com.scanly.data.files.DocumentStorage
import com.scanly.data.repo.DocumentRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PlaceSignatureViewModel @Inject constructor(
    private val repository: DocumentRepository,
    private val storage: DocumentStorage,
    savedState: SavedStateHandle,
) : ViewModel() {

    private val documentId: Long = savedState.get<Long>("documentId") ?: -1L

    val document: StateFlow<DocumentWithPages?> =
        repository.observeDocument(documentId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _signaturePath = MutableStateFlow(storage.latestSignature()?.absolutePath)
    val signaturePath = _signaturePath.asStateFlow()

    private val _stamping = MutableStateFlow(false)
    val stamping = _stamping.asStateFlow()

    /** Re-check after returning from the drawing screen. */
    fun refreshSignature() {
        _signaturePath.value = storage.latestSignature()?.absolutePath
    }

    fun stamp(
        pageId: Long,
        centerXFrac: Float,
        centerYFrac: Float,
        widthFrac: Float,
        onDone: () -> Unit,
    ) {
        val sig = _signaturePath.value ?: return
        viewModelScope.launch {
            _stamping.value = true
            repository.stampSignature(pageId, sig, centerXFrac, centerYFrac, widthFrac)
            _stamping.value = false
            onDone()
        }
    }
}

/**
 * Place a saved signature on a page: pick the page from the strip, drag the signature
 * into position, size it with the slider, then Stamp. The preview and the composite use
 * the same page-relative fractions, so what you see is what gets burned in.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaceSignatureScreen(
    onBack: () -> Unit,
    onDrawNew: () -> Unit,
    vm: PlaceSignatureViewModel = hiltViewModel(),
) {
    val doc by vm.document.collectAsState()
    val signaturePath by vm.signaturePath.collectAsState()
    val stamping by vm.stamping.collectAsState()

    // Pick up a signature drawn while this screen was on the back stack.
    LaunchedEffect(Unit) { vm.refreshSignature() }

    val pages = remember(doc) { doc?.pages.orEmpty().sortedBy { it.orderIndex } }
    var selectedPageId by remember(pages) { mutableStateOf(pages.firstOrNull()?.id) }
    val selectedPage = pages.firstOrNull { it.id == selectedPageId }

    // Signature placement as fractions of the displayed page.
    var centerX by remember { mutableStateOf(0.7f) }
    var centerY by remember { mutableStateOf(0.85f) }
    var widthFrac by remember { mutableStateOf(0.35f) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Place signature") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                actions = {
                    IconButton(onClick = onDrawNew) { Icon(Icons.Default.Draw, "Draw new signature") }
                },
            )
        },
        bottomBar = {
            Column {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Size", style = MaterialTheme.typography.labelMedium)
                    Slider(
                        value = widthFrac,
                        onValueChange = { widthFrac = it },
                        valueRange = 0.1f..0.8f,
                        modifier = Modifier.weight(1f).padding(start = 12.dp),
                    )
                }
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(pages, key = { it.id }) { page ->
                        AsyncImage(
                            model = page.imagePath,
                            contentDescription = "Page ${page.orderIndex + 1}",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(56.dp, 74.dp)
                                .border(
                                    width = if (page.id == selectedPageId) 3.dp else 1.dp,
                                    color = if (page.id == selectedPageId)
                                        MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outlineVariant,
                                )
                                .clickable { selectedPageId = page.id },
                        )
                    }
                }
                BottomAppBar {
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = {
                            selectedPage?.let {
                                vm.stamp(it.id, centerX, centerY, widthFrac, onDone = onBack)
                            }
                        },
                        enabled = signaturePath != null && selectedPage != null && !stamping,
                        modifier = Modifier.padding(8.dp),
                    ) {
                        if (stamping) CircularProgressIndicator(Modifier.size(18.dp))
                        else Text("Stamp")
                    }
                }
            }
        },
    ) { padding ->
        if (signaturePath == null) {
            Column(
                Modifier.padding(padding).fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("No signature yet", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Button(onClick = onDrawNew) { Text("Draw a signature") }
            }
            return@Scaffold
        }

        // Decode intrinsic sizes so the preview box has EXACTLY the page's aspect ratio.
        // Placement fractions then map 1:1 onto the composited bitmap (WYSIWYG).
        val pageAspect = remember(selectedPage?.imagePath) {
            selectedPage?.imagePath?.let(::imageAspect) ?: 0.75f
        }
        val sigAspect = remember(signaturePath) {
            signaturePath?.let(::imageAspect) ?: 2f
        }

        var boxSize by remember { mutableStateOf(IntSize.Zero) }
        Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .fillMaxSize()
                    .aspectRatio(pageAspect, matchHeightConstraintsFirst = pageAspect > 1f)
                    .onSizeChanged { boxSize = it }
                    .pointerInput(selectedPageId) {
                        detectDragGestures { change, drag ->
                            if (boxSize == IntSize.Zero) return@detectDragGestures
                            centerX = (centerX + drag.x / boxSize.width).coerceIn(0.05f, 0.95f)
                            centerY = (centerY + drag.y / boxSize.height).coerceIn(0.05f, 0.95f)
                            change.consume()
                        }
                    },
            ) {
                selectedPage?.let { page ->
                    AsyncImage(
                        model = page.imagePath,
                        contentDescription = "Selected page",
                        contentScale = ContentScale.FillBounds,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                if (boxSize != IntSize.Zero) {
                    val density = LocalDensity.current
                    val sigWidthPx = boxSize.width * widthFrac
                    val sigHeightPx = sigWidthPx / sigAspect
                    AsyncImage(
                        model = signaturePath,
                        contentDescription = "Signature",
                        contentScale = ContentScale.FillBounds,
                        modifier = Modifier
                            .graphicsLayer {
                                translationX = centerX * boxSize.width - sigWidthPx / 2
                                translationY = centerY * boxSize.height - sigHeightPx / 2
                            }
                            .size(
                                with(density) { sigWidthPx.toDp() },
                                with(density) { sigHeightPx.toDp() },
                            ),
                    )
                }
            }
        }
    }
}

/** width / height of an image file, without decoding pixels. */
private fun imageAspect(path: String): Float? {
    val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeFile(path, o)
    return if (o.outWidth > 0 && o.outHeight > 0) o.outWidth.toFloat() / o.outHeight else null
}
