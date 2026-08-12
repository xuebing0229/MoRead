package com.mozhi.reader.feature.importer

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node

data class ParsedDocxImage(
    val charOffset: Int,
    val sourceName: String,
    val altText: String,
    val bytes: ByteArray
)

data class ParsedDocxChapter(
    val title: String,
    val text: String,
    val images: List<ParsedDocxImage>
)

data class ParsedDocx(
    val title: String,
    val author: String,
    val chapters: List<ParsedDocxChapter>
)

class DocxParser @Inject constructor() {
    fun parse(file: File, fallbackTitle: String): ParsedDocx {
        require(file.isFile) { "Word 文件不存在" }
        ZipFile(file).use { zip ->
            require(zip.size() <= MAX_ZIP_ENTRIES) { "DOCX 内部文件过多，暂不支持导入" }
            val document = parseXml(zip.readRequired("word/document.xml", MAX_DOCUMENT_XML_BYTES))
            val styles = zip.readOptional("word/styles.xml", MAX_SUPPORT_XML_BYTES)
                ?.let(::parseXml)
                ?.let(::readHeadingStyles)
                .orEmpty()
            val relationships = zip.readOptional(
                "word/_rels/document.xml.rels",
                MAX_SUPPORT_XML_BYTES
            )?.let(::parseXml)?.let(::readRelationships).orEmpty()
            val metadata = zip.readOptional("docProps/core.xml", MAX_SUPPORT_XML_BYTES)
                ?.let(::parseXml)
                ?.let(::readMetadata)
                ?: ("" to "")
            val mediaCache = mutableMapOf<String, ByteArray?>()
            var totalMediaBytes = 0L
            fun readMedia(entryName: String): ByteArray? = mediaCache.getOrPut(entryName) {
                zip.readOptional(entryName, MAX_IMAGE_BYTES)?.also { bytes ->
                    totalMediaBytes += bytes.size
                    require(totalMediaBytes <= MAX_TOTAL_IMAGE_BYTES) {
                        "DOCX 图片总量超过 120 MB，暂不支持导入"
                    }
                }
            }
            val title = metadata.first.ifBlank { fallbackTitle.ifBlank { "未命名文档" } }
            val body = document.documentElement.descendants("body").firstOrNull()
                ?: error("DOCX 中没有正文")
            val builders = mutableListOf(ChapterBuilder(title = "正文"))

            body.childElements().forEach { block ->
                when (block.localName) {
                    "p" -> {
                        val parsed = parseParagraph(block, relationships, ::readMedia)
                        val headingLevel = paragraphStyle(block)
                            ?.let { styles[it] ?: headingLevel(it) }
                        if (headingLevel != null && headingLevel <= MAX_CHAPTER_HEADING_LEVEL &&
                            parsed.text.isNotBlank()
                        ) {
                            val current = builders.last()
                            if (current.hasContent()) {
                                builders += ChapterBuilder(parsed.text.trim())
                            } else {
                                current.title = parsed.text.trim()
                            }
                        } else {
                            builders.last().append(parsed)
                        }
                    }
                    "tbl" -> builders.last().append(parseTable(block, relationships, ::readMedia))
                }
            }
            val chapters = builders
                .filter { it.hasContent() || it.title != "正文" }
                .mapIndexed { index, builder -> builder.build(index) }
                .ifEmpty { error("DOCX 中没有可阅读文字") }
            return ParsedDocx(title = title, author = metadata.second, chapters = chapters)
        }
    }

