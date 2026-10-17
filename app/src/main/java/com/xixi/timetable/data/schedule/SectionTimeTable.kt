package com.xixi.timetable.data.schedule

import android.content.Context
import android.content.SharedPreferences
import com.xixi.timetable.notify.ReminderScheduler
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** 一节课的起止时间；[section] 为节次（1-based，落在 1..[SectionTimeTable.MAX_SECTION]） */
data class SectionTime(
    val section: Int,
    val start: LocalTime,
    val end: LocalTime
)

/**
 * 节次起止时间表：用户可以在设置里调整每节课的上下课时间，通知调度据此算出真实上课时刻。
 *
 * 存储：SharedPreferences（契约明确禁止 DataStore），文件名 `kobi_settings`
 * （与 notify 的 `NotifyPrefs.PREFS_NAME` 保持一致），key `section_times`，
 * 格式 `1=08:00-08:45;2=08:55-09:40;…`。
 *
 * 容错原则：**任何脏数据都回退到 [defaults]，绝不抛异常** —— `null`、空串、格式错、
 * 节次缺号或重复、节次越界、下课时间不晚于上课时间，任一不满足就整体回退。
 *
 * [encode] / [decode] 以及按表查询的两个重载都是**纯函数**（不碰 Android API），
 * 因此可以在纯 JVM 单测里直接覆盖；[load] / [save] 只是它们的薄封装。
 */
object SectionTimeTable {

    /** 支持的节次数（契约 8.7 冻结的 1–12 节） */
    const val MAX_SECTION: Int = 12

    private const val PREFS_NAME = "kobi_settings"
    private const val KEY_SECTION_TIMES = "section_times"

    private const val CHUNK_SEPARATOR = ';'
    private const val SECTION_SEPARATOR = '='
    private const val RANGE_SEPARATOR = '-'

    /** 默认每节课时长（契约 8.7：45 分钟） */
    private const val DEFAULT_LESSON_MINUTES = 45L

    private val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    /** 契约 8.7 冻结的 1–12 节起始时间 */
    private val DEFAULT_STARTS: List<LocalTime> = listOf(
        LocalTime.of(8, 0),
        LocalTime.of(8, 55),
        LocalTime.of(10, 0),
        LocalTime.of(10, 55),
        LocalTime.of(14, 0),
        LocalTime.of(14, 55),
        LocalTime.of(16, 0),
        LocalTime.of(16, 55),
        LocalTime.of(19, 0),
        LocalTime.of(19, 55),
        LocalTime.of(20, 50),
        LocalTime.of(21, 45)
    )

    /** 契约 8.7 冻结的默认作息表：1–12 节，每节 45 分钟 */
    fun defaults(): List<SectionTime> = DEFAULT_STARTS.mapIndexed { index, start ->
        SectionTime(
            section = index + 1,
            start = start,
            end = start.plusMinutes(DEFAULT_LESSON_MINUTES)
        )
    }

    /** 读取用户配置；未配置或数据损坏时返回 [defaults]，绝不抛异常 */
    fun load(context: Context): List<SectionTime> {
        val raw = try {
            prefs(context).getString(KEY_SECTION_TIMES, null)
        } catch (t: Throwable) {
            // 存储不可用（或运行在无 Android 实现的测试环境）时按「未配置」处理
            null
        }
        return decode(raw)
    }

    /**
     * 保存配置，并**立即重排提醒**让新时间当场生效（契约要求）。
     *
     * 写入前先归一化：越界 / 颠倒 / 重复的条目会被丢弃，缺号则整体回退到默认表，
     * 因此存储里不会留下半截的表。
     */
    fun save(context: Context, times: List<SectionTime>) {
        try {
            val editor = prefs(context).edit()
            editor.putString(KEY_SECTION_TIMES, encode(normalized(times)))
            editor.apply()
        } catch (t: Throwable) {
            // 存储写入失败也不能让调用方崩溃；下面的重排仍然照常执行
        }
        ReminderScheduler.rescheduleAll(context)
    }

    /** 第 [section] 节的开始时间（读配置）；越界节次夹紧到 1..[MAX_SECTION]，不抛异常 */
    fun startOf(context: Context, section: Int): LocalTime = startOf(load(context), section)

    /** 第 [section] 节的结束时间（读配置）；越界节次夹紧到 1..[MAX_SECTION]，不抛异常 */
    fun endOf(context: Context, section: Int): LocalTime = endOf(load(context), section)

