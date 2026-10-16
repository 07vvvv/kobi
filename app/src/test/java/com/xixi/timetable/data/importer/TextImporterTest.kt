package com.xixi.timetable.data.importer

import com.xixi.timetable.data.week.OddEven
import com.xixi.timetable.data.week.WeekPatterns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TextImporter 的 JVM 单元测试：锁住周次解析的多区间/多枚举合并，以及契约 9.3 的单双周口径。
 *
 * 只使用 public API（`TextImporter.parse`），不依赖 internal 可见性。
 * 所有姓名与编号均为虚构。
 */
class TextImporterTest {

    /** 整行导入后取出周次掩码，同时断言该行确实被成功解析 */
    private fun weekPatternOf(line: String): Long {
        val parsed = TextImporter.parse(line)
        assertEquals("整行应能被解析：$line", 1, parsed.items.size)
        return parsed.items[0].weekPattern
    }

    @Test
    fun `多区间周次必须逐段合并而不是只取最后一段`() {
        // 修复前 WEEK_RANGE_RE.find 只取到 6-16，1-4 整段丢失
        val expected = WeekPatterns.fromRange(1, 4, OddEven.ALL) or
            WeekPatterns.fromRange(6, 16, OddEven.ALL)
        val actual = weekPatternOf("高等数学,张三,3301,周一,第1-2节,1-4,6-16周")
        assertTrue("1-4,6-16周 应为两段的并集", expected == actual)
    }

    @Test
    fun `多段枚举周次同样全部合并`() {
        val expected = WeekPatterns.fromRange(1, 4, OddEven.ALL) or
            WeekPatterns.of(6) or WeekPatterns.of(8)
        val actual = weekPatternOf("大学英语,李四,A101,周二,第3-4节,1-4,6,8周")
        assertTrue("1-4,6,8周 应为并集", expected == actual)
    }

    @Test
    fun `三种单双周写法仍然等价`() {
        val expected = WeekPatterns.fromRange(1, 16, OddEven.ODD)
        val a = weekPatternOf("高等数学,张三,3301,周一,第1-2节,1-16周(单)")
        val b = weekPatternOf("高等数学,张三,3301,周一,第1-2节,1-16周,单周")
        val c = weekPatternOf("高等数学,张三,3301,周一,第1-2节,单周 1-16周")
        assertTrue("1-16周(单)", expected == a)
        assertTrue("1-16周,单周", expected == b)
        assertTrue("单周 1-16周", expected == c)
    }

    @Test
    fun `只有单双标记而没有数字片段时仍然生效`() {
        // 回归防线：文本里一个含「周」的数字片段都没有（行内只写「单周」、
        // 或 CSV 周次列填「单周」）时，单双周信息不得丢失、不得退化成 1-30 周全周。
        val odd = WeekPatterns.fromRange(1, WeekPatterns.MAX_WEEKS, OddEven.ODD)
        val even = WeekPatterns.fromRange(1, WeekPatterns.MAX_WEEKS, OddEven.EVEN)
        assertTrue("单独写「单周」应得到 1-30 单周", odd == weekPatternOf("大学物理,王五,B201,周三,第3-4节,单周"))
        assertTrue("单独写「双周」应得到 1-30 双周", even == weekPatternOf("大学物理,王五,B201,周三,第3-4节,双周"))
    }

    @Test
    fun `课程名或教师名里的单双字不得被当成周次标记`() {
        // 回归防线：旧实现用裸 contains("单")/contains("双") 判定整串单双周，
        // 于是「单片机原理」「双语教学」这类真实课程名会把整门课的周次错误地过滤成单周/双周。
        // 正确口径是「单/双」必须紧邻「周」才算标记。
        val all16 = WeekPatterns.fromRange(1, 16, OddEven.ALL)
        assertTrue(
            "单片机原理…1-16周 应为全周",
            all16 == weekPatternOf("单片机原理,张老师,3301,周一,第1-2节,1-16周")
        )
        assertTrue(
            "双语教学…1-8周 应为全周",
            WeekPatterns.fromRange(1, 8, OddEven.ALL) ==
                weekPatternOf("双语教学,李四,3302,周二,第3-4节,1-8周")
        )
        assertTrue(
            "教师名含「单」不影响整串口径",
            all16 == weekPatternOf("高等数学,单伟,3301,周一,第1-2节,1-16周")
        )
    }

