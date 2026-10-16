package com.xixi.timetable.data.layout

import com.xixi.timetable.data.importer.TextImporter
import com.xixi.timetable.data.week.OddEven
import com.xixi.timetable.data.week.WeekPatterns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TimetableLayoutParser 的 JVM 单元测试（契约 §10.5）。
 *
 * 布局常量贴近真实课表：列宽约 150、行高约 57、表头 y≈88（均为内部坐标尺度）。
 * 所有姓名/学号均为虚构，不含任何真实个人信息。
 */
class TimetableLayoutParserTest {

    private val columnCenters = intArrayOf(250, 400, 550, 700, 850, 1000, 1150)
    private val weekdayNames = listOf("星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日")

    private val headerTop = 78
    private val headerBottom = 98
    private val firstRowCenter = 130
    private val rowHeight = 57
    private val labelCenterX = 60
    private val cellTopOffset = 26
    private val cellLineStep = 9
    private val cellLineHeight = 8

    // ===================== 坐标构造 =====================

    private fun headerLine(index: Int): PositionedText {
        val cx = columnCenters[index]
        return PositionedText(weekdayNames[index], cx - 40, headerTop, cx + 40, headerBottom)
    }

    private fun sectionLabel(section: Int): PositionedText {
        val cy = firstRowCenter + rowHeight * (section - 1)
        return PositionedText(section.toString(), labelCenterX - 10, cy - 8, labelCenterX + 10, cy + 8)
    }

    /** 第 `column` 列、第 `section` 节格子内的第 `order` 行文字 */
    private fun cellLine(text: String, column: Int, section: Int, order: Int): PositionedText {
        val cx = columnCenters[column]
        val cellTop = firstRowCenter + rowHeight * (section - 1) - cellTopOffset
        val top = cellTop + order * cellLineStep
        return PositionedText(text, cx - 70, top, cx + 70, top + cellLineHeight)
    }

    /** 硬验收：产出的每一行都必须能被 TextImporter 成功解析 */
    private fun assertAllParseable(result: TimetableLayoutParser.Result) {
        if (result.lines.isEmpty()) return
        val parsed = TextImporter.parse(result.lines.joinToString("\n"))
        assertEquals("存在无法识别的行：${parsed.errors}", 0, parsed.errors.size)
        assertEquals("解析条数与产出行数不一致", result.lines.size, parsed.items.size)
    }

    // ===================== ① 标准网格 =====================

    @Test
    fun `标准网格按列与节次还原课程`() {
        val lines = ArrayList<PositionedText>()
        lines += (0..6).map { headerLine(it) }
        lines += (1..4).map { sectionLabel(it) }

        // 周三第 1 节
        lines += cellLine("★高等数学", 2, 1, 0)
        lines += cellLine("(0000000000)000000000", 2, 1, 1)
        lines += cellLine("2.0/张三", 2, 1, 2)
        lines += cellLine("【1-16周】,3301", 2, 1, 3)

        // 周五第 3 节
        lines += cellLine("★大学英语", 4, 3, 0)
        lines += cellLine("(0000000000)000000001", 4, 3, 1)
        lines += cellLine("2.0/李四", 4, 3, 2)
        lines += cellLine("【2-8周】,A101", 4, 3, 3)

        val result = TimetableLayoutParser.parse(lines)

        assertEquals(TimetableLayoutParser.Mode.GRID, result.mode)
        assertEquals(lines.size, result.sourceLineCount)
        assertEquals(
            listOf(
                "高等数学,张三,3301,周三,第1-1节,1-16周",
                "大学英语,李四,A101,周五,第3-3节,2-8周"
            ),
            result.lines
        )
        assertAllParseable(result)
    }

    // ===================== ② 块内多行堆叠（课程名跨行） =====================

