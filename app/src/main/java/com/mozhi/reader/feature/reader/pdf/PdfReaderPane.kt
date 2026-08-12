package com.mozhi.reader.feature.reader.pdf

import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.SparseArray
import android.graphics.RectF
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.pdf.ExperimentalPdfApi
import androidx.pdf.PdfDocument
import androidx.pdf.PdfPasswordException
import androidx.pdf.SandboxedPdfLoader
import androidx.pdf.selection.ContextMenuComponent
import androidx.pdf.selection.SelectionMenuComponent
import androidx.pdf.selection.model.TextSelection
import androidx.pdf.view.PdfView
import com.mozhi.reader.ai.prompt.SelectionAiAction
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

@OptIn(ExperimentalPdfApi::class)
@Composable
fun PdfReaderPane(
    filePath: String,
    initialPage: Int,
    requestedPage: Int,
    enabled: Boolean,
    onPageChanged: (Int) -> Unit,
    onAiAction: (SelectionAiAction, String, Int) -> Unit,
    onNotice: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val latestOnPageChanged by rememberUpdatedState(onPageChanged)
    val latestOnAiAction by rememberUpdatedState(onAiAction)
    var document by remember(filePath) { mutableStateOf<PdfDocument?>(null) }
    var loadError by remember(filePath) { mutableStateOf<String?>(null) }
    var currentSelection by remember { mutableStateOf<TextSelection?>(null) }
    var lastRequestedPage by remember(filePath) { mutableStateOf(initialPage) }

    LaunchedEffect(filePath) {
        loadError = null
        try {
            document = withTimeout(PDF_READER_OPEN_TIMEOUT_MS) {
                openLocalPdf(context, File(filePath))
            }
        } catch (_: PdfPasswordException) {
            loadError = "PDF 设置了打开密码，暂时无法阅读"
        } catch (_: TimeoutCancellationException) {
            loadError = "PDF 打开超时，请返回后重试"
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            loadError = error.message ?: "PDF 打开失败"
        }
    }
    DisposableEffect(document) {
        val opened = document
        onDispose { opened?.close() }
    }

    Box(modifier = modifier) {
        val opened = document
        when {
            loadError != null -> Text(
                text = loadError.orEmpty(),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.align(Alignment.Center)
            )
            opened == null -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            else -> AndroidView(
                factory = { viewContext ->
                    val pdfView = PdfView(viewContext)
                    val restoredPage = initialPage.coerceIn(0, opened.pageCount - 1)
                    var waitingForInitialPage = true
                    pdfView.isEnabled = enabled
                    pdfView.addOnFirstContentLoadListener {
                        pdfView.scrollToPage(restoredPage)
                    }
                    pdfView.addOnViewportChangedListener(
                        object : PdfView.OnViewportChangedListener {
                            override fun onViewportChanged(
                                firstVisiblePage: Int,
                                visiblePagesCount: Int,
                                pageLocations: SparseArray<RectF>,
                                zoomLevel: Float
                            ) {
                                if (firstVisiblePage < 0) return
                                if (waitingForInitialPage) {
                                    if (firstVisiblePage != restoredPage) return
                                    waitingForInitialPage = false
                                }
                                latestOnPageChanged(firstVisiblePage)
                            }
                        }
                    )
                    pdfView.addOnSelectionChangedListener(
                        object : PdfView.OnSelectionChangedListener {
                            override fun onSelectionChanged(newSelection: androidx.pdf.selection.Selection?) {
                                currentSelection = newSelection as? TextSelection
                            }
                        }
                    )
                    pdfView.addSelectionMenuItemPreparer(
                        object : PdfView.SelectionMenuItemPreparer {
                            override fun onPrepareSelectionMenuItems(
                                components: MutableList<ContextMenuComponent>
                            ) {
                                SelectionAiAction.entries.forEach { action ->
                                    components += SelectionMenuComponent(
                                        key = PdfAiMenuKey(action),
                                        label = action.label,
                                        contentDescription = "用 AI ${action.label}所选文字",
                                        onClick = {
                                            val selection = currentSelection
                                            val page = selection?.bounds?.firstOrNull()?.pageNum
                                            val text = selection?.text?.toString()?.trim().orEmpty()
                                            if (page != null && text.isNotBlank()) {
                                                latestOnAiAction(action, text, page)
                                                pdfView.clearCurrentSelection()
                                            } else {
                                                onNotice("这一页没有可选择的文字层")
                                            }
                                            close()
                                        }
                                    )
                                }
                            }
                        }
                    )
                    pdfView.pdfDocument = opened
                    pdfView
                },
                update = { pdfView ->
                    pdfView.isEnabled = enabled
                    if (pdfView.pdfDocument !== opened) pdfView.pdfDocument = opened
                    val target = requestedPage.coerceIn(0, opened.pageCount - 1)
                    if (target != lastRequestedPage) {
                        lastRequestedPage = target
                        pdfView.scrollToPage(target)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

private data class PdfAiMenuKey(val action: SelectionAiAction)

@OptIn(ExperimentalPdfApi::class)
private suspend fun openLocalPdf(context: android.content.Context, file: File): PdfDocument {
    require(file.isFile) { "PDF 文件不存在" }
    val descriptor = withContext(Dispatchers.IO) {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }
    return try {
        SandboxedPdfLoader(context).openDocument(
            uri = Uri.fromFile(file),
            fileDescriptor = descriptor,
            password = ""
        )
    } catch (error: Throwable) {
        descriptor.close()
        throw error
    }
}

private const val PDF_READER_OPEN_TIMEOUT_MS = 20_000L
