package com.mozhi.reader.feature.importer

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AndroidX PDF 对早期 Acrobat 生成的 RC4 权限加密文件兼容不稳定。
 * 导入时只对带 /Encrypt trailer 的文件做一次本地规范化，移除权限加密；页面内容、
 * 排版和文字层保持为 PDF，不经过图片化或 OCR。真正需要打开密码的文件仍明确拒绝。
 */
@Singleton
class LegacyPdfNormalizer @Inject constructor(
    @ApplicationContext context: Context
) {
    init {
        PDFBoxResourceLoader.init(context)
    }

    fun needsNormalization(file: File): Boolean = hasPdfEncryptionMarker(file)

    fun normalize(file: File) {
        require(file.isFile) { "PDF 文件不存在" }
        val normalized = File(file.parentFile, "${file.nameWithoutExtension}.normalized.pdf")
        try {
            PDDocument.load(file, "").use { document ->
                if (!document.isEncrypted) return
                document.setAllSecurityToBeRemoved(true)
                document.save(normalized)
            }
            require(normalized.isFile && normalized.length() > 0L) { "旧版 PDF 兼容处理失败" }
            Files.move(
                normalized.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: InvalidPasswordException) {
            throw IllegalStateException("PDF 设置了打开密码，请先移除密码后再导入")
        } finally {
            normalized.delete()
        }
    }
}

internal fun hasPdfEncryptionMarker(file: File): Boolean {
    if (!file.isFile || file.length() <= 0L) return false
    RandomAccessFile(file, "r").use { input ->
        val count = minOf(input.length(), PDF_TRAILER_SCAN_BYTES).toInt()
        input.seek(input.length() - count)
        val tail = ByteArray(count)
        input.readFully(tail)
        return ENCRYPT_MARKER.containsMatchIn(String(tail, StandardCharsets.ISO_8859_1))
    }
}

private val ENCRYPT_MARKER = Regex("/Encrypt\\b")
private const val PDF_TRAILER_SCAN_BYTES = 4L * 1024 * 1024