    @Test
    fun `块内多行堆叠时合并为一条时段`() {
        val lines = ArrayList<PositionedText>()
        lines += (0..6).map { headerLine(it) }
        lines += (1..4).map { sectionLabel(it) }

        lines += cellLine("★毛泽东思想和中国特色", 2, 2, 0)
        lines += cellLine("社会主义理论体系概论", 2, 2, 1)
        lines += cellLine("(0000000000)000000002", 2, 2, 2)
        lines += cellLine("2.0/王老师", 2, 2, 3)
        lines += cellLine("【1-4,6-16周】,3301", 2, 2, 4)

        val result = TimetableLayoutParser.parse(lines)

        assertEquals(TimetableLayoutParser.Mode.GRID, result.mode)
        assertEquals(1, result.lines.size)

        val expectedWeeks = WeekPatterns.fromRange(1, 4, OddEven.ALL) or
            WeekPatterns.fromRange(6, 16, OddEven.ALL)
        val parsed = TextImporter.parse(result.lines[0])
        assertEquals(1, parsed.items.size)
        assertEquals("毛泽东思想和中国特色社会主义理论体系概论", parsed.items[0].name)
        assertEquals("王老师", parsed.items[0].teacher)
        assertEquals("3301", parsed.items[0].location)
        assertEquals(3, parsed.items[0].dayOfWeek)
        assertEquals(2, parsed.items[0].startSection)
        assertEquals(2, parsed.items[0].endSection)
        assertTrue("周次掩码应为 1-4 与 6-16 的并集", expectedWeeks == parsed.items[0].weekPattern)
        assertAllParseable(result)
    }

    // ===================== ③ 无节次标签，仅靠 y 聚类 =====================

    @Test
    fun `无节次标签时按 y 聚类得到相对节次`() {
        val lines = ArrayList<PositionedText>()
        lines += (0..6).map { headerLine(it) }

        lines += cellLine("★高等数学", 1, 1, 0)
        lines += cellLine("2.0/张三", 1, 1, 1)
        lines += cellLine("【1-8周】,3301", 1, 1, 2)

        val result = TimetableLayoutParser.parse(lines)

        assertEquals(TimetableLayoutParser.Mode.GRID, result.mode)
        assertEquals(1, result.lines.size)
        val parsed = TextImporter.parse(result.lines[0])
        assertEquals(1, parsed.items.size)
        assertEquals("高等数学", parsed.items[0].name)
        assertEquals(2, parsed.items[0].dayOfWeek)
        assertEquals(1, parsed.items[0].startSection)
        assertEquals(1, parsed.items[0].endSection)
        assertAllParseable(result)
    }

    // ============ ④ 无星期表头且视觉行不可解析 → RAW + 空行 ============

    @Test
    fun `无星期表头且视觉行不可解析时返回 RAW 与空行`() {
        val lines = listOf(
            PositionedText("★高等数学", 200, 100, 320, 112),
            PositionedText("【1-16周】,3301", 200, 116, 320, 128),
            PositionedText("★大学英语", 420, 220, 540, 232)
        )

        val result = TimetableLayoutParser.parse(lines)

        // 这些视觉行里既没有「星期」也没有「节次」，交 TextImporter 必然全败。
        // Result.lines 的语义是「其中每一行都能被解析」，因此这里必须是 RAW + 空行，
        // 由界面回填原始文字 —— 而不是谎称「已重建 2 个时段」再让用户撞上「无法识别」。
        assertEquals(TimetableLayoutParser.Mode.RAW, result.mode)
        assertTrue(result.lines.isEmpty())
        assertEquals(lines.size, result.sourceLineCount)
    }

    // ============ ④b 无表头但视觉行自带星期与节次 → 可解析的 VISUAL_LINES ============

    @Test
    fun `无星期表头但视觉行自带星期与节次时降级为可解析的视觉行`() {
        val lines = listOf(
            PositionedText("高等数学 张三 教一101 周一 第1-2节 1-16周", 60, 100, 900, 112),
            PositionedText("大学英语 李四 A101 周三 第3-4节 2-8周", 60, 160, 900, 172)
        )

        val result = TimetableLayoutParser.parse(lines)

        assertEquals(TimetableLayoutParser.Mode.VISUAL_LINES, result.mode)
        assertEquals(2, result.lines.size)

        val parsed = TextImporter.parse(result.lines.joinToString("\n"))
        assertEquals(2, parsed.items.size)
        assertEquals("高等数学", parsed.items[0].name)
        assertEquals(1, parsed.items[0].dayOfWeek)
        assertEquals(1, parsed.items[0].startSection)
        assertEquals("大学英语", parsed.items[1].name)
        assertEquals(3, parsed.items[1].dayOfWeek)
        assertEquals(3, parsed.items[1].startSection)
        assertAllParseable(result)
    }

    // ===================== ⑤ 空输入 =====================

