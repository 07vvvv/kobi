package com.xixi.timetable.data.week

/** 单双周选项。 */
enum class OddEven { ALL, ODD, EVEN }

/**
 * 周次位掩码工具。
 *
 * 掩码为 Long，bit0 表示第 1 周，bit(N-1) 表示第 N 周；只使用低 [MAX_WEEKS] 位。
 * 掩码为 0 表示「没有任何周次」。
 */
object WeekPatterns {
    const val MAX_WEEKS: Int = 30

    /** 由若干周次序号（1-based）构造掩码 */
    fun of(vararg weeks: Int): Long {
        var mask = 0L
        for (week in weeks) {
            if (week in 1..MAX_WEEKS) {
                mask = mask or (1L shl (week - 1))
            }
        }
        return mask
    }

    /** 由「起止周 + 单双周」构造掩码 */
    fun fromRange(start: Int, end: Int, oddEven: OddEven): Long {
        val from = maxOf(start, 1)
        val to = minOf(end, MAX_WEEKS)
        if (from > to) return 0L
        var mask = 0L
        for (week in from..to) {
            val keep = when (oddEven) {
                OddEven.ALL -> true
                OddEven.ODD -> week % 2 == 1
                OddEven.EVEN -> week % 2 == 0
            }
            if (keep) {
                mask = mask or (1L shl (week - 1))
            }
        }
        return mask
    }

    /** 判断某周是否落在掩码内 */
    fun contains(mask: Long, week: Int): Boolean {
        if (week < 1 || week > MAX_WEEKS) return false
        return (mask and (1L shl (week - 1))) != 0L
    }

    /** 两个掩码是否有交集（冲突检测用） */
    fun intersects(a: Long, b: Long): Boolean = (a and b) != 0L

    /** 展开为周次列表，升序 */
    fun weeksOf(mask: Long): List<Int> {
        val result = ArrayList<Int>()
        for (week in 1..MAX_WEEKS) {
            if (contains(mask, week)) result.add(week)
        }
        return result
    }

    /** 人类可读摘要，如「1-16周(单)」「2,4,6周」 */
    fun describe(mask: Long): String {
        val weeks = weeksOf(mask)
        if (weeks.isEmpty()) return "无周次"
        if (weeks.size == 1) return "${weeks[0]}周"

        // 整段等差的单/双周（公差恒为 2 且至少 4 周）压缩为「a-b周(单/双)」
        if (weeks.size >= 4 && evenlySpaced(weeks, 2)) {
            val suffix = if (weeks[0] % 2 == 1) "单" else "双"
            return "${weeks.first()}-${weeks.last()}周($suffix)"
        }

        // 其余情况按连续段压缩：长度 >= 3 的连续段用「a-b」，长度 1~2 的用逗号列举
        val builder = StringBuilder()
        var index = 0
        while (index < weeks.size) {
            var last = index
            while (last + 1 < weeks.size && weeks[last + 1] == weeks[last] + 1) {
                last++
            }
            if (builder.isNotEmpty()) builder.append(',')
            if (last - index + 1 >= 3) {
                builder.append(weeks[index]).append('-').append(weeks[last])
            } else {
                for (i in index..last) {
                    if (i > index) builder.append(',')
                    builder.append(weeks[i])
                }
            }
            index = last + 1
        }
        return builder.append("周").toString()
    }

    /**
     * 解析中文周次文本，如「1-16」「1-16周」「1-16周(单)」「1,3,5」「单周」。
     *
     * 解析失败返回 null（由调用方写入错误列表，不抛异常）。
     * 仅供应导入器内部复用，不对外暴露。
     */
    internal fun parseText(text: String): Long? {
        var raw = text.trim()
        if (raw.isEmpty()) return null
        raw = raw.replace("（", "(").replace("）", ")")
            .replace("，", ",").replace("、", ",")
            .replace("－", "-").replace("—", "-").replace("~", "-").replace("～", "-")
            .replace("至", "-")
            .replace("第", "").replace("周", "")
            .replace(" ", "").replace("\t", "")
        var oddEven = OddEven.ALL
        if (raw.contains("单")) {
            oddEven = OddEven.ODD
        } else if (raw.contains("双")) {
            oddEven = OddEven.EVEN
        }
        raw = raw.replace("单", "").replace("双", "")
            .replace("(", "").replace(")", "")
        if (raw.isEmpty()) return null

        var mask = 0L
        var matched = false
        for (part in raw.split(',')) {
            if (part.isEmpty()) continue
            val dash = part.indexOf('-')
            if (dash > 0) {
                val start = part.substring(0, dash).toIntOrNull() ?: return null
                val end = part.substring(dash + 1).toIntOrNull() ?: return null
                if (start < 1 || start > MAX_WEEKS || end < start || end > MAX_WEEKS) return null
                mask = mask or fromRange(start, end, oddEven)
                matched = true
            } else {
                val week = part.toIntOrNull() ?: return null
                if (week < 1 || week > MAX_WEEKS) return null
                matched = true
                val keep = when (oddEven) {
                    OddEven.ALL -> true
                    OddEven.ODD -> week % 2 == 1
                    OddEven.EVEN -> week % 2 == 0
                }
                if (keep) mask = mask or of(week)
            }
        }
        if (!matched || mask == 0L) return null
        return mask
    }

    /** 判断列表是否为公差固定的等差数列 */
    private fun evenlySpaced(weeks: List<Int>, step: Int): Boolean {
        for (i in 1 until weeks.size) {
            if (weeks[i] - weeks[i - 1] != step) return false
        }
        return true
    }
}
