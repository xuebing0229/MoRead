package com.mozhi.reader.feature.importer

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyPdfNormalizerTest {
    @Test
    fun `detects encryption marker in pdf trailer`() {
        val file = temporaryPdf("%PDF-1.4\nbody\ntrailer << /Encrypt 12 0 R >>\n%%EOF")
        try {
            assertTrue(hasPdfEncryptionMarker(file))
        } finally {
            file.delete()
        }
    }

    @Test
    fun `ignores ordinary unencrypted pdf trailer`() {
        val file = temporaryPdf("%PDF-1.7\nbody\ntrailer << /Root 1 0 R >>\n%%EOF")
        try {
            assertFalse(hasPdfEncryptionMarker(file))
        } finally {
            file.delete()
        }
    }

    private fun temporaryPdf(content: String): File =
        File.createTempFile("moread-pdf-marker-", ".pdf").apply { writeText(content) }
}
