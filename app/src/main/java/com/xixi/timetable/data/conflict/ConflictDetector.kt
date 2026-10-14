package com.xixi.timetable.data.conflict

import com.xixi.timetable.data.entity.SessionEntity
import com.xixi.timetable.data.week.WeekPatterns

/** 两条时段之间的冲突。 */
data class Conflict(
    val a: SessionEntity,
    val b: SessionEntity,
    val reason: String
)

/** 冲突检测：纯函数，不访问数据库。 */
object ConflictDetector {

    private val WEEKDAY_NAMES: Array<String> =
        arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

    /**
     * 判定标准：dayOfWeek 相同 && 节次区间重叠 && 周次掩码相交。
     * 区间重叠：a.startSection <= b.endSection && a.endSection >= b.startSection
     */
    fun findConflicts(sessions: List<SessionEntity>): List<Conflict> {
        val result = ArrayList<Conflict>()
        for (i in sessions.indices) {
            for (j in i + 1 until sessions.size) {
                val a = sessions[i]
                val b = sessions[j]
                if (a.dayOfWeek != b.dayOfWeek) continue
                if (!WeekPatterns.intersects(a.weekPattern, b.weekPattern)) continue
                val start = maxOf(a.startSection, b.startSection)
                val end = minOf(a.endSection, b.endSection)
                if (start > end) continue
                result.add(Conflict(a = a, b = b, reason = buildReason(a, b, start, end)))
            }
        }
        return result
    }

    /** 生成人类可读的冲突说明，例如「周一 第1-2节 周次重叠，最早重叠于第1周」 */
    private fun buildReason(a: SessionEntity, b: SessionEntity, start: Int, end: Int): String {
        val weeks = WeekPatterns.weeksOf(a.weekPattern and b.weekPattern)
        val firstWeek = if (weeks.isEmpty()) "" else "，最早重叠于第${weeks.first()}周"
        val names = "${a.location} ${b.location}".trim()
        val detail = if (names.isEmpty()) "" else "（$names）"
        return "${weekdayName(a.dayOfWeek)} 第${start}-${end}节 周次重叠$detail$firstWeek"
    }

    private fun weekdayName(dayOfWeek: Int): String =
        if (dayOfWeek in 1..7) WEEKDAY_NAMES[dayOfWeek - 1] else "星期$dayOfWeek"
}
