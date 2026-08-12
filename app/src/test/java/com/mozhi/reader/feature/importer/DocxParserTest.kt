package com.mozhi.reader.feature.importer

import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DocxParserTest {
    @Test
    fun parsesHeadingsListsTablesMetadataAndImages() {
        val file = Files.createTempFile("moread-docx-", ".docx").toFile()
        try {
            ZipOutputStream(file.outputStream()).use { zip ->
                zip.write("word/document.xml", DOCUMENT_XML)
                zip.write("word/styles.xml", STYLES_XML)
                zip.write("word/_rels/document.xml.rels", RELATIONSHIPS_XML)
                zip.write("docProps/core.xml", CORE_XML)
                zip.putNextEntry(ZipEntry("word/media/image1.png"))
                zip.write(PNG_BYTES)
                zip.closeEntry()
            }

            val parsed = DocxParser().parse(file, "fallback")

            assertEquals("测试教材", parsed.title)
            assertEquals("作者甲", parsed.author)
            assertEquals(listOf("第一章", "第二节"), parsed.chapters.map { it.title })
            assertTrue(parsed.chapters[0].text.contains("• 项目［图片］"))
            assertTrue(parsed.chapters[0].text.contains("甲 | 乙"))
            assertEquals(1, parsed.chapters[0].images.size)
            assertEquals("示意图", parsed.chapters[0].images.single().altText)
            assertEquals(PNG_BYTES.toList(), parsed.chapters[0].images.single().bytes.toList())
            assertTrue(parsed.chapters[1].text.contains("第二节正文"))
        } finally {
            file.delete()
        }
    }

    private fun ZipOutputStream.write(path: String, text: String) {
        putNextEntry(ZipEntry(path))
        write(text.toByteArray())
        closeEntry()
    }

    private companion object {
        val PNG_BYTES = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3
        )
        val DOCUMENT_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"
                xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"
                xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"
                xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing">
              <w:body>
                <w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>第一章</w:t></w:r></w:p>
                <w:p><w:pPr><w:numPr/></w:pPr><w:r><w:t>项目</w:t></w:r><w:r><w:drawing><wp:docPr name="图1" descr="示意图"/><a:blip r:embed="rId5"/></w:drawing></w:r></w:p>
                <w:tbl><w:tr><w:tc><w:p><w:r><w:t>甲</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>乙</w:t></w:r></w:p></w:tc></w:tr></w:tbl>
                <w:p><w:pPr><w:pStyle w:val="Heading2"/></w:pPr><w:r><w:t>第二节</w:t></w:r></w:p>
                <w:p><w:r><w:t>第二节正文</w:t></w:r></w:p>
              </w:body>
            </w:document>
        """.trimIndent()
        val STYLES_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:style w:type="paragraph" w:styleId="Heading1"><w:name w:val="heading 1"/></w:style>
              <w:style w:type="paragraph" w:styleId="Heading2"><w:name w:val="heading 2"/></w:style>
            </w:styles>
        """.trimIndent()
        val RELATIONSHIPS_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
              <Relationship Id="rId5" Type="image" Target="media/image1.png"/>
            </Relationships>
        """.trimIndent()
        val CORE_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties"
                xmlns:dc="http://purl.org/dc/elements/1.1/">
              <dc:title>测试教材</dc:title><dc:creator>作者甲</dc:creator>
            </cp:coreProperties>
        """.trimIndent()
    }
}