    @Test
    fun `空输入返回空结果且不抛异常`() {
        val result = TimetableLayoutParser.parse(emptyList())

        assertEquals(TimetableLayoutParser.Mode.RAW, result.mode)
        assertTrue(result.lines.isEmpty())
        assertEquals(0, result.sourceLineCount)
    }

    // ===================== ⑥ 一格两门课（★ 分格） =====================

    @Test
    fun `同一格内两个星号开头的课程被拆成两条`() {
        val lines = ArrayList<PositionedText>()
        lines += (0..6).map { headerLine(it) }
        lines += (1..4).map { sectionLabel(it) }

        lines += cellLine("★体育 / 甘老师 /", 2, 1, 0)
        lines += cellLine("(0000000000)000000000", 2, 1, 1)
        lines += cellLine("【7-17周】,东盟足球场", 2, 1, 2)

        lines += cellLine("★体育 / 詹老师 /", 2, 1, 3)
        lines += cellLine("(0000000000)000000001", 2, 1, 4)
        lines += cellLine("【6周】,东盟足球场", 2, 1, 5)

        val result = TimetableLayoutParser.parse(lines)

        assertEquals(TimetableLayoutParser.Mode.GRID, result.mode)
        assertEquals(2, result.lines.size)
        assertEquals("体育,甘老师,东盟足球场,周三,第1-1节,7-17周", result.lines[0])
        assertEquals("体育,詹老师,东盟足球场,周三,第1-1节,6周", result.lines[1])
        assertAllParseable(result)
    }

    // ===================== ⑦ 跨行合并单元格 =====================

    @Test
    fun `块跨两个节次行时得到节次范围`() {
        val lines = ArrayList<PositionedText>()
        lines += (0..6).map { headerLine(it) }
        lines += (1..4).map { sectionLabel(it) }

        lines += cellLine("★专业实践", 2, 1, 0)
        lines += cellLine("(0000000000)000000000", 2, 1, 1)
        lines += cellLine("3.0/孙老师", 2, 1, 2)
        for (order in 3..9) {
            lines += cellLine("实践环节说明$order", 2, 1, order)
        }
        // 周次行是每格的最后一行（与真实课表一致）
        lines += cellLine("【1-16周】,3301", 2, 1, 10)

        val result = TimetableLayoutParser.parse(lines)

        assertEquals(TimetableLayoutParser.Mode.GRID, result.mode)
        assertEquals(1, result.lines.size)
        val parsed = TextImporter.parse(result.lines[0])
        assertEquals(1, parsed.items.size)
        assertEquals("专业实践", parsed.items[0].name)
        assertEquals(1, parsed.items[0].startSection)
        assertEquals(2, parsed.items[0].endSection)
        assertAllParseable(result)
    }

    // ===================== ⑧ 块内文字残缺仍必须输出一行 =====================

    @Test
    fun `文字残缺时仍输出一行且周次回退为全周`() {
        val lines = ArrayList<PositionedText>()
        lines += (0..6).map { headerLine(it) }
        lines += (1..4).map { sectionLabel(it) }

        lines += cellLine("★", 2, 1, 0)
        lines += cellLine("###", 2, 1, 1)

        val result = TimetableLayoutParser.parse(lines)

        // 列与节次能定下来就必须产出行：宁可让用户改名字，也不能给 0 条
        assertEquals(TimetableLayoutParser.Mode.GRID, result.mode)
        assertEquals(1, result.lines.size)

        val parsed = TextImporter.parse(result.lines[0])
        assertEquals(1, parsed.items.size)
        assertEquals("未识别课程", parsed.items[0].name)
        assertEquals("", parsed.items[0].teacher)
        assertEquals("", parsed.items[0].location)
        assertEquals(3, parsed.items[0].dayOfWeek)
        assertEquals(1, parsed.items[0].startSection)
        assertEquals(1, parsed.items[0].endSection)
        val allWeeks = WeekPatterns.fromRange(1, WeekPatterns.MAX_WEEKS, OddEven.ALL)
        assertTrue("周次应回退为 1-30 周全周", allWeeks == parsed.items[0].weekPattern)
        assertAllParseable(result)
    }

    // ===================== ⑨ 周次容错：逗号被识别成点 =====================

