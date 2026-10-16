package com.xixi.timetable.data.pdf

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * PDF 底层解析器的 JVM 单元测试。
 *
 * 坐标与线段必须如实解出：课表重建**完全依赖**它们 ——
 * 文字坐标决定「哪门课在哪一列、哪一行」，表格描边线段决定「连堂占几节」。
 *
 * 夹具是 `src/test/resources/timetable-fixture.pdf`：完全虚构的课表，结构与真实
 * 教务系统导出的打印版 PDF 同构（横向 A4、经典 xref 表、FlateDecode 内容流、
 * Type0 + UniGB-UCS2-H 字体、逐字 Tm 定位、表格用 m/l+S 描边）。
 */
class PdfTextExtractorTest {

    private fun fixture(): ByteArray {
        val stream = javaClass.getResourceAsStream("/timetable-fixture.pdf")
            ?: error("测试夹具缺失：app/src/test/resources/timetable-fixture.pdf")
        return stream.use { it.readBytes() }
    }

    @Test
    fun `解析出单页与正确的页面尺寸`() {
        val pages = PdfTextExtractor.extractAllPages(fixture())
        assertEquals(1, pages.size)
        assertEquals(841.0, pages[0].width.toDouble(), 0.5)
        assertEquals(595.0, pages[0].height.toDouble(), 0.5)
    }

    @Test
    fun `逐字文本项与坐标都被解出`() {
        val page = PdfTextExtractor.extractAllPages(fixture())[0]
        assertEquals("夹具里共 130 个逐字文本项", 130, page.items.size)
        // 三个课程名行都以 ★ 开头
        assertEquals(3, page.items.count { it.text == "★" })
        // 课程名首个汉字的基线位置：★高等数学 那一行的基线是 y=509
        val gao = page.items.firstOrNull { it.text == "高" }
        assertNotNull("没有解出「高」这个字", gao)
        assertEquals(509.0, gao!!.y.toDouble(), 0.6)
    }

    @Test
    fun `表格描边线段被解出且水平竖直都有`() {
        val page = PdfTextExtractor.extractAllPages(fixture())[0]
        assertTrue("描边线段太少：${page.segments.size}", page.segments.size >= 40)

        val horizontal = page.segments.count { abs(it.y1 - it.y2) < 0.5f }
        val vertical = page.segments.count { abs(it.x1 - it.x2) < 0.5f }
        assertTrue("应解出水平网格线", horizontal > 0)
        assertTrue("应解出竖直网格线", vertical > 0)

        // 课表格子的水平边长度等于一个列宽（176 - 70 = 106pt）
        val hasColumnWideLine = page.segments.any {
            abs(it.y1 - it.y2) < 0.5f && abs(abs(it.x2 - it.x1) - 106f) < 1f
        }
        assertTrue("应解出长度约 106pt 的格子边", hasColumnWideLine)
    }

    @Test
    fun `非 PDF 字节抛 PdfParseException 而不是实现细节异常`() {
        try {
            PdfTextExtractor.extractAllPages("这不是一个 PDF 文件".toByteArray(Charsets.UTF_8))
            fail("应当抛出 PdfParseException")
        } catch (expected: PdfParseException) {
            assertTrue("异常信息不应为空", (expected.message ?: "").isNotEmpty())
        }
    }

    @Test
    fun `空字节输入同样以 PdfParseException 收场`() {
        try {
            PdfTextExtractor.extractAllPages(ByteArray(0))
            fail("应当抛出 PdfParseException")
        } catch (expected: PdfParseException) {
            assertTrue("异常信息不应为空", (expected.message ?: "").isNotEmpty())
        }
    }
}
