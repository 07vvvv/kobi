package com.xixi.timetable.ui.common

import com.xixi.timetable.data.week.WeekPatterns
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** 设置项存储文件名，与提醒层共用同一份约定（契约 8.6）。 */
internal const val SETTINGS_PREFS_NAME = "kobi_settings"

/** 「第 1 教学周周一 00:00」毫秒时间戳的存储键（契约 8.6）。 */
internal const val KEY_TERM_START_MILLIS = "term_start_millis"

/**
 * 星期短标签，dayOfWeek 取值 1..7（1 = 周一）。
 */
internal fun dayLabel(dayOfWeek: Int): String = when (dayOfWeek) {
    1 -> "周一"
    2 -> "周二"
    3 -> "周三"
    4 -> "周四"
    5 -> "周五"
    6 -> "周六"
    7 -> "周日"
    else -> "第${dayOfWeek}天"
}

/**
 * 节次区间文案，如「第1-2节」。
 */
internal fun sectionRangeText(startSection: Int, endSection: Int): String =
    if (startSection == endSection) {
        "第${startSection}节"
    } else {
        "第${startSection}-${endSection}节"
    }

/**
 * 今天的星期序号（1..7）。
 */
internal fun todayOfWeek(): Int = LocalDate.now().dayOfWeek.value

/**
 * 明天的星期序号（1..7），周日之后回到周一。
 */
internal fun tomorrowOfWeek(): Int {
    val today = todayOfWeek()
    return if (today >= 7) 1 else today + 1
}

/**
 * 由「第 1 教学周周一 00:00」推算今天属于第几教学周（1-based）。
 *
 * 未设置（<= 0）时返回第 1 周；学期尚未开始时也夹取到第 1 周；超过最大周数则夹取到最大周数。
 */
internal fun weekIndexFromTermStart(
    termStartMillis: Long,
    nowMillis: Long = System.currentTimeMillis()
): Int {
    if (termStartMillis <= 0L) return 1
    val zone = ZoneId.systemDefault()
    val startDate = Instant.ofEpochMilli(termStartMillis).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
    val days = ChronoUnit.DAYS.between(startDate, today)
    val index = Math.floorDiv(days, 7L).toInt() + 1
    return index.coerceIn(1, WeekPatterns.MAX_WEEKS)
}