    @Test
    fun `周次里的点号按逗号解析并保持端到端一致`() {
        val lines = ArrayList<PositionedText>()
        lines += (0..6).map { headerLine(it) }
        lines += (1..4).map { sectionLabel(it) }

        lines += cellLine("★中级财务会计", 2, 1, 0)
        lines += cellLine("(0000000000)000000003", 2, 1, 1)
        lines += cellLine("2.0/赵一", 2, 1, 2)
        // 真实数据：原文 `【1,4-6,17周】` 里的逗号被读成点，写成 `【1.4-6.17周】`
        lines += cellLine("【1.4-6.17周】,2117", 2, 1, 3)

        val result = TimetableLayoutParser.parse(lines)

        assertEquals(TimetableLayoutParser.Mode.GRID, result.mode)
        assertEquals(1, result.lines.size)

        val expected = WeekPatterns.of(1) or WeekPatterns.of(4, 5, 6) or WeekPatterns.of(17)
        val parsed = TextImporter.parse(result.lines[0])
        assertEquals(1, parsed.items.size)
        assertEquals("中级财务会计", parsed.items[0].name)
        assertEquals("赵一", parsed.items[0].teacher)
        assertEquals("2117", parsed.items[0].location)
        assertTrue("点号应等价于逗号：期望 = 1,4-6,17", expected == parsed.items[0].weekPattern)
        assertAllParseable(result)
    }

    // ===================== ⑩ ★ 丢失：靠「周次行是块尾」切块 =====================

    @Test
    fun `星号丢失时靠周次行切分同一格内的两门课`() {
        val lines = ArrayList<PositionedText>()
        lines += (0..6).map { headerLine(it) }
        lines += (1..4).map { sectionLabel(it) }

        lines += cellLine("★体育 / 甘老师 /", 2, 1, 0)
        lines += cellLine("(0000000000)000000000", 2, 1, 1)
        lines += cellLine("【7-17周】,东盟足球场", 2, 1, 2)

        lines += cellLine("体育 / 詹老师 /", 2, 1, 3) // ★ 丢失
        lines += cellLine("(0000000000)000000001", 2, 1, 4)
        lines += cellLine("【6周】,东盟足球场", 2, 1, 5)

        val result = TimetableLayoutParser.parse(lines)

        assertEquals(TimetableLayoutParser.Mode.GRID, result.mode)
        assertEquals(
            listOf(
                "体育,甘老师,东盟足球场,周三,第1-1节,7-17周",
                "体育,詹老师,东盟足球场,周三,第1-1节,6周"
            ),
            result.lines
        )
        assertAllParseable(result)
    }

    // ===================== ⑪ 表头缺列：星期号取自表头文字 =====================

    @Test
    fun `表头缺列时星期号仍取自表头文字`() {
        val lines = ArrayList<PositionedText>()
        lines += listOf(2, 3, 4).map { headerLine(it) } // 只有周三、周四、周五
        lines += (1..4).map { sectionLabel(it) }

        lines += cellLine("★高等数学", 2, 1, 0)
        lines += cellLine("2.0/张三", 2, 1, 1)
        lines += cellLine("【1-16周】,3301", 2, 1, 2)

        lines += cellLine("★大学英语", 4, 1, 0)
        lines += cellLine("2.0/李四", 4, 1, 1)
        lines += cellLine("【2-8周】,A101", 4, 1, 2)

        val result = TimetableLayoutParser.parse(lines)

        assertEquals(TimetableLayoutParser.Mode.GRID, result.mode)
        assertEquals(
            listOf(
                "高等数学,张三,3301,周三,第1-1节,1-16周",
                "大学英语,李四,A101,周五,第1-1节,2-8周"
            ),
            result.lines
        )
        assertAllParseable(result)
    }

    // ============ ⑫ 页眉页脚噪声（B1）与页眉大字号（H5）============

