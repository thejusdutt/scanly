package com.scanly.ui.review

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.scanly.R
import com.scanly.common.Filter
import com.scanly.data.db.PageEntity

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

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.title_review)) }) },
        bottomBar = {
            BottomAppBar {
                TextButton(onClick = onAddMorePages, modifier = Modifier.padding(8.dp)) {
                    Text("Add pages")
                }
                Spacer(Modifier.weight(1f))
                Button(onClick = onDone, modifier = Modifier.padding(8.dp)) { Text("Done") }
            }
        },
    ) { padding ->
        val pages = remember(doc) { doc?.pages.orEmpty().sortedBy { it.orderIndex } }
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            items(pages, key = { it.id }) { page ->
                PageCard(
                    page = page,
                    isFirst = page.id == pages.firstOrNull()?.id,
                    isLast = page.id == pages.lastOrNull()?.id,
                    canRecrop = page.originalPath != null,
                    onFilter = { vm.setFilter(page.id, it) },
                    onDelete = { vm.deletePage(page.id) },
                    onRotate = { vm.rotatePage(page.id) },
                    onMoveUp = { vm.movePage(page.id, up = true) },
                    onMoveDown = { vm.movePage(page.id, up = false) },
                    onAdjustCrop = { onAdjustCrop(page.id) },
                )
            }
        }
    }
}

@Composable
private fun PageCard(
    page: PageEntity,
    isFirst: Boolean,
    isLast: Boolean,
    canRecrop: Boolean,
    onFilter: (Filter) -> Unit,
    onDelete: () -> Unit,
    onRotate: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onAdjustCrop: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().padding(12.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    model = page.imagePath,
                    contentDescription = "Page ${page.orderIndex + 1}",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.height(160.dp).weight(1f),
                )
                Column {
                    IconButton(onClick = onMoveUp, enabled = !isFirst) {
                        Icon(Icons.Default.ArrowUpward, "Move page up")
                    }
                    IconButton(onClick = onMoveDown, enabled = !isLast) {
                        Icon(Icons.Default.ArrowDownward, "Move page down")
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilterChoice("Color", page.filter == Filter.COLOR) { onFilter(Filter.COLOR) }
                FilterChoice("Grey", page.filter == Filter.GREYSCALE) { onFilter(Filter.GREYSCALE) }
                FilterChoice("B&W", page.filter == Filter.BW) { onFilter(Filter.BW) }
                FilterChoice("Magic", page.filter == Filter.MAGIC) { onFilter(Filter.MAGIC) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onRotate) { Icon(Icons.Default.RotateRight, "Rotate 90°") }
                IconButton(onClick = onAdjustCrop, enabled = canRecrop) {
                    Icon(Icons.Default.Crop, "Adjust crop")
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Delete page") }
            }
        }
    }
}

@Composable
private fun FilterChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) },
        modifier = Modifier.padding(end = 6.dp))
}
