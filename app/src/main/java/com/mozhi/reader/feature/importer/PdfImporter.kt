package com.mozhi.reader.feature.importer

import android.content.Context
import android.net.Uri
import com.mozhi.reader.core.database.entity.BookEntity
import com.mozhi.reader.core.database.entity.BookSourceType
import com.mozhi.reader.core.importer.BookImportProgress
import com.mozhi.reader.core.library.ChapterDraft
import com.mozhi.reader.core.library.ChapterTextInput
import com.mozhi.reader.core.library.LibraryRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

@Singleton
class PdfImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val libraryRepository: LibraryRepository,
    private val textExtractor: PdfPageTextExtractor,
    private val legacyNormalizer: LegacyPdfNormalizer
) {
    suspend fun import(
        uri: Uri,
        displayName: String,
        onProgress: (BookImportProgress) -> Unit = {}
    ): Long {
        val target = File(booksDirectory(), "${UUID.randomUUID()}.pdf")
        onProgress(BookImportProgress("正在复制 PDF"))
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().buffered().use(input::copyTo)
        } ?: error("无法读取 PDF 文件")

        var insertedBookId: Long? = null
        try {
            if (legacyNormalizer.needsNormalization(target)) {
                onProgress(BookImportProgress("正在兼容旧版加密 PDF"))
                legacyNormalizer.normalize(target)
            }
            onProgress(BookImportProgress("正在读取 PDF 页面"))
            val pages = textExtractor.extract(target, onProgress)
            val pageCount = pages.size
            require(pageCount > 0) { "PDF 中没有可阅读页面" }
            val chapters = pages.map { page ->
                ChapterDraft(
                    index = page.pageIndex,
                    title = "第 ${page.pageIndex + 1} 页",
                    href = "pdf-page://${page.pageIndex + 1}",
                    charCount = page.text.length
                )
            }
            val title = displayName.substringBeforeLast('.').trim().ifBlank { "未命名教材" }
            onProgress(BookImportProgress("正在写入书架", pageCount, pageCount))
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
        }
    }

    private fun booksDirectory(): File = File(context.filesDir, "books").apply { mkdirs() }
}

internal suspend fun extractPdfPages(
    pageCount: Int,
    pageTimeoutMs: Long,
    onProgress: (BookImportProgress) -> Unit = {},
    extract: suspend (pageIndex: Int) -> PdfExtractedPage
): List<PdfExtractedPage> = (0 until pageCount).map { pageIndex ->
    onProgress(
        BookImportProgress(
            message = "正在提取第 ${pageIndex + 1}/$pageCount 页文字",
            completed = pageIndex,
            total = pageCount
        )
    )
    try {
        withTimeout(pageTimeoutMs) { extract(pageIndex) }
    } catch (_: TimeoutCancellationException) {
        // 文字层异常不应阻止用户阅读原版页面；该页保留为空。
        PdfExtractedPage(pageIndex = pageIndex, text = "")
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        // 部分旧 PDF 只有个别页的文字对象损坏；原版页面仍可正常渲染。
        PdfExtractedPage(pageIndex = pageIndex, text = "")
    }
}
