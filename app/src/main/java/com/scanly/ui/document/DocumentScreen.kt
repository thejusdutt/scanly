package com.scanly.ui.document

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
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
    onOpenPage: (Long, Int) -> Unit,
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
    var showFolder by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showWatermark by remember { mutableStateOf(false) }
    var showTags by remember { mutableStateOf(false) }
    /** Page multi-select for split/delete; empty = normal browsing. */
    var selectedPages by remember { mutableStateOf(setOf<Long>()) }

    // Per-document lock gate: nothing of a locked document renders until the user
    // passes the device credential/biometric prompt (once per process session).
    val sessionUnlocked by vm.sessionUnlocked.collectAsState()
    if (doc?.document?.locked == true && !sessionUnlocked) {
        val activity = context as? androidx.fragment.app.FragmentActivity
        val promptUnlock = {
            activity?.let {
                com.scanly.ui.security.BiometricUnlock.prompt(it, "Unlock document") {
                    vm.markUnlocked()
                }
            } ?: vm.markUnlocked()
        }
        LaunchedEffect(Unit) { promptUnlock() }
        com.scanly.ui.security.LockGate(
            title = "This document is locked",
            onUnlockRequest = { promptUnlock() },
        )
        return
    }
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
            ExportAction.PRINT -> {
                val file = e.files.first()
                val pm = context.getSystemService(android.content.Context.PRINT_SERVICE)
                    as android.print.PrintManager
                pm.print(
                    file.nameWithoutExtension,
                    PdfPrintDocumentAdapter(file, file.nameWithoutExtension),
                    null,
                )
            }
            ExportAction.SHARE_VCF -> {
                val uri = FileProvider.getUriForFile(
                    context, "${context.packageName}.fileprovider", e.files.first(),
                )
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "text/x-vcard"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(share, "Share contact"))
            }
            ExportAction.SHARE_LONG_IMAGE -> {
                val uri = FileProvider.getUriForFile(
                    context, "${context.packageName}.fileprovider", e.files.first(),
                )
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "image/jpeg"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(share, "Share long image"))
            }
            ExportAction.SHARE_TXT -> {
                val uri = FileProvider.getUriForFile(
                    context, "${context.packageName}.fileprovider", e.files.first(),
                )
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(share, "Share text"))
            }
        }
        vm.consumeExport()
    }

    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); vm.consumeMessage() }
    }

    Scaffold(
        topBar = {
            if (selectedPages.isNotEmpty()) {
                TopAppBar(
                    title = { Text("${selectedPages.size} selected") },
                    navigationIcon = {
                        IconButton(onClick = { selectedPages = emptySet() }) {
                            Icon(Icons.Default.Close, "Clear selection")
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            vm.extractPages(selectedPages.toList())
                            selectedPages = emptySet()
                        }) {
                            Icon(Icons.Default.CallSplit, "Move to new document")
                        }
                        IconButton(onClick = {
                            vm.deletePages(selectedPages.toList())
                            selectedPages = emptySet()
                        }) {
                            Icon(Icons.Default.Delete, "Delete selected pages")
                        }
                    },
                )
            } else {
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
                            text = { Text("Print") },
                            leadingIcon = { Icon(Icons.Default.Print, null) },
                            onClick = { showMenu = false; vm.requestPrint() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.add_to_contacts)) },
                            leadingIcon = { Icon(Icons.Default.PersonAdd, null) },
                            onClick = {
                                showMenu = false
                                val contact = vm.parsedContact()
                                if (contact == null) {
                                    vm.postMessage("No contact details found — run OCR first")
                                } else {
                                    runCatching { context.startActivity(contactInsertIntent(contact)) }
                                        .onFailure { vm.postMessage("No contacts app available") }
                                }
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.share_vcard)) },
                            leadingIcon = { Icon(Icons.Default.ContactPage, null) },
                            onClick = { showMenu = false; vm.shareVCard() },
                        )
                        DropdownMenuItem(
                            text = { Text("Add watermark…") },
                            leadingIcon = { Icon(Icons.Default.BrandingWatermark, null) },
                            onClick = { showMenu = false; showWatermark = true },
                        )
                        DropdownMenuItem(
                            text = { Text("Share as long image") },
                            leadingIcon = { Icon(Icons.Default.Panorama, null) },
                            onClick = { showMenu = false; vm.requestLongImage() },
                        )
                        DropdownMenuItem(
                            text = { Text("Share text (.txt)") },
                            leadingIcon = { Icon(Icons.Default.Description, null) },
                            onClick = { showMenu = false; vm.requestText() },
                        )
                        DropdownMenuItem(
                            text = { Text("Edit tags…") },
                            leadingIcon = { Icon(Icons.Default.Label, null) },
                            onClick = { showMenu = false; showTags = true },
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (doc?.document?.locked == true) "Remove lock"
                                    else "Lock document",
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    if (doc?.document?.locked == true) Icons.Default.LockOpen
                                    else Icons.Default.Lock,
                                    null,
                                )
                            },
                            onClick = { showMenu = false; vm.toggleLock() },
                        )
                        DropdownMenuItem(
                            text = { Text("Move to folder…") },
                            leadingIcon = { Icon(Icons.Default.Folder, null) },
                            onClick = { showMenu = false; showFolder = true },
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
            }
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
                val index = pages.indexOf(page)
                PageTile(
                    page = page,
                    selected = page.id in selectedPages,
                    onClick = {
                        if (selectedPages.isNotEmpty()) {
                            selectedPages =
                                if (page.id in selectedPages) selectedPages - page.id
                                else selectedPages + page.id
                        } else {
                            onOpenPage(documentId, index)
                        }
                    },
                    onLongClick = {
                        selectedPages =
                            if (page.id in selectedPages) selectedPages - page.id
                            else selectedPages + page.id
                    },
                )
            }
        }
    }

    if (showTags) {
        TagsDialog(
            current = doc?.document?.tags.orEmpty(),
            onConfirm = { csv ->
                showTags = false
                vm.setTags(csv.split(',').map(String::trim).filter(String::isNotEmpty))
            },
            onDismiss = { showTags = false },
        )
    }

    if (showExport) {
        ExportSheet(
            onShare = { searchable, password, size, quality ->
                showExport = false; vm.requestShare(searchable, password, size, quality)
            },
            onSaveToDevice = { searchable, password, size, quality ->
                showExport = false; vm.requestSaveToDevice(searchable, password, size, quality)
            },
            onShareImages = { showExport = false; vm.requestShareImages() },
            onDismiss = { showExport = false },
        )
    }

    if (showWatermark) {
        WatermarkDialog(
            onConfirm = { spec -> showWatermark = false; vm.applyWatermark(spec) },
            onDismiss = { showWatermark = false },
        )
    }

    if (showRename) {
        RenameDialog(
            current = doc?.document?.name.orEmpty(),
            onConfirm = { vm.rename(it); showRename = false },
            onDismiss = { showRename = false },
        )
    }

    if (showFolder) {
        val folders by vm.folders.collectAsState()
        FolderDialog(
            folders = folders,
            current = doc?.document?.folder,
            onConfirm = { vm.setFolder(it); showFolder = false },
            onDismiss = { showFolder = false },
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
private fun FolderDialog(
    folders: List<String>,
    current: String?,
    onConfirm: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(current.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Move to folder") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Folder name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (folders.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("Existing:", style = MaterialTheme.typography.labelMedium)
                    folders.forEach { f ->
                        ListItem(
                            headlineContent = { Text(f) },
                            leadingContent = { Icon(Icons.Default.Folder, null) },
                            modifier = Modifier.clickable { name = f },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.trim().ifBlank { null }) }) { Text("Move") }
        },
        dismissButton = {
            Row {
                if (current != null) {
                    TextButton(onClick = { onConfirm(null) }) { Text("Remove") }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
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
    onShare: (Boolean, String?, com.scanly.pdf.PdfPageSize, Float) -> Unit,
    onSaveToDevice: (Boolean, String?, com.scanly.pdf.PdfPageSize, Float) -> Unit,
    onShareImages: () -> Unit,
    onDismiss: () -> Unit,
) {
    var searchable by remember { mutableStateOf(true) }
    var password by remember { mutableStateOf("") }
    var pageSize by remember { mutableStateOf(com.scanly.pdf.PdfPageSize.AUTO) }
    var quality by remember { mutableStateOf(0.9f) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .padding(bottom = 24.dp)
                .verticalScroll(androidx.compose.foundation.rememberScrollState()),
        ) {
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
            // Adobe-style page resize presets.
            Text(
                "Page size",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            Row(
                Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                com.scanly.pdf.PdfPageSize.entries.forEach { size ->
                    FilterChip(
                        selected = pageSize == size,
                        onClick = { pageSize = size },
                        label = {
                            Text(
                                when (size) {
                                    com.scanly.pdf.PdfPageSize.AUTO -> "Auto fit"
                                    com.scanly.pdf.PdfPageSize.A4 -> "A4"
                                    com.scanly.pdf.PdfPageSize.LETTER -> "Letter"
                                    com.scanly.pdf.PdfPageSize.LEGAL -> "Legal"
                                },
                            )
                        },
                    )
                }
            }
            Text(
                "File size",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            Row(
                Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(selected = quality >= 0.89f, onClick = { quality = 0.9f },
                    label = { Text("Best") })
                FilterChip(selected = quality in 0.75f..0.88f, onClick = { quality = 0.8f },
                    label = { Text("Balanced") })
                FilterChip(selected = quality < 0.75f, onClick = { quality = 0.62f },
                    label = { Text("Smallest") })
            }
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
                modifier = Modifier.fillMaxWidth()
                    .clickable { onShare(searchable, password, pageSize, quality) },
            )
            ListItem(
                headlineContent = { Text("Save to device") },
                supportingContent = { Text("Files, or any cloud app (Drive, Dropbox, Nextcloud…)") },
                leadingContent = { Icon(Icons.Default.SaveAlt, null) },
                modifier = Modifier.fillMaxWidth()
                    .clickable { onSaveToDevice(searchable, password, pageSize, quality) },
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

/** Custom text watermark: user text, size, opacity, single or tiled. Baked onto pages. */
@Composable
private fun WatermarkDialog(
    onConfirm: (com.scanly.cv.ImageProcessing.WatermarkSpec) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    var sizeFrac by remember { mutableStateOf(0.10f) }
    var opacity by remember { mutableStateOf(0.25f) }
    var tiled by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add watermark") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Watermark text") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Text("Size", style = MaterialTheme.typography.labelMedium)
                Slider(value = sizeFrac, onValueChange = { sizeFrac = it }, valueRange = 0.05f..0.25f)
                Text("Opacity", style = MaterialTheme.typography.labelMedium)
                Slider(value = opacity, onValueChange = { opacity = it }, valueRange = 0.08f..0.6f)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = tiled, onCheckedChange = { tiled = it })
                    Spacer(Modifier.width(8.dp))
                    Text("Repeat across the page")
                }
                Text(
                    "The watermark is baked into the pages. Changing a page's filter removes it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = text.isNotBlank(),
                onClick = {
                    onConfirm(
                        com.scanly.cv.ImageProcessing.WatermarkSpec(
                            text = text.trim(),
                            sizeFrac = sizeFrac,
                            opacity = opacity,
                            tiled = tiled,
                        ),
                    )
                },
            ) { Text("Apply") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * System "new contact" intent pre-filled from a parsed business card. Uses the contacts
 * app's own insert flow, so Scanly needs no contacts permission and the user reviews
 * every field before anything is saved.
 */
private fun contactInsertIntent(c: com.scanly.common.ContactParser.Contact): Intent =
    Intent(android.provider.ContactsContract.Intents.Insert.ACTION).apply {
        type = android.provider.ContactsContract.RawContacts.CONTENT_TYPE
        c.name?.let { putExtra(android.provider.ContactsContract.Intents.Insert.NAME, it) }
        c.org?.let { putExtra(android.provider.ContactsContract.Intents.Insert.COMPANY, it) }
        c.phones.getOrNull(0)
            ?.let { putExtra(android.provider.ContactsContract.Intents.Insert.PHONE, it) }
        c.phones.getOrNull(1)
            ?.let { putExtra(android.provider.ContactsContract.Intents.Insert.SECONDARY_PHONE, it) }
        c.phones.getOrNull(2)
            ?.let { putExtra(android.provider.ContactsContract.Intents.Insert.TERTIARY_PHONE, it) }
        c.emails.getOrNull(0)
            ?.let { putExtra(android.provider.ContactsContract.Intents.Insert.EMAIL, it) }
        c.emails.getOrNull(1)
            ?.let { putExtra(android.provider.ContactsContract.Intents.Insert.SECONDARY_EMAIL, it) }
        c.emails.getOrNull(2)
            ?.let { putExtra(android.provider.ContactsContract.Intents.Insert.TERTIARY_EMAIL, it) }
        if (c.websites.isNotEmpty()) {
            val data = ArrayList<android.content.ContentValues>()
            c.websites.forEach { url ->
                data.add(
                    android.content.ContentValues().apply {
                        put(
                            android.provider.ContactsContract.Data.MIMETYPE,
                            android.provider.ContactsContract.CommonDataKinds.Website.CONTENT_ITEM_TYPE,
                        )
                        put(android.provider.ContactsContract.CommonDataKinds.Website.URL, url)
                    },
                )
            }
            putParcelableArrayListExtra(
                android.provider.ContactsContract.Intents.Insert.DATA, data,
            )
        }
    }

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun PageTile(
    page: PageEntity,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    ElevatedCard {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.75f)
                .clip(RoundedCornerShape(8.dp))
                .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        ) {
            AsyncImage(
                model = page.imagePath,
                contentDescription = "Page ${page.orderIndex + 1}",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            OcrBadge(page.ocrStatus, Modifier.align(Alignment.BottomStart).padding(6.dp))
            if (selected) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.30f)),
                )
                Icon(
                    Icons.Default.CheckCircle, "Selected",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                )
            }
        }
    }
}

@Composable
private fun TagsDialog(current: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var value by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit tags") },
        text = {
            Column {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text("Tags, comma-separated") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "e.g. receipts, taxes 2026, work",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(value) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
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