    @Test
    fun `页眉页脚噪声不入库且页眉大字号不影响切块`() {
        // 基线：同一格内两门课，第二门只有一行、不带 ★（只能靠 y 间隙切分）
        val baseline = ArrayList<PositionedText>()
        baseline += (0..6).map { headerLine(it) }
        baseline += (1..4).map { sectionLabel(it) }
        baseline += cellLine("★体育", 2, 1, 0)
        baseline += cellLine("(0000000000)000000000", 2, 1, 1)
        baseline += cellLine("3.0/甘老师", 2, 1, 2)
        baseline += cellLine("体育", 2, 1, 5) // 与上一行间隙 19px：大于正确阈值 12.8，小于被页眉抬高的阈值
        val baselineResult = TimetableLayoutParser.parse(baseline)

        // 噪声：页眉两行（高 24px，是格内行高 8px 的 3 倍）+ 页脚一行
        val noisy = ArrayList<PositionedText>(baseline)
        noisy += PositionedText("2026-2027学年第1学期 个人课程表", 60, 20, 1200, 44)
        noisy += PositionedText("学号：000000 学生姓名：某同学 所属班级：某班 总学分：29", 60, 48, 1200, 72)
        noisy += PositionedText("备注 公选课程：示例课程[网络课]【某老师（4-15）】", 60, 900, 1200, 924)

        val result = TimetableLayoutParser.parse(noisy)

        assertEquals(TimetableLayoutParser.Mode.GRID, result.mode)
        // 页眉大字号不得抬高切块阈值：两门课仍须是两条
        assertEquals(2, baselineResult.lines.size)
        assertEquals(2, result.lines.size)
        assertEquals(baselineResult.lines, result.lines)
        for (line in result.lines) {
            assertTrue("课程名不该含页眉页脚文字：$line",
                !line.contains("学期") && !line.contains("学号") && !line.contains("备注"))
        }
        assertAllParseable(result)
    }

    // ============ ⑬ 混合单双周（H1）============

    @Test
    fun `混合单双周的周次按片段生效`() {
        val lines = ArrayList<PositionedText>()
        lines += (0..6).map { headerLine(it) }
        lines += (1..4).map { sectionLabel(it) }

        lines += cellLine("★创业理论与实践", 2, 1, 0)
        lines += cellLine("(0000000000)000000005", 2, 1, 1)
        lines += cellLine("2.0/刘老师", 2, 1, 2)
        // 真实数据写法：逗号被读成点
        lines += cellLine("【1-3(单),6.8-9周】,12138", 2, 1, 3)

        val result = TimetableLayoutParser.parse(lines)

        assertEquals(TimetableLayoutParser.Mode.GRID, result.mode)
        assertEquals(1, result.lines.size)
        val parsed = TextImporter.parse(result.lines[0])
        assertEquals(1, parsed.items.size)
        val expected = WeekPatterns.of(1, 3, 6, 8, 9)
        assertTrue("1-3(单),6.8-9周 应为 1,3,6,8,9", expected == parsed.items[0].weekPattern)
        assertAllParseable(result)
    }

    // ============ ⑭ 块内文字溢出不得撑大节次范围（H2）============

    @Test
    fun `块内文字溢出到下一节中心时仍是单节`() {
        val lines = ArrayList<PositionedText>()
        lines += (0..6).map { headerLine(it) }
        lines += (1..4).map { sectionLabel(it) }

        val cx = columnCenters[2]
        lines += cellLine("★高等数学", 2, 1, 0)
        lines += cellLine("(0000000000)000000000", 2, 1, 1)
        lines += cellLine("2.0/张三", 2, 1, 2)
        for (order in 3..8) {
            lines += cellLine("溢出文字$order", 2, 1, order)
        }
        // 末行 bottom=190 已越过第 2 节的行中心（187），但主体仍在第 1 节
        lines += PositionedText("【1-16周】,3301", cx - 70, 182, cx + 70, 190)

        val result = TimetableLayoutParser.parse(lines)

        assertEquals(TimetableLayoutParser.Mode.GRID, result.mode)
        assertEquals(1, result.lines.size)
        val parsed = TextImporter.parse(result.lines[0])
        assertEquals(1, parsed.items.size)
        assertEquals("溢出不得把单节撑成连堂", 1, parsed.items[0].startSection)
        assertEquals(1, parsed.items[0].endSection)
        assertAllParseable(result)
    }

    // ============ ⑮ 周次行与地点行被拆成两行（H4）============

    @Test
    fun `周次行与地点行被拆开时不会多出假课`() {
        val lines = ArrayList<PositionedText>()
        lines += (0..6).map { headerLine(it) }
        lines += (1..4).map { sectionLabel(it) }

        lines += cellLine("★高等数学", 2, 1, 0)
        lines += cellLine("(0000000000)000000000", 2, 1, 1)
        lines += cellLine("2.0/张三", 2, 1, 2)
        lines += cellLine("【1-16周】", 2, 1, 3)
        lines += cellLine("3301", 2, 1, 4) // 地点被拆到下一行

        val result = TimetableLayoutParser.parse(lines)

        assertEquals(TimetableLayoutParser.Mode.GRID, result.mode)
        assertEquals(1, result.lines.size)
        val parsed = TextImporter.parse(result.lines[0])
        assertEquals(1, parsed.items.size)
        assertEquals("高等数学", parsed.items[0].name)
        assertEquals("3301", parsed.items[0].location)
        assertAllParseable(result)
    }