    private fun parseParagraph(
        paragraph: Element,
        relationships: Map<String, String>,
        readMedia: (String) -> ByteArray?
    ): ParsedBlock {
        val text = StringBuilder()
        val images = mutableListOf<RelativeImage>()
        fun visit(node: Node) {
            when (node.localName) {
                "t", "instrText" -> text.append(node.textContent)
                "tab" -> text.append('\t')
                "br", "cr" -> text.append('\n')
                "blip" -> {
                    val element = node as? Element
                    val relationId = element?.attributeByLocalName("embed")
                    val target = relationId?.let(relationships::get)
                    val entryName = target?.let(::resolveWordTarget)
                    val bytes = entryName?.let(readMedia)
                    if (bytes != null) {
                        val alt = paragraph.descendants("docPr").firstOrNull()
                            ?.let { it.getAttribute("descr").ifBlank { it.getAttribute("name") } }
                            .orEmpty()
                        images += RelativeImage(text.length, entryName, alt, bytes)
                        text.append(IMAGE_TOKEN)
                    }
                }
                else -> node.childNodes.asSequence().forEach(::visit)
            }
        }
        paragraph.childNodes.asSequence().forEach(::visit)
        val rawText = text.toString()
        val contentStart = rawText.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
        val contentEnd = rawText.indexOfLast { !it.isWhitespace() }.let { if (it < 0) 0 else it + 1 }
        val trimmedText = if (contentEnd > contentStart) {
            rawText.substring(contentStart, contentEnd)
        } else {
            ""
        }
        val prefix = if (paragraph.descendants("numPr").isNotEmpty() && trimmedText.isNotBlank()) "• " else ""
        return ParsedBlock(
            text = prefix + trimmedText,
            images = images
                .filter { it.charOffset in contentStart..contentEnd }
                .map { it.copy(charOffset = it.charOffset - contentStart + prefix.length) }
        )
    }

    private fun parseTable(
        table: Element,
        relationships: Map<String, String>,
        readMedia: (String) -> ByteArray?
    ): ParsedBlock {
        val output = StringBuilder()
        val images = mutableListOf<RelativeImage>()
        table.childElements().filter { it.localName == "tr" }.forEachIndexed { rowIndex, row ->
            if (rowIndex > 0) output.append('\n')
            row.childElements().filter { it.localName == "tc" }.forEachIndexed { cellIndex, cell ->
                if (cellIndex > 0) output.append(" | ")
                val cellBlocks = cell.childElements()
                    .filter { it.localName == "p" }
                    .map { parseParagraph(it, relationships, readMedia) }
                cellBlocks.forEachIndexed { blockIndex, block ->
                    if (blockIndex > 0) output.append(' ')
                    val base = output.length
                    output.append(block.text)
                    images += block.images.map { it.copy(charOffset = base + it.charOffset) }
                }
            }
        }
        return ParsedBlock(output.toString().trim(), images)
    }

    private fun readHeadingStyles(root: org.w3c.dom.Document): Map<String, Int> = buildMap {
        root.documentElement.descendants("style").forEach { style ->
            val id = style.attributeByLocalName("styleId") ?: return@forEach
            val name = style.descendants("name").firstOrNull()?.attributeByLocalName("val")
            headingLevel(name.orEmpty())?.let { put(id, it) }
            headingLevel(id)?.let { putIfAbsent(id, it) }
        }
    }

    private fun readRelationships(root: org.w3c.dom.Document): Map<String, String> = buildMap {
        root.documentElement.descendants("Relationship").forEach { relation ->
            if (relation.getAttribute("TargetMode").equals("External", ignoreCase = true)) return@forEach
            val id = relation.getAttribute("Id")
            val target = relation.getAttribute("Target")
            if (id.isNotBlank() && target.isNotBlank()) put(id, target)
        }
    }

    private fun readMetadata(root: org.w3c.dom.Document): Pair<String, String> {
        val title = root.documentElement.descendants("title").firstOrNull()?.textContent.orEmpty().trim()
        val creator = root.documentElement.descendants("creator").firstOrNull()?.textContent.orEmpty().trim()
        return title to creator
    }

    private fun paragraphStyle(paragraph: Element): String? = paragraph
        .descendants("pStyle")
        .firstOrNull()
        ?.attributeByLocalName("val")

    private fun headingLevel(value: String): Int? = HEADING_PATTERN
        .find(value.trim())
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()

