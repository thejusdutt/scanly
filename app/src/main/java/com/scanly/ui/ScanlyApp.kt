package com.scanly.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.scanly.ui.capture.CaptureScreen
import com.scanly.ui.crop.CropScreen
import com.scanly.ui.document.DocumentScreen
import com.scanly.ui.edit.CleanupScreen
import com.scanly.ui.edit.MarkupScreen
import com.scanly.ui.library.LibraryScreen
import com.scanly.ui.review.ReviewScreen
import com.scanly.ui.settings.SettingsScreen
import com.scanly.ui.signature.PlaceSignatureScreen
import com.scanly.ui.signature.SignatureScreen
import com.scanly.ui.viewer.PageViewerScreen

/** Navigation routes. Document/review carry a working-document id. */
object Routes {
    const val LIBRARY = "library"
    const val CAPTURE = "capture?documentId={documentId}&retakePageId={retakePageId}"
    const val REVIEW = "review/{documentId}"
    const val DOCUMENT = "document/{documentId}"
    const val PAGES = "pages/{documentId}?index={index}"
    const val CROP = "crop/{pageId}"
    const val CLEANUP = "cleanup/{pageId}"
    const val MARKUP = "markup/{pageId}"
    const val SIGN = "sign/{documentId}"
    const val SIGNATURE = "signature"
    const val SETTINGS = "settings"

    fun capture(documentId: Long? = null, retakePageId: Long? = null): String {
        val params = buildList {
            documentId?.let { add("documentId=$it") }
            retakePageId?.let { add("retakePageId=$it") }
        }
        return "capture" + if (params.isEmpty()) "" else "?${params.joinToString("&")}"
    }

    fun review(documentId: Long) = "review/$documentId"
    fun document(documentId: Long) = "document/$documentId"
    fun pages(documentId: Long, index: Int) = "pages/$documentId?index=$index"
    fun crop(pageId: Long) = "crop/$pageId"
    fun cleanup(pageId: Long) = "cleanup/$pageId"
    fun markup(pageId: Long) = "markup/$pageId"
    fun sign(documentId: Long) = "sign/$documentId"
}

@Composable
fun ScanlyApp() {
    val nav = rememberNavController()

    NavHost(navController = nav, startDestination = Routes.LIBRARY) {
        composable(Routes.LIBRARY) {
            LibraryScreen(
                onScan = { nav.navigate(Routes.capture()) },
                onOpenDocument = { id -> nav.navigate(Routes.document(id)) },
                onSettings = { nav.navigate(Routes.SETTINGS) },
            )
        }
        composable(
            route = Routes.CAPTURE,
            arguments = listOf(
                navArgument("documentId") {
                    type = NavType.StringType; nullable = true; defaultValue = null
                },
                navArgument("retakePageId") {
                    type = NavType.StringType; nullable = true; defaultValue = null
                },
            ),
        ) { entry ->
            val docId = entry.arguments?.getString("documentId")?.toLongOrNull()
            val retakeId = entry.arguments?.getString("retakePageId")?.toLongOrNull()
            CaptureScreen(
                appendToDocumentId = docId,
                retakePageId = retakeId,
                onFinished = { id -> nav.navigate(Routes.review(id)) { popUpTo(Routes.LIBRARY) } },
                onCancel = { nav.popBackStack() },
            )
        }
        composable(
            route = Routes.REVIEW,
            arguments = listOf(navArgument("documentId") { type = NavType.LongType }),
        ) { entry ->
            val docId = entry.arguments!!.getLong("documentId")
            ReviewScreen(
                documentId = docId,
                onDone = { nav.navigate(Routes.document(docId)) { popUpTo(Routes.LIBRARY) } },
                onAddMorePages = { nav.navigate(Routes.capture(docId)) },
                onAdjustCrop = { pageId -> nav.navigate(Routes.crop(pageId)) },
                onRetake = { pageId ->
                    nav.navigate(Routes.capture(docId, retakePageId = pageId))
                },
                onCleanup = { pageId -> nav.navigate(Routes.cleanup(pageId)) },
                onMarkup = { pageId -> nav.navigate(Routes.markup(pageId)) },
            )
        }
        composable(
            route = Routes.DOCUMENT,
            arguments = listOf(navArgument("documentId") { type = NavType.LongType }),
        ) { entry ->
            val docId = entry.arguments!!.getLong("documentId")
            DocumentScreen(
                documentId = docId,
                onBack = { nav.popBackStack() },
                onAddSignature = { nav.navigate(Routes.sign(docId)) },
                onAddPages = { id -> nav.navigate(Routes.capture(id)) },
                onOpenPage = { id, index -> nav.navigate(Routes.pages(id, index)) },
            )
        }
        composable(
            route = Routes.PAGES,
            arguments = listOf(
                navArgument("documentId") { type = NavType.LongType },
                navArgument("index") { type = NavType.IntType; defaultValue = 0 },
            ),
        ) {
            PageViewerScreen(
                onBack = { nav.popBackStack() },
                onAdjustCrop = { pageId -> nav.navigate(Routes.crop(pageId)) },
            )
        }
        composable(
            route = Routes.CROP,
            arguments = listOf(navArgument("pageId") { type = NavType.LongType }),
        ) {
            CropScreen(onDone = { nav.popBackStack() })
        }
        composable(
            route = Routes.CLEANUP,
            arguments = listOf(navArgument("pageId") { type = NavType.LongType }),
        ) {
            CleanupScreen(onDone = { nav.popBackStack() })
        }
        composable(
            route = Routes.MARKUP,
            arguments = listOf(navArgument("pageId") { type = NavType.LongType }),
        ) {
            MarkupScreen(onDone = { nav.popBackStack() })
        }
        composable(
            route = Routes.SIGN,
            arguments = listOf(navArgument("documentId") { type = NavType.LongType }),
        ) {
            PlaceSignatureScreen(
                onBack = { nav.popBackStack() },
                onDrawNew = { nav.navigate(Routes.SIGNATURE) },
            )
        }
        composable(Routes.SIGNATURE) {
            SignatureScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { nav.popBackStack() })
        }
    }
}
