package com.mozhi.reader.feature.importer

import android.content.Context
import android.net.Uri
import com.mozhi.reader.core.database.entity.BookEntity
import com.mozhi.reader.core.database.entity.BookSourceType
import com.mozhi.reader.core.importer.BookImportProgress
import com.mozhi.reader.core.library.BookImageInput
import com.mozhi.reader.core.library.BookMediaStore
import com.mozhi.reader.core.library.ChapterTextInput
import com.mozhi.reader.core.library.LibraryRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DocxImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val parser: DocxParser,
    private val epubGenerator: EpubGenerator,
    private val libraryRepository: LibraryRepository,
    private val mediaStore: BookMediaStore
) {
    suspend fun import(
        uri: Uri,
        displayName: String,
        onProgress: (BookImportProgress) -> Unit = {}
    ): Long {
        val key = UUID.randomUUID().toString()
        val source = File(context.cacheDir, "docx-import-$key.docx")
        val output = File(context.filesDir, "books/$key.epub")
        var insertedBookId: Long? = null
        try {
            onProgress(BookImportProgress("正在复制 Word 文档"))
            context.contentResolver.openInputStream(uri)?.buffered()?.use { input ->
                source.outputStream().buffered().use { destination ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= MAX_DOCX_BYTES) { "DOCX 文件超过 200 MB，暂不支持导入" }
                        destination.write(buffer, 0, count)
                    }
                }
            } ?: error("无法读取 Word 文件")

            onProgress(BookImportProgress("正在解析标题、表格与图片"))
            val fallbackTitle = displayName.substringBeforeLast('.').trim()
            val parsed = try {
                parser.parse(source, fallbackTitle)
            } catch (error: java.util.zip.ZipException) {
                throw IllegalStateException("DOCX 解析失败；如果是旧版 .doc，请先另存为 DOCX 或 PDF", error)
            }
            val txtChapters = parsed.chapters.mapIndexed { index, chapter ->
                TxtChapter(
                    index = index,
                    title = chapter.title,
                    content = chapter.text,
                    startOffset = 0,
                    endOffset = chapter.text.length
                )
            }
            onProgress(BookImportProgress("正在生成可阅读版本", total = txtChapters.size))
            val generated = epubGenerator.generate(
                outputFile = output,
                title = parsed.title,
                author = parsed.author,
                chapters = txtChapters
            ) { completed, total ->
                onProgress(BookImportProgress("正在生成可阅读版本", completed, total))
            }
            onProgress(BookImportProgress("正在写入书架", txtChapters.size, txtChapters.size))
            val bookId = libraryRepository.insertBook(
                book = BookEntity(
                    title = parsed.title,
                    author = parsed.author,
                    coverPath = null,
                    epubPath = generated.file.absolutePath,
                    sourceType = BookSourceType.DOCX,
                    importedAt = System.currentTimeMillis(),
                    totalChapters = generated.chapters.size
                ),
                chapters = generated.chapters
            )
            insertedBookId = bookId
            libraryRepository.materializeBookText(
                bookId = bookId,
                chapters = parsed.chapters.mapIndexed { index, chapter ->
                    ChapterTextInput(index = index, body = chapter.text)
                },
                markReady = false
            )
            mediaStore.replace(
                bookId = bookId,
                images = parsed.chapters.flatMapIndexed { chapterIndex, chapter ->
                    chapter.images.map { image ->
                        BookImageInput(
                            chapterIndex = chapterIndex,
                            charOffset = image.charOffset,
                            sourceName = image.sourceName,
                            altText = image.altText,
                            bytes = image.bytes
                        )
                    }
                }
            )
            libraryRepository.markTextReady(bookId)
            return bookId
        } catch (error: Throwable) {
            insertedBookId?.let { id ->
                libraryRepository.getBook(id)?.let { book ->
                    runCatching { libraryRepository.deleteBook(book) }
                }
            } ?: output.delete()
            throw error
        } finally {
            source.delete()
        }
    }

    private companion object {
        const val MAX_DOCX_BYTES = 200L * 1024 * 1024
    }
}
