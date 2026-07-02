package com.scanly.ui.document

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.scanly.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.scanly.data.db.OcrStatus
import com.scanly.data.db.PageEntity
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentScreen(
    documentId: Long,
    onBack: () -> Unit,
    onAddSignature: () -> Unit,
    onAddPages: (Long) -> Unit,
    vm: DocumentViewModel = hiltViewModel(),
) {
    val doc by vm.document.collectAsState()
    val exported by vm.exported.collectAsState()
    val busy by vm.busy.collectAsState()
    val message by vm.message.collectAsState()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val snackbar = remember { SnackbarHostState() }

    var showExport by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var pendingSafFile by remember { mutableStateOf<File?>(null) }

    // SAF "save as": copy the built PDF into the user-chosen location. This can target
    // any DocumentsProvider — Files, Drive, Dropbox, Nextcloud — without Scanly ever
    // holding the INTERNET permission.
    val scope = rememberCoroutineScope()
    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf"),
    ) { uri ->
        val file = pendingSafFile
        pendingSafFile = null
        if (uri != null && file != null) {
            scope.launch(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        file.inputStream().use { it.copyTo(out) }
                    }
                }
            }
        }
    }

    // Act on freshly exported files.
    LaunchedEffect(exported) {
        val e = exported ?: return@LaunchedEffect
        when (e.action) {
            ExportAction.SHARE -> {
                val file = e.files.first()
                val uri = FileProvider.getUriForFile(
                    context, "${context.packageName}.fileprovider", file,
                )
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "application/pdf"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(share, "Share PDF"))
            }
            ExportAction.SAVE_SAF -> {
                pendingSafFile = e.files.first()
                saveLauncher.launch("${e.files.first().nameWithoutExtension}.pdf")
            }
            ExportAction.SHARE_IMAGES -> {
                val uris = ArrayList(
                    e.files.map {
                        FileProvider.getUriForFile(
                            context, "${context.packageName}.fileprovider", it,
                        )
                    },
                )
                val share = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "image/jpeg"
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(share, "Share pages as images"))
            }
        }
        vm.consumeExport()
    }

    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); vm.consumeMessage() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(doc?.document?.name ?: stringResource(R.string.title_document)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    if (busy) CircularProgressIndicator(Modifier.size(22.dp))
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, "More")
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Copy text") },
                            leadingIcon = { Icon(Icons.Default.ContentCopy, null) },
                            onClick = {
                                showMenu = false
                                val text = vm.collectText()
                                if (text == null) {
                                    vm.postMessage("No recognized text yet — run OCR first")
                                } else {
                                    clipboard.setText(AnnotatedString(text))
                                    vm.postMessage("Text copied to clipboard")
                                }
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Rename") },
                            leadingIcon = { Icon(Icons.Default.Edit, null) },
                            onClick = { showMenu = false; showRename = true },
                        )
                        DropdownMenuItem(
                            text = { Text("Delete") },
                            leadingIcon = { Icon(Icons.Default.Delete, null) },
                            onClick = { showMenu = false; showDeleteConfirm = true },
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            BottomAppBar(
                actions = {
                    IconButton(onClick = { onAddPages(documentId) }) {
                        Icon(Icons.Default.AddAPhoto, "Add pages")
                    }
                    IconButton(onClick = vm::runOcr) {
                        Icon(Icons.Default.TextFields, "Run OCR")
                    }
                    IconButton(onClick = onAddSignature) {
                        Icon(Icons.Default.Draw, stringResource(R.string.title_signature))
                    }
                },
                floatingActionButton = {
                    ExtendedFloatingActionButton(
                        onClick = { showExport = true },
                        icon = { Icon(Icons.Default.IosShare, null) },
                        text = { Text("Export") },
                    )
                },
            )
        },
    ) { padding ->
        val pages = remember(doc) { doc?.pages.orEmpty().sortedBy { it.orderIndex } }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 150.dp),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            items(pages, key = { it.id }) { page ->
                PageTile(page)
            }
        }
    }

    if (showExport) {
        ExportSheet(
            onShare = { searchable, password ->
                showExport = false; vm.requestShare(searchable, password)
            },
            onSaveToDevice = { searchable, password ->
                showExport = false; vm.requestSaveToDevice(searchable, password)
            },
            onShareImages = { showExport = false; vm.requestShareImages() },
            onDismiss = { showExport = false },
        )
    }

    if (showRename) {
        RenameDialog(
            current = doc?.document?.name.orEmpty(),
            onConfirm = { vm.rename(it); showRename = false },
            onDismiss = { showRename = false },
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete document?") },
            text = { Text("All pages of this document will be removed from this device. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = { showDeleteConfirm = false; vm.delete(onBack) }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun RenameDialog(current: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename document") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim()) },
                enabled = name.isNotBlank(),
            ) { Text("Rename") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExportSheet(
    onShare: (Boolean, String?) -> Unit,
    onSaveToDevice: (Boolean, String?) -> Unit,
    onShareImages: () -> Unit,
    onDismiss: () -> Unit,
) {
    var searchable by remember { mutableStateOf(true) }
    var password by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Text(
                "Export PDF",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(16.dp),
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.export_searchable)) },
                supportingContent = { Text("Add an invisible, selectable text layer (OCR)") },
                leadingContent = { Icon(Icons.Default.TextFields, null) },
                trailingContent = {
                    Switch(checked = searchable, onCheckedChange = { searchable = it })
                },
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("PDF password (optional)") },
                supportingText = { Text("Encrypts the PDF — free here, paywalled elsewhere") },
                leadingIcon = { Icon(Icons.Default.Lock, null) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            HorizontalDivider(Modifier.padding(top = 12.dp))
            ListItem(
                headlineContent = { Text(stringResource(R.string.share)) },
                leadingContent = { Icon(Icons.Default.IosShare, null) },
                modifier = Modifier.fillMaxWidth().clickable { onShare(searchable, password) },
            )
            ListItem(
                headlineContent = { Text("Save to device") },
                supportingContent = { Text("Files, or any cloud app (Drive, Dropbox, Nextcloud…)") },
                leadingContent = { Icon(Icons.Default.SaveAlt, null) },
                modifier = Modifier.fillMaxWidth().clickable { onSaveToDevice(searchable, password) },
            )
            ListItem(
                headlineContent = { Text("Share pages as images") },
                supportingContent = { Text("One JPEG per page") },
                leadingContent = { Icon(Icons.Default.Image, null) },
                modifier = Modifier.fillMaxWidth().clickable { onShareImages() },
            )
            Text(
                "\"Save to device\" can write straight into Google Drive or Dropbox via " +
                    "their system file providers — Scanly itself never touches the internet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

@Composable
private fun PageTile(page: PageEntity) {
    ElevatedCard {
        Box(Modifier.fillMaxWidth().aspectRatio(0.75f).clip(RoundedCornerShape(8.dp))) {
            AsyncImage(
                model = page.imagePath,
                contentDescription = "Page ${page.orderIndex + 1}",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            OcrBadge(page.ocrStatus, Modifier.align(Alignment.BottomStart).padding(6.dp))
        }
    }
}

@Composable
private fun OcrBadge(status: OcrStatus, modifier: Modifier = Modifier) {
    val pair = when (status) {
        OcrStatus.NONE -> null
        OcrStatus.QUEUED -> stringResource(R.string.ocr_queued) to MaterialTheme.colorScheme.tertiary
        OcrStatus.DONE -> stringResource(R.string.ocr_done) to MaterialTheme.colorScheme.primary
        OcrStatus.FAILED -> stringResource(R.string.ocr_failed) to MaterialTheme.colorScheme.error
    } ?: return
    Surface(color = pair.second, shape = RoundedCornerShape(50), modifier = modifier) {
        Text(
            pair.first,
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}
