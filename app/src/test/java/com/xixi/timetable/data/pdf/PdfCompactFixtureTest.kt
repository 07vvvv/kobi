package com.xixi.timetable.data.pdf

import com.xixi.timetable.data.importer.TextImporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 紧凑排版 fixture（`timetable-compact-fixture.pdf`）的重建回归。
 *
 * 这个 fixture 复刻真实教务课表 PDF 里「只有按表格列切开才认得出来」的结构，与
 * [PdfTimetableImporterTest] 的基础结构夹具互补：
 *
 * - 正文 8~10pt 小字号、行距紧到相邻两格的行会挨在一起；
 * - `/(1000000002)00000002` 这类 21 字符课程号几乎铺满整列宽（106pt）；
 * - 课程名跨行（`★中级财务会计模拟实` + `验`）；
 * - 同一个格子里两门课（`★财务管理` 与 `★计量经济学` 共享节 7-8 的合并格）；
 * - 页脚备注行横跨两个星期列。
 *
 * 这些结构曾经让重建结果出现字符级乱码（`/(2026★20计271量)08经42济000学0.05/宋`）、
 * 课程名被截断成单字、以及页脚备注被当成课程入库。
 */
class PdfCompactFixtureTest {

    private fun fixture(): ByteArray {
        val stream = javaClass.getResourceAsStream("/timetable-compact-fixture.pdf")
            ?: error("测试夹具缺失：app/src/test/resources/timetable-compact-fixture.pdf")
        return stream.use { it.readBytes() }
    }

    private val imported: PdfImportResult by lazy { PdfTimetableImporter.parse(fixture()) }

    @Test
    fun `紧凑排版的课表能全部重建出来`() {
        assertNull("紧凑排版不应报失败", imported.failure)
        assertEquals(
            listOf(
                "体育,甘老师,A301,周一,第3-3节,7-17周",
                "中级财务会计模拟实验,杨明,2117,周二,第1-2节,1-4,6-17周",
                "财务管理,宋满,2413,周三,第7-8节,2-4周",
                "计量经济学,庄舟,3310,周三,第7-8节,5-9周",
                "“橙爱橙家”宿舍清洁及安全防护项目,赵亮,4119,周六,第3-3节,14周",
                "大学外语A,赖声,3313,周六,第4-4节,5周"
            ),
            imported.lines
        )
    }

    @Test
    fun `页脚的公选课程备注不得混进课程行`() {
        assertTrue(
            "页脚备注行必须整行剔除，否则按列切开后的碎片会被当成课程名",
            imported.lines.none { it.contains("公选") }
        )
        assertTrue(
            "页脚备注里的人名不该出现在任何字段里",
            imported.lines.none { it.contains("吴老师") }
        )
    }

    @Test
    fun `每一条重建结果都能被 TextImporter 解析`() {
        assertTrue("紧凑夹具必须重建出课程", imported.lines.isNotEmpty())
        for (line in imported.lines) {
            val parsed = TextImporter.parse(line)
            assertTrue("解析失败：$line -> ${parsed.errors}", parsed.errors.isEmpty())
            assertEquals("$line 应解析出恰好一条", 1, parsed.items.size)
        }
    }
}
