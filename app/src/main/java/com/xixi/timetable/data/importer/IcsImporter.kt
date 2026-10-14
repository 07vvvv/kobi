package com.xixi.timetable.data.importer

import com.xixi.timetable.data.week.WeekPatterns
import java.time.DateTimeException
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * .ics 日历导入器：按 VEVENT 提取课程。
 *
 * 契约只规定了「按 VEVENT 提取课程」，未规定时间到节次的换算规则，
 * 因此这里采用一张常见高校作息表（见 [SECTION_START_TIMES]）：
 * - 星期取 RRULE 的 BYDAY，缺失时取 DTSTART 所在星期；
 * - 起始节次取与 DTSTART 时间最接近的节次（偏差不超过 [SECTION_MATCH_TOLERANCE_MINUTES] 分钟）；
 * - 结束节次按 DTSTART~DTEND 的时长估算；
 * - 周次按 RRULE 的 COUNT / UNTIL / INTERVAL 推算，并假定 DTSTART 落在第 1 教学周。
 *
 * 任何无法识别的事件写入 [ImportResult.errors]，不抛异常。
 */
object IcsImporter {

    /** 默认作息表：第 1..12 节的起始时间 */
    private val SECTION_START_TIMES: List<LocalTime> = listOf(
        LocalTime.of(8, 0), LocalTime.of(8, 55), LocalTime.of(10, 0), LocalTime.of(10, 55),
        LocalTime.of(14, 0), LocalTime.of(14, 55), LocalTime.of(16, 0), LocalTime.of(16, 55),
        LocalTime.of(19, 0), LocalTime.of(19, 55), LocalTime.of(20, 50), LocalTime.of(21, 45)
    )

    /** 相邻节次起始时间的典型间隔（分钟），用于按时长估算结束节次 */
    private const val SECTION_SPAN_MINUTES: Long = 55L

    /** DTSTART 与节次起始时间的最大允许偏差（分钟），超出则视为无法识别 */
    private const val SECTION_MATCH_TOLERANCE_MINUTES: Long = 30L

    /** 事件缺少 DTEND 时的默认时长（分钟） */
    private const val FALLBACK_DURATION_MINUTES: Long = 50L

    /** RRULE 既无 COUNT 也无 UNTIL 时的兜底周数（一学期） */
    private const val DEFAULT_WEEKS: Int = 16

    /** 从 DESCRIPTION 中提取教师，如「教师：张三」 */
    private val TEACHER_RE = Regex("""(?:教师|老师|授课教师)\s*[:：]\s*([^,，;；\s]+)""")

    fun parse(text: String): ImportResult {
        val items = ArrayList<ImportedCourse>()
        val errors = ArrayList<String>()
        val events = readEvents(text)
        if (events.isEmpty()) {
            errors.add("未找到任何 VEVENT 事件")
            return ImportResult(items = items, errors = errors)
        }
        for (event in events) {
            val name = event.summary.trim()
            if (name.isEmpty()) {
                errors.add("有一个事件缺少 SUMMARY，已跳过")
                continue
            }
            val start = parseLocalDateTime(event.dtStart)
            if (start == null) {
                errors.add("「$name」的开始时间「${event.dtStart}」无法解析，已跳过")
                continue
            }
            val startSection = sectionOfTime(start.toLocalTime())
            if (startSection < 0) {
                errors.add("「$name」的上课时间 ${start.toLocalTime()} 无法映射到节次，已跳过")
                continue
            }
            val endSection = endSectionOf(startSection, start, parseLocalDateTime(event.dtEnd))
            val weekPattern = buildWeekPattern(event.rrule, start.toLocalDate())
            val days = if (event.byDay.isEmpty()) listOf(start.dayOfWeek.value) else event.byDay
            val teacher = TEACHER_RE.find(event.description)?.groupValues?.get(1)?.trim().orEmpty()
            for (day in days) {
                items.add(
                    ImportedCourse(
                        name = name,
                        teacher = teacher,
                        location = event.location.trim(),
                        dayOfWeek = day,
                        startSection = startSection,
                        endSection = endSection,
                        weekPattern = weekPattern
                    )
                )
            }
        }
        return ImportResult(items = items, errors = errors)
    }

    // ---------- 事件读取 ----------

    /** 事件原始属性 */
    private class RawEvent {
        var summary: String = ""
        var location: String = ""
        var description: String = ""
        var dtStart: String = ""
        var dtEnd: String = ""
        var rrule: String = ""
        var byDay: List<Int> = emptyList()
    }