    private fun resolveWordTarget(target: String): String? {
        val segments = (if (target.startsWith('/')) target.drop(1) else "word/$target")
            .replace('\\', '/')
            .split('/')
        val normalized = ArrayDeque<String>()
        segments.forEach { segment ->
            when (segment) {
                "", "." -> Unit
                ".." -> if (normalized.isEmpty()) return null else normalized.removeLast()
                else -> normalized.addLast(segment)
            }
        }
        return normalized.joinToString("/").takeIf { it.startsWith("word/") }
    }

    private fun parseXml(bytes: ByteArray): org.w3c.dom.Document {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            runCatching { isXIncludeAware = false }
            setExpandEntityReferences(false)
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        }
        return factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
    }

    private fun ZipFile.readRequired(name: String, limit: Int): ByteArray =
        readOptional(name, limit) ?: error("DOCX 缺少 $name，文件可能已损坏")

    private fun ZipFile.readOptional(name: String, limit: Int): ByteArray? {
        val entry = getEntry(name) ?: return null
        require(!entry.isDirectory) { "DOCX 内部结构异常" }
        require(entry.size < 0 || entry.size <= limit) { "DOCX 内部内容过大，暂不支持导入" }
        return getInputStream(entry).use { input ->
            val output = ByteArrayOutputStream(minOf(limit, DEFAULT_BUFFER_SIZE))
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= limit) { "DOCX 内部内容过大，暂不支持导入" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }

    private data class RelativeImage(
        val charOffset: Int,
        val sourceName: String,
        val altText: String,
        val bytes: ByteArray
    )

    private data class ParsedBlock(val text: String, val images: List<RelativeImage>)

    private class ChapterBuilder(var title: String) {
        private val text = StringBuilder()
        private val images = mutableListOf<ParsedDocxImage>()

        fun hasContent(): Boolean = text.isNotBlank() || images.isNotEmpty()

        fun append(block: ParsedBlock) {
            if (block.text.isBlank() && block.images.isEmpty()) return
            if (text.isNotEmpty()) text.append("\n\n")
            val base = text.length
            text.append(block.text)
            images += block.images.map { image ->
                ParsedDocxImage(
                    charOffset = base + image.charOffset,
                    sourceName = image.sourceName,
                    altText = image.altText,
                    bytes = image.bytes
                )
            }
        }

        fun build(index: Int): ParsedDocxChapter = ParsedDocxChapter(
            title = title.ifBlank { "第 ${index + 1} 章" },
            text = text.toString().trim(),
            images = images
        )
    }

    private fun Node.childElements(): Sequence<Element> = childNodes.asSequence().mapNotNull { it as? Element }

    private fun Node.descendants(localName: String): List<Element> = buildList {
        fun visit(node: Node) {
            node.childNodes.asSequence().forEach { child ->
                if (child is Element && child.localName == localName) add(child)
                visit(child)
            }
        }
        visit(this@descendants)
    }

    private fun Element.attributeByLocalName(name: String): String? = (0 until attributes.length)
        .asSequence()
        .map(attributes::item)
        .firstOrNull { it.localName == name || it.nodeName == name }
        ?.nodeValue
        ?.takeIf(String::isNotBlank)

    private fun org.w3c.dom.NodeList.asSequence(): Sequence<Node> =
        (0 until length).asSequence().map(::item)

    private companion object {
        const val IMAGE_TOKEN = "［图片］"
        const val MAX_CHAPTER_HEADING_LEVEL = 2
        const val MAX_ZIP_ENTRIES = 10_000
        const val MAX_DOCUMENT_XML_BYTES = 30 * 1024 * 1024
        const val MAX_SUPPORT_XML_BYTES = 5 * 1024 * 1024
        const val MAX_IMAGE_BYTES = 30 * 1024 * 1024
        const val MAX_TOTAL_IMAGE_BYTES = 120L * 1024 * 1024
        val HEADING_PATTERN = Regex("(?:heading|标题)\\s*([1-9])", RegexOption.IGNORE_CASE)
    }
}
