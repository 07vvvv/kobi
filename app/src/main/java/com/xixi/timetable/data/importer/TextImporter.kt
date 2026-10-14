package com.xixi.timetable.data.importer

import com.xixi.timetable.data.week.OddEven
import com.xixi.timetable.data.week.WeekPatterns

/**
 * 纯文本课表导入器：解析用户粘贴的课表文本，每行一个时段。
 *
 * 支持的写法（顺序无关，可混用逗号、制表符分隔）：
 * - 「高等数学,张老师,教一101,周一,1-2节,1-16周(单)」
 * - 「高等数学 周一 第1-2节 1-16周」
 * - 「大学物理 周三 3-4节 单周」
 *
 * 行内必须能识别出星期与节次，否则整行写入 [ImportResult.errors]；
 * 缺少周次时按「全部周次」处理。解析过程绝不抛异常。
 */
object TextImporter {

    /** 周次区间，可带单双标记，如「1-16周(单)」「第2-8周」 */
    private val WEEK_RANGE_RE = Regex("""第?\s*(\d{1,2})\s*[-~—～至]\s*(\d{1,2})\s*周\s*[（(]?\s*([单双])?\s*[)）]?""")

    /** 周次枚举，如「2,4,6周」「第2,4,6周」 */
    private val WEEK_LIST_RE = Regex("""第?\s*(\d{1,2}(?:\s*[,，、]\s*\d{1,2})+)\s*周""")

    /** 仅写单双周，如「单周」「双周」 */
    private val WEEK_ODD_EVEN_RE = Regex("""([单双])\s*周""")

    /** 节次区间，如「第1-2节」「3-4节」 */
    private val SECTION_RANGE_RE = Regex("""第?\s*(\d{1,2})\s*[-~—～至]\s*(\d{1,2})\s*节""")

    /** 单节次，如「第3节」 */
    private val SECTION_SINGLE_RE = Regex("""第?\s*(\d{1,2})\s*节""")

    /** 星期，如「周一」「星期三」「礼拜天」 */
    private val WEEKDAY_RE = Regex("""(?:周|星期|礼拜)\s*([一二三四五六日天1-7])""")

    /**
     * 字段分隔符：逗号、顿号、制表符与空格。
     * 契约 8.3 要求「逗号 / 制表符 / 空格混排均可」，故必须包含空格。
     * 代价：课程名内部若含空格会被一并拆开，建议用户用逗号分隔。
     */
    private val SEPARATOR_RE = Regex("""[,，、\t ]+""")

    /** 剥离「第」「周」「单」「双」等关键字后可能残留的孤立片段 */
    private val NOISE_RE = Regex("""[第周单双节]+""")

    fun parse(text: String): ImportResult {
        val items = ArrayList<ImportedCourse>()
        val errors = ArrayList<String>()
        val normalized = text.removePrefix("\uFEFF").replace("\r\n", "\n").replace("\r", "\n")
        val lines = normalized.split("\n")
        for ((index, rawLine) in lines.withIndex()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#") || line.startsWith("//")) continue
            val parsed = parseLine(line)
            if (parsed == null) {
                errors.add("第${index + 1}行：无法识别「$line」")
            } else {
                items.add(parsed)
            }
        }
        return ImportResult(items = items, errors = errors)
    }