    /** 按行读取全部 VEVENT；处理 ICS 的折行（续行以空格或制表符开头） */
    private fun readEvents(text: String): List<RawEvent> {
        val lines = ArrayList<String>()
        val normalized = text.removePrefix("\uFEFF").replace("\r\n", "\n").replace("\r", "\n")
        for (raw in normalized.split("\n")) {
            if ((raw.startsWith(" ") || raw.startsWith("\t")) && lines.isNotEmpty()) {
                val last = lines.size - 1
                lines[last] = lines[last] + raw.substring(1)
            } else {
                lines.add(raw)
            }
        }

        val events = ArrayList<RawEvent>()
        var current: RawEvent? = null
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            val upper = trimmed.uppercase()
            if (upper.startsWith("BEGIN:VEVENT")) {
                current = RawEvent()
            } else if (upper.startsWith("END:VEVENT")) {
                val finished = current
                if (finished != null) events.add(finished)
                current = null
            } else {
                val active = current
                if (active != null) applyLine(active, trimmed)
            }
        }
        return events
    }

    /** 解析一行属性；`NAME;PARAM=..:VALUE` */
    private fun applyLine(event: RawEvent, line: String) {
        val colon = line.indexOf(':')
        if (colon <= 0) return
        val head = line.substring(0, colon)
        val value = line.substring(colon + 1)
        when (head.substringBefore(';').trim().uppercase()) {
            "SUMMARY" -> event.summary = unescape(value)
            "LOCATION" -> event.location = unescape(value)
            "DESCRIPTION" -> event.description = unescape(value)
            "DTSTART" -> event.dtStart = value.trim()
            "DTEND" -> event.dtEnd = value.trim()
            "RRULE" -> {
                event.rrule = value.trim()
                event.byDay = extractByDay(value)
            }
        }
    }

    /** 提取 RRULE 中的 BYDAY，如「BYDAY=MO,WE」→ [1, 3] */
    private fun extractByDay(rrule: String): List<Int> {
        val result = ArrayList<Int>()
        for (part in rrule.uppercase().split(';')) {
            if (!part.startsWith("BYDAY=")) continue
            for (token in part.substringAfter('=').split(',')) {
                val day = when (token.trim().takeLast(2)) {
                    "MO" -> 1
                    "TU" -> 2
                    "WE" -> 3
                    "TH" -> 4
                    "FR" -> 5
                    "SA" -> 6
                    "SU" -> 7
                    else -> -1
                }
                if (day > 0) result.add(day)
            }
        }
        return result
    }

    /** 处理 ICS 文本转义 */
    private fun unescape(value: String): String = value
        .replace("\\,", ",")
        .replace("\\;", ";")
        .replace("\\n", " ")
        .replace("\\N", " ")
        .trim()

    // ---------- 时间与周次推算 ----------

    /** 解析 `yyyyMMdd` 或 `yyyyMMdd'T'HHmmss`（忽略末尾 Z，按本地时间处理） */
    private fun parseLocalDateTime(value: String): LocalDateTime? {
        val core = value.trim().removeSuffix("Z")
        if (core.length < 8) return null
        return try {
            val date = LocalDate.parse(core.substring(0, 8), DateTimeFormatter.BASIC_ISO_DATE)
            if (core.length >= 15 && core[8] == 'T') {
                val hour = core.substring(9, 11).toIntOrNull() ?: 0
                val minute = core.substring(11, 13).toIntOrNull() ?: 0
                val second = core.substring(13, 15).toIntOrNull() ?: 0
                LocalDateTime.of(date, LocalTime.of(hour, minute, second))
            } else {
                date.atStartOfDay()
            }
        } catch (e: DateTimeException) {
            null
        }
    }

    /** 只取日期部分（RRULE 的 UNTIL） */
    private fun parseDateOnly(value: String): LocalDate? {
        val core = value.trim().removeSuffix("Z")
        if (core.length < 8) return null
        return try {
            LocalDate.parse(core.substring(0, 8), DateTimeFormatter.BASIC_ISO_DATE)
        } catch (e: DateTimeException) {
            null
        }
    }

    /** 起始时间 → 节次（1-based）；无法匹配返回 -1 */
    private fun sectionOfTime(time: LocalTime): Int {
        var bestIndex = -1
        var bestDiff = Long.MAX_VALUE
        for (index in SECTION_START_TIMES.indices) {
            val diff = abs(Duration.between(SECTION_START_TIMES[index], time).toMinutes())
            if (diff < bestDiff) {
                bestDiff = diff
                bestIndex = index
            }
        }
        if (bestIndex < 0 || bestDiff > SECTION_MATCH_TOLERANCE_MINUTES) return -1
        return bestIndex + 1
    }

    /** 按上课时长估算结束节次 */
    private fun endSectionOf(startSection: Int, start: LocalDateTime, end: LocalDateTime?): Int {
        val minutes = if (end == null) {
            FALLBACK_DURATION_MINUTES
        } else {
            Duration.between(start, end).toMinutes()
        }
        if (minutes <= 0) return startSection
        val span = (minutes + SECTION_SPAN_MINUTES - 1) / SECTION_SPAN_MINUTES
        return minOf(startSection + maxOf(span, 1L).toInt() - 1, SECTION_START_TIMES.size)
    }

    /** 由 RRULE 推算周次掩码；假定 DTSTART 落在第 1 教学周 */
    private fun buildWeekPattern(rrule: String, startDate: LocalDate): Long {
        if (rrule.isBlank()) return WeekPatterns.of(1)

        var interval = 1
        var count = -1
        var until: LocalDate? = null
        for (part in rrule.uppercase().split(';')) {
            val key = part.substringBefore('=').trim()
            val value = part.substringAfter('=', "").trim()
            when (key) {
                "INTERVAL" -> interval = value.toIntOrNull() ?: 1
                "COUNT" -> count = value.toIntOrNull() ?: -1
                "UNTIL" -> until = parseDateOnly(value)
            }
        }
        if (interval < 1) interval = 1
        if (count <= 0) {
            val untilDate = until
            count = if (untilDate == null) {
                DEFAULT_WEEKS
            } else {
                val weeks = ChronoUnit.WEEKS.between(startDate, untilDate).toInt() + 1
                if (weeks < 1) 1 else weeks
            }
        }

        val weeks = ArrayList<Int>()
        var week = 1
        while (week <= WeekPatterns.MAX_WEEKS && weeks.size < count) {
            weeks.add(week)
            week += interval
        }
        return WeekPatterns.of(*weeks.toIntArray())
    }
}
