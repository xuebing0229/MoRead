package com.mozhi.reader.core.importer

import android.net.Uri

sealed interface PreparedImport {
    data class PreviewReady(val sessionId: String) : PreparedImport
    data class BookImported(val bookId: Long) : PreparedImport
}

data class BookImportProgress(
    val message: String,
    val completed: Int = 0,
    val total: Int = 0
)

interface BookImportGateway {
    suspend fun prepare(
        uri: Uri,
        onProgress: (BookImportProgress) -> Unit = {}
    ): PreparedImport
    suspend fun backfillMissingCovers()
}