    /** 解析单行；无法识别返回 null */
    private fun parseLine(line: String): ImportedCourse? {
        var rest = line
        var weekPattern = 0L
        var startSection = -1
        var endSection = -1
        var dayOfWeek = -1

        // 1) 周次：先区间，再枚举，最后单/双周
        val rangeMatches = WEEK_RANGE_RE.findAll(rest).toList()
        if (rangeMatches.isNotEmpty()) {
            val first = rangeMatches.first()
            val start = first.groupValues[1].toIntOrNull()
            val end = first.groupValues[2].toIntOrNull()
            if (start != null && end != null && start >= 1 && end >= start) {
                weekPattern = WeekPatterns.fromRange(start, end, oddEvenOf(first.groupValues[3]))
            }
            rest = removeAll(rest, rangeMatches)
        } else {
            val listMatches = WEEK_LIST_RE.findAll(rest).toList()
            if (listMatches.isNotEmpty()) {
                val weeks = listMatches.first().groupValues[1].split(SEPARATOR_RE)
                    .mapNotNull { it.trim().toIntOrNull() }
                    .filter { it in 1..WeekPatterns.MAX_WEEKS }
                if (weeks.isNotEmpty()) weekPattern = WeekPatterns.of(*weeks.toIntArray())
                rest = removeAll(rest, listMatches)
            } else {
                val oddEvenMatches = WEEK_ODD_EVEN_RE.findAll(rest).toList()
                if (oddEvenMatches.isNotEmpty()) {
                    val oddEven = oddEvenOf(oddEvenMatches.first().groupValues[1])
                    weekPattern = WeekPatterns.fromRange(1, WeekPatterns.MAX_WEEKS, oddEven)
                    rest = removeAll(rest, oddEvenMatches)
                }
            }
        }

        // 2) 节次：先区间，再单节
        val sectionRangeMatches = SECTION_RANGE_RE.findAll(rest).toList()
        if (sectionRangeMatches.isNotEmpty()) {
            val first = sectionRangeMatches.first()
            startSection = first.groupValues[1].toIntOrNull() ?: -1
            endSection = first.groupValues[2].toIntOrNull() ?: -1
            rest = removeAll(rest, sectionRangeMatches)
        } else {
            val singleMatches = SECTION_SINGLE_RE.findAll(rest).toList()
            if (singleMatches.isNotEmpty()) {
                startSection = singleMatches.first().groupValues[1].toIntOrNull() ?: -1
                endSection = startSection
                rest = removeAll(rest, singleMatches)
            }
        }

        // 3) 星期（放在最后，避免把「1-16周1-2节」里的「周1」误判成周一）
        val dayMatches = WEEKDAY_RE.findAll(rest).toList()
        if (dayMatches.isNotEmpty()) {
            dayOfWeek = weekdayValue(dayMatches.first().groupValues[1])
            rest = removeAll(rest, dayMatches)
        }

        if (dayOfWeek !in 1..7) return null
        if (startSection < 1) return null
        if (endSection < startSection) return null

        // 4) 剩余字段按位置解释：课程名,教师,地点
        val fields = rest.split(SEPARATOR_RE)
            .map { it.trim() }
            .filter { it.isNotEmpty() && !NOISE_RE.matches(it) }
        if (fields.isEmpty()) return null
        val name = fields[0]
        if (name.isEmpty()) return null

        val pattern = if (weekPattern != 0L) {
            weekPattern
        } else {
            WeekPatterns.fromRange(1, WeekPatterns.MAX_WEEKS, OddEven.ALL)
        }

        return ImportedCourse(
            name = name,
            teacher = fields.getOrElse(1) { "" },
            location = fields.getOrElse(2) { "" },
            dayOfWeek = dayOfWeek,
            startSection = startSection,
            endSection = endSection,
            weekPattern = pattern
        )
    }

    /** 把已识别的片段统一替换为分隔符；从后往前替换，避免区间位移 */
    private fun removeAll(source: String, matches: List<MatchResult>): String {
        var result = source
        for (match in matches.asReversed()) {
            result = result.replaceRange(match.range, ",")
        }
        return result
    }

    private fun oddEvenOf(token: String): OddEven = when (token) {
        "单" -> OddEven.ODD
        "双" -> OddEven.EVEN
        else -> OddEven.ALL
    }

    private fun weekdayValue(token: String): Int = when (token) {
        "一" -> 1
        "二" -> 2
        "三" -> 3
        "四" -> 4
        "五" -> 5
        "六" -> 6
        "日", "天" -> 7
        else -> token.toIntOrNull() ?: -1
    }
}
