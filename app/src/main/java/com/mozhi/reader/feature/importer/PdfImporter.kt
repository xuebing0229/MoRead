package com.mozhi.reader.feature.importer

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.pdf.ExperimentalPdfApi
import androidx.pdf.PdfDocument
import androidx.pdf.SandboxedPdfLoader
import com.mozhi.reader.core.database.entity.BookEntity
import com.mozhi.reader.core.database.entity.BookSourceType
import com.mozhi.reader.core.library.ChapterDraft
import com.mozhi.reader.core.library.ChapterTextInput
import com.mozhi.reader.core.library.LibraryRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@OptIn(ExperimentalPdfApi::class)
@Singleton
class PdfImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val libraryRepository: LibraryRepository,
    private val textExtractor: PdfPageTextExtractor
) {
    suspend fun import(uri: Uri, displayName: String): Long {
        val target = File(booksDirectory(), "${UUID.randomUUID()}.pdf")
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().buffered().use(input::copyTo)
        } ?: error("无法读取 PDF 文件")

        var document: PdfDocument? = null
        var insertedBookId: Long? = null
        try {
            document = openLocalDocument(target)
            val pageCount = document.pageCount
            require(pageCount > 0) { "PDF 中没有可阅读页面" }
            val pages = (0 until pageCount).map { pageIndex ->
                textExtractor.extract(document, pageIndex)
            }
            val chapters = pages.map { page ->
                ChapterDraft(
                    index = page.pageIndex,
                    title = "第 ${page.pageIndex + 1} 页",
                    href = "pdf-page://${page.pageIndex + 1}",
                    charCount = page.text.length
                )
            }
            val title = displayName.substringBeforeLast('.').trim().ifBlank { "未命名教材" }
            val bookId = libraryRepository.insertBook(
                book = BookEntity(
                    title = title,
                    author = "",
                    coverPath = null,
                    epubPath = target.absolutePath,
                    sourceType = BookSourceType.PDF,
                    importedAt = System.currentTimeMillis(),
                    totalChapters = pageCount
                ),
                chapters = chapters
            )
            insertedBookId = bookId
            libraryRepository.materializeBookText(
                bookId = bookId,
                chapters = pages.map { ChapterTextInput(index = it.pageIndex, body = it.text) }
            )
            return bookId
        } catch (error: Throwable) {
            insertedBookId?.let { id ->
                libraryRepository.getBook(id)?.let { book ->
                    runCatching { libraryRepository.deleteBook(book) }
                }
            }
            target.delete()
            throw error
        } finally {
            document?.close()
        }
    }

    private suspend fun openLocalDocument(file: File): PdfDocument {
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        return try {
            SandboxedPdfLoader(context).openDocument(
                uri = Uri.fromFile(file),
                fileDescriptor = descriptor
            )
        } catch (error: Throwable) {
            descriptor.close()
            throw error
        }
    }

    private fun booksDirectory(): File = File(context.filesDir, "books").apply { mkdirs() }
}