    @Test
    fun `多区间周次不得在字段里残留碎片`() {
        // 回归防线：stripWeekFragments 未与解析口径对齐时，`1-4,6-16周` 里的 `1-4` 会残留成字段，
        // 被 classifyFields 的「最长片段」选成课程名（用户看到课程名变成「1-4」）。
        val parsed = TextImporter.parse("体育,王五,B201,周三,第1-2节,1-4,6-16周")
        assertEquals("整行应能被解析", 1, parsed.items.size)
        // 「体育」与「王五」都是 2 字，此处依赖 Kotlin 标准库 maxByOrNull「保留首个最大值」的语义
        // （实现里是严格小于才替换）。若将来有人把它改成 >= 比较，这条断言会翻成「王五」——
        // 那时要查的是 classifyFields 的候选顺序，而不是放宽这里的断言。
        assertEquals("课程名不得被周次碎片挤掉", "体育", parsed.items[0].name)
        assertEquals("王五", parsed.items[0].teacher)
        assertEquals("B201", parsed.items[0].location)
    }

    @Test
    fun `地点数字紧邻星期时不得被当成周次片段`() {
        // 真实故障（第三轮 CI 抓到）：周次正则允许数字与「周」之间有空格，
        // 于是空格分隔的行里「教一101 周一」被切成「教一1」+「,」+「一」：
        // 周次被误判成第 1 周、行内星期被吃掉、整行解析失败（用户看到「无法识别」）。
        val expected = WeekPatterns.fromRange(1, 16, OddEven.ALL)
        val parsed = TextImporter.parse("高等数学 张三 教一101 周一 第1-2节 1-16周")
        assertEquals("空格分隔且地点紧邻星期时整行必须可解析", 1, parsed.items.size)
        assertEquals("高等数学", parsed.items[0].name)
        assertEquals("张三", parsed.items[0].teacher)
        assertEquals("教一101", parsed.items[0].location)
        assertEquals(1, parsed.items[0].dayOfWeek)
        assertEquals(1, parsed.items[0].startSection)
        assertEquals(2, parsed.items[0].endSection)
        assertTrue("周次应为 1-16 周，而不是被截出来的第 1 周", expected == parsed.items[0].weekPattern)

        // 单个数字地点（A3）后紧跟星期：连「01」都没有，靠「周后不得紧跟中文星期」兜住
        val single = TextImporter.parse("高等数学 张三 A3 周二 第1-2节 1-16周")
        assertEquals("单个数字地点紧邻星期时整行必须可解析", 1, single.items.size)
        assertEquals(2, single.items[0].dayOfWeek)
        assertEquals("A3", single.items[0].location)
        assertTrue("周次应为 1-16 周", expected == single.items[0].weekPattern)
    }

    @Test
    fun `周次片段正则必须单一来源且不吃掉行内星期`() {
        // 锁住「TimetableGridParser 与 TextImporter 共用同一个片段正则」这一事实：
        // 第三轮曾在 GridParser 里留过一份副本，TextImporter 侧修断言后副本漏改，
        // 导致切块信号与地点锚点继续按旧口径误判。这里直接对正则本身断言。
        val text = "高等数学 张三 教一101 周一 第1-2节 1-16周"
        assertEquals(
            "片段应取到真正的周次，而不是「101 周一」里的「01 周」",
            "1-16周",
            TextImporter.WEEK_FRAGMENT_RE.find(text)?.value
        )
        assertEquals(
            "单个数字地点同理",
            "1-16周",
            TextImporter.WEEK_FRAGMENT_RE.find("高等数学 张三 A3 周二 第1-2节 1-16周")?.value
        )
    }
}
