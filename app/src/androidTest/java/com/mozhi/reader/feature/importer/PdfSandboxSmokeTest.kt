package com.mozhi.reader.feature.importer

import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.pdf.ExperimentalPdfApi
import androidx.pdf.SandboxedPdfLoader
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalPdfApi::class)
@RunWith(AndroidJUnit4::class)
class PdfSandboxSmokeTest {
    @Test
    fun pdfBoxExtractsPageTextWithoutPlatformExtensions() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        PDFBoxResourceLoader.init(context)
        val file = File(context.cacheDir, "pdf-text-smoke.pdf")
        PDDocument().use { document ->
            val page = PDPage()
            document.addPage(page)
            PDPageContentStream(document, page).use { content ->
                content.beginText()
                content.setFont(PDType1Font.HELVETICA, 12f)
                content.newLineAtOffset(72f, 720f)
                content.showText("MoRead PDF text")
                content.endText()
            }
            document.save(file)
        }
        try {
            val pages = PdfBoxPageTextExtractor(context).extract(file)
            assertEquals(1, pages.size)
            assertTrue(pages.single().text.contains("MoRead PDF text"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun isolatedPdfServiceCanOpenADocument() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        PDFBoxResourceLoader.init(context)
        val file = File(context.cacheDir, "pdf-sandbox-smoke.pdf")
        PDDocument().use { document ->
            document.addPage(PDPage())
            document.save(file)
        }
        val executor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "pdf-sandbox-smoke").apply { isDaemon = true }
        }
        try {
            val pageCount = executor.submit<Int> {
                runBlocking {
                    val descriptor = ParcelFileDescriptor.open(
                        file,
                        ParcelFileDescriptor.MODE_READ_ONLY
                    )
                    val document = SandboxedPdfLoader(context).openDocument(
                        uri = Uri.fromFile(file),
                        fileDescriptor = descriptor,
                        password = null
                    )
                    try {
                        document.pageCount
                    } finally {
                        document.close()
                    }
                }
            }.let { task ->
                try {
                    task.get(30, TimeUnit.SECONDS)
                } catch (_: TimeoutException) {
                    throw AssertionError("isolated PDF service did not respond")
                }
            }

            assertEquals(1, pageCount)
        } finally {
            executor.shutdownNow()
            file.delete()
        }
    }
}
