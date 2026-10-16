package com.xixi.timetable.data.pdf

import com.xixi.timetable.data.importer.TextImporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * PDF 课表导入的端到端 JVM 单元测试（本轮最关键的验收）。
 *
 * 输入是 `src/test/resources/timetable-fixture.pdf`：一个**完全虚构**的课表 PDF，
 * 结构与真实教务系统导出的打印版 PDF 同构。其中有两处最要紧的特征：
 *
 * 1. 一个格子里放四行元数据（课程名 / 课程号 / 学分教师 / 周次地点），四行文字的总高
 *    **超过一节课的行高**，会向下溢出格子 —— 所以「按块的纵向范围去套节次带」必然把
 *    单节课误判成连堂；
 * 2. 星期二第 1-2 节是**合并单元格**，中间那条横线不画。连堂跨度只能由「哪条边界线存在」
 *    判定，这正是 [PdfTimetableImporter] 要从表格线段里还原的信息。
 *
 * 因此本测试同时锁住两件事：解析链路能把课全找出来，且节次判定是**按几何事实**而不是
 * 按文字高度猜的。
 */
class PdfTimetableImporterTest {

    private fun fixture(): ByteArray {
        val stream = javaClass.getResourceAsStream("/timetable-fixture.pdf")
            ?: error("测试夹具缺失：app/src/test/resources/timetable-fixture.pdf")
        return stream.use { it.readBytes() }
    }

    private val imported: PdfImportResult by lazy { PdfTimetableImporter.parse(fixture()) }

    private fun lineOf(name: String): String {
        val line = imported.lines.firstOrNull { it.contains(name) }
        assertNotNull("重建结果里没有「$name」：${imported.lines}", line)
        return line!!
    }

    // ===================== 1. 能全部识别 =====================

    @Test
    fun `能从课表 PDF 重建出全部三门课程`() {
        assertNull("不应有失败原因：${imported.failure}", imported.failure)
        assertEquals("应重建出 3 个时段，实际：${imported.lines}", 3, imported.lines.size)
    }

    @Test
    fun `重建结果每一行都能被 TextImporter 成功解析`() {
        // 这是 [TimetableLayoutParser.Result.lines] 的语义保证：非空 ⟺ 每行都能解析
        val parsed = TextImporter.parse(imported.lines.joinToString("\n"))
        assertEquals("存在无法识别的行：${parsed.errors}", 0, parsed.errors.size)
        assertEquals(imported.lines.size, parsed.items.size)
    }

    // ===================== 2. 节次必须按格子几何判定 =====================

    @Test
    fun `合并单元格里的连堂课程被判成两节`() {
        // 星期二第 1-2 节合并成一格；文字向下溢出到第 3 节附近，若按块高推断会被算成更多节
        val line = lineOf("高等数学")
        assertTrue("应落在周二：$line", line.contains(",周二,"))
        assertTrue("应为第 1-2 节：$line", line.contains(",第1-2节,"))
    }

    @Test
    fun `单节格子的课程不会被文字溢出带成连堂`() {
        val physics = lineOf("大学物理")
        assertTrue("应为第 3-3 节：$physics", physics.contains(",第3-3节,"))

        val data = lineOf("数据结构")
        assertTrue("应为第 5-5 节：$data", data.contains(",第5-5节,"))
    }

    // ===================== 3. 名称 / 教师 / 地点 / 星期 =====================

    @Test
    fun `课程名教师地点与星期都被正确归类`() {
        val math = lineOf("高等数学")
        assertTrue("应含教师张三：$math", math.contains("张三"))
        assertTrue("应含地点 3301：$math", math.contains("3301"))

        val physics = lineOf("大学物理")
        assertTrue("应含教师李四：$physics", physics.contains("李四"))
        assertTrue("应含地点 2105：$physics", physics.contains("2105"))

        val data = lineOf("数据结构")
        assertTrue("应含教师王五：$data", data.contains("王五"))
        assertTrue("应含地点 A301：$data", data.contains("A301"))
        assertTrue("应落在周四：$data", data.contains(",周四,"))
    }

    @Test
    fun `周次被解析成周次字段而不是被当成课程名或地点`() {
        val math = lineOf("高等数学")
        assertTrue("应含周次信息：$math", math.contains("周"))
        assertTrue("课程名不应带方括号或周次字样：$math", !math.startsWith("【"))
    }

    // ===================== 4. 失败路径必须可读、不抛异常 =====================

    @Test
    fun `非 PDF 字节返回可读失败原因而不是抛异常`() {
        val result = PdfTimetableImporter.parse("这不是一个 PDF 文件".toByteArray(Charsets.UTF_8))
        assertEquals(0, result.lines.size)
        assertNotNull("必须给出可读失败原因", result.failure)
        assertTrue("失败原因不应为空", result.failure!!.isNotBlank())
    }

    @Test
    fun `空字节输入不崩溃且给出失败原因`() {
        val result = PdfTimetableImporter.parse(ByteArray(0))
        assertEquals(0, result.lines.size)
        assertNotNull("必须给出可读失败原因", result.failure)
    }

    @Test
    fun `解析结果的元信息如实反映来源规模`() {
        val result = imported
        assertTrue("来源文本项数应为 130 左右，实际 ${result.sourceLineCount}", result.sourceLineCount >= 100)
        assertEquals("网格模式不应丢弃行", 0, result.droppedLineCount)
    }
}
