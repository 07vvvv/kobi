package com.xixi.timetable.data.schedule

import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SectionTimeTable 的 JVM 单元测试。
 *
 * 只覆盖**纯函数**部分：[SectionTimeTable.defaults] / [SectionTimeTable.encode] /
 * [SectionTimeTable.decode] / 按表查时间的两个 internal 重载。
 * `load` / `save` 依赖 `android.content.SharedPreferences` 与 `Context`，无法在纯 JVM 下执行，
 * 因此不在本测试范围内（见回报中的「未验证部分」）。
 */
class SectionTimeTableTest {

    private val time0800 = LocalTime.of(8, 0)
    private val time0845 = LocalTime.of(8, 45)

    /** 构造一张合法但非默认的表：每节 40 分钟、间隔 50 分钟，用于验证「读配置」路径 */
    private fun customTable(): List<SectionTime> {
        var start = LocalTime.of(9, 0)
        return (1..SectionTimeTable.MAX_SECTION).map { section ->
            val end = start.plusMinutes(40)
            val item = SectionTime(section, start, end)
            start = start.plusMinutes(50)
            item
        }
    }

    // ---- 默认表 ----

    @Test
    fun `默认表必须是契约 8_7 冻结的 12 节`() {
        val table = SectionTimeTable.defaults()
        assertEquals("必须是 12 节", SectionTimeTable.MAX_SECTION, table.size)
        assertEquals("节次必须是 1..12", (1..12).toList(), table.map { it.section })
        assertEquals(time0800, table[0].start)
        assertEquals("第 12 节起始必须是 21:45（契约 8.7 表格）", LocalTime.of(21, 45), table[11].start)
        assertTrue("每节 45 分钟", table.all { it.start.plusMinutes(45) == it.end })
        assertTrue("下课时间必须晚于上课时间", table.all { it.end.isAfter(it.start) })
    }

    // ---- encode / decode 往返 ----

    @Test
    fun `默认表 encode 后 decode 必须原样还原`() {
        val table = SectionTimeTable.defaults()
        assertEquals(table, SectionTimeTable.decode(SectionTimeTable.encode(table)))
    }

    @Test
    fun `自定义表 encode 后 decode 必须原样还原`() {
        val table = customTable()
        val restored = SectionTimeTable.decode(SectionTimeTable.encode(table))
        assertEquals(table, restored)
        assertEquals(LocalTime.of(9, 0), restored.first().start)
        assertEquals(LocalTime.of(9, 40), restored.first().end)
    }

    @Test
    fun `encode 必须是契约约定的 分号分隔 HH_mm-HH_mm 格式且按节次升序`() {
        val encoded = SectionTimeTable.encode(
            listOf(
                SectionTime(2, LocalTime.of(8, 55), LocalTime.of(9, 40)),
                SectionTime(1, time0800, time0845)
            )
        )
        assertEquals("1=08:00-08:45;2=08:55-09:40", encoded)
    }

    // ---- 脏数据回退 ----

    @Test
    fun `decode 对 null 与空白回退默认表`() {
        val expected = SectionTimeTable.defaults()
        assertEquals(expected, SectionTimeTable.decode(null))
        assertEquals(expected, SectionTimeTable.decode(""))
        assertEquals(expected, SectionTimeTable.decode("   "))
    }

    @Test
    fun `decode 对完全无法解析的字符串回退默认表`() {
        val expected = SectionTimeTable.defaults()
        assertEquals(expected, SectionTimeTable.decode("hello world"))
        assertEquals(expected, SectionTimeTable.decode("1=08:00"))
        assertEquals(expected, SectionTimeTable.decode("08:00-08:45"))
        assertEquals(expected, SectionTimeTable.decode("=08:00-08:45"))
    }

    @Test
    fun `decode 对节次越界回退默认表`() {
        val expected = SectionTimeTable.defaults()
        assertEquals(expected, SectionTimeTable.decode("0=08:00-08:45"))
        assertEquals(expected, SectionTimeTable.decode("13=08:00-08:45"))
        assertEquals(expected, SectionTimeTable.decode("-1=08:00-08:45"))
    }