    /**
     * 从**已加载的表**里取开始时间。越界节次一律**夹紧**（小于 1 取第 1 节，大于
     * [MAX_SECTION] 取第 [MAX_SECTION] 节），永不抛异常。纯函数，便于 JVM 单测。
     */
    internal fun startOf(table: List<SectionTime>, section: Int): LocalTime {
        val clamped = section.coerceIn(1, MAX_SECTION)
        return table.firstOrNull { it.section == clamped }?.start
            ?: table.firstOrNull()?.start
            ?: DEFAULT_STARTS.first()
    }

    /** 同 [startOf]，取的是结束时间 */
    internal fun endOf(table: List<SectionTime>, section: Int): LocalTime {
        val clamped = section.coerceIn(1, MAX_SECTION)
        return table.firstOrNull { it.section == clamped }?.end
            ?: table.firstOrNull()?.end
            ?: DEFAULT_STARTS.first().plusMinutes(DEFAULT_LESSON_MINUTES)
    }

    /** 序列化为 `1=08:00-08:45;2=08:55-09:40;…`（按节次升序） */
    internal fun encode(times: List<SectionTime>): String {
        val builder = StringBuilder()
        for (time in times.sortedBy { it.section }) {
            if (builder.isNotEmpty()) builder.append(CHUNK_SEPARATOR)
            builder.append(time.section)
                .append(SECTION_SEPARATOR)
                .append(TIME_FORMATTER.format(time.start))
                .append(RANGE_SEPARATOR)
                .append(TIME_FORMATTER.format(time.end))
        }
        return builder.toString()
    }

    /**
     * 反序列化；**任何**脏数据都回退到 [defaults]：
     * `null` / 空串 / 任一分段格式错 / 节次越界或重复 / 节次缺号（不足 [MAX_SECTION] 条）/
     * 时间格式错 / 下课时间不晚于上课时间。
     */
    internal fun decode(raw: String?): List<SectionTime> {
        if (raw.isNullOrBlank()) return defaults()
        return try {
            val parsed = HashMap<Int, SectionTime>()
            for (chunk in raw.split(CHUNK_SEPARATOR)) {
                val item = parseChunk(chunk) ?: return defaults()
                if (parsed.put(item.section, item) != null) return defaults() // 节次重复
            }
            if (parsed.size != MAX_SECTION) return defaults() // 缺号
            (1..MAX_SECTION).map { section ->
                parsed[section] ?: return defaults()
            }
        } catch (t: Throwable) {
            defaults()
        }
    }

    /** 解析一段 `3=10:00-10:45`；任何不合法都返回 null（由 [decode] 决定整体回退） */
    private fun parseChunk(chunk: String): SectionTime? {
        val sectionIndex = chunk.indexOf(SECTION_SEPARATOR)
        if (sectionIndex <= 0) return null

        val section = chunk.substring(0, sectionIndex).trim().toIntOrNull() ?: return null
        if (section < 1 || section > MAX_SECTION) return null

        val range = chunk.substring(sectionIndex + 1).trim()
        val rangeIndex = range.indexOf(RANGE_SEPARATOR)
        if (rangeIndex <= 0) return null

        val start = parseTime(range.substring(0, rangeIndex)) ?: return null
        val end = parseTime(range.substring(rangeIndex + 1)) ?: return null
        // 下课必须晚于上课；相等或颠倒都视为脏数据（跨零点的课不在本应用场景内）
        if (!end.isAfter(start)) return null

        return SectionTime(section, start, end)
    }

    private fun parseTime(text: String): LocalTime? = try {
        LocalTime.parse(text.trim(), TIME_FORMATTER)
    } catch (t: Throwable) {
        null
    }

    /**
     * 归一化：丢掉越界 / 时间颠倒的条目，同节次重复时保留最后一条；
     * 若最终凑不齐 1..[MAX_SECTION]，返回 [defaults]。
     */
    private fun normalized(times: List<SectionTime>): List<SectionTime> {
        val bySection = times
            .filter { it.section >= 1 && it.section <= MAX_SECTION && it.end.isAfter(it.start) }
            .associateBy({ it.section }, { it })
        if (bySection.size != MAX_SECTION) return defaults()
        return (1..MAX_SECTION).map { section -> bySection.getValue(section) }
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
