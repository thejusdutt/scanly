package com.scanly.ui.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Folder
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.scanly.R
import com.scanly.data.db.DocumentSummary
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

    // The system Photo Picker: no storage permission, no gallery access beyond the picks.
    val pickImages = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(),
    ) { uris -> vm.importFromGallery(uris) }

    LaunchedEffect(importedDocId) {
        importedDocId?.let { vm.consumeImported(); onOpenDocument(it) }
    }

    Scaffold(
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    if (importing) CircularProgressIndicator(Modifier.size(22.dp))
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
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onScan,
                icon = { Icon(Icons.Default.DocumentScanner, null) },
                text = { Text(stringResource(R.string.title_capture)) },
            )
        },
    ) { padding ->
        val folders by vm.folders.collectAsState()
        val selectedFolder by vm.selectedFolder.collectAsState()
        Column(Modifier.padding(padding).fillMaxSize()) {
            SearchBarField(query, vm::onQueryChange)
            if (folders.isNotEmpty()) {
                FolderChips(folders, selectedFolder, vm::onFolderSelect)
            }
            if (documents.isEmpty()) {
                EmptyState(searching = query.isNotBlank())
            } else if (isGrid) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 160.dp),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(documents, key = { it.id }) { doc ->
                        DocumentCard(doc) { onOpenDocument(doc.id) }
                    }
                }
            } else {
                androidx.compose.foundation.lazy.LazyColumn(
                    contentPadding = PaddingValues(vertical = 4.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(documents.size, key = { documents[it].id }) { i ->
                        DocumentRow(
                            doc = documents[i],
                            onClick = { onOpenDocument(documents[i].id) },
                            onRename = { vm.rename(documents[i].id, it) },
                            onDelete = { vm.delete(documents[i].id) },
                        )
                    }
                }
            }
        }
    }
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
private fun SearchBarField(query: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onChange,
        leadingIcon = { Icon(Icons.Default.Search, null) },
        placeholder = { Text("Search names and scanned text") },
        singleLine = true,
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun DocumentCard(doc: DocumentSummary, onClick: () -> Unit) {
    ElevatedCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
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
                    "${doc.pageCount}p",
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
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
@Composable
private fun DocumentRow(
    doc: DocumentSummary,
    onClick: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }

    ListItem(
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
            Text(doc.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            val date = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(doc.updatedAt))
            Text(
                buildString {
                    append(date); append("  ·  "); append(doc.pageCount)
                    append(if (doc.pageCount == 1) " page" else " pages")
                    doc.folder?.let { append("  ·  "); append(it) }
                },
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
        modifier = Modifier.clickable(onClick = onClick),
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
private fun EmptyState(searching: Boolean) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Default.DocumentScanner, null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.outlineVariant,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            if (searching) "No matches" else "No documents yet",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            if (searching) "Try a different search term."
            else "Tap Scan to capture your first document. Everything stays on your device.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}
