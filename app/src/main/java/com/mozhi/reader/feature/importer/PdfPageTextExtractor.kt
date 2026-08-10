package com.mozhi.reader.feature.importer

import androidx.pdf.ExperimentalPdfApi
import androidx.pdf.PdfDocument
import javax.inject.Inject

data class PdfExtractedPage(
    val pageIndex: Int,
    val text: String
)

/** Abstraction point for a future OCR fallback. The first implementation uses the PDF text layer. */
interface PdfPageTextExtractor {
    suspend fun extract(document: PdfDocument, pageIndex: Int): PdfExtractedPage
}

@OptIn(ExperimentalPdfApi::class)
class NativePdfTextExtractor @Inject constructor() : PdfPageTextExtractor {
    override suspend fun extract(document: PdfDocument, pageIndex: Int): PdfExtractedPage {
        val text = document.getPageContent(pageIndex)
            ?.textContents
            .orEmpty()
            .joinToString(separator = "\n") { it.text }
            .trim()
        return PdfExtractedPage(pageIndex = pageIndex, text = text)
    }
}
