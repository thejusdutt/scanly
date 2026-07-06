package com.scanly.ui.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Merge
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Label
import androidx.compose.material.icons.outlined.TextSnippet
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.scanly.R
import com.scanly.data.db.DocumentSummary
import com.scanly.ui.common.rememberHaptics
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onScan: () -> Unit,
    onOpenDocument: (Long) -> Unit,
    onSettings: () -> Unit,
    vm: LibraryViewModel = hiltViewModel(),
) {
    val documents by vm.documents.collectAsState()
    val query by vm.query.collectAsState()
    val importing by vm.importing.collectAsState()
    val importedDocId by vm.importedDocId.collectAsState()
    val isGrid by vm.isGrid.collectAsState()
    val selection by vm.selection.collectAsState()
    val sort by vm.sort.collectAsState()
    val message by vm.message.collectAsState()

    var showMenu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmMerge by remember { mutableStateOf(false) }
    var showFolderMove by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    // The system Photo Picker: no storage permission, no gallery access beyond the picks.
    val pickImages = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(),
    ) { uris -> vm.importFromGallery(uris) }

    // SAF PDF picker: import any PDF, rendered on-device by PdfBox.
    val pickPdf = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> vm.importPdfFile(uri) }

    LaunchedEffect(importedDocId) {
        importedDocId?.let { vm.consumeImported(); onOpenDocument(it) }
    }
    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); vm.consumeMessage() }
    }

    val haptics = rememberHaptics()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            AnimatedContent(
                targetState = selection.isNotEmpty(),
                transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(200)) },
                label = "libraryTopBar",
            ) { selecting ->
            if (selecting) {
                TopAppBar(
                    title = { Text("${selection.size} selected") },
                    navigationIcon = {
                        IconButton(onClick = vm::clearSelection) {
                            Icon(Icons.Default.Close, "Clear selection")
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = { confirmMerge = true },
                            enabled = selection.size >= 2,
                        ) { Icon(Icons.Default.Merge, "Merge documents") }
                        IconButton(onClick = { showFolderMove = true }) {
                            Icon(Icons.Default.Folder, "Move to folder")
                        }
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(Icons.Default.Delete, "Delete selected")
                        }
                    },
                )
            } else {
                LargeTopAppBar(
                    title = { Text(stringResource(R.string.app_name)) },
                    scrollBehavior = scrollBehavior,
                    actions = {
                        IconButton(
                            onClick = {
                                pickImages.launch(
                                    PickVisualMediaRequest(
                                        ActivityResultContracts.PickVisualMedia.ImageOnly,
                                    ),
                                )
                            },
                            enabled = !importing,
                        ) {
                            Icon(Icons.Default.AddPhotoAlternate, "Import from gallery")
                        }
                        IconButton(onClick = vm::toggleLayout) {
                            Icon(
                                if (isGrid) Icons.AutoMirrored.Filled.ViewList else Icons.Default.GridView,
                                "Toggle layout",
                            )
                        }
                        IconButton(onClick = onSettings) {
                            Icon(Icons.Default.Settings, stringResource(R.string.title_settings))
                        }
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.MoreVert, "More")
                        }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            Text(
                                "Sort by",
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            )
                            SortItem("Recent first", sort == LibrarySort.RECENT) {
                                vm.setSort(LibrarySort.RECENT); showMenu = false
                            }
                            SortItem("Name A–Z", sort == LibrarySort.NAME) {
                                vm.setSort(LibrarySort.NAME); showMenu = false
                            }
                            SortItem("Oldest first", sort == LibrarySort.OLDEST) {
                                vm.setSort(LibrarySort.OLDEST); showMenu = false
                            }
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Import PDF…") },
                                leadingIcon = { Icon(Icons.Default.PictureAsPdf, null) },
                                enabled = !importing,
                                onClick = {
                                    showMenu = false
                                    pickPdf.launch(arrayOf("application/pdf"))
                                },
                            )
                        }
                    },
                )
            }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (selection.isEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = onScan,
                    icon = { Icon(Icons.Default.DocumentScanner, null) },
                    text = { Text(stringResource(R.string.title_capture)) },
                )
            }
        },
    ) { padding ->
        val folders by vm.folders.collectAsState()
        val selectedFolder by vm.selectedFolder.collectAsState()
        val allTags by vm.allTags.collectAsState()
        val selectedTag by vm.selectedTag.collectAsState()
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (importing) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            SearchBarField(query, vm::onQueryChange)
            if (folders.isNotEmpty()) {
                FolderChips(folders, selectedFolder, vm::onFolderSelect)
            }
            if (allTags.isNotEmpty()) {
                TagChips(allTags, selectedTag, vm::onTagSelect)
            }
            if (documents.isEmpty()) {
                EmptyState(
                    filtering = query.isNotBlank() || selectedFolder != null || selectedTag != null,
                    onScan = onScan,
                    onImportPhotos = {
                        pickImages.launch(
                            PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageOnly,
                            ),
                        )
                    },
                    onImportPdf = { pickPdf.launch(arrayOf("application/pdf")) },
                )
            } else if (isGrid) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 160.dp),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(documents, key = { it.id }) { doc ->
                        DocumentCard(
                            doc = doc,
                            selected = doc.id in selection,
                            onClick = {
                                if (selection.isNotEmpty()) vm.toggleSelect(doc.id)
                                else onOpenDocument(doc.id)
                            },
                            onLongClick = { haptics.longPress(); vm.toggleSelect(doc.id) },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            } else {
                androidx.compose.foundation.lazy.LazyColumn(
                    contentPadding = PaddingValues(vertical = 4.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(documents.size, key = { documents[it].id }) { i ->
                        val doc = documents[i]
                        DocumentRow(
                            doc = doc,
                            selected = doc.id in selection,
                            onClick = {
                                if (selection.isNotEmpty()) vm.toggleSelect(doc.id)
                                else onOpenDocument(doc.id)
                            },
                            onLongClick = { haptics.longPress(); vm.toggleSelect(doc.id) },
                            onRename = { vm.rename(doc.id, it) },
                            onDelete = { vm.delete(doc.id) },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = {
                Text(
                    if (selection.size == 1) "Delete document?"
                    else "Delete ${selection.size} documents?",
                )
            },
            text = { Text("All their pages will be removed from this device. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; vm.deleteSelected() }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
        )
    }

    if (confirmMerge) {
        val first = documents.firstOrNull { it.id in selection }
        AlertDialog(
            onDismissRequest = { confirmMerge = false },
            title = { Text("Merge ${selection.size} documents?") },
            text = {
                Text(
                    "All pages are appended, in the order shown, into " +
                        "\"${first?.name ?: "the first document"}\". The other documents are removed.",
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmMerge = false; vm.mergeSelected() }) { Text("Merge") }
            },
            dismissButton = {
                TextButton(onClick = { confirmMerge = false }) { Text("Cancel") }
            },
        )
    }

    if (showFolderMove) {
        val folders by vm.folders.collectAsState()
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showFolderMove = false },
            title = { Text("Move ${selection.size} document(s) to folder") },
            text = {
                Column {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Folder name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    folders.forEach { f ->
                        ListItem(
                            headlineContent = { Text(f) },
                            leadingContent = { Icon(Icons.Outlined.Folder, null) },
                            modifier = Modifier.clickable { name = f },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showFolderMove = false
                    vm.moveSelectedToFolder(name.trim().ifBlank { null })
                }) { Text("Move") }
            },
            dismissButton = {
                TextButton(onClick = { showFolderMove = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun SortItem(label: String, selected: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = {
            RadioButton(selected = selected, onClick = null)
        },
        onClick = onClick,
    )
}

@Composable
private fun FolderChips(
    folders: List<String>,
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    androidx.compose.foundation.lazy.LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            FilterChip(
                selected = selected == null,
                onClick = { onSelect(null) },
                label = { Text("All") },
            )
        }
        items(folders.size) { i ->
            FilterChip(
                selected = selected == folders[i],
                onClick = { onSelect(if (selected == folders[i]) null else folders[i]) },
                leadingIcon = { Icon(Icons.Outlined.Folder, null, Modifier.size(16.dp)) },
                label = { Text(folders[i]) },
            )
        }
    }
}

@Composable
private fun TagChips(
    tags: List<String>,
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    androidx.compose.foundation.lazy.LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(tags.size) { i ->
            FilterChip(
                selected = selected == tags[i],
                onClick = { onSelect(if (selected == tags[i]) null else tags[i]) },
                leadingIcon = { Icon(Icons.Outlined.Label, null, Modifier.size(16.dp)) },
                label = { Text(tags[i]) },
            )
        }
    }
}

@Composable
private fun SearchBarField(query: String, onChange: (String) -> Unit) {
    val keyboard = LocalSoftwareKeyboardController.current
    OutlinedTextField(
        value = query,
        onValueChange = onChange,
        leadingIcon = { Icon(Icons.Default.Search, null) },
        trailingIcon = if (query.isNotEmpty()) {
            {
                IconButton(onClick = { onChange("") }) {
                    Icon(Icons.Default.Close, "Clear search")
                }
            }
        } else {
            null
        },
        placeholder = { Text("Search names and scanned text") },
        singleLine = true,
        shape = RoundedCornerShape(28.dp),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DocumentCard(
    doc: DocumentSummary,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ElevatedCard(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.75f)
                .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (doc.thumbnailPath != null) {
                AsyncImage(
                    model = doc.thumbnailPath,
                    contentDescription = doc.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    Icons.Default.DocumentScanner, null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.outline,
                )
            }
            // Page-count pill.
            Surface(
                color = Color.Black.copy(alpha = 0.55f),
                shape = RoundedCornerShape(50),
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
            ) {
                Text(
                    if (doc.pageCount == 1) "1 page" else "${doc.pageCount} pages",
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
            if (doc.locked) {
                Icon(
                    Icons.Default.Lock, "Locked",
                    tint = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .size(18.dp),
                )
            }
            // Qualified: inside the Card's ColumnScope the unqualified name resolves to
            // the ColumnScope extension, which can't be used from this nested Box.
            androidx.compose.animation.AnimatedVisibility(
                visible = selected,
                enter = fadeIn(tween(150)),
                exit = fadeOut(tween(150)),
                modifier = Modifier.matchParentSize(),
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.30f)),
                )
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = selected,
                enter = scaleIn(initialScale = 0.4f) + fadeIn(),
                exit = scaleOut(targetScale = 0.4f) + fadeOut(),
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
            ) {
                Icon(
                    Icons.Default.CheckCircle, "Selected",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.background(Color.White, CircleShape),
                )
            }
        }
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    doc.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (doc.hasText) {
                    Icon(
                        Icons.Outlined.TextSnippet, "Has searchable text",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Text(
                DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(doc.updatedAt)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Adobe-style file row: thumbnail, name, meta, overflow with quick actions. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DocumentRow(
    doc: DocumentSummary,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showMenu by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }

    ListItem(
        colors = ListItemDefaults.colors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
        leadingContent = {
            if (doc.thumbnailPath != null) {
                AsyncImage(
                    model = doc.thumbnailPath,
                    contentDescription = doc.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(52.dp, 68.dp)
                        .clip(RoundedCornerShape(6.dp)),
                )
            } else {
                Icon(
                    Icons.Default.DocumentScanner, null,
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.outline,
                )
            }
        },
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (doc.locked) {
                    Icon(
                        Icons.Default.Lock, "Locked",
                        modifier = Modifier.size(14.dp).padding(end = 2.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(doc.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        supportingContent = {
            val date = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(doc.updatedAt))
            Text(
                buildString {
                    append(date); append("  ·  "); append(doc.pageCount)
                    append(if (doc.pageCount == 1) " page" else " pages")
                    doc.folder?.let { append("  ·  "); append(it) }
                    doc.tags?.takeIf { it.isNotBlank() }?.let { append("  ·  #"); append(it) }
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Default.MoreVert, "More")
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        onClick = { showMenu = false; showRename = true },
                    )
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        onClick = { showMenu = false; onDelete() },
                    )
                }
            }
        },
        modifier = modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
    )

    if (showRename) {
        var name by remember { mutableStateOf(doc.name) }
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text("Rename document") },
            text = {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { onRename(name.trim()); showRename = false },
                    enabled = name.isNotBlank(),
                ) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { showRename = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun EmptyState(
    filtering: Boolean,
    onScan: () -> Unit,
    onImportPhotos: () -> Unit,
    onImportPdf: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.secondaryContainer,
        ) {
            Icon(
                Icons.Default.DocumentScanner, null,
                modifier = Modifier.padding(24.dp).size(44.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
        Spacer(Modifier.height(20.dp))
        Text(
            if (filtering) "No matches" else "No documents yet",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            if (filtering) "Try a different search term, or clear the filters above."
            else "Scan a paper document or bring in existing files. " +
                "Everything stays on your device.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        if (!filtering) {
            Spacer(Modifier.height(24.dp))
            Button(onClick = onScan) {
                Icon(Icons.Default.DocumentScanner, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Scan a document")
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onImportPhotos) {
                    Icon(Icons.Default.AddPhotoAlternate, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Import photos")
                }
                TextButton(onClick = onImportPdf) {
                    Icon(Icons.Default.PictureAsPdf, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Import PDF")
                }
            }
        }
    }
}