    @Test
    fun `decode 对时间颠倒或相等回退默认表`() {
        val expected = SectionTimeTable.defaults()
        val reversed = (SectionTimeTable.defaults().drop(1) +
            SectionTime(1, LocalTime.of(9, 0), LocalTime.of(8, 0)))
        assertEquals("颠倒的时间必须整体回退", expected, SectionTimeTable.decode(SectionTimeTable.encode(reversed)))

        val same = (SectionTimeTable.defaults().drop(1) +
            SectionTime(1, LocalTime.of(8, 0), LocalTime.of(8, 0)))
        assertEquals("零长度的课必须整体回退", expected, SectionTimeTable.decode(SectionTimeTable.encode(same)))
    }

    @Test
    fun `decode 对时间格式错误回退默认表`() {
        val expected = SectionTimeTable.defaults()
        val broken = SectionTimeTable.encode(customTable()).replaceFirst("09:00", "9点")
        assertEquals(expected, SectionTimeTable.decode(broken))
        assertEquals(expected, SectionTimeTable.decode("1=08:00-08:45;2=8:55-09:40;3=10:00-10:45"))
    }

    @Test
    fun `decode 对节次缺号回退默认表`() {
        val expected = SectionTimeTable.defaults()
        // 只有 1..11 节（缺第 12 节）
        val missing = SectionTimeTable.encode(customTable().dropLast(1))
        assertTrue("前置条件：缺号数据本身不是空串", missing.isNotEmpty())
        assertEquals(expected, SectionTimeTable.decode(missing))
    }

    @Test
    fun `decode 对节次重复回退默认表`() {
        val expected = SectionTimeTable.defaults()
        val duplicated = SectionTimeTable.encode(customTable()) + ";1=09:00-09:40"
        assertEquals(expected, SectionTimeTable.decode(duplicated))
    }

    @Test
    fun `decode 允许节次乱序但补回升序`() {
        val table = customTable()
        val shuffled = SectionTimeTable.encode(table.reversed())
        assertEquals(table, SectionTimeTable.decode(shuffled))
    }

    // ---- 越界节次夹紧（不抛异常）----

    @Test
    fun `越界节次被夹紧而不是抛异常`() {
        val table = customTable()
        val first = table.first()
        val last = table.last()

        assertEquals("0 节夹紧到第 1 节", first.start, SectionTimeTable.startOf(table, 0))
        assertEquals("-99 节夹紧到第 1 节", first.start, SectionTimeTable.startOf(table, -99))
        assertEquals("13 节夹紧到第 12 节", last.start, SectionTimeTable.startOf(table, 13))
        assertEquals("999 节夹紧到第 12 节", last.start, SectionTimeTable.startOf(table, 999))

        assertEquals(first.end, SectionTimeTable.endOf(table, 0))
        assertEquals(last.end, SectionTimeTable.endOf(table, SectionTimeTable.MAX_SECTION + 1))
        assertEquals(last.end, SectionTimeTable.endOf(table, Int.MAX_VALUE))
    }

    @Test
    fun `按表查时间命中合法节次时返回配置值`() {
        val table = customTable()
        assertEquals(LocalTime.of(9, 0), SectionTimeTable.startOf(table, 1))
        assertEquals(LocalTime.of(9, 40), SectionTimeTable.endOf(table, 1))
        assertEquals(table[2].start, SectionTimeTable.startOf(table, 3))
        assertEquals(table[2].end, SectionTimeTable.endOf(table, 3))
    }

    @Test
    fun `空表查询也不抛异常（兜底到默认表首节）`() {
        val fallback = SectionTimeTable.defaults().first()
        assertEquals(fallback.start, SectionTimeTable.startOf(emptyList(), 5))
        assertEquals(fallback.end, SectionTimeTable.endOf(emptyList(), 5))
    }
}
