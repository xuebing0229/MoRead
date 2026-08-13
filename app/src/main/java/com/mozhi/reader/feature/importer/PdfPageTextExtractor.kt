package com.mozhi.reader.feature.importer

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.mozhi.reader.core.importer.BookImportProgress
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject

data class PdfExtractedPage(
    val pageIndex: Int,
    val text: String
)

interface PdfPageTextExtractor {
    suspend fun extract(
        file: File,
        onProgress: (BookImportProgress) -> Unit = {}
    ): List<PdfExtractedPage>
}

/**
 * AndroidX delegates text extraction to platform PdfRenderer APIs that require S extension 13.
 * PDFBox keeps book import and RAG text available on every Android version supported by MoRead.
 */
class PdfBoxPageTextExtractor @Inject constructor(
    @ApplicationContext context: Context
) : PdfPageTextExtractor {
    init {
        PDFBoxResourceLoader.init(context)
    }

    override suspend fun extract(
        file: File,
        onProgress: (BookImportProgress) -> Unit
    ): List<PdfExtractedPage> =
        PDDocument.load(file, "").use { document ->
            val stripper = PDFTextStripper().apply {
                sortByPosition = true
            }
            extractPdfPages(
                pageCount = document.numberOfPages,
                pageTimeoutMs = PDF_PAGE_TEXT_TIMEOUT_MS,
                onProgress = onProgress
            ) { pageIndex ->
                stripper.startPage = pageIndex + 1
                stripper.endPage = pageIndex + 1
                PdfExtractedPage(
                    pageIndex = pageIndex,
                    text = stripper.getText(document).trim()
                )
            }
        }
}

private const val PDF_PAGE_TEXT_TIMEOUT_MS = 10_000L
