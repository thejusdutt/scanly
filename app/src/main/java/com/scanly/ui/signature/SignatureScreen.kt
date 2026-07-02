package com.scanly.ui.signature

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Path as AndroidPath
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.scanly.R
import java.io.File
import java.io.FileOutputStream

/**
 * Draw a signature once; saved as a transparent, tightly-cropped PNG under
 * <files>/signatures for stamping onto pages (a "paid hook" competitors charge for).
 *
 * Strokes are kept as raw point lists so the exact on-screen geometry is what gets
 * rasterized — the preview and the saved PNG cannot drift apart.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignatureScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val strokes = remember { mutableStateListOf<List<Offset>>() }
    var current by remember { mutableStateOf<List<Offset>>(emptyList()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_signature)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
            )
        },
        bottomBar = {
            BottomAppBar {
                TextButton(onClick = { strokes.clear(); current = emptyList() }) { Text("Clear") }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = {
                        saveSignaturePng(context.filesDir, strokes.toList())
                        onBack()
                    },
                    enabled = strokes.isNotEmpty(),
                    modifier = Modifier.padding(8.dp),
                ) { Text("Save") }
            }
        },
    ) { padding ->
        Canvas(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .background(Color.White)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { offset -> current = listOf(offset) },
                        onDrag = { change, _ -> current = current + change.position },
                        onDragEnd = {
                            if (current.size > 1) strokes.add(current)
                            current = emptyList()
                        },
                    )
                },
        ) {
            (strokes + listOf(current)).forEach { pts ->
                if (pts.size < 2) return@forEach
                val p = Path().apply {
                    moveTo(pts.first().x, pts.first().y)
                    pts.drop(1).forEach { lineTo(it.x, it.y) }
                }
                drawPath(p, Color.Black, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 6f))
            }
        }
    }
}

/** Rasterize the strokes to a transparent PNG, cropped to the ink bounds plus padding. */
private fun saveSignaturePng(filesDir: File, strokes: List<List<Offset>>, inkWidth: Float = 6f) {
    val all = strokes.flatten()
    if (all.isEmpty()) return
    val pad = inkWidth * 2
    val minX = all.minOf { it.x } - pad
    val minY = all.minOf { it.y } - pad
    val maxX = all.maxOf { it.x } + pad
    val maxY = all.maxOf { it.y } + pad
    val w = (maxX - minX).toInt().coerceAtLeast(1)
    val h = (maxY - minY).toInt().coerceAtLeast(1)

    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.BLACK
        strokeWidth = inkWidth
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    for (pts in strokes) {
        if (pts.size < 2) continue
        val path = AndroidPath().apply {
            moveTo(pts.first().x - minX, pts.first().y - minY)
            pts.drop(1).forEach { lineTo(it.x - minX, it.y - minY) }
        }
        canvas.drawPath(path, paint)
    }

    val dir = File(filesDir, "signatures").apply { mkdirs() }
    FileOutputStream(File(dir, "sig_${System.currentTimeMillis()}.png")).use {
        bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
    }
    bmp.recycle()
}