    // ============ ⑯ 表头缺一列时插值补列（H3）============

    @Test
    fun `表头缺列时插值补列而不是把内容归到邻列`() {
        val lines = ArrayList<PositionedText>()
        // 识别到周三、周四、周五、周日，缺周六
        lines += listOf(2, 3, 4, 6).map { headerLine(it) }
        lines += (1..4).map { sectionLabel(it) }

        // 内容落在「周六」的位置（cx=1000，正好是缺失列）
        lines += cellLine("★高等数学", 5, 1, 0)
        lines += cellLine("2.0/张三", 5, 1, 1)
        lines += cellLine("【1-16周】,3301", 5, 1, 2)

        val result = TimetableLayoutParser.parse(lines)

        assertEquals(TimetableLayoutParser.Mode.GRID, result.mode)
        assertEquals(1, result.lines.size)
        val parsed = TextImporter.parse(result.lines[0])
        assertEquals(1, parsed.items.size)
        assertEquals("插值列应为周六", 6, parsed.items[0].dayOfWeek)
        assertEquals("高等数学", parsed.items[0].name)
        assertAllParseable(result)
    }

    // ===================== ⑰ 真实课表 PDF 的两种结构（第四轮新增） =====================

    /**
     * 真实教务课表 PDF 里，下一门课的 `★` 会紧贴在上一门课的**地点后面**
     * （`…,6-16周】,3301★创业理论与实践`）。不把带 `★` 的行拆开，两门课会被粘成一块，
     * 而且地点会被读成 `3301★`——地点要进通知，必须干净。
     */
    @Test
    fun `行中间出现块首符号时两门课不得粘成一块且地点不带符号`() {
        val lines = ArrayList<PositionedText>()
        lines += (0..6).map { headerLine(it) }
        lines += (1..4).map { sectionLabel(it) }

        lines += cellLine("★计量经济学", 0, 1, 0)
        lines += cellLine("(0000)0001", 0, 1, 1)
        lines += cellLine("02/庄志晖", 0, 1, 2)
        lines += cellLine("【1-4,6-16周】,3301★创业理论与实践", 0, 1, 3)

        val result = TimetableLayoutParser.parse(lines)

        assertAllParseable(result)
        assertTrue("应识别出「计量经济学」：${result.lines}", result.lines.any { it.contains("计量经济学") })
        assertTrue(
            "应识别出「创业理论与实践」：${result.lines}",
            result.lines.any { it.contains("创业理论与实践") }
        )
        assertTrue("地点不得带块首符号：${result.lines}", result.lines.none { it.contains("3301★") })
        assertTrue("地点应是 3301：${result.lines}", result.lines.any { it.contains("3301") })
    }

    /**
     * 真实课表里课程名会**跨行**（`★中级财务会计模拟实` 的下一行是 `验及安全防护项目`），
     * 再下一行才是课序号 `05/卢亭薇`。课程名要逐行累加，但不能把课序号也累加进去。
     */
    @Test
    fun `课程名跨行时逐行累加且不被下一行的课序号污染`() {
        val lines = ArrayList<PositionedText>()
        lines += (0..6).map { headerLine(it) }
        lines += (1..4).map { sectionLabel(it) }

        lines += cellLine("★中级财务会计模拟实", 0, 1, 0)
        lines += cellLine("验及安全防护项目", 0, 1, 1)
        lines += cellLine("05/卢亭薇", 0, 1, 2)
        lines += cellLine("【1-4周】,3310", 0, 1, 3)

        val result = TimetableLayoutParser.parse(lines)

        assertAllParseable(result)
        assertTrue(
            "课程名应跨行合并：${result.lines}",
            result.lines.any { it.contains("中级财务会计模拟实验及安全防护项目") }
        )
        assertTrue("课序号不得污染课程名：${result.lines}", result.lines.none { it.contains("05卢亭薇") })
    }
}
