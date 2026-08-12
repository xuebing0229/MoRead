package com.mozhi.reader.feature.importer

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class PdfImporterTest {
    @Test
    fun extractsEveryPageAndReportsProgress() = runTest {
        val progress = mutableListOf<String>()

        val pages = extractPdfPages(
            pageCount = 3,
            pageTimeoutMs = 1_000,
            onProgress = { progress += it.message }
        ) { index ->
            PdfExtractedPage(index, "page-$index")
        }

        assertEquals(listOf("page-0", "page-1", "page-2"), pages.map { it.text })
        assertEquals(
            listOf("正在提取第 1/3 页文字", "正在提取第 2/3 页文字", "正在提取第 3/3 页文字"),
            progress
        )
    }

    @Test
    fun skipsTimedOutOrBrokenTextLayers() = runTest {
        val pages = extractPdfPages(pageCount = 3, pageTimeoutMs = 50) { index ->
            when (index) {
                0 -> PdfExtractedPage(index, "ok")
                1 -> error("broken text object")
                else -> {
                    delay(100)
                    PdfExtractedPage(index, "too late")
                }
            }
        }

        assertEquals(listOf("ok", "", ""), pages.map { it.text })
    }

    @Test
    fun cancellationStillStopsTheWholeImport() = runTest {
        try {
            extractPdfPages(pageCount = 1, pageTimeoutMs = 1_000) {
                throw CancellationException("user cancelled")
            }
            fail("Expected cancellation to propagate")
        } catch (_: CancellationException) {
            // Expected: the cancel button must stop the import instead of creating a blank page.
        }
    }
}
