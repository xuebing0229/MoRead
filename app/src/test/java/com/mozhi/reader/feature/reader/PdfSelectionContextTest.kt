package com.mozhi.reader.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfSelectionContextTest {
    @Test
    fun `selection context keeps nearby text`() {
        val page = "前".repeat(1_200) + "目标句" + "后".repeat(1_200)

        val context = buildPdfSelectionContext(page, "目标句")

        assertEquals(1_803, context.length)
        assertTrue(context.startsWith("前"))
        assertTrue(context.contains("目标句"))
        assertTrue(context.endsWith("后"))
    }

    @Test
    fun `missing text layer falls back to selected text`() {
        assertEquals("选区", buildPdfSelectionContext("", "选区"))
    }

    @Test
    fun `unmatched selection falls back to first two thousand page chars`() {
        val page = "页".repeat(2_500)
        assertEquals(2_000, buildPdfSelectionContext(page, "不存在").length)
    }
}
